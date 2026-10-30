package com.laddervision.engine.model

data class DetectionTelemetry(
    val loopCadenceMs: Float = 0.0f,
    val injectionDeltaMs: Float = 0.0f,
    val nativeTimeUs: Long = 0L,
    val confidence: Float = 0.0f,
    val hitRatePercent: Float = 100.0f,
    val spreadRow: Int = -1,
    val framesProcessed: Long = 0L,
    val rejectedFramesCount: Long = 0L,
    val gesturesDispatched: Long = 0L,
    val isRunning: Boolean = false,
    val isSafetyArmed: Boolean = true,
    val lastError: String? = null
)
