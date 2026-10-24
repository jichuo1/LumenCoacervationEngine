package com.lumen.coacervation.sample

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.interaction.ElasticTravelPolicy
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SkinId
import com.lumen.coacervation.engine.motion.expansion.SectionExpansionController
import com.lumen.coacervation.engine.motion.pager.LumenPagePager
import com.lumen.coacervation.engine.motion.pager.LumenPageScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A header promotes to the whole card; expanded content can already extend below the page. */
@RunWith(AndroidJUnit4::class)
class ExpandedAccordionHeaderTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Test fun expandedHeadersKeepFourDirectionsAndTheirExistingPageCrop() = withFixture { f ->
        checkFourDirections(f, cropped = true)
    }

    @Test fun collapsedAndRoomyExpandedHeadersKeepTheirResponse() {
        withFixture(expanded = false) { checkFourDirections(it, cropped = false) }
        withFixture(pageHeight = 900) { checkFourDirections(it, cropped = false) }
    }

    @Test fun aTighterOuterClipStillConstrainsAnAlreadyPageCroppedCard() = withFixture { f ->
        val foreign = Rect(0, 0, f.root.width, f.pageTop + f.pager.height - f.size(50))
        WindowRegressionCapture.main { f.root.clipBounds = foreign }
        WindowRegressionCapture.frame(f.pager)
        val point = f.point()
        val down = hold(point, 0f, 120f)
        WindowRegressionCapture.main {
            assertTrue("An additional outer crop must not be discarded", f.card.translationY in 0f..1f)
            assertEquals(foreign, f.root.clipBounds)
            assertEquals(1, f.cancels)
        }
        send(down, MotionEvent.ACTION_UP, point[0], point[1] + 120f)
        WindowRegressionCapture.main { f.controller.clear(); assertEquals(foreign, f.root.clipBounds) }
    }

    @Test fun regrabbingAndLeavingThePageReleaseTheHeaderCropWithoutRevivingIt() = withFixture { f ->
        val point = f.point()
        val down = hold(point, 0f, 120f)
        lateinit var ownedCrop: Rect
        WindowRegressionCapture.main {
            assertTrue(f.card.translationY > f.minimumDrag)
            ownedCrop = Rect(requireNotNull(f.card.clipBounds))
        }
        send(down, MotionEvent.ACTION_UP, point[0], point[1] + 120f)
        val nextPoint = f.point()
        val nextDown = hold(nextPoint, 0f, 120f)
        WindowRegressionCapture.main {
            assertTrue("The page crop must allow a release regrab", f.card.translationY > f.minimumDrag)
            assertEquals(ownedCrop, f.card.clipBounds)
            assertEquals(2, f.cancels)
            f.pager.selectPage(1, false)
        }
        WindowRegressionCapture.frame(f.pager)
        WindowRegressionCapture.frame(f.pager)
        WindowRegressionCapture.main { assertRestored(f) }
        send(nextDown, MotionEvent.ACTION_UP, nextPoint[0], nextPoint[1] + 120f)
        WindowRegressionCapture.main { f.pager.selectPage(0, false) }
        WindowRegressionCapture.frame(f.pager)
        WindowRegressionCapture.main { assertRestored(f); assertEquals(0, f.clicks) }
    }

    @Test fun invalidationPreservesAForeignCropWhileRestoringTheHeldTransform() = withFixture { f ->
        val point = f.point()
        val down = hold(point, 0f, 120f)
        val foreign = Rect(0, 0, f.card.width, f.pager.height - f.contentTop - f.size(40))
        WindowRegressionCapture.main { assertNotNull(f.card.clipBounds); f.card.clipBounds = foreign }
        WindowRegressionCapture.frame(f.pager)
        WindowRegressionCapture.frame(f.pager)
        WindowRegressionCapture.main {
            assertEquals(0f, f.card.translationY, 0f)
            assertEquals(1f, f.card.scaleY, 0f)
            assertEquals(foreign, f.card.clipBounds)
        }
        send(down, MotionEvent.ACTION_UP, point[0], point[1] + 120f)
        WindowRegressionCapture.main { f.controller.clear(); assertEquals(foreign, f.card.clipBounds); assertEquals(0, f.clicks) }
    }

    @Test fun aDifferentChildUnderTheScaledCardDoesNotInheritItsGesture() = withFixture { f ->
        val point = f.point()
        val down = hold(point, 0f, 120f)
        WindowRegressionCapture.main { assertTrue(f.card.translationY > f.minimumDrag) }
        send(down, MotionEvent.ACTION_UP, point[0], point[1] + 120f)
        val childPoint = f.point(f.body)
        val childDown = hold(childPoint, 0f, 120f)
        WindowRegressionCapture.main {
            assertEquals("A different child cannot continue the card's lease", 0f, f.body.translationY, 0f)
            assertRestored(f)
            assertEquals(1, f.cancels)
        }
        send(childDown, MotionEvent.ACTION_CANCEL, childPoint[0], childPoint[1] + 120f)
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test fun liquidExpandedHeadersKeepFourDirectionsAndHiddenPixels() = withFixture(liquid = true) { f ->
        WindowRegressionCapture.main {
            assertTrue(f.material.isLiquidEffective)
            assertEquals("REFRACTION", f.material.backendName)
        }
        checkFourDirections(f, cropped = true)
    }

    private fun checkFourDirections(f: Fixture, cropped: Boolean) {
        val outside = IntArray(18)
        WindowRegressionCapture.copy(f.activity, f.root) { pixels ->
            for (index in outside.indices) outside[index] =
                pixels.getPixel(pixels.width / 2, f.pageTop + f.pager.height + 2 + index)
        }
        for ((dx, dy) in listOf(-120f to 0f, 120f to 0f, 0f to -120f, 0f to 120f)) {
            val point = f.point()
            val down = hold(point, dx, dy)
            WindowRegressionCapture.frame(f.pager)
            WindowRegressionCapture.main {
                if (dx != 0f) assertTrue("Promoted header must follow horizontal drag", f.card.translationX * dx > f.minimumDrag * kotlin.math.abs(dx))
                if (dy != 0f) assertTrue("Promoted header must follow vertical drag", f.card.translationY * dy > f.minimumDrag * kotlin.math.abs(dy))
                assertEquals(0, f.clicks)
                assertTrue(f.scroll.clipChildren); assertTrue(f.viewport.clipChildren)
                if (cropped) assertEquals(Rect(0, 0, f.card.width, f.pager.height - f.contentTop), f.card.clipBounds)
                else assertNull(f.card.clipBounds)
            }
            WindowRegressionCapture.copy(f.activity, f.root) { pixels ->
                val x = pixels.width / 2
                for (index in outside.indices) assertEquals("Page exterior must keep its resting pixels", outside[index],
                    pixels.getPixel(x, f.pageTop + f.pager.height + 2 + index))
                if (cropped) for (y in f.pageTop until f.pageTop + f.pager.height) {
                    val color = pixels.getPixel(x, y)
                    assertFalse("The previously hidden red content must stay hidden", Color.red(color) > 200 &&
                        Color.green(color) < 80 && Color.blue(color) < 80)
                }
            }
            send(down, MotionEvent.ACTION_UP, point[0] + dx, point[1] + dy)
            WindowRegressionCapture.main { f.controller.clear(); assertRestored(f) }
            WindowRegressionCapture.frame(f.pager)
        }
        WindowRegressionCapture.main { assertEquals(4, f.cancels) }
    }

    private fun assertRestored(f: Fixture) {
        assertEquals(0f, f.card.translationX, 0f); assertEquals(0f, f.card.translationY, 0f)
        assertEquals(1f, f.card.scaleX, 0f); assertEquals(1f, f.card.scaleY, 0f)
        assertNull(f.card.clipBounds)
    }

    private fun withFixture(expanded: Boolean = true, pageHeight: Int = 700, liquid: Boolean = false,
                            check: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val oldMaterial = LumenEngine.requestedMaterial(context)
        try {
            if (liquid) WindowRegressionCapture.main { LumenEngine.selectMaterial(context, SkinId.LIQUID, realtimeCapture = false) }
            ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                lateinit var f: Fixture
                scenario.onActivity { f = Fixture(it, expanded, pageHeight, liquid) }
                try { WindowRegressionCapture.frame(f.pager); check(f) }
                finally { WindowRegressionCapture.main { f.close() } }
            }
        } finally {
            if (liquid) WindowRegressionCapture.main { LumenEngine.selectMaterial(context, oldMaterial) }
        }
    }

    private class Fixture(val activity: SurfaceSandboxActivity, expanded: Boolean, pageHeight: Int, liquid: Boolean) {
        private val unit = minOf(1f, (activity.resources.displayMetrics.heightPixels - 240f) / 1040f).coerceAtLeast(.25f)
        fun size(value: Int): Int = (value * unit).toInt().coerceAtLeast(1)
        val pageTop = size(120)
        val contentTop = size(60)
        val minimumDrag = 4f * unit
        val material = LumenActivityDelegate(activity, { LumenPalette.neutral(false) })
        val pager = LumenPagePager(activity)
        val scroll = LumenPageScrollView(activity, {}, {})
        val header = TextView(activity).apply {
            text = "Hold the accordion header"; setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f)
        }
        val body = TextView(activity).apply {
            text = "Expanded native content"; setBackgroundColor(Color.BLUE)
        }
        private val content = FrameLayout(activity).apply {
            addView(body, FrameLayout.LayoutParams(-1, size(600)))
            addView(View(activity).apply { setBackgroundColor(Color.RED) }, FrameLayout.LayoutParams(-1, size(40), Gravity.BOTTOM))
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            addView(header, LinearLayout.LayoutParams(-1, size(96)))
            addView(content, LinearLayout.LayoutParams(-1, -2))
        }
        private val expansion = SectionExpansionController(card, content, View(activity),
            activity.resources.displayMetrics.density, notifyPositionChanged = { material.notifyPositionChanged() })
        private val original = checkNotNull(activity.window.callback)
        val root = FrameLayout(activity).apply {
            keepScreenOn = true; setBackgroundColor(Color.LTGRAY)
            addView(pager, FrameLayout.LayoutParams(-1, size(pageHeight)).apply { topMargin = pageTop })
        }
        val controller = ElasticInteractionController(activity.window.decorView,
            notifyPositionChanged = { material.notifyPositionChanged() }).apply {
            travelPolicy = ElasticTravelPolicy.PARENT_BOUNDS
        }
        val viewport: LiquidStretchViewport
        var clicks = 0
        var cancels = 0

        init {
            activity.binding.close(); activity.session.close()
            if (liquid) { material.prepare(); card.background = material.cardBackground(Color.WHITE, 12f) }
            header.setOnClickListener { clicks++ }
            body.setOnClickListener { clicks++ }
            val page = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(View(activity), LinearLayout.LayoutParams(-1, contentTop))
                addView(card, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = size(80); rightMargin = size(80) })
                addView(View(activity), LinearLayout.LayoutParams(-1, 4000))
            }
            scroll.addView(page)
            pager.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            viewport = requireNotNull(LiquidStretchViewport.installAround(scroll,
                { pager.selectedPage == 0 && pager.isSettled }) { _, _ -> })
            pager.addView(View(activity).apply { setBackgroundColor(Color.CYAN) }, FrameLayout.LayoutParams(-1, -1))
            expansion.setExpanded(expanded, false)
            activity.setContentView(root)
            if (liquid) { assertTrue(material.bindRoot(root)); material.onStart() }
            activity.window.callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    material.onDispatchTouchEvent(event)
                    return controller.dispatch(event) {
                        if (it.actionMasked == MotionEvent.ACTION_CANCEL) cancels++
                        original.dispatchTouchEvent(it)
                    }
                }
            }
        }

        fun point(view: View = header): FloatArray {
            lateinit var result: FloatArray
            WindowRegressionCapture.main {
                val location = IntArray(2); view.getLocationOnScreen(location)
                result = floatArrayOf(location[0] + view.width / 2f, location[1] + view.height / 2f)
            }
            return result
        }

        fun close() {
            activity.window.callback = original
            controller.dispose(); expansion.cancel(); viewport.finishStretch(); material.onDestroy()
        }
    }

    private fun hold(point: FloatArray, dx: Float, dy: Float): Long {
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, point[0], point[1])
        SystemClock.sleep(190)
        send(down, MotionEvent.ACTION_MOVE, point[0] + dx, point[1] + dy)
        return down
    }

    private fun send(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { InstrumentationRegistry.getInstrumentation().sendPointerSync(event) } finally { event.recycle() }
    }

}
