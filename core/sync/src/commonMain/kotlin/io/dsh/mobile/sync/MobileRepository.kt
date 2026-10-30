package io.dsh.mobile.sync

import io.dsh.mobile.database.MobileCache
import io.dsh.mobile.model.*
import io.dsh.mobile.platform.*
import io.dsh.mobile.protocol.*
import io.dsh.mobile.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Desktop state is authoritative; the local database is a host-isolated view/cache. */
class MobileRepository(
    private val transport: PeerTransport,
    private val secureStore: SecureStore,
    private val cache: MobileCache,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val platform: String,
    private val pushService: PushService? = null,
) {
    private val mutableState = MutableStateFlow(MobileState())
    val state: StateFlow<MobileState> = mutableState.asStateFlow()
    private val lifecycle = Mutex()
    private val events = Mutex()
    private val refresh = Mutex()
    private val selection = Mutex()
    private val resuming = Mutex()
    private val pushConfiguration = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private var foreground = false
    private var resumeAllowed = true
    private val router = SubscriptionRouter()
    private var rpc: RpcSession? = null
    private var epoch = 0L
    private var reconnectJob: Job? = null
    private var connectionAttempt: Job? = null
    private var pathJob: Job? = null
    private var reducer: SessionReducer? = null
    private var interactionClientId: String? = null
    private var lastPushRegistration: PushRegistration? = null
    private val workspaceReducer = WorkspaceReducer()
    private val projectionReducer = ProjectionReducer()

    init {
        scope.launch {
            try {
                val hosts = cache.hosts()
                val hostId = cache.preference("selected.host")?.takeIf { id -> hosts.any { it.id == id } }
                val sessionId = hostId?.let { cache.preference("selected.session.$it") }
                val sessions = hostId?.let { cache.sessions(it) }.orEmpty()
                val records = if (hostId != null && sessionId != null) cache.messages(hostId, sessionId) else emptyList()
                val draft = if (hostId != null && sessionId != null) cache.draft(hostId, sessionId) else ""
                mutableState.update { it.copy(hosts = hosts, activeHostId = hostId, selectedSessionId = sessionId, sessions = sessions, messages = records, draft = draft) }
                initialized.complete(Unit)
            } catch (error: Throwable) { initialized.completeExceptionally(error); showError(error) }
        }
        pushService?.let { service ->
            scope.launch {
                service.registrations.collect { registration ->
                    lastPushRegistration = registration
                    if (state.value.connected) runCatching { registerPush(registration) }.onFailure(::showError)
                }
            }
        }
    }

    suspend fun connect(hostId: String) {
        initialized.await()
        resumeAllowed = true
        reconnectJob?.cancel(); reconnectJob = null
        val attempt = currentCoroutineContext()[Job]
        connectionAttempt = attempt
        try { lifecycle.withLock { connectOnce(hostId) } }
        catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is RemoteCallException && error.code == "device/revoked") cache.savePreference("selected.host", null)
            showError(error); mutableState.update { it.copy(connection = ConnectionStatus.ERROR) }
        }
        finally { if (connectionAttempt == attempt) connectionAttempt = null }
    }

    suspend fun pair(qr: String, deviceName: String) {
        reportDiagnostic("pairing.started")
        initialized.await()
        resumeAllowed = true
        reconnectJob?.cancel(); reconnectJob = null
        val attempt = currentCoroutineContext()[Job]
        connectionAttempt = attempt
        try {
            val invitation = PairingInvitation.parse(qr, clock.nowMillis())
            reportDiagnostic("pairing.qr.valid", "expiresInSeconds=${(invitation.expiresAt - clock.nowMillis()) / 1000}")
            lifecycle.withLock {
                closeCurrent()
                val currentEpoch = epoch
                mutableState.update { it.copy(activeHostId = null, connection = ConnectionStatus.PAIRING, pairingName = invitation.name, error = null, sessions = emptyList(), messages = emptyList(), interactions = emptyList()) }
                reportDiagnostic("pairing.transport.connect")
                val connection = openPeer(PeerDescriptor(invitation.endpointId, invitation.ticket))
                reportDiagnostic("pairing.transport.connected")
                val session = openRpc(connection, currentEpoch)
                rpc = session
                var hello = decodeHello(session.call("hello"))
                reportDiagnostic("pairing.hello", "authorized=${hello.authorized}")
                if (!hello.authorized) {
                    val response = session.call("pairing.request", buildJsonObject {
                        put("pairingId", invitation.pairingId); put("secret", invitation.secret)
                        put("name", deviceName.trim().ifEmpty { "Harness Link" }); put("platform", platform)
                    }).jsonObject
                    reportDiagnostic("pairing.awaiting.desktop")
                    val requestId = response.string("requestId") ?: throw ProtocolException("Missing pairing request id")
                    mutableState.update { it.copy(connection = ConnectionStatus.AWAITING_APPROVAL) }
                    while (true) {
                        if (clock.nowMillis() >= invitation.expiresAt) throw ProtocolException("配对邀请已过期")
                        val status = session.call("pairing.status", buildJsonObject { put("requestId", requestId) }).jsonObject.string("status")
                        when (status) {
                            "approved" -> break
                            "rejected" -> throw ProtocolException("桌面端拒绝了配对请求")
                            "expired" -> throw ProtocolException("配对邀请已过期")
                            "pending" -> delay(1000)
                            else -> throw ProtocolException("Unexpected pairing status")
                        }
                    }
                    hello = decodeHello(session.call("hello"))
                }
                if (!hello.authorized) throw ProtocolException("配对尚未授权")
                val host = hostFromHello(hello, invitation.endpointId, invitation.ticket)
                cache.saveHost(host)
                reportDiagnostic("pairing.approved")
                activate(host, session, currentEpoch)
            }
        } catch (error: Throwable) {
            reportFailure("pairing.failed", error)
            withContext(NonCancellable) { lifecycle.withLock { closeCurrent() } }
            if (error is CancellationException) throw error
            mutableState.update { it.copy(connection = ConnectionStatus.ERROR, pairingName = null, error = error.message ?: "配对失败") }
        } finally { if (connectionAttempt == attempt) connectionAttempt = null }
    }

    suspend fun disconnect() {
        resumeAllowed = false
        stopConnection()
    }

    private suspend fun stopConnection() {
        reconnectJob?.cancel(); reconnectJob = null
        connectionAttempt?.takeIf { it != currentCoroutineContext()[Job] }?.cancelAndJoin()
        connectionAttempt = null
        lifecycle.withLock { closeCurrent() }
        mutableState.update { it.copy(connection = ConnectionStatus.DISCONNECTED, path = ConnectionPath.UNKNOWN, pairingName = null) }
    }

    suspend fun onForeground() { foreground = true; resume() }

    suspend fun onBackground() {
        foreground = false
        stopConnection()
        resumeAllowed = true
    }

    /** Called on foreground/network restoration; no mutation or prompt is automatically replayed. */
    suspend fun resume() {
        if (!resuming.tryLock()) return
        try {
            initialized.await()
            if (!foreground || !resumeAllowed || rpc != null || connectionAttempt?.isActive == true || reconnectJob?.isActive == true) return
            cache.preference("selected.host")?.let { connect(it) }
        } catch (error: Throwable) { if (error is CancellationException) throw error; showError(error) }
        finally { resuming.unlock() }
    }

    suspend fun forgetHost(hostId: String) {
        if (state.value.activeHostId == hostId) disconnect()
        cache.removeHost(hostId)
        mutableState.update { if (it.activeHostId == hostId) MobileState(hosts = cache.hosts()) else it.copy(hosts = cache.hosts()) }
    }

    suspend fun refreshSessions() = refresh.withLock {
        val hostId = requireHost()
        val currentEpoch = epoch
        val result = requireRpc().call("sessions.list").jsonObject
        val rows = decorateSessions(decodeSessions(result))
        cache.replaceSessions(hostId, rows)
        if (currentEpoch == epoch) mutableState.update { it.copy(sessions = rows) }
    }

    suspend fun selectSession(sessionId: String) = selection.withLock {
        val hostId = requireHost()
        cache.savePreference("selected.session.$hostId", sessionId)
        val cached = cache.messages(hostId, sessionId)
        val draft = cache.draft(hostId, sessionId)
        events.withLock {
            reducer = SessionReducer(sessionId).also { it.seed(cached) }
            mutableState.update { it.copy(selectedSessionId = sessionId, messages = cached, draft = draft, sessionState = null, projections = null, feedback = emptyMap(), turnPresentation = emptyMap(), jobs = emptyList(), hasMoreHistory = false) }
        }
        if (rpc == null) return@withLock
        val previous = events.withLock { router.handles().filter { it.second.sessionId != null } }
        previous.forEach { (handle, _) ->
            events.withLock { router.remove(handle.subscriptionId) }
            runCatching { requireRpc().call("unsubscribe", buildJsonObject { put("subscriptionId", handle.subscriptionId) }) }
        }
        subscribe("session", buildJsonObject {
            put("address", buildJsonObject { put("kind", "session"); put("sessionId", sessionId) })
            put("assistantStream", true); put("activate", true)
        }, sessionId)
        subscribe("sessionState", buildJsonObject { put("sessionId", sessionId) }, sessionId)
        subscribe("jobs", buildJsonObject { put("sessionId", sessionId) }, sessionId)
        if ("feedback" in state.value.capabilities) subscribe("feedback", buildJsonObject { put("sessionId", sessionId) }, sessionId)
    }

    suspend fun setDraft(text: String) {
        val hostId = state.value.activeHostId ?: return
        val sessionId = state.value.selectedSessionId ?: return
        mutableState.update { it.copy(draft = text) }
        cache.saveDraft(hostId, sessionId, text)
    }

    suspend fun createSession(title: String? = null): String {
        val result = call("sessions.create", buildJsonObject { put("sessionId", newOperationId()) }).jsonObject
        val id = result.string("sessionId") ?: throw ProtocolException("Missing new session id")
        if (!title.isNullOrBlank()) renameSession(id, title)
        refreshSessions(); selectSession(id)
        return id
    }

    suspend fun sendMessage(text: String, receiptIds: List<String> = emptyList()) {
        if (text.isBlank() && receiptIds.isEmpty()) return
        val sessionId = state.value.selectedSessionId ?: createSession()
        call("sessions.prompt", buildJsonObject {
            put("sessionId", sessionId); put("requestId", newOperationId()); put("mode", "queue")
            put("content", buildJsonArray {
                if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
                receiptIds.forEach { add(buildJsonObject { put("type", "file"); put("receiptId", it) }) }
            })
        })
        setDraft("")
    }

    suspend fun cancelRun() { call("sessions.cancel", buildJsonObject { put("sessionId", requireSelectedSession()) }) }
    suspend fun renameSession(id: String, title: String) {
        call("sessions.rename", buildJsonObject { put("sessionId", id); put("title", title) }); refreshSessions()
    }
    suspend fun archiveSession(id: String) { call("sessions.archive", buildJsonObject { put("sessionId", id) }); refreshSessions() }

    suspend fun loadHistory(sessionId: String = requireSelectedSession()) {
        val hostId = requireHost()
        val earliest = state.value.messages.mapNotNull { it.sequence }.minOrNull()
        val page = requireRpc().call("sessions.events", buildJsonObject {
            put("address", buildJsonObject { put("kind", "session"); put("sessionId", sessionId) })
            if (earliest != null) put("beforeSeq", earliest)
            put("maxMessages", 100)
        }).jsonObject
        events.withLock {
            if (state.value.selectedSessionId != sessionId || state.value.activeHostId != hostId) return@withLock
            applyPresentation(page)
            reducer?.prependHistory(page)
            publishMessages(hostId, sessionId)
        }
    }

    suspend fun replyInteraction(id: String, outcome: JsonObject) {
        val clientId = interactionClientId ?: throw ProtocolException("交互订阅尚未就绪")
        call("interactions.reply", buildJsonObject { put("clientId", clientId); put("eventId", id); put("outcome", outcome) })
    }

    /** Uses only the documented mobile allowlist, never arbitrary upstream endpoints. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement = executeCall(method, params)

    suspend fun retryOperation(operationId: String): JsonElement {
        val operation = cache.operations(requireHost()).firstOrNull { it.id == operationId } ?: throw ProtocolException("找不到待重试操作")
        if (!OperationRecovery.canExplicitlyRetry(operation)) throw ProtocolException("此操作不能安全重试，请先核对桌面状态")
        return executeCall(operation.method, operation.params, explicitRetry = true)
    }

    private suspend fun executeCall(method: String, params: JsonObject, explicitRetry: Boolean = false): JsonElement {
        MobileMethods.requireAllowed(method)
        if (method !in MobileMethods.mutations) return requireRpc().call(method, params)
        val hostId = requireHost()
        val operationId = params.string("operationId") ?: newOperationId()
        val actual = JsonObject(params + ("operationId" to JsonPrimitive(operationId)))
        val existing = cache.operations(hostId).firstOrNull { it.id == operationId }
        if (existing != null) {
            if (existing.method != method || existing.params != actual) throw ProtocolException("Operation id cannot be reused with different content")
            if (!OperationRecovery.canAutomaticallySend(existing) && !(explicitRetry && OperationRecovery.canExplicitlyRetry(existing))) throw ProtocolException("操作已发送；请先核对桌面状态，避免重复执行")
        }
        var operation = existing ?: PendingOperation(operationId, hostId, method, actual, OperationStatus.QUEUED, actual.string("requestId"))
        cache.saveOperation(operation)
        val session = requireRpc() // Never dispatched if the connection is unavailable here.
        operation = operation.copy(status = OperationStatus.DISPATCHED)
        cache.saveOperation(operation); publishOperations(hostId)
        try {
            val result = session.call(method, actual)
            operation = operation.copy(status = OperationStatus.CONFIRMED, result = result as? JsonObject)
            cache.saveOperation(operation); publishOperations(hostId)
            return result
        } catch (error: Throwable) {
            val knownFailure = error is RemoteCallException && error.code !in setOf("OPERATION_OUTCOME_UNKNOWN", "operation/unknown", "operation/in-progress", "operation-unknown")
            operation = operation.copy(status = if (knownFailure) OperationStatus.FAILED else OperationStatus.UNKNOWN, error = if (knownFailure) error.message else "执行结果未知，请核对桌面状态；不会自动重发")
            withContext(NonCancellable) { cache.saveOperation(operation); publishOperations(hostId) }
            if (error !is CancellationException) showError(error)
            throw error
        }
    }

    suspend fun configurePush() = pushConfiguration.withLock {
        val service = pushService ?: return@withLock
        val hostId = requireHost()
        val currentEpoch = epoch
        val metadata = service.appMetadata()
        call("push.describe", buildJsonObject {
            put("platform", metadata.platform); put("applicationId", metadata.applicationId)
            metadata.teamId?.let { put("teamId", it) }; metadata.environment?.let { put("environment", it) }
        })
        val configuration = requireRpc().call("push.configuration").jsonObject
        if (currentEpoch != epoch) return@withLock
        cache.savePushConfiguration(hostId, configuration)
        val firebase = configuration.obj("publicFirebaseOptions") ?: configuration.obj("firebase") ?: configuration.obj("firebaseOptions") ?: configuration
        val appId = firebase.string("applicationId") ?: firebase.string("appId")
        val senderId = firebase.string("gcmSenderId") ?: firebase.string("senderId")
        val options = if (firebase.string("projectId") != null && appId != null && firebase.string("apiKey") != null && senderId != null) FirebasePublicOptions(
            firebase.string("projectId")!!, appId, firebase.string("apiKey")!!, senderId, firebase.string("packageName")
        ) else null
        val availability = service.configure(options)
        if (foreground && availability == PushAvailability.AVAILABLE) service.requestPermission()
        if (currentEpoch != epoch) return@withLock
        lastPushRegistration?.let { registerPush(it) }
    }

    suspend fun requestPushPermission(): Boolean = pushService?.requestPermission() ?: false
    fun clearError() { mutableState.update { it.copy(error = null) } }

    suspend fun receivePush(hostId: String, eventId: String, sessionId: String? = null) {
        try {
            foreground = true; resumeAllowed = true
            initialized.await()
            val host = cache.hosts().firstOrNull { it.id == hostId || it.endpointId == hostId } ?: throw ProtocolException("通知来自未配对的电脑")
            if (state.value.activeHostId != host.id || !state.value.connected) connect(host.id)
            if (!state.value.connected) return
            if (sessionId != null) selectSession(sessionId)
            call("push.received", buildJsonObject { put("eventId", eventId) })
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            showError(error)
        }
    }

    /** Hash and stream a user-selected file; only desktop-issued receipts enter a prompt. */
    suspend fun uploadAttachment(file: PickedFile, picker: FilePicker): String = try {
        uploadSelectedFile(file, picker)
    } finally { withContext(NonCancellable) { picker.release(file.handle) } }

    private suspend fun uploadSelectedFile(file: PickedFile, picker: FilePicker): String {
        awaitConnected()
        require(file.length >= 0) { "Invalid file length" }
        val sessionId = state.value.selectedSessionId ?: createSession()
        val connection = requireRpc().connection
        val hasher = createSha256Hasher()
        val checksum = try {
            var offset = 0L
            while (offset < file.length) {
                currentCoroutineContext().ensureActive()
                val size = minOf(65536L, file.length - offset).toInt()
                val bytes = picker.read(file.handle, offset, size)
                if (bytes.size != size) throw ProtocolException("Selected file changed or ended unexpectedly")
                hasher.update(bytes); offset += bytes.size
            }
            hasher.finishHex()
        } finally { hasher.close() }
        val begin = call("attachments.begin", buildJsonObject {
            put("sessionId", sessionId); put("name", file.name); put("size", file.length); put("sha256", checksum)
            file.mimeType?.let { put("mediaType", it) }
        }).jsonObject
        val uploadId = begin.string("uploadId") ?: throw ProtocolException("Missing upload handle")
        try {
            var offset = begin.long("offset") ?: 0L
            while (offset < file.length) {
                val size = minOf(65536L, file.length - offset).toInt()
                val bytes = picker.read(file.handle, offset, size)
                if (bytes.size != size) throw ProtocolException("Selected file changed during upload")
                val stream = connection.openBi()
                try {
                    stream.writeAll(FrameCodec.encode(buildJsonObject {
                        put("v", PROTOCOL_VERSION); put("type", "stream"); put("kind", "upload")
                        put("uploadId", uploadId); put("offset", offset); put("length", bytes.size)
                    }))
                    stream.writeAll(bytes)
                    val ack = FrameCodec.read(stream::readExact).jsonObject
                    ack.obj("error")?.let { throw ProtocolException(it.string("message") ?: "Upload failed") }
                    val acknowledged = (ack.obj("result") ?: ack).long("offset")
                    if (acknowledged != offset + bytes.size) throw ProtocolException("Upload acknowledgement offset mismatch")
                    offset = acknowledged
                    stream.finish()
                } finally { withContext(NonCancellable) { runCatching { stream.close() } } }
            }
            val receipt = call("attachments.finalize", buildJsonObject { put("uploadId", uploadId) }).jsonObject
            return receipt.string("receiptId") ?: throw ProtocolException("Missing attachment receipt")
        } catch (error: Throwable) {
            withContext(NonCancellable) { runCatching { call("attachments.cancel", buildJsonObject { put("uploadId", uploadId) }) } }
            throw error
        }
    }

    /** Receives verified file bytes into a caller-owned sink, without buffering the file in memory. */
    suspend fun downloadFile(sessionId: String, path: String, sink: suspend (ByteArray) -> Unit) {
        awaitConnected()
        val descriptor = call("files.download", buildJsonObject { put("sessionId", sessionId); put("path", path) }).jsonObject
        val downloadId = descriptor.string("downloadId") ?: throw ProtocolException("Missing download handle")
        val stream = requireRpc().connection.openBi()
        val hasher = createSha256Hasher()
        try {
            stream.writeAll(FrameCodec.encode(buildJsonObject {
                put("v", PROTOCOL_VERSION); put("type", "stream"); put("kind", "download"); put("downloadId", downloadId); put("offset", 0)
            }))
            val metadata = FrameCodec.read(stream::readExact).jsonObject
            metadata.obj("error")?.let { throw ProtocolException(it.string("message") ?: "Download failed") }
            val length = metadata.long("length") ?: throw ProtocolException("Missing download length")
            if (length < 0 || length != descriptor.long("length")) throw ProtocolException("Download length mismatch")
            val checksum = metadata.string("sha256") ?: throw ProtocolException("Missing download checksum")
            var remaining = length
            while (remaining > 0) {
                val size = minOf(65536L, remaining).toInt()
                val bytes = stream.readExact(size)
                if (bytes.size != size) throw ProtocolException("Truncated download")
                hasher.update(bytes); sink(bytes); remaining -= bytes.size
            }
            if (hasher.finishHex() != checksum || checksum != descriptor.string("sha256")) throw ProtocolException("Download checksum mismatch; discard the partial file")
            stream.finish()
        } finally { hasher.close(); withContext(NonCancellable) { runCatching { stream.close() } } }
    }

    private suspend fun registerPush(registration: PushRegistration) {
        call("push.register", buildJsonObject {
            put("provider", registration.provider); put("token", registration.token); put("applicationId", registration.appId)
            registration.environment?.let { put("environment", it) }
        })
    }

    private suspend fun connectOnce(hostId: String) {
        val host = cache.hosts().firstOrNull { it.id == hostId } ?: throw ProtocolException("找不到已配对的电脑")
        val reopenSession = state.value.takeIf { it.activeHostId == hostId }?.selectedSessionId ?: cache.preference("selected.session.$hostId")
        closeCurrent()
        val currentEpoch = epoch
        val savedSessions = cache.sessions(hostId)
        mutableState.update { it.copy(activeHostId = hostId, connection = ConnectionStatus.CONNECTING, error = null, path = ConnectionPath.UNKNOWN, sessions = savedSessions, messages = emptyList(), selectedSessionId = null, interactions = emptyList(), workspaces = emptyList(), models = emptyList(), defaultModel = null, feedback = emptyMap(), turnPresentation = emptyMap(), jobs = emptyList(), capabilities = emptySet(), sessionState = null, projections = null, draft = "", operations = emptyList()) }
        val connection = openPeer(PeerDescriptor(host.endpointId, host.ticket))
        try {
            val session = openRpc(connection, currentEpoch)
            rpc = session
            val hello = decodeHello(session.call("hello"))
            if (!hello.authorized) throw RemoteCallException("device/revoked", "此手机尚未授权或授权已被撤销，请重新配对")
            if (hello.hostId != hostId) throw ProtocolException("电脑身份与已配对记录不一致")
            val updated = hostFromHello(hello, host.endpointId, host.ticket)
            cache.saveHost(updated)
            activate(updated, session, currentEpoch)
            if (reopenSession != null) runCatching { selectSession(reopenSession) }.onFailure(::showError)
        } catch (error: Throwable) {
            withContext(NonCancellable) { closeCurrent() }
            throw error
        }
    }

    private suspend fun activate(host: DesktopHost, session: RpcSession, currentEpoch: Long) {
        cache.savePreference("selected.host", host.id)
        mutableState.update { it.copy(hosts = cache.hosts(), activeHostId = host.id, connection = ConnectionStatus.SYNCING, pairingName = null, capabilities = host.capabilities.toSet(), operations = cache.operations(host.id)) }
        recoverOperations(host.id)
        pathJob?.cancel()
        pathJob = scope.launch {
            session.connection.path.collect { path -> if (currentEpoch == epoch) mutableState.update { it.copy(path = when (path) { PeerPath.DIRECT -> ConnectionPath.DIRECT; PeerPath.RELAY -> ConnectionPath.RELAY; else -> ConnectionPath.UNKNOWN }) } }
        }
        refreshSessions()
        subscribe("sessions", JsonObject(emptyMap()))
        subscribe("interactions", JsonObject(emptyMap()))
        subscribe("workspaces", JsonObject(emptyMap()))
        subscribe("sessionControl", JsonObject(emptyMap()))
        runCatching { refreshModels() }.onFailure(::showError)
        if (currentEpoch == epoch) mutableState.update { it.copy(connection = ConnectionStatus.CONNECTED) }
        scope.launch { runCatching { configurePush() }.onFailure(::showError) }
    }

    private suspend fun openPeer(descriptor: PeerDescriptor): PeerConnection {
        val seed = secureStore.get("mobile.endpoint.seed.v1") ?: secureRandomBytes(32).also { secureStore.put("mobile.endpoint.seed.v1", it) }
        if (seed.size != 32) throw ProtocolException("Stored device identity is invalid")
        reportDiagnostic("transport.connect.started")
        val connection = try { withTimeout(30_000) { transport.connect(descriptor, seed) } }
        catch (timeout: TimeoutCancellationException) { throw ProtocolException("连接电脑超时。请确认两端网络可用，并刷新电脑二维码后重试。") }
        reportDiagnostic("transport.connect.ready")
        if (connection.remoteEndpointId != descriptor.endpointId) {
            connection.close(); throw ProtocolException("连接的电脑身份与配对记录不一致")
        }
        return connection
    }

    private suspend fun openRpc(connection: PeerConnection, currentEpoch: Long): RpcSession = RpcSession.open(connection, scope,
        onEvent = { event -> if (currentEpoch == epoch) events.withLock { router.accept(event)?.let { applyEvent(it) } } },
        onFailure = { error -> if (currentEpoch == epoch) connectionLost(error) },
        onNotice = { name -> if (currentEpoch == epoch && name == "push-configuration-changed") runCatching { configurePush() }.onFailure(::showError) },
    )

    private suspend fun subscribe(topic: String, request: JsonObject, sessionId: String? = null) {
        val currentEpoch = epoch
        reportDiagnostic("subscription.open", "topic=$topic")
        val result = requireRpc().call("subscribe", buildJsonObject { put("topic", topic); put("request", request) })
        val handle = WireJson.decodeFromJsonElement(SubscriptionHandle.serializer(), result)
        reportDiagnostic("subscription.ready", "topic=$topic")
        events.withLock {
            if (currentEpoch != epoch || (sessionId != null && state.value.selectedSessionId != sessionId)) return@withLock
            router.register(handle, SubscriptionTarget(topic, sessionId)).forEach { applyEvent(it) }
        }
    }

    private suspend fun applyEvent(routed: RoutedEvent) {
        val hostId = state.value.activeHostId ?: return
        val payload = routed.event.event
        if (payload.string("type") in setOf("error", "end")) {
            router.remove(routed.event.subscriptionId)
            val message = payload.obj("error")?.string("message") ?: "状态订阅已结束，请重新连接"
            mutableState.update { it.copy(error = "${routed.target.topic}: $message") }
            return
        }
        when (routed.target.topic) {
            "session" -> {
                val id = routed.target.sessionId ?: return
                if (state.value.selectedSessionId != id) return
                payload.obj("projections")?.let { projectionReducer.baseline(id, it) }
                applyPresentation(payload)
                reducer?.apply(payload); publishMessages(hostId, id)
            }
            "feedback" -> if (routed.target.sessionId == state.value.selectedSessionId && payload.string("type") == "baseline") {
                val items = payload.obj("value")?.array("items").orEmpty().mapNotNull { value ->
                    val item = value as? JsonObject ?: return@mapNotNull null
                    item.string("messageId")?.let { it to item }
                }.toMap()
                mutableState.update { it.copy(feedback = items) }
            }
            "sessionState" -> if (routed.target.sessionId == state.value.selectedSessionId) mutableState.update { current ->
                current.copy(sessionState = payload, sessions = current.sessions.map { if (it.id == routed.target.sessionId) it.copy(running = payload.boolean("running") ?: it.running) else it })
            }
            "sessions" -> {
                if (payload.string("type") == "baseline") {
                    val rows = decorateSessions(decodeSessions(payload.obj("value") ?: payload))
                    cache.replaceSessions(hostId, rows); mutableState.update { it.copy(sessions = rows) }
                } else scope.launch { runCatching { refreshSessions() }.onFailure(::showError) }
            }
            "interactions" -> when (payload.string("type")) {
                "ready" -> interactionClientId = payload.string("clientId")
                "waterfall" -> {
                    val id = payload.string("eventId") ?: return
                    val item = PendingInteraction(id, payload.string("agentId"), payload.string("event") ?: "question", payload)
                    mutableState.update { it.copy(interactions = it.interactions.filterNot { existing -> existing.id == id } + item) }
                }
                "cancel" -> mutableState.update { it.copy(interactions = it.interactions.filterNot { item -> item.id == payload.string("eventId") }) }
            }
            "workspaces" -> {
                workspaceReducer.apply(payload)
                val rows = decorateSessions(state.value.sessions)
                cache.replaceSessions(hostId, rows)
                mutableState.update { it.copy(workspaces = workspaceReducer.items, sessions = rows) }
            }
            "jobs" -> {
                if (routed.target.sessionId == state.value.selectedSessionId && payload.string("type") == "rows") mutableState.update { it.copy(jobs = payload.array("jobs").mapNotNull { row -> row as? JsonObject }) }
            }
            "sessionControl" -> {
                projectionReducer.control(payload)
                val rows = state.value.sessions.map { row -> projectionReducer.values(row.id)?.string("title")?.let { row.copy(title = it.ifBlank { "新会话" }) } ?: row }
                cache.replaceSessions(hostId, rows)
                mutableState.update { current ->
                    val projections = current.selectedSessionId?.let { projectionReducer.values(it) }
                    current.copy(sessions = rows, projections = projections)
                }
            }
        }
    }

    private fun applyPresentation(payload: JsonObject) {
        val rows = payload.obj("presentation")?.array("turns") ?: return
        val values = rows.mapNotNull { raw -> (raw as? JsonObject)?.let { item -> item.long("turn")?.let { it to item } } }.toMap()
        mutableState.update { it.copy(turnPresentation = it.turnPresentation + values) }
    }

    private suspend fun publishMessages(hostId: String, sessionId: String) {
        val value = reducer ?: return
        cache.saveMessages(hostId, sessionId, value.messages)
        value.cursor?.let { cache.saveCursor(hostId, "session:$sessionId", it) }
        mutableState.update { it.copy(messages = value.messages, hasMoreHistory = value.hasMore, projections = projectionReducer.values(sessionId) ?: it.projections) }
        val acceptedRequestIds = value.messages.filter { it.role == "user" }.mapNotNull { it.raw.obj("data")?.obj("source")?.string("rpcId") }.toSet()
        if (acceptedRequestIds.isNotEmpty()) {
            cache.operations(hostId).filter { it.requestId in acceptedRequestIds && it.status in setOf(OperationStatus.UNKNOWN, OperationStatus.DISPATCHED) }
                .forEach { cache.saveOperation(it.copy(status = OperationStatus.CONFIRMED, error = null)) }
            publishOperations(hostId)
        }
    }

    private suspend fun recoverOperations(hostId: String) {
        cache.operations(hostId).filter { it.status == OperationStatus.DISPATCHED }.forEach { cache.saveOperation(OperationRecovery.afterConnectionLoss(it)) }
        publishOperations(hostId)
    }
    private suspend fun publishOperations(hostId: String) {
        val items = cache.operations(hostId)
        if (state.value.activeHostId == hostId) mutableState.update { it.copy(operations = items) }
    }

    private suspend fun connectionLost(error: Throwable) {
        val hostId = state.value.activeHostId
        rpc = null
        if (error is LogicalMessageTooLargeException) {
            resumeAllowed = false
            mutableState.update { it.copy(connection = ConnectionStatus.ERROR, path = ConnectionPath.UNKNOWN, error = error.message) }
            if (hostId != null) recoverOperations(hostId)
            return
        }
        if (hostId == null) { showError(error); return }
        recoverOperations(hostId)
        if (!foreground || !resumeAllowed) {
            mutableState.update { it.copy(connection = ConnectionStatus.DISCONNECTED, path = ConnectionPath.UNKNOWN) }
            return
        }
        mutableState.update { it.copy(connection = ConnectionStatus.RECONNECTING, path = ConnectionPath.UNKNOWN, error = "连接中断，正在重新连接") }
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            for (attempt in 0 until 10) {
                val baseDelay = minOf(30_000L, 500L shl minOf(attempt, 6))
                val jitter = (secureRandomBytes(1)[0].toInt() and 255) * baseDelay / 1024
                delay(minOf(30_000L, baseDelay + jitter))
                try {
                    lifecycle.withLock { connectOnce(hostId) }
                    return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) {
                    if (failure is RemoteCallException && failure.code == "device/revoked") {
                        cache.savePreference("selected.host", null)
                        mutableState.update { it.copy(connection = ConnectionStatus.ERROR, error = failure.message) }; return@launch
                    }
                }
            }
            mutableState.update { it.copy(connection = ConnectionStatus.ERROR, error = "无法重新连接，请确认电脑在线后重试") }
        }
    }

    private suspend fun closeCurrent() {
        epoch++
        pathJob?.cancel(); pathJob = null
        val previous = rpc; rpc = null
        previous?.close()
        events.withLock { router.reset(); reducer = null; interactionClientId = null; workspaceReducer.reset(); projectionReducer.reset() }
    }
    private fun requireRpc(): RpcSession = rpc ?: throw ProtocolException("请先连接桌面端")
    private suspend fun awaitConnected() {
        if (state.value.connected) return
        resume()
        val connection = withTimeout(30_000) { state.first { it.connected || it.connection == ConnectionStatus.ERROR } }
        if (!connection.connected) throw ProtocolException(connection.error ?: "请先连接桌面端")
    }
    private fun requireHost(): String = state.value.activeHostId ?: throw ProtocolException("请先选择电脑")
    private fun requireSelectedSession(): String = state.value.selectedSessionId ?: throw ProtocolException("请先选择会话")
    private fun showError(error: Throwable) { reportFailure("repository.error", error); if (error !is CancellationException) mutableState.update { it.copy(error = error.message ?: "操作失败") } }
    private fun decodeHello(value: JsonElement): Hello = WireJson.decodeFromJsonElement(Hello.serializer(), value).also {
        if (it.protocolVersion != PROTOCOL_VERSION) throw ProtocolException("桌面插件协议不兼容")
    }
    private fun hostFromHello(hello: Hello, endpointId: String, ticket: String?) = DesktopHost(
        hello.hostId ?: throw ProtocolException("Missing desktop host id"), endpointId, hello.hostName ?: "Desktop", ticket,
        hello.hostInstanceId, hello.dshVersion, hello.capabilities,
    )

    suspend fun refreshModels() {
        val currentEpoch = epoch
        val catalog = requireRpc().call("models.list").jsonObject
        val rows = catalog.array("groups").flatMap { group ->
            val provider = group as? JsonObject ?: return@flatMap emptyList()
            provider.array("models").mapNotNull { value ->
                val model = value as? JsonObject ?: return@mapNotNull null
                val id = model.string("id") ?: return@mapNotNull null
                JsonObject(model + mapOf("provider" to JsonPrimitive(provider.string("id").orEmpty()), "providerName" to JsonPrimitive(provider.string("name").orEmpty()), "model" to JsonPrimitive(id)))
            }
        }
        if (currentEpoch == epoch) mutableState.update { it.copy(models = rows, defaultModel = catalog.obj("default")) }
    }

    private fun decorateSessions(rows: List<SessionSummary>): List<SessionSummary> = workspaceReducer.decorate(rows)
}
