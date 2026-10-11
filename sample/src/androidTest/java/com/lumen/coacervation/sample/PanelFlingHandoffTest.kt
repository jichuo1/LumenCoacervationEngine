package com.lumen.coacervation.sample

import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SkinId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Uses the real NestedScrollView scroller, HWUI stretch, glass surfaces and window frame metrics. */
@RunWith(AndroidJUnit4::class)
class PanelFlingHandoffTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()
    @SdkSuppress(minSdkVersion = 31)
    @Test fun fastFlingReboundsAndTouchTakesOwnership() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val oldMaterial = LumenEngine.requestedMaterial(context)
        instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, SkinId.LIQUID, realtimeCapture = false) }
        val metricsThread = HandlerThread("Lumen-TestFrames").apply { start() }
        val frameTimes = Collections.synchronizedList(ArrayList<Long>())
        val draws = Collections.synchronizedList(ArrayList<Long>())
        val overBudget = AtomicInteger()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frameTimes.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
            draws.add(metrics.getMetric(FrameMetrics.DRAW_DURATION))
            if (Build.VERSION.SDK_INT >= 31) {
                val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
                if (deadline > 0 && metrics.getMetric(FrameMetrics.TOTAL_DURATION) > deadline) overBudget.incrementAndGet()
            }
        }
        try {
            ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                lateinit var material: LumenActivityDelegate
                lateinit var scroll: NestedScrollView
                lateinit var viewport: LiquidStretchViewport
                var clicks = 0
                var sawStretch = false
                var maximumDistance = 0f
                scenario.onActivity { activity ->
                    activity.session.close()
                    material = LumenActivityDelegate(activity, { LumenPalette.neutral(false) })
                    material.prepare()
                    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    repeat(40) { index ->
                        body.addView(TextView(activity).apply {
                            text = "row $index"
                            setTextColor(Color.BLACK)
                            textSize = 18f
                            background = material.cardBackground()
                            isClickable = true
                            setOnClickListener { clicks++ }
                        }, LinearLayout.LayoutParams(-1, 100))
                    }
                    scroll = NestedScrollView(activity).apply { addView(body) }
                    val root = FrameLayout(activity).apply {
                        addView(scroll, FrameLayout.LayoutParams(500, 700).apply { leftMargin = 80; topMargin = 80 })
                    }
                    activity.setContentView(root)
                    assertTrue(material.bindRoot(activity.window.decorView))
                    material.onStart()
                    viewport = LiquidStretchViewport.installAround(scroll, { true }) { distance, edge ->
                        if (distance > 0f) sawStretch = true
                        maximumDistance = maxOf(maximumDistance, distance)
                        material.engine?.onStretchDistance(distance, edge, scroll)
                    }!!
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper))
                }
                instrumentation.waitForIdleSync()
                repeat(6) { round ->
                    scenario.onActivity {
                        viewport.finishStretch()
                        scroll.scrollTo(0, if (round % 2 == 0) 2500 else 800)
                        val velocity = if (round % 2 == 0) 7500 else -7500
                        viewport.onNestedFling(scroll, 0f, velocity.toFloat(), true)
                        scroll.fling(velocity)
                    }
                    repeat(10) {
                        SystemClock.sleep(60)
                    }
                    SystemClock.sleep(250)
                }
                assertTrue("Actual fast flings must reach and stretch an edge", sawStretch)
                val flingPeak = maximumDistance
                // Pull, release, catch: the whole gesture belongs to the viewport, with no row click.
                scenario.onActivity {
                    viewport.finishStretch()
                    val consumed = IntArray(2)
                    viewport.onNestedScroll(scroll, 0, 0, 0, -100, androidx.core.view.ViewCompat.TYPE_TOUCH, consumed)
                    assertTrue(consumed[1] < 0)
                    viewport.onStopNestedScroll(scroll, androidx.core.view.ViewCompat.TYPE_TOUCH)
                    val down = SystemClock.uptimeMillis()
                    val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 250f, 200f, 0)
                    try { viewport.dispatchTouchEvent(press) } finally { press.recycle() }
                    assertTrue("Rebound catch must claim the gesture", viewport.claimsCurrentGesture)
                    val release = MotionEvent.obtain(down, down + 20, MotionEvent.ACTION_UP, 250f, 200f, 0)
                    try { viewport.dispatchTouchEvent(release) } finally { release.recycle() }
                    assertFalse(viewport.claimsCurrentGesture)
                    assertEquals("Catching must not click a row", 0, clicks)
                }
                SystemClock.sleep(900)
                scenario.onActivity {
                    assertEquals("Rebound must settle exactly", 0f, viewport.samplingOverscroll, 0f)
                    it.window.removeOnFrameMetricsAvailableListener(listener)
                    material.onDestroy()
                }
                fun report(values: List<Long>): String {
                    val sorted = synchronized(values) { values.toList().sorted() }
                    assertTrue(sorted.isNotEmpty())
                    fun percentile(p: Float) = sorted[((sorted.size - 1) * p).toInt()] / 1_000_000.0
                    return "n=${sorted.size},p50ms=${percentile(.5f)},p95ms=${percentile(.95f)},p99ms=${percentile(.99f)},over32ms=${sorted.count { it > 32_000_000 }}"
                }
                val requestedLabel = InstrumentationRegistry.getArguments().getString("benchmarkLabel", "unspecified")
                val label = requestedLabel?.takeIf { it.length <= 24 && it.all(Char::isLetterOrDigit) } ?: "unspecified"
                val summary = "$label;peak=$flingPeak;overBudget=${overBudget.get()};total:${report(frameTimes)};draw:${report(draws)}"
                Log.i("Lumen-PanelFlingTest", summary)
                scenario.onActivity { activity ->
                    java.io.File(activity.getExternalFilesDir(null), "benchmark-$label.txt").writeText(summary)
                }
            }
        } finally {
            metricsThread.quitSafely()
            instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, oldMaterial) }
        }
    }
}
