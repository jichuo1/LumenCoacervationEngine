package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidRealtimeCapturePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LiquidRealtimeCapturePolicyTest {
    @Test
    fun `sampling follows a pixel budget instead of a fixed scale`() {
        assertEquals(3, LiquidRealtimeCapturePolicy.BUFFER_COUNT)
        val budget = LiquidRealtimeCapturePolicy.TARGET_SAMPLE_PIXELS
        assertEquals(1_000_000L, budget)

        // 1080p 面板按 100 万像素预算收敛到约 0.62 倍，为高刷新率滑动留出余量。
        val common = LiquidRealtimeCapturePolicy.resolveSize(1080, 2400)
        assertTrue(common.pixels <= budget)
        assertTrue(common.width / 1080f > 0.60f && common.width / 1080f < 0.64f)

        // 1440p 面板不再随分辨率平方增长：缓冲尺寸与 1080p 基本一致，倍率约 0.47。
        val dense = LiquidRealtimeCapturePolicy.resolveSize(1440, 3200)
        assertTrue(dense.pixels <= budget)
        assertTrue(dense.width / 1440f < 0.5f)
        assertTrue(abs(dense.pixels - common.pixels) < budget / 20)

        // 低分屏不会被反向放大到超过原有清晰度。
        val small = LiquidRealtimeCapturePolicy.resolveSize(720, 1280)
        assertEquals(0.72f, small.width / 720f, 0.01f)

        val large = LiquidRealtimeCapturePolicy.resolveSize(4000, 3000)
        assertTrue(large.pixels <= budget)
        assertTrue(abs(large.width.toFloat() / large.height - 4f / 3f) < 0.01f)
    }

    @Test
    fun `tighter budgets shrink the buffer and never fall below the floor`() {
        val tight = LiquidRealtimeCapturePolicy.resolveSize(1440, 3200, pixelBudget = 600_000L)
        assertTrue(tight.pixels <= 600_000L)

        val floored = LiquidRealtimeCapturePolicy.resolveSize(1440, 3200, pixelBudget = 1L)
        assertTrue(floored.pixels <= LiquidRealtimeCapturePolicy.MIN_SAMPLE_PIXELS)
        assertTrue(floored.width > 0 && floored.height > 0)
    }

    @Test
    fun `only large surfaces drop to reduced scatter taps`() {
        assertFalse(LiquidRealtimeCapturePolicy.useReducedScatterTaps(1080, 620))
        assertFalse(LiquidRealtimeCapturePolicy.useReducedScatterTaps(1300, 900))
        assertTrue(LiquidRealtimeCapturePolicy.useReducedScatterTaps(1440, 3200))
    }

    @Test
    fun `support and failure boundaries stay explicit`() {
        assertFalse(LiquidRealtimeCapturePolicy.isSupported(30, hardwareAccelerated = true))
        assertFalse(LiquidRealtimeCapturePolicy.isSupported(33, hardwareAccelerated = false))
        assertTrue(LiquidRealtimeCapturePolicy.isSupported(33, hardwareAccelerated = true))
        assertFalse(LiquidRealtimeCapturePolicy.shouldSuspend(3))
        assertTrue(LiquidRealtimeCapturePolicy.shouldSuspend(4))
    }

    @Test
    fun `frame pacing follows the fastest supported mode up to one hundred twenty hertz`() {
        assertEquals(
            120f,
            LiquidRealtimeCapturePolicy.targetRefreshRate(
                currentRefreshRate = 60f,
                supportedRefreshRates = listOf(60f, 90f, 120f, 144f)
            ),
            0f
        )
        assertEquals(
            90f,
            LiquidRealtimeCapturePolicy.targetRefreshRate(
                currentRefreshRate = 90f,
                supportedRefreshRates = listOf(60f, 90f, 144f)
            ),
            0f
        )
        assertEquals(8_333_333L, LiquidRealtimeCapturePolicy.frameIntervalNanos(120f))
        assertFalse(LiquidRealtimeCapturePolicy.isFrameDue(8_000_000L, 8_333_333L))
        assertTrue(LiquidRealtimeCapturePolicy.isFrameDue(8_333_333L, 8_333_333L))
        assertEquals(255, LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA)
    }

    @Test
    fun `stretch optics rise smoothly and stay bounded`() {
        val idle = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0f)
        val middle = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0.09f)
        val maximum = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0.18f)

        assertEquals(1f, idle, 0f)
        assertTrue(middle > idle)
        assertTrue(maximum > middle)
        assertEquals(maximum, LiquidRealtimeCapturePolicy.stretchOpticalIntensity(1f), 0f)
    }

    /**
     * 回弹强度必须量化（2026-09-22 真机实证）。
     *
     * 强度每变一次，渲染层就要把整组可见玻璃表面重录一遍；而系统 stretch 距离是连续
     * 衰减的，不量化时回弹期几乎每帧都越过发布门——102 秒滑动采样里坏帧的 draw 录制
     * 中位 3.43ms，是全局中位 1.19ms 的近 3 倍，现场就是"滑动偶发不跟手"。
     */
    @Test
    fun `stretch optics are quantised so a rebound cannot re-record every frame`() {
        val step = LiquidRealtimeCapturePolicy.STRETCH_INTENSITY_STEP
        assertTrue(step > 0f)
        val values = (0..900).map {
            LiquidRealtimeCapturePolicy.stretchOpticalIntensity(it / 5000f)
        }
        // 每个取值都必须落在整数级上。
        values.forEach {
            val steps = ((it - 1f) / step)
            assertEquals(Math.round(steps).toFloat(), steps, 1e-3f)
        }
        // 级数必须有限且远小于采样点数，否则等于没量化。
        val distinct = values.distinct().size
        assertTrue("量化后级数应远少于采样数: $distinct", distinct < 40)
        // 终态必须精确归 1，不能停在半亮。
        assertEquals(1f, LiquidRealtimeCapturePolicy.stretchOpticalIntensity(0f), 0f)
        // 单调不回头：回弹衰减过程中强度只降不升。
        var previous = Float.MAX_VALUE
        for (i in 900 downTo 0) {
            val v = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(i / 5000f)
            assertTrue(v <= previous + 1e-6f)
            previous = v
        }
    }

    /**
     * 静止门控（2026-09-23 真机：高级材质静止 5 秒渲染 154 帧、每帧 GPU 10ms，柔光同条件 0 帧；
     * 加门控后 0 帧，静止画面与旧实现逐像素一致）。
     */
    @Test
    fun `identical captures are dropped only when compared against the bound realtime source`() {
        assertTrue(LiquidRealtimeCapturePolicy.isUnchanged(true, false) { true })
        // 内容变了：必须绑定新截图。
        assertFalse(LiquidRealtimeCapturePolicy.isUnchanged(true, false) { false })
        // 抑制期后端绑的是稳定底图：内容恰好相同也必须绑回实时截图，否则玻璃停在磨砂观感。
        assertFalse(LiquidRealtimeCapturePolicy.isUnchanged(false, false) { true })
        // 抑制期完成的截图同样不参与静止判定。
        assertFalse(LiquidRealtimeCapturePolicy.isUnchanged(true, true) { true })
        // 基准不成立时不做逐像素比较（省一次 4MB memcmp）。
        var compared = false
        LiquidRealtimeCapturePolicy.isUnchanged(false, false) { compared = true; true }
        assertFalse(compared)
    }

    @Test
    fun `idle needs consecutive confirmations and bounded probe latency`() {
        // 刚画完的那一帧可能尚未合成：单张"相同"不足以判定静止。
        assertFalse(LiquidRealtimeCapturePolicy.shouldEnterIdle(1))
        assertTrue(LiquidRealtimeCapturePolicy.shouldEnterIdle(LiquidRealtimeCapturePolicy.IDLE_CONFIRMATIONS))
        assertTrue(LiquidRealtimeCapturePolicy.IDLE_CONFIRMATIONS >= 2)
        assertTrue(LiquidRealtimeCapturePolicy.WAKE_SETTLE_FRAMES >= 1)
        // 纯 RenderThread 动画不经 UI 线程绘制，兜底探测把滞后上限钉在 1 秒以内。
        assertTrue(LiquidRealtimeCapturePolicy.IDLE_PROBE_MS in 250L..1000L)
    }
}
