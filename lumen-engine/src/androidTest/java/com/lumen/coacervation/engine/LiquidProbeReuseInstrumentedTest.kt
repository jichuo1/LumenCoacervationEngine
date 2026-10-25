package com.lumen.coacervation.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.liquid.LiquidBackdropSource
import com.lumen.coacervation.engine.liquid.LiquidCaptureOutcome
import com.lumen.coacervation.engine.liquid.LiquidFeedbackSuppressor
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * 静止探测复用（2026-10-25 功耗）：用设备真实的 Skia 软件路径核对复用的两个前提——
 * 同一原始截图、同一遮罩与底图下抑制结果逐位相同；从基准恢复的像素与完整抑制逐位相同。
 */
@RunWith(AndroidJUnit4::class)
class LiquidProbeReuseInstrumentedTest {
    private val width = 671
    private val height = 1490
    private val palette = LumenPalette(Color.GREEN, Color.BLACK, Color.CYAN, Color.YELLOW, Color.DKGRAY,
        Color.rgb(246, 242, 244), Color.GRAY, Color.WHITE, Color.LTGRAY)

    private fun capture(random: Random): LiquidBackdropSource {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { if (it % 7 == 0) random.nextInt() or (0xFF shl 24) else 0xFFF4F1EE.toInt() }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return LiquidBackdropSource.fromRealtimeBitmap(bitmap, 1080, 2400)
    }

    private fun copyOf(source: LiquidBackdropSource): LiquidBackdropSource {
        val bitmap = source.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        return LiquidBackdropSource.fromRealtimeBitmap(bitmap, 1080, 2400)
    }

    private fun mask() = Path().apply {
        addRoundRect(24f, 60f, 647f, 160f, 50f, 50f, Path.Direction.CW)
        addRoundRect(24f, 1330f, 647f, 1440f, 55f, 55f, Path.Direction.CW)
    }

    @Test fun reusedProbesAreExactAndRestoringFromTheBaselineMatchesFullSuppression() {
        val stable = LiquidBackdropSource.create(palette, 1080, 2400).apply { markPublished() }
        val feedback = LiquidFeedbackSuppressor(paddingPx = 12f)
        val random = Random(5)
        val raw = capture(random)

        // 基准：上一次被绑定的、已抑制的截图。
        val baseline = copyOf(raw)
        assertEquals(LiquidCaptureOutcome.SUPPRESSED, feedback.sanitizeRealtimeCapture(baseline, stable, mask(), true))

        // 探测 1：完整路径，留原始截图，抑制后与基准相同。
        val probe1 = copyOf(raw)
        feedback.rememberProbeRaw(probe1)
        val outcome1 = feedback.sanitizeRealtimeCapture(probe1, stable, mask(), true)
        val same1 = probe1.bitmap.sameAs(baseline.bitmap)
        assertTrue("same raw, mask and backdrop must suppress to the same pixels", same1)
        feedback.commitProbe(1L, stable, baseline, true, outcome1, same1)

        // 探测 2：原始截图逐字节相同 → 复用上一次结论。
        val probe2 = copyOf(raw)
        assertEquals(outcome1, feedback.reuseIdenticalProbe(2L, probe2, stable, baseline, true, true))

        // 复用的截图若最终要被使用：从基准恢复的像素必须与完整抑制逐位相同。
        val fullySuppressed = copyOf(raw)
        feedback.sanitizeRealtimeCapture(fullySuppressed, stable, mask(), true)
        Canvas(probe2.bitmap).drawBitmap(baseline.bitmap, 0f, 0f,
            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        assertTrue(probe2.bitmap.sameAs(fullySuppressed.bitmap))

        // 复用成功后它就是新的比较对象：静止期每一次探测都应连续复用，而不是隔一次才复用一次。
        assertEquals(outcome1, feedback.reuseIdenticalProbe(3L, copyOf(raw), stable, baseline, true, true))
        assertEquals(outcome1, feedback.reuseIdenticalProbe(4L, copyOf(raw), stable, baseline, true, true))

        // 任何前提不满足都不复用。
        assertNull("serial gap", feedback.reuseIdenticalProbe(6L, copyOf(raw), stable, baseline, true, true))
        assertNull("mask changed", feedback.reuseIdenticalProbe(5L, copyOf(raw), stable, baseline, true, false))
        val other = LiquidBackdropSource.create(palette, 1080, 2400).apply { markPublished() }
        assertNull("backdrop changed", feedback.reuseIdenticalProbe(5L, copyOf(raw), other, baseline, true, true))
        assertNull("baseline changed", feedback.reuseIdenticalProbe(5L, copyOf(raw), stable, copyOf(baseline), true, true))
        val changed = copyOf(raw).also { it.bitmap.setPixel(300, 700, Color.RED) }
        assertNull("one pixel changed", feedback.reuseIdenticalProbe(5L, changed, stable, baseline, true, true))
        feedback.forgetProbe()
        assertNull("forgotten", feedback.reuseIdenticalProbe(5L, copyOf(raw), stable, baseline, true, true))
        assertNotNull(outcome1)
        feedback.close()
    }

    /** 区域比较：区域外的差异被忽略、区域内一个像素的差异被看见、没有玻璃时视为相同、尺寸不符一律不同。 */
    @Test fun regionComparisonSeesOnlyTheGlassRegions() {
        val feedback = LiquidFeedbackSuppressor(paddingPx = 12f)
        val random = Random(9)
        val a = capture(random).bitmap
        val b = a.copy(Bitmap.Config.ARGB_8888, true)
        val rects = intArrayOf(10, 20, 300, 140, 400, 1300, 660, 1480)
        b.setPixel(5, 5, Color.RED)            // 区域外
        b.setPixel(500, 700, Color.BLUE)       // 区域外
        assertTrue(feedback.regionsEqual(b, a, rects))
        assertTrue(!a.sameAs(b))
        b.setPixel(299, 139, Color.GREEN)      // 第一块区域的右下角（含）
        assertTrue(!feedback.regionsEqual(b, a, rects))
        val c = a.copy(Bitmap.Config.ARGB_8888, true).also { it.setPixel(659, 1479, Color.GREEN) }
        assertTrue(!feedback.regionsEqual(c, a, rects))
        val d = a.copy(Bitmap.Config.ARGB_8888, true).also { it.setPixel(300, 139, Color.GREEN) } // 右边界不含
        assertTrue(feedback.regionsEqual(d, a, rects))
        assertTrue(feedback.regionsEqual(b, a, IntArray(0)))
        val small = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        assertTrue(!feedback.regionsEqual(small, a, rects))
        feedback.close()
    }
}
