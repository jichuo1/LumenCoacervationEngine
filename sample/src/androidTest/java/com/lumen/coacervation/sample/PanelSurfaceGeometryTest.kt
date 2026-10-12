package com.lumen.coacervation.sample

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SkinId
import com.lumen.coacervation.engine.liquid.LiquidStretchSampling
import com.lumen.coacervation.engine.widget.LumenSlidingSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PanelSurfaceGeometryTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @SdkSuppress(minSdkVersion = 31)
    @Test fun dialogInputReadsCurrentNativeStretchCoordinatesAndClearsAfterRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previous = LumenEngine.requestedMaterial(context)
        instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, SkinId.LIQUID, realtimeCapture = false) }
        try {
            ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                lateinit var material: LumenActivityDelegate
                lateinit var dialog: Dialog
                lateinit var scroll: NestedScrollView
                lateinit var viewport: LiquidStretchViewport
                lateinit var root: View
                val sampling = FloatArray(3)
                val resolver = LiquidStretchSampling()
                scenario.onActivity { activity ->
                    activity.binding.close()
                    activity.session.close()
                    material = LumenActivityDelegate(activity, { LumenPalette.neutral(false) })
                    material.prepare()
                    activity.setContentView(FrameLayout(activity))
                    assertTrue(material.bindRoot(activity.window.decorView))
                    material.onStart()
                    assertTrue(material.isLiquidEffective)
                    val input = object : EditText(activity) {
                        override fun onDraw(canvas: Canvas) {
                            resolver.resolve(this, activity.window.decorView, sampling)
                            super.onDraw(canvas)
                        }
                    }.apply { background = material.cardBackground(); setText("native stretch input") }
                    val body = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(input, LinearLayout.LayoutParams(-1, 80))
                        addView(View(activity), LinearLayout.LayoutParams(-1, 900))
                    }
                    scroll = NestedScrollView(activity).apply { addView(body) }
                    val panel = FrameLayout(activity).apply {
                        background = material.modalBackground()
                        addView(scroll, FrameLayout.LayoutParams(280, 400).apply { leftMargin = 20; topMargin = 40 })
                    }
                    viewport = LiquidStretchViewport.installAround(scroll, { true }) { distance, edge ->
                        material.engine!!.onStretchDistance(distance, edge, scroll)
                    }!!
                    dialog = Dialog(activity).apply {
                        setContentView(panel)
                        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                        show()
                        window?.setLayout(320, 500)
                    }
                    root = checkNotNull(dialog.window).decorView
                }
                try {
                    frame(scenario, root)
                    for (dy in listOf(-60, 60)) {
                        scenario.onActivity {
                            viewport.finishStretch()
                            viewport.onNestedScroll(scroll, 0, 0, 0, dy, ViewCompat.TYPE_TOUCH, IntArray(2))
                        }
                        frame(scenario, root)
                        scenario.onActivity { activity ->
                            val at = IntArray(2)
                            val backdrop = IntArray(2)
                            viewport.getLocationOnScreen(at)
                            activity.window.decorView.getLocationOnScreen(backdrop)
                            assertEquals("Sampling uses the foreign window's viewport origin", (at[1] - backdrop[1]).toFloat(), sampling[0], .01f)
                            assertEquals(viewport.height.toFloat(), sampling[1], .01f)
                            assertTrue("The sign must follow the native edge in this frame", if (dy < 0) sampling[2] > 0f else sampling[2] < 0f)
                        }
                    }
                    scenario.onActivity { viewport.finishStretch() }
                    frame(scenario, root)
                    scenario.onActivity { assertArrayEquals(floatArrayOf(0f, 0f, 0f), sampling, 0f) }
                } finally {
                    scenario.onActivity { dialog.dismiss(); material.onDestroy() }
                }
            }
        } finally {
            instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, previous) }
        }
    }

    private class RecordedOrigin : Drawable() {
        val location = IntArray(2)
        private val paint = Paint()
        var draws = 0
        var recordedY = -1
        override fun draw(canvas: Canvas) {
            (callback as View).getLocationInWindow(location)
            draws++
            recordedY = location[1]
            paint.color = Color.rgb(0, recordedY % 255, 160)
            canvas.drawRect(bounds, paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    @Test fun selectedBackgroundRerecordsDuringRowTransformAndStaysIdleAfterwards() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            val backdrop = RecordedOrigin()
            lateinit var choice: LumenSlidingSelection
            lateinit var root: View
            var notifications = 0
            scenario.onActivity { activity ->
                activity.binding.close()
                activity.session.close()
                choice = LumenSlidingSelection(activity, backdrop, notifyPositionChanged = { notifications++ })
                repeat(3) { choice.addOption(View(activity), LinearLayout.LayoutParams(-1, 48)) }
                root = FrameLayout(activity).apply {
                    setBackgroundColor(Color.WHITE)
                    addView(choice, FrameLayout.LayoutParams(220, -2).apply { leftMargin = 20; topMargin = 80 })
                }
                activity.setContentView(root)
            }
            frame(scenario, root)
            var initialDraws = 0
            scenario.onActivity {
                initialDraws = backdrop.draws
                assertTrue(initialDraws > 0)
                choice.options.getChildAt(0).translationY = 24f
                choice.options.getChildAt(0).scaleX = 1.04f
            }
            frame(scenario, root)
            scenario.onActivity {
                val at = IntArray(2)
                choice.indicator.getLocationInWindow(at)
                assertEquals("Recorded shader origin must follow the actual indicator", at[1], backdrop.recordedY)
                assertTrue("Translation must rerecord the retained background", backdrop.draws > initialDraws)
                choice.select(2, animate = false)
            }
            frame(scenario, root)
            var idleDraws = 0
            var idleNotifications = 0
            scenario.onActivity {
                assertEquals(96, choice.indicator.top)
                assertEquals(0f, choice.indicator.translationY, 0f)
                idleDraws = backdrop.draws
                idleNotifications = notifications
            }
            repeat(3) { frame(scenario, root) }
            scenario.onActivity {
                assertEquals("Stationary selection must not keep rerecording", idleDraws, backdrop.draws)
                assertEquals(idleNotifications, notifications)
            }
        }
    }

    @Test fun bitmapSamplingTransformMatchesNativeWarpAtSurfaceEndpoints() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resolver = LiquidStretchSampling()
            val input = Matrix().apply { setScale(1.1f, 1.2f); postTranslate(12f, 80f) }
            val bounds = Rect(0, 0, 180, 64)
            val source = FloatArray(4)
            val actual = FloatArray(4)
            val output = Matrix()
            for (direction in listOf(-.03f, .03f)) {
                source[0] = 0f; source[1] = 0f; source[2] = 0f; source[3] = 64f
                actual[0] = 0f; actual[1] = 0f; actual[2] = 0f; actual[3] = 64f
                input.mapPoints(source)
                resolver.opticalTransform(bounds, input, 0f, 0f, floatArrayOf(40f, 600f, direction), output)
                output.mapPoints(actual)
                for (index in listOf(1, 3)) {
                    val amount = kotlin.math.abs(direction)
                    var q = (source[index] - 40f) / 600f
                    if (direction < 0f) q = 1f - q
                    val k = 1f + amount
                    q = k * k * q / (1f + .3f * amount + .7f * amount * k * q)
                    if (direction < 0f) q = 1f - q
                    assertEquals(40f + q * 600f, actual[index], .001f)
                }
                assertEquals(source[0], actual[0], .001f)
            }
        }
    }

    private fun frame(scenario: ActivityScenario<SurfaceSandboxActivity>, root: View) {
        val committed = CountDownLatch(1)
        var observer: ViewTreeObserver? = null
        val callback = Runnable { committed.countDown() }
        val drawn = ViewTreeObserver.OnDrawListener { root.post(callback) }
        try {
            scenario.onActivity {
                observer = root.viewTreeObserver
                if (android.os.Build.VERSION.SDK_INT >= 29) observer!!.registerFrameCommitCallback(callback)
                else observer!!.addOnDrawListener(drawn)
                root.postInvalidateOnAnimation()
            }
            assertTrue(committed.await(5, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        } finally {
            scenario.onActivity {
                root.removeCallbacks(callback)
                observer?.takeIf { it.isAlive }?.let {
                    if (android.os.Build.VERSION.SDK_INT >= 29) it.unregisterFrameCommitCallback(callback)
                    else it.removeOnDrawListener(drawn)
                }
            }
        }
    }
}
