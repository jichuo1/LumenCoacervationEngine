package com.lumen.coacervation.engine.widget

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class ModernNavigationMotionTest {
    @Test fun continuousProgressAndRtlUseTheSameFourLogicalPages() {
        assertEquals(1.4f, NavigationBarMotion.physicalSlot(1.4f, 4, false), .00001f)
        assertEquals(1.6f, NavigationBarMotion.physicalSlot(1.4f, 4, true), .00001f)
        assertEquals(0, NavigationBarMotion.indexAt(10f, 280f, 4f, 4, false))
        assertEquals(3, NavigationBarMotion.indexAt(10f, 280f, 4f, 4, true))
        assertEquals(3, NavigationBarMotion.indexAt(280f, 280f, 4f, 4, false))
        assertEquals(0, NavigationBarMotion.indexAt(280f, 280f, 4f, 4, true))
        assertEquals(2f, NavigationBarMotion.scrubPosition(1f, 70f, 70f, 4, false), 0f)
        assertEquals(0f, NavigationBarMotion.scrubPosition(1f, 70f, 70f, 4, true), 0f)
        assertEquals(3f, NavigationBarMotion.scrubPosition(1f, 10000f, 70f, 4, false), 0f)
        assertEquals(0f, NavigationBarMotion.position(Float.NaN, 4), 0f)
    }

    @Test fun fourDirectionMovementHasOneFourDpVectorBudgetIncludingDiagonalRelease() {
        for (x in listOf(-10000f, -50f, 0f, 50f, 10000f)) for (y in listOf(-10000f, -50f, 0f, 50f, 10000f)) {
            val scale = NavigationBarMotion.displacementScale(x, y, 4f, 48f)
            assertTrue(sqrt(x * x + y * y) * scale <= 4.00001f)
            assertTrue(scale >= 0f)
            val springClamp = NavigationBarMotion.travelClampScale(x, y, 4f)
            assertTrue(sqrt(x * x + y * y) * springClamp <= 4.00001f)
        }
        assertEquals(0f, NavigationBarMotion.displacementScale(Float.NaN, 1f, 4f, 48f), 0f)
        assertEquals(1f, NavigationBarMotion.lensScaleX(0f), 0f)
        assertEquals(1.055f, NavigationBarMotion.lensScaleX(2f), .00001f)
        assertEquals(1.07f, NavigationBarMotion.lensScaleY(2f), .00001f)
    }

    @Test fun onlyReleasedHorizontalLensScrubsCommitAndCancellationNeverDoes() {
        val gesture = NavigationBarGesture()
        gesture.begin(1, true)
        assertFalse(gesture.move(2f, 1f, 8f))
        assertTrue(gesture.move(30f, 3f, 8f))
        assertFalse(gesture.move(80f, 3f, 8f))
        assertEquals(NavigationBarIntent.SCRUB, gesture.intent)
        assertEquals(2, gesture.finish(false, 2.4f, 1, 4))
        assertNull(gesture.finish(false, 2.4f, 1, 4))
        gesture.begin(1, true)
        gesture.move(80f, 0f, 8f)
        assertNull(gesture.finish(true, 3f, 3, 4))
        gesture.begin(1, true)
        gesture.move(80f, 0f, 8f)
        gesture.cancel()
        assertNull(gesture.finish(false, 3f, 3, 4))
    }

    @Test fun verticalAndOffLensDragsOnlyReboundWhileStationaryTapsSelectTheirOwnSlot() {
        val gesture = NavigationBarGesture()
        gesture.begin(2, true)
        gesture.move(3f, -40f, 8f)
        assertEquals(NavigationBarIntent.ELASTIC, gesture.intent)
        assertNull(gesture.finish(false, 1f, 2, 4))
        gesture.begin(2, false)
        gesture.move(-40f, 3f, 8f)
        assertNull(gesture.finish(false, 1f, 1, 4))
        gesture.begin(2, false)
        assertEquals(2, gesture.finish(false, 1f, 2, 4))
        gesture.begin(2, false)
        assertNull(gesture.finish(false, 1f, -1, 4))
    }

    @Test fun analyticSpringPreservesInterruptionPositionAndVelocityAndConverges() {
        for (start in listOf(-4f, 0f, .4f, 4f)) for (velocity in listOf(-20f, 0f, 20f)) {
            val spring = LumenSpring(start, 0f, velocity)
            assertEquals(start, spring.value(0f), .000001f)
            assertEquals(velocity, spring.velocity(0f), .0001f)
            assertTrue(abs(spring.value(1f)) < .0001f)
            val time = .075f
            val retarget = LumenSpring(spring.value(time), 1f, spring.velocity(time))
            assertEquals(spring.value(time), retarget.value(0f), .000001f)
            assertEquals(spring.velocity(time), retarget.velocity(0f), .0001f)
            assertTrue(abs(retarget.value(1f) - 1f) < .0001f)
        }
    }
}
