package com.laddervision.engine.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.laddervision.engine.capture.FrameCaptureEngine
import com.laddervision.engine.model.LadderConfig
import com.laddervision.engine.widget.VisionWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

class LadderTouchService : AccessibilityService() {

    companion object {
        private const val TAG = "LadderTouchService"
        const val ACTION_INJECT_DUAL_TAP = "com.laddervision.action.INJECT_DUAL_TAP"
        const val ACTION_INJECT_SINGLE_TAP = "com.laddervision.action.INJECT_SINGLE_TAP"
        const val ACTION_TRIGGER_COMPANION = "com.laddervision.action.TRIGGER_COMPANION"
        const val ACTION_SCALP_COLUMN_A = "com.laddervision.action.SCALP_COLUMN_A"
        const val ACTION_SCALP_COLUMN_B = "com.laddervision.action.SCALP_COLUMN_B"
        const val ACTION_ENABLE_TOUCH_HOOK = "com.laddervision.action.ENABLE_TOUCH_HOOK"
        const val ACTION_DISABLE_TOUCH_HOOK = "com.laddervision.action.DISABLE_TOUCH_HOOK"

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

        // Companion touch hook state - OFF by default to preserve 100% native phone touch everywhere
        @Volatile
        var isTouchHookArmed: Boolean = false

        private val _isHookArmedState = MutableStateFlow(false)
        val isHookArmedState: StateFlow<Boolean> = _isHookArmedState.asStateFlow()

        // Prevents interceptor from catching its own synthetic injections
        @Volatile
        var isSyntheticInjecting: Boolean = false

        // Exact Vacant Boundary Targeting State (Live Calibrated)
        // - Column A: Vacant row where Column A ends from top (above which there are NO numbers)
        // - Column B: Vacant row where Column B ends from bottom (below which there are NO numbers)
        @Volatile
        var activeBackStrikeRow: Int = 7

        @Volatile
        var activeLayStrikeRow: Int = 8

        @Volatile
        var activeBackStrikeY: Float = 1489f

        @Volatile
        var activeLayStrikeY: Float = 1565f

        @Volatile
        var activeColACenterX: Float = 411f

        @Volatile
        var activeColBCenterX: Float = 781f

        @Volatile
        var activeRowH: Int = 76

        @Volatile
        var activeColW: Int = 185

        @Volatile
        var activeLadderTop: Int = 919

        @Volatile
        var activeRowCount: Int = 14
    }

    private val gestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_INJECT_DUAL_TAP -> {
                    val x1 = intent.getFloatExtra("x1", 390f)
                    val y1 = intent.getFloatExtra("y1", 1452f)
                    val x2 = intent.getFloatExtra("x2", 790f)
                    val y2 = intent.getFloatExtra("y2", 1604f)
                    rearm()
                    val ok = dispatchDualSimultaneousTap(x1, y1, x2, y2)
                    Log.i(TAG, "Broadcast INJECT_DUAL_TAP executed: ok=$ok (x1=$x1, y1=$y1, x2=$x2, y2=$y2)")
                }
                ACTION_INJECT_SINGLE_TAP -> {
                    val x = intent.getFloatExtra("x", 397f)
                    val y = intent.getFloatExtra("y", 1300f)
                    val durationMs = intent.getLongExtra("durationMs", 30L)
                    rearm()
                    val ok = dispatchSingleTap(x, y, durationMs)
                    Log.i(TAG, "Broadcast INJECT_SINGLE_TAP executed: ok=$ok (x=$x, y=$y, duration=${durationMs}ms)")
                }
                ACTION_TRIGGER_COMPANION -> {
                    val userX = intent.getFloatExtra("userX", activeColBCenterX)
                    val userY = intent.getFloatExtra("userY", activeLayStrikeY - 95f)
                    val targetX = intent.getFloatExtra("targetX", activeColBCenterX)
                    val targetY = intent.getFloatExtra("targetY", activeLayStrikeY)
                    val delayMs = intent.getLongExtra("delayMs", 150L)
                    rearm()
                    dispatchCompanionSequence(userX, userY, targetX, targetY, delayMs)
                    Log.i(TAG, "Broadcast TRIGGER_COMPANION executed: user=($userX, $userY) -> delay ${delayMs}ms -> target=($targetX, $targetY)")
                }
                ACTION_SCALP_COLUMN_A -> {
                    // Strictly isolated Column A Scalp: Strikes vacant row where Col A ends from top
                    val userX = intent.getFloatExtra("userX", activeColACenterX)
                    val userY = intent.getFloatExtra("userY", activeBackStrikeY + 95f)
                    val targetX = intent.getFloatExtra("targetX", activeColACenterX)
                    val targetY = intent.getFloatExtra("targetY", activeBackStrikeY)
                    val delayMs = intent.getLongExtra("delayMs", 20L)
                    val durationMs = intent.getLongExtra("durationMs", 15L)
                    val isAtomic = intent.getBooleanExtra("atomic", true)
                    rearm()
                    if (isAtomic) {
                        dispatchAtomicCompanionTap(userX, userY, targetX, targetY, gapMs = delayMs, strokeDurationMs = durationMs)
                    } else {
                        dispatchCompanionSequence(userX, userY, targetX, targetY, delayMs, targetDurationMs = durationMs)
                    }
                    triggerCompanionFeedback(targetX, targetY, isColA = true, targetRow = activeBackStrikeRow)
                    Log.i(TAG, "Broadcast SCALP_COLUMN_A [Vacant where Col A ends from top]: user=($userX, $userY) -> strike=($targetX, $targetY) [Row $activeBackStrikeRow, Col B untouched]")
                }
                ACTION_SCALP_COLUMN_B -> {
                    // Strictly isolated Column B Scalp: Strikes vacant row where Col B ends from bottom
                    val userX = intent.getFloatExtra("userX", activeColBCenterX)
                    val userY = intent.getFloatExtra("userY", activeLayStrikeY - 95f)
                    val targetX = intent.getFloatExtra("targetX", activeColBCenterX)
                    val targetY = intent.getFloatExtra("targetY", activeLayStrikeY)
                    val delayMs = intent.getLongExtra("delayMs", 20L)
                    val durationMs = intent.getLongExtra("durationMs", 15L)
                    val isAtomic = intent.getBooleanExtra("atomic", true)
                    rearm()
                    if (isAtomic) {
                        dispatchAtomicCompanionTap(userX, userY, targetX, targetY, gapMs = delayMs, strokeDurationMs = durationMs)
                    } else {
                        dispatchCompanionSequence(userX, userY, targetX, targetY, delayMs, targetDurationMs = durationMs)
                    }
                    triggerCompanionFeedback(targetX, targetY, isColA = false, targetRow = activeLayStrikeRow)
                    Log.i(TAG, "Broadcast SCALP_COLUMN_B [Vacant where Col B ends from bottom]: user=($userX, $userY) -> strike=($targetX, $targetY) [Row $activeLayStrikeRow, Col A untouched]")
                }
                ACTION_ENABLE_TOUCH_HOOK -> {
                    armTouchHook()
                    Log.i(TAG, "Touch hook armed via broadcast")
                }
                ACTION_DISABLE_TOUCH_HOOK -> {
                    disarmTouchHook()
                    Log.i(TAG, "Touch hook disarmed via broadcast")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.i(TAG, "LadderTouchService connected and active. Touch interceptor is DISARMED by default (100% native phone touch preserved).")

        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_INJECT_DUAL_TAP)
                addAction(ACTION_INJECT_SINGLE_TAP)
                addAction(ACTION_TRIGGER_COMPANION)
                addAction(ACTION_SCALP_COLUMN_A)
                addAction(ACTION_SCALP_COLUMN_B)
                addAction(ACTION_ENABLE_TOUCH_HOOK)
                addAction(ACTION_DISABLE_TOUCH_HOOK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(gestureReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(gestureReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register broadcast receiver", e)
        }

        // NOTE: We do NOT auto-attach touch interceptors on boot or service connect!
        // The phone screen and touchpad remain 100% untouched and native.
    }

    @Volatile
    private var isInRemoteDesktopSession = false

    private val periodicScanHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var isPeriodicScanRunning = false

    private val periodicScanRunnable = object : Runnable {
        override fun run() {
            if (isTouchHookArmed && !isDisarmed && (stripViewA != null || stripViewB != null)) {
                refreshLadderBoundariesFromScreen()
                periodicScanHandler.postDelayed(this, 1500L)
            } else {
                isPeriodicScanRunning = false
            }
        }
    }

    private fun startPeriodicScan() {
        if (isPeriodicScanRunning) return
        isPeriodicScanRunning = true
        periodicScanHandler.post(periodicScanRunnable)
    }

    private fun stopPeriodicScan() {
        isPeriodicScanRunning = false
        periodicScanHandler.removeCallbacks(periodicScanRunnable)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            // CRITICAL: Ignore events from our own engine, SystemUI, Toasts, overlays, and keyboards
            if (pkg.isEmpty() || pkg == packageName || pkg == "android" || pkg.contains("systemui") || pkg.contains("inputmethod")) {
                return
            }

            val cls = event.className?.toString() ?: ""
            val isRemoteDesktop = pkg.contains("rdc") || pkg.contains("com.microsoft") || pkg.contains("windowsapp")
            val isSession = isRemoteDesktop && (cls.contains("SessionActivity") || cls.contains("Session") || (!cls.contains("HomeActivity") && !cls.contains("MainActivity") && !cls.contains("Browser") && !cls.contains("EditRemote") && !cls.contains("Settings")))

            if (!isSession) {
                // User navigated away from Remote Desktop session (e.g. Launcher, WhatsApp, Settings, or RDC PC list)
                isInRemoteDesktopSession = false
                stopPeriodicScan()
                if (stripViewA != null || stripViewB != null) {
                    stopInvisibleTouchInterceptor()
                    Log.d(TAG, "Not in active Remote Desktop session ($pkg / $cls) -> Interceptor detached to preserve native phone touch")
                }
            } else if (!isDisarmed && isTouchHookArmed) {
                // User in active Remote Desktop session and hook is armed -> attach interceptor
                isInRemoteDesktopSession = true
                if (stripViewA == null || stripViewB == null) {
                    startInvisibleTouchInterceptor()
                    Log.d(TAG, "Entered Remote Desktop Session ($cls) -> Interceptor attached and armed")
                }
                refreshLadderBoundariesFromScreen()
                startPeriodicScan()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "LadderTouchService interrupted")
        _isServiceConnected.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicScan()
        stopInvisibleTouchInterceptor()
        try {
            unregisterReceiver(gestureReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        if (instance == this) {
            instance = null
        }
        _isServiceConnected.value = false
    }

    private var windowManager: WindowManager? = null
    private var stripViewA: View? = null
    private var stripViewB: View? = null
    private var paramsA: WindowManager.LayoutParams? = null
    private var paramsB: WindowManager.LayoutParams? = null

    fun setInterceptorsTouchable(touchable: Boolean) {
        val wm = windowManager ?: return
        val vA = stripViewA ?: return
        val vB = stripViewB ?: return
        val pA = paramsA ?: return
        val pB = paramsB ?: return

        try {
            if (touchable) {
                pA.flags = pA.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                pB.flags = pB.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                pA.flags = pA.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                pB.flags = pB.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            wm.updateViewLayout(vA, pA)
            wm.updateViewLayout(vB, pB)
        } catch (_: Exception) {}
    }

    private fun updateInterceptorPositions() {
        val wm = windowManager ?: return
        val vA = stripViewA ?: return
        val vB = stripViewB ?: return
        val pA = paramsA ?: return
        val pB = paramsB ?: return
        try {
            val stripWidth = (activeColW * 1.15f).toInt().coerceIn(160, 280)
            val stripHeight = (activeRowCount * activeRowH).coerceAtLeast(600)
            val colALeft = (activeColACenterX - (stripWidth / 2)).toInt()
            val colBLeft = (activeColBCenterX - (stripWidth / 2)).toInt()

            pA.x = colALeft
            pA.y = activeLadderTop
            pA.width = stripWidth
            pA.height = stripHeight

            pB.x = colBLeft
            pB.y = activeLadderTop
            pB.width = stripWidth
            pB.height = stripHeight

            wm.updateViewLayout(vA, pA)
            wm.updateViewLayout(vB, pB)
            Log.d(TAG, "Updated touch strip positions: Col A at $colALeft, Col B at $colBLeft, top=$activeLadderTop, H=$stripHeight")
        } catch (e: Exception) {
            Log.w(TAG, "updateInterceptorPositions failed: ${e.message}")
        }
    }

    /**
     * Attaches two 100% invisible touch strips over Column A and Column B.
     * ZERO pixels drawn, zero popups, zero draggable handles.
     * The Price column and rest of the screen are completely open for scrolling/tapping.
     * STRICTLY active only when explicitly armed and inside Remote Desktop.
     */
    fun startInvisibleTouchInterceptor() {
        if (!isTouchHookArmed || isDisarmed) {
            Log.d(TAG, "Touch interceptor not started: armed=$isTouchHookArmed, disarmed=$isDisarmed")
            return
        }
        if (stripViewA != null || stripViewB != null) return
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            val ladderTopY: Float = if (activeLadderTop > 0) activeLadderTop.toFloat() else (if (screenW >= 1400) 988f else 919f)
            val rowH: Float = if (activeRowH > 0) activeRowH.toFloat() else (if (screenW >= 1400) 95f else 76f)
            val rowCnt: Int = if (activeRowCount > 0) activeRowCount else (if (screenW >= 1400) 18 else 14)

            if (activeColACenterX <= 0f || activeColBCenterX <= 0f) {
                if (screenW >= 1400) {
                    activeColACenterX = 503f
                    activeColBCenterX = 985f
                } else {
                    activeColACenterX = 411f
                    activeColBCenterX = 781f
                }
            }
            if (activeBackStrikeY <= 0f) {
                activeBackStrikeY = ladderTopY + activeBackStrikeRow * rowH + rowH / 2f
            }
            if (activeLayStrikeY <= 0f) {
                activeLayStrikeY = ladderTopY + activeLayStrikeRow * rowH + rowH / 2f
            }

            val stripWidth = (activeColW.toFloat() * 1.15f).toInt().coerceIn(160, 280)
            val stripHeight = (rowCnt * rowH).toInt()
            val colALeft = (activeColACenterX - (stripWidth / 2)).toInt()
            val colBLeft = (activeColBCenterX - (stripWidth / 2)).toInt()

            // Strip A: Column A (Cyan / Back)
            // Vacant Boundary Rule: Selects the vacant row where Column A ends from the top
            // (immediately above the highest price bid row with volume, above which there are NO numbers)
            stripViewA = View(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnTouchListener { _, event ->
                    if (isDisarmed || isSyntheticInjecting || !isTouchHookArmed) return@setOnTouchListener false
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        val rawX = event.rawX
                        val rawY = event.rawY
                        
                        val targetRow = activeBackStrikeRow
                        val targetY = activeBackStrikeY
                        val targetX = activeColACenterX

                        dispatchAtomicCompanionTap(
                            userX = rawX,
                            userY = rawY,
                            targetX = targetX,
                            targetY = targetY,
                            gapMs = 20L,
                            strokeDurationMs = 15L
                        )
                        triggerCompanionFeedback(targetX, targetY, isColA = true, targetRow = targetRow)
                        Log.i(TAG, "Headless Interceptor: User tapped Col A @ ($rawX, $rawY) -> Vacant Strike @ ($targetX, $targetY) [Row $targetRow where Col A ends from top; Col B untouched]")
                        
                        refreshLadderBoundariesFromScreen()
                        return@setOnTouchListener true
                    }
                    false
                }
            }

            val paramsA = WindowManager.LayoutParams(
                stripWidth,
                stripHeight,
                colALeft,
                ladderTopY.toInt(),
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            // Strip B: Column B (Pink / Lay)
            // Vacant Boundary Rule: Selects the vacant row where Column B ends from the bottom
            // (immediately below the lowest price ask row with volume, below which there are NO numbers)
            stripViewB = View(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnTouchListener { _, event ->
                    if (isDisarmed || isSyntheticInjecting || !isTouchHookArmed) return@setOnTouchListener false
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        val rawX = event.rawX
                        val rawY = event.rawY
                        
                        val targetRow = activeLayStrikeRow
                        val targetY = activeLayStrikeY
                        val targetX = activeColBCenterX

                        dispatchAtomicCompanionTap(
                            userX = rawX,
                            userY = rawY,
                            targetX = targetX,
                            targetY = targetY,
                            gapMs = 20L,
                            strokeDurationMs = 15L
                        )
                        triggerCompanionFeedback(targetX, targetY, isColA = false, targetRow = targetRow)
                        Log.i(TAG, "Headless Interceptor: User tapped Col B @ ($rawX, $rawY) -> Vacant Strike @ ($targetX, $targetY) [Row $targetRow where Col B ends from bottom; Col A untouched]")
                        
                        refreshLadderBoundariesFromScreen()
                        return@setOnTouchListener true
                    }
                    false
                }
            }

            val paramsB = WindowManager.LayoutParams(
                stripWidth,
                stripHeight,
                colBLeft,
                ladderTopY.toInt(),
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            this.paramsA = paramsA
            this.paramsB = paramsB
            windowManager?.addView(stripViewA, paramsA)
            windowManager?.addView(stripViewB, paramsB)
            Log.i(TAG, "Invisible Touch Interceptors attached over Col A ($colALeft..${colALeft + stripWidth}) and Col B ($colBLeft..${colBLeft + stripWidth})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach invisible touch interceptors", e)
        }
    }

    fun stopInvisibleTouchInterceptor() {
        try {
            stripViewA?.let { windowManager?.removeViewImmediate(it) }
        } catch (e: Exception) {
            try { stripViewA?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        }
        try {
            stripViewB?.let { windowManager?.removeViewImmediate(it) }
        } catch (e: Exception) {
            try { stripViewB?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        }
        stripViewA = null
        stripViewB = null
        paramsA = null
        paramsB = null
        Log.i(TAG, "Invisible Touch Interceptors removed. Phone touch digitizer 100% native.")
    }

    fun armTouchHook() {
        isTouchHookArmed = true
        _isHookArmedState.value = true
        rearm()
        startInvisibleTouchInterceptor()
        refreshLadderBoundariesFromScreen()
        startPeriodicScan()
        VisionWidgetProvider.updateAllWidgets(this)
    }

    fun disarmTouchHook() {
        isTouchHookArmed = false
        _isHookArmedState.value = false
        stopPeriodicScan()
        stopInvisibleTouchInterceptor()
        VisionWidgetProvider.updateAllWidgets(this)
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
        isTouchHookArmed = false
        _isHookArmedState.value = false
        stopInvisibleTouchInterceptor()
        VisionWidgetProvider.updateAllWidgets(this)
        Log.w(TAG, "LadderTouchService DISARMED IMMEDIATELY (<5ms). All overlays detached.")
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

    /**
     * Injects a clean single tap at (x, y) with reliable duration for Remote Desktop.
     */
    fun dispatchSingleTap(
        x: Float,
        y: Float,
        durationMs: Long = 30L,
        onCompleteCallback: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (isDisarmed) {
            Log.w(TAG, "Single tap rejected: engine disarmed.")
            return false
        }

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        isSyntheticInjecting = true
        setInterceptorsTouchable(false)

        val restoreHandler = Handler(Looper.getMainLooper())
        val restoreRunnable = Runnable {
            isSyntheticInjecting = false
            setInterceptorsTouchable(true)
        }
        restoreHandler.postDelayed(restoreRunnable, durationMs + 80L)

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                restoreHandler.removeCallbacks(restoreRunnable)
                isSyntheticInjecting = false
                setInterceptorsTouchable(true)
                Log.d(TAG, "Single tap completed at ($x, $y) with duration ${durationMs}ms")
                onCompleteCallback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                restoreHandler.removeCallbacks(restoreRunnable)
                isSyntheticInjecting = false
                setInterceptorsTouchable(true)
                Log.w(TAG, "Single tap cancelled at ($x, $y)")
                onCompleteCallback?.invoke(false)
            }
        }, null)
    }

    /**
     * Injects near-realtime atomic companion tap in a SINGLE gesture description:
     * Stroke 1: User's manual selection tap (starts at 0ms, duration strokeDurationMs)
     * Stroke 2: Companion Vacant Row Strike (starts at strokeDurationMs + gapMs, duration strokeDurationMs)
     * Total sequence latency: ~35-45ms (hardware near real-time, within 2 frames at 60Hz).
     */
    fun dispatchAtomicCompanionTap(
        userX: Float,
        userY: Float,
        targetX: Float,
        targetY: Float,
        gapMs: Long = 20L,
        strokeDurationMs: Long = 15L,
        onCompleteCallback: ((Boolean, Float) -> Unit)? = null
    ): Boolean {
        if (isDisarmed) {
            Log.w(TAG, "Atomic companion tap rejected: engine disarmed.")
            return false
        }

        val t0Nanos = System.nanoTime()
        val gestureBuilder = GestureDescription.Builder()

        val path1 = Path().apply { moveTo(userX, userY) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0L, strokeDurationMs.coerceAtLeast(1L))
        gestureBuilder.addStroke(stroke1)

        val path2 = Path().apply { moveTo(targetX, targetY) }
        val start2 = strokeDurationMs + gapMs.coerceAtLeast(1L)
        val stroke2 = GestureDescription.StrokeDescription(path2, start2, strokeDurationMs.coerceAtLeast(1L))
        gestureBuilder.addStroke(stroke2)

        val gesture = gestureBuilder.build()

        isSyntheticInjecting = true
        // Make interceptor strips non-touchable so synthetic gestures pass directly to Remote Desktop
        setInterceptorsTouchable(false)

        val restoreHandler = Handler(Looper.getMainLooper())
        val restoreRunnable = Runnable {
            isSyntheticInjecting = false
            setInterceptorsTouchable(true)
        }
        val timeoutMs = strokeDurationMs * 2 + gapMs + 100L
        restoreHandler.postDelayed(restoreRunnable, timeoutMs)

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                restoreHandler.removeCallbacks(restoreRunnable)
                isSyntheticInjecting = false
                setInterceptorsTouchable(true)
                val deltaMs = (System.nanoTime() - t0Nanos) / 1_000_000.0f
                _lastInjectionDeltaMs.value = deltaMs
                _totalGesturesDispatched.value += 1
                Log.i(TAG, "Near-realtime atomic companion tap completed in $deltaMs ms (Stroke 1: ($userX, $userY) -> Stroke 2: ($targetX, $targetY) at +${start2}ms)")
                
                onCompleteCallback?.invoke(true, deltaMs)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                restoreHandler.removeCallbacks(restoreRunnable)
                isSyntheticInjecting = false
                setInterceptorsTouchable(true)
                val deltaMs = (System.nanoTime() - t0Nanos) / 1_000_000.0f
                Log.w(TAG, "Near-realtime atomic companion tap cancelled after $deltaMs ms")
                onCompleteCallback?.invoke(false, deltaMs)
            }
        }, null)
    }

    /**
     * Injects the Companion Scalping sequence:
     * 1. User manual tap at (userX, userY)
     * 2. Wait delayMs (default 150ms, < 1s)
     * 3. Automatic follow-up tap at (targetX, targetY) on the vacant row in the same column
     */
    fun dispatchCompanionSequence(
        userX: Float,
        userY: Float,
        targetX: Float,
        targetY: Float,
        delayMs: Long = 150L,
        userDurationMs: Long = 30L,
        targetDurationMs: Long = 30L,
        onCompleteCallback: ((Boolean) -> Unit)? = null
    ) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        // Step 1: User's manual selection tap
        dispatchSingleTap(userX, userY, durationMs = userDurationMs) { ok1 ->
            if (ok1) {
                // Step 2: Schedule sub-second companion vacant strike
                handler.postDelayed({
                    dispatchSingleTap(targetX, targetY, durationMs = targetDurationMs) { ok2 ->
                        if (ok2) {
                            triggerCompanionFeedback(targetX, targetY)
                        }
                        Log.i(TAG, "Companion sequence finished: user=($userX, $userY) -> target=($targetX, $targetY) ok=$ok2")
                        onCompleteCallback?.invoke(ok2)
                    }
                }, delayMs)
            } else {
                onCompleteCallback?.invoke(false)
            }
        }
    }

    /**
     * Triggers real-time visual feedback when a vacant row companion order is struck:
     * 1. Real-time in-place cell highlight over the exact vacant row being clicked.
     * 2. Single sleek floating HUD banner at the top (no duplicate system Toast).
     * 3. Haptic feedback completely removed.
     */
    private fun triggerCompanionFeedback(
        targetX: Float,
        targetY: Float,
        isColA: Boolean = (targetX < activeColBCenterX - 50f),
        targetRow: Int = if (isColA) activeBackStrikeRow else activeLayStrikeRow
    ) {
        // 1. Instant Real-Time Vacant Row Cell Highlight
        showRealtimeVacantRowHighlight(targetX, targetY, targetRow, isColA)

        // 2. Single floating HUD pill banner (no double Toast notification)
        val colLabel = if (isColA) "Column A (Back)" else "Column B (Lay)"
        val boundaryDesc = if (isColA) "ends from top" else "ends from bottom"
        val msg = "⚡ $colLabel: Vacant row $targetRow selected ($boundaryDesc)"
        showFloatingFeedbackPill(msg, isColA)
    }

    /**
     * Real-time visual highlight directly over the vacant row being struck.
     * Flashes instantly (0ms) and smoothly fades out over 450ms.
     * FLAG_NOT_TOUCHABLE ensures zero impact on touches.
     */
    private fun showRealtimeVacantRowHighlight(
        targetX: Float,
        targetY: Float,
        targetRow: Int,
        isColA: Boolean
    ) {
        Handler(Looper.getMainLooper()).post {
            try {
                val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val dm = resources.displayMetrics
                val screenW = dm.widthPixels
                val screenH = dm.heightPixels

                val cellW = if (activeColW in 80..400) activeColW else (240 * (screenW / 1440f)).toInt().coerceIn(160, 260)
                val cellH = if (activeRowH in 40..150) activeRowH else (95 * (screenH / 3168f)).toInt().coerceIn(65, 105)

                val highlightView = android.widget.FrameLayout(this).apply {
                    val strokeColor = if (isColA) Color.parseColor("#00E5FF") else Color.parseColor("#FF4081")
                    val fillColor = if (isColA) Color.argb(90, 0, 229, 255) else Color.argb(90, 255, 64, 129)

                    val bg = GradientDrawable().apply {
                        setColor(fillColor)
                        cornerRadius = 8f
                        setStroke(4, strokeColor)
                    }
                    background = bg

                    val label = TextView(context).apply {
                        text = "● VACANT #$targetRow"
                        setTextColor(Color.WHITE)
                        textSize = 10f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setShadowLayer(4f, 0f, 0f, strokeColor)
                    }
                    addView(
                        label,
                        android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    cellW,
                    cellH,
                    (targetX - cellW / 2).toInt(),
                    (targetY - cellH / 2).toInt(),
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                wm.addView(highlightView, params)

                // Flash highlight in real time and smoothly fade out over 450ms
                highlightView.alpha = 1.0f
                highlightView.animate()
                    .alpha(0.0f)
                    .setDuration(450L)
                    .withEndAction {
                        try {
                            wm.removeView(highlightView)
                        } catch (_: Exception) {}
                    }
                    .start()
            } catch (e: Exception) {
                Log.w(TAG, "Vacant row highlight error: ${e.message}")
            }
        }
    }

    private var feedbackPillView: TextView? = null
    private val pillHandler = Handler(Looper.getMainLooper())

    private fun showFloatingFeedbackPill(text: String, isColA: Boolean) {
        pillHandler.post {
            try {
                val wm = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
                if (feedbackPillView != null) {
                    try { wm.removeView(feedbackPillView) } catch (_: Exception) {}
                    feedbackPillView = null
                }

                val tv = TextView(this).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(32, 16, 32, 16)
                    gravity = Gravity.CENTER
                    val bg = GradientDrawable().apply {
                        setColor(Color.parseColor("#EE0B0F19"))
                        cornerRadius = 24f
                        setStroke(3, if (isColA) Color.parseColor("#00E5FF") else Color.parseColor("#FF4081"))
                    }
                    background = bg
                    elevation = 20f
                }

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val p = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    0,
                    140, // 140px down from status bar
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }

                wm.addView(tv, p)
                feedbackPillView = tv

                pillHandler.postDelayed({
                    try {
                        if (feedbackPillView == tv) {
                            wm.removeView(tv)
                            feedbackPillView = null
                        }
                    } catch (_: Exception) {}
                }, 1600L)
            } catch (e: Exception) {
                Log.w(TAG, "Floating feedback pill error: ${e.message}")
            }
        }
    }

    private val boundaryExecutor = Executors.newSingleThreadExecutor()
    private var lastScreenshotTimeMs = 0L

    /**
     * Scans the active screen bitmap via AccessibilityService.takeScreenshot (Android 11+)
     * to dynamically detect where Column A ends from top and Column B ends from bottom.
     */
    fun refreshLadderBoundariesFromScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScreenshotTimeMs < 1200L) return
        lastScreenshotTimeMs = now

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hwBuffer = screenshot.hardwareBuffer
                    val colorSpace = screenshot.colorSpace
                    val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    hwBuffer.close()
                    if (bitmap != null) {
                        boundaryExecutor.execute {
                            try {
                                analyzeScreenBitmap(bitmap)
                            } catch (e: Exception) {
                                Log.w(TAG, "Screen bitmap analysis failed: ${e.message}")
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.d(TAG, "takeScreenshot failure code: $errorCode")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot exception: ${e.message}")
        }
    }

    private fun analyzeScreenBitmap(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height

        // 1. Detect Royal Blue Price Column (R < 45, G < 45, B > 125)
        var minPriceX = width
        var maxPriceX = 0

        for (x in 0 until width step 8) {
            var count = 0
            for (y in 0 until height step 8) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r < 45 && g < 45 && b > 125) {
                    count++
                }
            }
            if (count > 25) {
                if (x < minPriceX) minPriceX = x
                if (x > maxPriceX) maxPriceX = x
            }
        }

        if (maxPriceX <= minPriceX) return // Price column not visible

        // 2. Detect Y range of Royal Blue Price Column
        var minPriceY = height
        var maxPriceY = 0
        for (y in 0 until height step 8) {
            var rowBlue = 0
            for (x in minPriceX until maxPriceX step 8) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r < 45 && g < 45 && b > 125) {
                    rowBlue++
                }
            }
            if (rowBlue > 3) {
                if (y < minPriceY) minPriceY = y
                if (y > maxPriceY) maxPriceY = y
            }
        }

        if (maxPriceY <= minPriceY) return

        val rawPriceColW = maxPriceX - minPriceX + 1
        val detectedLadderBot = maxPriceY
        val ladderH = maxPriceY - minPriceY

        // 3. Detect exact row height dynamically from horizontal grid lines in Price column
        val gridYs = ArrayList<Int>()
        for (y in minPriceY until maxPriceY) {
            var dark = 0
            for (x in (minPriceX + 10) until (maxPriceX - 10) step 4) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r + g + b < 180) {
                    dark++
                }
            }
            if (dark > (rawPriceColW - 20) / 5) {
                if (gridYs.isEmpty() || (y - gridYs.last()) > 20) {
                    gridYs.add(y)
                }
            }
        }

        val detectedLadderTop: Int
        val detectedRowH: Int
        val detectedRowCnt: Int

        if (gridYs.size >= 8) {
            var diffSum = 0
            for (i in 1 until gridYs.size) {
                diffSum += (gridYs[i] - gridYs[i - 1])
            }
            detectedRowH = diffSum / (gridYs.size - 1)
            detectedLadderTop = gridYs[0]
            detectedRowCnt = gridYs.size - 1
        } else {
            detectedRowH = (ladderH / 18).coerceIn(60, 110)
            detectedLadderTop = minPriceY
            detectedRowCnt = (ladderH / detectedRowH).coerceIn(12, 22)
        }

        // 4. Exact column boundaries derived from the Royal Blue Price Column
        // The Price column is flanked on the left by Column A (Back) and on the right by Column B (Lay).
        // In the Bet Angel ladder, Column A, Price, and Column B all share the exact same cell width.
        val colAPriceLine = minPriceX - 6
        val priceColBLine = maxPriceX + 6
        val detectedPriceColW = (priceColBLine - colAPriceLine).coerceIn(160, 260)

        val colALeftLine = colAPriceLine - detectedPriceColW
        val colBRightLine = priceColBLine + detectedPriceColW

        val detectedColACenterX = (colALeftLine + colAPriceLine) / 2
        val detectedColBCenterX = (priceColBLine + colBRightLine) / 2

        // Safe sampling box: kept strictly within cell interior away from horizontal and vertical grid borders
        val boxHalfH = (detectedRowH * 0.20f).toInt().coerceAtLeast(8)
        val boxHalfW = (detectedPriceColW * 0.20f).toInt().coerceAtLeast(20)

        // 5. Scan Column A from top (r = 1 until detectedRowCnt - 1)
        // Skip row 0 (Cancel button header). Find FIRST row with Back volume (dark pixels)
        var firstBackRow = -1
        for (r in 1 until (detectedRowCnt - 1)) {
            val yMid = detectedLadderTop + r * detectedRowH + detectedRowH / 2
            if (yMid >= height) break
            var darkCount = 0
            for (dy in -boxHalfH..boxHalfH step 2) {
                val py = yMid + dy
                if (py !in 0 until height) continue
                for (dx in -boxHalfW..boxHalfW step 4) {
                    val px = detectedColACenterX + dx
                    if (px !in 0 until width) continue
                    val pixel = bitmap.getPixel(px, py)
                    val rC = (pixel shr 16) and 0xFF
                    val gC = (pixel shr 8) and 0xFF
                    val bC = pixel and 0xFF
                    if (rC + gC + bC < 220) {
                        darkCount++
                    }
                }
            }
            if (darkCount >= 10) {
                firstBackRow = r
                break
            }
        }

        // 6. Scan Column B from bottom of Lay region:
        // In Bet Angel ladder, Lay volume is strictly above Back volume (rows <= firstBackRow).
        // Scan upwards from searchLimit to find lowest Lay row with volume.
        // Prevents scanner from ever dipping down into the Unmatched Bets / footer section!
        val searchLimit = if (firstBackRow != -1) firstBackRow else (detectedRowCnt - 2)
        var lastLayRow = -1
        for (r in searchLimit downTo 1) {
            val yMid = detectedLadderTop + r * detectedRowH + detectedRowH / 2
            if (yMid >= height) continue
            var darkCount = 0
            for (dy in -boxHalfH..boxHalfH step 2) {
                val py = yMid + dy
                if (py !in 0 until height) continue
                for (dx in -boxHalfW..boxHalfW step 4) {
                    val px = detectedColBCenterX + dx
                    if (px !in 0 until width) continue
                    val pixel = bitmap.getPixel(px, py)
                    val rC = (pixel shr 16) and 0xFF
                    val gC = (pixel shr 8) and 0xFF
                    val bC = pixel and 0xFF
                    if (rC + gC + bC < 220) {
                        darkCount++
                    }
                }
            }
            if (darkCount >= 10) {
                lastLayRow = r
                break
            }
        }

        // Exact Boundary Targeting:
        // - Column A: Vacant row where Column A ends from top (immediately above firstBackRow)
        // - Column B: Vacant row where Column B ends from bottom (immediately below lastLayRow)
        val backStrike = if (firstBackRow > 1) firstBackRow - 1 else (if (firstBackRow == 1) 0 else 1)
        val layStrike = if (lastLayRow != -1 && lastLayRow < detectedRowCnt - 1) lastLayRow + 1 else (if (firstBackRow != -1) firstBackRow else (detectedRowCnt - 2))

        activeBackStrikeRow = backStrike
        activeLayStrikeRow = layStrike
        activeBackStrikeY = (detectedLadderTop + backStrike * detectedRowH + detectedRowH / 2).toFloat()
        activeLayStrikeY = (detectedLadderTop + layStrike * detectedRowH + detectedRowH / 2).toFloat()
        activeColACenterX = detectedColACenterX.toFloat()
        activeColBCenterX = detectedColBCenterX.toFloat()
        activeRowH = detectedRowH
        activeColW = detectedPriceColW
        activeLadderTop = detectedLadderTop
        activeRowCount = detectedRowCnt

        Log.i(TAG, "Screen Scan: LadderTop=$detectedLadderTop, RowH=$detectedRowH, Rows=$detectedRowCnt | Col A Vacant Row $backStrike (Y=$activeBackStrikeY) | Col B Vacant Row $layStrike (Y=$activeLayStrikeY)")

        Handler(Looper.getMainLooper()).post {
            updateInterceptorPositions()
        }
    }
}
