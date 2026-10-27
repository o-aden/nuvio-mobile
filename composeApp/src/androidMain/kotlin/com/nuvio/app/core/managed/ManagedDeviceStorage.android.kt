package com.nuvio.app.core.managed

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual object ManagedDeviceStorage : ManagedDeviceStore {
    private const val preferencesName = "nuvio_managed_device"
    private const val keyAlias = "com.nuvio.mobile.managed-device.v1"
    private const val credentialKey = "credential"
    private const val accountKey = "account"
    private const val installationIdKey = "installation_id"
    private const val deviceNameKey = "device_name"
    private const val generationKey = "generation"
    private const val blockedKey = "blocked"
    private lateinit var preferences: SharedPreferences
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        }
    }

    actual override fun loadCredential(): String? = loadProtectedValue(credentialKey)

    actual override fun saveCredential(credential: String?) = synchronized(lock) {
        val current = loadProtectedValueLocked(credentialKey)
        if (current == credential) return@synchronized
        saveProtectedValueLocked(credentialKey, credential)
        bumpGenerationLocked()
    }

    actual override fun loadAccount(): String? = loadProtectedValue(accountKey)

    actual override fun saveAccount(account: String?) = synchronized(lock) {
        val normalized = account?.takeIf(String::isNotBlank)
        val current = loadProtectedValueLocked(accountKey)
        if (current == normalized) return@synchronized
        saveProtectedValueLocked(accountKey, normalized)
        bumpGenerationLocked()
    }

    actual override fun installationId(): String = synchronized(lock) {
        ensureInitialized()
        preferences.getString(installationIdKey, null)?.let { return@synchronized it }
        val value = newManagedInstallationId()
        if (!preferences.edit().putString(installationIdKey, value).commit()) throw IOException("Unable to save managed installation identity")
        value
    }

    actual override fun deviceName(): String = synchronized(lock) {
        ensureInitialized()
        preferences.getString(deviceNameKey, null)?.takeIf(String::isNotBlank)
            ?: formatManagedDeviceName(Build.MANUFACTURER, Build.MODEL, "Android device").also {
                if (!preferences.edit().putString(deviceNameKey, it).commit()) throw IOException("Unable to save managed device name")
            }
    }

    actual override fun generation(): Long = synchronized(lock) {
        ensureInitialized()
        generationLocked()
    }

    actual override fun isBlocked(): Boolean = synchronized(lock) {
        ensureInitialized()
        preferences.getBoolean(blockedKey, false)
    }

    actual override fun setBlocked(blocked: Boolean) = synchronized(lock) {
        ensureInitialized()
        if (preferences.getBoolean(blockedKey, false) == blocked) return@synchronized
        if (!preferences.edit().putBoolean(blockedKey, blocked).putLong(generationKey, generationLocked() + 1).commit()) {
            throw IOException("Unable to save managed device status")
        }
    }

    actual override fun supportsSecurePersistence(): Boolean = true

    actual override fun loadProtectedValue(key: String): String? = synchronized(lock) {
        ensureInitialized()
        loadProtectedValueLocked(key)
    }

    actual override fun saveProtectedValue(key: String, value: String?) = synchronized(lock) {
        ensureInitialized()
        saveProtectedValueLocked(key, value)
    }

    private fun loadProtectedValueLocked(key: String): String? {
        ensureInitialized()
        val stored = preferences.getString(protectedKey(key), null) ?: return null
        return try {
            val parts = stored.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD(aad(key))
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            if (!preferences.edit().remove(protectedKey(key)).commit()) throw IOException("Unable to clear unreadable protected data")
            null
        }
    }

    private fun saveProtectedValueLocked(key: String, value: String?) {
        ensureInitialized()
        val editor = preferences.edit()
        if (value == null) {
            editor.remove(protectedKey(key))
        } else {
            require(value.isNotEmpty())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(aad(key))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            editor.putString(protectedKey(key), "${encode(cipher.iv)}.${encode(encrypted)}")
        }
        if (!editor.commit()) throw IOException("Unable to save protected data")
    }

    private fun protectedKey(key: String): String = "protected:${key.hashCode()}:$key"
    private fun aad(key: String): ByteArray = "$keyAlias:$key".toByteArray(Charsets.UTF_8)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun bumpGenerationLocked() {
        ensureInitialized()
        if (!preferences.edit().putLong(generationKey, generationLocked() + 1).commit()) throw IOException("Unable to update managed device generation")
    }

    private fun generationLocked(): Long = preferences.getLong(generationKey, 0L)
    private fun ensureInitialized() { check(::preferences.isInitialized) { "Managed device storage has not been initialized" } }
    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP or Base64.URL_SAFE)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE)
}

private fun formatManagedDeviceName(manufacturer: String, model: String, fallback: String): String {
    val maker = manufacturer.trim()
    val device = model.trim()
    return when {
        device.isBlank() -> fallback
        maker.isBlank() -> device
        device.startsWith(maker, ignoreCase = true) -> device
        else -> "$maker $device"
    }.take(100)
}
