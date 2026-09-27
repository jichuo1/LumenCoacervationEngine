package com.lumen.coacervation.engine.motion.pager

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class SettingsPageMotionPolicyTest {
    @Test fun horizontalAccessibilityActionsFollowPhysicalDirectionInBothLayouts() {
        assertEquals(0, PageMotionPolicy.physicalPageTarget(1, -1, false))
        assertEquals(2, PageMotionPolicy.physicalPageTarget(1, 1, false))
        assertEquals(2, PageMotionPolicy.physicalPageTarget(1, -1, true))
        assertEquals(0, PageMotionPolicy.physicalPageTarget(1, 1, true))
        assertEquals(1, PageMotionPolicy.physicalPageTarget(1, 0, true))
        for (rtl in listOf(false, true)) for (current in 0..3) for (direction in listOf(-1, 1)) {
            val target = PageMotionPolicy.physicalPageTarget(current, direction, rtl)
            val events = mutableListOf<String>()
            val navigated = PageUserNavigation.request(current, target, 4,
                onUserInteraction = { events += "cancel" }, selectPage = { events += "select:$it" })
            assertEquals(target in 0..3, navigated)
            assertEquals(if (navigated) listOf("cancel", "select:$target") else emptyList<String>(), events)
        }
    }

    @Test fun successfulKeyboardAndAccessibilityNavigationCancelBeforeSelectingExactlyOnce() {
        for (current in 0..3) for (target in 0..3) {
            if (current == target) continue
            val calls = mutableListOf<String>()
            var selected = current
            assertTrue(PageUserNavigation.request(current, target, 4,
                onUserInteraction = { calls += "cancel:$selected" },
                selectPage = { selected = it; calls += "select:$it" }))
            assertEquals(listOf("cancel:$current", "select:$target"), calls)
            assertEquals(target, selected)
        }
    }

    @Test fun edgeSamePageAndMissingPageNavigationNeverCancelAnExistingReveal() {
        for ((current, target, count) in listOf(Triple(0, -1, 4), Triple(3, 4, 4),
            Triple(2, 2, 4), Triple(0, 0, 0), Triple(0, 1, 1), Triple(0, 10, 10))) {
            var cancelled = 0
            var selected = 0
            assertFalse(PageUserNavigation.request(current, target, count,
                onUserInteraction = { cancelled++ }, selectPage = { selected++ }))
            assertEquals(0, cancelled)
            assertEquals(0, selected)
        }
    }

    @Test fun motionLifecycleReportsOneStartPerContinuousAnimationAndReturnsToSettled() {
        val lifecycle = PageMotionLifecycle()
        assertTrue(lifecycle.isSettled)
        assertTrue(lifecycle.beginMotion())
        assertFalse(lifecycle.isSettled)
        repeat(20) { assertFalse(lifecycle.beginMotion()) }
        lifecycle.finishMotion()
        assertTrue(lifecycle.isSettled)
        assertTrue(lifecycle.beginMotion())
        assertFalse(lifecycle.isSettled)
    }

    @Test fun effectiveGestureNotifiesEvenWhenTakingOverMotionOrReturningToSamePage() {
        val lifecycle = PageMotionLifecycle()
        lifecycle.beginMotion()
        // DOWN pauses the animator without declaring a new gesture or completing the old motion.
        assertFalse(lifecycle.isSettled)
        assertTrue(lifecycle.beginUserGesture())
        assertFalse(lifecycle.beginMotion())
        assertFalse(lifecycle.beginUserGesture())
        lifecycle.finishUserGesture()
        assertFalse(lifecycle.isSettled)
        lifecycle.finishMotion()
        assertTrue(lifecycle.isSettled)
        // A later small drag back to the original selection is still a new user interaction.
        assertTrue(lifecycle.beginUserGesture())
        assertTrue(lifecycle.beginMotion())
        lifecycle.finishUserGesture()
        lifecycle.finishMotion()
        assertTrue(lifecycle.isSettled)
    }

    @Test fun cancellingOrDisablingAnimationDoesNotReenableContentBeforeGestureEnds() {
        val lifecycle = PageMotionLifecycle()
        lifecycle.beginUserGesture()
        lifecycle.beginMotion()
        lifecycle.finishMotion()
        assertFalse(lifecycle.isSettled)
        lifecycle.finishUserGesture()
        assertTrue(lifecycle.isSettled)
        lifecycle.finishMotion()
        lifecycle.finishUserGesture()
        assertTrue(lifecycle.isSettled)
    }

    @Test fun switchLabelAllowsPagingWhileTrackThumbAndTouchSlopKeepTheirGesture() {
        val track = SwitchTouchBounds(260, 15, 310, 45)
        val thumb = SwitchTouchBounds(285, 12, 315, 48)
        fun protects(x: Float, y: Float) = PageMotionPolicy.protectsSwitchTouch(
            x, y, 320, 60, true, track, thumb, 8)
        assertFalse(protects(120f, 30f))
        assertTrue(protects(262f, 30f))
        assertTrue(protects(314f, 30f))
        assertTrue(protects(252f, 4f))
        assertFalse(protects(251.99f, 30f))
        assertFalse(protects(270f, 3.99f))
        assertFalse(protects(270f, 56.01f))
    }

    @Test fun switchProtectionUsesActualLeftSideRtlBoundsWithoutMirroringThemTwice() {
        val track = SwitchTouchBounds(10, 15, 60, 45)
        val thumb = SwitchTouchBounds(5, 12, 35, 48)
        assertTrue(PageMotionPolicy.protectsSwitchTouch(40f, 30f, 320, 60, true, track, thumb, 8))
        assertFalse(PageMotionPolicy.protectsSwitchTouch(200f, 30f, 320, 60, true, track, thumb, 8))
    }

    @Test fun unknownOrStaleSwitchGeometryConservativelyKeepsTheEntireGesture() {
        val valid = SwitchTouchBounds(260, 15, 310, 45)
        for (bounds in listOf(null, SwitchTouchBounds(0, 0, 0, 0),
            SwitchTouchBounds(-1, 0, 40, 40), SwitchTouchBounds(310, 0, 340, 40),
            SwitchTouchBounds(10, 10, 5, 20))) {
            assertTrue(PageMotionPolicy.protectsSwitchTouch(100f, 30f, 320, 60, true, bounds, valid, 8))
            assertTrue(PageMotionPolicy.protectsSwitchTouch(100f, 30f, 320, 60, true, valid, bounds, 8))
        }
        assertTrue(PageMotionPolicy.protectsSwitchTouch(100f, 30f, 320, 60, false, valid, valid, 8))
        assertTrue(PageMotionPolicy.protectsSwitchTouch(100f, 30f, 0, 0, true, valid, valid, 8))
    }

    @Test fun onlyViewportIntersectingPagesRenderIncludingFarRetargetsAndEdgeRebounds() {
        fun visible(position: Float) = (0..3).filter { PageMotionPolicy.isPageVisible(it, position, 4) }
        assertEquals(listOf(0), visible(0f))
        assertEquals(listOf(3), visible(3f))
        assertEquals(listOf(1, 2), visible(1.5f))
        assertEquals(listOf(0), visible(-.18f))
        assertEquals(listOf(3), visible(3.18f))
        for (step in -18..318) assertTrue(visible(step / 100f).size in 1..2)
        assertFalse(PageMotionPolicy.isPageVisible(0, Float.NaN, 4))
        assertFalse(PageMotionPolicy.isPageVisible(4, 3.5f, 4))
    }

    @Test fun physicalDirectionMirrorsInRtlWithoutChangingLogicalPageOrder() {
        assertEquals(1f, PageMotionPolicy.logicalDelta(-400f, 400, false), 0f)
        assertEquals(-1f, PageMotionPolicy.logicalDelta(-400f, 400, true), 0f)
        assertEquals(0f, PageMotionPolicy.logicalDelta(1f, 0, false), 0f)
        assertEquals(0f, PageMotionPolicy.logicalDelta(Float.NaN, 400, false), 0f)
    }

    @Test fun dragEdgesAreContinuousMonotonicBoundedAndNeverMoveSinglePage() {
        assertEquals(1.25f, PageMotionPolicy.resistedPosition(1.25f, 4), 0f)
        var previous = 0f
        for (step in 0..1000) {
            val pulled = PageMotionPolicy.resistedPosition(-step.toFloat() / 100f, 4)
            assertTrue(pulled <= previous)
            assertTrue(pulled >= -PageMotionPolicy.EDGE_LIMIT)
            assertEquals(3f - pulled, PageMotionPolicy.resistedPosition(3f + step / 100f, 4), .000001f)
            previous = pulled
        }
        assertEquals(0f, PageMotionPolicy.resistedPosition(10f, 1), 0f)
        assertEquals(0f, PageMotionPolicy.resistedPosition(Float.POSITIVE_INFINITY, 4), 0f)
    }

    @Test fun distanceVelocityAndReversalChooseAtMostOneNeighbor() {
        assertEquals(1, PageMotionPolicy.releasePage(1, 1.1f, 0f, 4))
        assertEquals(2, PageMotionPolicy.releasePage(1, 1.3f, 0f, 4))
        assertEquals(0, PageMotionPolicy.releasePage(1, .7f, 0f, 4))
        assertEquals(2, PageMotionPolicy.releasePage(1, 1.02f, 2f, 4))
        assertEquals(1, PageMotionPolicy.releasePage(1, 1.4f, -2f, 4))
        assertEquals(1, PageMotionPolicy.releasePage(1, .6f, 2f, 4))
        for (selected in 0..3) for (position in listOf(-20f, 0f, .5f, 2f, 30f)) for (velocity in listOf(-100f, 0f, 100f)) {
            val target = PageMotionPolicy.releasePage(selected, position, velocity, 4)
            assertTrue(target in 0..3)
            assertTrue(abs(target - selected) <= 1)
        }
        assertEquals(2, PageMotionPolicy.releasePage(2, Float.NaN, 0f, 4))
    }

    @Test fun distanceAndVelocityThresholdsAreInclusiveWithAdjacentFloatBoundaries() {
        assertEquals(0, PageMotionPolicy.releasePage(0, Math.nextDown(.22f), 0f, 4))
        assertEquals(1, PageMotionPolicy.releasePage(0, .22f, 0f, 4))
        assertEquals(1, PageMotionPolicy.releasePage(0, Math.nextUp(.22f), 0f, 4))
        val backwardBoundary = 1f - .22f
        assertEquals(1, PageMotionPolicy.releasePage(1, Math.nextUp(backwardBoundary), 0f, 4))
        assertEquals(0, PageMotionPolicy.releasePage(1, backwardBoundary, 0f, 4))
        assertEquals(0, PageMotionPolicy.releasePage(1, Math.nextDown(backwardBoundary), 0f, 4))
        assertEquals(1, PageMotionPolicy.releasePage(1, 1.02f, Math.nextDown(.5f), 4))
        assertEquals(2, PageMotionPolicy.releasePage(1, 1.02f, .5f, 4))
        assertEquals(2, PageMotionPolicy.releasePage(1, 1.02f, Math.nextUp(.5f), 4))
        assertEquals(1, PageMotionPolicy.releasePage(1, .98f, Math.nextUp(-.5f), 4))
        assertEquals(0, PageMotionPolicy.releasePage(1, .98f, -.5f, 4))
        assertEquals(0, PageMotionPolicy.releasePage(1, .98f, Math.nextDown(-.5f), 4))
        assertEquals(2, PageMotionPolicy.releasePage(1, 1.4f, Math.nextUp(-.5f), 4))
        assertEquals(1, PageMotionPolicy.releasePage(1, 1.4f, -.5f, 4))
    }

    @Test fun firstAndLastPageReverseFlingsRetractWithoutWrappingOrSkipping() {
        assertEquals(0, PageMotionPolicy.releasePage(0, .3f, -.5f, 4))
        assertEquals(0, PageMotionPolicy.releasePage(0, -.15f, .5f, 4))
        assertEquals(0, PageMotionPolicy.releasePage(0, -.15f, -8f, 4))
        assertEquals(1, PageMotionPolicy.releasePage(0, .03f, .5f, 4))
        assertEquals(3, PageMotionPolicy.releasePage(3, 2.7f, .5f, 4))
        assertEquals(3, PageMotionPolicy.releasePage(3, 3.15f, -.5f, 4))
        assertEquals(3, PageMotionPolicy.releasePage(3, 3.15f, 8f, 4))
        assertEquals(2, PageMotionPolicy.releasePage(3, 2.97f, -.5f, 4))
    }

    @Test fun grabbingAnEdgeReboundDoesNotApplyResistanceTwiceOrJumpToThePage() {
        for (start in listOf(-.17f, -.05f, 0f, 1.4f, 3f, 3.05f, 3.17f)) {
            assertEquals(start, PageMotionPolicy.dragPosition(start, 0f, 4), .000001f)
            assertEquals(start, PageMotionPolicy.dragPosition(start, .000001f, 4), .000002f)
        }
        assertEquals(1.8f, PageMotionPolicy.dragPosition(1.5f, .3f, 4), .000001f)
    }

    @Test fun interruptedMotionStartsAtCurrentFrameAndFinishesAtRestWithinEdgeBounds() {
        for (start in listOf(-.17f, 0f, .4f, 1.7f, 3f, 3.17f)) for (target in 0..3) {
            for (velocity in listOf(-8f, -.4f, 0f, .4f, 8f)) {
                val curve = PageMotionContinuation(start, target, velocity, 340L, 4)
                assertEquals(start, curve.value(0f), 0f)
                assertEquals(target.toFloat(), curve.value(1f), .000001f)
                repeat(1001) {
                    assertTrue(curve.value(it / 1000f) in -.18f..3.18f)
                }
                assertTrue(abs(curve.value(1f) - curve.value(.9999f)) < .00001f)
            }
        }
        val reverse = PageMotionContinuation(1.5f, 0, .4f, 340L, 4)
        assertTrue(reverse.value(.001f) > 1.5f)
    }

    @Test fun repeatedRetargetsAndInvalidRequestsRemainBoundedAndReachLastSelection() {
        var position = 0f
        repeat(200) { step ->
            val target = PageMotionPolicy.selected(if (step % 2 == 0) 200 else -1, 4)
            val curve = PageMotionContinuation(position, target, if (step % 2 == 0) -3f else 3f, 300L, 4)
            assertEquals(position, curve.value(0f), 0f)
            position = curve.value(.2f)
            assertTrue(position in -.18f..3.18f)
        }
        assertEquals(2f, PageMotionContinuation(position, 2, 0f, 340L, 4).value(1f), 0f)
        assertEquals(0, PageMotionPolicy.selected(4, 0))
        // 页数不再封顶 4（来源工程固定 4 页）：99 页时最后一页是 98。
        assertEquals(98, PageMotionPolicy.selected(99, 99))
        assertEquals(180L, PageMotionPolicy.duration(1f, 1f))
        assertEquals(420L, PageMotionPolicy.duration(-1f, 99f))
    }

    /** 点击切页：从静止起步、非线性（前快后慢），单调无过冲，恰好停在目标。 */
    @Test fun navigationCurveStartsFromRestAcceleratesThenGlidesIntoTarget() {
        for ((from, to) in listOf(0f to 1, 0f to 3, 3f to 0, 2f to 1)) {
            val duration = PageMotionPolicy.navigationDuration(from, to.toFloat())
            val curve = PageMotionContinuation(from, to, 0f, duration, 4, navigation = true)
            assertEquals(from, curve.value(0f), 0f)
            assertEquals(to.toFloat(), curve.value(1f), .00001f)
            val sign = if (to > from) 1f else -1f
            var previous = from
            repeat(1000) {
                val next = curve.value((it + 1) / 1000f)
                assertTrue((next - previous) * sign >= -.000001f)
                previous = next
            }
            // 起点速度为 0（第一毫帧几乎不动），中段过半、前 40% 时间走完大部分：不是线性，也不是对称的 smoothstep。
            assertTrue(abs(curve.value(.001f) - from) < .001f * abs(to - from))
            assertTrue(abs(curve.value(.4f) - from) > .7f * abs(to - from))
        }
    }

    @Test fun fartherJumpsLastLongerAndLaunchHarder() {
        val one = PageMotionPolicy.navigationDuration(0f, 1f)
        val two = PageMotionPolicy.navigationDuration(0f, 2f)
        val three = PageMotionPolicy.navigationDuration(0f, 3f)
        assertTrue(one < two && two < three)
        assertTrue(three <= PageMotionPolicy.NAVIGATION_MAX_MS)
        // 时长增长慢于距离：远跳的额外距离由更陡的起步吸收。
        assertTrue(three < 3 * one)
        assertTrue(PageMotionPolicy.navigationSteepness(3f) > PageMotionPolicy.navigationSteepness(1f))
        assertEquals(PageMotionPolicy.NAVIGATION_MIN_MS, PageMotionPolicy.navigationDuration(Float.NaN, 1f))
    }

    /** 动画途中再次点击：新曲线以当前速度起步，位置与速度都连续。 */
    @Test fun retargetDuringNavigationKeepsVelocityContinuous() {
        val duration = 400L
        val velocity = 4f
        val curve = PageMotionContinuation(1.3f, 3, velocity, duration, 4, navigation = true)
        val dt = .0005f
        val startSpeed = (curve.value(dt) - curve.value(0f)) / (dt * duration / 1000f)
        assertEquals(velocity, startSpeed, .15f)
        for (v in listOf(-8f, 8f)) for (start in listOf(-.17f, 3.17f)) {
            val bounded = PageMotionContinuation(start, 0, v, duration, 4, navigation = true)
            repeat(1001) { assertTrue(bounded.value(it / 1000f) in -.18f..3.18f) }
        }
    }

    /** 中途再点、同向高速接续：导航曲线不越过目标。 */
    @Test fun navigationRetargetNeverOvershootsEvenWithFastSameDirectionVelocity() {
        for (target in 1..3) for (velocity in listOf(.5f, 2f, 4f, 8f)) for (start in listOf(0f, .4f, .9f)) {
            if (start >= target) continue
            val base = PageMotionPolicy.navigationDuration(start, target.toFloat())
            val duration = PageMotionPolicy.handoffDuration(base, start, target.toFloat(), velocity)
            val curve = PageMotionContinuation(start, target, velocity, duration, 4, navigation = true)
            repeat(1001) { assertTrue(curve.value(it / 1000f) <= target + .0001f) }
        }
    }

    /** 离目标近却甩得快：缩短时长让起点速度等于接手速度（不被夹断），反向/静止保持原时长。 */
    @Test fun handoffDurationMatchesTheIncomingVelocity() {
        for (navigation in listOf(false, true)) for (velocity in listOf(3f, 6f, 8f)) {
            val start = .8f
            val base = if (navigation) PageMotionPolicy.navigationDuration(start, 1f)
            else PageMotionPolicy.duration(start, 1f)
            val duration = PageMotionPolicy.handoffDuration(base, start, 1f, velocity)
            assertTrue(duration <= base && duration >= PageMotionPolicy.HANDOFF_MIN_MS)
            val curve = PageMotionContinuation(start, 1, velocity, duration, 4, navigation)
            val dt = .0005f
            val speed = (curve.value(dt) - curve.value(0f)) / (dt * duration / 1000f)
            if (duration > PageMotionPolicy.HANDOFF_MIN_MS) assertEquals(velocity, speed, velocity * .06f)
            repeat(1001) { assertTrue(curve.value(it / 1000f) <= 1.0001f) }
        }
        assertEquals(340L, PageMotionPolicy.handoffDuration(340L, .8f, 1f, -5f))
        assertEquals(340L, PageMotionPolicy.handoffDuration(340L, .8f, 1f, 0f))
        assertEquals(340L, PageMotionPolicy.handoffDuration(340L, 0f, 1f, .5f))
    }

    /** 页数不设上限（适配标准 §15.1）：第 5 页以后的导航、可见性与来源工程的 4 页行为一致。 */
    @Test fun morePagesThanTheSourceProjectBehaveTheSame() {
        assertEquals(9, PageMotionPolicy.lastPage(10))
        assertTrue(PageMotionPolicy.isPageVisible(7, 6.5f, 10))
        assertTrue(PageMotionPolicy.isPageVisible(6, 6.5f, 10))
        assertFalse(PageMotionPolicy.isPageVisible(8, 6.5f, 10))
        var selected = -1
        assertTrue(PageUserNavigation.request(0, 7, 10, onUserInteraction = {}, selectPage = { selected = it }))
        assertEquals(7, selected)
        val far = PageMotionContinuation(0f, 9, 0f, PageMotionPolicy.navigationDuration(0f, 9f), 10, navigation = true)
        assertEquals(9f, far.value(1f), 0f)
        assertTrue(PageMotionPolicy.navigationDuration(0f, 9f) <= PageMotionPolicy.NAVIGATION_MAX_MS)
    }
}
