package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NuvioPlusLanguageDefaultsTest {
    @Test fun defaultTargetsUseEnglishThenOriginalLanguage() {
        val settings = PlayerSettingsUiState()
        assertEquals(listOf("en", "ja"), resolvePreferredAudioLanguageTargets(
            settings.preferredAudioLanguage, settings.secondaryPreferredAudioLanguage,
            deviceLanguages = listOf("fr"), contentOriginalLanguage = "ja"))
    }

    @Test fun englishFullSubtitleWinsOverEarlierForcedTrack() {
        val settings = PlayerSettingsUiState()
        val plan = assertNotNull(resolveSubtitleAutoSelectionPlan(
            AudioTrack(0, "audio", "Original", "ja"), listOf("en", "ja"),
            listOf(settings.preferredSubtitleLanguage), settings.subtitleStyle.useForcedSubtitles))
        val tracks = listOf(
            SubtitleTrack(0, "forced", "English Forced", "en", isForced = true),
            SubtitleTrack(1, "full", "English", "en"))
        assertEquals(1, findPreferredSubtitleTrackIndex(tracks, plan.targets, plan.mode))
    }
}