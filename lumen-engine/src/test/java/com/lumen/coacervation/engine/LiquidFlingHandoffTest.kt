package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidFlingHandoff
import org.junit.Assert.assertEquals
import org.junit.Test

class LiquidFlingHandoffTest {
    @Test fun remainingVelocityDecaysBeforeTheEdge() {
        val handoff = LiquidFlingHandoff()
        handoff.start(8000f)
        handoff.observe(120, 1_000_000_000L)
        handoff.observe(60, 1_016_000_000L)
        assertEquals(3750f, handoff.velocity(), .001f)
        handoff.observe(16, 1_032_000_000L)
        assertEquals(1000f, handoff.velocity(), .001f)
    }

    @Test fun topAndBottomUseTheSameSpeedMagnitude() {
        val handoff = LiquidFlingHandoff()
        handoff.start(-8000f)
        handoff.observe(-80, 1_000_000_000L)
        handoff.observe(-20, 1_010_000_000L)
        assertEquals(2000f, handoff.velocity(), .001f)
    }

    @Test fun immediateCollisionKeepsTheLaunchVelocity() {
        val handoff = LiquidFlingHandoff()
        handoff.start(-8000f)
        handoff.observe(-80, 1_000_000_000L)
        assertEquals(8000f, handoff.velocity(), 0f)
    }

    @Test fun duplicateAndSubMillisecondCallbacksCannotCreateASpeedSpike() {
        val handoff = LiquidFlingHandoff()
        handoff.start(8000f)
        handoff.observe(80, 1_000_000_000L)
        handoff.observe(20, 1_010_000_000L)
        handoff.observe(800, 1_010_000_000L)
        handoff.observe(800, 1_010_100_000L)
        assertEquals(2000f, handoff.velocity(), 0f)
    }

    @Test fun estimatedVelocityCannotExceedLaunchVelocity() {
        val handoff = LiquidFlingHandoff()
        handoff.start(1000f)
        handoff.observe(Int.MAX_VALUE, 1_000_000_000L)
        handoff.observe(Int.MAX_VALUE, 1_002_000_000L)
        assertEquals(1000f, handoff.velocity(), 0f)
    }

    @Test fun takeoverAndNewFlingDiscardOldFrameHistory() {
        val handoff = LiquidFlingHandoff()
        handoff.start(8000f)
        handoff.observe(80, 1_000_000_000L)
        handoff.observe(20, 1_010_000_000L)
        handoff.clear()
        assertEquals(0f, handoff.velocity(), 0f)
        handoff.start(-6000f)
        handoff.observe(-20, 2_000_000_000L)
        assertEquals(6000f, handoff.velocity(), 0f)
    }

    @Test fun wrongDirectionAndInvalidVelocityAreIgnored() {
        val handoff = LiquidFlingHandoff()
        handoff.start(8000f)
        handoff.observe(-80, 1_000_000_000L)
        handoff.observe(-20, 1_010_000_000L)
        assertEquals(8000f, handoff.velocity(), 0f)
        handoff.start(Float.NaN)
        assertEquals(0f, handoff.velocity(), 0f)
    }
}
