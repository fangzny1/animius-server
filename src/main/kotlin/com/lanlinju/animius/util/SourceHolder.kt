package com.lanlinju.animius.util

import com.lanlinju.animius.data.remote.parse.AgedmSource
import com.lanlinju.animius.data.remote.parse.AnimeSource
import com.lanlinju.animius.data.remote.parse.CycanimeSource
import com.lanlinju.animius.data.remote.parse.GirigiriSource
import com.lanlinju.animius.data.remote.parse.GogoanimeSource
import com.lanlinju.animius.data.remote.parse.HiAnimeSource
import com.lanlinju.animius.data.remote.parse.GugufanSource
import com.lanlinju.animius.data.remote.parse.MxdmSource
import com.lanlinju.animius.data.remote.parse.NtdmSource
import com.lanlinju.animius.data.remote.parse.NyafunSource
import com.lanlinju.animius.data.remote.parse.SilisiliSource
import com.lanlinju.animius.data.remote.parse.XifanSource
import com.lanlinju.animius.data.remote.parse.YhdmSource

object SourceHolder {
    val DEFAULT_ANIME_SOURCE = SourceMode.Silisili

    private var _currentSource: AnimeSource = getSource(DEFAULT_ANIME_SOURCE)
    private var _currentSourceMode: SourceMode = DEFAULT_ANIME_SOURCE

    val currentSource: AnimeSource
        get() = _currentSource

    val currentSourceMode: SourceMode
        get() = _currentSourceMode

    var isSourceChanged = false

    fun switchSource(mode: SourceMode) {
        _currentSource = getSource(mode)
        _currentSourceMode = mode
    }

    fun getSource(mode: SourceMode): AnimeSource {
        return when (mode) {
            SourceMode.Yhdm -> YhdmSource
            SourceMode.Silisili -> SilisiliSource
            SourceMode.Mxdm -> MxdmSource
            SourceMode.Agedm -> AgedmSource
            SourceMode.Girigiri -> GirigiriSource
            SourceMode.Nyafun -> NyafunSource
            SourceMode.Cycanime -> CycanimeSource
            SourceMode.Gogoanime -> GogoanimeSource
            SourceMode.Xifan -> XifanSource()
            SourceMode.Ntdm -> NtdmSource()
            SourceMode.Gugufan -> GugufanSource()
            SourceMode.HiAnime -> HiAnimeSource
        }
    }
}

enum class SourceMode {
    Silisili,
    Agedm,
    Girigiri,
    Cycanime,
    Gugufan,
    Mxdm,
    Xifan,
    Ntdm,
    Nyafun,
    Gogoanime,
    Yhdm,
    HiAnime
}
