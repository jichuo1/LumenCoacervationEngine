package com.lumen.coacervation.engine.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatestPreparationTest {
    private class Harness {
        val worker = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val acquired = mutableListOf<Int>()
        val delivered = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        var rejectWorker = false
        var rejectMain = false
        val queue = LatestPreparation(
            execute = { if (rejectWorker) throw RejectedExecutionException() else worker.add(it) },
            post = { if (rejectMain) false else { main.add(it); true } },
            unavailable = { -1 }, discard = { discarded.add(it) }
        )
        fun submit(value: Int) = queue.submit({ acquired.add(value); value }) { delivered.add(it) }
    }

    @Test fun replacingTenThousandPendingSourcesAcquiresOnlyTheLatestAndNeverCallsBackInline() {
        val h = Harness()
        repeat(10_000) { h.submit(it) }
        assertEquals(1, h.worker.size)
        assertTrue(h.acquired.isEmpty())
        h.worker.remove().run()
        assertEquals(listOf(9_999), h.acquired)
        assertTrue(h.delivered.isEmpty())
        h.main.remove().run()
        assertEquals(listOf(9_999), h.delivered)
        assertTrue(h.discarded.isEmpty())
    }

    @Test fun replacementsDuringActiveWorkKeepOnlyOneSuccessorAndDiscardItsStaleResult() {
        val h = Harness()
        h.queue.submit({ repeat(5_000) { h.submit(it) }; 5_000 }) { h.delivered.add(it) }
        h.worker.remove().run()
        assertEquals(listOf(5_000), h.discarded)
        assertEquals(1, h.worker.size)
        assertTrue(h.main.isEmpty())
        h.worker.remove().run(); h.main.remove().run()
        assertEquals(listOf(4_999), h.acquired)
        assertEquals(listOf(4_999), h.delivered)
    }

    @Test fun slowMainThreadRetainsOnlyTheNewestResultAndOneNotification() {
        val h = Harness()
        repeat(50) { h.submit(it); h.worker.remove().run() }
        assertEquals(1, h.main.size)
        assertEquals((0 until 49).toList(), h.discarded)
        h.main.remove().run()
        assertEquals(listOf(49), h.delivered)
        h.queue.close()
        assertFalse(h.discarded.contains(49))
    }

    @Test fun cancelReleasesPendingResultsSuppressesCallbacksAndAllowsReuse() {
        val h = Harness()
        h.submit(1); h.queue.cancel(); h.worker.remove().run()
        assertTrue(h.acquired.isEmpty())
        h.submit(2); h.worker.remove().run(); h.queue.cancel()
        assertEquals(listOf(2), h.discarded)
        h.main.remove().run()
        assertTrue(h.delivered.isEmpty())
        h.submit(3); h.worker.remove().run(); h.main.remove().run()
        assertEquals(listOf(3), h.delivered)
        h.queue.close(); h.queue.close(); h.submit(4)
        assertTrue(h.queue.isClosed)
        assertTrue(h.worker.isEmpty())
    }

    @Test fun closeDuringAcquisitionDiscardsTheCompletedResultAndNeverPublishesIt() {
        val h = Harness()
        var released = 0
        h.queue.submit({
            try { h.submit(2); h.queue.close(); 1 } finally { released++ }
        }) { h.delivered.add(it) }
        h.worker.remove().run()
        assertEquals(1, released)
        assertEquals(listOf(1), h.discarded)
        assertTrue(h.main.isEmpty())
        assertTrue(h.worker.isEmpty())
        assertTrue(h.delivered.isEmpty())
    }

    @Test fun rejectedWorkerReportsAsynchronouslyAndDoesNotStrandTheNextRequest() {
        val h = Harness()
        h.rejectWorker = true; h.submit(1)
        assertTrue(h.delivered.isEmpty())
        h.main.remove().run()
        assertEquals(listOf(-1), h.delivered)
        assertTrue(h.acquired.isEmpty())
        h.rejectWorker = false; h.submit(2)
        h.worker.remove().run(); h.main.remove().run()
        assertEquals(listOf(-1, 2), h.delivered)
    }

    @Test fun rejectedMainQueueDiscardsUnpublishedOutputAndCanRecover() {
        val h = Harness()
        h.rejectMain = true; h.submit(1); h.worker.remove().run()
        assertEquals(listOf(1), h.discarded)
        assertTrue(h.delivered.isEmpty())
        h.rejectMain = false; h.submit(2); h.worker.remove().run(); h.main.remove().run()
        assertEquals(listOf(2), h.delivered)
    }

    @Test fun thrownOperationStillSchedulesTheNewestPendingOperation() {
        val h = Harness()
        h.queue.submit({ h.submit(2); throw IllegalStateException("fixture") }) { h.delivered.add(it) }
        try { h.worker.remove().run(); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, h.worker.size)
        h.worker.remove().run(); h.main.remove().run()
        assertEquals(listOf(2), h.delivered)
    }

    @Test fun throwingCallbackCannotRecyclePublishedOutputOrBlockFutureDelivery() {
        val h = Harness()
        h.queue.submit({ 1 }) { throw IllegalArgumentException("fixture") }
        h.worker.remove().run()
        try { h.main.remove().run(); fail() } catch (_: IllegalArgumentException) { }
        h.queue.cancel()
        assertTrue(h.discarded.isEmpty())
        h.submit(2); h.worker.remove().run(); h.main.remove().run()
        assertEquals(listOf(2), h.delivered)
    }

    @Test fun replacementAndCloseAcrossWorkerAndMainThreadsHaveOneResultOwner() {
        val worker = Executors.newSingleThreadExecutor()
        val main = ConcurrentLinkedQueue<Runnable>()
        val discarded = ConcurrentLinkedQueue<Int>()
        val delivered = mutableListOf<Int>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val posted = CountDownLatch(1)
        val queue = LatestPreparation(worker::execute, { main.add(it); posted.countDown(); true },
            { -1 }, { discarded.add(it) })
        try {
            queue.submit({ entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); 1 }) { delivered.add(it) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.cancel()
            queue.submit({ 2 }) { delivered.add(it) }
            release.countDown()
            assertTrue(posted.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1), discarded.toList())
            queue.close()
            main.remove().run()
            assertEquals(listOf(1, 2), discarded.toList())
            assertTrue(delivered.isEmpty())
        } finally { release.countDown(); queue.close(); worker.shutdownNow() }
    }
}
