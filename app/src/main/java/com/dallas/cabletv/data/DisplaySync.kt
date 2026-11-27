package com.dallas.cabletv.data

import android.app.Activity
import android.os.Build
import android.view.Display
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * One display mode the panel can use. Pulled out of [Display.Mode] so the choice can be
 * checked without a window.
 */
internal data class DisplayModeSpec(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

/**
 * Switches the panel to the refresh rate of the video, and pins the current resolution when
 * asked. The call only receives a frame rate, not a picture size, so resolution switching
 * stays on the mode that is already showing. Guessing 1920x1080 would resize the panel for a
 * stream that is not that size.
 *
 * Both switches off puts the window back to the platform default (mode id 0) only if this
 * object had set a mode. A window that was never touched is left alone.
 */
object DisplaySync {
    private var changedActivity: WeakReference<Activity>? = null

    fun apply(activity: Activity, frameRate: Float, options: PlayerOptions) {
        try {
            if (!options.autoFrameRate && !options.switchResolution) {
                restoreDefault()
                return
            }
            val display = activity.currentDisplay() ?: return
            val currentMode = display.mode ?: return
            val supported = display.supportedModes ?: return
            val current = currentMode.toSpec()
            val chosen = chooseDisplayMode(
                modes = supported.map { it.toSpec() },
                current = current,
                frameRate = frameRate,
                autoFrameRate = options.autoFrameRate,
                filmRatesOnly = options.afrFilmRatesOnly,
                switchResolution = options.switchResolution,
            ) ?: return
            val previous = changedActivity?.get()
            if (previous != null && previous !== activity) {
                writeMode(previous, 0)
            }
            writeMode(activity, chosen.id)
            changedActivity = if (chosen.id != 0) WeakReference(activity) else null
        } catch (_: Throwable) {
            // A detached window or a display that disappeared should not take the channel down.
        }
    }

    private fun restoreDefault() {
        val activity = changedActivity?.get()
        if (activity == null) {
            changedActivity = null
            return
        }
        writeMode(activity, 0)
        changedActivity = null
    }
}

/** True when the frame rate is close enough to 50 or 60 that a film-only switch is allowed. */
internal fun isFiftyOrSixty(frameRate: Float): Boolean {
    if (!frameRate.isFinite()) return false
    return abs(frameRate - 50f) <= 1.5f || abs(frameRate - 60f) <= 1.5f
}

/**
 * The mode to prefer, or null when the window should not be touched.
 * Auto frame rate picks the closest refresh rate at the current width and height. Film-only
 * mode refuses every rate that is not within 1.5 of 50 or 60, and then this returns null
 * unless resolution switching still wants the current mode pinned. Resolution switching has
 * no video size to aim at, so it uses the current mode's size and, when auto frame rate is
 * off or the film gate refuses the rate, the current refresh rate.
 */
internal fun chooseDisplayMode(
    modes: List<DisplayModeSpec>,
    current: DisplayModeSpec,
    frameRate: Float,
    autoFrameRate: Boolean,
    filmRatesOnly: Boolean,
    switchResolution: Boolean,
): DisplayModeSpec? {
    if (!autoFrameRate && !switchResolution) return null
    val rateSwitch = autoFrameRate &&
        frameRate.isFinite() &&
        frameRate > 0f &&
        (!filmRatesOnly || isFiftyOrSixty(frameRate))
    if (!rateSwitch && !switchResolution) return null
    val targetRate = if (rateSwitch) frameRate else current.refreshRate
    val sameSize = modes.filter { it.width == current.width && it.height == current.height }
    val pool = if (sameSize.isEmpty()) listOf(current) else sameSize
    return pool.minWith(
        compareBy<DisplayModeSpec> { abs(it.refreshRate - targetRate) }
            .thenBy { if (it.id == current.id) 0 else 1 }
            .thenBy { it.id }
    )
}

private fun writeMode(activity: Activity, modeId: Int) {
    val window = activity.window ?: return
    val attrs = window.attributes ?: return
    if (attrs.preferredDisplayModeId == modeId) return
    attrs.preferredDisplayModeId = modeId
    window.attributes = attrs
}

private fun Activity.currentDisplay(): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }

private fun Display.Mode.toSpec(): DisplayModeSpec =
    DisplayModeSpec(
        id = modeId,
        width = physicalWidth,
        height = physicalHeight,
        refreshRate = refreshRate,
    )
