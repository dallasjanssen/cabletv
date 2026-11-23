package com.dallas.cabletv.data

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** How far back a live channel can be paused or rewound from what is already buffered. */
const val LIVE_BUFFER_MS = 3 * 60_000

/**
 * A player that sends the same client identity the provider API calls use.
 * [compact] keeps a small buffer and no rewind, for the two small pictures of Two channels.
 * Those two leave audio focus alone, because two players asking for it would pause each other.
 */
fun newPlayer(context: Context, compact: Boolean = false): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(PLAYER_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
    val load = if (compact) {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 15_000, 1_500, 3_000)
            .setBackBuffer(0, false)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .build()
    } else {
        // The size cap keeps a high bitrate stream from filling the memory before the time limit.
        // The first 15 seconds still load past the cap, so the rewind buffer can never stall playback.
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, LIVE_BUFFER_MS, 1_500, 3_000)
            .setBackBuffer(LIVE_BUFFER_MS, true)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
    val audio = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    fun build(loadControl: DefaultLoadControl): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(audio, !compact)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { TrackMemory.apply(context, it) }

    return runCatching { build(load) }.getOrElse { first ->
        // Some devices fail to create codec state under pressure. Retry once with a lighter buffer.
        val fallbackLoad = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 20_000, 1_500, 3_000)
            .setBackBuffer(0, false)
            .setTargetBufferBytes(24 * 1024 * 1024)
            .build()
        runCatching {
            System.gc()
            build(fallbackLoad)
        }.getOrElse { throw first }
    }
}

/**
 * The last audio language and caption choice, kept on the device so the next channel
 * and the next movie start the same way. Captions use an empty string for Off and
 * [CAPTIONS_ON] when the chosen track has no language code.
 * Nothing here is secret, so it stays out of the encrypted settings.
 */
object TrackMemory {
    private const val PREFS = "cabletv_tracks"
    private const val AUDIO = "audio"
    private const val CAPTIONS = "captions"

    fun apply(context: Context, player: Player) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val audio = prefs.getString(AUDIO, null)
        val captions = if (prefs.contains(CAPTIONS)) prefs.getString(CAPTIONS, "") else null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (!audio.isNullOrBlank()) setPreferredAudioLanguage(audio)
            applyCaptionChoice(captions)
        }.build()
    }

    /** Remembers an audio language and asks the player to prefer it from the next item on. */
    fun saveAudio(context: Context, player: Player, language: String?) {
        if (language.isNullOrBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(AUDIO, language).apply()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(language)
            .build()
    }

    /**
     * Remembers captions. [enabled] false is Off. A language keeps that language next time.
     * A track with no language code still turns captions on.
     */
    fun saveCaptions(context: Context, player: Player, enabled: Boolean, language: String?) {
        val stored = storedCaptionChoice(enabled, language)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CAPTIONS, stored).apply()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            applyCaptionChoice(stored)
        }.build()
    }
}

/** Stored caption choice. Blank is Off. [CAPTIONS_ON] is a track that has no language code. */
internal const val CAPTIONS_ON = "*"

/** What to store after the viewer picks a caption row. */
internal fun storedCaptionChoice(enabled: Boolean, language: String?): String {
    if (!enabled) return ""
    val code = language?.trim()?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
    return code ?: CAPTIONS_ON
}

private fun androidx.media3.common.TrackSelectionParameters.Builder.applyCaptionChoice(stored: String?) {
    when {
        stored == null -> Unit
        stored.isBlank() -> {
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            clearOverridesOfType(C.TRACK_TYPE_TEXT)
            setSelectUndeterminedTextLanguage(false)
        }
        stored == CAPTIONS_ON -> {
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            setPreferredTextLanguages()
            setSelectUndeterminedTextLanguage(true)
        }
        else -> {
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            setPreferredTextLanguage(stored)
            setSelectUndeterminedTextLanguage(false)
        }
    }
}

/**
 * HTTP status from a playback failure, when the player reported one.
 * A bare bad-status error with no code attached is treated as a refusal so the
 * live TS fallback can still run.
 */
fun playbackHttpStatus(error: PlaybackException): Int? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) 400 else null
}
