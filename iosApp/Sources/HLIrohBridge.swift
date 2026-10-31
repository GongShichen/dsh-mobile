import Foundation
import HLShared
import IrohLib

private let mobileALPN = Data("dsh-mobile/1".utf8)

private extension KotlinByteArray {
    func asData() -> Data {
        Data((0..<Int(size)).map { UInt8(bitPattern: get(index: Int32($0))) })
    }
}

private extension Data {
    func asKotlinBytes() -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() { result.set(index: Int32(index), value: Int8(bitPattern: byte)) }
        return result
    }
}

private final class HLIrohOperation: NSObject, HLNativeCancellation, @unchecked Sendable {
    private let task: Task<Void, Never>
    init(_ action: @escaping @Sendable () async -> Void) {
        task = Task { await action() }
        super.init()
    }
    func cancel() { task.cancel() }
}

private struct HLTransportFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Official Swift iroh binding, injected into the KMP shared transport adapter.
final class HLIrohBridge: NSObject, HLNativeIrohBridge, @unchecked Sendable {
    func connect(endpointId: String, ticket: String?, seed: KotlinByteArray, callback: HLNativeConnectCallback) -> HLNativeCancellation {
        let key = seed.asData()
        return HLIrohOperation {
            var endpoint: IrohLib.Endpoint?
            var connection: IrohLib.Connection?
            do {
                guard key.count == 32 else { throw HLTransportFailure(message: "Invalid device identity") }
                let expected = try IrohLib.EndpointId.fromString(s: endpointId)
                let address: IrohLib.EndpointAddr
                if let ticket {
                    address = try IrohLib.EndpointTicket.fromString(str: ticket).endpointAddr()
                    guard address.id() == expected else { throw HLTransportFailure(message: "Ticket identity mismatch") }
                } else {
                    address = IrohLib.EndpointAddr(id: expected, relayUrl: nil, addresses: [])
                }
                let ep = try await IrohLib.Endpoint.bind(options: EndpointOptions(secretKey: key, alpns: [mobileALPN]))
                endpoint = ep
                try Task.checkCancellation()
                let conn = try await ep.connect(addr: address, alpn: mobileALPN)
                connection = conn
                guard conn.remoteId() == expected else { throw HLTransportFailure(message: "Desktop identity verification failed") }
                try Task.checkCancellation()
                callback.success(connection: HLSwiftPeerConnection(endpoint: ep, connection: conn))
            } catch {
                try? connection?.close(errorCode: 1, reason: Data("connection cancelled".utf8))
                if let endpoint {
                    await Task.detached { try? await endpoint.close() }.value
                }
                callback.failure(message: error.localizedDescription)
            }
        }
    }
}

private final class HLSwiftPathCallback: IrohLib.PathChangeCallback, @unchecked Sendable {
    let callback: HLNativePathCallback
    init(_ callback: HLNativePathCallback) { self.callback = callback }
    func onChange(paths: [IrohLib.PathSnapshot]) async throws {
        callback.changed(path: Self.name(paths))
    }
    static func name(_ paths: [IrohLib.PathSnapshot]) -> String {
        guard let selected = paths.first(where: { $0.isSelected }) else { return "CONNECTING" }
        if selected.isRelay { return "RELAY" }
        return selected.isIp ? "DIRECT" : "CONNECTING"
    }
}

private final class HLSwiftPathWatch: NSObject, HLNativeCancellation, @unchecked Sendable {
    private let watch: IrohLib.WatchHandle
    private let callbackOwner: HLSwiftPathCallback
    private let closedTask: Task<Void, Never>
    init(connection: IrohLib.Connection, callback: HLNativePathCallback) {
        let owner = HLSwiftPathCallback(callback)
        callbackOwner = owner
        watch = connection.watchPaths(callback: owner)
        callback.changed(path: HLSwiftPathCallback.name(connection.paths()))
        closedTask = Task {
            _ = await connection.closed()
            if !Task.isCancelled { callback.changed(path: "CLOSED") }
        }
        super.init()
    }
    func cancel() {
        closedTask.cancel()
        let watch = watch
        Task { await watch.stop() }
    }
    deinit { closedTask.cancel() }
}

private final class HLSwiftPeerConnection: NSObject, HLNativePeerConnection, @unchecked Sendable {
    private let endpoint: IrohLib.Endpoint
    private let connection: IrohLib.Connection
    private let lock = NSLock()
    private var disposed = false
    private var watches: [HLSwiftPathWatch] = []
    private var streams: [UUID: HLSwiftPeerStream] = [:]

    init(endpoint: IrohLib.Endpoint, connection: IrohLib.Connection) {
        self.endpoint = endpoint
        self.connection = connection
        super.init()
    }
    func remoteEndpointId() -> String { connection.remoteId().description }
    func openBi(callback: HLNativeStreamCallback) -> HLNativeCancellation { stream(callback, open: true) }
    func acceptBi(callback: HLNativeStreamCallback) -> HLNativeCancellation { stream(callback, open: false) }
    private func stream(_ callback: HLNativeStreamCallback, open: Bool) -> HLNativeCancellation {
        HLIrohOperation { [self] in
            var wrapped: HLSwiftPeerStream?
            do {
                let native = try await (open ? connection.openBi() : connection.acceptBi())
                let id = UUID()
                let value = HLSwiftPeerStream(native: native, onDispose: { [weak self] in self?.forgetStream(id) })
                wrapped = value
                guard keepStream(value, id: id) else { throw HLTransportFailure(message: "Connection is closed") }
                try Task.checkCancellation()
                callback.success(stream: value)
            } catch {
                wrapped?.dispose()
                callback.failure(message: error.localizedDescription)
            }
        }
    }
    private func keepStream(_ value: HLSwiftPeerStream, id: UUID) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !disposed else { return false }
        streams[id] = value
        return true
    }
    private func forgetStream(_ id: UUID) {
        lock.lock(); defer { lock.unlock() }
        streams.removeValue(forKey: id)
    }
    func watchPath(callback: HLNativePathCallback) -> HLNativeCancellation {
        let watch = HLSwiftPathWatch(connection: connection, callback: callback)
        lock.lock()
        if disposed { lock.unlock(); watch.cancel() } else { watches.append(watch); lock.unlock() }
        return watch
    }
    func close(callback: HLNativeUnitCallback) -> HLNativeCancellation {
        HLIrohOperation { [self] in
            dispose()
            do { try await endpoint.close(); callback.success() }
            catch { callback.failure(message: error.localizedDescription) }
        }
    }
    func dispose() {
        lock.lock()
        guard !disposed else { lock.unlock(); return }
        disposed = true
        let oldStreams = Array(streams.values)
        streams.removeAll()
        let oldWatches = watches
        watches.removeAll()
        lock.unlock()
        oldWatches.forEach { $0.cancel() }
        try? connection.close(errorCode: 0, reason: Data("closed".utf8))
        oldStreams.forEach { $0.dispose() }
        let endpoint = endpoint
        Task.detached { try? await endpoint.close() }
    }
    deinit { dispose() }
}

private final class HLSwiftPeerStream: NSObject, HLNativePeerStream, @unchecked Sendable {
    private let send: IrohLib.SendStream
    private let receive: IrohLib.RecvStream
    private let onDispose: @Sendable () -> Void
    private let lock = NSLock()
    private var disposed = false

    init(native: IrohLib.BiStream, onDispose: @escaping @Sendable () -> Void) {
        send = native.send()
        receive = native.recv()
        self.onDispose = onDispose
        super.init()
    }
    func readExact(count: Int32, callback: HLNativeBytesCallback) -> HLNativeCancellation {
        HLIrohOperation { [self] in
            do {
                guard (0...1_048_576).contains(count) else { throw HLTransportFailure(message: "Invalid read size") }
                let data = try await receive.readExact(size: UInt32(count))
                try Task.checkCancellation()
                callback.success(bytes: data.asKotlinBytes())
            } catch {
                if Task.isCancelled { dispose() }
                callback.failure(message: error.localizedDescription)
            }
        }
    }
    func writeAll(bytes: KotlinByteArray, callback: HLNativeUnitCallback) -> HLNativeCancellation {
        let data = bytes.asData()
        return operation(callback) { [send] in try await send.writeAll(buf: data) }
    }
    func finish(callback: HLNativeUnitCallback) -> HLNativeCancellation {
        operation(callback) { [send] in try await send.finish() }
    }
    func close(callback: HLNativeUnitCallback) -> HLNativeCancellation {
        HLIrohOperation { [self] in dispose(); callback.success() }
    }
    private func operation(_ callback: HLNativeUnitCallback, action: @escaping @Sendable () async throws -> Void) -> HLNativeCancellation {
        HLIrohOperation { [self] in
            do { try await action(); try Task.checkCancellation(); callback.success() }
            catch {
                if Task.isCancelled { dispose() }
                callback.failure(message: error.localizedDescription)
            }
        }
    }
    func dispose() {
        lock.lock()
        guard !disposed else { lock.unlock(); return }
        disposed = true
        lock.unlock()
        let send = send
        let receive = receive
        Task.detached { try? await receive.stop(errorCode: 0); try? await send.reset(errorCode: 0) }
        onDispose()
    }
    deinit { dispose() }
}
