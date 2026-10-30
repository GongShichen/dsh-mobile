@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dsh.mobile

import io.dsh.mobile.database.DatabaseDriverFactory
import io.dsh.mobile.platform.*
import io.dsh.mobile.sync.MobileRepository
import io.dsh.mobile.transport.BridgePeerTransport
import io.dsh.mobile.transport.NativeIrohBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Native shell keeps this object alive for the lifetime of the application. */
class IosAppRuntime internal constructor(
    private val repository: MobileRepository,
    private val scope: CoroutineScope,
    private val push: IosPushAdapter,
) {
    fun updatePushToken(token: String, environment: String, applicationId: String) {
        push.register(token, environment, applicationId)
    }
    fun receivePush(hostId: String, eventId: String, sessionId: String?) {
        scope.launch {
            repository.receivePush(hostId, eventId, sessionId)
            if (sessionId != null && repository.state.value.selectedSessionId == sessionId) AppGraph.openConversation()
        }
    }
    fun onForeground() {
        scope.launch { repository.onForeground() }
    }
    fun onBackground() { scope.launch { repository.onBackground() } }
    fun networkAvailable() { scope.launch { repository.resume() } }
}

fun installIosApp(transportBridge: NativeIrohBridge, uiBridge: NativeUiBridge): IosAppRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val push = IosPushAdapter(uiBridge)
    val repository = MobileRepository(
        BridgePeerTransport(transportBridge), createSecureStore(), DatabaseDriverFactory().createCache(),
        scope, object : Clock { override fun nowMillis() = (NSDate().timeIntervalSince1970 * 1000).toLong() },
        "ios", push,
    )
    AppGraph.repository = repository
    AppGraph.scanner = object : QrScanner {
        override suspend fun scan(): String? = suspendCancellableCoroutine { continuation ->
            val operation = uiBridge.scan(object : UiStringCallback {
                override fun success(value: String?) { if (continuation.isActive) continuation.resume(value) }
                override fun failure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
            })
            continuation.invokeOnCancellation { operation.cancel() }
        }
    }
    AppGraph.filePicker = object : FilePicker {
        override suspend fun pick(): List<PickedFile> = suspendCancellableCoroutine { continuation ->
            val operation = uiBridge.pickFiles(object : UiFilesCallback {
                override fun success(files: List<PickedFile>) { if (continuation.isActive) continuation.resume(files) }
                override fun failure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
            })
            continuation.invokeOnCancellation { operation.cancel() }
        }
        override suspend fun read(handle: String, offset: Long, length: Int): ByteArray = suspendCancellableCoroutine { continuation ->
            val operation = uiBridge.readFile(handle, offset, length, object : UiBytesCallback {
                override fun success(bytes: ByteArray) { if (continuation.isActive) continuation.resume(bytes) }
                override fun failure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
            })
            continuation.invokeOnCancellation { operation.cancel() }
        }
        override suspend fun release(handle: String) { uiBridge.releaseFile(handle) }
    }
    AppGraph.notificationPermissions = object : NotificationPermissions {
        override suspend fun request() = push.requestPermission()
    }
    AppGraph.saveFile = { name, bytes ->
        suspendCancellableCoroutine { continuation ->
            val operation = uiBridge.saveFile(name, bytes, object : UiUnitCallback {
                override fun success() { if (continuation.isActive) continuation.resume(Unit) }
                override fun failure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
            })
            continuation.invokeOnCancellation { operation.cancel() }
        }
    }
    return IosAppRuntime(repository, scope, push)
}

internal class IosPushAdapter(private val bridge: NativeUiBridge) : PushService {
    private var registered = false
    private val updates = MutableSharedFlow<PushRegistration>(replay = 1, extraBufferCapacity = 1)
    override val registrations = updates.asSharedFlow()
    override fun appMetadata() = bridge.appMetadata()
    override suspend fun configure(options: FirebasePublicOptions?) = if (registered) PushAvailability.AVAILABLE else PushAvailability.UNCONFIGURED
    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        val operation = bridge.requestNotificationPermission(object : UiBooleanCallback {
            override fun success(value: Boolean) { if (continuation.isActive) continuation.resume(value) }
            override fun failure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
        })
        continuation.invokeOnCancellation { operation.cancel() }
    }
    fun register(token: String, environment: String, appId: String) {
        require(environment in setOf("sandbox", "production")) { "Invalid APNs environment" }
        registered = true
        updates.tryEmit(PushRegistration("apns", token, environment, appId))
    }
}
