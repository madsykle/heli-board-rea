// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.TouchpadHandler
import helium314.keyboard.latin.utils.prefs
import kotlin.math.abs

/**
 * Labs-only space-bar trackpad.
 *
 * The feature is gated twice: the build type must be `labs`, and the Labs switch [PREF_KEY] must
 * be on (default off). Every entry point returns immediately on other build types, so release
 * behaviour is unchanged.
 *
 * Holding a popup-free space key still for [LONG_PRESS_TIMEOUT_MS] starts cursor movement. The
 * switch is read only when a keyboard or field starts, never from the touch path.
 *
 * RTL handling and second-finger selection are intentionally not implemented.
 */
object LabsSpaceTrackpad {
    const val PREF_KEY = "labs_space_trackpad"

    /** Labs-only space long-press timeout in milliseconds. */
    const val LONG_PRESS_TIMEOUT_MS = 350

    val isLabsBuild: Boolean = BuildConfig.BUILD_TYPE == "labs"

    @Volatile
    private var enabled = false
    @Volatile
    private var loaded = false

    /**
     * Read the switch at keyboard attach or field start, never on the touch path. A later layout
     * change reuses the volatile value; the Labs screen forces a refresh after it writes the pref.
     */
    fun load(context: Context, force: Boolean = false) {
        if (!isLabsBuild) {
            enabled = false
            loaded = false
            return
        }
        if (loaded && !force) return
        enabled = context.applicationContext.prefs().getBoolean(PREF_KEY, false)
        loaded = true
    }

    fun isEnabled(): Boolean = isLabsBuild && enabled

    /**
     * Timeout for the space key. A popup-free space uses the trackpad timeout; every other case
     * keeps HeliBoard's existing 1.5x timeout.
     */
    fun spaceTimeout(
        configuredTimeoutMs: Int,
        labsEnabled: Boolean,
        hasPopupKeys: Boolean,
    ): Int {
        if (labsEnabled && !hasPopupKeys) return LONG_PRESS_TIMEOUT_MS
        return configuredTimeoutMs * 3 / 2
    }

    /** True when the finger has not moved beyond the activation slop. */
    fun isWithinSlop(deltaX: Int, deltaY: Int, slopPx: Int): Boolean {
        val dx = deltaX.toLong()
        val dy = deltaY.toLong()
        val slop = slopPx.toLong()
        return dx * dx + dy * dy <= slop * slop
    }

    /** Called from MainKeyboardView.setKeyboard. */
    fun onKeyboardChanged(context: Context) {
        if (!isLabsBuild) return
        load(context)
        resetRuntimeState()
    }

    /** Called when a new field starts. */
    fun onFieldChanged(context: Context) {
        if (!isLabsBuild) return
        load(context)
        resetRuntimeState()
    }

    /** Called when the input view is hidden. */
    fun onKeyboardHidden() {
        if (!isLabsBuild) return
        resetRuntimeState()
    }

    private fun resetRuntimeState() {
        PointerTracker.cancelLabsSpaceTrackpad()
        TouchpadHandler.resetLabsTouchpadMode()
        KeyboardSwitcher.getInstance().mainKeyboardView?.setKeyTopVisualsHidden(false)
    }
}

/**
 * Pure step accumulation for the Labs trackpad. One horizontal step is one character; one
 * vertical step is one line. Acceleration matches the classic touchpad: the factor grows with
 * the absolute delta, and the divisor is tunable.
 */
class LabsTrackpadSteps(
    private val horizontalStepPx: Int,
    private val verticalStepPx: Int,
    private val accelerationDivisor: Float = 50f,
) {
    init {
        require(horizontalStepPx > 0) { "horizontalStepPx must be positive" }
        require(verticalStepPx > 0) { "verticalStepPx must be positive" }
        require(accelerationDivisor > 0f) { "accelerationDivisor must be positive" }
    }

    data class Steps(val horizontal: Int, val vertical: Int)

    private var accumulatedX = 0
    private var accumulatedY = 0

    fun reset() {
        accumulatedX = 0
        accumulatedY = 0
    }

    /** Adds a move delta and returns the whole steps that should be emitted now. */
    fun add(deltaX: Int, deltaY: Int): Steps {
        if (abs(deltaX) > abs(deltaY)) {
            accumulatedX += accelerated(deltaX)
            accumulatedY = 0
        } else {
            accumulatedY += accelerated(deltaY)
            accumulatedX = 0
        }
        val horizontal = accumulatedX / horizontalStepPx
        accumulatedX -= horizontal * horizontalStepPx
        val vertical = accumulatedY / verticalStepPx
        accumulatedY -= vertical * verticalStepPx
        return Steps(horizontal, vertical)
    }

    private fun accelerated(delta: Int): Int {
        val factor = 1.0f + abs(delta) / accelerationDivisor
        return (delta * factor).toInt()
    }
}
