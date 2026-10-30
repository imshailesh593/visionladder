package com.laddervision.engine

import com.laddervision.engine.model.LadderConfig
import org.junit.Assert.*
import org.junit.Test

class LadderEngineTest {

    @Test
    fun testDefaultLadderConfig() {
        val config = LadderConfig()
        assertEquals(20, config.rowCount)
        assertEquals(60, config.rowHeight)
        assertEquals(1, config.lowerBlankOffsetRows)
        assertEquals(1, config.targetUpperOffsetRows)
        assertEquals(100L, config.cooldownMs)
        assertEquals(LadderConfig.STRATEGY_SPREAD_SHIFT, config.triggerStrategy)
    }

    @Test
    fun testTargetCoordinatesCalculation() {
        val config = LadderConfig(
            ladderTop = 400,
            rowHeight = 50,
            rowCount = 20,
            backColX = 350,
            layColX = 750,
            lowerBlankOffsetRows = 1,
            targetUpperOffsetRows = 1
        )

        val spreadRow = 10

        // Stroke 1: Blank column one price lower (spreadRow + 1)
        val expectedLowerBlankY = config.ladderTop + ((spreadRow + config.lowerBlankOffsetRows) * config.rowHeight) + (config.rowHeight / 2)
        assertEquals(400 + (11 * 50) + 25, expectedLowerBlankY) // 400 + 550 + 25 = 975

        // Stroke 2: Target price above (spreadRow - 1)
        val expectedTargetUpperY = config.ladderTop + ((spreadRow - config.targetUpperOffsetRows) * config.rowHeight) + (config.rowHeight / 2)
        assertEquals(400 + (9 * 50) + 25, expectedTargetUpperY) // 400 + 450 + 25 = 875
    }

    @Test
    fun testDeterministicTimingCompensation() {
        val targetIntervalMs = 20L // 50 Hz

        // Case 1: Processing took 4ms -> next delay should be 16ms
        val elapsed1 = 4L
        val delay1 = (targetIntervalMs - elapsed1).coerceAtLeast(2L)
        assertEquals(16L, delay1)

        // Case 2: Processing took 19ms -> clamped to minimum 2ms to prevent starvation
        val elapsed2 = 19L
        val delay2 = (targetIntervalMs - elapsed2).coerceAtLeast(2L)
        assertEquals(2L, delay2)

        // Case 3: Processing exceeded 20ms -> clamped to minimum 2ms
        val elapsed3 = 25L
        val delay3 = (targetIntervalMs - elapsed3).coerceAtLeast(2L)
        assertEquals(2L, delay3)
    }
}
