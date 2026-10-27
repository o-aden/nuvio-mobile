package com.nuvio.app.core.auth

import com.nuvio.app.core.managed.ManagedRuntimeContract
import com.nuvio.app.core.network.ServerConfiguration

internal object ManagedUsernameLoginPolicy {
    sealed interface Resolution {
        data class AuthIdentifier(val value: String) : Resolution
        data object InvalidUsername : Resolution
        data object UnsupportedEnvironment : Resolution
    }

    fun supportsUsernameLogin(configuration: ServerConfiguration): Boolean {
        if (configuration.isCustom || !configuration.capabilities.emailPasswordAuth) return false
        return ManagedRuntimeContract.isEnabledForBackend(configuration.backendUrl)
    }

    fun resolve(input: String, supported: Boolean): Resolution {
        if ('@' in input) return Resolution.AuthIdentifier(input)
        if (!supported) return Resolution.UnsupportedEnvironment
        val username = input.trim().map { character ->
            if (character in 'A'..'Z') character + ('a' - 'A') else character
        }.joinToString("")
        if (!USERNAME_PATTERN.matches(username)) return Resolution.InvalidUsername
        return Resolution.AuthIdentifier("u-$username@manager.invalid")
    }

    private val USERNAME_PATTERN = Regex("^[a-z0-9_]{3,24}$")
}
