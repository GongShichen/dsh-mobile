@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dsh.mobile.platform

import kotlinx.cinterop.*
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.CoreCrypto.CC_SHA256_Final

actual fun createSha256Hasher(): Sha256Hasher = object : Sha256Hasher {
    private val context = nativeHeap.alloc<CC_SHA256_CTX>()
    private var closed = false
    private var finished = false
    init { check(CC_SHA256_Init(context.ptr) == 1) }
    override fun update(bytes: ByteArray) {
        check(!closed && !finished)
        if (bytes.isNotEmpty()) bytes.usePinned { check(CC_SHA256_Update(context.ptr, it.addressOf(0), bytes.size.convert()) == 1) }
    }
    override fun finishHex(): String {
        check(!closed && !finished); finished = true
        val output = ByteArray(32)
        output.usePinned { check(CC_SHA256_Final(it.addressOf(0).reinterpret(), context.ptr) == 1) }
        return output.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    override fun close() { if (!closed) { closed = true; nativeHeap.free(context) } }
}
