package io.dsh.mobile.platform

import java.security.MessageDigest

actual fun createSha256Hasher(): HLSHA256Hasher = object : HLSHA256Hasher {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var finished = false
    override fun update(bytes: ByteArray) { check(!finished); digest.update(bytes) }
    override fun finishHex(): String {
        check(!finished); finished = true
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    override fun close() { finished = true; digest.reset() }
}
