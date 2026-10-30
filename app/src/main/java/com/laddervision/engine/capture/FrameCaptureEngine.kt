package com.laddervision.engine.capture

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.laddervision.engine.model.DetectionTelemetry
import com.laddervision.engine.model.LadderConfig
import com.laddervision.engine.touch.LadderTouchService
import com.laddervision.engine.vision.NativeVisionCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class FrameCaptureEngine(
    private val context: Context,
    private val visionCore: NativeVisionCore
) {
    companion object {
        private const val TAG = "FrameCaptureEngine"
        private const val TARGET_INTERVAL_MS = 20L // 50 Hz loop cadence
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var syncThread: HandlerThread? = null
    private var syncHandler: Handler? = null

    private val isRunning = AtomicBoolean(false)
    private var config: LadderConfig = LadderConfig.load(context)

    private val _telemetry = MutableStateFlow(DetectionTelemetry())
    val telemetry: StateFlow<DetectionTelemetry> = _telemetry.asStateFlow()

    private var framesCount: Long = 0L
    private var rejectedFramesCount: Long = 0L
    private var validFramesCount: Long = 0L
    private var lastGestureTimeMs: Long = 0L
    private var rollingCadenceMs: Float = 20.0f
    private var previousSpreadRow: Int = -1
    private var oneShotRequested = AtomicBoolean(false)

    fun updateConfig(newConfig: LadderConfig) {
        this.config = newConfig
        visionCore.syncConfig(newConfig)
    }

    fun triggerOneShotNow() {
        oneShotRequested.set(true)
    }

    fun startCapture(
        projection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int
    ) {
        if (isRunning.get()) return
        Log.i(TAG, "Starting FrameCaptureEngine at ${width}x${height} ($densityDpi dpi)")

        this.mediaProjection = projection
        visionCore.syncConfig(config)

        // Dual-buffer RGBA_8888 ImageReader
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        syncThread = HandlerThread("LadderSyncClock", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
            syncHandler = Handler(looper)
        }

        virtualDisplay = projection.createVirtualDisplay(
            "LadderCaptureDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            syncHandler
        )

        isRunning.set(true)
        framesCount = 0L
        rejectedFramesCount = 0L
        validFramesCount = 0L
        previousSpreadRow = -1

        syncHandler?.post(processCycle)
    }

    fun stopCapture() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping FrameCaptureEngine")

        syncHandler?.removeCallbacksAndMessages(null)
        syncThread?.quitSafely()
        syncThread = null
        syncHandler = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        _telemetry.value = _telemetry.value.copy(isRunning = false)
    }

    /**
     * Deterministic 20ms Frame Processing Loop (50Hz)
     * Meets Section 5 Architecture Specification:
     * - Sub-20ms frame cycle
     * - Zero-copy direct ByteBuffer C++ segmentation
     * - Dual simultaneous tap injection (< 1ms delta)
     */
    private val processCycle = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return

            val t0 = SystemClock.uptimeMillis()
            var detectedRow = -1
            var confidence = 0.0f
            var nativeUs = 0L

            val reader = imageReader
            val image: Image? = try {
                reader?.acquireLatestImage()
            } catch (e: Exception) {
                null
            }

            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val imgW = image.width
                    val imgH = image.height

                    // Zero-copy C++ NDK matrix segmentation
                    val det = visionCore.detectDetailed(buffer, rowStride, pixelStride, imgW, imgH)
                    detectedRow = det.spreadRow
                    confidence = det.confidence
                    nativeUs = det.processingTimeUs

                    framesCount++
                    if (detectedRow != -1) {
                        validFramesCount++
                    } else {
                        rejectedFramesCount++
                    }

                    // Modular Trading Flow & Strategy Dispatcher
                    if (shouldFireGesture(detectedRow)) {
                        fireDualSimultaneousTap(detectedRow)
                    }

                    previousSpreadRow = detectedRow
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing error", e)
                } finally {
                    image.close()
                }
            }

            val elapsed = SystemClock.uptimeMillis() - t0
            rollingCadenceMs = 0.9f * rollingCadenceMs + 0.1f * elapsed.toFloat()

            val hitRate = if (framesCount > 0) {
                (validFramesCount.toFloat() / framesCount.toFloat()) * 100.0f
            } else 100.0f

            _telemetry.value = DetectionTelemetry(
                loopCadenceMs = rollingCadenceMs,
                injectionDeltaMs = LadderTouchService.lastInjectionDeltaMs.value,
                nativeTimeUs = nativeUs,
                confidence = confidence,
                hitRatePercent = hitRate,
                spreadRow = detectedRow,
                framesProcessed = framesCount,
                rejectedFramesCount = rejectedFramesCount,
                gesturesDispatched = LadderTouchService.totalGesturesDispatched.value,
                isRunning = true,
                isSafetyArmed = !LadderTouchService.isDisarmed
            )

            // Strict deterministic 20ms sync clock
            val nextDelay = (TARGET_INTERVAL_MS - elapsed).coerceAtLeast(2L)
            syncHandler?.postDelayed(this, nextDelay)
        }
    }

    private fun shouldFireGesture(detectedRow: Int): Boolean {
        if (detectedRow == -1) return false
        if (!config.autoTradeEnabled) return false
        if (LadderTouchService.isDisarmed) return false

        // Manual one-shot trigger override
        if (oneShotRequested.compareAndSet(true, false)) {
            return true
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastGestureTimeMs < config.cooldownMs) {
            return false
        }

        return when (config.triggerStrategy) {
            LadderConfig.STRATEGY_SPREAD_SHIFT -> {
                // Fires when spread shifts to a new rung
                detectedRow != previousSpreadRow
            }
            LadderConfig.STRATEGY_GAP_EXPANSION -> {
                // In expansion mode, fires when spread gap condition met
                true
            }
            LadderConfig.STRATEGY_CONTINUOUS_PEG -> {
                // Refreshes quotes periodically
                true
            }
            LadderConfig.STRATEGY_MANUAL_ONE_SHOT -> false
            LadderConfig.STRATEGY_MONITOR_ONLY -> false
            else -> false
        }
    }

    private fun fireDualSimultaneousTap(spreadRow: Int) {
        lastGestureTimeMs = SystemClock.uptimeMillis()

        val lowerBlankY = config.ladderTop + ((spreadRow + config.lowerBlankOffsetRows) * config.rowHeight) + (config.rowHeight / 2)
        val targetUpperY = config.ladderTop + ((spreadRow - config.targetUpperOffsetRows) * config.rowHeight) + (config.rowHeight / 2)

        LadderTouchService.instance?.dispatchDualSimultaneousTap(
            config.backColX.toFloat(),
            lowerBlankY.toFloat(),
            config.layColX.toFloat(),
            targetUpperY.toFloat()
        )
    }
}
