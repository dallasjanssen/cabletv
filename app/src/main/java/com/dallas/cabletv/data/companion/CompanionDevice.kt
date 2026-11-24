package com.dallas.cabletv.data.companion

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * True when the app runs on a TV. A TV runs the companion server, and a phone opens on My TVs.
 * Leanback is the TV feature flag. The UI mode check covers TVs that leave the flag out.
 */
fun isTelevision(context: Context): Boolean {
    if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
