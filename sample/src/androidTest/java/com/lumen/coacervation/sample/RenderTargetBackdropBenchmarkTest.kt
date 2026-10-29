package com.lumen.coacervation.sample

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Same window and trajectory; device-specific measurements, not a general performance claim. */
@RunWith(AndroidJUnit4::class)
class RenderTargetBackdropBenchmarkTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val outputDir = File(instrumentation.targetContext.getExternalFilesDir(null), "native-backdrop-benchmark")
    private val report = JSONObject()
    private val runs = JSONArray()
    private val snapshots = JSONArray()

    @Test fun compareCurrentTargetAndExplicitSourceDuringTheSameScroll() {
        assertTrue(outputDir.mkdirs() || outputDir.isDirectory)
        report.put("schema", 1).put("sdkInt", Build.VERSION.SDK_INT)
            .put("sdkIntFull", if (Build.VERSION.SDK_INT >= 36) Build.VERSION.SDK_INT_FULL else JSONObject.NULL)
            .put("fingerprint", Build.FINGERPRINT).put("model", Build.MODEL)
            .put("runs", runs).put("snapshots", snapshots)
            .put("cpuNote", "Process.getElapsedCpuTime is whole-process CPU, not UI-thread-only; the window spans animation start to end")
            .put("memoryNote", "Debug.MemoryInfo memoryStats are process summaries, not total device or driver GPU memory; corroborate with dumpsys meminfo")
            .put("gfxNote", "dumpsys gfxinfo Total GPU memory usage is this process's HWUI GPU cache, not total device or driver GPU memory")
            .put("jankDefinition", "TOTAL_DURATION > DEADLINE for frames reporting a positive deadline")
        if (Build.VERSION.SDK_INT < 36 || Build.VERSION.SDK_INT < 37 || Build.VERSION.SDK_INT_FULL < 3_700_002) {
            report.put("status", "unsupported_sdk")
            writeReport()
            return
        }
        try {
            ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
                lateinit var stage: Stage
                scenario.onActivity { activity ->
                    val density = activity.resources.displayMetrics.density
                    fun dp(value: Int) = (value * density).roundToInt()
                    val band = dp(240)
                    val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    repeat(9) { index ->
                        column.addView(View(activity).apply { setBackgroundColor(COLORS[index % COLORS.size]) },
                            LinearLayout.LayoutParams(-1, band))
                    }
                    val scroll = ScrollView(activity).apply {
                        isVerticalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        addView(column)
                    }
                    val target = View(activity)
                    val root = FrameLayout(activity).apply {
                        setBackgroundColor(Color.BLACK)
                        addView(scroll, FrameLayout.LayoutParams(-1, -1))
                        addView(target, FrameLayout.LayoutParams(dp(140), dp(80)).apply {
                            leftMargin = dp(64); topMargin = dp(60)
                        })
                    }
                    activity.setContentView(root)
                    val session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
                    val options = LumenSurfaceOptions(radiusDp = 12f, tintEnabled = false, edgeEnabled = false,
                        sampling = LumenSurfaceSampling(blurEnabled = true, blurRadiusDp = 6f,
                            refractionEnabled = false, fadeEnabled = false, minIntervalMs = 0L))
                    val binding = session.bind(target, options, scroll)
                    stage = Stage(activity.window, root, scroll, target, session, binding, band)
                    @Suppress("DEPRECATION")
                    val refresh = activity.window.windowManager.defaultDisplay.refreshRate
                    report.put("refreshRateHz", refresh.toDouble()).put("density", density.toDouble())
                        .put("bandPx", band).put("targetWidthPx", dp(140)).put("targetHeightPx", dp(80))
                        .put("blurRadiusDp", 6).put("durationMillis", DURATION_MS)
                        .put("trajectory", "linear scroll from 0 to two bands")
                        .put("sampleOrder", "legacy, native, native, legacy")
                }
                try {
                    awaitCondition("Scroll scene layout was not ready") {
                        var ready = false
                        scenario.onActivity { ready = stage.scroll.getChildAt(0).height - stage.scroll.height >= stage.band * 2 }
                        ready
                    }
                    val reference = capturePositions(scenario, stage, false)
                    val native = capturePositions(scenario, stage, true)
                    try {
                        for (i in reference.indices) {
                            val left = reference[i]; val right = native[i]
                            val lx = left.width / 2; val ly = left.height / 2
                            assertNear("PixelCopy at scroll position $i", left.getPixel(lx, ly), right.getPixel(lx, ly), 12)
                            val diff = compare(left, right)
                            snapshots.getJSONObject(i).put("comparison", diff)
                        }
                    } finally {
                        reference.forEach(Bitmap::recycle); native.forEach(Bitmap::recycle)
                    }
                    val legacyMoving = captureMoving(scenario, stage, false)
                    val nativeMoving = captureMoving(scenario, stage, true)
                    assertNear("PixelCopy while scrolling", legacyMoving, nativeMoving, 12)
                    for ((index, useNative) in listOf(false, true, true, false).withIndex()) {
                        measure(scenario, stage, useNative, index)
                        writeReport()
                    }
                    report.put("status", "complete")
                } finally {
                    scenario.onActivity { stage.binding.close(); stage.session.close() }
                }
            }
        } catch (error: Throwable) {
            report.put("status", "failed").put("failure", error.toString())
            throw error
        } finally {
            writeReport()
        }
    }

    private class Stage(val window: Window, val root: FrameLayout, val scroll: ScrollView, val target: View,
        val session: LumenSurfaceSession, val binding: LumenSurfaceBinding, val band: Int)

    private fun select(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage, native: Boolean) {
        scenario.onActivity {
            stage.binding.setRenderTargetBackdropEnabled(native)
            stage.scroll.scrollTo(0, 0)
            stage.session.notifyContentChanged()
            stage.target.invalidate()
        }
        awaitCondition("Requested native=$native did not become the active GPU backend") {
            var selected = false
            scenario.onActivity {
                val nativeState = stage.binding.renderTargetBackdropState()
                selected = nativeState != null && nativeState.requested == native && nativeState.active == native &&
                    !nativeState.failed && stage.binding.diagnostics()?.backend == LumenSurfaceBackend.GPU
            }
            selected
        }
    }

    private fun capturePositions(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage, native: Boolean): List<Bitmap> {
        select(scenario, stage, native)
        val images = ArrayList<Bitmap>()
        val offsets = intArrayOf(0, stage.band, stage.band * 2, stage.band * 3 / 5)
        val expected = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.GREEN)
        try {
            offsets.forEachIndexed { index, offset ->
                scenario.onActivity {
                    stage.scroll.scrollTo(0, offset)
                    stage.session.notifyScrollPositionChanged(stage.scroll)
                    stage.target.invalidate()
                }
                var image: Bitmap? = null
                awaitCondition("Displayed scroll position $offset did not show its expected frosted color") {
                    image?.recycle()
                    image = capture(scenario, stage)
                    val pixel = image!!.getPixel(image!!.width / 2, image!!.height / 2)
                    dominant(pixel) == dominant(expected[index]) && minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) >= 5
                }
                val saved = image!!
                val name = "${if (native) "native" else "legacy"}-$index.png"
                File(outputDir, name).outputStream().use { assertTrue(saved.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                snapshots.put(JSONObject().put("mode", if (native) "native" else "legacy").put("offsetPx", offset)
                    .put("expectedColor", expected[index]).put("file", name)
                    .put("centerColor", saved.getPixel(saved.width / 2, saved.height / 2)))
                images.add(saved)
            }
            return images
        } catch (error: Throwable) {
            images.forEach(Bitmap::recycle)
            throw error
        }
    }

    private fun measure(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage, native: Boolean, index: Int): JSONObject {
        val samples = JSONArray()
        val run = JSONObject().put("index", index).put("mode", if (native) "native" else "legacy").put("samples", samples)
        runs.put(run)
        select(scenario, stage, native)
        // Warm the exact same trajectory, without PixelCopy in either timed measurement.
        animate(scenario, stage, 800L, native)
        scenario.onActivity { stage.scroll.scrollTo(0, 0); stage.session.notifyScrollPositionChanged(stage.scroll) }
        SystemClock.sleep(150)
        run.put("gfxBefore", gfxSnapshot(scenario, stage, native))
        var droppedReports = 0L
        val finished = CountDownLatch(1)
        lateinit var listener: Window.OnFrameMetricsAvailableListener
        lateinit var animator: ValueAnimator
        var initialRecordings = 0L
        var activeChecks = 0
        var unexpectedBackendChecks = 0
        var startCpuMs = -1L; var endCpuMs = -1L
        var startElapsedNanos = -1L; var endElapsedNanos = -1L
        scenario.onActivity {
            initialRecordings = stage.session.diagnostics().contentRecordings
            run.put("memoryBefore", memorySnapshot())
            listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                droppedReports += dropped.toLong()
                samples.put(JSONObject().put("totalNanos", metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                    .put("deadlineNanos", metrics.getMetric(FrameMetrics.DEADLINE))
                    .put("gpuNanos", metrics.getMetric(FrameMetrics.GPU_DURATION))
                    .put("unknownDelayNanos", metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION))
                    .put("inputNanos", metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION))
                    .put("animationNanos", metrics.getMetric(FrameMetrics.ANIMATION_DURATION))
                    .put("layoutNanos", metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
                    .put("drawNanos", metrics.getMetric(FrameMetrics.DRAW_DURATION))
                    .put("syncNanos", metrics.getMetric(FrameMetrics.SYNC_DURATION))
                    .put("commandIssueNanos", metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION))
                    .put("swapBuffersNanos", metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)))
            }
            stage.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            var updates = 0
            animator = ValueAnimator.ofInt(0, stage.band * 2).apply {
                duration = DURATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    stage.scroll.scrollTo(0, animation.animatedValue as Int)
                    stage.session.notifyScrollPositionChanged(stage.scroll)
                    if (++updates % 30 == 0) {
                        activeChecks++
                        val state = stage.binding.renderTargetBackdropState()
                        if (state == null || state.active != native || state.failed ||
                            stage.binding.diagnostics()?.backend != LumenSurfaceBackend.GPU) unexpectedBackendChecks++
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        endCpuMs = Process.getElapsedCpuTime()
                        endElapsedNanos = SystemClock.elapsedRealtimeNanos()
                        finished.countDown()
                    }
                })
                startCpuMs = Process.getElapsedCpuTime()
                startElapsedNanos = SystemClock.elapsedRealtimeNanos()
                start()
            }
        }
        try {
            assertTrue("Scroll animation timed out", finished.await(12, TimeUnit.SECONDS))
            SystemClock.sleep(100)
        } finally {
            scenario.onActivity {
                animator.cancel()
                stage.window.removeOnFrameMetricsAvailableListener(listener)
                run.put("cpuMeasurement", JSONObject()
                    .put("method", "android.os.Process.getElapsedCpuTime")
                    .put("scope", "whole process; animation start through onAnimationEnd")
                    .put("startCpuMs", startCpuMs).put("endCpuMs", endCpuMs)
                    .put("cpuDeltaMs", if (startCpuMs >= 0 && endCpuMs >= startCpuMs) endCpuMs - startCpuMs else JSONObject.NULL)
                    .put("startElapsedNanos", startElapsedNanos).put("endElapsedNanos", endElapsedNanos)
                    .put("windowNanos", if (startElapsedNanos >= 0 && endElapsedNanos >= startElapsedNanos) endElapsedNanos - startElapsedNanos else JSONObject.NULL))
                run.put("memoryAfter", memorySnapshot())
            }
        }
        // Runs on the instrumentation thread, after the listener and CPU timing window have stopped.
        run.put("gfxAfter", gfxSnapshot(scenario, stage, native))
        run.put("sampleCount", samples.length()).put("droppedMetricReports", droppedReports)
            .put("backendChecks", activeChecks).put("unexpectedBackendChecks", unexpectedBackendChecks)
        assertTrue("Insufficient actual FrameMetrics samples: ${samples.length()}", samples.length() >= 30)
        assertTrue("No backend checks occurred during animation", activeChecks > 0)
        assertEquals("Backend changed during the measured animation", 0, unexpectedBackendChecks)
        val totals = (0 until samples.length()).map { samples.getJSONObject(it).getLong("totalNanos") }.filter { it >= 0 }.sorted()
        assertTrue("No valid frame duration samples", totals.size >= 30)
        val eligible = (0 until samples.length()).map { samples.getJSONObject(it) }
            .filter { it.getLong("deadlineNanos") > 0 && it.getLong("totalNanos") >= 0 }
        val gpu = (0 until samples.length()).map { samples.getJSONObject(it).getLong("gpuNanos") }.filter { it > 0 }.sorted()
        var recordings = 0L
        var state: JSONObject? = null
        scenario.onActivity {
            val nativeState = stage.binding.renderTargetBackdropState()!!
            assertEquals(native, nativeState.active)
            assertFalse(nativeState.failed)
            recordings = stage.session.diagnostics().contentRecordings - initialRecordings
            state = JSONObject().put("requested", nativeState.requested).put("active", nativeState.active)
                .put("failed", nativeState.failed).put("nativeDraws", nativeState.draws)
        }
        return run.put("validDurationCount", totals.size)
            .put("p50Nanos", percentile(totals, .50)).put("p95Nanos", percentile(totals, .95)).put("p99Nanos", percentile(totals, .99))
            .put("jankEligibleFrames", eligible.size).put("jankFrames", if (eligible.isEmpty()) JSONObject.NULL else eligible.count { it.getLong("totalNanos") > it.getLong("deadlineNanos") })
            .put("droppedMetricReports", droppedReports).put("gpuValidSamples", gpu.size)
            .put("gpuP95Nanos", if (gpu.isEmpty()) JSONObject.NULL else percentile(gpu, .95))
            .put("backendChecks", activeChecks).put("unexpectedBackendChecks", unexpectedBackendChecks)
            .put("contentRecordings", recordings).put("state", state)
    }

    /** Main-thread snapshots: the raw values are Android's process-memory summary in kB. */
    private fun memorySnapshot(): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val values = info.memoryStats
        val raw = JSONObject()
        for ((key, value) in values) raw.put(key, value)
        val graphics = values["summary.graphics"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        return JSONObject().put("method", "Debug.getMemoryInfo / MemoryInfo.getMemoryStats")
            .put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
            .put("graphicsKb", graphics ?: JSONObject.NULL).put("memoryStats", raw)
            .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
    }

    /** A fixed command for this test's own package only; unavailable data is never reported as zero. */
    private fun gfxSnapshot(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage, native: Boolean): JSONObject {
        fun verifyBackend() {
            scenario.onActivity {
                val state = stage.binding.renderTargetBackdropState()
                assertNotNull(state)
                assertEquals(native, state!!.requested)
                assertEquals(native, state.active)
                assertFalse(state.failed)
                assertEquals(LumenSurfaceBackend.GPU, stage.binding.diagnostics()?.backend)
            }
        }
        verifyBackend()
        val result = JSONObject().put("method", "dumpsys gfxinfo target package")
            .put("scope", "process HWUI GPU cache; excludes unreported device/driver allocations")
            .put("totalGpuMemoryBytes", JSONObject.NULL).put("rawOutput", JSONObject.NULL)
        try {
            val packageName = instrumentation.targetContext.packageName
            require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
            val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo $packageName")
            val output = descriptor.use { file ->
                ParcelFileDescriptor.AutoCloseInputStream(file).bufferedReader().use { it.readText() }
            }
            result.put("rawOutput", output)
            val value = Regex("Total GPU memory usage:\\s*([0-9][0-9,]*)\\s+bytes", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
            if (value != null) result.put("totalGpuMemoryBytes", value)
        } catch (error: Exception) {
            result.put("error", error.toString())
        }
        verifyBackend()
        return result
    }

    /** Capture during the animation itself, outside the timed FrameMetrics measurements. */
    private fun captureMoving(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage, native: Boolean): Int {
        select(scenario, stage, native)
        val copied = CountDownLatch(1)
        val finished = CountDownLatch(1)
        lateinit var bitmap: Bitmap
        var pixelX = 0; var pixelY = 0; var requestedOffset = -1; var completedOffset = -1
        var status = PixelCopy.ERROR_UNKNOWN
        scenario.onActivity {
            val location = IntArray(2)
            stage.target.getLocationInWindow(location)
            pixelX = location[0] + stage.target.width / 2; pixelY = location[1] + stage.target.height / 2
            bitmap = Bitmap.createBitmap(stage.window.decorView.width, stage.window.decorView.height, Bitmap.Config.ARGB_8888)
            var requested = false
            ValueAnimator.ofInt(0, stage.band * 2).apply {
                duration = DURATION_MS; interpolator = LinearInterpolator()
                addUpdateListener { frame ->
                    stage.scroll.scrollTo(0, frame.animatedValue as Int)
                    stage.session.notifyScrollPositionChanged(stage.scroll)
                    // Sample well inside the green band: asynchronous PixelCopy must not compare
                    // different points on the red/green blur transition during a moving frame.
                    if (!requested && stage.scroll.scrollY >= stage.band * 4 / 5) {
                        requested = true; requestedOffset = stage.scroll.scrollY
                        PixelCopy.request(stage.window, bitmap, { result ->
                            status = result; completedOffset = stage.scroll.scrollY; copied.countDown()
                        }, Handler(Looper.getMainLooper()))
                    }
                }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { finished.countDown() } })
                start()
            }
        }
        try {
            assertTrue("Animated PixelCopy timed out", copied.await(8, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, status)
            val centerInScroll = (60f + 40f) * instrumentation.targetContext.resources.displayMetrics.density
            assertEquals("Animated capture crossed a color boundary before its request", 1, ((requestedOffset + centerInScroll) / stage.band).toInt())
            assertEquals("Animated capture crossed a color boundary before completion", 1, ((completedOffset + centerInScroll) / stage.band).toInt())
            val actual = bitmap.getPixel(pixelX, pixelY)
            assertEquals("Animated backdrop did not follow green band", dominant(Color.GREEN), dominant(actual))
            assertTrue("Animated backdrop was not frosted", minOf(Color.red(actual), Color.blue(actual)) >= 5)
            val name = "${if (native) "native" else "legacy"}-moving.png"
            File(outputDir, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            snapshots.put(JSONObject().put("mode", if (native) "native_moving" else "legacy_moving")
                .put("requestOffsetPx", requestedOffset).put("completionOffsetPx", completedOffset)
                .put("centerColor", actual).put("file", name))
            assertTrue("Animated screenshot run did not finish", finished.await(8, TimeUnit.SECONDS))
            scenario.onActivity { assertEquals(native, stage.binding.renderTargetBackdropState()!!.active) }
            return actual
        } finally { bitmap.recycle() }
    }

    private fun animate(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage, durationMs: Long, native: Boolean) {
        val finished = CountDownLatch(1)
        scenario.onActivity {
            ValueAnimator.ofInt(0, stage.band * 2).apply {
                duration = durationMs; interpolator = LinearInterpolator()
                addUpdateListener { frame ->
                    stage.scroll.scrollTo(0, frame.animatedValue as Int)
                    stage.session.notifyScrollPositionChanged(stage.scroll)
                }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { finished.countDown() } })
                start()
            }
        }
        assertTrue("Warm-up timed out", finished.await(8, TimeUnit.SECONDS))
        scenario.onActivity { assertEquals(native, stage.binding.renderTargetBackdropState()!!.active) }
    }

    private fun capture(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage): Bitmap {
        var x = 0; var y = 0; var width = 0; var height = 0
        lateinit var windowBitmap: Bitmap
        scenario.onActivity {
            val location = IntArray(2)
            stage.target.getLocationInWindow(location)
            x = location[0]; y = location[1]; width = stage.target.width; height = stage.target.height
            windowBitmap = Bitmap.createBitmap(stage.window.decorView.width, stage.window.decorView.height, Bitmap.Config.ARGB_8888)
        }
        try {
            val done = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            scenario.onActivity { PixelCopy.request(stage.window, windowBitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper())) }
            assertTrue("PixelCopy callback timed out", done.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            return Bitmap.createBitmap(windowBitmap, x, y, width, height)
        } finally { windowBitmap.recycle() }
    }

    private fun compare(left: Bitmap, right: Bitmap): JSONObject {
        assertEquals(left.width, right.width); assertEquals(left.height, right.height)
        var sum = 0L; var maximum = 0
        val shifts = intArrayOf(0, 8, 16)
        for (y in 0 until left.height) for (x in 0 until left.width) {
            val a = left.getPixel(x, y); val b = right.getPixel(x, y)
            for (shift in shifts) {
                val delta = abs((a ushr shift and 255) - (b ushr shift and 255))
                sum += delta; maximum = maxOf(maximum, delta)
            }
        }
        return JSONObject().put("meanAbsoluteRgbDifference", sum.toDouble() / (left.width.toLong() * left.height * 3))
            .put("maxAbsoluteChannelDifference", maximum)
    }

    private fun dominant(color: Int): Int = intArrayOf(Color.red(color), Color.green(color), Color.blue(color)).indices
        .maxByOrNull { intArrayOf(Color.red(color), Color.green(color), Color.blue(color))[it] }!!
    private fun assertNear(message: String, expected: Int, actual: Int, tolerance: Int) {
        for (shift in intArrayOf(0, 8, 16, 24))
            assertTrue("$message: ${Integer.toHexString(expected)} -> ${Integer.toHexString(actual)}",
                abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= tolerance)
    }
    private fun percentile(values: List<Long>, probability: Double): Long = values[(ceil(values.size * probability).toInt() - 1).coerceIn(0, values.lastIndex)]
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do { if (condition()) return; SystemClock.sleep(40) } while (SystemClock.uptimeMillis() < deadline)
        fail(message)
    }
    private fun writeReport() { File(outputDir, "results.json").writeText(report.toString(2)) }

    private companion object {
        const val DURATION_MS = 3_000L
        val COLORS = intArrayOf(Color.RED, Color.GREEN, Color.BLUE)
    }
}
