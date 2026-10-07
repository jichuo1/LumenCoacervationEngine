package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.material.LumenSampleCadence
import org.junit.Assert.*
import org.junit.Test

class LumenSampleCadenceTest {
    @Test fun fastSurfaceDoesNotAccelerateTheSlowSurface() {
        val fast = LumenSampleCadence()
        val slow = LumenSampleCadence()
        fast.sampled(0L); slow.sampled(0L)
        for (ms in 1L..999L) {
            fast.invalidate(); slow.invalidate()
            assertEquals(0L, fast.remainingMs(ms * 1_000_000L, 0L))
            assertEquals(1_000L - ms, slow.remainingMs(ms * 1_000_000L, 1_000L))
            fast.sampled(ms * 1_000_000L)
        }
        assertEquals(0L, slow.remainingMs(1_000_000_000L, 1_000L))
    }

    @Test fun finalStaleFrameStaysPendingAfterMotionStops() {
        val cadence = LumenSampleCadence()
        cadence.sampled(0L); cadence.invalidate()
        assertEquals(900L, cadence.remainingMs(100_000_000L, 1_000L))
        assertTrue(cadence.pending)
        assertEquals(0L, cadence.remainingMs(1_000_000_000L, 1_000L))
        cadence.sampled(1_000_000_000L)
        assertFalse(cadence.pending)
        assertEquals(Long.MAX_VALUE, cadence.remainingMs(2_000_000_000L, 1_000L))
    }

    @Test fun newAndReleasedBuffersDoNotWaitForTheOldInterval() {
        val cadence = LumenSampleCadence()
        assertEquals(0L, cadence.remainingMs(0L, 1_000L))
        cadence.sampled(0L); cadence.invalidate()
        assertEquals(1_000L, cadence.remainingMs(0L, 1_000L))
        cadence.reset()
        assertEquals(0L, cadence.remainingMs(1L, 1_000L))
    }

    @Test fun subMillisecondRemainderRoundsUpAndNanoTimeWrapIsSafe() {
        val cadence = LumenSampleCadence()
        cadence.sampled(Long.MAX_VALUE - 999_999L); cadence.invalidate()
        assertEquals(1L, cadence.remainingMs(Long.MIN_VALUE, 2L))
        assertEquals(0L, cadence.remainingMs(Long.MIN_VALUE + 1_000_000L, 2L))
    }

    @Test fun hiddenOrInvalidGeometryDoesNotKeepWakingTheWindow() {
        val cadence = LumenSampleCadence()
        cadence.discardPending()
        assertEquals(Long.MAX_VALUE, cadence.remainingMs(0L, 0L))
        cadence.invalidate()
        assertEquals(0L, cadence.remainingMs(0L, 0L))
    }
}
