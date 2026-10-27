package com.nuvio.app.core.managed

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.cinterop.memScoped
import platform.CoreFoundation.*
import platform.Foundation.NSUUID
import platform.Security.*
import platform.UIKit.UIDevice

@OptIn(ExperimentalForeignApi::class)
internal actual object ManagedDeviceStorage : ManagedDeviceStore {
    private const val keyService = "com.nuvio.mobile.managed-device"
    private const val credentialKey = "credential"
    private const val accountKey = "account"
    private const val installationIdKey = "installation_id"
    private const val deviceNameKey = "device_name"
    private const val generationKey = "generation"
    private const val blockedKey = "blocked"

    actual override fun loadCredential(): String? = read(credentialKey)
    actual override fun saveCredential(credential: String?) = update(credentialKey, credential)
    actual override fun loadAccount(): String? = read(accountKey)
    actual override fun saveAccount(account: String?) = update(accountKey, account?.takeIf(String::isNotBlank))

    actual override fun installationId(): String =
        read(installationIdKey) ?: NSUUID().UUIDString.lowercase().also { write(installationIdKey, it) }

    actual override fun deviceName(): String =
        read(deviceNameKey) ?: UIDevice.currentDevice.name.trim()
            .ifBlank { UIDevice.currentDevice.localizedModel.trim() }
            .ifBlank { "Apple device" }
            .take(100)
            .also { write(deviceNameKey, it) }

    actual override fun generation(): Long = read(generationKey)?.toLongOrNull() ?: 0L
    actual override fun isBlocked(): Boolean = read(blockedKey) == "1"

    actual override fun setBlocked(blocked: Boolean) {
        val next = if (blocked) "1" else "0"
        if (read(blockedKey) == next) return
        write(blockedKey, next)
        incrementGeneration()
    }

    actual override fun supportsSecurePersistence(): Boolean = true
    actual override fun loadProtectedValue(key: String): String? = read("protected:$key")
    actual override fun saveProtectedValue(key: String, value: String?) = write("protected:$key", value)

    private fun update(key: String, value: String?) {
        if (read(key) == value) return
        write(key, value)
        incrementGeneration()
    }

    private fun incrementGeneration() {
        write(generationKey, (generation() + 1L).toString())
    }

    private fun read(account: String): String? = query(account) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            check(status == errSecSuccess) { "Unable to read protected managed data (OSStatus $status)" }
            val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
            try {
                val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                ByteArray(CFDataGetLength(data).toInt()) { bytes[it].toByte() }.decodeToString()
            } finally {
                CFRelease(data)
            }
        }
    }

    private fun write(account: String, value: String?) = query(account) { query ->
        if (value == null) {
            val status = SecItemDelete(query)
            check(status == errSecSuccess || status == errSecItemNotFound) {
                "Unable to remove protected managed data (OSStatus $status)"
            }
        } else {
            val bytes = value.encodeToByteArray().toUByteArray()
            require(bytes.isNotEmpty())
            val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong())
                ?: error("Unable to encode protected managed data")
            val attributes = CFDictionaryCreateMutable(
                null, 0L, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
            ) ?: error("Unable to create protected managed data attributes")
            try {
                CFDictionarySetValue(attributes, kSecValueData, data)
                val update = SecItemUpdate(query, attributes)
                if (update == errSecItemNotFound) {
                    CFDictionarySetValue(query, kSecValueData, data)
                    CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                    val added = SecItemAdd(query, null)
                    check(added == errSecSuccess) { "Unable to save protected managed data (OSStatus $added)" }
                } else {
                    check(update == errSecSuccess) { "Unable to update protected managed data (OSStatus $update)" }
                }
            } finally {
                CFRelease(attributes)
                CFRelease(data)
            }
        }
    }

    private inline fun <T> query(accountName: String, block: (CFMutableDictionaryRef) -> T): T {
        val service = CFStringCreateWithCString(null, keyService, kCFStringEncodingUTF8)
            ?: error("Unable to encode managed credential service")
        val account = CFStringCreateWithCString(null, accountName, kCFStringEncodingUTF8)
            ?: error("Unable to encode managed credential key")
        val query = CFDictionaryCreateMutable(
            null, 0L, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        ) ?: error("Unable to create managed credential query")
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(account)
            CFRelease(service)
        }
    }
}
