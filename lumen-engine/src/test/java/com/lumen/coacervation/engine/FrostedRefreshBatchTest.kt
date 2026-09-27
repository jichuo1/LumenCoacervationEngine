package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.material.FrostedRefreshBatch
import org.junit.Assert.*
import org.junit.Test

class FrostedRefreshBatchTest {
    @Test fun aBurstOfAnimationAndScrollNotificationsScansOnlyOnceBeforeDraw() {
        val batch = FrostedRefreshBatch()
        repeat(120) {
            repeat(12) { assertFalse(batch.request()) }
            assertTrue(batch.beforeDraw())
            assertFalse(batch.beforeDraw())
            batch.drawn()
        }
    }

    @Test fun laterPreDrawTransformsMustRefreshInTheSameFrame() {
        val batch = FrostedRefreshBatch()
        assertFalse(batch.request())
        assertTrue(batch.beforeDraw())
        // A later listener changes translation after the first sweep: never retain stale sampling.
        assertTrue(batch.request())
        assertTrue(batch.request())
        batch.drawn()
        assertFalse(batch.beforeDraw())
    }

    @Test fun canceledTraversalKeepsLateChangesSafeUntilADrawActuallyCompletes() {
        val batch = FrostedRefreshBatch()
        assertFalse(batch.beforeDraw())
        // No draw callback: a canceled traversal cannot silently defer subsequent mutations.
        assertTrue(batch.request())
        assertFalse(batch.beforeDraw())
        assertTrue(batch.request())
        batch.drawn()
        assertFalse(batch.request())
        assertTrue(batch.beforeDraw())
    }

    @Test fun idleWindowsDoNotScanAndWindowsDoNotConsumeEachOthersRequests() {
        val main = FrostedRefreshBatch()
        val dialog = FrostedRefreshBatch()
        assertFalse(main.request())
        assertFalse(dialog.beforeDraw())
        assertTrue(main.beforeDraw())
        main.drawn()
        dialog.drawn()
        repeat(10) {
            assertFalse(main.beforeDraw())
            main.drawn()
        }
    }

    @Test fun stopAndObserverReplacementDiscardPendingAndLatePhaseState() {
        val batch = FrostedRefreshBatch()
        batch.request()
        batch.clear()
        assertFalse(batch.beforeDraw())
        batch.clear()
        assertFalse(batch.request())
        assertTrue(batch.beforeDraw())
    }
}
