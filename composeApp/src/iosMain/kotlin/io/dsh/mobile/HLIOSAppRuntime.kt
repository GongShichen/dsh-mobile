@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dsh.mobile

import io.dsh.mobile.database.HLDatabaseDriverFactory
import io.dsh.mobile.platform.*
import io.dsh.mobile.sync.HLRepository
import io.dsh.mobile.transport.HLBridgePeerTransport
import io.dsh.mobile.transport.HLNativeIrohBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Native shell keeps this object alive for the lifetime of the application. */
class HLIOSAppRuntime internal constructor(
    private val repository: HLRepository,
    private val scope: CoroutineScope,
    private val push: HLIOSPushAdapter,
) {
    fun updatePushToken(token: String, environment: String, applicationId: String) {
        push.register(token, environment, applicationId)
    }
    fun receivePush(hostId: String, eventId: String, sessionId: String?) {
        scope.launch {
            repository.receivePush(hostId, eventId, sessionId)
            if (sessionId != null && repository.state.value.selectedSessionId == sessionId) HLAppGraph.openConversation()
        }
    }
    fun onForeground() {
        scope.launch { repository.onForeground() }
    }
    fun onBackground() { scope.launch { repository.onBackground() } }
    fun networkAvailable() { scope.launch { repository.resume() } }
}

fun installIosApp(transportBridge: HLNativeIrohBridge, uiBridge: HLNativeUIBridge, remotePushEnabled: Boolean = true): HLIOSAppRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val push = HLIOSPushAdapter(uiBridge, remotePushEnabled)
    val repository = HLRepository(
        HLBridgePeerTransport(transportBridge), createSecureStore(), HLDatabaseDriverFactory().createCache(),
        scope, object : HLClock { override fun nowMillis() = (NSDate().timeIntervalSince1970 * 1000).toLong() },
        "ios", push,
    )
    HLAppGraph.repository = repository
    HLAppGraph.scanner = object : HLQRScanner {
        override suspend fun scan(): String? = suspendCancellableCoroutine { continuation ->
            val operation = uiBridge.scan(object : HLUIStringCallback {
                override fun success(value: String?) { if (continuation.isActive) continuation.resume(value) }
                override fun failure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
            })
            continuation.invokeOnCancellation { operation.cancel() }
        }
    }
    HLAppGraph.filePicker = object : HLFilePicker {
        override suspend fun pick(photos: Boolean): List<HLPickedFile> = suspendCancellableCoroutine { continuation ->
            val callback = object : HLUIFilesCallback {
                override fun success(files: List<HLPickedFile>) { if (continuation.isActive) continuation.resume(files) }
                override fun failure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
            }
            val operation = if (photos) uiBridge.pickPhotos(callback) else uiBridge.pickFiles(callback)
            continuation.invokeOnCancellation { operation.cancel() }
        }
        override suspend fun read(handle: String, offset: Long, length: Int): ByteArray = suspendCancellableCoroutine { continuation ->
            val operation = uiBridge.readFile(handle, offset, length, object : HLUIBytesCallback {
                override fun success(bytes: ByteArray) { if (continuation.isActive) continuation.resume(bytes) }
                override fun failure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
            })
            continuation.invokeOnCancellation { operation.cancel() }
        }
        override suspend fun release(handle: String) { uiBridge.releaseFile(handle) }
    }
    HLAppGraph.notificationPermissions = object : HLNotificationPermissions {
        override suspend fun request() = push.requestPermission()
    }
    HLAppGraph.saveFile = { name, bytes ->
        suspendCancellableCoroutine { continuation ->
            val operation = uiBridge.saveFile(name, bytes, object : HLUIUnitCallback {
                override fun success() { if (continuation.isActive) continuation.resume(Unit) }
                override fun failure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
            })
            continuation.invokeOnCancellation { operation.cancel() }
        }
    }
    return HLIOSAppRuntime(repository, scope, push)
}

internal class HLIOSPushAdapter(private val bridge: HLNativeUIBridge, private val remotePushEnabled: Boolean) : HLPushService {
    private var registered = false
    private val updates = MutableSharedFlow<HLPushRegistration>(replay = 1, extraBufferCapacity = 1)
    override val registrations = updates.asSharedFlow()
    override fun appMetadata() = bridge.appMetadata()
    override suspend fun configure(options: HLFirebasePublicOptions?): HLPushAvailability {
        check(remotePushEnabled) { "当前签名不支持远程推送。电脑连接、会话和文件功能仍可正常使用。" }
        return if (registered) HLPushAvailability.AVAILABLE else HLPushAvailability.UNCONFIGURED
    }
    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        val operation = bridge.requestNotificationPermission(object : HLUIBooleanCallback {
            override fun success(value: Boolean) { if (continuation.isActive) continuation.resume(value) }
            override fun failure(message: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message)) }
        })
        continuation.invokeOnCancellation { operation.cancel() }
    }
    fun register(token: String, environment: String, appId: String) {
        if (!remotePushEnabled) return
        require(environment in setOf("sandbox", "production")) { "Invalid APNs environment" }
        registered = true
        updates.tryEmit(HLPushRegistration("apns", token, environment, appId))
    }
}
