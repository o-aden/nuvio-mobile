package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedPlaybackPolicyTest {
    @Test fun launchRequiresCurrentRuntimeAndProfile() {
        assertTrue(ManagedPlaybackPolicy.acceptsLaunch("managed:account-a-revision-1", "account-a-revision-1", 1, 1))
        assertFalse(ManagedPlaybackPolicy.acceptsLaunch("managed:account-a-revision-1", "account-b-revision-1", 1, 1))
        assertFalse(ManagedPlaybackPolicy.acceptsLaunch("managed:account-a-revision-1", "account-a-revision-2", 1, 1))
        assertFalse(ManagedPlaybackPolicy.acceptsLaunch("managed:current", "current", 1, 2))
        assertFalse(ManagedPlaybackPolicy.acceptsLaunch("addon:ordinary", "current", 1, 1))
        assertFalse(ManagedPlaybackPolicy.acceptsLaunch(null, "current", 1, 1))
    }

    @Test fun unsupportedCodecsAreRemovedWithoutChangingManagerOrder() {
        val streams = listOf(stream("AV1 2160p"), stream("HEVC 1080p"), stream("H264 1080p"), stream("H264 720p"))
        assertEquals(listOf("H264 1080p", "H264 720p"),
            streams.filter { ManagedPlaybackPolicy.compatible(it, false, false) }.map { it.title })
        assertEquals(streams, streams.filter { ManagedPlaybackPolicy.compatible(it, true, true) })
    }

    @Test fun managedFallbackCandidatesStayWithinAuthorizedDirectStreams() {
        val streams = listOf(stream("AV1"), stream("H264"), stream("fallback"))
            .filter { ManagedPlaybackPolicy.compatible(it, false, true) }
        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams, StreamAutoPlayMode.FIRST_STREAM, "", StreamAutoPlaySource.ALL_SOURCES,
            setOf(ManagedPlaybackPolicy.NAME), emptySet(), emptySet())
        assertEquals("H264", evaluation.stream?.title)
        assertEquals(listOf("H264", "fallback"), evaluation.readyStreams.map { it.title })
    }

    @Test fun externalTorrentAndExecutableSourcesCannotBecomeManagedCandidates() {
        assertFalse(ManagedPlaybackPolicy.compatible(stream("bad").copy(url = "file:///private/file"), true, true))
        assertFalse(ManagedPlaybackPolicy.compatible(stream("bad").copy(url = null, externalUrl = "https://example.test/page"), true, true))
        assertFalse(ManagedPlaybackPolicy.compatible(stream("bad").copy(url = "magnet:?xt=urn:btih:hash"), true, true))
        assertFalse(ManagedPlaybackPolicy.compatible(stream("bad").copy(infoHash = "abc"), true, true))
    }

    private fun stream(title: String) = StreamItem(title = title, url = "https://cdn.example.test/video.mp4",
        addonName = ManagedPlaybackPolicy.NAME, addonId = "managed:current")
}