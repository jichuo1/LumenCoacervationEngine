package com.lumen.coacervation.sample

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.controls.LumenControls
import com.lumen.coacervation.engine.motion.reveal.LumenReveal
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public scroller ownership and delivery geometry; no private fields or synthetic input. */
@RunWith(AndroidJUnit4::class)
class RevealHorizontalOwnershipTest {
    private class Fixture(activity: SurfaceSandboxActivity, recycler: Boolean = false, targetWidth: Int = 60) {
        val target = View(activity).apply { setBackgroundColor(Color.RED) }
        val horizontal: ViewGroup
        val outer = ScrollView(activity)
        val reveal = LumenReveal(Color.GREEN)
        var completions = 0
        var callbackLeft = Int.MIN_VALUE
        var callbackRight = Int.MIN_VALUE
        val rect = Rect()

        init {
            activity.binding.close()
            if (recycler) {
                horizontal = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                    adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 8
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                            val card = FrameLayout(parent.context).apply {
                                layoutParams = RecyclerView.LayoutParams(600, 80)
                            }
                            return object : RecyclerView.ViewHolder(card) {}
                        }
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                            val card = holder.itemView as FrameLayout
                            card.removeAllViews()
                            if (position == 0) card.addView(target, FrameLayout.LayoutParams(targetWidth, 60).apply {
                                leftMargin = 320
                            })
                        }
                    }
                }
            } else {
                horizontal = HorizontalScrollView(activity).apply {
                    val strip = FrameLayout(activity).apply { minimumWidth = 1200 }
                    strip.addView(target, FrameLayout.LayoutParams(targetWidth, 60).apply { leftMargin = 650 })
                    addView(strip, FrameLayout.LayoutParams(1200, 80))
                }
            }
            val content = FrameLayout(activity).apply {
                minimumHeight = 400
                addView(horizontal, FrameLayout.LayoutParams(240, 80))
            }
            outer.addView(content, FrameLayout.LayoutParams(300, 400))
            val root = FrameLayout(activity).apply { addView(outer, FrameLayout.LayoutParams(300, 260)) }
            activity.setContentView(root)
        }

        fun start() {
            reveal.reveal(outer, target, highlight = false, afterReveal = {
                completions++
                target.getDrawingRect(rect)
                horizontal.offsetDescendantRectToMyCoords(target, rect)
                rect.offset(-horizontal.scrollX, 0)
                callbackLeft = rect.left
                callbackRight = rect.right
            })
        }

        fun assertVisibleCompletion() {
            assertEquals(1, completions)
            assertTrue("Completion observes the actual visible left edge", callbackLeft >= horizontal.paddingLeft)
            assertTrue("Completion observes the actual visible right edge", callbackRight <= horizontal.width - horizontal.paddingRight)
            assertFalse(reveal.isActive)
        }

        /** Identifies the final frame so a preceding pre-draw listener can inject a queue race. */
        fun expectedHorizontalX(): Int {
            val margin = (12 * horizontal.resources.displayMetrics.density).toInt()
            val viewportWidth = horizontal.width - horizontal.paddingLeft - horizontal.paddingRight
            return if (target.width + 2 * margin >= viewportWidth) {
                target.left - horizontal.paddingLeft - minOf(margin, (viewportWidth - target.width).coerceAtLeast(0) / 2)
            } else target.right - horizontal.width + horizontal.paddingRight + margin
        }
    }

    @Test fun platformFlingStopsBeforeRevealAndCannotPullItsCompletedPositionBack() {
        withFixture { scenario, fixture ->
            scenario.onActivity {
                assumeTrue(ValueAnimator.areAnimatorsEnabled())
                val host = fixture.horizontal as HorizontalScrollView
                host.scrollTo(120, 0)
                host.fling(4000)
                fixture.start()
            }
            await(scenario) { fixture.completions == 1 }
            var arrived = -1
            scenario.onActivity { fixture.assertVisibleCompletion(); arrived = fixture.horizontal.scrollX }
            SystemClock.sleep(350)
            scenario.onActivity { assertEquals("The old platform scroller was aborted", arrived, fixture.horizontal.scrollX) }
        }
    }

    @Test fun recyclerViewFlingHandsOffThroughTheOptionalControlsAdapter() {
        withFixture(recycler = true) { scenario, fixture ->
            var stops = 0
            scenario.onActivity {
                fixture.reveal.setHorizontalScrollMotionStopper { host -> stops++; LumenControls.stopScroll(host) }
                val host = fixture.horizontal as RecyclerView
                assertTrue(host.fling(5000, 0))
                fixture.start()
            }
            await(scenario) { fixture.completions == 1 }
            scenario.onActivity {
                fixture.assertVisibleCompletion()
                assertEquals(1, stops)
                assertEquals(RecyclerView.SCROLL_STATE_IDLE, (fixture.horizontal as RecyclerView).scrollState)
            }
        }
    }

    @Test fun aPositionChangeBetweenPredrawAndDeliveryContinuesTheSameReveal() {
        withFixture { scenario, fixture ->
            var injected = false
            var activeDuringDrift = false
            var completionsDuringDrift = -1
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            scenario.onActivity {
                val host = fixture.horizontal as HorizontalScrollView
                val expected = fixture.expectedHorizontalX()
                listener = ViewTreeObserver.OnPreDrawListener {
                    if (!injected && fixture.reveal.isActive && host.scrollX == expected) {
                        injected = true
                        // This listener was installed before reveal's listener, so this post
                        // precedes its settled delivery on the same UI queue.
                        fixture.outer.post {
                            host.scrollBy(200, 0)
                            activeDuringDrift = fixture.reveal.isActive
                            completionsDuringDrift = fixture.completions
                        }
                    }
                    true
                }
                fixture.outer.viewTreeObserver.addOnPreDrawListener(listener)
                fixture.start()
            }
            try {
                await(scenario) { fixture.completions == 1 }
                scenario.onActivity {
                    assertTrue(injected)
                    assertTrue("The original request still owns its posted handoff", activeDuringDrift)
                    assertEquals(0, completionsDuringDrift)
                    fixture.assertVisibleCompletion()
                }
            } finally {
                scenario.onActivity {
                    fixture.outer.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
                }
            }
        }
    }

    @Test fun aFailingOptionalStopperCancelsWithoutNotifyingCompletion() {
        withFixture(recycler = true) { scenario, fixture ->
            var called = false
            scenario.onActivity {
                fixture.reveal.setHorizontalScrollMotionStopper {
                    called = true
                    throw IllegalStateException("fixture stop failure")
                }
                fixture.start()
            }
            await(scenario) { called && !fixture.reveal.isActive }
            scenario.onActivity { assertEquals(0, fixture.completions) }
        }
    }

    @Test fun postedVerticalDriftRecoversAnAlreadyAlignedAxis() = postedVerticalDrift(initiallyAligned = true)

    @Test fun postedVerticalDriftRecoversAnAxisWhoseOldAnimatorAlreadyEnded() = postedVerticalDrift(initiallyAligned = false)

    private fun postedVerticalDrift(initiallyAligned: Boolean) {
        withFixture { scenario, fixture ->
            val destination = if (initiallyAligned) 0 else 80
            var injected = false
            var activeDuringDrift = false
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            scenario.onActivity {
                val params = fixture.horizontal.layoutParams as FrameLayout.LayoutParams
                params.topMargin = destination
                fixture.horizontal.layoutParams = params
            }
            await(scenario) { !fixture.outer.isLayoutRequested && !fixture.target.isLayoutRequested }
            scenario.onActivity {
                val host = fixture.horizontal as HorizontalScrollView
                val expectedX = fixture.expectedHorizontalX()
                listener = ViewTreeObserver.OnPreDrawListener {
                    if (!injected && fixture.reveal.isActive && host.scrollX == expectedX &&
                        fixture.outer.scrollY == destination) {
                        injected = true
                        fixture.outer.post {
                            fixture.outer.scrollTo(0, destination + 40)
                            activeDuringDrift = fixture.reveal.isActive
                        }
                    }
                    true
                }
                fixture.outer.viewTreeObserver.addOnPreDrawListener(listener)
                fixture.start()
            }
            try {
                await(scenario) { fixture.completions == 1 }
                scenario.onActivity {
                    assertTrue(injected)
                    assertTrue(activeDuringDrift)
                    assertEquals(destination, fixture.outer.scrollY)
                    fixture.assertVisibleCompletion()
                }
            } finally {
                scenario.onActivity {
                    fixture.outer.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
                }
            }
        }
    }

    @Test fun aReplacementStartedInsideTheStopperSurvivesTheOldStoppersFailure() {
        withFixture(recycler = true) { scenario, fixture ->
            var first = true
            var replacementCompletions = 0
            scenario.onActivity {
                fixture.reveal.setHorizontalScrollMotionStopper { host ->
                    if (first) {
                        first = false
                        fixture.reveal.reveal(fixture.outer, fixture.target, highlight = false,
                            afterReveal = { replacementCompletions++ })
                        throw IllegalStateException("old fixture stop failure after replacement")
                    }
                    LumenControls.stopScroll(host)
                }
                fixture.start()
            }
            await(scenario) { replacementCompletions == 1 }
            scenario.onActivity {
                assertEquals(0, fixture.completions)
                assertFalse(fixture.reveal.isActive)
            }
        }
    }

    @Test fun detachingTheTargetStopsTheOwnedHorizontalPlan() {
        withFixture { scenario, fixture ->
            scenario.onActivity { assumeTrue(ValueAnimator.areAnimatorsEnabled()); fixture.start() }
            await(scenario) { fixture.horizontal.scrollX > 0 && fixture.completions == 0 }
            var stoppedAt = -1
            scenario.onActivity {
                (fixture.target.parent as ViewGroup).removeView(fixture.target)
                stoppedAt = fixture.horizontal.scrollX
            }
            await(scenario) { !fixture.reveal.isActive }
            SystemClock.sleep(300)
            scenario.onActivity {
                assertEquals(stoppedAt, fixture.horizontal.scrollX)
                assertEquals(0, fixture.completions)
            }
        }
    }

    @Test fun oversizedTargetConvergesAtItsStartEdge() {
        withFixture(targetWidth = 500) { scenario, fixture ->
            scenario.onActivity { fixture.start() }
            await(scenario) { fixture.completions == 1 }
            scenario.onActivity {
                assertEquals(0, fixture.callbackLeft)
                assertEquals(500, fixture.callbackRight)
                assertFalse(fixture.reveal.isActive)
            }
        }
    }

    private fun withFixture(
        recycler: Boolean = false,
        targetWidth: Int = 60,
        test: (ActivityScenario<SurfaceSandboxActivity>, Fixture) -> Unit
    ) {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, recycler, targetWidth) }
            await(scenario) { fixture.outer.isLaidOut && fixture.target.isAttachedToWindow && fixture.target.width > 0 }
            try { test(scenario, fixture) } finally { scenario.onActivity { fixture.reveal.cancel() } }
        }
    }

    private fun await(scenario: ActivityScenario<SurfaceSandboxActivity>, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            scenario.onActivity { done = ready() }
            if (done) return
            SystemClock.sleep(16)
        }
        fail("The horizontal reveal did not reach the expected state")
    }
}
