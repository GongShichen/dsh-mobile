package io.dsh.mobile.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private lateinit var platformContext: Context

/** Call from Application.onCreate before creating repositories. */
fun initializeAndroidPlatform(context: Context) { platformContext = context.applicationContext }

actual fun createSecureStore(): SecureStore {
    check(::platformContext.isInitialized) { "Call initializeAndroidPlatform(applicationContext) first" }
    return AndroidSecureStore(platformContext)
}

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0)
    return ByteArray(size).also { SecureRandom().nextBytes(it) }
}

private class AndroidSecureStore(context: Context) : SecureStore {
    private val directory = File(context.noBackupFilesDir, "secure-store").apply { mkdirs() }
    private val mutex = Mutex()
    private val alias = "io.dsh.mobile.secure-store.aes.v1"
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }
    private fun file(name: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, digest))
    }
    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = file(key)
            val bytes = try { target.openRead().use { it.readBytes() } }
                catch (_: FileNotFoundException) { return@withLock null }
            require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Invalid encrypted credential" }
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
                updateAAD(key.toByteArray())
                doFinal(bytes.copyOfRange(13, bytes.size))
            }
        }
    }
    override suspend fun put(key: String, value: ByteArray): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, wrappingKey())
                updateAAD(key.toByteArray())
            }
            val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(value)
            val target = file(key)
            val stream = target.startWrite()
            try { stream.write(bytes); target.finishWrite(stream) } catch (error: Throwable) { target.failWrite(stream); throw error }
        }
    }
    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) { mutex.withLock { file(key).delete() } }
}
