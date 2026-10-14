package com.lumen.coacervation.engine.motion.reveal

import org.junit.Assert.*
import org.junit.Test

class RevealCompletionTest {
    @Test fun eitherMovingAxisKeepsTheRequestAndDoesNotCancelItsRenderedScroll() {
        for ((vertical, horizontal) in listOf(false to false, false to true, true to false)) {
            val request = RevealRequest()
            var horizontalPosition = 37
            var released = 0
            val token = request.begin { released++ }
            assertFalse(request.completeWhenSettled(token, vertical, horizontal))
            assertTrue(request.owns(token))
            assertEquals(0, released)
            // The outstanding animator can continue to its natural endpoint before completion.
            horizontalPosition += 63
            assertTrue(request.completeWhenSettled(token, true, true))
            assertEquals(100, horizontalPosition)
            assertEquals(1, released)
            assertFalse(request.isActive)
            assertTrue(request.isCompleted(token))
            assertFalse(request.completeWhenSettled(token, true, true))
        }
    }

    @Test fun explicitCancellationAtMidFrameStillStopsAndInvalidatesOldEndCallbacks() {
        val request = RevealRequest()
        var moving = true
        var position = 37
        val token = request.begin { moving = false }
        assertFalse(request.completeWhenSettled(token, true, false))
        request.cancel()
        if (moving) position = 100
        assertEquals(37, position)
        assertFalse(request.completeWhenSettled(token, true, true))
        assertFalse(request.isCompleted(token))
    }

    @Test fun completionHandoffIsRevokedByCancellationOrReplacementBeforeThePostedCallback() {
        for (replacement in listOf(false, true)) {
            val request = RevealRequest()
            val old = request.begin {}
            assertTrue(request.completeWhenSettled(old, true, true))
            assertTrue(request.isCompleted(old))
            if (replacement) {
                val next = request.begin {}
                assertTrue(request.owns(next))
                assertFalse(request.completeWhenSettled(old, true, true))
            } else request.cancel()
            assertFalse(request.isCompleted(old))
        }
    }

    @Test fun completionReleaseReentrantBeginKeepsTheNewRequestAndRevokesTheOldHandoff() {
        val request = RevealRequest()
        var oldReleases = 0
        var nextReleases = 0
        var next = -1L
        val old = request.begin {
            oldReleases++
            next = request.begin { nextReleases++ }
        }
        assertTrue(request.completeWhenSettled(old, true, true))
        assertEquals(1, oldReleases)
        assertEquals(0, nextReleases)
        assertTrue(request.isActive)
        assertTrue(request.owns(next))
        assertFalse(request.isCompleted(old))
        assertFalse(request.complete(old))
        request.cancel()
        assertEquals(1, nextReleases)
        assertFalse(request.isCompleted(next))
    }

    @Test fun completionReleaseReentrantCancelRevokesTheCompletionWithoutReleasingTwice() {
        val request = RevealRequest()
        var releases = 0
        val token = request.begin {
            releases++
            request.cancel()
        }
        assertTrue(request.complete(token))
        assertEquals(1, releases)
        assertFalse(request.isActive)
        assertFalse(request.isCompleted(token))
        assertFalse(request.complete(token))
        request.cancel()
        assertEquals(1, releases)
    }
}
