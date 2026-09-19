package com.lanlinju.animius.data.remote.dto

import com.lanlinju.animius.domain.model.WebVideo

data class SubtitleTrack(
    val label: String,
    val lang: String,
    val url: String
)

data class VideoBean(
    val videoUrl: String,            /* 视频播放地址 */
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList()
) {
    fun toWebVideo(): WebVideo {
        return WebVideo(
            url = videoUrl,
            headers = headers
        )
    }
}
