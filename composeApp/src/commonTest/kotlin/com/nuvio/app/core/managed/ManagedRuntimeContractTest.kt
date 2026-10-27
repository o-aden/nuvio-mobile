package com.nuvio.app.core.managed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedRuntimeContractTest {
    @Test
    fun `managed integration only enables on exact official backend origin`() {
        assertTrue(ManagedRuntimeContract.isEnabledForBackend("https://nuvio.adenstack.com"))
        assertTrue(ManagedRuntimeContract.isEnabledForBackend("https://NUVIO.ADENSTACK.COM/"))
        assertFalse(ManagedRuntimeContract.isEnabledForBackend("http://nuvio.adenstack.com"))
        assertFalse(ManagedRuntimeContract.isEnabledForBackend("https://nuvio.adenstack.com:444"))
        assertFalse(ManagedRuntimeContract.isEnabledForBackend("https://nuvio.adenstack.com/other"))
        assertFalse(ManagedRuntimeContract.isEnabledForBackend("https://nuvio.adenstack.com?next=evil"))
        assertFalse(ManagedRuntimeContract.isEnabledForBackend("https://user@nuvio.adenstack.com"))
        assertFalse(ManagedRuntimeContract.isEnabledForBackend("https://nuvio.adenstack.com.evil.test"))
    }

    @Test
    fun `runtime parser accepts exact profile capability variant contract`() {
        for ((profile, av1, variants) in listOf(
            Triple("max", "supported", "[\"max\"]"),
            Triple("high", "unknown", "[\"high\"]"),
            Triple("budget", "unsupported", "[\"budget\"]"),
            Triple("instant", "unknown", "[\"instant\"]"),
            Triple("instant", "supported", "[\"instant\",\"instant-av1\"]"),
        )) {
            val runtime = parse(profile, av1, variants)
            assertNotNull(runtime, "$profile/$av1 should be accepted")
            assertEquals(7L, runtime.deviceId)
            assertEquals(listOf(profile) + if (profile == "instant" && av1 == "supported") listOf("instant-av1") else emptyList(), runtime.variantIds)
            assertEquals(64, runtime.runtimeRevision.length)
        }
    }

    @Test
    fun `runtime parser rejects mismatched or unsafe response fields`() {
        assertNull(parse("ultra", "supported", "[\"ultra\"]"))
        assertNull(parse("instant", "supported", "[\"instant\"]"))
        assertNull(parse("high", "unsupported", "[\"high\",\"instant-av1\"]"))
        assertNull(response(deviceId = 8).let(::decode))
        assertNull(response(revision = "bad").let(::decode))
        assertNull(response(manifest = "http://aio.example/stremio/u/p/v/high/manifest.json").let(::decode))
        assertNull(response(manifest = "https://user:pass@aio.example/stremio/u/v/high/manifest.json").let(::decode))
        assertNull(response(manifest = "https://aio.example/stremio/u/p/v/high/manifest.json?x=1").let(::decode))
        assertNull(response(manifest = "https://aio.example/stremio/u/p/v/high/manifest.json#x").let(::decode))
    }

    @Test
    fun `runtime identity is opaque and tied to account generation and revision`() {
        val runtime = assertNotNull(parse("high", "unknown", "[\"high\"]"))
        assertFalse(runtime.identity.contains(CREDENTIAL))
        assertFalse(runtime.identity.contains(runtime.manifestUrl))
        assertEquals(64, runtime.identity.length)
        assertFalse(runtime.identity == ManagedRuntimeContract.runtimeIdentity("other-account", 3, runtime))
        assertFalse(runtime.identity == ManagedRuntimeContract.runtimeIdentity("account-a", 4, runtime))
        assertFalse(runtime.identity == ManagedRuntimeContract.runtimeIdentity(
            "account-a", 3, runtime.copy(runtimeRevision = "b".repeat(64)),
        ))
    }

    @Test
    fun `stream url replaces manifest and encodes each dynamic path segment`() {
        assertEquals(
            "https://aio.example/stremio/uuid/secret/v/instant/stream/series/tt123%3A1%2F2.json",
            ManagedRuntimeContract.buildStreamUrl(
                "https://aio.example/stremio/uuid/secret/v/instant/manifest.json",
                "series",
                "tt123:1/2",
            ),
        )
        assertNull(ManagedRuntimeContract.buildStreamUrl("http://aio.example/manifest.json", "movie", "tt1"))
        assertNull(ManagedRuntimeContract.buildStreamUrl("https://aio.example/manifest.json", "", "tt1"))
        assertNull(ManagedRuntimeContract.buildStreamUrl("https://aio.example/manifest.json", "..", "tt1"))
    }

    private fun parse(profile: String, av1: String, variants: String): ManagedRuntime? {
        val manifestVariants = if (profile == "instant" && av1 == "supported") "instant,instant-av1" else profile
        return decode(response(
            profile = profile,
            av1 = av1,
            variants = variants,
            manifest = "https://aio.example/stremio/uuid/secret/v/$manifestVariants/manifest.json",
        ))
    }

    private fun decode(payload: String): ManagedRuntime? = ManagedRuntimeContract.decodeRuntime(
        payload = payload,
        credential = CREDENTIAL,
        account = "account-a",
        generation = 3,
    )

    private fun response(
        deviceId: Long = 7,
        profile: String = "high",
        av1: String = "unknown",
        variants: String = "[\"high\"]",
        revision: String = "a".repeat(64),
        manifest: String = "https://aio.example/stremio/uuid/secret/v/high/manifest.json",
    ): String = """{"device":{"id":$deviceId,"name":"Phone"},"effectivePerformanceProfile":"$profile","effectiveCapabilities":{"av1HardwareSupport":"$av1"},"effectiveAioVariantIds":$variants,"aiostreamsManifestUrl":"$manifest","runtimeRevision":"$revision"}"""

    private companion object {
        val CREDENTIAL = "7." + "A".repeat(43)
    }
}
