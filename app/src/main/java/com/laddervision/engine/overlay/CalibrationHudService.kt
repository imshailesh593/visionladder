package com.laddervision.engine.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.laddervision.engine.model.DetectionTelemetry
import com.laddervision.engine.model.LadderConfig
import com.laddervision.engine.service.LadderAutomationService
import com.laddervision.engine.touch.LadderTouchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class CalibrationHudService : Service() {

    companion object {
        var instance: CalibrationHudService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var overlayCanvasView: OverlayCanvasView
    private lateinit var controlToolbarView: View

    private lateinit var config: LadderConfig
    private var isTouchThrough: Boolean = false
    private var isMinimized: Boolean = false
    private var currentTelemetry = DetectionTelemetry()

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = LadderConfig.load(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val channelId = "ladder_hud_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Ladder HUD", android.app.NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vision HUD Active")
            .setContentText("Companion Scalping & Calibration HUD running")
            .setSmallIcon(android.R.drawable.presence_online)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1002, notification)

        initCanvasOverlay()
        initControlToolbar()

        serviceScope.launch {
            LadderAutomationService.engineTelemetry.collect { telemetry ->
                currentTelemetry = telemetry
                overlayCanvasView.invalidate()
                updateToolbarTelemetry(telemetry)
            }
        }
    }

    private fun initCanvasOverlay() {
        overlayCanvasView = OverlayCanvasView(this)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (isTouchThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(overlayCanvasView, params)
    }

    @SuppressLint("SetTextI18n")
    private fun initControlToolbar() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE0F172A"))
            setPadding(24, 20, 24, 20)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "VISION HUD"
            setTextColor(Color.WHITE)
            textSize = 12f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val btnMinimize = Button(this).apply {
            text = "_"
            textSize = 10f
            setOnClickListener {
                toggleMinimize()
            }
        }

        headerRow.addView(title)
        headerRow.addView(btnMinimize)

        val telemetryText = TextView(this).apply {
            id = View.generateViewId()
            text = "Cadence: -- ms | Native: -- µs | Spread: --"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 11f
        }

        val buttonRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 6)
        }

        val btnTouchThrough = Button(this).apply {
            text = if (isTouchThrough) "MODE: PASS" else "MODE: EDIT"
            textSize = 10f
            setOnClickListener {
                toggleTouchThrough()
                text = if (isTouchThrough) "MODE: PASS" else "MODE: EDIT"
            }
        }

        val btnAutoTrade = Button(this).apply {
            text = if (config.autoTradeEnabled) "AUTO: ON" else "AUTO: OFF"
            textSize = 10f
            setTextColor(if (config.autoTradeEnabled) Color.GREEN else Color.LTGRAY)
            setOnClickListener {
                config.autoTradeEnabled = !config.autoTradeEnabled
                config.save(this@CalibrationHudService)
                text = if (config.autoTradeEnabled) "AUTO: ON" else "AUTO: OFF"
                setTextColor(if (config.autoTradeEnabled) Color.GREEN else Color.LTGRAY)
                LadderAutomationService.instance?.updateConfig(config)
            }
        }

        val btnStrategy = Button(this).apply {
            text = strategyLabel(config.triggerStrategy)
            textSize = 10f
            setOnClickListener {
                config.triggerStrategy = (config.triggerStrategy + 1) % 5
                config.save(this@CalibrationHudService)
                text = strategyLabel(config.triggerStrategy)
                LadderAutomationService.instance?.updateConfig(config)
            }
        }

        buttonRow1.addView(btnTouchThrough)
        buttonRow1.addView(btnAutoTrade)
        buttonRow1.addView(btnStrategy)

        val buttonRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnFireOneShot = Button(this).apply {
            text = "ONE-SHOT"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                LadderTouchService.instance?.rearm()
                LadderAutomationService.instance?.triggerOneShot()
            }
        }

        val btnKillSwitch = Button(this).apply {
            text = "KILL (<5ms)"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            setOnClickListener {
                LadderAutomationService.instance?.emergencyKill()
                stopSelf()
            }
        }

        buttonRow2.addView(btnFireOneShot)
        buttonRow2.addView(btnKillSwitch)

        val buttonRow3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }

        val btnScalpA = Button(this).apply {
            text = "⚡ SCALP A"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            setOnClickListener {
                LadderTouchService.instance?.rearm()
                val targetY = config.ladderTop + (currentTelemetry.spreadRow * config.rowHeight) + (config.rowHeight / 2)
                LadderTouchService.instance?.dispatchSingleTap(config.backColX.toFloat(), targetY.toFloat(), durationMs = 30L)
            }
        }

        val btnScalpB = Button(this).apply {
            text = "⚡ SCALP B"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#DB2777"))
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            setOnClickListener {
                LadderTouchService.instance?.rearm()
                val targetY = config.ladderTop + (currentTelemetry.spreadRow * config.rowHeight) + (config.rowHeight / 2)
                LadderTouchService.instance?.dispatchSingleTap(config.layColX.toFloat(), targetY.toFloat(), durationMs = 30L)
            }
        }

        buttonRow3.addView(btnScalpA)
        buttonRow3.addView(btnScalpB)

        container.addView(headerRow)
        container.addView(telemetryText)
        container.addView(buttonRow1)
        container.addView(buttonRow2)
        container.addView(buttonRow3)

        controlToolbarView = container

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 100
        }

        // Draggable floating toolbar
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        controlToolbarView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(controlToolbarView, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(controlToolbarView, params)
    }

    private fun strategyLabel(strategy: Int): String {
        return when (strategy) {
            LadderConfig.STRATEGY_SPREAD_SHIFT -> "SHIFT"
            LadderConfig.STRATEGY_GAP_EXPANSION -> "GAP"
            LadderConfig.STRATEGY_CONTINUOUS_PEG -> "PEG"
            LadderConfig.STRATEGY_MANUAL_ONE_SHOT -> "MANUAL"
            LadderConfig.STRATEGY_MONITOR_ONLY -> "MONITOR"
            else -> "FLOW"
        }
    }

    private fun updateToolbarTelemetry(telemetry: DetectionTelemetry) {
        val tv = controlToolbarView.findViewById<TextView>(controlToolbarView.id)
            ?: (controlToolbarView as? LinearLayout)?.getChildAt(1) as? TextView
        tv?.text = String.format(
            "Loop: %.1fms | NDK: %dµs | Spread: #%d | Hit: %.1f%%",
            telemetry.loopCadenceMs,
            telemetry.nativeTimeUs,
            telemetry.spreadRow,
            telemetry.hitRatePercent
        )
    }

    private fun toggleMinimize() {
        isMinimized = !isMinimized
        val b1 = (controlToolbarView as? LinearLayout)?.getChildAt(2)
        val b2 = (controlToolbarView as? LinearLayout)?.getChildAt(3)
        b1?.visibility = if (isMinimized) View.GONE else View.VISIBLE
        b2?.visibility = if (isMinimized) View.GONE else View.VISIBLE
    }

    private fun toggleTouchThrough() {
        isTouchThrough = !isTouchThrough
        val params = overlayCanvasView.layoutParams as WindowManager.LayoutParams
        if (isTouchThrough) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        windowManager.updateViewLayout(overlayCanvasView, params)
    }

    /**
     * Overlay Canvas View: Renders visual ladder grid, handles, markers, and touch coordinates.
     * When not in pass-through mode, allows direct touch alignment of the bounding box and columns.
     */
    inner class OverlayCanvasView(context: Context) : View(context) {
        private val ladderBorderPaint = Paint().apply {
            color = Color.parseColor("#773B82F6")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        private val handlePaint = Paint().apply {
            color = Color.parseColor("#3B82F6")
            style = Paint.Style.FILL
        }

        private val rowDividerPaint = Paint().apply {
            color = Color.parseColor("#25FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        private val backColPaint = Paint().apply {
            color = Color.parseColor("#9960A5FA")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }

        private val layColPaint = Paint().apply {
            color = Color.parseColor("#99F472B6")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }

        private val spreadHighlightPaint = Paint().apply {
            color = Color.parseColor("#55FBBF24")
            style = Paint.Style.FILL
        }

        private val tapTargetPaint = Paint().apply {
            color = Color.parseColor("#EF4444")
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 4f
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 26f
            isAntiAlias = true
        }

        private var draggingHandle = 0 // 0: None, 1: TopLeft, 2: BottomRight, 3: BackCol, 4: LayCol

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isTouchThrough) return false

            val x = event.x
            val y = event.y

            val left = config.ladderLeft.toFloat()
            val top = config.ladderTop.toFloat()
            val right = (config.ladderLeft + config.ladderWidth).toFloat()
            val bottom = (config.ladderTop + (config.rowCount * config.rowHeight)).toFloat()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    draggingHandle = when {
                        abs(x - left) < 40 && abs(y - top) < 40 -> 1
                        abs(x - right) < 40 && abs(y - bottom) < 40 -> 2
                        abs(x - config.backColX) < 30 && y >= top && y <= bottom -> 3
                        abs(x - config.layColX) < 30 && y >= top && y <= bottom -> 4
                        else -> 0
                    }
                    return draggingHandle != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    when (draggingHandle) {
                        1 -> { // Top Left
                            config.ladderLeft = x.toInt().coerceAtLeast(0)
                            config.ladderTop = y.toInt().coerceAtLeast(0)
                        }
                        2 -> { // Bottom Right
                            config.ladderWidth = (x - config.ladderLeft).toInt().coerceAtLeast(200)
                            val totalH = (y - config.ladderTop).toInt().coerceAtLeast(200)
                            config.rowHeight = (totalH / config.rowCount).coerceIn(20, 150)
                        }
                        3 -> { // Back Col X
                            config.backColX = x.toInt().coerceIn(config.ladderLeft, config.ladderLeft + config.ladderWidth)
                        }
                        4 -> { // Lay Col X
                            config.layColX = x.toInt().coerceIn(config.ladderLeft, config.ladderLeft + config.ladderWidth)
                        }
                    }
                    if (draggingHandle != 0) {
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (draggingHandle != 0) {
                        draggingHandle = 0
                        config.save(this@CalibrationHudService)
                        LadderAutomationService.instance?.updateConfig(config)
                        return true
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val left = config.ladderLeft.toFloat()
            val top = config.ladderTop.toFloat()
            val right = (config.ladderLeft + config.ladderWidth).toFloat()
            val bottom = (config.ladderTop + (config.rowCount * config.rowHeight)).toFloat()

            // Ladder Bounding Box
            canvas.drawRect(left, top, right, bottom, ladderBorderPaint)

            if (!isTouchThrough) {
                // Draw Draggable Corner Handles
                canvas.drawCircle(left, top, 16f, handlePaint)
                canvas.drawCircle(right, bottom, 16f, handlePaint)
            }

            // Draw Column Guide Lines
            canvas.drawLine(config.backColX.toFloat(), top, config.backColX.toFloat(), bottom, backColPaint)
            canvas.drawLine(config.layColX.toFloat(), top, config.layColX.toFloat(), bottom, layColPaint)
            canvas.drawText("BACK (BUY)", config.backColX.toFloat() - 40, top - 10, textPaint)
            canvas.drawText("LAY (SELL)", config.layColX.toFloat() - 40, top - 10, textPaint)

            // Draw Row Dividers & Highlight Active Spread Row
            val spreadRow = currentTelemetry.spreadRow
            for (r in 0 until config.rowCount) {
                val rowY = top + (r * config.rowHeight)
                canvas.drawLine(left, rowY, right, rowY, rowDividerPaint)

                if (r == spreadRow) {
                    canvas.drawRect(left, rowY, right, rowY + config.rowHeight, spreadHighlightPaint)
                    canvas.drawText("MEDIAN / SPREAD #$r", left + 10, rowY + (config.rowHeight * 0.7f), textPaint)

                    // Target rungs on either side of median:
                    // Stroke 1: Back column above 857 (at r - 1 or r)
                    val backStrikeY = top + ((r - 1).coerceAtLeast(0) * config.rowHeight) + (config.rowHeight / 2)
                    canvas.drawCircle(config.backColX.toFloat(), backStrikeY, 15f, tapTargetPaint)
                    canvas.drawText("STRIKE BACK (>857)", config.backColX.toFloat() + 20, backStrikeY + 8, textPaint)

                    // Stroke 2: Lay column below 755 (at r + 1 or r)
                    val layStrikeY = top + ((r + 1).coerceAtMost(config.rowCount - 1) * config.rowHeight) + (config.rowHeight / 2)
                    canvas.drawCircle(config.layColX.toFloat(), layStrikeY, 15f, tapTargetPaint)
                    canvas.drawText("STRIKE LAY (<755)", config.layColX.toFloat() + 20, layStrikeY + 8, textPaint)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (::overlayCanvasView.isInitialized) {
            windowManager.removeView(overlayCanvasView)
        }
        if (::controlToolbarView.isInitialized) {
            windowManager.removeView(controlToolbarView)
        }
        if (instance == this) {
            instance = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
