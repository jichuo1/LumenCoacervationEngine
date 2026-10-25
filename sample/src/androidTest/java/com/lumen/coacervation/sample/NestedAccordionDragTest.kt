package com.lumen.coacervation.sample

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.interaction.ElasticTravelPolicy
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.motion.expansion.SectionExpansionController
import com.lumen.coacervation.engine.motion.pager.LumenPagePager
import com.lumen.coacervation.engine.motion.pager.LumenPageScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 多级手风琴：首级展开后整卡远高于视口。首级标题的按压下沉、跟手方向与可见段拉伸，
 * 必须与次级标题（整卡可见）同一量级；按屏幕上实际绘制的位置判断，不看 translation 符号。
 */
@RunWith(AndroidJUnit4::class)
class NestedAccordionDragTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Test fun pagedOuterHeaderDeformsLikeItsInnerHeader() = withFixture(paged = true) { compare(it) }

    @Test fun plainScrollOuterHeaderDeformsLikeItsInnerHeader() = withFixture(paged = false) { compare(it) }

    @Test fun neighborPolicyKeepsTheOuterHeaderResponse() = withFixture(paged = true) { f ->
        // 次级卡片朝相邻卡片的行程本就让位给邻居（拉伸随行程变小），这里只比首级。
        // 首级上方留有间距、下邻居远在屏幕外：向下必须与只看父容器时同样满额。
        for (sign in listOf(1f, -1f)) {
            WindowRegressionCapture.main { f.controller.travelPolicy = ElasticTravelPolicy.PARENT_BOUNDS }
            val parent = drag(f, inner = false, dx = 0f, dy = sign * f.dp(80))
            WindowRegressionCapture.main { f.controller.travelPolicy = ElasticTravelPolicy.AVOID_NEIGHBORS }
            val neighbor = drag(f, inner = false, dx = 0f, dy = sign * f.dp(80))
            checkFollows(f, neighbor, sign)
            if (sign > 0) assertEquals("An offscreen neighbour must not shorten the travel: parent=$parent neighbor=$neighbor",
                parent.dragY, neighbor.dragY, 1f)
        }
    }

    @Test fun horizontalDragDoesNotSinkTheOuterHeader() = withFixture(paged = true) { f ->
        val result = drag(f, inner = false, dx = f.dp(80).toFloat(), dy = 0f)
        assertTrue("A sideways drag must not push the visible header toward an offscreen centre: $result",
            abs(result.dragY) < f.dp(10))
        assertTrue("The header must still follow sideways: $result", result.dragX > f.dp(4))
    }

    private fun compare(f: Fixture) {
        for (sign in listOf(1f, -1f)) {
            val outer = drag(f, inner = false, dx = 0f, dy = sign * f.dp(80))
            val inner = drag(f, inner = true, dx = 0f, dy = sign * f.dp(80))
            checkFollows(f, outer, sign)
            checkFollows(f, inner, sign)
            assertTrue("Outer response must match the inner one: outer=$outer inner=$inner",
                abs(outer.dragY) > abs(inner.dragY) * .6f)
        }
    }

    private fun checkFollows(f: Fixture, result: Drag, sign: Float) {
        assertTrue("Holding must not sink the header by a far pivot: $result", abs(result.pressY) < f.dp(8))
        assertTrue("The drawn header must follow the finger: $result", result.dragY * sign > f.dp(4))
        assertTrue("The visible segment must stretch along the drag: $result", result.stretch > 2f)
    }

    private data class Drag(val inner: Boolean, val pressY: Float, val dragX: Float, val dragY: Float,
                            val stretch: Float, val scaleY: Float, val translationY: Float)

    private fun drag(f: Fixture, inner: Boolean, dx: Float, dy: Float): Drag {
        val header = if (inner) f.innerHeader else f.outerHeader
        val group = if (inner) f.inner else f.outer
        val marker = if (inner) f.innerMarker else f.outerMarker
        val location = IntArray(2)
        val rest = FloatArray(3)
        val pressed = FloatArray(3)
        val dragged = FloatArray(3)
        fun sample(out: FloatArray) {
            header.getLocationOnScreen(location)
            out[0] = location[0].toFloat(); out[1] = location[1].toFloat()
            marker.getLocationOnScreen(location)
            out[2] = location[1] - out[1]
        }
        WindowRegressionCapture.main { sample(rest) }
        val x = rest[0] + header.width / 2f
        val y = rest[1] + header.height / 2f
        val down = SystemClock.uptimeMillis()
        val cancels = f.cancels
        try {
            send(down, MotionEvent.ACTION_DOWN, x, y)
            SystemClock.sleep(450)
            WindowRegressionCapture.frame(f.root)
            WindowRegressionCapture.main {
                assertTrue("The real press must deform the chosen card", group.scaleY < .9999f)
                sample(pressed)
            }
            send(down, MotionEvent.ACTION_MOVE, x + dx, y + dy)
            WindowRegressionCapture.frame(f.root)
            lateinit var result: Drag
            WindowRegressionCapture.main {
                sample(dragged)
                assertEquals("Capture must send exactly one native CANCEL", 1, f.cancels - cancels)
                result = Drag(inner, pressed[1] - rest[1], dragged[0] - pressed[0], dragged[1] - pressed[1],
                    dragged[2] - rest[2], group.scaleY, group.translationY)
                Log.i("LumenNestedDrag", "paged=${f.paged} height=${group.height} $result")
                assertEquals(0, f.clicks)
            }
            return result
        } finally {
            send(down, MotionEvent.ACTION_CANCEL, x + dx, y + dy)
            WindowRegressionCapture.main { f.controller.clear() }
            WindowRegressionCapture.frame(f.root)
            WindowRegressionCapture.main {
                assertEquals(0f, group.translationY, 0f); assertEquals(1f, group.scaleY, 0f)
                assertNull(group.clipBounds)
            }
        }
    }

    private fun withFixture(paged: Boolean, check: (Fixture) -> Unit) {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, paged) }
            try {
                WindowRegressionCapture.frame(fixture.root)
                check(fixture)
            } finally {
                WindowRegressionCapture.main { fixture.close() }
            }
        }
    }

    private class Fixture(val activity: SurfaceSandboxActivity, val paged: Boolean) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt().coerceAtLeast(1)
        private fun card() = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() }
        }
        private fun header(text: String) = TextView(activity).apply {
            this.text = text; setTextColor(Color.BLACK); textSize = 14f
            setOnClickListener { clicks++ }
        }
        val outerHeader = header("Outer header")
        val innerHeader = header("Inner header")
        val outer = card()
        val inner = card()
        lateinit var outerMarker: View
        lateinit var innerMarker: View
        private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val root = FrameLayout(activity).apply { keepScreenOn = true; setBackgroundColor(Color.LTGRAY) }
        private var viewport: LiquidStretchViewport? = null
        private val expansion = ArrayList<SectionExpansionController>()
        private val original = requireNotNull(activity.window.callback)
        val controller = ElasticInteractionController(activity.window.decorView).apply {
            travelPolicy = ElasticTravelPolicy.PARENT_BOUNDS
        }
        var clicks = 0
        var cancels = 0

        init {
            activity.binding.close(); activity.session.close()
            repeat(6) { index ->
                val card = if (index == 0) inner else card()
                val title = if (index == 0) innerHeader else header("Nested section $index")
                if (index == 1) outerMarker = title
                val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                repeat(8) { row ->
                    val line = TextView(activity).apply { text = "Row ${row + 1}"; setTextColor(Color.DKGRAY) }
                    if (index == 0 && row == 4) innerMarker = line
                    content.addView(line, LinearLayout.LayoutParams(-1, dp(48)))
                }
                card.addView(title, LinearLayout.LayoutParams(-1, dp(64)))
                card.addView(content, LinearLayout.LayoutParams(-1, -2))
                body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                expansion += SectionExpansionController(card, content, View(activity), density, notifyPositionChanged = {})
            }
            outer.addView(outerHeader, LinearLayout.LayoutParams(-1, dp(64)))
            outer.addView(body, LinearLayout.LayoutParams(-1, -2))
            expansion += SectionExpansionController(outer, body, View(activity), density, notifyPositionChanged = {})
            val page = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // 留白用 padding 而不是占位兄弟：默认邻居策略会把紧贴的占位 View 当成上邻。
                setPadding(0, dp(24), 0, 0)
                addView(outer, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(36); rightMargin = dp(36) })
                // 首级下方的兄弟卡片：展开后远在屏幕外。
                addView(card(), LinearLayout.LayoutParams(-1, dp(96)).apply { topMargin = dp(12) })
            }
            val height = activity.resources.displayMetrics.heightPixels - dp(160)
            if (paged) {
                val pager = LumenPagePager(activity)
                val scroll = LumenPageScrollView(activity, {}, {})
                scroll.addView(page)
                pager.addView(scroll, FrameLayout.LayoutParams(-1, -1))
                viewport = requireNotNull(LiquidStretchViewport.installAround(scroll, { true }) { _, _ -> })
                root.addView(pager, FrameLayout.LayoutParams(-1, height).apply { topMargin = dp(40) })
            } else {
                val scroll = ScrollView(activity)
                scroll.addView(page)
                root.addView(scroll, FrameLayout.LayoutParams(-1, height).apply { topMargin = dp(40) })
            }
            activity.setContentView(root)
            for (controller in expansion) controller.setExpanded(true, false)
            activity.window.callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean = controller.dispatch(event) {
                    if (it.actionMasked == MotionEvent.ACTION_CANCEL) cancels++
                    original.dispatchTouchEvent(it)
                }
            }
        }

        fun close() {
            activity.window.callback = original
            controller.dispose(); expansion.forEach { it.cancel() }; viewport?.finishStretch()
        }
    }

    private fun send(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { InstrumentationRegistry.getInstrumentation().sendPointerSync(event) } finally { event.recycle() }
    }
}
