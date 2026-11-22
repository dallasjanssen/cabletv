package com.dallas.cabletv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dallas.cabletv.ui.CableApp
import com.dallas.cabletv.ui.CableTheme

class MainActivity : ComponentActivity() {

    private val vm: CableViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A cable box never sleeps while you are watching.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Live TV stops when the app leaves the screen, so no provider connection is held in the background.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onBackground()
                Lifecycle.Event.ON_START -> vm.onForeground()
                else -> Unit
            }
        })

        setContent {
            CableTheme {
                CableApp(vm)
            }
        }
    }
}
