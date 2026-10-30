package io.dsh.mobile.platform

import kotlinx.coroutines.flow.Flow

/** Secrets are scoped to this app installation. Implementations must not log values. */
interface SecureStore {
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, value: ByteArray)
    suspend fun remove(key: String)
}

expect fun createSecureStore(): SecureStore
expect fun secureRandomBytes(size: Int): ByteArray
interface Sha256Hasher {
    fun update(bytes: ByteArray)
    fun finishHex(): String
    fun close()
}
expect fun createSha256Hasher(): Sha256Hasher

fun newOperationId(): String {
    val bytes = secureRandomBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    return bytes.mapIndexed { index, byte ->
        (if (index in setOf(4, 6, 8, 10)) "-" else "") +
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }.joinToString("")
}

interface QrScanner { suspend fun scan(): String? }
data class PickedFile(val name: String, val mimeType: String?, val length: Long, val handle: String)
interface FilePicker {
    suspend fun pick(photos: Boolean = false): List<PickedFile>
    suspend fun read(handle: String, offset: Long, length: Int): ByteArray
    suspend fun release(handle: String)
}
interface NotificationPermissions { suspend fun request(): Boolean }
interface Clock { fun nowMillis(): Long }

data class FirebasePublicOptions(val projectId: String, val appId: String, val apiKey: String, val senderId: String, val packageName: String? = null)
data class PushRegistration(val provider: String, val token: String, val environment: String? = null, val appId: String)
data class PushAppMetadata(val platform: String, val applicationId: String, val teamId: String? = null, val environment: String? = null)
enum class PushAvailability { AVAILABLE, UNCONFIGURED, UNSUPPORTED, PERMISSION_DENIED }
interface PushService {
    val registrations: Flow<PushRegistration>
    fun appMetadata(): PushAppMetadata
    suspend fun configure(options: FirebasePublicOptions?): PushAvailability
    suspend fun requestPermission(): Boolean
}
