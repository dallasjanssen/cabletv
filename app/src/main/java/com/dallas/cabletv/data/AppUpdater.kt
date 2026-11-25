package com.dallas.cabletv.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks the public GitHub repo for a newer build and installs it.
 * Each release is tagged build-<number>, where the number is the versionCode.
 * Android still asks the user to confirm every install.
 */
object AppUpdater {

    private const val REPO = "dallasjanssen/cabletv"
    private const val APK_NAME = "app-release.apk"

    data class Release(val versionCode: Int, val apkUrl: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Returns the newest release if it is newer than [currentCode], otherwise null. A positive
     * [callTimeoutSeconds] caps the whole check, for callers that must not wait long.
     */
    fun latest(currentCode: Int, callTimeoutSeconds: Long = 0L): Release? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CableTV/1.0")
            .build()
        val client = if (callTimeoutSeconds > 0) {
            http.newBuilder().callTimeout(callTimeoutSeconds, TimeUnit.SECONDS).build()
        } else {
            http
        }
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub returned HTTP ${resp.code}")
            val releases = JSONArray(resp.body?.string() ?: error("Empty response from GitHub"))
            var best: Release? = null
            for (i in 0 until releases.length()) {
                val json = releases.optJSONObject(i) ?: continue
                val code = buildNumber(json.optString("tag_name")) ?: continue
                if (code <= currentCode || code <= (best?.versionCode ?: 0)) continue
                val assets = json.optJSONArray("assets") ?: continue
                for (a in 0 until assets.length()) {
                    val asset = assets.optJSONObject(a) ?: continue
                    if (asset.optString("name") == APK_NAME) {
                        val link = asset.optString("browser_download_url")
                        if (link.isNotEmpty()) best = Release(code, link)
                        break
                    }
                }
            }
            return best
        }
    }

    /** Downloads the APK into [dir] and returns the file. */
    fun download(release: Release, dir: File): File {
        dir.mkdirs()
        val file = File(dir, "cabletv-update.apk")
        file.delete()
        val req = Request.Builder().url(release.apkUrl).header("User-Agent", "CableTV/1.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download returned HTTP ${resp.code}")
            val body = resp.body ?: error("Empty download")
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
            val expected = body.contentLength()
            if (expected > 0L && file.length() != expected) {
                file.delete()
                throw Exception("The download was incomplete.")
            }
        }
        return file
    }

    /** Hands the APK to the system installer. The user confirms on screen. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw Exception("This TV has no installer that can open the update.")
        }
    }

    /** The number in a tag like build-42, or null for any other tag. */
    private fun buildNumber(tag: String): Int? =
        Regex("build-(\\d+)").matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()
}
