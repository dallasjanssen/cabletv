package com.dallas.cabletv.data

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The most a live channel can be paused or rewound from what is already buffered. */
const val LIVE_BUFFER_MS = 3 * 60_000

/** The shortest rewind kept, even on a device with little memory. */
private const val MIN_REWIND_MS = 45_000

/** A busy HD channel, about 8 Mbps, used to turn a memory budget into seconds of rewind. */
private const val HD_BYTES_PER_SECOND = 1_000_000L

/**
 * How much rewind this device can hold. The player keeps its buffer in the app's memory, and the
 * rewind buffer is limited by time, not size, so three minutes of HD could use most of a TV box's
 * memory and cause stutters or a crash. A quarter of the app's memory is set aside for it, which
 * keeps the full three minutes on roomy devices and about a minute on tight ones.
 */
private fun rewindBufferMs(): Int {
    val budgetBytes = Runtime.getRuntime().maxMemory() / 4
    val ms = budgetBytes * 1000 / HD_BYTES_PER_SECOND
    return ms.coerceIn(MIN_REWIND_MS.toLong(), LIVE_BUFFER_MS.toLong()).toInt()
}

/**
 * One OkHttp client for every player, so tiles and the main picture share connections.
 * The other clients in the app are private to their own files, so this one is built fresh.
 */
private val playerHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * Retries a failed segment or playlist load inside the player, a little longer and more often
 * than the default. Without this a single 404 or timeout reaches onPlayerError, which reloads
 * the stream and drops the buffer. Errors the default treats as final (bad data, missing file)
 * stay final, because the delay is only changed when the default would retry.
 */
private class LiveLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 8

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val base = super.getRetryDelayMsFor(loadErrorInfo)
        if (base == C.TIME_UNSET) return base
        // A refusal will not change on retry. Failing at once lets the MPEG-TS fallback start quickly.
        val status = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (status != null && status in 400..499 && status != 404 && status != 408 && status != 429) return C.TIME_UNSET
        return minOf(500L * loadErrorInfo.errorCount, 2_000L)
    }
}

/**
 * TS flags for the video path, kept at the Media3 defaults. Allowing streams to start on a non IDR
 * keyframe and detecting access units by hand was tried for a faster first picture, but on some
 * channels the picture froze on its first frame while audio and captions kept playing. Turn these
 * back on only for a stream that is shown to need them.
 */
private const val TS_FLAGS = 0

/**
 * DefaultMediaSourceFactory has no setter for the HLS extractor in Media3 1.4.1, so HLS items
 * get their own HlsMediaSource.Factory with the TS flags above. Everything else goes to [fallback].
 */
private class LiveMediaSourceFactory(
    private val fallback: DefaultMediaSourceFactory,
    dataSourceFactory: DataSource.Factory,
    policy: LoadErrorHandlingPolicy,
) : MediaSource.Factory {
    // Subtitles must be parsed while extracting, as DefaultMediaSourceFactory does. The text
    // renderer in this Media3 version refuses raw WebVTT, so without this an HLS stream with
    // subtitles fails as soon as captions are turned on.
    @Suppress("DEPRECATION")
    private val hls = HlsMediaSource.Factory(dataSourceFactory)
        .setExtractorFactory(DefaultHlsExtractorFactory(TS_FLAGS, true))
        .experimentalParseSubtitlesDuringExtraction(true)
        .setLoadErrorHandlingPolicy(policy)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        fallback.setDrmSessionManagerProvider(provider)
        hls.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        fallback.setLoadErrorHandlingPolicy(policy)
        hls.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = fallback.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = mediaItem.localConfiguration
        val isHls = config != null &&
            Util.inferContentTypeForUriAndMimeType(config.uri, config.mimeType) == C.CONTENT_TYPE_HLS
        return if (isHls) hls.createMediaSource(mediaItem) else fallback.createMediaSource(mediaItem)
    }
}

/**
 * A player that sends the same client identity the provider API calls use.
 * [compact] keeps a small buffer and no rewind, for the two small pictures of Two channels.
 * Those two leave audio focus alone, because two players asking for it would pause each other.
 */
fun newPlayer(context: Context, compact: Boolean = false): ExoPlayer {
    val okHttp = OkHttpDataSource.Factory(playerHttp).setUserAgent(PLAYER_USER_AGENT)
    // The default wrapper keeps file and content URIs working next to http.
    val http = DefaultDataSource.Factory(context, okHttp)
    val policy = LiveLoadErrorPolicy()
    val extractors = DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS)
    val sources = LiveMediaSourceFactory(
        DefaultMediaSourceFactory(http, extractors).setLoadErrorHandlingPolicy(policy),
        http,
        policy,
    )
    val load = if (compact) {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 15_000, 1_500, 4_000)
            .setBackBuffer(0, false)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .build()
    } else {
        // The size cap keeps a high bitrate stream from filling the memory before the time limit.
        // The first 15 seconds still load past the cap, so the rewind buffer can never stall playback.
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, LIVE_BUFFER_MS, 1_500, 5_000)
            .setBackBuffer(rewindBufferMs(), true)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
    val audio = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    // If the preferred hardware decoder fails to start, try the next one instead of failing the channel.
    val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    fun build(loadControl: DefaultLoadControl): ExoPlayer =
        ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(audio, !compact)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { TrackMemory.apply(context, it) }

    return runCatching { build(load) }.getOrElse { first ->
        // Some devices fail to create codec state under pressure. Retry once with a lighter buffer.
        val fallbackLoad = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 20_000, 1_500, 5_000)
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
