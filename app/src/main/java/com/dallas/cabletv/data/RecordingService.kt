package com.dallas.cabletv.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Copies one stream to a file until it is stopped or the programme ends.
 * A foreground notification keeps the recording going when the picture is not on screen.
 * HLS playlists are followed segment by segment. Anything else is copied as bytes.
 */
class RecordingService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> startRecording(intent)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        if (!running.compareAndSet(false, true)) return
        busy.set(true)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Recording"
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: ""
        val until = intent.getLongExtra(EXTRA_UNTIL, 0L)
        val file = File(RecordingStore(filesDir).directory(), "$id.ts")
        val started = System.currentTimeMillis()
        startForeground(NOTE_ID, notification(title))
        worker = Thread {
            runCatching { capture(url, file, until) }
            val store = RecordingStore(filesDir)
            if (file.exists() && file.length() > 0L) {
                store.add(Recording(id, title, channel, file.name, started, System.currentTimeMillis()))
            }
            store.removeSchedule(id)
            running.set(false)
            busy.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.also { it.start() }
    }

    private fun capture(url: String, file: File, until: Long) {
        FileOutputStream(file).use { out ->
            if (url.contains(".m3u8", ignoreCase = true)) captureHls(url, out, until) else captureBytes(url, out, until)
        }
    }

    private fun captureBytes(url: String, out: FileOutputStream, until: Long) {
        val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body ?: return
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (running.get() && (until <= 0L || System.currentTimeMillis() < until)) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
    }

    private fun captureHls(playlistUrl: String, out: FileOutputStream, until: Long) {
        var media = playlistUrl
        val seen = HashSet<String>()
        while (running.get() && (until <= 0L || System.currentTimeMillis() < until)) {
            val text = fetchText(media) ?: break
            val variant = hlsVariantUrl(text, media)
            if (variant != null) {
                media = variant
                continue
            }
            val segments = hlsSegments(text, media)
            for (segment in segments) {
                if (!running.get()) return
                if (!seen.add(segment)) continue
                appendUrl(segment, out)
            }
            if (text.contains("#EXT-X-ENDLIST")) break
            Thread.sleep(4_000)
        }
    }

    private fun appendUrl(url: String, out: FileOutputStream) {
        val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            resp.body?.byteStream()?.use { input -> input.copyTo(out) }
        }
    }

    private fun fetchText(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", PLAYER_USER_AGENT).build()
        http.newCall(req).execute().use { it.body?.string() }
    }.getOrNull()

    private fun stopRecording() {
        running.set(false)
        worker?.interrupt()
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Recording")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "recordings"
        private const val NOTE_ID = 42
        const val ACTION_START = "com.dallas.cabletv.RECORD_START"
        const val ACTION_STOP = "com.dallas.cabletv.RECORD_STOP"
        const val EXTRA_URL = "url"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_UNTIL = "until"

        fun start(context: Context, id: String, url: String, title: String, channel: String, untilMs: Long) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CHANNEL, channel)
                .putExtra(EXTRA_UNTIL, untilMs)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }

        private val busy = AtomicBoolean(false)
        fun isBusy(): Boolean = busy.get()
    }
}
