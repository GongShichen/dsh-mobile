@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dsh.mobile.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.*
import platform.Security.*

actual fun createSecureStore(): HLSecureStore = HLKeychainSecureStore()

actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0)
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { bytes ->
        bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) == errSecSuccess) { "Secure random generation failed" } }
    }
}

private class HLKeychainSecureStore : HLSecureStore {
    private fun query(key: String): CFMutableDictionaryRef {
        val result = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: error("Keychain query allocation failed")
        CFDictionarySetValue(result, kSecClass, kSecClassGenericPassword)
        val service = CFStringCreateWithCString(null, "io.dsh.mobile.credentials.v1", kCFStringEncodingUTF8)
        val account = CFStringCreateWithCString(null, key, kCFStringEncodingUTF8)
        try {
            CFDictionarySetValue(result, kSecAttrService, service)
            CFDictionarySetValue(result, kSecAttrAccount, account)
            CFDictionarySetValue(result, kSecAttrSynchronizable, kCFBooleanFalse)
        } finally {
            if (service != null) CFRelease(service)
            if (account != null) CFRelease(account)
        }
        return result
    }
    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.Default) {
        val query = query(key)
        try {
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val output = alloc<CFTypeRefVar>()
                output.value = null
                when (val status = SecItemCopyMatching(query, output.ptr)) {
                    errSecItemNotFound -> null
                    errSecSuccess -> {
                        val data: CFDataRef = output.value?.reinterpret() ?: error("Keychain returned no credential")
                        try {
                            val count = CFDataGetLength(data).toInt()
                            if (count == 0) ByteArray(0) else CFDataGetBytePtr(data)!!.readBytes(count)
                        } finally { CFRelease(data) }
                    }
                    else -> error("Keychain read failed ($status)")
                }
            }
        } finally { CFRelease(query) }
    }
    override suspend fun put(key: String, value: ByteArray): Unit = withContext(Dispatchers.Default) {
        val query = query(key)
        val attributes = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: error("Keychain attributes allocation failed")
        val data = if (value.isEmpty()) CFDataCreate(null, null, 0) else value.usePinned {
            CFDataCreate(null, it.addressOf(0).reinterpret(), value.size.toLong())
        }
        try {
            CFDictionarySetValue(attributes, kSecValueData, data)
            CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
            val updated = SecItemUpdate(query, attributes)
            if (updated == errSecItemNotFound) {
                CFDictionarySetValue(query, kSecValueData, data)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                check(SecItemAdd(query, null) == errSecSuccess) { "Keychain insert failed" }
            } else check(updated == errSecSuccess) { "Keychain update failed ($updated)" }
        } finally {
            if (data != null) CFRelease(data)
            CFRelease(attributes)
            CFRelease(query)
        }
    }
    override suspend fun remove(key: String): Unit = withContext(Dispatchers.Default) {
        val query = query(key)
        try {
            val status = SecItemDelete(query)
            check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain deletion failed ($status)" }
        } finally { CFRelease(query) }
    }
}
