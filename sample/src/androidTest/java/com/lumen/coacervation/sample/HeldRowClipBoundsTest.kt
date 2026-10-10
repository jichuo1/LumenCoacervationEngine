package com.lumen.coacervation.sample

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.interaction.ElasticMotionPolicy
import com.lumen.coacervation.engine.interaction.ElasticTravelPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Match-parent rows must not acquire a new rectangular cut during a held drag. */
@RunWith(AndroidJUnit4::class)
class HeldRowClipBoundsTest {
    @Test fun matchParentRowStaysInsideRetainedViewportWhileDragging() = runCase(0, 0)

    @Test fun paddedViewportIncludesScrollOffsetInItsBounds() = runCase(12, 12)

    @Test fun partiallyVisibleRowKeepsBaselineCropWithoutFurtherOverflow() = runCase(0, 32)

    @Test fun roundedViewportPreservesItsRealCornerDuringDiagonalDrag() = runCase(0, 0, true)

    private fun runCase(padding: Int, scrollY: Int, rounded: Boolean = false) {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var root: View
            lateinit var row: TextView
            lateinit var content: FrameLayout
            lateinit var viewport: ScrollView
            lateinit var panel: FrameLayout
            lateinit var controller: ElasticInteractionController
            scenario.onActivity { activity ->
                activity.binding.close()
                panel = FrameLayout(activity).apply {
                    tag = ElasticInteractionController.CONTAINER_TAG
                }
                viewport = ScrollView(activity).apply {
                    setPadding(padding, padding, padding, padding)
                    if (rounded) {
                        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 26f }
                        clipToOutline = true
                    }
                }
                // ScrollView measures its child with unspecified height. Keep
                // the content taller than this row, as in the real source list.
                content = FrameLayout(activity).apply { minimumHeight = 400 }
                row = TextView(activity).apply {
                    text = "source row"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f)
                    isClickable = true
                    setPadding(16, 12, 16, 12)
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = 16f
                    }
                }
                content.addView(row, FrameLayout.LayoutParams(300 - padding * 2 - if (rounded) 40 else 0, 80).apply { topMargin = 16; leftMargin = if (rounded) 20 else 0 })
                viewport.addView(content, FrameLayout.LayoutParams(-1, 400))
                panel.addView(viewport, FrameLayout.LayoutParams(300, 120).apply {
                    leftMargin = 80
                    topMargin = 80
                })
                activity.setContentView(panel)
                root = activity.window.decorView
                controller = ElasticInteractionController(root).apply {
                    travelPolicy = ElasticTravelPolicy.PARENT_BOUNDS
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(root.hasWindowFocus())
                assertTrue(row.isLaidOut && !row.isLayoutRequested)
                assertFalse("The fixture text must fit vertically", row.canScrollVertically(1))
                assertFalse("The fixture text must fit horizontally", row.canScrollHorizontally(1))
                viewport.scrollTo(0, scrollY)
                val baselineLeft = content.left - viewport.scrollX + row.left.toFloat()
                val baselineTop = content.top - viewport.scrollY + row.top.toFloat()
                fun assertContained() {
                    val left = baselineLeft + row.translationX + row.pivotX * (1f - row.scaleX)
                    val top = baselineTop + row.translationY + row.pivotY * (1f - row.scaleY)
                    val right = left + row.width * row.scaleX
                    val bottom = top + row.height * row.scaleY
                    assertTrue("Dragged left edge must not enter viewport clipping: $left",
                        left >= minOf(baselineLeft, viewport.paddingLeft.toFloat()) - .01f)
                    assertTrue("Dragged right edge must not enter viewport clipping: $right",
                        right <= maxOf(baselineLeft + row.width,
                            (viewport.width - viewport.paddingRight).toFloat()) + .01f)
                    assertTrue("Dragged top edge must not add to the baseline crop: $top",
                        top >= minOf(baselineTop, viewport.paddingTop.toFloat()) - .01f)
                    assertTrue("Dragged bottom edge must not enter viewport clipping: $bottom",
                        bottom <= maxOf(baselineTop + row.height,
                            (viewport.height - viewport.paddingBottom).toFloat()) + .01f)
                    if (rounded) {
                        for ((x, y) in listOf(left to top, right to top, left to bottom, right to bottom)) {
                            val cx = x.coerceIn(26f, viewport.width - 26f)
                            val cy = y.coerceIn(26f, viewport.height - 26f)
                            val dx = x - cx
                            val dy = y - cy
                            assertTrue("Corner crossed the retained rounded outline",
                                dx * dx + dy * dy <= 26f * 26f + .01f)
                        }
                        assertTrue(viewport.clipToOutline)
                    }
                }
                val position = IntArray(2)
                row.getLocationOnScreen(position)
                val down = SystemClock.uptimeMillis()
                var cancels = 0
                fun send(action: Int, time: Long, dx: Float, dy: Float = 0f) {
                    val event = MotionEvent.obtain(down, time, action,
                        position[0] + 100f + dx, position[1] + 40f + dy, 0)
                    try {
                        controller.dispatch(event) {
                            if (it.actionMasked == MotionEvent.ACTION_DOWN) row.isPressed = true
                            if (it.actionMasked == MotionEvent.ACTION_CANCEL) {
                                row.isPressed = false
                                cancels++
                            }
                            true
                        }
                    } finally {
                        event.recycle()
                    }
                }
                try {
                    send(MotionEvent.ACTION_DOWN, down, 0f)
                    send(MotionEvent.ACTION_MOVE, down + ElasticMotionPolicy.HOLD_MILLIS + 1, -100f)
                    assertEquals("The row must really capture the held drag", 1, cancels)
                    assertContained()
                    for ((index, direction) in listOf(100f to 0f, 0f to -100f,
                            0f to 100f, -100f to -100f, 100f to 100f).withIndex()) {
                        send(MotionEvent.ACTION_MOVE,
                            down + ElasticMotionPolicy.HOLD_MILLIS + 2 + index,
                            direction.first, direction.second)
                        assertContained()
                    }
                    assertFalse(content.clipChildren)
                    assertFalse(content.clipToPadding)
                    assertTrue(viewport.clipChildren)
                    assertTrue(viewport.clipToPadding)
                    assertTrue(panel.clipChildren)
                    assertTrue(panel.clipToPadding)
                    assertEquals(1f, viewport.scaleX, 0f)
                    controller.clear()
                    assertEquals(0f, row.translationX, 0f)
                    assertEquals(1f, row.scaleX, 0f)
                    assertTrue(content.clipChildren)
                    assertTrue(content.clipToPadding)
                } finally {
                    controller.dispose()
                }
            }
        }
    }
}
