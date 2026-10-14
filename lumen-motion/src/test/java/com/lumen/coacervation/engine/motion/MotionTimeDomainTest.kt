package com.lumen.coacervation.engine.motion

import com.lumen.coacervation.engine.motion.pager.PageMotionContinuation
import com.lumen.coacervation.engine.motion.pager.PageMotionPolicy
import org.junit.Assert.*
import org.junit.Test

class MotionTimeDomainTest {
    @Test fun wallDurationUsesTheSameWholeMillisecondsAsTheNativeAnimator() {
        assertEquals(86L, MotionTimeDomain.wallDurationMs(173L, .5f))
        assertEquals(173L, MotionTimeDomain.wallDurationMs(173L, 1f))
        assertEquals(346L, MotionTimeDomain.wallDurationMs(173L, 2f))
        assertEquals(0L, MotionTimeDomain.wallDurationMs(173L, 0f))
        assertEquals(0L, MotionTimeDomain.wallDurationMs(173L, .001f))
        assertEquals(0L, MotionTimeDomain.wallDurationMs(-1L, 2f))
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            assertEquals(173L, MotionTimeDomain.wallDurationMs(173L, scale))
        }
    }

    @Test fun modalContinuationKeepsRealInitialVelocityAcrossAnimatorScales() {
        for (scale in listOf(.5f, 1f, 2f)) {
            val duration = MotionTimeDomain.wallDurationMs(173L, scale)
            val curve = InterruptibleMotionContinuation(.2f, 1f, .8f, duration)
            val dt = .0001f
            val speed = (curve.value(dt) - curve.value(0f)) / (dt * duration / 1000f)
            assertEquals(.8f, speed, .01f)
            assertEquals(.2f, curve.value(0f), 0f)
            assertEquals(1f, curve.value(1f), .00001f)
        }
    }

    @Test fun pageContinuationKeepsRealVelocityForShortAndLongNavigationAndDrag() {
        for (scale in listOf(.5f, 1f, 2f)) for (navigation in listOf(false, true)) {
            for (duration in if (navigation) listOf(289L, 509L, 560L) else listOf(320L, 420L)) {
                val curve = PageMotionContinuation(.2f, 3, 5f, duration, 4, navigation, scale)
                val wallDuration = MotionTimeDomain.wallDurationMs(duration, scale)
                val dt = .0001f
                val speed = (curve.value(dt) - curve.value(0f)) / (dt * wallDuration / 1000f)
                assertEquals(5f, speed, .05f)
                assertEquals(.2f, curve.value(0f), 0f)
                assertEquals(3f, curve.value(1f), .00001f)
            }
        }
    }

    @Test fun scaleConversionDoesNotClampTheConvertedVelocityASecondTime() {
        val curve = PageMotionContinuation(.2f, 3, 6f, 420L, 4, false, 2f)
        val dt = .0001f
        val speed = (curve.value(dt) - curve.value(0f)) / (dt * .84f)
        assertEquals(6f, speed, .02f)
        val bounded = PageMotionContinuation(.2f, 3, 100f, 420L, 4, false, 2f)
        val boundedSpeed = (bounded.value(dt) - bounded.value(0f)) / (dt * .84f)
        assertEquals(8f, boundedSpeed, .02f)
    }

    @Test fun handoffMatchesRealTimeWhileKeepingItsNominalMinimum() {
        for (scale in listOf(.5f, 1f, 2f)) {
            val duration = PageMotionPolicy.handoffDuration(340L, .5f, 1f, 3f, scale)
            assertTrue(duration in PageMotionPolicy.HANDOFF_MIN_MS..340L)
            val curve = PageMotionContinuation(.5f, 1, 3f, duration, 4, false, scale)
            val wallDuration = MotionTimeDomain.wallDurationMs(duration, scale)
            val dt = .0001f
            val speed = (curve.value(dt) - curve.value(0f)) / (dt * wallDuration / 1000f)
            assertEquals(3f, speed, .03f)
        }
        assertEquals(120L, PageMotionPolicy.handoffDuration(340L, .99f, 1f, 8f, 2f))
    }

    @Test fun oneTimesScalePreservesTheOldHandoffAndEntireContinuationCurve() {
        for (navigation in listOf(false, true)) for (duration in listOf(120L, 289L, 420L, 509L, 560L)) {
            for (velocity in listOf(-100f, -8f, -1f, 0f, 1f, 8f, 100f, Float.NaN)) {
                val original = PageMotionContinuation(.2f, 3, velocity, duration, 4, navigation)
                val scaled = PageMotionContinuation(.2f, 3, velocity, duration, 4, navigation, 1f)
                repeat(1001) { assertEquals(original.value(it / 1000f), scaled.value(it / 1000f), 0f) }
                assertEquals(PageMotionPolicy.handoffDuration(duration, .2f, 3f, velocity),
                    PageMotionPolicy.handoffDuration(duration, .2f, 3f, velocity, 1f))
            }
        }
    }

    @Test fun scaledContinuationsStillRespectReversalSpaceAndPageBoundaries() {
        for (scale in listOf(.5f, 1f, 2f, 10f)) for (velocity in listOf(-100f, 100f)) {
            for ((start, target) in listOf(-.17f to 3, .2f to 3, 2.8f to 0, 3.17f to 0)) {
                val curve = PageMotionContinuation(start, target, velocity, 560L, 4, true, scale)
                assertEquals(start, curve.value(0f), 0f)
                assertEquals(target.toFloat(), curve.value(1f), .00001f)
                repeat(1001) { assertTrue(curve.value(it / 1000f) in -.18f..3.18f) }
            }
        }
    }

    @Test fun oppositeAndZeroVelocityKeepBaseHandoffDuration() {
        for (scale in listOf(0f, .5f, 1f, 2f)) {
            assertEquals(340L, PageMotionPolicy.handoffDuration(340L, .5f, 1f, -3f, scale))
            assertEquals(340L, PageMotionPolicy.handoffDuration(340L, .5f, 1f, 0f, scale))
        }
    }
}
