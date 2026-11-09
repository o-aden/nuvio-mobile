package com.nuvio.app.features.details

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.managed.ManagedDeviceStorage
import com.nuvio.app.core.managed.ManagedRuntimeRepository
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.isSupportedDownloadUrl
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_managed_access_unavailable
import nuvio.composeapp.generated.resources.downloads_no_compatible_stream
import org.jetbrains.compose.resources.getString

internal object DetailsDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = mutableSetOf<Triple<Int, String, String>>()

    fun start(meta: MetaDetails, episode: MetaVideo?, profileId: Int) {
        val season = episode?.season
        val episodeNumber = episode?.episode
        val videoId = if (episode == null) {
            meta.id
        } else {
            if (season == null || episodeNumber == null) return
            episode.id.takeIf { it.isNotBlank() }
                ?: buildPlaybackVideoId(meta.id, season, episodeNumber, episode.id)
        }

        scope.launch {
            if (ProfileRepository.activeProfileId != profileId) return@launch
            val key = Triple(profileId, meta.id, videoId)
            if (!pending.add(key)) return@launch
            try {
                if (DownloadsRepository.hasDownload(meta.id, season, episodeNumber)) {
                    NuvioToastController.show(DownloadEnqueueResult.AlreadyExists.toastMessage())
                    return@launch
                }
                val account = (AuthRepository.state.value as? AuthState.Authenticated)?.userId
                val generation = ManagedDeviceStorage.generation()
                val group = StreamsRepository.fetchManagedStreamGroup(meta.type, videoId)
                if (!ManagedRuntimeRepository.enabled || account == null ||
                    ProfileRepository.activeProfileId != profileId ||
                    (AuthRepository.state.value as? AuthState.Authenticated)?.userId != account ||
                    ManagedDeviceStorage.generation() != generation
                ) return@launch

                val stream = group.streams.firstOrNull { it.playableDirectUrl?.isSupportedDownloadUrl() == true }
                if (stream == null) {
                    NuvioToastController.show(getString(Res.string.downloads_no_compatible_stream))
                    return@launch
                }
                val result = DownloadsRepository.enqueueFromStream(
                    contentType = meta.type,
                    videoId = videoId,
                    parentMetaId = meta.id,
                    parentMetaType = meta.type,
                    title = meta.name,
                    logo = meta.logo,
                    poster = meta.poster,
                    background = meta.background,
                    seasonNumber = season,
                    episodeNumber = episodeNumber,
                    episodeTitle = episode?.title,
                    episodeThumbnail = episode?.thumbnail,
                    stream = stream,
                )
                NuvioToastController.show(result.toastMessage())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NuvioToastController.show(getString(Res.string.downloads_managed_access_unavailable))
            } finally {
                pending.remove(key)
            }
        }
    }
}
