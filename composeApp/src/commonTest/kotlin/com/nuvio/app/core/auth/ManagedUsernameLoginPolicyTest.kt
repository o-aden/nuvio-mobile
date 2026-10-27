package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedUsernameLoginPolicyTest {
    @Test
    fun mapsTrimmedAsciiUsernameAndPreservesEmailInput() {
        assertEquals(
            ManagedUsernameLoginPolicy.Resolution.AuthIdentifier("u-viewer_7@manager.invalid"),
            ManagedUsernameLoginPolicy.resolve("  VIEWER_7  ", supported = true),
        )
        assertEquals(
            ManagedUsernameLoginPolicy.Resolution.AuthIdentifier(" Viewer@Example.COM "),
            ManagedUsernameLoginPolicy.resolve(" Viewer@Example.COM ", supported = false),
        )
    }

    @Test
    fun rejectsInvalidUsernameAndUnsupportedEnvironment() {
        assertEquals(
            ManagedUsernameLoginPolicy.Resolution.InvalidUsername,
            ManagedUsernameLoginPolicy.resolve("ab!", supported = true),
        )
        assertEquals(
            ManagedUsernameLoginPolicy.Resolution.InvalidUsername,
            ManagedUsernameLoginPolicy.resolve("a".repeat(25), supported = true),
        )
        assertEquals(
            ManagedUsernameLoginPolicy.Resolution.UnsupportedEnvironment,
            ManagedUsernameLoginPolicy.resolve("viewer", supported = false),
        )
    }

    @Test
    fun supportsOnlyOfficialHttpsAccountServerWithEmailAuth() {
        val supported = ServerConfiguration(
            backendUrl = "https://nuvio.adenstack.com",
            publishableKey = "public",
            capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
            isCustom = false,
        )
        assertTrue(ManagedUsernameLoginPolicy.supportsUsernameLogin(supported))
        assertFalse(
            ManagedUsernameLoginPolicy.supportsUsernameLogin(
                supported.copy(backendUrl = "http://nuvio.adenstack.com"),
            ),
        )
        assertFalse(
            ManagedUsernameLoginPolicy.supportsUsernameLogin(
                supported.copy(isCustom = true),
            ),
        )
    }
}
