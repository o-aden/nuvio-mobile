package com.nuvio.app.core.managed

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal interface ManagedDeviceStore {
    fun loadCredential(): String?
    fun saveCredential(credential: String?)
    fun loadAccount(): String?
    fun saveAccount(account: String?)
    fun installationId(): String
    fun deviceName(): String
    fun generation(): Long
    fun isBlocked(): Boolean
    fun setBlocked(blocked: Boolean)
    fun supportsSecurePersistence(): Boolean
    fun loadProtectedValue(key: String): String?
    fun saveProtectedValue(key: String, value: String?)
}

internal expect object ManagedDeviceStorage : ManagedDeviceStore {
    override fun loadCredential(): String?
    override fun saveCredential(credential: String?)
    override fun loadAccount(): String?
    override fun saveAccount(account: String?)
    override fun installationId(): String
    override fun deviceName(): String
    override fun generation(): Long
    override fun isBlocked(): Boolean
    override fun setBlocked(blocked: Boolean)
    override fun supportsSecurePersistence(): Boolean
    override fun loadProtectedValue(key: String): String?
    override fun saveProtectedValue(key: String, value: String?)
}

@OptIn(ExperimentalUuidApi::class)
internal fun newManagedInstallationId(): String = Uuid.random().toString()
