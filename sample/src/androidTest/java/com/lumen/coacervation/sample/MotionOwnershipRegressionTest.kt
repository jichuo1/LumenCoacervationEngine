package com.lumen.coacervation.sample

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.interaction.ElasticMotionPolicy
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.motion.pager.LumenPagePager
import com.lumen.coacervation.engine.motion.pager.PageTextChain
import com.lumen.coacervation.engine.motion.reveal.LumenReveal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real View ownership and animator end behavior; no private-state injection or reflection. */
@RunWith(AndroidJUnit4::class)
class MotionOwnershipRegressionTest {
    @Test fun recapturingADifferentChildReleasesTheOldHighlightAndDetachListener() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var root: View
            lateinit var group: FrameLayout
            lateinit var first: TextView
            lateinit var second: TextView
            lateinit var controller: ElasticInteractionController
            var cancels = 0
            var downTime = 0L
            var eventTime = 0L
            val location = IntArray(2)
            val rootLocation = IntArray(2)
            fun send(view: View, action: Int, dx: Float = 0f) {
                view.getLocationOnScreen(location)
                if (action == MotionEvent.ACTION_DOWN) {
                    downTime = SystemClock.uptimeMillis()
                    eventTime = downTime
                } else eventTime = maxOf(eventTime + 1, downTime + ElasticMotionPolicy.HOLD_MILLIS + 1)
                val event = MotionEvent.obtain(downTime, eventTime, action,
                    location[0] + view.width * .7f + dx, location[1] + view.height * .5f, 0)
                try {
                    controller.dispatch(event) { native ->
                        if (native.actionMasked == MotionEvent.ACTION_CANCEL) cancels++
                        val local = MotionEvent.obtain(native)
                        root.getLocationOnScreen(rootLocation)
                        local.offsetLocation(-rootLocation[0].toFloat(), -rootLocation[1].toFloat())
                        try { root.dispatchTouchEvent(local) } finally { local.recycle() }
                    }
                } finally { event.recycle() }
            }
            scenario.onActivity { activity ->
                activity.binding.close()
                val panel = FrameLayout(activity).apply { tag = ElasticInteractionController.CONTAINER_TAG }
                group = FrameLayout(activity).apply { setBackgroundColor(Color.DKGRAY) }
                fun child(top: Int): TextView = TextView(activity).apply {
                    setBackgroundColor(Color.BLACK)
                    isClickable = true
                    group.addView(this, FrameLayout.LayoutParams(240, 60).apply { topMargin = top })
                }
                first = child(0)
                second = child(60)
                panel.addView(group, FrameLayout.LayoutParams(240, 120).apply { leftMargin = 100; topMargin = 100 })
                activity.setContentView(panel)
                root = activity.window.decorView
                controller = ElasticInteractionController(root,
                    effectTuning = { LumenEffectTuning(dragDeformation = 0f) })
            }
            idle()
            try {
                scenario.onActivity {
                    assertTrue(ValueAnimator.areAnimatorsEnabled())
                    assertTrue(root.hasWindowFocus())
                    send(first, MotionEvent.ACTION_DOWN)
                    assertTrue("The real native child must receive DOWN", first.isPressed)
                    assertFalse("The promoted group has no native pressed semantics", group.isPressed)
                }
                SystemClock.sleep(170)
                scenario.onActivity {
                    assertTrue("The first highlight must be visible before handoff", Color.red(pixel(first)) > 0)
                    send(first, MotionEvent.ACTION_MOVE, 100f)
                    assertEquals("The held drag must send a real CANCEL", 1, cancels)
                    send(first, MotionEvent.ACTION_UP, 100f)
                    send(second, MotionEvent.ACTION_DOWN)
                    assertEquals("Old child overlay must be returned during handoff", Color.BLACK, pixel(first))
                    assertTrue(second.isPressed)
                    assertEquals(1f, group.scaleX, 0f)
                    assertEquals(0f, group.translationX, 0f)
                }
                await(scenario) { Color.red(pixel(second)) > 0 }
                scenario.onActivity {
                    // Avoid a legitimate geometry invalidation: removing the unrelated child
                    // in-layout leaves the new hit path and group bounds unchanged.
                    group.removeViewInLayout(first)
                    assertTrue("Old child's detach listener must not clear the new press", Color.red(pixel(second)) > 0)
                    send(second, MotionEvent.ACTION_CANCEL)
                    assertEquals(Color.BLACK, pixel(second))
                    assertEquals(1f, group.scaleX, 0f)
                }
            } finally { scenario.onActivity { controller.dispose() } }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun removingAndReusingTheTailReturnsOnlyTheChainsBorrowedMatrixAndClipFlags() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var pager: LumenPagePager
            lateinit var chain: PageTextChain
            lateinit var page: FrameLayout
            lateinit var card: FrameLayout
            lateinit var row: FrameLayout
            lateinit var text: TextView
            val original = Matrix().apply { setTranslate(0f, 6f) }
            val foreign = Matrix().apply { setTranslate(99f, 5f) }
            scenario.onActivity { activity ->
                activity.binding.close()
                pager = LumenPagePager(activity)
                pager.addView(FrameLayout(activity), FrameLayout.LayoutParams(-1, -1))
                page = FrameLayout(activity)
                card = FrameLayout(activity)
                row = FrameLayout(activity).apply { clipToPadding = false }
                text = TextView(activity).apply { this.text = "tail"; animationMatrix = original }
                row.addView(text, FrameLayout.LayoutParams(160, 60).apply { leftMargin = 20 })
                card.addView(row, FrameLayout.LayoutParams(200, 60).apply { leftMargin = 20; topMargin = 40 })
                page.addView(card, FrameLayout.LayoutParams(300, 200).apply { leftMargin = 20; topMargin = 40 })
                pager.addView(page, FrameLayout.LayoutParams(-1, -1))
                val root = FrameLayout(activity)
                root.addView(pager, FrameLayout.LayoutParams(360, 400))
                activity.setContentView(root)
                chain = PageTextChain(pager, emptyList())
                pager.onPositionChanged = chain::onPositionChanged
            }
            idle()
            try {
                scenario.onActivity { assertTrue(ValueAnimator.areAnimatorsEnabled()); pager.selectPage(1) }
                await(scenario) { text.animationMatrix != original && !card.clipChildren && !card.clipToPadding }
                scenario.onActivity {
                    assertFalse(row.clipToPadding)
                    pager.removeView(page)
                }
                await(scenario) { text.animationMatrix == original && card.clipChildren && card.clipToPadding }
                scenario.onActivity {
                    assertFalse("Initially disabled padding clipping was never borrowed", row.clipToPadding)
                    assertEquals(0f, text.translationX, 0f)
                    pager.addView(page, FrameLayout.LayoutParams(-1, -1))
                    pager.selectPage(1)
                }
                await(scenario) { text.animationMatrix != original && !card.clipChildren }
                scenario.onActivity {
                    text.animationMatrix = foreign
                    pager.removeView(page)
                }
                await(scenario) { card.clipChildren && card.clipToPadding }
                scenario.onActivity {
                    assertEquals("A later matrix owner keeps its transform", foreign, text.animationMatrix)
                    text.animationMatrix = original
                    pager.addView(page, FrameLayout.LayoutParams(-1, -1))
                    pager.selectPage(1)
                }
                await(scenario) { text.animationMatrix != original && !card.clipChildren }
                scenario.onActivity {
                    chain.dispose()
                    chain.dispose()
                    chain.onPositionChanged()
                    assertEquals(original, text.animationMatrix)
                    assertTrue(card.clipChildren && card.clipToPadding)
                    assertFalse(row.clipToPadding)
                }
            } finally { scenario.onActivity { chain.dispose() } }
        }
    }

    @Test fun anAlreadySettledVerticalAxisWaitsForTheHorizontalNaturalEndpoint() = revealCase(true, false)

    @Test fun aBlockedRelativeScrollerWaitsForTheHorizontalNaturalEndpoint() = revealCase(false, false)

    @Test fun cancellingMidHorizontalRevealPreservesTheRenderedPositionAndSuppressesCompletion() = revealCase(true, true)

    private fun revealCase(absolute: Boolean, cancelMidway: Boolean) {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var outer: ViewGroup
            lateinit var horizontal: HorizontalScrollView
            lateinit var target: View
            lateinit var reveal: LumenReveal
            var expectedX = 0
            var completed = 0
            var callbackX = -1
            scenario.onActivity { activity ->
                activity.binding.close()
                outer = if (absolute) ScrollView(activity) else FrameLayout(activity)
                val content = FrameLayout(activity).apply { minimumHeight = 400 }
                horizontal = HorizontalScrollView(activity)
                // HorizontalScrollView measures its child with an unspecified horizontal size.
                val strip = FrameLayout(activity).apply { minimumWidth = 800 }
                target = View(activity).apply { setBackgroundColor(Color.RED) }
                strip.addView(target, FrameLayout.LayoutParams(60, 60).apply { leftMargin = 650 })
                horizontal.addView(strip, FrameLayout.LayoutParams(800, 60))
                content.addView(horizontal, FrameLayout.LayoutParams(240, 60))
                outer.addView(content, FrameLayout.LayoutParams(300, 400))
                val root = FrameLayout(activity)
                root.addView(outer, FrameLayout.LayoutParams(300, 260))
                activity.setContentView(root)
                reveal = LumenReveal(Color.GREEN)
            }
            idle()
            try {
                scenario.onActivity {
                    assertTrue(ValueAnimator.areAnimatorsEnabled())
                    expectedX = (target.right - horizontal.width + (12 * horizontal.resources.displayMetrics.density).toInt())
                        .coerceAtMost(horizontal.getChildAt(0).width - horizontal.width)
                    assertTrue(expectedX > 0)
                    assertEquals(0, outer.scrollY)
                    reveal.reveal(outer, target, highlight = false, afterReveal = {
                        completed++
                        callbackX = horizontal.scrollX
                    })
                }
                if (cancelMidway) {
                    await(scenario) { horizontal.scrollX in 1 until expectedX }
                    var stoppedAt = -1
                    scenario.onActivity { stoppedAt = horizontal.scrollX; reveal.cancel() }
                    SystemClock.sleep(LumenReveal.SCROLL_DURATION_MS + 100)
                    scenario.onActivity {
                        assertEquals(stoppedAt, horizontal.scrollX)
                        assertEquals(0, completed)
                        assertFalse(reveal.isActive)
                    }
                } else {
                    await(scenario) { completed == 1 }
                    scenario.onActivity {
                        assertEquals("afterReveal must observe the actual horizontal final frame", expectedX, callbackX)
                        assertEquals(expectedX, horizontal.scrollX)
                        assertEquals(0, outer.scrollY)
                        assertFalse(reveal.isActive)
                    }
                }
            } finally { scenario.onActivity { reveal.cancel() } }
        }
    }

    private fun pixel(view: View): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap.getPixel((view.width * .7f).toInt(), view.height / 2).also { bitmap.recycle() }
    }

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    private fun await(scenario: ActivityScenario<SurfaceSandboxActivity>, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            scenario.onActivity { done = predicate() }
            if (done) return
            SystemClock.sleep(16)
        }
        fail("The motion fixture did not reach the expected state")
    }
}
