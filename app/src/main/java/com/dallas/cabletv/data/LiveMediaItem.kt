package com.dallas.cabletv.data

import androidx.media3.common.MediaItem

/** How far behind the newest segment live playback aims to stay, in milliseconds. */
const val LIVE_TARGET_OFFSET_MS = 24_000L

/**
 * Builds the media item for a live channel.
 *
 * Playing about 24 seconds behind the newest segment leaves room to absorb a slow provider
 * before the buffer runs dry. Pinning the playback speed to 1.0 stops the player from speeding
 * up or slowing down to chase the live edge, which some TVs make audible.
 *
 * LiveConfiguration is ignored for items that are not live (replays and catch-up), so it is
 * safe to use for every stream LivePlayer loads.
 *
 * For HLS, ExoPlayer clamps the target offset so it fits inside the live window of the playlist.
 * Plain MPEG-TS progressive streams have no live window, so LiveConfiguration has no effect there.
 * That is fine, since those streams have nothing to fall back on anyway.
 */
fun liveMediaItem(url: String): MediaItem =
    MediaItem.Builder()
        .setUri(url)
        .setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                .setMinPlaybackSpeed(1f)
                .setMaxPlaybackSpeed(1f)
                .build()
        )
        .build()
