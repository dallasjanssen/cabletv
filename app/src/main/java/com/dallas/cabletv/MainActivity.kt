package com.dallas.cabletv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dallas.cabletv.data.CHANNEL_LINK_SCHEME
import com.dallas.cabletv.data.CrashLog
import com.dallas.cabletv.data.EpgCache
import com.dallas.cabletv.data.DebugLog
import com.dallas.cabletv.data.PlayerOptionsStore
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.data.companion.isTelevision
import com.dallas.cabletv.ui.CableApp
import com.dallas.cabletv.ui.TvTheme
import com.dallas.cabletv.ui.companion.CompanionApp
import com.dallas.cabletv.ui.enterPictureInPicture
import com.dallas.cabletv.ui.supportsPictureInPicture

class MainActivity : ComponentActivity() {
    /** A cabletv:// link waiting for the channel list, such as one sent by Home Assistant. */
    private var link by mutableStateOf<String?>(null)

    /**
     * True when the Cable TV app is showing. A TV always shows it. A phone shows the companion
     * (My TVs) unless it was opened by a cabletv: link or the person chose Watch on this phone.
     */
    private var showCable by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        CrashLog.recordExitReasons(this)
        EpgCache.open(this)
        DebugLog.install(this)
        // Rebuilding the screen, for example after a restore, does not play the opening link again.
        if (savedInstanceState == null) link = channelLink(intent)
        val phone = !isTelevision(this)
        showCable = when {
            !phone -> true
            savedInstanceState != null -> savedInstanceState.getBoolean(STATE_SHOW_CABLE, false)
            else -> link != null
        }
        keepAwakeWhileWatching()

        val store = SettingsStore(this)
        setContent {
            TvTheme {
                if (showCable) {
                    CableApp(store, link = link, onLinkHandled = { link = null })
                } else {
                    CompanionApp(onWatchOnPhone = {
                        showCable = true
                        keepAwakeWhileWatching()
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        channelLink(intent)?.let {
            link = it
            showCable = true
            keepAwakeWhileWatching()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOW_CABLE, showCable)
    }

    /**
     * Home, or leaving for another app, keeps the channel in a small window when that option is on.
     * A device that cannot do it is left alone.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!showCable || !supportsPictureInPicture(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) return
        if (!PlayerOptionsStore(this).load().pipOnHome) return
        enterPictureInPicture(this)
    }

    /** A cable box never sleeps while you are watching. The companion screens let the phone sleep. */
    private fun keepAwakeWhileWatching() {
        if (showCable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** The link in [intent], or null when it is not one of ours or was reopened from recent apps. */
    private fun channelLink(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return intent.dataString?.takeIf { it.startsWith("$CHANNEL_LINK_SCHEME:", ignoreCase = true) }
    }

    private companion object {
        const val STATE_SHOW_CABLE = "show_cable"
    }
}
