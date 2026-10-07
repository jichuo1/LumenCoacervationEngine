package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.runtime.LumenGraphicsCounters
import org.junit.Assert.*
import org.junit.Test

class LumenGraphicsCountersTest {
    @Test fun retiredSourcesKeepLateCompletionAndTimingWithoutRetainingTheOwner(){
        val source=LumenGraphicsCounters().apply {timingEnabled=true}
        val total=LumenGraphicsCounters()
        source.softwareRequested();source.retireInto(total)
        source.softwareCompleted(23L,true)
        assertEquals(1L,total.softwareRequests);assertEquals(1L,total.softwareCompletions)
        assertEquals(1L,total.staleCompletions);assertEquals(23L,total.softwareProcessingNanos)
        assertThrows(IllegalStateException::class.java){source.retireInto(total)}
    }
    @Test fun contentProxyAndEffectWorkAreIndependent() {
        val counters=LumenGraphicsCounters()
        counters.contentRecorded()
        repeat(3) { counters.proxyRecorded() }
        counters.effectBuilt();counters.shaderCompiled()
        assertEquals(1L,counters.contentRecordings)
        assertEquals(3L,counters.proxyRecordings)
        assertEquals(1L,counters.effectBuilds)
        assertEquals(1L,counters.shaderCompilations)
    }
    @Test fun disabledCountersDoNotRecordWorkOrTiming() {
        val counters=LumenGraphicsCounters().apply {enabled=false;timingEnabled=true}
        counters.contentRecorded(100L);counters.proxyRecorded();counters.effectBuilt()
        counters.shaderCompiled();counters.softwareRequested();counters.softwareCompleted(100L,true)
        assertEquals(0L,counters.contentRecordings+counters.proxyRecordings+counters.effectBuilds+counters.shaderCompilations+counters.softwareRequests+counters.softwareCompletions)
        assertEquals(0L,counters.contentRecordingNanos+counters.softwareProcessingNanos)
    }
    @Test fun timingIsOptionalAndNegativeMeasurementsAreIgnored() {
        val counters=LumenGraphicsCounters()
        counters.contentRecorded(100L);counters.softwareCompleted(200L,false)
        assertEquals(0L,counters.contentRecordingNanos+counters.softwareProcessingNanos)
        counters.timingEnabled=true
        counters.contentRecorded(-1L);counters.contentRecorded(10L)
        counters.softwareCompleted(-1L,true);counters.softwareCompleted(20L,false)
        assertEquals(10L,counters.contentRecordingNanos)
        assertEquals(20L,counters.softwareProcessingNanos)
        assertEquals(1L,counters.staleCompletions)
    }
}
