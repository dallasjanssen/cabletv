package com.dallas.cabletv.data

import android.content.Context
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

/** A player that sends the same client identity the provider API calls use. */
fun newPlayer(context: Context): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(PLAYER_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(15_000, LIVE_BUFFER_MS, 1_500, 3_000)
        .setBackBuffer(LIVE_BUFFER_MS, true)
        .build()
    return ExoPlayer.Builder(context)
        .setLoadControl(load)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .build()
        .also { TrackMemory.apply(context, it) }
}

/**
 * The last audio language and caption choice, kept on the device so the next channel
 * and the next movie start the same way. Captions use an empty string for Off.
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
            when {
                captions == null -> Unit
                captions.isBlank() -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else -> {
                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    setPreferredTextLanguage(captions)
                }
            }
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

    /** Remembers captions. A null or blank language turns them off. */
    fun saveCaptions(context: Context, player: Player, language: String?) {
        val stored = language.orEmpty()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CAPTIONS, stored).apply()
        val builder = player.trackSelectionParameters.buildUpon()
        if (stored.isBlank()) builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(stored)
        player.trackSelectionParameters = builder.build()
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
