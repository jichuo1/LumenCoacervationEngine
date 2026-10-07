package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.motion.LumenFixedFrameClock
import org.junit.Assert.*
import org.junit.Test

class LumenFrameClockTest {
    @Test fun replaySupportsPauseSeekAndDeterministicSteps(){val c=LumenFixedFrameClock();c.advanceMillis(16);assertEquals(16_000_000L,c.nowNanos());c.seekNanos(0);c.advanceMillis(16);assertEquals(16_000_000L,c.nowNanos())}
    @Test fun negativeAndOverflowingStepsAreRejected(){assertThrows(IllegalArgumentException::class.java){LumenFixedFrameClock(-1)};val c=LumenFixedFrameClock(Long.MAX_VALUE);assertThrows(IllegalArgumentException::class.java){c.advanceMillis(1)}}
}
