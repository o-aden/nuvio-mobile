package com.nuvio.app.core.managed

import com.nuvio.app.features.profiles.ProfilePinCrypto
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

const val MANAGED_AIOSTREAMS_NAME = "Managed AIOStreams"

@Serializable
data class ManagedRuntime(
    val deviceId: Long,
    val deviceName: String,
    val performanceProfile: String,
    val av1HardwareSupport: String,
    val variantIds: List<String>,
    val manifestUrl: String,
    val runtimeRevision: String,
    /** Opaque local provenance; never contains the device credential or manifest URL. */
    val identity: String,
)

sealed interface ManagedRuntimeState {
    data object Disabled : ManagedRuntimeState
    data object Unpaired : ManagedRuntimeState
    data object Blocked : ManagedRuntimeState
    data object Loading : ManagedRuntimeState
    data class Ready(val identity: String, val deviceName: String) : ManagedRuntimeState
    data object Unavailable : ManagedRuntimeState
}

enum class ManagedAccessFailure {
    DISABLED,
    UNPAIRED,
    BLOCKED,
    UNAVAILABLE,
    INVALID_RUNTIME,
    STALE_CONTEXT,
}

class ManagedAccessException internal constructor(
    val failure: ManagedAccessFailure,
) : Exception(SAFE_MESSAGE) {
    companion object {
        const val SAFE_MESSAGE = "Managed streaming is unavailable."
    }
}

/** The Manager contract shared with Nuvio TV and Manager. */
object ManagedRuntimeContract {
    const val MANAGER_BASE_URL = "https://manager.adenstack.com"
    const val OFFICIAL_BACKEND_HOST = "nuvio.adenstack.com"

    private val credentialPattern = Regex("^[1-9]\\d*\\.[A-Za-z0-9_-]{43}$")
    private val profiles = setOf("max", "high", "budget", "instant")
    private val av1Values = setOf("unknown", "unsupported", "supported")
    private val streamTypePattern = Regex("^[A-Za-z0-9_-]+$")
    private val json = Json { ignoreUnknownKeys = true }

    fun isEnabledForBackend(backendUrl: String): Boolean {
        val backend = runCatching { Url(backendUrl.trim()) }.getOrNull() ?: return false
        return backend.protocol.name == "https" &&
            backend.host.equals(OFFICIAL_BACKEND_HOST, ignoreCase = true) &&
            backend.port == 443 &&
            backend.encodedUser.isNullOrEmpty() &&
            backend.encodedPassword.isNullOrEmpty() &&
            backend.parameters.entries().isEmpty() &&
            backend.fragment.isEmpty() &&
            backend.encodedPath.orEmpty().let { it.isEmpty() || it == "/" }
    }

    fun credentialDeviceId(credential: String): Long? =
        credential.takeIf(credentialPattern::matches)?.substringBefore('.')?.toLongOrNull()

    fun expectedVariantIds(profile: String, av1HardwareSupport: String): List<String> =
        listOf(profile) + if (profile == "instant" && av1HardwareSupport == "supported") {
            listOf("instant-av1")
        } else {
            emptyList()
        }

    fun decodeRuntime(
        payload: String,
        credential: String,
        account: String,
        generation: Long,
    ): ManagedRuntime? {
        val credentialDeviceId = credentialDeviceId(credential) ?: return null
        val dto = runCatching { json.decodeFromString<ManagerRuntimeResponse>(payload) }.getOrNull() ?: return null
        val runtime = ManagedRuntime(
            deviceId = dto.device.id,
            deviceName = dto.device.name,
            performanceProfile = dto.effectivePerformanceProfile,
            av1HardwareSupport = dto.effectiveCapabilities.av1HardwareSupport,
            variantIds = dto.effectiveAioVariantIds,
            manifestUrl = dto.aiostreamsManifestUrl,
            runtimeRevision = dto.runtimeRevision,
            identity = "",
        )
        if (!isValidRuntime(runtime, credentialDeviceId)) return null
        return runtime.copy(identity = runtimeIdentity(account, generation, runtime))
    }

    fun isValidRuntime(runtime: ManagedRuntime, credentialDeviceId: Long): Boolean {
        if (runtime.deviceId != credentialDeviceId || runtime.deviceName.isBlank()) return false
        if (runtime.performanceProfile !in profiles || runtime.av1HardwareSupport !in av1Values) return false
        if (runtime.variantIds != expectedVariantIds(runtime.performanceProfile, runtime.av1HardwareSupport)) return false
        if (!runtime.runtimeRevision.matches(Regex("[a-f0-9]{64}"))) return false
        val manifest = runCatching { Url(runtime.manifestUrl) }.getOrNull() ?: return false
        if (manifest.protocol.name != "https" || manifest.host.isBlank()) return false
        if (!manifest.encodedUser.isNullOrEmpty() || !manifest.encodedPassword.isNullOrEmpty()) return false
        if (manifest.parameters.entries().isNotEmpty() || manifest.fragment.isNotEmpty()) return false
        val suffix = "/v/${runtime.variantIds.joinToString(",")}/manifest.json"
        return manifest.encodedPath.orEmpty().endsWith(suffix)
    }

    fun runtimeIdentity(account: String, generation: Long, runtime: ManagedRuntime): String {
        val fields = listOf(account, generation.toString(), runtime.deviceId.toString(), runtime.runtimeRevision)
        val framed = fields.joinToString(separator = "") { "${it.length}:$it" }
        return ProfilePinCrypto.sha256Hex(framed)
    }

    fun buildStreamUrl(manifestUrl: String, type: String, videoId: String): String? {
        if (!streamTypePattern.matches(type) || videoId.isBlank()) return null
        val manifest = runCatching { Url(manifestUrl) }.getOrNull() ?: return null
        val path = manifest.encodedPath.orEmpty()
        if (manifest.protocol.name != "https" || path.substringAfterLast('/') != "manifest.json") return null
        if (!manifest.encodedUser.isNullOrEmpty() || !manifest.encodedPassword.isNullOrEmpty()) return null
        if (manifest.parameters.entries().isNotEmpty() || manifest.fragment.isNotEmpty()) return null
        val basePath = path.removeSuffix("/manifest.json")
        val safeType = type.encodeManagedPathSegment()
        val safeVideoId = videoId.encodeManagedPathSegment()
        val host = if (':' in manifest.host) "[${manifest.host}]" else manifest.host
        val defaultPort = if (manifest.protocol.name == "https") 443 else 80
        val portSuffix = if (manifest.port == defaultPort) "" else ":${manifest.port}"
        return "${manifest.protocol.name}://$host$portSuffix$basePath/stream/$safeType/$safeVideoId.json"
    }

    private fun String.encodeManagedPathSegment(): String = buildString {
        encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_.~") {
                append(char)
            } else {
                append('%')
                append(HEX[value shr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

@Serializable
private data class ManagerRuntimeResponse(
    val device: ManagerDevice,
    val effectivePerformanceProfile: String,
    val effectiveCapabilities: ManagerCapabilities,
    val effectiveAioVariantIds: List<String>,
    val aiostreamsManifestUrl: String,
    val runtimeRevision: String,
)

@Serializable
private data class ManagerDevice(val id: Long, val name: String)

@Serializable
private data class ManagerCapabilities(val av1HardwareSupport: String)