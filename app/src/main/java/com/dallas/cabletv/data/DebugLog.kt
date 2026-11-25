package com.dallas.cabletv.data

import android.content.Context
import com.dallas.cabletv.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A log the TV keeps while debug logging is on, so a problem can be read from a paired phone
 * without a computer. It records playback and phone connection events. Nothing is written while
 * it is off. Each line goes through [CrashLog.redact] first, so stream addresses, logins and
 * Authorization headers never reach the file.
 */
object DebugLog {
    private const val PREFS = "cabletv_debug"
    private const val ON = "enabled"
    private const val FILE = "debug_log.txt"
    private const val MAX_LINE = 1_000

    /** The file is cut back to its newest half once it grows past this many bytes. */
    const val MAX_BYTES = 256 * 1024

    private val lock = Any()
    @Volatile private var app: Context? = null
    @Volatile private var on = false

    /** Call once when the app starts. Reads whether debug logging was left on. */
    fun install(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        on = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON, false) }
            .getOrDefault(false)
        log("app", "Started build ${BuildConfig.VERSION_CODE}.")
    }

    fun enabled(): Boolean = on

    fun setEnabled(enabled: Boolean) {
        if (enabled) on = true
        log("app", "Debug logging turned ${if (enabled) "on" else "off"}.")
        on = enabled
        runCatching { app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(ON, enabled)?.apply() }
    }

    /** Adds one line when debug logging is on. [error] adds its type and message after the text. */
    fun log(tag: String, message: String, error: Throwable? = null) {
        if (!on) return
        val ctx = app ?: return
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val detail = error?.let { ": ${it.javaClass.simpleName} ${it.message.orEmpty()}" }.orEmpty()
        val line = CrashLog.redact("$stamp $tag $message$detail").replace('\n', ' ').take(MAX_LINE)
        runCatching {
            synchronized(lock) { appendCapped(File(ctx.filesDir, FILE), line + "\n", MAX_BYTES) }
        }
    }

    /** The log text, oldest line first. Empty when nothing was recorded. */
    fun read(): String {
        val ctx = app ?: return ""
        return synchronized(lock) {
            runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")
        }
    }

    fun clear() {
        val ctx = app ?: return
        synchronized(lock) { runCatching { File(ctx.filesDir, FILE).delete() } }
    }

    /** The host of a stream address, for a line that must not show the path or logins. */
    fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "unknown"
}

/**
 * Adds [line] to [file] and, once the file is past [maxBytes], keeps only its newest part. The cut
 * is made at a line break, so the file always starts on a whole line.
 */
internal fun appendCapped(file: File, line: String, maxBytes: Int) {
    file.appendText(line)
    if (file.length() <= maxBytes) return
    val newest = file.readText().takeLast(maxBytes / 2)
    file.writeText(newest.substringAfter('\n', newest))
}
