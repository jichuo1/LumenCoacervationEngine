package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.material.LensBatchSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensBatchSplitTest {
    private val top = Any()
    private val bottom = Any()

    /** 顶栏胶囊与三个圆按钮共用一份录制，底栏单独一份：底栏整组交给第二条线程，组内不拆。 */
    @Test fun sharedRecordingsStayTogetherAndTheLighterGroupIsOffloaded() {
        val groups = listOf(top, top, top, top, bottom)
        val costs = listOf(18_104L, 7_056L, 7_056L, 7_056L, 18_104L)
        assertEquals(listOf(4), LensBatchSplit.secondHalf(groups, costs, processors = 8))
    }

    @Test fun singleGroupsAndSmallMachinesStaySerial() {
        assertNull(LensBatchSplit.secondHalf(listOf(top, top), listOf(1L, 1L), processors = 8))
        assertNull(LensBatchSplit.secondHalf(listOf(top, bottom), listOf(1L, 1L), processors = 3))
        assertNull(LensBatchSplit.secondHalf(emptyList(), emptyList(), processors = 8))
    }

    @Test fun everyJobRunsExactlyOnceAndGroupsAreNeverSplit() {
        val pictures = List(6) { Any() }
        val groups = listOf(0, 1, 1, 2, 3, 3, 3, 4, 5).map(pictures::get)
        val costs = listOf(5L, 9L, 1L, 7L, 2L, 2L, 2L, 8L, 3L)
        val second = LensBatchSplit.secondHalf(groups, costs, processors = 4)!!
        assertTrue(second.isNotEmpty() && second.size < groups.size)
        assertEquals(second.sorted(), second)
        for (picture in pictures) {
            val indices = groups.indices.filter { groups[it] === picture }
            assertTrue(indices.all { it in second } || indices.none { it in second })
        }
    }

    /** 两半都完成才发布一次；第二条线程随采样器关闭，且名字以 Lumen- 开头（ENGINEERING_RULES §4、§9）。 */
    @Test fun theBatchPublishesOnceAfterBothHalvesAndTheSecondWorkerIsClosed() {
        val sampler = SourceContract.read("material/LiveBackdropSampler.kt")
        val dispatch = sampler.after("val second = LensBatchSplit.secondHalf(").before("repeat(jobs.size)")
        assertTrue(dispatch.contains("if (remaining.decrementAndGet() == 0) mainHandler.post { onBatchDone(token, jobs, !failed.get()) }"))
        assertTrue(dispatch.contains("if (!offloaded) remaining.set(1)"))
        assertTrue(sampler.contains("Thread(task, \"Lumen-LiveLens-2\")"))
        assertTrue(sampler.after("    fun close() {").contains("if (secondWorkerStarted) secondWorker.shutdownNow()"))
    }
}
