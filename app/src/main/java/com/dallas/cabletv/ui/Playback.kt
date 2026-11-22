package com.dallas.cabletv.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.dallas.cabletv.data.JellyfinClient
import com.dallas.cabletv.data.LiveChannel
import com.dallas.cabletv.data.TICKS_PER_MS
import com.dallas.cabletv.data.VodItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One selectable audio or subtitle track, as shown in the track menu. */
data class TrackOption(
    val label: String,
    val type: Int,
    val groupIndex: Int,
    val trackIndex: Int,
    val selected: Boolean
)

/**
 * The single player used by Live TV, the guide preview and On Demand.
 * Owned by the ViewModel so it survives rotation and is shared by every screen.
 */
class Playback(context: Context, private val scope: CoroutineScope) {

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    /** Channel currently tuned for live viewing, or null while playing On Demand. */
    var liveChannel by mutableStateOf<LiveChannel?>(null)
        private set

    /** On Demand item currently playing, or null while watching live TV. */
    var vodItem by mutableStateOf<VodItem?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var reconnecting by mutableStateOf<String?>(null)
        private set

    var buffering by mutableStateOf(false)
        private set

    var transcoding by mutableStateOf(false)
        private set

    /** Last known position in milliseconds. Refreshed by a ticker while playing. */
    var positionMs by mutableLongStateOf(0L)
        private set

    var durationMs by mutableLongStateOf(0L)
        private set

    private var retries = 0
    private var retryJob: Job? = null
    private var vodClient: JellyfinClient? = null
    private var vodPlaySession = ""
    private var vodStartedAt = 0L
    private var fallbackAllowed = true
    private var stopSent = false
    private var hlsTried = false
    private var resumeLiveAfterBackground: LiveChannel? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    retries = 0
                    reconnecting = null
                    error = null
                }
                if (state == Player.STATE_ENDED) {
                    if (vodItem != null) {
                        reportStopOnce()
                    } else if (liveChannel != null) {
                        // A live stream that closes cleanly is a dropped connection, not the end.
                        scheduleReconnect()
                    }
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                handleError(e)
            }

            override fun onTracksChanged(tracks: Tracks) {
                // ExoPlayer can drop an unsupported video or audio track without raising an error.
                // Treat that as a playback failure so On Demand can fall back to transcoding.
                val vod = vodItem
                if (vod != null && !transcoding && fallbackAllowed && hasUnsupportedRequiredTrack(tracks)) {
                    switchToTranscode(player.currentPosition.takeIf { it > 0 } ?: vodStartedAt)
                }
            }
        })
        startTicker()
    }

    private fun startTicker() {
        scope.launch {
            var reportedAt = 0L
            while (true) {
                positionMs = player.currentPosition
                durationMs = player.duration.takeIf { it > 0 } ?: 0L
                val vod = vodItem
                if (vod != null && player.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - reportedAt >= 10_000L) {
                        reportedAt = now
                        reportProgress(vod)
                    }
                }
                delay(500)
            }
        }
    }

    // ---- Live TV ----

    fun tuneLive(channel: LiveChannel) {
        stopVod()
        liveChannel = channel
        retries = 0
        hlsTried = false
        retryJob?.cancel()
        error = null
        reconnecting = null
        playLive(channel, forceHls = false)
    }

    fun stopLive() {
        retryJob?.cancel()
        liveChannel = null
        retries = 0
        error = null
        reconnecting = null
        player.stop()
        player.clearMediaItems()
    }

    private fun playLive(channel: LiveChannel, forceHls: Boolean) {
        val item = if (forceHls) {
            MediaItem.Builder().setUri(channel.streamUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        } else {
            MediaItem.fromUri(channel.streamUrl)
        }
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    private fun scheduleReconnect() {
        val channel = liveChannel ?: return
        if (retries >= MAX_RETRIES) {
            error = "Stream stopped"
            reconnecting = null
            return
        }
        retries++
        reconnecting = "RECONNECTING $retries/$MAX_RETRIES"
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(BACKOFF_MS * retries)
            playLive(channel, forceHls = hlsTried)
        }
    }

    // ---- On Demand ----

    /**
     * Plays a Jellyfin item from [startMs]. Tries direct play first and falls back
     * to a server transcode if the device cannot decode the file.
     */
    fun playVod(item: VodItem, client: JellyfinClient, startMs: Long, allowTranscodeFallback: Boolean) {
        stopVod()
        liveChannel = null
        retryJob?.cancel()
        vodItem = item
        vodClient = client
        vodPlaySession = client.newPlaySessionId()
        vodStartedAt = startMs
        fallbackAllowed = allowTranscodeFallback
        error = null
        reconnecting = null
        transcoding = false
        stopSent = false
        player.setMediaItem(MediaItem.fromUri(item.streamUrl))
        if (startMs > 0L) player.seekTo(startMs)
        player.prepare()
        player.playWhenReady = true
        scope.launch(Dispatchers.IO) {
            runCatching { client.reportStart(item, startMs, vodPlaySession, transcoded = false) }
        }
    }

    /** Stops On Demand playback and tells Jellyfin where it stopped. */
    fun stopVod() {
        if (vodItem == null) return
        reportStopOnce()
        player.stop()
        player.clearMediaItems()
        vodItem = null
        vodClient = null
        transcoding = false
    }

    /** Sends the Stopped report at most once per play session. */
    private fun reportStopOnce() {
        if (stopSent) return
        val item = vodItem ?: return
        val client = vodClient ?: return
        stopSent = true
        val pos = player.currentPosition
        val session = vodPlaySession
        val isTranscoded = transcoding
        scope.launch(Dispatchers.IO) {
            runCatching { client.reportStopped(item, pos, session, isTranscoded) }
        }
    }

    private fun reportProgress(item: VodItem) {
        val client = vodClient ?: return
        val pos = player.currentPosition
        val session = vodPlaySession
        val isTranscoded = transcoding
        val paused = !player.isPlaying
        scope.launch(Dispatchers.IO) {
            runCatching { client.reportProgress(item, pos, session, isTranscoded, paused) }
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        if (vodItem == null) return
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        val max = player.duration.takeIf { it > 0 }
        player.seekTo(if (max != null) target.coerceAtMost(max) else target)
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceAtLeast(0L))
    }

    // ---- Errors and recovery ----

    private fun handleError(e: PlaybackException) {
        val vod = vodItem
        val resumeAt = player.currentPosition.takeIf { it > 0 } ?: vodStartedAt
        if (vod != null && vodClient != null && fallbackAllowed && !transcoding) {
            // Direct play failed. Ask the server to transcode and resume where we were.
            switchToTranscode(resumeAt)
            return
        }

        val channel = liveChannel
        if (channel != null) {
            // A URL with no recognizable file extension is often HLS. Try that once before giving up.
            if (!hlsTried && !hasKnownExtension(channel.streamUrl)) {
                hlsTried = true
                retryJob?.cancel()
                retryJob = scope.launch {
                    playLive(channel, forceHls = true)
                }
                return
            }
            if (retries < MAX_RETRIES) {
                scheduleReconnect()
                return
            }
        }

        error = e.message ?: "SIGNAL LOST"
        reconnecting = null
    }

    private fun switchToTranscode(resumeAt: Long) {
        val vod = vodItem ?: return
        val client = vodClient ?: return
        transcoding = true
        error = null
        reconnecting = null
        player.setMediaItem(MediaItem.fromUri(client.transcodeStreamUrl(vod, vodPlaySession)))
        if (resumeAt > 0L) player.seekTo(resumeAt)
        player.prepare()
        player.playWhenReady = true
        scope.launch(Dispatchers.IO) {
            runCatching { client.reportStart(vod, resumeAt, vodPlaySession, transcoded = true) }
        }
    }

    /** Lets the user retry a channel or item that gave up. */
    fun retry() {
        val channel = liveChannel
        val vod = vodItem
        when {
            channel != null -> {
                retries = 0
                hlsTried = false
                error = null
                reconnecting = null
                playLive(channel, forceHls = false)
            }
            vod != null && vodClient != null -> {
                val client = vodClient ?: return
                playVod(vod, client, player.currentPosition, fallbackAllowed)
            }
        }
    }

    /** Called when the app leaves the screen. Live TV stops, so no connection is held in the background. */
    fun onBackground() {
        val channel = liveChannel
        if (channel != null) {
            resumeLiveAfterBackground = channel
            stopLive()
        } else if (vodItem != null) {
            player.pause()
        }
    }

    /** Called when the app comes back. Live TV tunes to the channel it was on. */
    fun onForeground() {
        resumeLiveAfterBackground?.let { tuneLive(it) }
        resumeLiveAfterBackground = null
    }

    private fun hasKnownExtension(url: String): Boolean {
        val path = url.substringBefore('?').substringAfterLast('/').lowercase()
        return listOf(".ts", ".m3u8", ".mp4", ".mkv", ".mpd", ".webm", ".m4v", ".avi").any { path.endsWith(it) }
    }

    private fun hasUnsupportedRequiredTrack(tracks: Tracks): Boolean =
        tracks.groups.any { g ->
            (g.type == C.TRACK_TYPE_VIDEO || g.type == C.TRACK_TYPE_AUDIO) &&
                g.length > 0 &&
                (0 until g.length).none { g.isTrackSupported(it) }
        }

    // ---- Tracks ----

    /** Audio or text (subtitle) tracks, with the selected one marked. */
    fun trackOptions(type: Int): List<TrackOption> {
        val out = mutableListOf<TrackOption>()
        val tracks: Tracks = player.currentTracks
        tracks.groups.forEachIndexed { gi, group ->
            if (group.type != type) return@forEachIndexed
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val f = group.getTrackFormat(ti)
                val label = listOfNotNull(
                    f.label,
                    f.language?.uppercase(),
                    f.channelCount.takeIf { type == C.TRACK_TYPE_AUDIO && it > 0 }?.let { "${it}CH" },
                    f.codecs?.takeIf { it.isNotBlank() }?.substringBefore('.')?.uppercase()
                ).distinct().joinToString(" ").ifBlank { "TRACK ${out.size + 1}" }
                out += TrackOption(label, type, gi, ti, group.isTrackSelected(ti))
            }
        }
        return out
    }

    fun selectTrack(option: TrackOption?, type: Int) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            // "Off" for subtitles, or default for audio.
            if (type == C.TRACK_TYPE_TEXT) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                builder.clearOverridesOfType(type).setTrackTypeDisabled(type, false)
            }
        } else {
            // The track list can change under an open menu, for example after an auto-advance.
            val group = player.currentTracks.groups.getOrNull(option.groupIndex)?.mediaTrackGroup ?: return
            builder.setTrackTypeDisabled(type, false)
                .clearOverridesOfType(type)
                .setOverrideForType(TrackSelectionOverride(group, option.trackIndex))
        }
        player.trackSelectionParameters = builder.build()
    }

    fun release() {
        stopVod()
        retryJob?.cancel()
        player.release()
    }

    companion object {
        const val MAX_RETRIES = 5
        const val BACKOFF_MS = 1500L
    }
}
