package com.nuvio.app.core.managed

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MAX_MANAGER_RESPONSE_BYTES = 16 * 1024
private val managedJson = Json { ignoreUnknownKeys = true }

sealed interface ManagedDeviceSessionState {
    data object Idle : ManagedDeviceSessionState
    data object Connecting : ManagedDeviceSessionState
    data object Paired : ManagedDeviceSessionState
    data object Blocked : ManagedDeviceSessionState
    data class Error(val message: String) : ManagedDeviceSessionState
}

internal interface ManagedDeviceTransport {
    suspend fun post(path: String, body: String, bearer: String? = null): ManagedDeviceResponse
}

internal data class ManagedDeviceResponse(val statusCode: Int, val body: String)

internal class ManagedDeviceSessionController(
    private val storage: ManagedDeviceStore,
    private val transport: ManagedDeviceTransport,
    private val isAccountCurrent: (String) -> Boolean,
    private val managerAvailable: () -> Boolean = { isOfficialManagedServer() },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<ManagedDeviceSessionState>(ManagedDeviceSessionState.Idle)
    val state: StateFlow<ManagedDeviceSessionState> = _state.asStateFlow()
    private var lastEnrollmentAttempt: Pair<String, String>? = null

    suspend fun authenticated(account: String, accessToken: String) {
        if (account.isBlank() || accessToken.isBlank() || !storage.supportsSecurePersistence()) return
        if (!managerAvailable()) return

        mutex.withLock {
            if (!isAccountCurrent(account)) return@withLock
            val currentCredential = storage.loadCredential()
            val previousAccount = storage.loadAccount()
            if (previousAccount != null && previousAccount != account) {
                lastEnrollmentAttempt = null
                if (currentCredential != null) signOutCredential() else storage.saveAccount(null)
            }
            if (storage.isBlocked()) {
                _state.value = ManagedDeviceSessionState.Blocked
                return@withLock
            }
            if (currentCredential != null && previousAccount == account) {
                _state.value = ManagedDeviceSessionState.Paired
                return@withLock
            }
            if (!isAccountCurrent(account)) return@withLock

            val attempt = account to storage.installationId()
            if (lastEnrollmentAttempt == attempt) return@withLock
            lastEnrollmentAttempt = attempt
            storage.saveAccount(account)
            _state.value = ManagedDeviceSessionState.Connecting

            val body = managedJson.encodeToString(
                EnrollmentRequest.serializer(),
                EnrollmentRequest(storage.installationId(), storage.deviceName().take(100)),
            )
            try {
                val response = transport.post("/api/device/enroll", body, accessToken)
                if (response.body.length > MAX_MANAGER_RESPONSE_BYTES) {
                    _state.value = ManagedDeviceSessionState.Error("Manager returned an invalid enrollment response.")
                    return@withLock
                }
                if (response.statusCode != 200) {
                    _state.value = ManagedDeviceSessionState.Error(enrollmentError(response.statusCode))
                    return@withLock
                }
                val result = runCatching {
                    managedJson.decodeFromString(EnrollmentResponse.serializer(), response.body)
                }.getOrNull() ?: run {
                    _state.value = ManagedDeviceSessionState.Error("Manager returned an invalid enrollment response.")
                    return@withLock
                }
                if (ManagedRuntimeContract.credentialDeviceId(result.credential) != result.deviceId || result.deviceId <= 0) {
                    _state.value = ManagedDeviceSessionState.Error("Manager returned an invalid enrollment response.")
                    return@withLock
                }
                if (!isAccountCurrent(account) || storage.isBlocked()) return@withLock
                storage.saveCredential(result.credential)
                _state.value = ManagedDeviceSessionState.Paired
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _state.value = ManagedDeviceSessionState.Error("Unable to contact Manager. Check your connection and sign in again.")
            }
        }
    }

    suspend fun signOut() {
        mutex.withLock {
            signOutCredential()
            storage.setBlocked(false)
            lastEnrollmentAttempt = null
            _state.value = ManagedDeviceSessionState.Idle
        }
    }

    suspend fun sessionLost() {
        if (storage.loadAccount() == null) return
        mutex.withLock {
            storage.saveCredential(null)
            storage.saveAccount(null)
            lastEnrollmentAttempt = null
            _state.value = ManagedDeviceSessionState.Idle
        }
    }

    private suspend fun signOutCredential() {
        val credential = storage.loadCredential()
        storage.saveCredential(null)
        storage.saveAccount(null)
        if (credential == null) return
        try {
            transport.post("/api/device/sign-out", "", credential)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Remote sign-out is best effort; local credentials are already removed.
        }
    }

    private fun enrollmentError(status: Int): String = when (status) {
        401 -> "Your Nuvio session expired. Sign in again to connect this device."
        403 -> "This Nuvio account or device is not enabled for Manager enrollment."
        409 -> "This account has reached its 10 device limit. Ask an administrator to free a slot."
        429 -> "Too many enrollment attempts. Wait a minute and sign in again."
        else -> "Manager enrollment is unavailable. Try again later."
    }
}

private fun isOfficialManagedServer(): Boolean {
    val configuration = ServerConfigurationRepository.active.value
    return !configuration.isCustom &&
        configuration.capabilities.emailPasswordAuth &&
        ManagedRuntimeContract.isEnabledForBackend(configuration.backendUrl)
}

@Serializable
private data class EnrollmentRequest(val installationId: String, val name: String)

@Serializable
private data class EnrollmentResponse(val deviceId: Long, val credential: String)

object ManagedDeviceSession {
    private val controller: ManagedDeviceSessionController by lazy {
        ManagedDeviceSessionController(
            storage = ManagedDeviceStorage,
            transport = ManagedDeviceHttpTransport,
            isAccountCurrent = { account ->
                val state = AuthRepository.state.value as? AuthState.Authenticated
                state != null && !state.isAnonymous && state.userId == account
            },
        )
    }

    val state: StateFlow<ManagedDeviceSessionState>
        get() = controller.state

    suspend fun authenticated(account: String, accessToken: String) =
        controller.authenticated(account, accessToken)

    suspend fun signOut() = controller.signOut()

    suspend fun sessionLost() = controller.sessionLost()
}

internal object ManagedDeviceHttpTransport : ManagedDeviceTransport {
    private val client by lazy { createApiHttpClient() }

    override suspend fun post(path: String, body: String, bearer: String?): ManagedDeviceResponse {
        val response = client.post("${ManagedRuntimeContract.MANAGER_BASE_URL}$path") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.CacheControl, "no-store")
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }
        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val boundedBody = readBoundedResponseBody(
            channel = response.bodyAsChannel(),
            contentLength = contentLength,
            maxBytes = MAX_MANAGER_RESPONSE_BYTES,
        )
        return ManagedDeviceResponse(response.status.value, boundedBody)
    }
}
