package com.dallas.cabletv.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import kotlinx.coroutines.withContext

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

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    retries = 0
                    reconnecting = null
                    error = null
                }
                if (state == Player.STATE_ENDED && vodItem != null) {
                    // Finished a file: report it as stopped at the end.
                    reportStopNow()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                handleError(e)
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
        retryJob?.cancel()
        error = null
        reconnecting = null
        playUrl(channel.streamUrl, startMs = 0L)
    }

    fun stopLive() {
        retryJob?.cancel()
        liveChannel = null
        player.stop()
        player.clearMediaItems()
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
        transcoding = false
        playUrl(item.streamUrl, startMs = startMs)
        scope.launch(Dispatchers.IO) {
            runCatching { client.reportStart(item, startMs, vodPlaySession, transcoded = false) }
        }
    }

    /** Stops On Demand playback and tells Jellyfin where it stopped. */
    fun stopVod() {
        if (vodItem == null) return
        reportStopNow()
        player.stop()
        player.clearMediaItems()
        vodItem = null
        vodClient = null
        transcoding = false
    }

    private fun reportStopNow() {
        val item = vodItem ?: return
        val client = vodClient ?: return
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
        val client = vodClient
        val resumeAt = player.currentPosition.takeIf { it > 0 } ?: vodStartedAt
        if (vod != null && client != null && fallbackAllowed && !transcoding) {
            // Direct play failed. Ask the server to transcode and resume where we were.
            transcoding = true
            error = null
            playUrl(client.transcodeStreamUrl(vod.id, vod.mediaSourceId), startMs = resumeAt)
            return
        }

        val channel = liveChannel
        if (channel != null && retries < MAX_RETRIES) {
            retries++
            reconnecting = "RECONNECTING $retries/$MAX_RETRIES"
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(BACKOFF_MS * retries)
                playUrl(channel.streamUrl, startMs = 0L)
            }
            return
        }

        error = e.message ?: "SIGNAL LOST"
        reconnecting = null
    }

    /** Lets the user retry a channel that gave up. */
    fun retry() {
        val channel = liveChannel
        val vod = vodItem
        when {
            channel != null -> tuneLive(channel)
            vod != null && vodClient != null -> {
                val client = vodClient ?: return
                playVod(vod, client, player.currentPosition, fallbackAllowed)
            }
        }
    }

    private fun playUrl(url: String, startMs: Long) {
        player.setMediaItem(MediaItem.fromUri(url))
        if (startMs > 0L) player.seekTo(startMs)
        player.prepare()
        player.playWhenReady = true
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
            val group = player.currentTracks.groups[option.groupIndex].mediaTrackGroup
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
