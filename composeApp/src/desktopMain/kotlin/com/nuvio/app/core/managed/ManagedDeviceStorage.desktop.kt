package com.nuvio.app.core.managed

internal actual object ManagedDeviceStorage : ManagedDeviceStore {
    private val values = mutableMapOf<String, String>()
    private var generationValue = 0L
    private var blockedValue = false
    private var installationIdValue: String? = null
    private var deviceNameValue: String? = null

    actual override fun loadCredential(): String? = values[credentialKey]
    actual override fun saveCredential(credential: String?) = update(credentialKey, credential)
    actual override fun loadAccount(): String? = values[accountKey]
    actual override fun saveAccount(account: String?) = update(accountKey, account?.takeIf(String::isNotBlank))

    actual override fun installationId(): String =
        installationIdValue ?: newManagedInstallationId().also { installationIdValue = it }

    actual override fun deviceName(): String =
        deviceNameValue ?: "Nuvio Desktop".also { deviceNameValue = it }

    actual override fun generation(): Long = generationValue
    actual override fun isBlocked(): Boolean = blockedValue

    actual override fun setBlocked(blocked: Boolean) {
        if (blockedValue == blocked) return
        blockedValue = blocked
        generationValue += 1
    }

    actual override fun supportsSecurePersistence(): Boolean = false
    actual override fun loadProtectedValue(key: String): String? = values["protected:$key"]
    actual override fun saveProtectedValue(key: String, value: String?) {
        val storageKey = "protected:$key"
        if (value == null) values.remove(storageKey) else values[storageKey] = value
    }

    private fun update(key: String, value: String?) {
        if (values[key] == value) return
        if (value == null) values.remove(key) else values[key] = value
        generationValue += 1
    }

    private const val credentialKey = "credential"
    private const val accountKey = "account"
}
