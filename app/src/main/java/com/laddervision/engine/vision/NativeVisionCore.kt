package com.laddervision.engine.vision

import com.laddervision.engine.model.LadderConfig
import java.nio.ByteBuffer

class NativeVisionCore {

    companion object {
        init {
            try {
                System.loadLibrary("laddervision")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }

    data class DetectionResult(
        val spreadRow: Int,
        val confidence: Float,
        val processingTimeUs: Long
    )

    data class BetAngelStrikePlan(
        val bestBackRow: Int,
        val bestLayRow: Int,
        val backStrikeRow: Int, // Above 857 (in unpopulated blue Back cell)
        val layStrikeRow: Int,  // Below 755 (in unpopulated pink Lay cell)
        val confidencePercent: Int,
        val timeUs: Int
    )

    external fun updateCalibration(
        ladderLeft: Int,
        ladderTop: Int,
        ladderWidth: Int,
        ladderHeight: Int,
        rowHeight: Int,
        rowCount: Int,
        backColX: Int,
        layColX: Int,
        sampleBoxWidth: Int,
        sampleBoxHeight: Int,
        luminanceThreshold: Float,
        detectionMode: Int,
        minConfidence: Float
    )

    external fun detectSpreadBoundary(
        byteBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): Int

    external fun detectSpreadBoundaryDetailed(
        byteBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): LongArray?

    external fun detectBetAngelMedianAndStrikes(
        byteBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): IntArray?

    external fun autoDetectBetAngelLadder(
        byteBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): IntArray?

    external fun getLuminanceProfile(
        byteBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): FloatArray?

    external fun runNativeBenchmark(
        byteBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        iterations: Int
    ): Double

    fun syncConfig(config: LadderConfig) {
        updateCalibration(
            config.ladderLeft,
            config.ladderTop,
            config.ladderWidth,
            config.ladderHeight,
            config.rowHeight,
            config.rowCount,
            config.backColX,
            config.layColX,
            config.sampleBoxWidth,
            config.sampleBoxHeight,
            config.luminanceThreshold,
            config.detectionMode,
            config.minConfidence
        )
    }

    fun detectDetailed(
        byteBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): DetectionResult {
        val raw = detectSpreadBoundaryDetailed(byteBuffer, rowStride, pixelStride, width, height)
        return if (raw != null && raw.size >= 3) {
            DetectionResult(
                spreadRow = raw[0].toInt(),
                confidence = raw[1] / 1000.0f,
                processingTimeUs = raw[2]
            )
        } else {
            DetectionResult(spreadRow = -1, confidence = 0.0f, processingTimeUs = 0L)
        }
    }

    fun getBetAngelPlan(
        byteBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): BetAngelStrikePlan? {
        val raw = detectBetAngelMedianAndStrikes(byteBuffer, rowStride, pixelStride, width, height)
        return if (raw != null && raw.size >= 6) {
            BetAngelStrikePlan(
                bestBackRow = raw[0],
                bestLayRow = raw[1],
                backStrikeRow = raw[2],
                layStrikeRow = raw[3],
                confidencePercent = raw[4],
                timeUs = raw[5]
            )
        } else null
    }
}
