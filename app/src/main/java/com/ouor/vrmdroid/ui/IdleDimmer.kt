package com.ouor.vrmdroid.ui

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ouor.vrmdroid.R
import com.ouor.vrmdroid.service.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Power saving after a period without touches, so long streams don't cook the phone.
 *
 * It never goes dark by surprise: a card counts down the last [WARNING_SEC] seconds ("5초 뒤
 * 절전 모드로 바뀌어요") and any touch cancels it. Then the screen fades to black, brightness
 * drops and the host pauses avatar rendering via [onDim]; tracking and sending continue.
 * The touch that wakes the screen is swallowed so it can't press a button by accident.
 */
class IdleDimmer(
    private val activity: Activity,
    private val overlay: View,
    private val badge: View,
    private val dot: View,
    private val text: TextView,
    private val warning: View,
    private val warningCount: TextView,
    private val warningTitle: TextView,
    /** Seconds before dimming; 0 disables. Read each tick so settings apply immediately. */
    private val timeoutSec: () -> Int,
    /** Whether dimming is allowed right now (tracking on, no dialog/guide in front, …). */
    private val canDim: () -> Boolean,
    private val onDim: () -> Unit,
    private val onWake: () -> Unit,
) {
    var dimmed = false
        private set
    private var lastInteraction = SystemClock.uptimeMillis()
    private var swallowGesture = false
    private var shownCount = -1
    private var loop: Job? = null

    fun start(scope: CoroutineScope) {
        loop?.cancel()
        loop = scope.launch {
            var lastDrift = 0L
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.uptimeMillis()
                val timeout = timeoutSec()
                if (dimmed) {
                    if (timeout == 0 || !canDim()) wake()
                    else if (now - lastDrift >= DRIFT_MS) { drift(); lastDrift = now }
                    continue
                }
                // Time spent where dimming isn't allowed (a sheet open, tracking off, …)
                // doesn't count as idle; otherwise closing a sheet could dim instantly.
                if (timeout == 0 || !canDim()) {
                    lastInteraction = now
                    hideWarning()
                    continue
                }
                val remainingMs = timeout * 1000L - (now - lastInteraction)
                when {
                    remainingMs <= 0 -> { hideWarning(); dim() }
                    remainingMs <= WARNING_SEC * 1000L -> showWarning(ceil(remainingMs / 1000.0).toInt())
                    else -> hideWarning()
                }
            }
        }
    }

    /** Call from Activity.dispatchTouchEvent; returns true when the event must be consumed. */
    fun onTouch(ev: MotionEvent): Boolean {
        lastInteraction = SystemClock.uptimeMillis()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) hideWarning() // countdown cancelled
        if (dimmed && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            wake()
            swallowGesture = true
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowGesture = false
            return true
        }
        return false
    }

    fun render(status: TrackingStatus) {
        val pill = StatusText.pill(activity, status)
        text.text = pill.text
        dot.backgroundTintList = ContextCompat.getColorStateList(activity, pill.color)
    }

    /** Back to normal without the wake callback (e.g. when the activity resumes). */
    fun reset() {
        lastInteraction = SystemClock.uptimeMillis()
        hideWarning()
        if (!dimmed) return
        dimmed = false
        overlay.animate().cancel()
        overlay.alpha = 1f
        overlay.visibility = View.GONE
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun showWarning(seconds: Int) {
        if (seconds == shownCount && warning.visibility == View.VISIBLE) return
        shownCount = seconds
        warningCount.text = seconds.toString()
        warningTitle.text = activity.resources.getQuantityString(R.plurals.dim_warn_title, seconds, seconds)
        if (warning.visibility != View.VISIBLE) {
            warning.alpha = 0f
            warning.translationY = -warning.resources.displayMetrics.density * 16
            warning.visibility = View.VISIBLE
            warning.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    private fun hideWarning() {
        shownCount = -1
        if (warning.visibility != View.VISIBLE) return
        warning.animate().cancel()
        warning.visibility = View.GONE
    }

    private fun dim() {
        dimmed = true
        drift()
        // Fade rather than snap to black, so it reads as "settling down", not "crashed".
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            if (dimmed) setBrightness(MIN_BRIGHTNESS)
        }.start()
        onDim()
    }

    private fun wake() {
        if (!dimmed) return
        reset()
        onWake()
    }

    /** Nudge the badge so nothing static sits on an OLED for hours. */
    private fun drift() {
        val w = overlay.width.takeIf { it > 0 } ?: return
        val h = overlay.height
        badge.translationX = Random.nextFloat() * w * 0.4f - w * 0.2f
        badge.translationY = Random.nextFloat() * h * 0.5f - h * 0.25f
    }

    private fun setBrightness(value: Float) {
        val attrs = activity.window.attributes
        attrs.screenBrightness = value
        activity.window.attributes = attrs
    }

    private companion object {
        const val WARNING_SEC = 5
        const val TICK_MS = 250L
        const val FADE_MS = 800L
        const val MIN_BRIGHTNESS = 0.01f
        const val DRIFT_MS = 60_000L
    }
}
