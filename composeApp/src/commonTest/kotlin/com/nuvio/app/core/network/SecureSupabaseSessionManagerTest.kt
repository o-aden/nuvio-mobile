package com.nuvio.app.core.network

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecureSupabaseSessionManagerTest {
    @Test
    fun sessionStorageIsNamespacedByBackend() = runTest {
        val values = mutableMapOf<String, String>()
        val backendA = SecureSupabaseSessionManager(
            "https://backend-a.example/",
            loadProtectedValue = values::get,
            saveProtectedValue = { key, value -> if (value == null) values.remove(key) else values[key] = value },
        )
        val backendB = SecureSupabaseSessionManager(
            "https://backend-b.example",
            loadProtectedValue = values::get,
            saveProtectedValue = { key, value -> if (value == null) values.remove(key) else values[key] = value },
        )

        backendA.saveSession(testSession("backend-a-access"))

        assertEquals("backend-a-access", backendA.loadSession()?.accessToken)
        assertNull(backendB.loadSession())
        assertEquals(setOf("supabase-session:https://backend-a.example"), values.keys)
    }

    @Test
    fun unavailableSecureStorageReadsAsUnauthenticated() = runTest {
        val manager = SecureSupabaseSessionManager(
            "https://backend.example",
            loadProtectedValue = { error("secure storage unavailable") },
            saveProtectedValue = { _, _ -> error("unused") },
        )

        assertNull(manager.loadSession())
    }

    @Test
    fun malformedSessionCleanupUsesItsBackendNamespaceAndFailsClosed() = runTest {
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String?>>()
        val manager = SecureSupabaseSessionManager(
            "https://backend.example/",
            loadProtectedValue = { key ->
                reads += key
                "not-json"
            },
            saveProtectedValue = { key, value -> writes += key to value },
        )

        assertNull(manager.loadSession())
        val expectedKey = "supabase-session:https://backend.example"
        assertEquals(listOf(expectedKey), reads)
        assertEquals(listOf<Pair<String, String?>>(expectedKey to null), writes)
    }

    private fun testSession(accessToken: String) = UserSession(
        accessToken = accessToken,
        refreshToken = "refresh-token",
        expiresIn = 3_600,
        tokenType = "bearer",
        user = null,
    )
}
