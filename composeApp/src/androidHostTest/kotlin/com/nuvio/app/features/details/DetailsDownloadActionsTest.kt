package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.DetailActions
import com.nuvio.app.features.details.components.DetailSeriesContent
import com.nuvio.app.features.details.components.DetailSeriesListEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-port")
class DetailsDownloadActionsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun movieDownloadIsVisibleBesidePlayWithAutoplayManualOverride() {
        val tablet = mutableStateOf(false)
        var plays = 0
        var downloads = 0
        compose.setContent {
            NuvioTheme {
                DetailActions(
                    playLabel = "Play",
                    playEnabled = true,
                    isSaved = false,
                    isWatched = false,
                    isTablet = tablet.value,
                    shuffleEnabled = false,
                    onPlayClick = { plays++ },
                    onPlayLongClick = { plays++ },
                    onShuffleClick = null,
                    onWatchedClick = {},
                    onSaveClick = {},
                    onSaveLongClick = null,
                    onDownloadClick = { downloads++ },
                )
            }
        }
        for (isTablet in listOf(false, true)) {
            compose.runOnIdle { tablet.value = isTablet }
            compose.onNodeWithText("Play").assertIsDisplayed()
            compose.onNodeWithContentDescription("Download file").assertIsDisplayed().performTouchInput { click() }
        }
        compose.runOnIdle {
            assertEquals(2, downloads)
            assertEquals(0, plays)
        }
    }

    @Test
    fun episodeDownloadIsVisibleAndHandlesTapsInEveryCardLayout() {
        val layout = mutableStateOf(0)
        val episode = MetaVideo(id = "tt1234567:1:1", title = "Episode", season = 1, episode = 1)
        val meta = MetaDetails(id = "tt1234567", type = "series", name = "Series", videos = listOf(episode))
        var plays = 0
        val downloads = mutableListOf<MetaVideo>()
        compose.setContent {
            NuvioTheme {
                if (layout.value == 2) {
                    DetailSeriesListEpisode(
                        meta = meta,
                        episode = episode,
                        progressByVideoId = emptyMap(),
                        watchedKeys = emptySet(),
                        episodeRatings = emptyMap(),
                        episodeRatingsVisibility = EpisodeRatingsVisibility.SHOW_ALL,
                        blurUnwatchedEpisodes = false,
                        onEpisodeClick = { plays++ },
                        onEpisodeLongPress = {},
                        onEpisodeDownload = { downloads.add(it) },
                    )
                } else {
                    DetailSeriesContent(
                        meta = meta,
                        episodeCardStyle = if (layout.value == 0) MetaEpisodeCardStyle.Horizontal else MetaEpisodeCardStyle.List,
                        onEpisodeClick = { plays++ },
                        onEpisodeLongPress = {},
                        onEpisodeDownload = { downloads.add(it) },
                    )
                }
            }
        }
        for (style in 0..2) {
            compose.runOnIdle { layout.value = style }
            compose.onNodeWithContentDescription("Download file").assertIsDisplayed().performTouchInput { click() }
        }
        compose.runOnIdle {
            assertEquals(List(3) { episode }, downloads)
            assertEquals(0, plays)
        }
    }
}
