package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidWindowPositionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidWindowPositionStateTest {
    @Test fun unnotifiedImePanAndReturnEachProduceOneRefresh() {
        val state = LiquidWindowPositionState()
        val matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertTrue(state.update(matrix))
        repeat(120) { assertFalse(state.update(matrix)) }
        matrix[5] = -80.25f
        assertTrue(state.update(matrix))
        repeat(120) { assertFalse(state.update(matrix)) }
        matrix[5] = 0f
        assertTrue(state.update(matrix))
        assertFalse(state.update(matrix))
    }

    @Test fun tinyWindowMotionAndScaleAreNotRoundedAway() {
        val state = LiquidWindowPositionState()
        val matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        state.update(matrix)
        matrix[5] = .25f
        assertTrue(state.update(matrix))
        matrix[4] = .99f
        assertTrue(state.update(matrix))
        assertFalse(state.update(matrix))
    }

    @Test fun invalidGeometryCannotPoisonTheLastKnownWindowPosition() {
        val state = LiquidWindowPositionState()
        val valid = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        state.update(valid)
        assertFalse(state.update(floatArrayOf(1f)))
        val invalid = valid.clone().apply { this[5] = Float.NaN }
        assertFalse(state.update(invalid))
        assertFalse(state.update(valid))
    }
}
