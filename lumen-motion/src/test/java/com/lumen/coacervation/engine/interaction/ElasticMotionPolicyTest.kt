package com.lumen.coacervation.engine.interaction

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class ElasticMotionPolicyTest {
    @Test fun staticTranslucentTextDoesNotBlockItsClickableRow() {
        for (alpha in listOf(.6f, .68f, .85f, 1f)) {
            assertEquals(ElasticNodeRole.TRAVERSE, ElasticEligibilityPolicy.nodeRole(
                alpha, false, false, false, false, 280, 24, 400, 760))
            assertTrue(ElasticEligibilityPolicy.unchangedOpacity(alpha, alpha, false, false))
        }
        assertEquals(ElasticNodeRole.TARGET, ElasticEligibilityPolicy.nodeRole(
            1f, false, false, true, false, 360, 72, 400, 760))
    }

    @Test fun staticToolbarOpacityDoesNotDisableAnInteractiveIcon() {
        assertEquals(ElasticNodeRole.TARGET, ElasticEligibilityPolicy.nodeRole(
            .85f, false, false, true, false, 48, 48, 400, 760))
        assertTrue(ElasticEligibilityPolicy.unchangedOpacity(.85f, .85f, false, false))
        assertFalse(ElasticEligibilityPolicy.unchangedOpacity(.85f, .8f, false, false))
    }

    @Test fun animatedAncestorsAndOpacityChangesStayIneligible() {
        for (alpha in listOf(.6f, .85f, 1f)) {
            assertEquals(ElasticNodeRole.BLOCKED, ElasticEligibilityPolicy.nodeRole(
                alpha, true, false, false, false, 400, 760, 400, 760))
            assertEquals(ElasticNodeRole.BLOCKED, ElasticEligibilityPolicy.nodeRole(
                alpha, false, true, false, false, 400, 760, 400, 760))
            assertFalse(ElasticEligibilityPolicy.unchangedOpacity(alpha, alpha, true, false))
            assertFalse(ElasticEligibilityPolicy.unchangedOpacity(alpha, alpha, false, true))
            assertFalse(ElasticEligibilityPolicy.unchangedOpacity(alpha, alpha - .01f, false, false))
        }
        for (alpha in listOf(0f, -.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(ElasticNodeRole.BLOCKED, ElasticEligibilityPolicy.nodeRole(
                alpha, false, false, true, false, 48, 48, 400, 760))
        }
    }

    @Test fun windowRootsAndFullscreenScrimsAreTraversedButNeverScaled() {
        assertEquals(ElasticNodeRole.TRAVERSE, ElasticEligibilityPolicy.nodeRole(
            1f, false, false, true, true, 400, 800, 400, 760))
        // Explicit root exclusion applies even to a small popup window or stale measured size.
        assertEquals(ElasticNodeRole.TRAVERSE, ElasticEligibilityPolicy.nodeRole(
            1f, false, false, true, true, 80, 60, 400, 760))
        assertEquals(ElasticNodeRole.TRAVERSE, ElasticEligibilityPolicy.nodeRole(
            1f, false, false, true, false, 400, 760, 400, 760))
        assertEquals(ElasticNodeRole.TRAVERSE, ElasticEligibilityPolicy.nodeRole(
            1f, false, false, true, false, 360, 684, 400, 760))
        // A child's eligibility is independent of its traversal-only window/scrim ancestor.
        assertEquals(ElasticNodeRole.TARGET, ElasticEligibilityPolicy.nodeRole(
            .85f, false, false, true, false, 320, 64, 400, 760))
        assertEquals(ElasticNodeRole.TARGET, ElasticEligibilityPolicy.nodeRole(
            1f, false, false, true, false, 359, 684, 400, 760))
    }

    @Test fun containerOnlyKeepsItsControlsReachableWithoutMakingBlankSpaceElastic() {
        assertEquals(ElasticNodeRole.TRAVERSE, ElasticEligibilityPolicy.nodeRole(
            1f, false, false, true, false, 340, 320, 400, 760, containerOnly = true))
        assertEquals(ElasticNodeRole.TARGET, ElasticEligibilityPolicy.nodeRole(
            .85f, false, false, true, false, 280, 48, 400, 760, containerOnly = false))
        // The traversal tag is not an exemption from a currently running container animation.
        assertEquals(ElasticNodeRole.BLOCKED, ElasticEligibilityPolicy.nodeRole(
            1f, true, false, true, false, 340, 320, 400, 760, containerOnly = true))
    }

    @Test fun aStationaryHoldNeedsMovementAfterTheDeadlineToCapture() {
        val gate = ElasticGestureGate()
        gate.begin(1000L)
        assertEquals(ElasticGestureDecision.OBSERVE, gate.move(1159L, 0f, 0f, 8f))
        assertEquals(ElasticGestureDecision.OBSERVE, gate.move(1160L, 8f, 0f, 8f))
        assertEquals(ElasticGestureDecision.OBSERVE, gate.move(1500L, 0f, 0f, 8f))
        assertEquals(ElasticGestureDecision.CAPTURE, gate.move(1501L, 8.001f, 0f, 8f))
        assertEquals(ElasticGestureDecision.DRAG, gate.move(1510L, -12f, 6f, 8f))
    }

    @Test fun fastScrollingCanNeverBecomeElasticEvenIfItLaterPauses() {
        for ((dx, dy) in listOf(12f to 0f, -12f to 0f, 0f to 12f, 0f to -12f, 6f to 6f)) {
            val gate = ElasticGestureGate()
            gate.begin(0L)
            assertEquals(ElasticGestureDecision.YIELD, gate.move(159L, dx, dy, 8f))
            assertEquals(ElasticGestureDecision.YIELD, gate.move(160L, dx, dy, 8f))
            assertEquals(ElasticGestureDecision.YIELD, gate.move(800L, dx * 4f, dy * 4f, 8f))
        }
    }

    @Test fun deadlineIsInclusiveInEveryPhysicalDirection() {
        for ((dx, dy) in listOf(12f to 0f, -12f to 0f, 0f to 12f, 0f to -12f)) {
            val gate = ElasticGestureGate()
            gate.begin(2000L)
            assertEquals(ElasticGestureDecision.CAPTURE, gate.move(2160L, dx, dy, 8f))
        }
    }

    @Test fun batchedMoveHistoryHonorsEarlierScrollInsteadOfUsingOnlyLatestTimestamp() {
        val gate = ElasticGestureGate()
        gate.begin(0L)
        assertEquals(ElasticGestureDecision.OBSERVE, gate.move(80L, 2f, 0f, 8f))
        assertEquals(ElasticGestureDecision.YIELD, gate.move(120L, 9f, 0f, 8f))
        assertEquals(ElasticGestureDecision.YIELD, gate.move(190L, 40f, 0f, 8f))
    }

    @Test fun cancelledOrMultitouchStreamsStayYieldedUntilANewDown() {
        val gate = ElasticGestureGate()
        gate.begin(0L)
        assertEquals(ElasticGestureDecision.CAPTURE, gate.move(160L, 10f, 10f, 8f))
        gate.yield()
        assertEquals(ElasticGestureDecision.YIELD, gate.move(200L, 20f, 20f, 8f))
        gate.begin(300L)
        assertEquals(ElasticGestureDecision.CAPTURE, gate.move(460L, 10f, 0f, 8f))
    }

    @Test fun invalidOrReversedTimeCannotTakeGestureOwnership() {
        for ((dx, dy, slop) in listOf(Triple(Float.NaN, 0f, 8f), Triple(0f, Float.POSITIVE_INFINITY, 8f),
            Triple(10f, 0f, -1f), Triple(10f, 0f, Float.NaN))) {
            val gate = ElasticGestureGate()
            gate.begin(100L)
            assertEquals(ElasticGestureDecision.YIELD, gate.move(260L, dx, dy, slop))
        }
        val gate = ElasticGestureGate()
        gate.begin(100L)
        gate.move(250L, 0f, 0f, 8f)
        assertEquals(ElasticGestureDecision.YIELD, gate.move(240L, 12f, 0f, 8f))
    }

    @Test fun dragIsContinuousAtSlopBoundedAndSymmetric() {
        val forward = ElasticVector()
        val reverse = ElasticVector()
        var previous = 0f
        for (step in 0..10000) {
            val x = step / 10f
            ElasticMotionPolicy.drag(x, 0f, 8f, 12f, forward)
            ElasticMotionPolicy.drag(-x, 0f, 8f, 12f, reverse)
            assertTrue(forward.x >= previous)
            assertTrue(forward.x in 0f..12f)
            assertEquals(-forward.x, reverse.x, .000001f)
            assertEquals(0f, forward.y, 0f)
            previous = forward.x
        }
        ElasticMotionPolicy.drag(8f, 0f, 8f, 12f, forward)
        assertEquals(0f, forward.x, 0f)
        ElasticMotionPolicy.drag(8.0001f, 0f, 8f, 12f, forward)
        assertTrue(forward.x < .0001f)
    }

    @Test fun diagonalDragRemovesCircularSlopAndDoesNotPreferAnAxis() {
        val one = ElasticVector()
        val two = ElasticVector()
        ElasticMotionPolicy.drag(3f, 4f, 5f, 12f, one)
        assertEquals(0f, one.x, 0f)
        assertEquals(0f, one.y, 0f)
        ElasticMotionPolicy.drag(30f, 60f, 8f, 12f, one)
        ElasticMotionPolicy.drag(60f, 30f, 8f, 12f, two)
        assertEquals(one.x, two.y, .000001f)
        assertEquals(one.y, two.x, .000001f)
        assertTrue(one.y > one.x)
        ElasticMotionPolicy.drag(Float.MAX_VALUE, -Float.MAX_VALUE, 8f, 12f, one)
        assertEquals(12f / kotlin.math.sqrt(2f), one.x, .00001f)
        assertEquals(-one.x, one.y, 0f)
    }

    @Test fun rotatingADragKeepsItsLengthWithinOneRadialBudget() {
        val axial = ElasticVector()
        val rotated = ElasticVector()
        for (distance in listOf(0f, 8f, 8.0001f, 20f, 90f, 10000f)) {
            ElasticMotionPolicy.drag(distance, 0f, 8f, 12f, axial)
            for (degree in 0 until 360 step 5) {
                val angle = Math.toRadians(degree.toDouble())
                ElasticMotionPolicy.drag((distance * kotlin.math.cos(angle)).toFloat(),
                    (distance * kotlin.math.sin(angle)).toFloat(), 8f, 12f, rotated)
                val length = kotlin.math.hypot(rotated.x, rotated.y)
                assertTrue(length <= 12.00001f)
                assertEquals(axial.x, length, .00001f)
            }
        }
    }

    @Test fun regrabStartsAtRenderedPositionAndCannotIncreaseExistingOvershoot() {
        val out = ElasticVector()
        for ((x, y) in listOf(7f to -4f, -3f to 5f, 14f to 0f)) {
            ElasticMotionPolicy.dragFrom(x, y, 0f, 0f, 8f, 12f, out)
            assertEquals(x, out.x, .000001f)
            assertEquals(y, out.y, .000001f)
            ElasticMotionPolicy.dragFrom(x, y, 8.0001f, 0f, 8f, 12f, out)
            assertEquals(x, out.x, .0001f)
            assertEquals(y, out.y, .0001f)
            ElasticMotionPolicy.dragFrom(x, y, 10000f, -10000f, 8f, 12f, out)
            assertTrue(kotlin.math.hypot(out.x, out.y) <= maxOf(12f, kotlin.math.hypot(x, y)) + .00001f)
        }
    }

    @Test fun invalidDragClearsTheReusableOutputRatherThanLeavingAStaleFrame() {
        val output = ElasticVector(10f, 12f)
        for ((x, y, slop, limit) in listOf(listOf(Float.NaN, 0f, 8f, 12f),
            listOf(0f, Float.NEGATIVE_INFINITY, 8f, 12f), listOf(1f, 1f, -1f, 12f),
            listOf(1f, 1f, 8f, 0f), listOf(1f, 1f, 8f, Float.NaN))) {
            output.x = 10f
            output.y = 12f
            ElasticMotionPolicy.drag(x, y, slop, limit, output)
            assertEquals(0f, output.x, 0f)
            assertEquals(0f, output.y, 0f)
        }
    }

    @Test fun limitsScaleWithDensityButSmallControlsRemainSmall() {
        assertEquals(7.68f, ElasticMotionPolicy.positionLimit(48, 48, 1f), .00001f)
        assertEquals(18f, ElasticMotionPolicy.positionLimit(300, 300, 1f), 0f)
        assertEquals(36f, ElasticMotionPolicy.positionLimit(600, 600, 2f), 0f)
        assertEquals(0f, ElasticMotionPolicy.positionLimit(0, 30, 1f), 0f)
        assertEquals(0f, ElasticMotionPolicy.positionLimit(30, 30, Float.NaN), 0f)
    }

    @Test fun touchShapeHasDirectionalStretchAndExactlyStableReleasedGeometry() {
        val scale = ElasticVector()
        ElasticMotionPolicy.scale(0f, 0f, 0f, 12f, scale)
        assertEquals(1f, scale.x, 0f)
        assertEquals(1f, scale.y, 0f)
        ElasticMotionPolicy.scale(1f, 0f, 0f, 12f, scale)
        assertTrue(scale.x < 1f)
        assertEquals(scale.x, scale.y, 0f)
        ElasticMotionPolicy.scale(1f, 10f, 0f, 12f, scale)
        assertTrue(scale.x > scale.y)
        val horizontalX = scale.x
        val horizontalY = scale.y
        ElasticMotionPolicy.scale(1f, 0f, -10f, 12f, scale)
        assertEquals(horizontalX, scale.y, 0f)
        assertEquals(horizontalY, scale.x, 0f)
        for (p in listOf(-100f, 0f, 1f, 100f, Float.NaN)) for (x in listOf(-1000f, 0f, 1000f)) {
            ElasticMotionPolicy.scale(p, x, -x, 12f, scale)
            assertTrue(scale.x in .97f..1.04f)
            assertTrue(scale.y in .97f..1.04f)
        }
    }

    @Test fun releaseVelocityIsBoundedAndAStationaryHoldDoesNotReuseOldSpeed() {
        assertEquals(100f, ElasticMotionPolicy.releaseVelocity(0f, 1f, 10L, 12f), 0f)
        assertEquals(240f, ElasticMotionPolicy.releaseVelocity(0f, 100f, 1L, 12f), 0f)
        assertEquals(-240f, ElasticMotionPolicy.releaseVelocity(0f, -100f, 1L, 12f), 0f)
        for (time in listOf(-1L, 0L, 81L, 2000L)) {
            assertEquals(0f, ElasticMotionPolicy.releaseVelocity(0f, 10f, time, 12f), 0f)
        }
    }

    @Test fun physicalReboundCrossesRestAndSettlesWithinTheBoundedClock() {
        for (rate in listOf(30, 60, 90, 120)) {
            val spring = ElasticSpringAxis(12f)
            var crossed = false
            repeat((rate * ElasticMotionPolicy.MAX_SETTLE_MILLIS / 1000L).toInt()) {
                spring.advance(1f / rate, 0f)
                assertTrue(spring.value.isFinite())
                assertTrue(abs(spring.value) <= 12f)
                if (spring.value < 0f) crossed = true
            }
            assertTrue(crossed)
            assertTrue(spring.atRest(0f, .025f))
        }
    }

    @Test fun highReleaseSpeedsRemainFiniteAndSettleWithoutAnUnboundedRebound() {
        for (direction in listOf(-1f, 1f)) for (speed in listOf(-240f, 0f, 240f)) {
            val spring = ElasticSpringAxis(12f * direction, speed)
            repeat(86) {
                spring.advance(1f / 120f, 0f)
                assertTrue(spring.value.isFinite())
                assertTrue(spring.velocity.isFinite())
                assertTrue(abs(spring.value) < 24f)
            }
            assertTrue(spring.atRest(0f, .025f))
        }
    }

    @Test fun springStateIsConsistentAcrossFrameRatesAndInterruptedTargets() {
        val once = ElasticSpringAxis(4f, -10f)
        val split = ElasticSpringAxis(4f, -10f)
        once.advance(.1f, 0f)
        repeat(12) { split.advance(.1f / 12, 0f) }
        assertEquals(once.value, split.value, .00001f)
        assertEquals(once.velocity, split.velocity, .0001f)
        val retainedPosition = split.value
        val retainedVelocity = split.velocity
        split.advance(0f, 1f)
        assertEquals(retainedPosition, split.value, 0f)
        assertEquals(retainedVelocity, split.velocity, 0f)
        split.advance(.001f, 1f)
        assertTrue(split.value < retainedPosition)
    }

    @Test fun invalidSpringValuesRecoverToTheRequestedRestPosition() {
        val spring = ElasticSpringAxis(Float.NaN, Float.POSITIVE_INFINITY)
        spring.advance(.016f, 1f)
        assertEquals(1f, spring.value, 0f)
        assertEquals(0f, spring.velocity, 0f)
        spring.reset(12f)
        spring.advance(Float.NaN, 0f)
        assertEquals(12f, spring.value, 0f)
        spring.advance(10f, 0f)
        assertTrue(spring.atRest(0f, .025f))
    }

    @Test fun repeatedViewTakeoversPreserveFirstBaselineAndRejectLateReleases() {
        val leases = ElasticTransformLeases<Any, Any>()
        val view = Any()
        val first = Any()
        val baseline = ElasticTransform(2f, 3f, 1f, 1f)
        var previous = leases.acquire(view, first, baseline)
        repeat(100) { step ->
            val nextOwner = Any()
            val deformed = ElasticTransform(2f + step, 3f - step, .984f, 1.015f)
            val next = leases.acquire(view, nextOwner, deformed)
            assertEquals(baseline, next.original)
            assertEquals(deformed.translationX, next.writtenX, 0f)
            assertEquals(deformed.scaleX, next.writtenScaleX, 0f)
            assertFalse(leases.owns(view, previous))
            assertFalse(leases.release(view, previous))
            assertSame(nextOwner, leases.owner(view))
            previous = next
        }
        assertTrue(leases.release(view, previous))
        assertFalse(leases.release(view, previous))
        assertNull(leases.current(view))
    }

    @Test fun separateViewsCannotShareTransformsAndAReleasedViewCanAcquireANewBaseline() {
        val leases = ElasticTransformLeases<Any, Any>()
        val owner = Any()
        val firstView = Any()
        val secondView = Any()
        val first = ElasticTransform(0f, 0f, 1f, 1f)
        val second = ElasticTransform(12f, 24f, 1f, 1f)
        val one = leases.acquire(firstView, owner, first)
        val two = leases.acquire(secondView, owner, second)
        one.record(1f, 2f, .99f, 1.01f)
        assertEquals(second, two.original)
        assertEquals(1f, two.writtenScaleX, 0f)
        assertTrue(leases.release(firstView, one))
        val replacement = leases.acquire(firstView, owner, second)
        assertEquals(second, replacement.original)
        assertFalse(leases.release(firstView, one))
        assertTrue(leases.owns(secondView, two))
    }
}
