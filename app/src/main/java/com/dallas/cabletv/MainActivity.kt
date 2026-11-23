package com.dallas.cabletv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dallas.cabletv.data.CrashLog
import com.dallas.cabletv.data.SettingsStore
import com.dallas.cabletv.ui.CableApp
import com.dallas.cabletv.ui.TvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        // A cable box never sleeps while you are watching.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val store = SettingsStore(this)
        setContent {
            TvTheme {
                CableApp(store)
            }
        }
    }
}
