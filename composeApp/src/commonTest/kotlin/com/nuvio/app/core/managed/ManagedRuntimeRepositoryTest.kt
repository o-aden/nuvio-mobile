package com.nuvio.app.core.managed

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManagedRuntimeRepositoryTest {
    @Test
    fun `require runtime performs fresh no store authorized GET and publishes only safe state`() = runTest {
        var calls = 0
        var captured: HttpRequestData? = null
        val client = mockClient { request ->
            calls++
            captured = request
            MockResponse(
                body = runtimePayload(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val storage = FakeStorage()
        val repository = repository(client, storage)

        val first = repository.requireRuntime()
        val second = repository.requireRuntime()

        assertEquals(2, calls, "each launch request should validate a fresh remote runtime")
        assertEquals(HttpMethod.Get, captured?.method)
        assertEquals("Bearer ${FakeStorage.CREDENTIAL}", captured?.headers?.get(HttpHeaders.Authorization))
        assertEquals("no-store", captured?.headers?.get(HttpHeaders.CacheControl))
        assertEquals(first, second)
        assertIs<ManagedRuntimeState.Ready>(repository.state.value)
        assertFalse(repository.state.value.toString().contains(FakeStorage.CREDENTIAL))
        assertFalse(repository.state.value.toString().contains(first.manifestUrl))
        client.close()
    }

    @Test
    fun `disabled backend and missing account credential block network access`() = runTest {
        var calls = 0
        val client = mockClient { calls++; MockResponse(body = runtimePayload()) }
        val storage = FakeStorage()
        val repository = ManagedRuntimeRepositoryImpl(client, storage.access(), { "https://custom.backend" }, { storage.activeAccount })
        assertFalse(repository.enabled)
        assertEquals(ManagedAccessFailure.DISABLED, assertFailsWith<ManagedAccessException> { repository.requireRuntime() }.failure)
        assertEquals(0, calls)

        val noCredential = FakeStorage().apply { credential = null }
        val second = repository(client, noCredential)
        assertEquals(ManagedAccessFailure.UNPAIRED, assertFailsWith<ManagedAccessException> { second.requireRuntime() }.failure)
        assertEquals(0, calls)
        client.close()
    }

    @Test
    fun `authoritative rejection marks enrollment blocked while not ready does not`() = runTest {
        var status = HttpStatusCode.Unauthorized
        val client = mockClient { MockResponse(body = "{}", status = status) }
        val storage = FakeStorage()
        val repository = repository(client, storage)

        assertEquals(ManagedAccessFailure.BLOCKED, assertFailsWith<ManagedAccessException> { repository.requireRuntime() }.failure)
        assertTrue(storage.blocked)
        assertIs<ManagedRuntimeState.Blocked>(repository.state.value)

        storage.blocked = false
        status = HttpStatusCode.Conflict
        assertEquals(ManagedAccessFailure.UNAVAILABLE, assertFailsWith<ManagedAccessException> { repository.requireRuntime() }.failure)
        assertFalse(storage.blocked)
        assertIs<ManagedRuntimeState.Unavailable>(repository.state.value)
        client.close()
    }

    @Test
    fun `response from replaced account or credential is rejected without blocking new session`() = runTest {
        val storage = FakeStorage()
        val client = mockClient {
            storage.activeAccount = "account-b"
            MockResponse(body = runtimePayload())
        }
        val repository = repository(client, storage)

        assertEquals(ManagedAccessFailure.STALE_CONTEXT, assertFailsWith<ManagedAccessException> { repository.requireRuntime() }.failure)
        assertFalse(storage.blocked)
        assertIs<ManagedRuntimeState.Unpaired>(repository.state.value)
        assertEquals(FakeStorage.CREDENTIAL, storage.credential)
        assertEquals("account-a", storage.account)
        assertEquals(3L, storage.generation)
        client.close()
    }

    @Test
    fun `invalid response never publishes a managed runtime`() = runTest {
        val client = mockClient {
            MockResponse(body = runtimePayload().replace("\"high\"]", "\"high\",\"instant-av1\"]"))
        }
        val repository = repository(client, FakeStorage())
        assertEquals(ManagedAccessFailure.INVALID_RUNTIME, assertFailsWith<ManagedAccessException> { repository.requireRuntime() }.failure)
        assertIs<ManagedRuntimeState.Unavailable>(repository.state.value)
        client.close()
    }

    @Test
    fun `managed secret path fetch requires https and rejects redirects`() = runTest {
        var calls = 0
        var requestedUrl: String? = null
        var requestHeaders: Headers? = null
        val client = mockClient { request ->
            calls++
            requestedUrl = request.url.toString()
            requestHeaders = request.headers
            MockResponse(
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://redirect.example/secret"),
            )
        }
        val repository = repository(client, FakeStorage())

        assertFailsWith<ManagedAccessException> { repository.fetchManagedText("http://aio.example/secret/manifest.json") }
        assertEquals(0, calls)
        assertEquals("Managed streaming is unavailable.", assertFailsWith<ManagedAccessException> {
            repository.fetchManagedText("https://aio.example/stremio/uuid/password/v/high/manifest.json")
        }.message)
        assertEquals(1, calls)
        assertTrue(requestedUrl.orEmpty().contains("/stremio/uuid/password/"))
        assertEquals("no-store", requestHeaders?.get(HttpHeaders.CacheControl))
        assertEquals(null, requestHeaders?.get(HttpHeaders.Authorization))
        client.close()
    }

    private fun repository(client: HttpClient, storage: FakeStorage) =
        ManagedRuntimeRepositoryImpl(client, storage.access(), { "https://nuvio.adenstack.com" }, { storage.activeAccount })

    private fun mockClient(handler: (HttpRequestData) -> MockResponse): HttpClient =
        HttpClient(
            MockEngine { request ->
                val response = handler(request)
                respond(response.body, response.status, response.headers)
            },
        ) {
            followRedirects = false
        }

    private data class MockResponse(
        val body: String = "",
        val status: HttpStatusCode = HttpStatusCode.OK,
        val headers: Headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private class FakeStorage {
        var credential: String? = CREDENTIAL
        var account: String? = "account-a"
        var activeAccount: String? = "account-a"
        var generation: Long = 3
        var blocked: Boolean = false

        fun access() = ManagedRuntimeStorageAccess(
            loadCredential = { credential },
            loadAccount = { account },
            generation = { generation },
            isBlocked = { blocked },
            setBlocked = { blocked = it },
        )

        companion object {
            val CREDENTIAL = "7." + "A".repeat(43)
        }
    }

    private fun runtimePayload(): String =
        """{"device":{"id":7,"name":"Phone"},"effectivePerformanceProfile":"high","effectiveCapabilities":{"av1HardwareSupport":"unknown"},"effectiveAioVariantIds":["high"],"aiostreamsManifestUrl":"https://aio.example/stremio/uuid/secret/v/high/manifest.json","runtimeRevision":"${"a".repeat(64)}"}"""
}