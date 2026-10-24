package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidRefreshActivity
import org.junit.Assert.*
import org.junit.Test

class LiquidRefreshActivityTest {
    @Test fun `static probes neither reacquire nor repeatedly restore a window preference`() {
        val state = LiquidRefreshActivity()
        assertTrue(state.enterIdle())
        repeat(120) {
            assertTrue(state.beginProbe())
            assertEquals(LiquidRefreshActivity.Phase.PROBING, state.phase)
            assertFalse(state.beginProbe())
            assertFalse(state.enterIdle())
        }
    }

    @Test fun `real draws and changed probes acquire once then use the latest target`() {
        for (probe in listOf(false, true)) {
            val state = LiquidRefreshActivity()
            assertTrue(state.enterIdle())
            if (probe) assertTrue(state.beginProbe())
            assertTrue(state.wake())
            assertEquals(LiquidRefreshActivity.Phase.ACTIVE, state.phase)
            assertFalse(state.wake())
            assertFalse(state.beginProbe())
            assertTrue(state.enterIdle())
        }
    }

    @Test fun `lifecycle reset discards idle or pending probe without requesting a preference`() {
        for (probe in listOf(false, true)) {
            val state = LiquidRefreshActivity()
            state.enterIdle()
            if (probe) state.beginProbe()
            state.reset()
            assertEquals(LiquidRefreshActivity.Phase.ACTIVE, state.phase)
            assertFalse(state.wake())
            assertFalse(state.beginProbe())
        }
    }

    @Test fun `preference redraw is consumed once and explicit wake supersedes it`() {
        val state = LiquidRefreshActivity()
        state.enterIdle(); state.preferenceRestored(true)
        assertTrue(state.consumeRestoredDraw())
        assertFalse(state.consumeRestoredDraw())
        state.preferenceRestored(true); state.wake()
        assertFalse(state.consumeRestoredDraw())
        state.enterIdle(); state.preferenceRestored(false)
        assertFalse(state.consumeRestoredDraw())
        state.preferenceRestored(true); state.reset()
        assertFalse(state.consumeRestoredDraw())
    }
}
