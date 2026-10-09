package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.geometry.RecordingScrollNotifications
import com.lumen.coacervation.engine.geometry.ScrollSamplingGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RecordingScrollNotificationsTest {
    @Test fun nestedRecordingsReplayAtOuterExitAndDeduplicateHostIdentity() {
        val queue = RecordingScrollNotifications<Any>()
        val first = Any(); val second = Any()
        val notified = ArrayList<Any>()
        queue.beginRecord()
        queue.defer(first)
        queue.beginRecord()
        queue.defer(first); queue.defer(second)
        queue.finishRecord(true, notified::add)
        assertEquals(1, queue.depth)
        assertEquals(0, notified.size)
        assertEquals(2, queue.pendingCount)
        queue.finishRecord(true, notified::add)
        assertEquals(0, queue.depth)
        assertEquals(0, queue.pendingCount)
        assertEquals(2, notified.size)
        assertSame(first, notified[0]); assertSame(second, notified[1])
    }

    @Test fun equalLookingHostsAreDistinctAndOutsideRecordingCannotRetainHosts() {
        data class Host(val id: Int)
        val queue = RecordingScrollNotifications<Host>()
        val first = Host(1); val second = Host(1)
        val notified = ArrayList<Host>()
        queue.defer(first)
        assertEquals(0, queue.pendingCount)
        queue.beginRecord(); queue.defer(first); queue.defer(second)
        queue.finishRecord(true, notified::add)
        assertEquals(2, notified.size)
        assertSame(first, notified[0]); assertSame(second, notified[1])
    }

    @Test fun stoppedSessionAndCloseDropHostsWithoutBreakingFinallyDepthBalance() {
        val queue = RecordingScrollNotifications<Any>()
        var notifications = 0
        queue.beginRecord(); queue.beginRecord(); queue.defer(Any())
        queue.clear()
        assertEquals(2, queue.depth)
        queue.finishRecord(false) { notifications++ }
        queue.defer(Any())
        queue.finishRecord(false) { notifications++ }
        assertEquals(0, notifications)
        assertEquals(0, queue.pendingCount)
        assertEquals(0, queue.depth)
    }

    @Test fun exceptionalRecordingAndNotificationAlwaysReleasePendingHosts() {
        val queue = RecordingScrollNotifications<Any>()
        var notifications = 0
        val sourceFailure = IllegalArgumentException("source")
        try {
            queue.beginRecord()
            try { queue.defer(Any()); throw sourceFailure }
            finally { queue.finishRecord(true) { notifications++ } }
        } catch (failure: IllegalArgumentException) { assertSame(sourceFailure, failure) }
        assertEquals(1, notifications)
        assertEquals(0, queue.depth); assertEquals(0, queue.pendingCount)
        queue.beginRecord(); queue.defer(Any())
        try {
            queue.finishRecord(true) { throw IllegalStateException("notification") }
            fail("notification failure must be observable to its caller")
        } catch (_: IllegalStateException) { }
        assertEquals(0, queue.depth); assertEquals(0, queue.pendingCount)
    }

    @Test fun deferredScrollWithUnchangedRelativeMapDoesNotInvalidateTarget() {
        val queue = RecordingScrollNotifications<Any>()
        val footprint = ScrollSamplingGeometry()
        val relative = floatArrayOf(1f, 0f, -20f, 0f, 1f, -40f, 0f, 0f, 1f)
        footprint.record(relative)
        var invalidations = 0
        queue.beginRecord(); queue.beginRecord(); queue.defer(Any())
        queue.finishRecord(true) { if (footprint.needsRefresh(relative)) invalidations++ }
        assertEquals(0, invalidations)
        queue.finishRecord(true) { if (footprint.needsRefresh(relative)) invalidations++ }
        assertEquals(0, invalidations)
        assertEquals(0, queue.pendingCount)
    }
}
