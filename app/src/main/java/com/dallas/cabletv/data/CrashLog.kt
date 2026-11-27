package com.dallas.cabletv.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.dallas.cabletv.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Keeps the error from the last crash, so it can be read in Settings on the TV. A TV has no easy way
 * to see logs, and this makes a crash something that can be reported and fixed.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val SEEN = "last_crash_seen"
    private const val EXIT_SEEN = "last_exit_seen_at"
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val PATH_CREDENTIALS = Regex("/(live|movie|series|timeshift)/[^/\\s?#]+/[^/\\s?#]+/")
    private val QUERY_SECRET = Regex("(?i)\\b(username|password|api_key|token|mac)=[^&\\s\"')>\\]]*")
    private val AUTH_HEADER = Regex("(?i)(Authorization:\\s*).*")

    /** Hides logins in crash text: stream paths, query values and Authorization headers. */
    fun redact(text: String): String =
        text.replace(PATH_CREDENTIALS) { "/${it.groupValues[1]}/***/***/" }
            .replace(QUERY_SECRET) { "${it.groupValues[1]}=***" }
            .replace(AUTH_HEADER) { "${it.groupValues[1]}***" }

    /** Saves any uncaught error to a file, then lets Android handle the crash as usual. */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val dir = context.applicationContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                File(dir, FILE).writeText("Build ${BuildConfig.VERSION_CODE}, $stamp\n${heapLine()}${redact(error.stackTraceToString().take(12_000))}")
                File(dir, SEEN).delete()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Records crashes the Java handler never sees: native crashes, ANRs (a frozen main thread) and kills
     * by the system. Android keeps the reason each process ended for, so the newest problem not seen
     * before is saved in the same file as a Java crash. Java crashes are skipped here, because [install]
     * already saved them. Runs on its own thread, so startup does not wait for it.
     */
    fun recordExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        thread(name = "crash-exit-reasons") {
            runCatching { recordExits(context.applicationContext) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordExits(context: Context) {
        val dir = context.filesDir
        val seenFile = File(dir, EXIT_SEEN)
        val seenUpTo = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        val newest = exits.maxOfOrNull { it.timestamp } ?: return
        seenFile.writeText(newest.toString())

        val problem = exits
            .filter { it.timestamp > seenUpTo && isProblem(it.reason) }
            .maxByOrNull { it.timestamp } ?: return
        // A Java crash saved by install() is newer than a system record of the same run, so keep it.
        if (File(dir, FILE).exists() && File(dir, FILE).lastModified() >= problem.timestamp) return

        val trace = problem.traceInputStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(problem.timestamp))
        val text = "Build ${BuildConfig.VERSION_CODE}, $stamp\n" + heapLine() +
            "Not a Java error. The system ended the app: ${reasonName(problem.reason)}. ${problem.description.orEmpty()}\n\n" +
            redact(trace.take(12_000))
        File(dir, FILE).writeText(text)
        File(dir, SEEN).delete()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isProblem(reason: Int): Boolean = reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
        reason == ApplicationExitInfo.REASON_ANR ||
        reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
        reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
        reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "removed for low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        else -> "exit code $reason"
    }

    /** How much of the app's memory was in use when the crash happened, so an out of memory error shows its size. */
    private fun heapLine(): String {
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        return "Memory ${(rt.totalMemory() - rt.freeMemory()) / mb} MB in use of ${rt.maxMemory() / mb} MB\n"
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
