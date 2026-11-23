package com.dallas.cabletv.data

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** A player that sends the same client identity the provider API calls use. */
fun newPlayer(context: Context): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(PLAYER_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .build()
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
