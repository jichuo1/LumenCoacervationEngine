package com.lumen.coacervation.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.glow.GlowChromeBlurApi31
import com.lumen.coacervation.engine.glow.GlowChromeGlassApi31
import com.lumen.coacervation.engine.glow.GlowContentCaptureApi31
import com.lumen.coacervation.engine.material.FrostedChromeGlassApi31
import com.lumen.coacervation.engine.material.LensRefractionPolicy
import com.lumen.coacervation.engine.material.ModernMaterialDrawables
import com.lumen.coacervation.engine.model.SurfaceRole
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用设备真正的 HWUI/RenderEffect，不启动宿主或修改设置。CPU 位图不执行效果链。 */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class ChromeBackdropCoordinatesInstrumentedTest {
    @Test fun chromeOverlayInheritsItsParentsLiveColorInsteadOfReplacingIt() {
        val overlay = ModernMaterialDrawables.chromeOverlay(Color.rgb(32, 32, 32), 0f, 1f,
            SurfaceRole.SELECTED_ITEM, true)
        overlay.setBounds(0, 0, 32, 32)
        fun sample(background: Int): Int {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(background)
                overlay.draw(android.graphics.Canvas(bitmap))
                return bitmap.getPixel(16, 16)
            } finally { bitmap.recycle() }
        }
        val green = sample(Color.GREEN)
        val blue = sample(Color.BLUE)
        assertTrue("The selection must retain the parent's changing image", green != blue)
        assertTrue(Color.green(green) > Color.blue(green))
        assertTrue(Color.blue(blue) > Color.green(blue))
        assertTrue(Color.alpha(green) == 255 && Color.alpha(blue) == 255)
    }

    @Test fun slowSubpixelLinesRemainStableThroughTheBlur() {
        val source = GlowContentCaptureApi31()
        val recording = source.begin(800, 600)
        val paint = Paint().apply { color = Color.WHITE }
        recording.drawColor(Color.rgb(32, 32, 32))
        for (y in 0 until 600 step 4) recording.drawRect(0f, y.toFloat(), 800f, y + 1f, paint)
        source.end()
        val stats = StringBuilder()
        val reader = ImageReader.newInstance(800, 600, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        val root = RenderNode("slow-blur-proof").apply { setPosition(0, 0, 800, 600) }
        renderer.setSurface(reader.surface)
        renderer.setOpaque(false)
        renderer.setContentRoot(root)
        try {
            for (radius in listOf(9f, 51f)) {
              var baselineRange = 0.0
              for (prefilter in listOf(false, true)) {
                val glass = GlowChromeGlassApi31(54) { _, _, _, _, _, _ ->
                    if (prefilter) GlowChromeBlurApi31.create(radius)
                    else RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
                }
                try {
                    val values = ArrayList<Double>()
                    repeat(17) { step ->
                        source.node.translationY = step * 0.25f
                        val canvas = root.beginRecording(800, 600)
                        canvas.drawRenderNode(source.node)
                        canvas.translate(220f, 300f)
                        glass.draw(canvas, Rect(12, 8, 412, 168), 0f, source, 220f, 300f, 1f, 1f, 0f, null)
                        root.endRecording()
                        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                        val deadline = SystemClock.uptimeMillis() + 5000
                        var image = reader.acquireNextImage()
                        while (image == null && SystemClock.uptimeMillis() < deadline) {
                            SystemClock.sleep(10); image = reader.acquireNextImage()
                        }
                        requireNotNull(image).use { ready ->
                            val buffer = requireNotNull(ready.hardwareBuffer)
                            try {
                                val hardware = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                                val bitmap = requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                                hardware.recycle()
                                try {
                                    var total = 0.0
                                    for (y in 370 until 402) for (x in 380 until 444) total += Color.red(bitmap.getPixel(x, y))
                                    values += total / (32 * 64)
                                } finally { bitmap.recycle() }
                            } finally { buffer.close() }
                        }
                    }
                    val range = values.max() - values.min()
                    stats.append("radius=$radius prefilter=$prefilter values=$values range=$range\n")
                    if (!prefilter) baselineRange = range
                    else {
                        assertTrue("Aliasing at radius=$radius: $range vs $baselineRange", range <= maxOf(3.0, baselineRange * 0.1))
                        assertTrue("Lost energy at radius=$radius: $values", values.all { abs(it - 87.75) <= 3.0 })
                    }
                } finally { glass.close() }
              }
            }
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "blur-probe.txt").writeText(stats.toString())
        } finally { renderer.destroy(); root.discardDisplayList(); reader.close(); source.release() }
    }

    private fun render(source: GlowContentCaptureApi31, glass: GlowChromeGlassApi31, name: String,
        contentToHost: Matrix? = null, advance: (() -> Unit)? = null): Bitmap {
        val reader = ImageReader.newInstance(800, 600, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        val root = RenderNode("coordinate-proof").apply { setPosition(0, 0, 800, 600) }
        try {
            val canvas = root.beginRecording(800, 600)
            try {
                canvas.drawRenderNode(source.node)
                canvas.translate(220f, 300f)
                glass.draw(canvas, Rect(12, 8, 412, 168), 0f, source,
                    220f, 300f, 1f, 1f, 0f, null, contentToHost)
            } finally { root.endRecording() }
            renderer.setSurface(reader.surface)
            renderer.setOpaque(false)
            renderer.setContentRoot(root)
            fun nextFrame(): android.media.Image {
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                val deadline = SystemClock.uptimeMillis() + 5000
                var image = reader.acquireNextImage()
                while (image == null && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(20)
                    image = reader.acquireNextImage()
                }
                return requireNotNull(image) { "GPU did not produce a frame" }
            }
            var ready = nextFrame()
            if (advance != null) {
                ready.close()
                advance()
                ready = nextFrame()
            }
            return ready.use {
                val buffer = requireNotNull(it.hardwareBuffer)
                try {
                    val hardware = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))
                    val result = requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                    hardware.recycle()
                    val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$name.png")
                    file.outputStream().use { stream -> result.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                    result
                } finally { buffer.close() }
            }
        } finally {
            renderer.destroy()
            root.discardDisplayList()
            reader.close()
        }
    }

    private fun gradient(): GlowContentCaptureApi31 = GlowContentCaptureApi31().also { source ->
        val canvas = source.begin(800, 600)
        // 采用连续不透明纹理：2×2 独立矩形在非整数缩放时会出现覆盖缝隙，
        // 混入下面的原图颜色，测到的是图元覆盖而不是取样坐标。
        val pixels = IntArray(800 * 600) { index ->
            Color.rgb(index % 800 * 255 / 800, index / 800 * 255 / 600, 30)
        }
        val bitmap = Bitmap.createBitmap(pixels, 800, 600, Bitmap.Config.ARGB_8888)
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        } finally { source.end() }
        // Bitmap 已交给 display list，随 source.release() 释放引用，不提前 recycle。
    }

    @Test fun descendantMatrixKeepsScaledSamplingAlignedWithNonzeroBounds() {
        val source = gradient()
        val shader = RuntimeShader("uniform shader content; half4 main(float2 p) { return content.eval(p); }")
        val glass = GlowChromeGlassApi31(24) { _, _, _, _, _, _ ->
            RenderEffect.createRuntimeShaderEffect(shader, "content")
        }
        // 内容 → 按钮：非均匀缩放与父层位移组合；取样必须走完整矩阵，不能只减原点。
        val transform = Matrix().apply {
            setValues(floatArrayOf(.8f, 0f, -220f, 0f, .75f, -300f, 0f, 0f, 1f))
        }
        try {
            val bitmap = render(source, glass, "chrome-descendant-matrix", contentToHost = transform)
            try {
                for (y in listOf(320, 370, 420)) for (x in listOf(250, 410, 540)) {
                    val pixel = bitmap.getPixel(x, y)
                    assertTrue("scaled sampling x=$x y=$y actual=${Color.red(pixel)},${Color.green(pixel)} expected=${x / .8f * 255 / 800},${y / .75f * 255 / 600}",
                        abs(Color.red(pixel) - x / .8f * 255 / 800) <= 3 &&
                            abs(Color.green(pixel) - y / .75f * 255 / 600) <= 3)
                }
            } finally { bitmap.recycle() }
        } finally { glass.close(); source.release() }
    }

    @Test fun runtimeIdentityKeepsNonzeroBoundsAndHostOffsetsAligned() {
        val source = gradient()
        val shader = RuntimeShader("uniform shader content; half4 main(float2 p) { return content.eval(p); }")
        val glass = GlowChromeGlassApi31(24) { _, _, _, _, _, _ ->
            RenderEffect.createRuntimeShaderEffect(shader, "content")
        }
        try {
            val bitmap = render(source, glass, "chrome-identity")
            try {
                for (y in listOf(320, 370, 440)) for (x in listOf(250, 410, 600)) {
                    val pixel = bitmap.getPixel(x, y)
                    assertTrue("x=$x y=$y actual=${Color.red(pixel)},${Color.green(pixel)}",
                        abs(Color.red(pixel) - x * 255 / 800) <= 2 && abs(Color.green(pixel) - y * 255 / 600) <= 2)
                }
            } finally { bitmap.recycle() }
        } finally { glass.close(); source.release() }
    }

    @Test fun softLensSamplesTheExpectedUnderlyingCoordinates() {
        val source = gradient()
        val glass = FrostedChromeGlassApi31.create(1f)
        try {
            val bitmap = render(source, glass, "chrome-soft-coordinates")
            try {
                for (localY in listOf(4f, 16f, 40f, 80f, 120f, 144f, 156f))
                    for (localX in listOf(4f, 16f, 80f, 200f, 320f, 384f, 396f)) {
                    val x = 232 + localX.toInt()
                    val y = 308 + localY.toInt()
                    val sampledX = 232 + LensRefractionPolicy.nodeSampleCoordinate(localX + 0.5f, 400f,
                        LensRefractionPolicy.CENTER_GAIN_X, LensRefractionPolicy.RIM_PUSH_X)
                    val sampledY = 308 + LensRefractionPolicy.nodeSampleCoordinate(localY + 0.5f, 160f,
                        LensRefractionPolicy.CENTER_GAIN_Y, LensRefractionPolicy.RIM_PUSH_Y)
                    val expectedR = (sampledX * 255 / 800 * LensRefractionPolicy.LUMINANCE_GAIN + LensRefractionPolicy.LUMINANCE_BIAS).roundToInt()
                    val expectedG = (sampledY * 255 / 600 * LensRefractionPolicy.LUMINANCE_GAIN + LensRefractionPolicy.LUMINANCE_BIAS).roundToInt()
                    val actual = bitmap.getPixel(x, y)
                    assertTrue("($x,$y) expected=$expectedR,$expectedG actual=${Color.red(actual)},${Color.green(actual)}",
                        abs(Color.red(actual) - expectedR) <= 3 && abs(Color.green(actual) - expectedG) <= 3)
                }
            } finally { bitmap.recycle() }
        } finally { glass.close(); source.release() }
    }

    @Test fun movingSharedContentUpdatesGlassWithoutRerecordingTheGlassOrRoot() {
        val source = gradient()
        val shader = RuntimeShader("uniform shader content; half4 main(float2 p) { return content.eval(p); }")
        val glass = GlowChromeGlassApi31(24) { _, _, _, _, _, _ ->
            RenderEffect.createRuntimeShaderEffect(shader, "content")
        }
        try {
            val bitmap = render(source, glass, "chrome-moving-content") {
                source.node.translationX = 32f
                source.node.translationY = -24f
            }
            try {
                val pixel = bitmap.getPixel(432, 388)
                assertTrue("Moving content sampled from stale coordinates: ${Color.red(pixel)},${Color.green(pixel)}",
                    abs(Color.red(pixel) - 400 * 255 / 800) <= 2 &&
                        abs(Color.green(pixel) - 412 * 255 / 600) <= 2)
            } finally { bitmap.recycle() }
        } finally { glass.close(); source.release() }
    }
}
