package com.nuvio.app.core.managed

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private fun currentActiveAccount(): String? =
    (AuthRepository.state.value as? AuthState.Authenticated)
        ?.takeUnless { it.isAnonymous }
        ?.userId

private const val MANAGED_RESPONSE_MAX_BYTES = 1024 * 1024
private const val RUNTIME_RESPONSE_MAX_BYTES = 64 * 1024

/** Snapshot adapter keeps platform-owned secure storage replaceable in common tests. */
internal data class ManagedRuntimeStorageAccess(
    val loadCredential: () -> String?,
    val loadAccount: () -> String?,
    val generation: () -> Long,
    val isBlocked: () -> Boolean,
    val setBlocked: (Boolean) -> Unit,
) {
    companion object {
        fun platform() = ManagedRuntimeStorageAccess(
            loadCredential = ManagedDeviceStorage::loadCredential,
            loadAccount = ManagedDeviceStorage::loadAccount,
            generation = ManagedDeviceStorage::generation,
            isBlocked = ManagedDeviceStorage::isBlocked,
            setBlocked = ManagedDeviceStorage::setBlocked,
        )
    }
}

/**
 * Fetches a freshly authorized Manager runtime for each playback launch. This class never enrolls,
 * rotates credentials, or serves its status-flow value as a substitute for a remote check.
 */
internal class ManagedRuntimeRepositoryImpl internal constructor(
    private val client: HttpClient,
    private val storage: ManagedRuntimeStorageAccess,
    private val backendUrl: () -> String,
    private val activeAccount: () -> String? = ::currentActiveAccount,
) {
    constructor() : this(
        client = createApiHttpClient(),
        storage = ManagedRuntimeStorageAccess.platform(),
        backendUrl = { ServerConfigurationRepository.active.value.backendUrl },
        activeAccount = ::currentActiveAccount,
    )

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ManagedRuntimeState> = _state.asStateFlow()

    /** The managed source is only exposed on Nuvio's canonical HTTPS backend. */
    val enabled: Boolean
        get() = ManagedRuntimeContract.isEnabledForBackend(backendUrl())

    suspend fun requireRuntime(): ManagedRuntime {
        if (!enabled) return fail(ManagedAccessFailure.DISABLED, ManagedRuntimeState.Disabled)
        val local = runCatching {
            StorageSnapshot(storage.isBlocked(), storage.loadCredential(), storage.loadAccount(), storage.generation())
        }.getOrElse { return fail(ManagedAccessFailure.UNAVAILABLE, ManagedRuntimeState.Unavailable) }
        if (local.blocked) return fail(ManagedAccessFailure.BLOCKED, ManagedRuntimeState.Blocked)
        val credential = local.credential
            ?: return fail(ManagedAccessFailure.UNPAIRED, ManagedRuntimeState.Unpaired)
        val account = local.account?.takeIf(String::isNotBlank)
            ?: return fail(ManagedAccessFailure.UNPAIRED, ManagedRuntimeState.Unpaired)
        val deviceId = ManagedRuntimeContract.credentialDeviceId(credential)
            ?: return fail(ManagedAccessFailure.UNPAIRED, ManagedRuntimeState.Unpaired)
        if (account != activeAccount()) return fail(ManagedAccessFailure.UNPAIRED, ManagedRuntimeState.Unpaired)
        val generation = local.generation
        val snapshot = RequestSnapshot(credential, account, generation)
        if (!isCurrent(snapshot)) return fail(ManagedAccessFailure.STALE_CONTEXT, ManagedRuntimeState.Unpaired)
        _state.value = ManagedRuntimeState.Loading

        val response = try {
            client.get("${ManagedRuntimeContract.MANAGER_BASE_URL}/api/device/runtime") {
                header(HttpHeaders.Authorization, "Bearer $credential")
                header(HttpHeaders.CacheControl, "no-store")
                accept(ContentType.Application.Json)
            }
        } catch (error: CancellationException) {
            _state.value = currentSafeState()
            throw error
        } catch (_: Exception) {
            return fail(ManagedAccessFailure.UNAVAILABLE, ManagedRuntimeState.Unavailable)
        }

        if (!enabled || !isCurrent(snapshot)) return fail(ManagedAccessFailure.STALE_CONTEXT, currentSafeState())
        if (response.status.value == 401 || response.status.value == 403) {
            if (runCatching { storage.setBlocked(true) }.isFailure) {
                return fail(ManagedAccessFailure.UNAVAILABLE, ManagedRuntimeState.Unavailable)
            }
            _state.value = ManagedRuntimeState.Blocked
            return throwAccess(ManagedAccessFailure.BLOCKED)
        }
        if (response.status.value !in 200..299) {
            return fail(ManagedAccessFailure.UNAVAILABLE, ManagedRuntimeState.Unavailable)
        }

        val payload = try {
            readBoundedResponseBody(
                channel = response.bodyAsChannel(),
                contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                maxBytes = RUNTIME_RESPONSE_MAX_BYTES,
            )
        } catch (error: CancellationException) {
            _state.value = currentSafeState()
            throw error
        } catch (_: Exception) {
            return fail(ManagedAccessFailure.UNAVAILABLE, ManagedRuntimeState.Unavailable)
        }
        if (!isCurrent(snapshot)) return fail(ManagedAccessFailure.STALE_CONTEXT, currentSafeState())

        val runtime = ManagedRuntimeContract.decodeRuntime(
            payload = payload,
            credential = credential,
            account = account,
            generation = generation,
        ) ?: return fail(ManagedAccessFailure.INVALID_RUNTIME, ManagedRuntimeState.Unavailable)

        // Recheck after parsing too; account switches and credential replacement invalidate in-flight work.
        if (!enabled || !isCurrent(snapshot)) {
            return fail(ManagedAccessFailure.STALE_CONTEXT, currentSafeState())
        }
        val currentlyBlocked = runCatching { storage.isBlocked() }.getOrElse {
            return fail(ManagedAccessFailure.UNAVAILABLE, ManagedRuntimeState.Unavailable)
        }
        if (currentlyBlocked) return fail(ManagedAccessFailure.BLOCKED, ManagedRuntimeState.Blocked)
        _state.value = ManagedRuntimeState.Ready(runtime.identity, runtime.deviceName)
        return runtime
    }

    /** Fetches a secret-path managed AIOStreams resource without telemetry breadcrumbs or redirects. */
    suspend fun fetchManagedText(url: String): String {
        val parsed = runCatching { Url(url) }.getOrNull()
            ?: throw ManagedAccessException(ManagedAccessFailure.UNAVAILABLE)
        if (parsed.protocol.name != "https" || parsed.host.isBlank() ||
            !parsed.encodedUser.isNullOrEmpty() || !parsed.encodedPassword.isNullOrEmpty() ||
            parsed.parameters.entries().isNotEmpty() || parsed.fragment.isNotEmpty()
        ) {
            throw ManagedAccessException(ManagedAccessFailure.UNAVAILABLE)
        }
        val response = try {
            client.get(url) {
                header(HttpHeaders.CacheControl, "no-store")
                accept(ContentType.Application.Json)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ManagedAccessException(ManagedAccessFailure.UNAVAILABLE)
        }
        if (response.status.value !in 200..299) {
            throw ManagedAccessException(ManagedAccessFailure.UNAVAILABLE)
        }
        return try {
            readBoundedResponseBody(
                channel = response.bodyAsChannel(),
                contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                maxBytes = MANAGED_RESPONSE_MAX_BYTES,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ManagedAccessException(ManagedAccessFailure.UNAVAILABLE)
        }
    }

    private fun isCurrent(snapshot: RequestSnapshot): Boolean = runCatching {
        enabled &&
            activeAccount() == snapshot.account &&
            storage.loadCredential() == snapshot.credential &&
            storage.loadAccount() == snapshot.account &&
            storage.generation() == snapshot.generation
    }.getOrDefault(false)

    private fun currentSafeState(): ManagedRuntimeState {
        if (!enabled) return ManagedRuntimeState.Disabled
        val deviceState = runCatching {
            Triple(storage.isBlocked(), storage.loadCredential(), storage.loadAccount())
        }.getOrNull() ?: return ManagedRuntimeState.Unavailable
        return when {
            deviceState.first -> ManagedRuntimeState.Blocked
            deviceState.second == null || deviceState.third.isNullOrBlank() || deviceState.third != activeAccount() -> ManagedRuntimeState.Unpaired
            else -> ManagedRuntimeState.Unavailable
        }
    }

    private fun initialState(): ManagedRuntimeState =
        if (!enabled) ManagedRuntimeState.Disabled else ManagedRuntimeState.Unpaired

    private fun fail(failure: ManagedAccessFailure, state: ManagedRuntimeState): Nothing {
        _state.value = state
        return throwAccess(failure)
    }

    private fun throwAccess(failure: ManagedAccessFailure): Nothing =
        throw ManagedAccessException(failure)

    private data class StorageSnapshot(val blocked: Boolean, val credential: String?, val account: String?, val generation: Long)

    private data class RequestSnapshot(
        val credential: String,
        val account: String,
        val generation: Long,
    )
}
object ManagedRuntimeRepository {
    private val implementation by lazy { ManagedRuntimeRepositoryImpl() }

    val enabled: Boolean
        get() = ManagedRuntimeContract.isEnabledForBackend(
            ServerConfigurationRepository.active.value.backendUrl
        )

    val state: StateFlow<ManagedRuntimeState>
        get() = implementation.state

    suspend fun requireRuntime(): ManagedRuntime = implementation.requireRuntime()

    suspend fun fetchManagedText(url: String): String = implementation.fetchManagedText(url)
}
