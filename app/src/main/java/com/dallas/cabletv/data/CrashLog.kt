package com.dallas.cabletv.data

import android.content.Context
import com.dallas.cabletv.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the error from the last crash, so it can be read in Settings on the TV. A TV has no easy way
 * to see logs, and this makes a crash something that can be reported and fixed.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val SEEN = "last_crash_seen"

    /** Saves any uncaught error to a file, then lets Android handle the crash as usual. */
    fun install(context: Context) {
        val dir = context.applicationContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                File(dir, FILE).writeText("Build ${BuildConfig.VERSION_CODE}, $stamp\n${error.stackTraceToString().take(12_000)}")
                File(dir, SEEN).delete()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved error, or null when the app has not crashed since it was cleared. */
    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    /** True the first time it is asked after a new crash, so the notice shows once. */
    fun takeNotice(context: Context): Boolean {
        if (read(context) == null) return false
        val seen = File(context.filesDir, SEEN)
        if (seen.exists()) return false
        runCatching { seen.createNewFile() }
        return true
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        File(context.filesDir, SEEN).delete()
    }
}
