package com.nuvio.app.core.network

import com.nuvio.app.core.managed.ManagedDeviceStorage
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class SecureSupabaseSessionManager(
    backendUrl: String,
    private val loadProtectedValue: (String) -> String? = { key ->
        ManagedDeviceStorage.loadProtectedValue(key)
    },
    private val saveProtectedValue: (String, String?) -> Unit = { key, value ->
        ManagedDeviceStorage.saveProtectedValue(key, value)
    },
) : SessionManager {
    private val storageKey = "supabase-session:${backendUrl.trim().trimEnd('/')}"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveSession(session: UserSession) {
        saveProtectedValue(storageKey, json.encodeToString(session))
    }

    override suspend fun loadSession(): UserSession? {
        val stored = try {
            loadProtectedValue(storageKey)
        } catch (_: Exception) {
            return null
        } ?: return null
        return try {
            json.decodeFromString<UserSession>(stored)
        } catch (_: Exception) {
            runCatching { saveProtectedValue(storageKey, null) }
            null
        }
    }

    override suspend fun deleteSession() {
        saveProtectedValue(storageKey, null)
    }
}
