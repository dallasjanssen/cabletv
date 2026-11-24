package com.dallas.cabletv

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dallas.cabletv.data.CHANNEL_LINK_SCHEME
import com.dallas.cabletv.data.CrashLog
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.ui.CableApp
import com.dallas.cabletv.ui.TvTheme

class MainActivity : ComponentActivity() {
    /** A cabletv:// link waiting for the channel list, such as one sent by Home Assistant. */
    private var link by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        // A cable box never sleeps while you are watching.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Rebuilding the screen, for example after a restore, does not play the opening link again.
        if (savedInstanceState == null) link = channelLink(intent)

        val store = SettingsStore(this)
        setContent {
            TvTheme {
                CableApp(store, link = link, onLinkHandled = { link = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        channelLink(intent)?.let { link = it }
    }

    /** The link in [intent], or null when it is not one of ours or was reopened from recent apps. */
    private fun channelLink(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return intent.dataString?.takeIf { it.startsWith("$CHANNEL_LINK_SCHEME:", ignoreCase = true) }
    }
}
