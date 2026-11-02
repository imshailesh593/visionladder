package com.laddervision.engine.model

import android.content.Context
import android.content.SharedPreferences

data class LadderConfig(
    var ladderLeft: Int = 298,
    var ladderTop: Int = 800,
    var ladderWidth: Int = 594,
    var ladderHeight: Int = 1300,
    var rowHeight: Int = 76,
    var rowCount: Int = 17,
    var backColX: Int = 397,
    var layColX: Int = 793,
    var sampleBoxWidth: Int = 80,
    var sampleBoxHeight: Int = 35,
    var luminanceThreshold: Float = 55.0f,
    var detectionMode: Int = 0, // 0: Luminance, 1: Color Chroma, 2: Combined
    var minConfidence: Float = 0.5f,
    var autoTradeEnabled: Boolean = true,
    var cooldownMs: Long = 150L,
    var targetUpperOffsetRows: Int = 1,
    var lowerBlankOffsetRows: Int = 1,
    var triggerStrategy: Int = 0, // 0: SPREAD_SHIFT, 1: GAP_EXPANSION, 2: CONTINUOUS_PEG, 3: MANUAL_ONE_SHOT, 4: MONITOR_ONLY
    var requiredGapRungs: Int = 1,
    var presetName: String = "Bet Angel v1.63 (1080x2400)"
) {
    companion object {
        const val STRATEGY_SPREAD_SHIFT = 0
        const val STRATEGY_GAP_EXPANSION = 1
        const val STRATEGY_CONTINUOUS_PEG = 2
        const val STRATEGY_MANUAL_ONE_SHOT = 3
        const val STRATEGY_MONITOR_ONLY = 4

        private const val PREFS_NAME = "ladder_vision_config"

        fun load(context: Context): LadderConfig {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return LadderConfig(
                ladderLeft = p.getInt("ladderLeft", 298),
                ladderTop = p.getInt("ladderTop", 800),
                ladderWidth = p.getInt("ladderWidth", 594),
                ladderHeight = p.getInt("ladderHeight", 1300),
                rowHeight = p.getInt("rowHeight", 76),
                rowCount = p.getInt("rowCount", 17),
                backColX = p.getInt("backColX", 397),
                layColX = p.getInt("layColX", 793),
                sampleBoxWidth = p.getInt("sampleBoxWidth", 40),
                sampleBoxHeight = p.getInt("sampleBoxHeight", 20),
                luminanceThreshold = p.getFloat("luminanceThreshold", 55.0f),
                detectionMode = p.getInt("detectionMode", 0),
                minConfidence = p.getFloat("minConfidence", 0.5f),
                autoTradeEnabled = p.getBoolean("autoTradeEnabled", false),
                cooldownMs = p.getLong("cooldownMs", 100L),
                targetUpperOffsetRows = p.getInt("targetUpperOffsetRows", 1),
                lowerBlankOffsetRows = p.getInt("lowerBlankOffsetRows", 1),
                triggerStrategy = p.getInt("triggerStrategy", STRATEGY_SPREAD_SHIFT),
                requiredGapRungs = p.getInt("requiredGapRungs", 1),
                presetName = p.getString("presetName", "Default") ?: "Default"
            )
        }
    }

    fun save(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        p.edit()
            .putInt("ladderLeft", ladderLeft)
            .putInt("ladderTop", ladderTop)
            .putInt("ladderWidth", ladderWidth)
            .putInt("ladderHeight", ladderHeight)
            .putInt("rowHeight", rowHeight)
            .putInt("rowCount", rowCount)
            .putInt("backColX", backColX)
            .putInt("layColX", layColX)
            .putInt("sampleBoxWidth", sampleBoxWidth)
            .putInt("sampleBoxHeight", sampleBoxHeight)
            .putFloat("luminanceThreshold", luminanceThreshold)
            .putInt("detectionMode", detectionMode)
            .putFloat("minConfidence", minConfidence)
            .putBoolean("autoTradeEnabled", autoTradeEnabled)
            .putLong("cooldownMs", cooldownMs)
            .putInt("targetUpperOffsetRows", targetUpperOffsetRows)
            .putInt("lowerBlankOffsetRows", lowerBlankOffsetRows)
            .putInt("triggerStrategy", triggerStrategy)
            .putInt("requiredGapRungs", requiredGapRungs)
            .putString("presetName", presetName)
            .apply()
    }
}
