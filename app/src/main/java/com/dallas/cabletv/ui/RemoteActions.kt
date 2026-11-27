package com.dallas.cabletv.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import com.dallas.cabletv.data.PlayerOptions

/**
 * A viewer's remote override. Null means that key keeps the behavior it already had.
 * An empty map always returns null, including for a key the viewer never named.
 */
object RemoteActions {
    fun actionFor(key: String, options: PlayerOptions): String? {
        if (options.remoteMap.isBlank()) return null
        val action = options.remoteActions()[key] ?: return null
        return action.ifBlank { null }
    }
}

/**
 * Puts the activity in a small window. Devices and activities that cannot do this
 * return false. The call is swallowed so a missing system feature never crashes playback.
 */
fun enterPictureInPicture(activity: Activity): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (activity.isFinishing || activity.isDestroyed) return false
    if (!supportsPictureInPicture(activity)) return false
    if (activity.isInPictureInPictureMode) return true
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
    return runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
}

/** True when the system can shrink this activity. Already being in that window does not count. */
fun supportsPictureInPicture(activity: Activity): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (activity.isInPictureInPictureMode) return false
    return activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
}
