package com.dallas.cabletv.data

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** The most a live channel can be paused or rewound from what is already buffered. */
const val LIVE_BUFFER_MS = 3 * 60_000

/** The shortest rewind kept, even on a device with little memory. */
private const val MIN_REWIND_MS = 45_000

/** Same range PlayerOptionsStore writes. A larger value would be ignored on the next save. */
internal const val MAX_AUDIO_OFFSET_MS = 2_000

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
 * A player that sends the same client identity the provider API calls use, unless a user agent
 * was saved. [compact] keeps a small buffer and no rewind, for the two small pictures of Two
 * channels. Those two leave audio focus alone, because two players asking for it would pause
 * each other.
 *
 * Saved picture choices are read here. Flags that are still at their defaults are left unset,
 * so a fresh install builds the same player as before those choices existed.
 */
fun newPlayer(context: Context, compact: Boolean = false): ExoPlayer {
    val options = PlayerOptionsStore(context).load()
    // The gate is only the cap. The screen that opens a stream acquires a slot; building a player does not.
    ConnectionGate.setLimit(options.maxConnections)
    val okHttp = OkHttpDataSource.Factory(playerHttp)
        .setUserAgent(playbackUserAgent(options.userAgent))
    // The default wrapper keeps file and content URIs working next to http.
    val http = DefaultDataSource.Factory(context, okHttp)
    val policy = LiveLoadErrorPolicy()
    val extractors = DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS)
    val sources = LiveMediaSourceFactory(
        DefaultMediaSourceFactory(http, extractors).setLoadErrorHandlingPolicy(policy),
        http,
        policy,
    )
    // auto passes the memory-based rewind already computed for this device. Compact pictures
    // ignore the preset inside bufferPlan, so four tiles stay small.
    val load = loadControlFor(bufferPlan(options.bufferPreset, compact, rewindBufferMs()))
    val audio = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    val renderers = renderersFor(context, options)
    fun build(loadControl: DefaultLoadControl): ExoPlayer =
        ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(audio, !compact)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { player ->
                TrackMemory.apply(context, player)
                applyStoredPlayback(player, options)
            }

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
 * The byte cap still yields to the time targets when the plan says so, which keeps a high
 * bitrate stream from filling memory before the time limit. The first seconds still load past
 * the cap, so the rewind buffer cannot stall playback. A rewind window is kept from the previous
 * keyframe so pause and rewind land on a picture.
 */
private fun loadControlFor(plan: BufferPlan): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(plan.minMs, plan.maxMs, plan.startMs, plan.rebufferMs)
        .setBackBuffer(plan.backMs, plan.backMs > 0)
        .setTargetBufferBytes(plan.byteCap)
        .setPrioritizeTimeOverSizeThresholds(plan.prioritizeTime)
        .build()

/**
 * Decoder fallback stays on, which is what lets the next decoder try when the first fails to start.
 * A selector is installed only when a saved choice actually changes the order or the list.
 */
private fun renderersFor(context: Context, options: PlayerOptions): DefaultRenderersFactory {
    val factory = PlaybackRenderersFactory(context, clampedAudioOffset(options.audioOffsetMs))
        .setEnableDecoderFallback(true)
    if (options.softwareVideoDecoder || options.softwareAudioDecoder || options.amlogicFix) {
        factory.setMediaCodecSelector(
            OptionCodecSelector(
                preferSoftwareVideo = options.softwareVideoDecoder,
                preferSoftwareAudio = options.softwareAudioDecoder,
                dropAmlogic = options.amlogicFix,
            )
        )
    }
    return factory
}

/** The store clamps to this range on save. Clamp again so a hand-edited value cannot run past it. */
internal fun clampedAudioOffset(offsetMs: Int): Int = offsetMs.coerceIn(-MAX_AUDIO_OFFSET_MS, MAX_AUDIO_OFFSET_MS)

/**
 * Offload and tunneling stay off unless the viewer turned them on.
 *
 * Media3 1.4.1 has no DefaultRenderersFactory.setEnableAudioOffload; that method was removed in
 * 1.2. The public replacement is the track selector's audio offload preference, which asks the
 * sink to offload when the device and the selected audio can do it. Tunneling is the same kind
 * of public switch, [DefaultTrackSelector.Parameters.Builder.setTunnelingEnabled]. The selector
 * only tunnels when the audio and video decoders support it, so nothing here reaches into a
 * private codec API. Encoded passthrough that Media3 already attempts for AC3 and similar
 * formats is left alone when the flag is off, because turning it off would change playback on
 * a default install.
 */
private fun applyStoredPlayback(player: ExoPlayer, options: PlayerOptions) {
    if (!options.audioPassthrough && !options.tunneledPlayback) return
    val selector = player.trackSelector as? DefaultTrackSelector ?: return
    val parameters = selector.buildUponParameters()
    if (options.audioPassthrough) {
        parameters.setAudioOffloadPreferences(
            TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                )
                .build()
        )
    }
    if (options.tunneledPlayback) {
        parameters.setTunnelingEnabled(true)
    }
    selector.setParameters(parameters)
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

/**
 * Software decoders from Google and Android's C2 software codecs. Hardware decoders stay in
 * their original order behind these when the viewer prefers software.
 */
internal fun isSoftwareCodec(name: String): Boolean {
    val lower = name.lowercase()
    return "omx.google." in lower || "c2.android." in lower
}

/**
 * Amlogic decoders stutter on some boxes. "omx.amlogic" is already covered by "amlogic";
 * ".aml." catches the short vendor token those names also use.
 */
internal fun isAmlogicCodec(name: String): Boolean {
    val lower = name.lowercase()
    return "amlogic" in lower || "omx.amlogic" in lower || ".aml." in lower
}

/** Decoder names in the order the player should try them. */
internal fun orderedDecoderNames(
    names: List<String>,
    preferSoftware: Boolean,
    dropAmlogic: Boolean,
): List<String> {
    val kept = if (dropAmlogic) names.filterNot { isAmlogicCodec(it) } else names
    if (!preferSoftware) return kept
    // sortedBy is stable, so the hardware decoders keep Media3's order after the software ones.
    return kept.sortedBy { if (isSoftwareCodec(it)) 0 else 1 }
}

/**
 * Prefers a software video or audio decoder when that choice is on, and drops Amlogic codecs
 * when that fix is on. Other tracks, and choices that are off, keep the list Media3 returned.
 */
private class OptionCodecSelector(
    private val preferSoftwareVideo: Boolean,
    private val preferSoftwareAudio: Boolean,
    private val dropAmlogic: Boolean,
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val found = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )
        val preferSoftware = when {
            MimeTypes.isVideo(mimeType) -> preferSoftwareVideo
            MimeTypes.isAudio(mimeType) -> preferSoftwareAudio
            else -> false
        }
        val order = orderedDecoderNames(found.map { it.name }, preferSoftware, dropAmlogic)
        if (order.size == found.size && order.indices.all { order[it] == found[it].name }) return found
        val remaining = found.toMutableList()
        return order.mapNotNull { name ->
            val index = remaining.indexOfFirst { it.name == name }
            if (index < 0) null else remaining.removeAt(index)
        }
    }
}

/**
 * The default renderer factory, plus a PCM offset when the viewer asked for one.
 * An offset of 0 does not add a processor, so the sink Media3 builds itself is unchanged.
 */
private class PlaybackRenderersFactory(
    context: Context,
    private val audioOffsetMs: Int,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        if (audioOffsetMs == 0) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        }
        // Copy the sink the parent builds, then put the offset in front of it. Processors run
        // only for decoded PCM, which is the path a delay or advance can actually shift.
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(PcmOffsetProcessor(audioOffsetMs)))
            .build()
    }
}

/**
 * Shifts decoded PCM against the picture. A positive offset writes silence first, so the sound
 * reaches the speakers later than the picture. A negative offset discards the first samples, so
 * the sound starts early. The track clock follows the samples that were actually written, which
 * is what makes the picture and the sound drift apart by this amount. Zero is not constructed.
 */
private class PcmOffsetProcessor(offsetMs: Int) : BaseAudioProcessor() {
    private val offsetMs = clampedAudioOffset(offsetMs)
    private var dropBytes = 0
    private var silenceBytes = 0
    private var pendingDrop = 0
    private var pendingSilence = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (offsetMs == 0 || inputAudioFormat.bytesPerFrame <= 0 || inputAudioFormat.sampleRate <= 0) {
            dropBytes = 0
            silenceBytes = 0
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val frames = inputAudioFormat.sampleRate.toLong() * abs(offsetMs) / 1000L
        val bytes = frames * inputAudioFormat.bytesPerFrame
        if (bytes <= 0L || bytes > Int.MAX_VALUE) {
            dropBytes = 0
            silenceBytes = 0
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (offsetMs > 0) {
            silenceBytes = bytes.toInt()
            dropBytes = 0
        } else {
            dropBytes = bytes.toInt()
            silenceBytes = 0
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        pendingDrop = dropBytes
        pendingSilence = silenceBytes
    }

    override fun onReset() {
        dropBytes = 0
        silenceBytes = 0
        pendingDrop = 0
        pendingSilence = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (pendingDrop > 0 && inputBuffer.hasRemaining()) {
            val drop = minOf(pendingDrop, inputBuffer.remaining())
            inputBuffer.position(inputBuffer.position() + drop)
            pendingDrop -= drop
        }
        val incoming = inputBuffer.remaining()
        if (incoming == 0 && pendingSilence == 0) return
        val extra = pendingSilence
        val out = replaceOutputBuffer(extra + incoming)
        if (extra > 0) {
            putZeros(out, extra)
            pendingSilence = 0
        }
        if (incoming > 0) out.put(inputBuffer)
        out.flip()
    }

    override fun onQueueEndOfStream() {
        if (pendingSilence <= 0) return
        val out = replaceOutputBuffer(pendingSilence)
        putZeros(out, pendingSilence)
        pendingSilence = 0
        out.flip()
    }
}

/** PCM silence is a run of zero bytes for both integer and float samples. */
private fun putZeros(out: ByteBuffer, count: Int) {
    val zeros = ByteArray(minOf(count, 8_192))
    var left = count
    while (left > 0) {
        val n = minOf(left, zeros.size)
        out.put(zeros, 0, n)
        left -= n
    }
}
