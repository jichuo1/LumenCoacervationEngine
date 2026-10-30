package com.lumen.coacervation.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.*
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a real attached source and the first sampler, with no content/position/layout notification. */
@RunWith(AndroidJUnit4::class)
class SurfaceSourceInvalidationTest {
    private class DrawingView(context: Context) : View(context) {
        var color = Color.RED
            set(value) { field = value; invalidate() }
        override fun onDraw(canvas: Canvas) = canvas.drawColor(color)
    }

    private open class VersionedSource(override val coordinateView: DrawingView) : LumenVersionedContentSource {
        override var sourceEpoch = 0L
        override var contentVersion = 0L
        override val availability = LumenSourceAvailability.READY
        override fun drawContent(canvas: Canvas) = canvas.drawColor(coordinateView.color)
    }

    private fun configure(activity: SurfaceSandboxActivity, sourceFactory: ((DrawingView) -> LumenContentSource)?): DrawingView {
        // Rebinding an existing Group after an options update would miss the original constructor bug.
        activity.session.close()
        activity.session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
        activity.preview.removeView(activity.content)
        val content = DrawingView(activity)
        activity.content = content
        activity.preview.addView(content, 0, FrameLayout.LayoutParams(-1, -1))
        activity.glass.text = ""
        val options = LumenSurfaceOptions(radiusDp = 0f, tintEnabled = false, edgeEnabled = false,
            sampling = LumenSurfaceSampling(backend = LumenSurfaceBackend.SOFTWARE,
                blurEnabled = false, refractionEnabled = false, minIntervalMs = 0))
        activity.binding = if (sourceFactory == null) activity.session.bind(activity.glass, options, content)
            else activity.session.bindSource(activity.glass, options, sourceFactory(content))
        return content
    }

    private fun centerColor(activity: SurfaceSandboxActivity): Int {
        val view = activity.glass
        if (view.width <= 0 || view.height <= 0) return Color.TRANSPARENT
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.background.setBounds(0, 0, view.width, view.height)
            view.background.draw(Canvas(bitmap))
            bitmap.getPixel(view.width / 2, view.height / 2)
        } finally { bitmap.recycle() }
    }

    private fun hasColor(activity: SurfaceSandboxActivity, color: Int): Boolean {
        val pixel = centerColor(activity)
        if (activity.binding.diagnostics()?.backend != LumenSurfaceBackend.SOFTWARE) return false
        return when (color) {
            Color.RED -> Color.red(pixel) > 200 && Color.blue(pixel) < 30
            Color.BLUE -> Color.blue(pixel) > 200 && Color.red(pixel) < 30
            else -> Color.green(pixel) > 200 && Color.red(pixel) < 30
        }
    }

    private fun await(scenario: ActivityScenario<SurfaceSandboxActivity>, message: String,
        condition: (SurfaceSandboxActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail(message)
    }

    private fun awaitQuiet(scenario: ActivityScenario<SurfaceSandboxActivity>) {
        var lastCount = -1L
        var stableSince = SystemClock.uptimeMillis()
        await(scenario, "A static source must stop recording") { activity ->
            val count = activity.session.diagnostics().contentRecordings
            if (count != lastCount) { lastCount = count; stableSince = SystemClock.uptimeMillis() }
            SystemClock.uptimeMillis() - stableSince >= 150
        }
    }

    @Test fun directViewInvalidationRefreshesTheFirstSoftwareSamplerAndThenStops() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var content: DrawingView
            scenario.onActivity { content = configure(it, null) }
            await(scenario, "Initial source never reached the software texture") { hasColor(it, Color.RED) }
            awaitQuiet(scenario)
            var before = 0L
            scenario.onActivity {
                before = it.session.diagnostics().contentRecordings
                content.color = Color.BLUE
                assertTrue("Changing drawing data must invalidate the coordinate View", content.isDirty)
            }
            await(scenario, "Direct View.invalidate must update the texture without an explicit session notification") {
                hasColor(it, Color.BLUE) && it.session.diagnostics().contentRecordings > before
            }
            awaitQuiet(scenario)
        }
    }

    /** 统计 contentVersion 被读取的次数；其余行为与 [VersionedSource] 相同。 */
    private class CountingSource(view: DrawingView) : VersionedSource(view) {
        val versionReads = java.util.concurrent.atomic.AtomicInteger()
        override var contentVersion: Long = 0L
            get() { versionReads.incrementAndGet(); return field }
    }

    @Test fun rootContentVersionIsReadOncePerPreDrawCallback() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var source: CountingSource
            lateinit var content: DrawingView
            scenario.onActivity {
                content = configure(it) { view -> CountingSource(view).also { created -> source = created } }
            }
            await(scenario, "Versioned source never produced its initial texture") { hasColor(it, Color.RED) }
            awaitQuiet(scenario)
            val preDraws = java.util.concurrent.atomic.AtomicInteger()
            val observer = android.view.ViewTreeObserver.OnPreDrawListener { preDraws.incrementAndGet(); true }
            var readsBefore = 0
            scenario.onActivity {
                content.viewTreeObserver.addOnPreDrawListener(observer)
                readsBefore = source.versionReads.get()
            }
            // 与版本无关的纯重绘请求：每次遍历都会进入会话的 onPreDraw，但内容版本不变，不会触发新的采样。
            repeat(40) {
                scenario.onActivity { content.invalidate() }
                SystemClock.sleep(20)
            }
            var reads = 0
            var traversals = 0
            scenario.onActivity {
                content.viewTreeObserver.removeOnPreDrawListener(observer)
                reads = source.versionReads.get() - readsBefore
                traversals = preDraws.get()
            }
            assertTrue("the probe must have seen several pre-draw callbacks, saw $traversals", traversals >= 10)
            // 改动前每次回调读两次（预绘制一次、可用性汇总一次）；现在每次回调只读一次。
            assertTrue("root contentVersion reads ($reads) must not exceed pre-draw callbacks ($traversals)", reads <= traversals)
        }
    }

    @Test fun versionedSourcesRetainBothDefaultDirtyObservationAndExplicitOptOut() {
        for (observeDirty in listOf(true, false)) {
            ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                lateinit var source: VersionedSource
                scenario.onActivity {
                    configure(it) { view ->
                        val declared = if (observeDirty) VersionedSource(view) else object : VersionedSource(view) {
                            override val observeCoordinateViewInvalidations = false
                        }
                        declared.also { source = it }
                    }
                }
                await(scenario, "Versioned source never produced its initial texture") { hasColor(it, Color.RED) }
                awaitQuiet(scenario)
                var before = 0L
                scenario.onActivity {
                    before = it.session.diagnostics().contentRecordings
                    source.coordinateView.color = Color.BLUE
                    assertTrue(source.coordinateView.isDirty)
                }
                if (observeDirty) {
                    await(scenario, "An observing versioned source must refresh after direct invalidation") { hasColor(it, Color.BLUE) }
                } else {
                    SystemClock.sleep(200)
                    scenario.onActivity {
                        assertEquals("An isolated anchor must not request content because the View is dirty", before,
                            it.session.diagnostics().contentRecordings)
                        assertTrue("The texture waits for its declared version", hasColor(it, Color.RED))
                        source.contentVersion++
                        source.coordinateView.invalidate()
                    }
                    await(scenario, "Declared content version must still update an isolated source") { hasColor(it, Color.BLUE) }
                    awaitQuiet(scenario)
                    scenario.onActivity {
                        source.coordinateView.color = Color.GREEN
                        source.sourceEpoch++
                    }
                    await(scenario, "Declared source epoch must still replace an isolated source") { hasColor(it, Color.GREEN) }
                }
                awaitQuiet(scenario)
            }
        }
    }
}
