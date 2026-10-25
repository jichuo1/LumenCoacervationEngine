package com.lumen.coacervation.engine

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.liquid.LiquidBackdropSource
import com.lumen.coacervation.engine.liquid.LiquidEffectProfile
import com.lumen.coacervation.engine.liquid.LiquidRefractionBackendApi33
import com.lumen.coacervation.engine.liquid.LiquidTokenResolver
import com.lumen.coacervation.engine.liquid.LiquidVisualTuningPolicy
import com.lumen.coacervation.engine.model.LiquidParameters
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenEffectTuningPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

/**
 * 玻璃采样范围的设备证明（2026-10-26，只加测试、不改引擎）：REFRACTION 后端的窗口玻璃只会读到
 * [GlassSamplingFootprint] 给出的截图区域。区域外的像素怎么变，GPU 渲染出的玻璃都逐位不变；
 * 区域内紧贴玻璃的像素一变，输出就会变（证明测试不是空转）。
 *
 * 推导（canvas 坐标，coord 为正在着色的像素）：
 * - 深内部早退 0；内部透镜 `interiorOffset` ≤ 0.385·ID·oi（r(1−r²) 在 r≤1 的最大值，r>1 时透镜权重为 0）；
 * - 折射位移 `edgeWeight·RA·edgeBoost·|grad|·edgeReach` ≤ RA·oi（edgeBoost ≤ oi、|grad| = 1、edgeReach ≤ 1）；
 * - 其上再叠色散 `CS·edgeBoost·edgeWeight` ≤ CS·oi 或散射 `SR·edgeBoost·mix(0.42,1,ew)·edgeReach` ≤ SR·oi（二者不叠加）；
 * - 故 Δ = oi·(RA + 0.385·ID + max(CS, SR))，oi 在后端被夹到 ≤ 1.85。
 * 取样点 → 根坐标：未映射时是纯平移（viewX/Y），映射时是 w>0 的射影变换（凸区域的像落在角点像的包围盒内），
 * 回弹拉伸只单调地改 y；再乘 backdropScale 到位图坐标。LINEAR 读 floor(p−0.5) 与其右下邻像素，CLAMP 只读位图内像素。
 * 抗锯齿边缘的像素中心至多在 bounds 外 0.5px。
 */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class LiquidGlassFootprintInstrumentedTest {
    private val fullWidth = 1080
    private val fullHeight = 2400
    private val bitmapWidth = 671
    private val bitmapHeight = 1491
    private val density = 2.75f
    private val target = 760 to 520

    private data class Case(
        val name: String,
        val profile: LiquidEffectProfile,
        val bounds: Rect,
        val viewX: Int,
        val viewY: Int,
        val radius: Float,
        val opticalIntensity: Float = 1f,
        val stretchDirY: Float = 0f,
        val motionLite: Boolean = false,
        val localToBackdrop: Matrix? = null,
        val stretchSampling: FloatArray? = null,
        val sensitivity: Boolean = true
    )

    private fun parameters(profile: LiquidEffectProfile): LiquidParameters = LumenEffectTuningPolicy.applyTo(
        LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark = false), profile, dark = false),
        LumenEffectTuning.DEFAULT
    )

    private fun cases(): List<Case> {
        val bar = Rect(40, 40, 700, 200)
        val button = Rect(80, 260, 248, 428)
        val affine = Matrix().apply { setScale(0.94f, 0.94f); postRotate(2.5f); postTranslate(190f, 820f) }
        val cases = ArrayList<Case>()
        for (profile in LiquidEffectProfile.entries) {
            cases += Case("$profile bar centre", profile, bar, 180, 1300, 80f)
            cases += Case("$profile bar oi1.85 up-stretch", profile, bar, 180, 1300, 80f, 1.85f, -1f)
            cases += Case("$profile bar oi1.6 diagonal", profile, bar, 180, 1300, 80f, 1.6f, 0.6f)
            cases += Case("$profile bar motionLite", profile, bar, 180, 1300, 80f, 1.4f, 0f, motionLite = true)
            cases += Case("$profile round button", profile, button, 300, 700, 84f, 1.85f)
            // 贴左缘与顶缘：折射位移被 edgeReach 收敛、CLAMP 只读边缘列。
            cases += Case("$profile flush to screen edge", profile, bar, -40, -40, 80f, 1.85f, sensitivity = false)
            cases += Case("$profile mapped (scaled, rotated)", profile, bar, 0, 0, 80f, 1.85f, localToBackdrop = affine)
            cases += Case("$profile stretched viewport", profile, bar, 180, 900, 80f, 1.85f, -1f,
                stretchSampling = floatArrayOf(700f, 1400f, 0.35f))
        }
        return cases
    }

    @Test fun pixelsOutsideTheFootprintNeverChangeTheGlass() {
        val random = Random(20261026)
        val report = StringBuilder()
        for (case in cases()) {
            val params = parameters(case.profile)
            val base = noise(random)
            val footprint = GlassSamplingFootprint.bitmapRect(case.bounds, case.viewX, case.viewY, case.localToBackdrop,
                case.stretchSampling, params, density, fullWidth, fullHeight, bitmapWidth, bitmapHeight)
            val outside = base.copy(Bitmap.Config.ARGB_8888, true).also { flipOutside(it, footprint) }
            val first = render(case, params, base)
            val again = render(case, params, base)
            assertTrue("${case.name}: GPU rendering must be deterministic", first.sameAs(again))
            val changedOutside = render(case, params, outside)
            assertTrue("${case.name}: pixels outside $footprint changed the glass", first.sameAs(changedOutside))
            if (case.sensitivity) {
                val inside = base.copy(Bitmap.Config.ARGB_8888, true).also { flipRimBand(it, case, footprint) }
                assertTrue("${case.name}: the rim band inside the footprint must matter",
                    !first.sameAs(render(case, params, inside)))
            }
            report.append(case.name).append(" footprint=").append(footprint.toShortString()).append('\n')
            listOf(first, again, changedOutside, base, outside).forEach(Bitmap::recycle)
        }
        android.util.Log.i("Lumen-Footprint", report.toString())
    }

    /**
     * 上界有多紧：二分出"翻转玻璃外扩 m 位图像素以外的全部像素、输出仍不变"的最小 m，与推导值对照。
     * 实测值不得超过推导值（否则推导漏了采样项）；二者差距决定将来区域比较能省下多少误唤醒。
     */
    @Test fun derivedBoundIsNeverSmallerThanTheMeasuredReach() {
        val random = Random(7)
        val report = StringBuilder()
        for (case in cases().filter { it.sensitivity && it.localToBackdrop == null && it.stretchSampling == null }) {
            val params = parameters(case.profile)
            val base = noise(random)
            val reference = render(case, params, base)
            val glass = GlassSamplingFootprint.bitmapRect(case.bounds, case.viewX, case.viewY, null, null, null,
                density, fullWidth, fullHeight, bitmapWidth, bitmapHeight)
            val derived = GlassSamplingFootprint.bitmapRect(case.bounds, case.viewX, case.viewY, null, null, params,
                density, fullWidth, fullHeight, bitmapWidth, bitmapHeight)
            val derivedMargin = derived.right - glass.right
            fun unchangedWithMargin(margin: Int): Boolean {
                val keep = Rect(glass).apply { inset(-margin, -margin) }
                val flipped = base.copy(Bitmap.Config.ARGB_8888, true).also { flipOutside(it, keep) }
                return render(case, params, flipped).sameAs(reference).also { flipped.recycle() }
            }
            var low = 0
            var high = derivedMargin
            assertTrue("${case.name}: the derived margin itself must be safe", unchangedWithMargin(high))
            while (low < high) {
                val mid = (low + high) / 2
                if (unchangedWithMargin(mid)) high = mid else low = mid + 1
            }
            report.append(case.name).append(": measured ").append(low).append(" px, derived ").append(derivedMargin)
                .append(" px\n")
            assertTrue("${case.name}: measured reach $low exceeds derived $derivedMargin", low <= derivedMargin)
            base.recycle(); reference.recycle()
        }
        android.util.Log.i("Lumen-Footprint", report.toString())
    }

    private fun noise(random: Random): Bitmap {
        val pixels = IntArray(bitmapWidth * bitmapHeight) { random.nextInt() or (0xFF shl 24) }
        return Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
        }
    }

    /** 区域外每个像素取反（保持不透明）：最强的"变化"，任何一次越界读取都会被看见。 */
    private fun flipOutside(bitmap: Bitmap, keep: Rect) {
        val pixels = IntArray(bitmapWidth * bitmapHeight)
        bitmap.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
        for (y in 0 until bitmapHeight) for (x in 0 until bitmapWidth) {
            if (!keep.contains(x, y)) pixels[y * bitmapWidth + x] = pixels[y * bitmapWidth + x] xor 0x00FFFFFF
        }
        bitmap.setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
    }

    /** 灵敏度对照：只翻转区域内、玻璃边界外 2–6 位图像素的那一圈（折射带会把它折进来）。 */
    private fun flipRimBand(bitmap: Bitmap, case: Case, footprint: Rect) {
        val glass = GlassSamplingFootprint.bitmapRect(case.bounds, case.viewX, case.viewY, case.localToBackdrop,
            case.stretchSampling, null, density, fullWidth, fullHeight, bitmapWidth, bitmapHeight)
        val outer = Rect(glass).apply { inset(-6, -6); intersect(footprint) }
        val inner = Rect(glass).apply { inset(-2, -2) }
        val pixels = IntArray(bitmapWidth * bitmapHeight)
        bitmap.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
        for (y in outer.top until outer.bottom) for (x in outer.left until outer.right) {
            if (!inner.contains(x, y)) pixels[y * bitmapWidth + x] = pixels[y * bitmapWidth + x] xor 0x00FFFFFF
        }
        bitmap.setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
    }

    private fun render(case: Case, params: LiquidParameters, bitmap: Bitmap): Bitmap {
        val (width, height) = target
        val source = LiquidBackdropSource.fromRealtimeBitmap(bitmap, fullWidth, fullHeight)
        val driver = LiquidRefractionBackendApi33(params, density)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        val root = RenderNode("footprint-proof").apply { setPosition(0, 0, width, height) }
        try {
            driver.bindBackdrop(source)
            val canvas = root.beginRecording(width, height)
            try {
                driver.drawBackdrop(canvas, case.bounds, case.radius, case.viewX, case.viewY, case.opticalIntensity,
                    case.stretchDirY, 1f, case.motionLite, case.localToBackdrop, case.stretchSampling)
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

/**
 * 候选实现（测试内）：REFRACTION 窗口玻璃会读到的截图位图区域。`params == null` 时只返回玻璃自身
 * 映射到位图的范围（Δ = 0），供灵敏度对照使用。
 */
internal object GlassSamplingFootprint {
    private const val MAX_OPTICAL_INTENSITY = 1.85f
    private const val INTERIOR_LENS_PEAK = 0.385f

    fun maxOffsetPx(params: LiquidParameters, density: Float): Float = MAX_OPTICAL_INTENSITY * density * (
        params.refractionAmountDp + INTERIOR_LENS_PEAK * params.interiorDistortionDp +
            maxOf(params.chromaticShiftDp, params.scatteringRadiusDp))

    fun bitmapRect(
        bounds: Rect, viewX: Int, viewY: Int, localToBackdrop: Matrix?, stretchSampling: FloatArray?,
        params: LiquidParameters?, density: Float, fullWidth: Int, fullHeight: Int, bitmapWidth: Int, bitmapHeight: Int
    ): Rect {
        val delta = (params?.let { maxOffsetPx(it, density) } ?: 0f) + 1f // +1：抗锯齿边缘像素中心在 bounds 外 0.5px
        val canvasRect = RectF(bounds.left - delta, bounds.top - delta, bounds.right + delta, bounds.bottom + delta)
        val root = RectF(canvasRect)
        if (localToBackdrop != null) localToBackdrop.mapRect(root) else root.offset(viewX.toFloat(), viewY.toFloat())
        if (stretchSampling != null && stretchSampling[1] > 0f && stretchSampling[2] != 0f) {
            root.top = stretched(root.top, stretchSampling)
            root.bottom = stretched(root.bottom, stretchSampling)
        }
        val sx = bitmapWidth.toFloat() / fullWidth
        val sy = bitmapHeight.toFloat() / fullHeight
        // LINEAR 读 floor(p−0.5) 及其后一格；各留 2 texel 余量。
        return Rect(
            (floor(root.left * sx).toInt() - 2).coerceIn(0, bitmapWidth),
            (floor(root.top * sy).toInt() - 2).coerceIn(0, bitmapHeight),
            (ceil(root.right * sx).toInt() + 2).coerceIn(0, bitmapWidth),
            (ceil(root.bottom * sy).toInt() + 2).coerceIn(0, bitmapHeight)
        )
    }

    /** 与 shader `backdropPoint` 相同的单调拉伸映射（视口外的 y 由同一公式外推，取两端点即可）。 */
    private fun stretched(y: Float, sampling: FloatArray): Float {
        var q = (y - sampling[0]) / sampling[1]
        val bottom = sampling[2] < 0f
        if (bottom) q = 1f - q
        val s = kotlin.math.abs(sampling[2])
        val k = 1f + s
        q = k * k * q / (1f + 0.3f * s + 0.7f * s * k * q)
        if (bottom) q = 1f - q
        return sampling[0] + q * sampling[1]
    }
}
