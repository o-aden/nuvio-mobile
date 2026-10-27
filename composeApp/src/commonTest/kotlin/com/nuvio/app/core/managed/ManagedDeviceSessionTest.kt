package com.nuvio.app.core.managed

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedDeviceSessionTest {
    @Test
    fun enrollsWithNuvioBearerAndSkipsDuplicateSameAccountEvents() = runTest {
        val store = FakeManagedDeviceStore()
        val transport = FakeManagedDeviceTransport(
            ManagedDeviceResponse(200, """{"deviceId":7,"credential":"7.${"a".repeat(43)}"}"""),
        )
        val controller = ManagedDeviceSessionController(store, transport, { it == "account-1" }) { true }

        controller.authenticated("account-1", "nuvio-access-token")
        val firstGeneration = store.generation()

        controller.authenticated("account-1", "nuvio-access-token")

        assertEquals(1, transport.requests.size)
        assertEquals("/api/device/enroll", transport.requests.single().path)
        assertEquals("nuvio-access-token", transport.requests.single().bearer)
        val requestBody = Json.parseToJsonElement(transport.requests.single().body).jsonObject
        assertEquals(store.installationId(), requestBody.getValue("installationId").jsonPrimitive.content)
        assertEquals(store.deviceName(), requestBody.getValue("name").jsonPrimitive.content)
        assertEquals("account-1", store.loadAccount())
        assertEquals("7.${"a".repeat(43)}", store.loadCredential())
        assertEquals(firstGeneration, store.generation())
        assertIs<ManagedDeviceSessionState.Paired>(controller.state.value)
    }

    @Test
    fun accountSwitchSignsOutOldCredentialBeforeEnrollingNewAccount() = runTest {
        val store = FakeManagedDeviceStore().apply {
            saveCredential("3.${"b".repeat(43)}")
            saveAccount("account-1")
        }
        val transport = FakeManagedDeviceTransport(
            ManagedDeviceResponse(204, ""),
            ManagedDeviceResponse(200, """{"deviceId":4,"credential":"4.${"c".repeat(43)}"}"""),
        )
        val controller = ManagedDeviceSessionController(store, transport, { it == "account-2" }) { true }

        controller.authenticated("account-2", "new-account-token")

        assertEquals(listOf("/api/device/sign-out", "/api/device/enroll"), transport.requests.map { it.path })
        assertEquals("3.${"b".repeat(43)}", transport.requests.first().bearer)
        assertEquals("4.${"c".repeat(43)}", store.loadCredential())
        assertEquals("account-2", store.loadAccount())
    }

    @Test
    fun blockedInstallationDoesNotAutomaticallyEnroll() = runTest {
        val store = FakeManagedDeviceStore().apply { setBlocked(true) }
        val transport = FakeManagedDeviceTransport()
        val controller = ManagedDeviceSessionController(store, transport, { true }) { true }

        controller.authenticated("account-1", "token")

        assertTrue(transport.requests.isEmpty())
        assertIs<ManagedDeviceSessionState.Blocked>(controller.state.value)
    }

    @Test
    fun newAccessTokenDoesNotRepeatAmbiguousEnrollmentButExplicitSignOutAllowsRetry() = runTest {
        val store = FakeManagedDeviceStore()
        val transport = FakeManagedDeviceTransport(
            ManagedDeviceResponse(503, ""),
            ManagedDeviceResponse(200, """{"deviceId":7,"credential":"7.${"a".repeat(43)}"}"""),
        )
        val controller = ManagedDeviceSessionController(store, transport, { it == "account-1" }) { true }

        controller.authenticated("account-1", "token-before-refresh")
        controller.authenticated("account-1", "token-after-refresh")
        assertEquals(1, transport.requests.size)

        controller.signOut()
        controller.authenticated("account-1", "token-after-refresh")

        assertEquals(listOf("token-before-refresh", "token-after-refresh"), transport.requests.map { it.bearer })
        assertEquals("7.${"a".repeat(43)}", store.loadCredential())
    }

    @Test
    fun sessionLostClearsLocalCredentialWithoutRemoteMutationAndPreservesBlockedState() = runTest {
        val store = FakeManagedDeviceStore().apply {
            saveCredential("7.${"a".repeat(43)}")
            saveAccount("account-1")
            setBlocked(true)
        }
        val transport = FakeManagedDeviceTransport()
        val controller = ManagedDeviceSessionController(store, transport, { true }) { true }

        controller.sessionLost()

        assertNull(store.loadCredential())
        assertNull(store.loadAccount())
        assertTrue(store.isBlocked())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun malformedCredentialResponseIsNeverStored() = runTest {
        val store = FakeManagedDeviceStore()
        val transport = FakeManagedDeviceTransport(
            ManagedDeviceResponse(200, """{"deviceId":8,"credential":"8.not-a-device-secret"}"""),
        )
        val controller = ManagedDeviceSessionController(store, transport, { true }) { true }

        controller.authenticated("account-1", "token")

        assertNull(store.loadCredential())
        assertIs<ManagedDeviceSessionState.Error>(controller.state.value)
    }
}

private class FakeManagedDeviceTransport(vararg responses: ManagedDeviceResponse) : ManagedDeviceTransport {
    data class Request(val path: String, val body: String, val bearer: String?)
    val requests = mutableListOf<Request>()
    private val responses = ArrayDeque(responses.toList())

    override suspend fun post(path: String, body: String, bearer: String?): ManagedDeviceResponse {
        requests += Request(path, body, bearer)
        return responses.removeFirstOrNull() ?: error("No test response was configured.")
    }
}

private class FakeManagedDeviceStore : ManagedDeviceStore {
    private var credential: String? = null
    private var account: String? = null
    private var blocked = false
    private var currentGeneration = 0L
    private val protected = mutableMapOf<String, String>()

    override fun loadCredential(): String? = credential
    override fun saveCredential(credential: String?) {
        if (this.credential == credential) return
        this.credential = credential
        currentGeneration += 1
    }
    override fun loadAccount(): String? = account
    override fun saveAccount(account: String?) {
        if (this.account == account) return
        this.account = account
        currentGeneration += 1
    }
    override fun installationId(): String = "11111111-1111-4111-8111-111111111111"
    override fun deviceName(): String = "Google Pixel 9"
    override fun generation(): Long = currentGeneration
    override fun isBlocked(): Boolean = blocked
    override fun setBlocked(blocked: Boolean) {
        if (this.blocked == blocked) return
        this.blocked = blocked
        currentGeneration += 1
    }
    override fun supportsSecurePersistence(): Boolean = true
    override fun loadProtectedValue(key: String): String? = protected[key]
    override fun saveProtectedValue(key: String, value: String?) {
        if (value == null) protected.remove(key) else protected[key] = value
    }
}
