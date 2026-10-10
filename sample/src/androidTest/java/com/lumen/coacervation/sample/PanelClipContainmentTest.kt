package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import com.lumen.coacervation.engine.motion.modal.IconAnchoredMotionLayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 验证真实 View 裁剪与像素，不以稳定态 outline 属性代替绘制结果。 */
@RunWith(AndroidJUnit4::class)
class PanelClipContainmentTest {
    @Test fun restingPanelMasksOverflowAndTracksContentRelayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val layer = IconAnchoredMotionLayer(context, ColorDrawable(Color.WHITE),
                surfaceRadiusPx = 16f).apply { setBackgroundColor(Color.WHITE) }
            val card = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
            card.addView(View(context).apply { setBackgroundColor(Color.RED) },
                FrameLayout.LayoutParams(220, 220).apply { leftMargin = -10; topMargin = -10 })
            layer.addView(card, FrameLayout.LayoutParams(120, 100).apply { leftMargin = 30; topMargin = 20 })
            fun layout() {
                layer.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY))
                layer.layout(0, 0, 240, 260)
            }
            fun pixel(x: Int, y: Int): Int {
                val bitmap = Bitmap.createBitmap(240, 260, Bitmap.Config.ARGB_8888)
                layer.draw(Canvas(bitmap))
                return bitmap.getPixel(x, y).also { bitmap.recycle() }
            }
            layout()
            layer.applyFrame(20f, 10f, 160f, 130f, 16f)
            layer.clearShape()
            assertEquals(Color.RED, pixel(60, 60))
            assertEquals("Hidden child must stay outside the card", Color.WHITE, pixel(180, 60))
            assertEquals("Resting rounded corner must still mask content", Color.WHITE, pixel(31, 21))
            card.layoutParams.height = 160
            card.requestLayout()
            layout()
            assertEquals("Expanded content must not retain the old terminal clip", Color.RED, pixel(60, 150))
            assertEquals(Color.WHITE, pixel(60, 200))
        }
    }

    @Test fun heldDragPreservesViewportAndRestoresOnlyInnerClipReliefs() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var root: View
            lateinit var target: TextView
            lateinit var wrapper: FrameLayout
            lateinit var content: FrameLayout
            lateinit var viewport: ScrollView
            lateinit var panel: FrameLayout
            lateinit var controller: ElasticInteractionController
            scenario.onActivity { activity ->
                activity.binding.close()
                panel = FrameLayout(activity).apply { tag = ElasticInteractionController.CONTAINER_TAG }
                viewport = ScrollView(activity)
                content = FrameLayout(activity).apply { minimumHeight = 400 }
                wrapper = FrameLayout(activity).apply { setBackgroundColor(Color.RED) }
                target = TextView(activity).apply {
                    text = "long control"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f)
                    isClickable = true
                }
                wrapper.addView(target, FrameLayout.LayoutParams(280, 80))
                content.addView(wrapper, FrameLayout.LayoutParams(280, 80))
                viewport.addView(content, FrameLayout.LayoutParams(300, 400))
                panel.addView(viewport, FrameLayout.LayoutParams(300, 120).apply {
                    leftMargin = 80
                    topMargin = 80
                })
                activity.setContentView(panel)
                root = activity.window.decorView
                controller = ElasticInteractionController(root)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(root.hasWindowFocus())
                assertTrue(target.isLaidOut && !target.isLayoutRequested)
                val location = IntArray(2)
                target.getLocationOnScreen(location)
                val down = SystemClock.uptimeMillis()
                var cancels = 0
                fun send(action: Int, time: Long, dx: Float) {
                    val event = MotionEvent.obtain(down, time, action,
                        location[0] + 60f + dx, location[1] + 40f, 0)
                    try {
                        controller.dispatch(event) {
                            if (it.actionMasked == MotionEvent.ACTION_DOWN) target.isPressed = true
                            if (it.actionMasked == MotionEvent.ACTION_CANCEL) { target.isPressed = false; cancels++ }
                            true
                        }
                    } finally { event.recycle() }
                }
                send(MotionEvent.ACTION_DOWN, down, 0f)
                send(MotionEvent.ACTION_MOVE, down + ElasticMotionPolicy.HOLD_MILLIS + 1, 100f)
                assertEquals("Controller must capture the actual held drag", 1, cancels)
                assertFalse(content.clipChildren)
                assertFalse(content.clipToPadding)
                assertTrue(viewport.clipChildren)
                assertTrue(viewport.clipToPadding)
                assertTrue(panel.clipChildren)
                assertTrue(panel.clipToPadding)
                assertEquals(1f, viewport.scaleX, 0f)
                controller.clear()
                assertTrue(content.clipChildren)
                assertTrue(content.clipToPadding)
                controller.dispose()
                assertTrue(viewport.clipChildren)
            }
        }
    }
}
