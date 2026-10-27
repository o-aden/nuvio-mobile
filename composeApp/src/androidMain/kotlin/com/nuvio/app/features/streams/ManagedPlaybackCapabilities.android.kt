package com.nuvio.app.features.streams

import android.media.MediaCodecList
import android.os.Build

internal actual object ManagedPlaybackCapabilities {
    actual val av1Supported: Boolean by lazy { supports("video/av01", hardwareOnly = true) }
    actual val hevcSupported: Boolean by lazy { supports("video/hevc", hardwareOnly = false) }

    private fun supports(mime: String, hardwareOnly: Boolean): Boolean = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
            !codec.isEncoder && codec.supportedTypes.any { it.equals(mime, true) } &&
                (!hardwareOnly || (Build.VERSION.SDK_INT >= 29 && codec.isHardwareAccelerated))
        }
    }.getOrDefault(false)
}