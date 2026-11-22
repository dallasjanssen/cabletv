package com.dallas.cabletv.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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

    /** Returns the newest release if it is newer than [currentCode], otherwise null. */
    fun latest(currentCode: Int): Release? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CableTV/1.0")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub returned HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: error("Empty response from GitHub"))
            val code = json.optString("tag_name").removePrefix("build-").toIntOrNull()
                ?: error("Latest release has no build number")
            if (code <= currentCode) return null

            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == APK_NAME) {
                    return Release(code, asset.getString("browser_download_url"))
                }
            }
            return null
        }
    }

    /** Downloads the APK into [dir] and returns the file. */
    fun download(release: Release, dir: File): File {
        dir.mkdirs()
        val file = File(dir, "cabletv-update.apk")
        val req = Request.Builder().url(release.apkUrl).header("User-Agent", "CableTV/1.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download returned HTTP ${resp.code}")
            val body = resp.body ?: error("Empty download")
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        return file
    }

    /** Hands the APK to the system installer. The user confirms on screen. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
