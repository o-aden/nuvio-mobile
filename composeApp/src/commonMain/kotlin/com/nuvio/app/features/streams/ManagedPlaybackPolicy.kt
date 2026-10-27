package com.nuvio.app.features.streams

/** Manager owns ranking; the phone only removes formats it cannot decode. */
internal object ManagedPlaybackPolicy {
    const val PREFIX = "managed:"
    const val NAME = "Managed AIOStreams"

    fun acceptsLaunch(addonId: String?, runtimeIdentity: String, profileId: Int, activeProfileId: Int): Boolean =
        profileId == activeProfileId && addonId == PREFIX + runtimeIdentity

    fun compatible(stream: StreamItem, av1Supported: Boolean, hevcSupported: Boolean): Boolean {
        val url = stream.playableDirectUrl ?: return false
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return false
        if (stream.clientResolve != null || stream.isTorrentStream) return false
        val description = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints.filename)
            .joinToString(" ").lowercase()
        if (!av1Supported && Regex("(?<![a-z0-9])(?:av1|av01)(?![a-z0-9])").containsMatchIn(description)) return false
        if (!hevcSupported && Regex("(?<![a-z0-9])(?:hevc|h[ .]?265|x265)(?![a-z0-9])").containsMatchIn(description)) return false
        return true
    }
}

internal expect object ManagedPlaybackCapabilities {
    val av1Supported: Boolean
    val hevcSupported: Boolean
}