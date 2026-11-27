package com.dallas.cabletv.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.C
import androidx.media3.common.Player
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import com.dallas.cabletv.data.NowPlayingInfo
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Logos and posters are scaled to this before they are handed to Android, which limits their size. */
private const val ART_PX = 320

/**
 * Tells Android what is on screen, so Google Home, the Google TV Now Playing panel and the
 * Assistant can show it and pause it. Live TV and movies each own one, tied to their player.
 * It is only active while [show] has something, so nothing can start playback while the app is
 * off screen. Only used on the main thread.
 */
class NowPlayingSession(context: Context, tag: String, private var player: Player) {
    private val appContext = context.applicationContext
    private val session = MediaSession(appContext, "CableTV-$tag")
    private val scope = MainScope()
    private var shown: NowPlayingInfo? = null
    private var art: Bitmap? = null
    private var artFor: String? = null

    /** Next and previous, such as channel up and down. Null hides them. */
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    /** Movies can be moved through. Live TV cannot, so Google Home shows no seek bar for it. */
    var seekable = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (shown == null) return
            if (events.containsAny(
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_TIMELINE_CHANGED
                )
            ) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) shown?.let { publishMetadata(it) }
                publishState()
            }
        }
    }

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                if (shown != null) player.play()
            }

            override fun onPause() {
                player.pause()
            }

            override fun onStop() {
                player.pause()
            }

            override fun onSkipToNext() {
                onNext?.invoke()
            }

            override fun onSkipToPrevious() {
                onPrevious?.invoke()
            }

            override fun onSeekTo(pos: Long) {
                if (seekable && shown != null) player.seekTo(pos.coerceAtLeast(0L))
            }
        })
        player.addListener(listener)
        publishState()
    }

    /** Follows a rebuilt player. The previous one is left for the caller to release. */
    fun attach(next: Player) {
        if (next === player) return
        player.removeListener(listener)
        player = next
        next.addListener(listener)
        shown?.let {
            publishMetadata(it)
            publishState()
        }
    }

    /** What is on screen now, or null when nothing is. */
    fun show(info: NowPlayingInfo?) {
        if (info == null) {
            if (shown == null) return
            shown = null
            session.isActive = false
            publishState()
            return
        }
        val changed = info != shown
        shown = info
        if (changed) {
            loadArt(info.image)
            publishMetadata(info)
        }
        publishState()
        if (!session.isActive) session.isActive = true
    }

    fun release() {
        scope.cancel()
        player.removeListener(listener)
        session.isActive = false
        session.release()
    }

    private fun publishMetadata(info: NowPlayingInfo) {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, info.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, info.subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, info.subtitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Cable TV")
        info.image?.let {
            b.putString(MediaMetadata.METADATA_KEY_ART_URI, it)
            b.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, it)
        }
        art?.takeIf { artFor == info.image }?.let {
            b.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
            b.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
        }
        if (seekable) {
            val d = player.duration
            if (d != C.TIME_UNSET && d > 0L) b.putLong(MediaMetadata.METADATA_KEY_DURATION, d)
        }
        session.setMetadata(b.build())
    }

    private fun publishState() {
        val state = when {
            shown == null -> PlaybackState.STATE_NONE
            player.playbackState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            !player.playWhenReady -> PlaybackState.STATE_PAUSED
            player.playbackState == Player.STATE_READY -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_BUFFERING
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (onNext != null) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (onPrevious != null) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (seekable) actions = actions or PlaybackState.ACTION_SEEK_TO
        val position = if (seekable) player.currentPosition.coerceAtLeast(0L) else PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val speed = if (state == PlaybackState.STATE_PLAYING) 1f else 0f
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(if (shown == null) 0L else actions)
                .setState(state, position, speed)
                .build()
        )
    }

    // The logo or poster, as a small picture. Its address goes out straight away, the picture once it loads.
    private fun loadArt(url: String?) {
        if (url == artFor) return
        artFor = url
        art = null
        if (url.isNullOrBlank()) return
        scope.launch {
            val request = ImageRequest.Builder(appContext)
                .data(url)
                .size(ART_PX)
                .scale(Scale.FIT)
                .allowHardware(false)
                .build()
            val bitmap = runCatching {
                (appContext.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            }.getOrNull() ?: return@launch
            if (artFor != url) return@launch
            art = bitmap
            shown?.takeIf { it.image == url }?.let { publishMetadata(it) }
        }
    }
}
