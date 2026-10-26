package com.lumen.coacervation.engine

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.liquid.LiquidBackdropSource
import com.lumen.coacervation.engine.liquid.LiquidBlurBackendApi31
import com.lumen.coacervation.engine.liquid.LiquidGlassFootprint
import com.lumen.coacervation.engine.liquid.LiquidGlassFootprintPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * BLUR 后端（API 31–32，及 33+ 折射失败后的降级）的采样范围设备证明：窗口玻璃只会读到
 * [LiquidGlassFootprint]（Δ = 0）外扩 [LiquidGlassFootprintPolicy.blurReachTexels] 的截图区域——
 * 引擎在截图请求时用的同一份代码。区域外的像素怎么变，GPU 渲染出的玻璃都逐位不变；紧贴玻璃外侧的
 * 像素一变，输出就会变（证明测试不是空转）。
 *
 * 推导：HWUI `Blur::convertRadiusToSigma` 给 σ = 0.57735·r + 0.5（r 为位图坐标里的 `nextBlurRadius`），
 * Skia 高斯核截断在 ceil(3σ)，CLAMP 只读位图内像素。需在 API 31（BLUR 的主场）与 33+ 各跑一次。
 */
@SdkSuppress(minSdkVersion = 31)
@RunWith(AndroidJUnit4::class)
class LiquidBlurFootprintInstrumentedTest {
    private val fullWidth = 1080
    private val fullHeight = 2400
    private val density = 2.75f
    private val target = 760 to 520

    private data class Case(
        val name: String,
        val blurDp: Float,
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val bounds: Rect,
        val viewX: Int,
        val viewY: Int,
        val radius: Float,
        val localToBackdrop: Matrix? = null
    )

    private fun cases(): List<Case> {
        val bar = Rect(40, 40, 700, 200)
        val button = Rect(80, 260, 248, 428)
        val affine = Matrix().apply { setScale(0.94f, 0.94f); postRotate(2.5f); postTranslate(190f, 820f) }
        val out = ArrayList<Case>()
        // 实时档 22dp、标准档 20dp；位图 671×1491 是实时档 1M 像素预算下的尺寸，1080×2400 是不缩放。
        for (blur in listOf(22f, 20f)) for ((w, h) in listOf(671 to 1491, 1080 to 2400)) {
            out += Case("blur${blur.toInt()} ${w}x$h bar", blur, w, h, bar, 180, 1300, 80f)
            out += Case("blur${blur.toInt()} ${w}x$h button", blur, w, h, button, 300, 700, 84f)
            out += Case("blur${blur.toInt()} ${w}x$h mapped", blur, w, h, bar, 0, 0, 80f, affine)
        }
        return out
    }

    private fun reach(case: Case): Int = LiquidGlassFootprintPolicy.blurReachTexels(case.blurDp * density,
        fullWidth, fullHeight, case.bitmapWidth, case.bitmapHeight)

    /** 生产实现给出的位图区域：玻璃（Δ = 0）外扩 [extraTexels]。 */
    private fun footprint(case: Case, extraTexels: Int): Rect {
        val out = IntArray(4)
        LiquidGlassFootprint().bitmapRect(case.bounds.left, case.bounds.top, case.bounds.right, case.bounds.bottom,
            case.viewX.toFloat(), case.viewY.toFloat(), case.localToBackdrop, 0f,
            case.bitmapWidth.toFloat() / fullWidth, case.bitmapHeight.toFloat() / fullHeight,
            case.bitmapWidth, case.bitmapHeight, out, 0, extraTexels)
        return Rect(out[0], out[1], out[2], out[3])
    }

    @Test fun pixelsOutsideTheFootprintNeverChangeTheGlass() {
        val random = Random(20261026)
        for (case in cases()) {
            val base = noise(random, case)
            val first = render(case, base)
            assertTrue("${case.name}: GPU rendering must be deterministic", first.sameAs(render(case, base)))
            val region = footprint(case, reach(case))
            val outside = base.copy(Bitmap.Config.ARGB_8888, true).also { flipOutside(it, region) }
            assertTrue("${case.name}: pixels outside $region changed the glass", first.sameAs(render(case, outside)))
            // 灵敏度对照：只翻转玻璃外 0–4 纹素那一圈，模糊核一定会把它带进来。
            val inside = base.copy(Bitmap.Config.ARGB_8888, true).also {
                flipBand(it, footprint(case, 4), footprint(case, 0))
            }
            assertTrue("${case.name}: the band next to the glass must matter", !first.sameAs(render(case, inside)))
            listOf(first, base, outside, inside).forEach(Bitmap::recycle)
        }
    }

    /** 上界有多紧：二分出"翻转玻璃外扩 m 纹素以外的全部像素、输出仍不变"的最小 m，不得超过推导值。 */
    @Test fun derivedReachIsNeverSmallerThanTheMeasuredReach() {
        val random = Random(31)
        val report = StringBuilder("API ${Build.VERSION.SDK_INT}\n")
        for (case in cases()) {
            val base = noise(random, case)
            val reference = render(case, base)
            val threeSigma = reach(case)
            fun unchangedWithMargin(margin: Int): Boolean {
                val flipped = base.copy(Bitmap.Config.ARGB_8888, true).also { flipOutside(it, footprint(case, margin)) }
                return render(case, flipped).sameAs(reference).also { flipped.recycle() }
            }
            var high = threeSigma
            assertTrue("${case.name}: the derived reach $high itself must be safe", unchangedWithMargin(high))
            var low = 0
            while (low < high) {
                val mid = (low + high) / 2
                if (unchangedWithMargin(mid)) high = mid else low = mid + 1
            }
            report.append(case.name).append(": derived ").append(threeSigma).append(" measured ").append(low)
                .append(" texels\n")
            base.recycle(); reference.recycle()
        }
        android.util.Log.i("Lumen-BlurFootprint", report.toString())
    }

    private fun noise(random: Random, case: Case): Bitmap {
        val pixels = IntArray(case.bitmapWidth * case.bitmapHeight) { random.nextInt() or (0xFF shl 24) }
        return Bitmap.createBitmap(case.bitmapWidth, case.bitmapHeight, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, case.bitmapWidth, 0, 0, case.bitmapWidth, case.bitmapHeight)
        }
    }

    /** 翻转 [outer] 内、[inner] 外的那一圈。 */
    private fun flipBand(bitmap: Bitmap, outer: Rect, inner: Rect) {
        val w = bitmap.width
        val pixels = IntArray(w * bitmap.height)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, bitmap.height)
        for (y in outer.top until outer.bottom) for (x in outer.left until outer.right) {
            if (!inner.contains(x, y)) pixels[y * w + x] = pixels[y * w + x] xor 0x00FFFFFF
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, bitmap.height)
    }

    private fun flipOutside(bitmap: Bitmap, keep: Rect) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!keep.contains(x, y)) pixels[y * w + x] = pixels[y * w + x] xor 0x00FFFFFF
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun render(case: Case, bitmap: Bitmap): Bitmap {
        val (width, height) = target
        val source = LiquidBackdropSource.fromRealtimeBitmap(bitmap, fullWidth, fullHeight)
        val driver = LiquidBlurBackendApi31(case.blurDp * density)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        val root = RenderNode("blur-footprint").apply { setPosition(0, 0, width, height) }
        try {
            driver.bindBackdrop(source)
            val canvas = root.beginRecording(width, height)
            try {
                driver.drawBackdrop(canvas, case.bounds, case.radius, case.viewX, case.viewY, 1f, 0f, 1f, false,
                    case.localToBackdrop, null)
            } finally { root.endRecording() }
            renderer.setSurface(reader.surface)
            renderer.setOpaque(false)
            renderer.setContentRoot(root)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val deadline = SystemClock.uptimeMillis() + 5000
            var image = reader.acquireNextImage()
            while (image == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(10)
                image = reader.acquireNextImage()
            }
            return requireNotNull(image) { "GPU did not produce a frame" }.use {
                val buffer = requireNotNull(it.hardwareBuffer)
                try {
                    val hardware = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                    requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)).also { hardware.recycle() }
                } finally { buffer.close() }
            }
        } finally {
            driver.close()
            renderer.destroy()
            root.discardDisplayList()
            reader.close()
        }
    }
}
