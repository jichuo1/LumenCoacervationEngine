package com.lumen.coacervation.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.liquid.LiquidBackdropSource
import com.lumen.coacervation.engine.liquid.LiquidBlurBackendApi31
import com.lumen.coacervation.engine.liquid.LiquidBackdropSizingPolicy
import com.lumen.coacervation.engine.liquid.LiquidRefractionBackendApi33
import com.lumen.coacervation.engine.liquid.LiquidTokenResolver
import com.lumen.coacervation.engine.liquid.LiquidVisualTuningPolicy
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真正执行 HWUI 与 AGSL；纹理坐标和形状坐标分别验证，包含回弹后重用同一后端。 */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class LiquidSurfaceMappingInstrumentedTest {
    private val parameters = LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(true)).copy(
        refractionAmountDp = 0f, interiorDistortionDp = 0f, saturation = 1f)

    private fun source(): LiquidBackdropSource {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(800 * 600) { i -> Color.rgb(i % 800 * 255 / 800, i / 800 * 255 / 600, 30) }
        bitmap.setPixels(pixels, 0, 800, 0, 0, 800, 600)
        return LiquidBackdropSource.fromRealtimeBitmap(bitmap, 800, 600)
    }

    private fun render(draw: (Canvas) -> Unit): Bitmap {
        val reader = ImageReader.newInstance(800, 600, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        val node = RenderNode("liquid-surface-mapping").apply { setPosition(0, 0, 800, 600) }
        try {
            val canvas = node.beginRecording(800, 600)
            try { canvas.drawColor(Color.BLACK); draw(canvas) } finally { node.endRecording() }
            renderer.setSurface(reader.surface)
            renderer.setOpaque(true)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val deadline = SystemClock.uptimeMillis() + 5000
            var ready = reader.acquireNextImage()
            while (ready == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(10); ready = reader.acquireNextImage()
            }
            return requireNotNull(ready).use { image ->
                val buffer = requireNotNull(image.hardwareBuffer)
                try {
                    val hardware = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                    try { requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) }
                    finally { hardware.recycle() }
                } finally { buffer.close() }
            }
        } finally { renderer.destroy(); node.discardDisplayList(); reader.close() }
    }

    private fun assertGradient(bitmap: Bitmap, xs: List<Int>, ys: List<Int>) {
        try {
            for (y in ys) for (x in xs) {
                val pixel = bitmap.getPixel(x, y)
                assertTrue("x=$x y=$y actual=${Color.red(pixel)},${Color.green(pixel)}",
                    abs(Color.red(pixel) - x * 255 / 800) <= 3 && abs(Color.green(pixel) - y * 255 / 600) <= 3)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun nonzeroMorphBoundsUseTheHostOriginOnlyOnce() {
        val source = source()
        val driver = LiquidRefractionBackendApi33(parameters, 1f)
        try {
            driver.bindBackdrop(source)
            for (bounds in listOf(Rect(0, 0, 400, 250), Rect(40, 30, 440, 280))) {
                assertGradient(render { canvas ->
                    canvas.translate(120f, 140f)
                    driver.drawBackdrop(canvas, bounds, 0f, 120, 140, 1f, 0f, 1f, false)
                }, listOf(200, 300, 450), listOf(200, 250, 350))
            }
        } finally { driver.close(); source.close() }
    }

    @Test fun scaleAndSubpixelTranslationReturnToTheSameBackdropAfterRebound() {
        val source = source()
        val driver = LiquidRefractionBackendApi33(parameters, 1f)
        try {
            driver.bindBackdrop(source)
            for (scale in listOf(1f, .92f, .95f, 1f)) {
                val mapping = Matrix().apply { setScale(scale, scale); postTranslate(120.25f, 140.75f) }
                assertGradient(render { canvas ->
                    canvas.concat(mapping)
                    driver.drawBackdrop(canvas, Rect(40, 30, 440, 280), 0f, 120, 141,
                        if (scale < 1f) 1.85f else 1f, -1f, 1f, scale < 1f, mapping)
                }, listOf(200, 300, 450), listOf(200, 250, 350))
            }
        } finally { driver.close(); source.close() }
    }

    @Test fun blurFallbackKeepsNonzeroBoundsAndTransformAligned() {
        val source = source()
        try {
            for (mapped in listOf(false, true)) {
                // 每个 HardwareRenderer 拥有自己的效果节点，不能跨已 destroy 的渲染器复用缓存图层。
                val driver = LiquidBlurBackendApi31(.1f)
                driver.bindBackdrop(source)
                val mapping = Matrix().apply { setScale(.95f, .92f); postTranslate(120f, 140f) }
                try {
                    val bitmap = render { canvas ->
                        if (mapped) canvas.concat(mapping) else canvas.translate(120f, 140f)
                        driver.drawBackdrop(canvas, Rect(40, 30, 440, 280), 0f, 120, 140,
                            1f, 0f, 1f, false, if (mapped) mapping else null)
                    }
                    assertGradient(bitmap, listOf(200, 300, 450), listOf(200, 250, 350))
                } finally { driver.close() }
            }
        } finally { source.close() }
    }

    @Test fun foreignFallbackUsesTheSameCustomImageProfileAsRefraction() {
        val size = LiquidBackdropSizingPolicy.resolve(800, 600)
        for (crisp in listOf(false, true)) {
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size.width * size.height) { i ->
                if ((i % size.width / 12 + i / size.width / 12) % 2 == 0) Color.rgb(240, 30, 10)
                else Color.rgb(20, 180, 220)
            }
            bitmap.setPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
            val source = LiquidBackdropSource.fromCustomBitmap(bitmap, "mapping-fixture", 800, 600, 1f, crisp)
            val driver = LiquidRefractionBackendApi33(parameters, 1f)
            try {
                driver.bindBackdrop(source)
                val bounds = Rect(40, 30, 440, 280)
                val refracted = render { canvas ->
                    canvas.translate(120f, 140f)
                    driver.drawBackdrop(canvas, bounds, 0f, 120, 140, 1f, 0f, 1f, false)
                }
                val fallback = render { canvas ->
                    canvas.translate(120f, 140f)
                    source.drawOpticalRegion(canvas, bounds, 0f, 120f, 140f, 255)
                }
                try {
                    for (y in 200..350 step 15) for (x in 200..450 step 15) {
                        val a = refracted.getPixel(x, y)
                        val b = fallback.getPixel(x, y)
                        assertTrue("crisp=$crisp x=$x y=$y colors=$a,$b",
                            abs(Color.red(a) - Color.red(b)) <= 3 && abs(Color.blue(a) - Color.blue(b)) <= 3)
                    }
                } finally { refracted.recycle(); fallback.recycle() }
            } finally { driver.close(); source.close() }
        }
    }
}
