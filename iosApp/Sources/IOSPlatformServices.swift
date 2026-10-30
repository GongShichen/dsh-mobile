import Foundation
import UIKit
import AVFoundation
import PhotosUI
import UniformTypeIdentifiers
import UserNotifications
import DshMobile

private struct MobileUIError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private final class UiOperation: NSObject, UiCancellation, @unchecked Sendable {
    private let lock = NSLock()
    private var settled = false
    private var cancellationAction: (() -> Void)?
    var onCancel: (() -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return cancellationAction }
        set {
            lock.lock()
            cancellationAction = newValue
            let alreadySettled = settled
            lock.unlock()
            if alreadySettled { DispatchQueue.main.async { newValue?() } }
        }
    }
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return settled }
    @discardableResult
    func complete(_ action: () -> Void) -> Bool {
        lock.lock()
        guard !settled else { lock.unlock(); return false }
        settled = true
        lock.unlock()
        action()
        return true
    }
    func cancel() {
        lock.lock()
        guard !settled else { lock.unlock(); return }
        settled = true
        let action = cancellationAction
        lock.unlock()
        DispatchQueue.main.async { action?() }
    }
}

private final class DocumentResult: NSObject, UIDocumentPickerDelegate, UIAdaptivePresentationControllerDelegate {
    let finish: ([URL]?) -> Void
    init(_ finish: @escaping ([URL]?) -> Void) { self.finish = finish }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { finish(urls) }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish(nil) }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { finish(nil) }
}

private final class PhotoResult: NSObject, PHPickerViewControllerDelegate, UIAdaptivePresentationControllerDelegate {
    private var finished = false
    let finish: ([PHPickerResult]) -> Void
    init(_ finish: @escaping ([PHPickerResult]) -> Void) { self.finish = finish }
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) { complete(results) }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { complete([]) }
    private func complete(_ results: [PHPickerResult]) { guard !finished else { return }; finished = true; finish(results) }
}

final class IOSPlatformServices: NSObject, NativeUiBridge, @unchecked Sendable {
    private let fileLock = NSLock()
    private var files: [String: URL] = [:]
    private var documentDelegate: DocumentResult?
    private var photoDelegate: PhotoResult?
    private var presentationOperation: UiOperation?
    private let maximumFileSize: Int64 = 128 * 1024 * 1024

    func appMetadata() -> PushAppMetadata {
        let team = Bundle.main.object(forInfoDictionaryKey: "DSHSigningTeam") as? String
        let entitlementEnvironment = Bundle.main.object(forInfoDictionaryKey: "DSHAPNsEnvironment") as? String
        return PushAppMetadata(platform: "ios", applicationId: Bundle.main.bundleIdentifier ?? "io.github.gongshichen.dshmobile",
            teamId: team?.isEmpty == false ? team : nil,
            environment: IOSPushCapability.isEnabled ? (entitlementEnvironment == "production" ? "production" : (entitlementEnvironment == "development" ? "sandbox" : nil)) : nil)
    }

    private func reservePresentation(_ operation: UiOperation) throws {
        if let existing = presentationOperation, !existing.isCancelled { throw MobileUIError(message: "请先完成当前选择") }
        presentationOperation = operation
    }

    private func presenter() throws -> UIViewController {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard var controller = scenes.flatMap(\.windows).first(where: { $0.isKeyWindow })?.rootViewController else {
            throw MobileUIError(message: "界面尚未就绪，请稍后重试")
        }
        while let presented = controller.presentedViewController { controller = presented }
        return controller
    }

    func scan(callback: UiStringCallback) -> UiCancellation {
        let operation = UiOperation()
        AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
            DispatchQueue.main.async {
                guard !operation.isCancelled, let self else { return }
                guard allowed else { operation.complete { callback.failure(message: "请在系统设置中允许相机访问") }; return }
                do {
                    try self.reservePresentation(operation)
                    let parent = try self.presenter()
                    let scanner = QRScannerController { value, error in
                        operation.complete {
                            parent.dismiss(animated: true)
                            if let error { callback.failure(message: error.localizedDescription) }
                            else { callback.success(value_: value) }
                        }
                    }
                    scanner.modalPresentationStyle = .fullScreen
                    operation.onCancel = { [weak scanner] in scanner?.dismiss(animated: true) }
                    parent.present(scanner, animated: true)
                } catch { operation.complete { callback.failure(message: error.localizedDescription) } }
            }
        }
        return operation
    }

    func pickFiles(callback: UiFilesCallback) -> UiCancellation {
        let operation = UiOperation()
        DispatchQueue.main.async { [self] in
            guard !operation.isCancelled else { return }
            do {
                try reservePresentation(operation)
                let parent = try presenter()
                let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false)
                picker.allowsMultipleSelection = true
                let delegate = DocumentResult { [self] urls in
                    documentDelegate = nil
                    guard let urls else { operation.complete { callback.success(files: []) }; return }
                    DispatchQueue.global(qos: .userInitiated).async { [self] in
                        var created: [String] = []
                        do {
                            let picked: [PickedFile] = try urls.map { url in
                                let scoped = url.startAccessingSecurityScopedResource()
                                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                                let handle = UUID().uuidString
                                let target = FileManager.default.temporaryDirectory.appendingPathComponent("dsh-upload-\(handle)")
                                let input = try FileHandle(forReadingFrom: url)
                                defer { try? input.close() }
                                FileManager.default.createFile(atPath: target.path, contents: nil)
                                let output = try FileHandle(forWritingTo: target)
                                defer { try? output.close() }
                                fileLock.lock(); files[handle] = target; fileLock.unlock()
                                created.append(handle)
                                var size: Int64 = 0
                                while let bytes = try input.read(upToCount: 65_536), !bytes.isEmpty {
                                    size += Int64(bytes.count)
                                    guard size <= maximumFileSize else { throw MobileUIError(message: "单个附件不能超过 128 MiB") }
                                    guard !operation.isCancelled else { throw CancellationError() }
                                    try output.write(contentsOf: bytes)
                                }
                                let type = try? url.resourceValues(forKeys: [.contentTypeKey]).contentType
                                return PickedFile(name: url.lastPathComponent, mimeType: type?.preferredMIMEType, length: size, handle: handle)
                            }
                            if operation.isCancelled { created.forEach { releaseFile(handle: $0) }; return }
                            operation.complete { callback.success(files: picked) }
                        } catch {
                            created.forEach { releaseFile(handle: $0) }
                            operation.complete { callback.failure(message: error.localizedDescription) }
                        }
                    }
                }
                documentDelegate = delegate
                picker.delegate = delegate
                picker.presentationController?.delegate = delegate
                operation.onCancel = { [weak picker] in picker?.dismiss(animated: true) }
                parent.present(picker, animated: true)
            } catch { operation.complete { callback.failure(message: error.localizedDescription) } }
        }
        return operation
    }

    func pickPhotos(callback: UiFilesCallback) -> UiCancellation {
        let operation = UiOperation()
        DispatchQueue.main.async { [self] in
            guard !operation.isCancelled else { return }
            do {
                try reservePresentation(operation)
                let parent = try presenter()
                var configuration = PHPickerConfiguration(photoLibrary: .shared())
                configuration.filter = .images
                configuration.selectionLimit = 10
                configuration.preferredAssetRepresentationMode = .compatible
                let picker = PHPickerViewController(configuration: configuration)
                let delegate = PhotoResult { [self] results in
                    photoDelegate = nil
                    picker.dismiss(animated: true)
                    guard !results.isEmpty else { operation.complete { callback.success(files: []) }; return }
                    var picked: [PickedFile] = []
                    func finishError(_ error: Error) {
                        picked.forEach { releaseFile(handle: $0.handle) }
                        operation.complete { callback.failure(message: error.localizedDescription) }
                    }
                    func load(_ index: Int) {
                        guard !operation.isCancelled else { picked.forEach { releaseFile(handle: $0.handle) }; return }
                        if index == results.count {
                            if !operation.complete({ callback.success(files: picked) }) { picked.forEach { releaseFile(handle: $0.handle) } }
                            return
                        }
                        let provider = results[index].itemProvider
                        let progress = provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { [self] url, error in
                            do {
                                if let error { throw error }
                                guard let url else { throw MobileUIError(message: "无法读取所选照片，请先确认照片已从 iCloud 下载") }
                                let handle = UUID().uuidString
                                let target = FileManager.default.temporaryDirectory.appendingPathComponent("dsh-upload-\(handle)")
                                var ext = url.pathExtension.lowercased()
                                let suggestedName = provider.suggestedName ?? "照片-\(index + 1)"
                                var name = (suggestedName as NSString).deletingPathExtension
                                if ext.isEmpty { ext = "jpg" }
                                if ["heic", "heif"].contains(ext) {
                                    guard let image = UIImage(contentsOfFile: url.path), let data = image.jpegData(compressionQuality: 0.95) else { throw MobileUIError(message: "无法转换这张照片") }
                                    guard data.count <= maximumFileSize else { throw MobileUIError(message: "单张照片不能超过 128 MiB") }
                                    try data.write(to: target, options: .atomic)
                                    ext = "jpg"
                                } else {
                                    let size = (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
                                    guard size <= maximumFileSize else { throw MobileUIError(message: "单张照片不能超过 128 MiB") }
                                    // The provider's temporary URL is only valid inside this callback.
                                    try FileManager.default.copyItem(at: url, to: target)
                                }
                                name += ".\(ext)"
                                let size = (try FileManager.default.attributesOfItem(atPath: target.path)[.size] as? NSNumber)?.int64Value ?? 0
                                fileLock.lock(); files[handle] = target; fileLock.unlock()
                                picked.append(PickedFile(name: name, mimeType: UTType(filenameExtension: ext)?.preferredMIMEType ?? "image/jpeg", length: size, handle: handle))
                                load(index + 1)
                            } catch { finishError(error) }
                        }
                        operation.onCancel = { progress.cancel() }
                    }
                    load(0)
                }
                photoDelegate = delegate
                picker.delegate = delegate
                picker.presentationController?.delegate = delegate
                operation.onCancel = { [weak self, weak picker] in self?.photoDelegate = nil; picker?.dismiss(animated: true) }
                parent.present(picker, animated: true)
            } catch { operation.complete { callback.failure(message: error.localizedDescription) } }
        }
        return operation
    }

    func readFile(handle: String, offset: Int64, length: Int32, callback: UiBytesCallback) -> UiCancellation {
        let operation = UiOperation()
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                guard offset >= 0, (0...1_048_576).contains(length) else { throw MobileUIError(message: "无效的文件读取范围") }
                fileLock.lock(); let url = files[handle]; fileLock.unlock()
                guard let url else { throw MobileUIError(message: "文件已释放，请重新选择") }
                let input = try FileHandle(forReadingFrom: url)
                defer { try? input.close() }
                let size = try input.seekToEnd()
                guard UInt64(offset) <= size else { throw MobileUIError(message: "读取位置超出文件长度") }
                try input.seek(toOffset: UInt64(offset))
                let data = try input.read(upToCount: Int(length)) ?? Data()
                let bytes = KotlinByteArray(size: Int32(data.count))
                for (index, value) in data.enumerated() { bytes.set(index: Int32(index), value: Int8(bitPattern: value)) }
                operation.complete { callback.success(bytes: bytes) }
            } catch { operation.complete { callback.failure(message: error.localizedDescription) } }
        }
        return operation
    }

    func releaseFile(handle: String) {
        fileLock.lock(); let url = files.removeValue(forKey: handle); fileLock.unlock()
        if let url { try? FileManager.default.removeItem(at: url) }
    }

    func saveFile(name: String, bytes: KotlinByteArray, callback: UiUnitCallback) -> UiCancellation {
        let operation = UiOperation()
        guard bytes.size <= 20 * 1024 * 1024 else { callback.failure(message: "保存内容不能超过 20 MiB"); return operation }
        let data = Data((0..<Int(bytes.size)).map { UInt8(bitPattern: bytes.get(index: Int32($0))) })
        DispatchQueue.main.async { [self] in
            guard !operation.isCancelled else { return }
            do {
                try reservePresentation(operation)
                let parent = try presenter()
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let safeName = (name as NSString).lastPathComponent
                let file = directory.appendingPathComponent(safeName.isEmpty ? "download" : safeName)
                try data.write(to: file, options: .atomic)
                let picker = UIDocumentPickerViewController(forExporting: [file], asCopy: true)
                let delegate = DocumentResult { [self] urls in
                    documentDelegate = nil
                    try? FileManager.default.removeItem(at: directory)
                    operation.complete {
                        if urls == nil { callback.failure(message: "已取消保存") }
                        else { callback.success() }
                    }
                }
                documentDelegate = delegate
                picker.delegate = delegate
                picker.presentationController?.delegate = delegate
                operation.onCancel = { [weak picker] in
                    picker?.dismiss(animated: true)
                    try? FileManager.default.removeItem(at: directory)
                }
                parent.present(picker, animated: true)
            } catch { operation.complete { callback.failure(message: error.localizedDescription) } }
        }
        return operation
    }

    func requestNotificationPermission(callback: UiBooleanCallback) -> UiCancellation {
        let operation = UiOperation()
        guard IOSPushCapability.isEnabled else {
            operation.complete { callback.failure(message: IOSPushCapability.unavailableMessage) }
            return operation
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { allowed, error in
            DispatchQueue.main.async {
                if let error { operation.complete { callback.failure(message: error.localizedDescription) }; return }
                if allowed { UIApplication.shared.registerForRemoteNotifications() }
                operation.complete { callback.success(value: allowed) }
            }
        }
        return operation
    }
}

private final class QRScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "io.dsh.mobile.camera")
    private var preview: AVCaptureVideoPreviewLayer?
    private var startupError: Error?
    private var finished = false
    private let completion: (String?, Error?) -> Void
    init(completion: @escaping (String?, Error?) -> Void) { self.completion = completion; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("Use init(completion:)") }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let button = UIButton(type: .system)
        button.setTitle("取消", for: .normal)
        button.tintColor = .white
        button.addTarget(self, action: #selector(cancel), for: .touchUpInside)
        button.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(button)
        NSLayoutConstraint.activate([button.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12), button.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20)])
        do {
            guard let device = AVCaptureDevice.default(for: .video) else { throw MobileUIError(message: "此设备没有可用相机，可粘贴配对码连接") }
            let input = try AVCaptureDeviceInput(device: device)
            guard session.canAddInput(input) else { throw MobileUIError(message: "无法打开相机") }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { throw MobileUIError(message: "相机不支持二维码识别") }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            view.layer.insertSublayer(layer, at: 0)
            preview = layer
            queue.async { [session] in session.startRunning() }
        } catch { startupError = error }
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if let startupError { finish(nil, startupError) }
    }
    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }
    override func viewDidDisappear(_ animated: Bool) { super.viewDidDisappear(animated); queue.async { [session] in session.stopRunning() } }
    @objc private func cancel() { finish(nil, nil) }
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        if let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first?.stringValue { finish(code, nil) }
    }
    private func finish(_ value: String?, _ error: Error?) {
        guard !finished else { return }; finished = true
        completion(value, error)
    }
}
