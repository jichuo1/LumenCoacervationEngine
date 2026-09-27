package com.lumen.coacervation.engine.motion.reveal

import org.junit.Assert.*
import org.junit.Test

class SettingsRevealScrollMotionTest {
    @Test fun cancellingAnAlreadyMovingRequestStopsAtItsRenderedFrame() {
        for (reason in listOf("same tab", "pause", "replacement", "touch", "keyboard", "accessibility")) {
            var position = 100
            val motion = RevealScrollMotion({ position }, { position = it })
            val request = RevealRequest()
            val token = request.begin(motion::cancel)
            val scroll = motion.retarget(1100)
            assertTrue(motion.frame(scroll, .25f))
            assertEquals(350, position)
            request.cancel()
            for (frame in 26..100) assertFalse(motion.frame(scroll, frame / 100f))
            assertEquals(reason, 350, position)
            assertFalse(request.complete(token))
            // User/native scrolling after cancellation is never overwritten by an old frame.
            position = 500
            assertFalse(motion.frame(scroll, 1f))
            assertEquals(500, position)
        }
    }

    @Test fun aNewRequestStartsFromCurrentPositionAndOldFramesCannotCompleteIt() {
        var position = 0
        val motion = RevealScrollMotion({ position }, { position = it })
        val old = motion.retarget(1000)
        motion.frame(old, .4f)
        motion.cancel()
        val replacement = motion.retarget(200)
        motion.frame(replacement, .5f)
        assertEquals(300, position)
        assertFalse(motion.frame(old, 1f))
        assertEquals(300, position)
        motion.frame(replacement, 1f)
        assertEquals(200, position)
    }

    @Test fun geometryChangesRetargetToTheNewClampedEndWithoutOldEndpointJump() {
        var position = 0
        val motion = RevealScrollMotion({ position }, { position = it })
        val old = motion.retarget(2000)
        motion.frame(old, .4f)
        val resized = motion.retarget(600)
        motion.frame(resized, 0f)
        assertEquals(800, position)
        assertFalse(motion.frame(old, 1f))
        motion.frame(resized, .5f)
        assertEquals(700, position)
        motion.frame(resized, 1f)
        assertEquals(600, position)
    }

    @Test fun immediateCompletionAndLargeDistancesStayWithinTheRequestedRange() {
        var position = 0
        val motion = RevealScrollMotion({ position }, { position = it })
        val token = motion.retarget(Int.MAX_VALUE)
        motion.frame(token, .5f)
        assertTrue(position > 0)
        motion.frame(token, 1f)
        assertEquals(Int.MAX_VALUE, position)
        val instant = motion.retarget(0)
        motion.frame(instant, 1f)
        assertEquals(0, position)
    }
}
