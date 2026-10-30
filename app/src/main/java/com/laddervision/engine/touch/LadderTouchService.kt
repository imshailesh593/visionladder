package com.laddervision.engine.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LadderTouchService : AccessibilityService() {

    companion object {
        private const val TAG = "LadderTouchService"
        var instance: LadderTouchService? = null
            private set

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _lastInjectionDeltaMs = MutableStateFlow(0.0f)
        val lastInjectionDeltaMs: StateFlow<Float> = _lastInjectionDeltaMs.asStateFlow()

        private val _totalGesturesDispatched = MutableStateFlow(0L)
        val totalGesturesDispatched: StateFlow<Long> = _totalGesturesDispatched.asStateFlow()

        // Emergency disarm flag
        @Volatile
        var isDisarmed: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.i(TAG, "LadderTouchService connected and active")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Passive observation; no OCR or accessibility inspection delay
    }

    override fun onInterrupt() {
        Log.w(TAG, "LadderTouchService interrupted")
        _isServiceConnected.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        _isServiceConnected.value = false
    }

    /**
     * Hardware Kill Switch: Intercepts volume keys to terminate automation within < 5ms
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                Log.w(TAG, "Hardware Kill Switch triggered by Volume Key! Disarming engine immediately.")
                disarmImmediately()
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    fun disarmImmediately() {
        isDisarmed = true
    }

    fun rearm() {
        isDisarmed = false
    }

    /**
     * Injects two simultaneous taps on screen within the exact same frame (Delta < 0.1ms).
     * Stroke 1: Blank column one price lower (x1, y1)
     * Stroke 2: Target price above (x2, y2)
     */
    fun dispatchDualSimultaneousTap(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        onCompleteCallback: ((Boolean, Float) -> Unit)? = null
    ): Boolean {
        if (isDisarmed) {
            Log.w(TAG, "Gesture rejected: engine is disarmed by safety kill switch.")
            return false
        }

        val t0Nanos = System.nanoTime()

        val gestureBuilder = GestureDescription.Builder()

        // Stroke 1: Blank column one price lower
        val path1 = Path().apply { moveTo(x1, y1) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0L, 1L)

        // Stroke 2: Target price above
        val path2 = Path().apply { moveTo(x2, y2) }
        val stroke2 = GestureDescription.StrokeDescription(path2, 0L, 1L)

        gestureBuilder.addStroke(stroke1)
        gestureBuilder.addStroke(stroke2)

        val gesture = gestureBuilder.build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val deltaMs = (System.nanoTime() - t0Nanos) / 1_000_000.0f
                _lastInjectionDeltaMs.value = deltaMs
                _totalGesturesDispatched.value += 1
                Log.d(TAG, "Dual simultaneous injection completed in $deltaMs ms (x1=$x1, y1=$y1, x2=$x2, y2=$y2)")
                onCompleteCallback?.invoke(true, deltaMs)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                val deltaMs = (System.nanoTime() - t0Nanos) / 1_000_000.0f
                Log.w(TAG, "Dual simultaneous injection cancelled after $deltaMs ms")
                onCompleteCallback?.invoke(false, deltaMs)
            }
        }, null)

        return dispatched
    }
}
