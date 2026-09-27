package com.lumen.coacervation.engine.motion

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.motion.morph.ContainerMorphContentTiming
import com.lumen.coacervation.engine.motion.morph.ContainerMorphSpec
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class NavigationMotionPolicyTest {
    @Test fun aBackPressKeepsTouchOwnershipAcrossAnimationCompletionUntilUpOrCancel() {
        assertTrue(InterruptibleMotionPolicy.keepInputBlocked(true, false))
        assertTrue(InterruptibleMotionPolicy.keepInputBlocked(true, true))
        assertTrue(InterruptibleMotionPolicy.keepInputBlocked(false, true))
        assertFalse(InterruptibleMotionPolicy.keepInputBlocked(false, false))
        // If UP starts closing before the press clears, the closing blocker must remain.
        assertTrue(InterruptibleMotionPolicy.keepInputBlocked(true, false))
    }
    @Test fun entryExpandedAndCancelReboundAllowNavigationButNeverBusinessWork() {
        for (phase in InterruptibleMotionPhase.entries) {
            assertFalse(InterruptibleMotionPolicy.canNavigate(phase, true))
            assertEquals(phase == InterruptibleMotionPhase.ENTERING || phase == InterruptibleMotionPhase.EXPANDED ||
                phase == InterruptibleMotionPhase.CANCELLING_BACK, InterruptibleMotionPolicy.canNavigate(phase, false))
        }
    }
    @Test fun onlyUnsettledReusableFramesPreserveTheirOriginalProfile() {
        for (phase in InterruptibleMotionPhase.entries) {
            assertEquals(phase in listOf(InterruptibleMotionPhase.ENTERING, InterruptibleMotionPhase.PREDICTIVE_BACK,
                InterruptibleMotionPhase.CANCELLING_BACK), InterruptibleMotionPolicy.preserveFrame(phase))
        }
    }
    @Test fun sameExpansionCannotBeUsedToSwitchContentProfilesMidAnimation() {
        assertEquals(0f, ContainerMorphSpec.contentFraction(.8f, ContainerMorphContentTiming.TIMED), 0f)
        assertEquals(1f, ContainerMorphSpec.contentFraction(.8f, ContainerMorphContentTiming.PREDICTIVE), 0f)
        for (profile in ContainerMorphContentTiming.entries) {
            val before = ContainerMorphSpec.contentFraction(.8f, profile)
            val continuation = InterruptibleMotionContinuation(.8f, 0f, 1f, 200L)
            assertEquals(before, ContainerMorphSpec.contentFraction(continuation.value(0f), profile), 0f)
        }
    }
    @Test fun interruptedMotionStartsAtTheExactCurrentValueAndEndsAtTheRequestedEndpoint() {
        for (start in listOf(0f, .01f, .3f, .8f, .99f, 1f)) for (target in listOf(0f, 1f)) {
            for (velocity in listOf(-8f, -1f, 0f, 1f, 8f)) {
                val curve = InterruptibleMotionContinuation(start, target, velocity, 320L)
                assertEquals(start, curve.value(0f), 0f)
                assertEquals(target, curve.value(1f), .000001f)
                repeat(1001) { assertTrue(curve.value(it / 1000f) in 0f..1f) }
            }
        }
    }
    @Test fun moderateReversalContinuesVelocityBeforeTurningWithoutAPositionJump() {
        val curve = InterruptibleMotionContinuation(.5f, 0f, .5f, 300L)
        val initialVelocity = (curve.value(.0001f) - .5f) / .00003f
        assertEquals(.5f, initialVelocity, .015f)
        assertTrue(curve.value(.001f) > .5f)
        assertTrue(curve.value(.8f) < .5f)
    }
    @Test fun continuationFinishesAtRestAndDoesNotOvershootAtEitherBoundary() {
        for (target in listOf(0f, 1f)) {
            val curve = InterruptibleMotionContinuation(.5f, target, -2f, 320L)
            assertTrue(abs(curve.value(1f) - curve.value(.9999f)) < .00001f)
        }
        assertEquals(1f, InterruptibleMotionContinuation(1f, 1f, 8f, 200L).value(.5f), 0f)
    }
    @Test fun reboundDurationUsesRemainingDistanceWithABoundedMinimum() {
        assertEquals(210L, InterruptibleMotionPolicy.remainingDuration(210L, 0f, 1f))
        assertEquals(105L, InterruptibleMotionPolicy.remainingDuration(210L, .5f, 1f))
        assertEquals(80L, InterruptibleMotionPolicy.remainingDuration(210L, .99f, 1f))
    }
    @Test fun oldEntryAnimatorAndFinishTokensCannotTakeOverANewerRequest() {
        val session = InterruptibleMotionSession()
        val entry = session.invalidate()
        val animator = session.invalidate()
        assertFalse(session.owns(entry))
        val gesture = session.invalidate()
        assertFalse(session.owns(animator))
        val close = session.invalidate()
        assertFalse(session.owns(gesture))
        assertTrue(session.owns(close))
        session.invalidate()
        assertFalse(session.owns(close))
    }
    @Test fun velocityIsBoundedAndStaleSamplesAreNotReused() {
        val session = InterruptibleMotionSession()
        session.reset(.2f, 1000L)
        session.sample(.3f, 1050L)
        assertEquals(2f, session.velocity(1050L), .00001f)
        assertEquals(0f, session.velocity(1200L), 0f)
        session.sample(1f, 1051L)
        assertEquals(8f, session.velocity(1051L), 0f)
        session.reset(.6f, 2000L)
        assertEquals(0f, session.velocity(2000L), 0f)
    }
    @Test fun sameTimestampUpdatesUseTheLatestVisualPositionForTheNextSample() {
        val session = InterruptibleMotionSession()
        session.reset(.2f, 1000L)
        session.sample(.3f, 1050L)
        session.sample(.4f, 1050L)
        session.sample(.45f, 1100L)
        assertEquals(1f, session.velocity(1100L), .00001f)
    }

    private fun source(name: String): String {
        return MotionSource.file(name)
    }
    // 来源工程两个目标页各有一份编排；抽离后收进 ContainerMorphController 一处。
    @Test fun bothPagesFreezeTheVisualProfileAndInvalidatePostedEntryWork() {
        for (page in listOf("ContainerMorphController")) {
            val code = source(page)
            val prepare = code.after("private fun prepareExitMotion(").before("private fun requestClose(")
            assertTrue(prepare.indexOf("InterruptibleMotionPolicy.preserveFrame(motionState)") < prepare.indexOf("resolveMotionGeometry("))
            assertTrue(prepare.before("resolveMotionGeometry(").contains("return"))
            assertTrue(code.contains("!motionSession.owns(entryToken)"))
            assertTrue(code.contains("!motionSession.owns(finishToken)"))
            assertTrue(code.contains("motionState == MotionState.CLOSING"))
            assertTrue(code.contains("current && !cancelled && !activity.isFinishing && !activity.isDestroyed"))
            assertTrue(code.contains("animator.removeAllListeners()"))
        }
    }
    @Test fun transitionsDoNotAllocateFrameSnapshotsOrRebuildListsPerFrame() {
        for (page in listOf("ContainerMorphController")) {
            val apply = source(page).after("private fun applyMotionExpansion(").before("private fun completeExpandedMotion(")
            for (forbidden in listOf("snapshot()", "Bitmap", "resolveMotionGeometry", "renderHome", "removeAllViews")) {
                assertFalse(forbidden, apply.contains(forbidden))
            }
        }
        // 形变期间延后渲染、业务忙时不许返回，是目标页的义务（适配标准 §13.5）：控制器只提供入口。
        val controller = source("ContainerMorphController")
        assertTrue(controller.contains("private val isBusinessBlocked: () -> Boolean"))
        assertTrue(controller.contains("public val isExpanded: Boolean"))
    }
    @Test fun touchInterceptionOnlyRelaysTheVisibleRegisteredBackControl() {
        val host = source("ContainerMorphHost")
        assertTrue(host.contains("pressedBackTarget === navigationBackTarget"))
        assertTrue(host.contains("MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clearBackPress()"))
        assertTrue(host.contains("currentPage?.alpha"))
        assertTrue(host.contains("event.rawX >= backLocation[0]"))
        assertFalse(host.contains("dispatchTouchEvent(event)"))
        // 页内返回按钮由目标页登记（host.registerNavigationBack），见适配标准 §13.5。
    }
}
