package com.dallas.cabletv.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dallas.cabletv.MainActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Copies one stream to a file until it is stopped or the programme ends.
 * A foreground notification keeps the recording going when the picture is not on screen.
 * HLS playlists are followed segment by segment. Anything else is copied as bytes.
 * A dropped connection is retried a few times before the recording gives up.
 */
class RecordingService : Service() {
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var worker: Thread? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> startRecording(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!running.get()) busy.set(false)
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)
        val id = intent.getStringExtra(EXTRA_ID)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Recording"
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: ""
        val until = intent.getLongExtra(EXTRA_UNTIL, 0L)
        val won = running.compareAndSet(false, true)
        // A started foreground service must call startForeground, whatever happens next.
        try {
            startForeground(NOTE_ID, notification(if (won) title else (currentTitle ?: title)))
        } catch (e: Exception) {
            if (won) {
                running.set(false)
                busy.set(false)
                stopSelf()
            }
            return
        }
        if (!won) return
        if (url == null || id == null) {
            running.set(false)
            busy.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        stopRequested.set(false)
        currentTitle = title
        val started = System.currentTimeMillis()
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(RecordingStore(filesDir).directory(), "${safeId}_$started.ts")
        val lock = acquireWakeLock(until)
        worker = Thread {
            try {
                val store = RecordingStore(filesDir)
                runCatching { store.deleteOrphans(file.name) }
                try {
                    capture(url, file, until)
                } catch (e: Exception) {
                    // The recording ends here. Whatever was copied is kept.
                }
                Thread.interrupted()
                try {
                    if (file.exists() && file.length() > 0L) {
                        store.add(Recording(id, title, channel, file.name, started, System.currentTimeMillis()))
                    }
                    store.removeSchedule(id)
                } catch (e: Exception) {
                    // The file stays on disk. The next recording clears it if it is not listed.
                }
            } finally {
                runCatching { if (lock?.isHeld == true) lock.release() }
                currentTitle = null
                running.set(false)
                busy.set(false)
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                runCatching { stopSelf() }
            }
        }.also { it.start() }
    }

    private fun acquireWakeLock(until: Long): PowerManager.WakeLock? = runCatching {
        val power = getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cabletv:recording")
        lock.setReferenceCounted(false)
        val span = if (until > 0L) (until - System.currentTimeMillis()).coerceAtLeast(0L) + 5 * 60_000L else MAX_WAKE_MS
        lock.acquire(span.coerceAtMost(MAX_WAKE_MS))
        lock
    }.getOrNull()

    private fun shouldContinue(until: Long): Boolean =
        !stopRequested.get() &&
            (until <= 0L || System.currentTimeMillis() < until) &&
            filesDir.usableSpace >= MIN_RUN_SPACE

    private fun capture(url: String, file: File, until: Long) {
        FileOutputStream(file).use { out ->
            if (url.contains(".m3u8", ignoreCase = true)) captureHls(url, out, until) else captureBytes(url, out, until)
        }
    }

    /** A plain stream. A dropped connection is reopened, up to about five times in a row. */
    private fun captureBytes(url: String, out: FileOutputStream, until: Long) {
        var drops = 0
        while (shouldContinue(until) && drops <= MAX_RECONNECTS) {
            var copied = 0L
            try {
                copied = copyOnce(url, out, until)
            } catch (e: IOException) {
                // Fall through to the retry below.
            }
            if (!shouldContinue(until)) break
            drops = if (copied > 0L) 1 else drops + 1
            Thread.sleep(1_000L * minOf(drops, 3))
        }
    }

    private fun copyOnce(url: String, out: FileOutputStream, until: Long): Long {
        val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
        var total = 0L
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty body")
            val buf = ByteArray(64 * 1024)
            var rounds = 0
            body.byteStream().use { input ->
                while (true) {
                    if (++rounds % 64 == 0 && !shouldContinue(until)) break
                    if (stopRequested.get() || (until > 0L && System.currentTimeMillis() >= until)) break
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
        }
        return total
    }

    private fun captureHls(playlistUrl: String, out: FileOutputStream, until: Long) {
        var media = playlistUrl
        val seen = HashSet<String>()
        while (shouldContinue(until)) {
            val (text, finalUrl) = fetchPlaylist(media) ?: break
            val variant = hlsVariantUrl(text, finalUrl)
            if (variant != null) {
                media = variant
                continue
            }
            for (segment in hlsSegments(text, finalUrl)) {
                if (!shouldContinue(until)) return
                if (!seen.add(segment)) continue
                // A segment that still fails after retries is skipped, so one bad piece does not end the recording.
                val bytes = fetchBytes(segment) ?: continue
                out.write(bytes)
            }
            if (text.contains("#EXT-X-ENDLIST")) break
            Thread.sleep(4_000)
        }
    }

    /** The playlist text and the address it finally came from, after redirects. */
    private fun fetchPlaylist(url: String): Pair<String, String>? = retry {
        val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val text = resp.body?.string() ?: throw IOException("Empty playlist")
            text to resp.request.url.toString()
        }
    }

    private fun fetchBytes(url: String): ByteArray? = retry {
        val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IOException("Empty segment")
        }
    }

    /** Tries up to three times with a short wait. Null when every try failed or a stop was asked for. */
    private fun <T> retry(block: () -> T): T? {
        for (attempt in 1..3) {
            if (stopRequested.get()) return null
            try {
                return block()
            } catch (e: IOException) {
                if (attempt < 3) Thread.sleep(1_000L * attempt)
            }
        }
        return null
    }

    private fun stopRecording() {
        if (!running.get()) {
            busy.set(false)
            stopSelf()
            return
        }
        stopRequested.set(true)
        worker?.interrupt()
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Recording")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "recordings"
        private const val NOTE_ID = 42
        private const val MIN_START_SPACE = 1_000_000_000L
        private const val MIN_RUN_SPACE = 200_000_000L
        private const val MAX_RECONNECTS = 5
        private const val MAX_WAKE_MS = 6 * 60 * 60_000L
        const val ACTION_START = "com.dallas.cabletv.RECORD_START"
        const val ACTION_STOP = "com.dallas.cabletv.RECORD_STOP"
        const val EXTRA_URL = "url"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_UNTIL = "until"

        private val busy = AtomicBoolean(false)
        @Volatile private var currentTitle: String? = null

        fun isBusy(): Boolean = busy.get()

        /** The title of the recording in progress, or null. */
        fun currentTitle(): String? = currentTitle

        /** Starts a recording. False when one is already running, space is low, or Android refuses. Never throws. */
        fun start(context: Context, id: String, url: String, title: String, channel: String, untilMs: Long): Boolean {
            if (context.filesDir.usableSpace < MIN_START_SPACE) return false
            if (!busy.compareAndSet(false, true)) return false
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CHANNEL, channel)
                .putExtra(EXTRA_UNTIL, untilMs)
            return try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                currentTitle = title
                true
            } catch (e: IllegalStateException) {
                busy.set(false)
                false
            } catch (e: SecurityException) {
                busy.set(false)
                false
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
