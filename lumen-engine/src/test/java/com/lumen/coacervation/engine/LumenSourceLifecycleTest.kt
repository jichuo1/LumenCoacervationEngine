package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.runtime.*
import org.junit.Assert.*
import org.junit.Test

class LumenSourceLifecycleTest {
    @Test fun graphRejectsSelfCyclesAndIndirectCyclesBeforeRecording() {
        assertEquals(LumenSourceGraph.Result.CYCLE,LumenSourceGraph.validate(mapOf(1L to longArrayOf(1))))
        assertEquals(LumenSourceGraph.Result.CYCLE,LumenSourceGraph.validate(mapOf(1L to longArrayOf(2),2L to longArrayOf(3),3L to longArrayOf(1))))
    }
    @Test fun acyclicSharingIsAllowedAndMissingDependenciesAreRejected() {
        assertEquals(LumenSourceGraph.Result.VALID,LumenSourceGraph.validate(mapOf(1L to longArrayOf(3),2L to longArrayOf(3),3L to longArrayOf())))
        assertEquals(LumenSourceGraph.Result.MISSING_DEPENDENCY,LumenSourceGraph.validate(mapOf(1L to longArrayOf(2))))
    }
    @Test fun sourceGraphHasAHardCountLimit() {
        assertEquals(LumenSourceGraph.Result.BUDGET_EXCEEDED,LumenSourceGraph.validate((1L..33L).associateWith {longArrayOf()}))
    }
    @Test fun lateCaptureCannotRestoreAReplacedSource() {
        val timeline=LumenSourceTimeline();val epoch=timeline.epoch
        timeline.requested(10L);timeline.sourceChanged()
        assertFalse(timeline.completed(epoch,0L,10L,20L));assertNull(timeline.estimatedAgeNanos(30L))
    }
    @Test fun sourceAgeIsExplicitlyAnEstimateFromRequestTime() {
        val timeline=LumenSourceTimeline();timeline.contentChanged();timeline.requested(10L)
        assertTrue(timeline.completed(timeline.epoch,timeline.contentVersion,10L,20L))
        assertEquals(20L,timeline.estimatedAgeNanos(30L))
        timeline.contentChanged();assertEquals(1L,timeline.capturedVersion);assertEquals(2L,timeline.contentVersion)
    }
}
