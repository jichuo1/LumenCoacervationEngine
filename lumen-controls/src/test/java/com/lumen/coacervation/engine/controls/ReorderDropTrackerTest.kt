package com.lumen.coacervation.engine.controls

import org.junit.Assert.*
import org.junit.Test

class ReorderDropTrackerTest {
    @Test fun repeatedCleanupOnlyCompletesOneDrop() {
        val drops = ReorderDropTracker<Any>()
        val holder = Any()
        drops.begin(holder)
        assertTrue(drops.finish(holder, 3))
        assertFalse(drops.finish(holder, 3))
        assertEquals(3, drops.poll())
        assertNull(drops.poll())
    }

    @Test fun oldRecoveryCannotUnlockNewDragOrSubmitDuringIt() {
        val drops = ReorderDropTracker<Any>()
        val old = Any(); val current = Any()
        drops.begin(old)
        drops.begin(current)
        drops.finish(old, 1)
        assertTrue(drops.isDragging)
        assertNull(drops.poll())
        drops.finish(current, 4)
        assertFalse(drops.isDragging)
        assertEquals(1, drops.poll())
        assertEquals(4, drops.poll())
        assertNull(drops.poll())
    }

    @Test fun newDragBeforePostedDeliveryKeepsCompletedDropPending() {
        val drops = ReorderDropTracker<Any>()
        val first = Any(); val second = Any()
        drops.begin(first); drops.finish(first, 2)
        drops.begin(second)
        assertNull(drops.poll())
        drops.finish(second, 5)
        assertEquals(2, drops.poll())
        assertEquals(5, drops.poll())
    }

    @Test fun regrabSameHolderHasOneFinalCompletion() {
        val drops = ReorderDropTracker<Any>()
        val holder = Any()
        drops.begin(holder); drops.begin(holder)
        assertTrue(drops.finish(holder, -1))
        assertEquals(-1, drops.poll())
        assertFalse(drops.finish(holder, 0))
        assertNull(drops.poll())
    }

    @Test fun cleanupOfUntrackedHolderDoesNotSubmit() {
        val drops = ReorderDropTracker<Any>()
        assertFalse(drops.finish(Any(), 0))
        assertFalse(drops.isDragging)
        assertNull(drops.poll())
    }

    @Test fun deliveryCanReenterCleanupWithoutDuplicatingTheClaimedDrop() {
        val drops = ReorderDropTracker<Any>()
        val holder = Any()
        drops.begin(holder); drops.finish(holder, 7)
        assertEquals(7, drops.poll())
        // ItemTouchHelper.detach clears a recovery still present in its own list.
        assertFalse(drops.finish(holder, 7))
        assertNull(drops.poll())
    }
}
