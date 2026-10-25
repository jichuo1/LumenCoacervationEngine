package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.material.LensRefractionPolicy
import com.lumen.coacervation.engine.material.ModernBackdropBlur
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 2026-10-25 性能整改"只快不变"的护栏：模糊与透镜重映射改写后，必须与改写前的实现逐位相同。
 *
 * 下面的 `Reference*` 是改写前生产代码的逐字副本，不得为了让用例通过而修改它们。
 */
class BitExactKernelsTest {
    private fun image(random: Random, width: Int, height: Int, kind: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        when (kind) {
            0 -> random.nextInt() or (0xFF shl 24)
            1 -> random.nextInt()
            2 -> if ((x / 7 + y / 5) % 3 == 0) 0 else random.nextInt()
            3 -> (0xFF shl 24) or ((x * 3 and 255) shl 16) or ((y * 5 and 255) shl 8) or (x + y and 255)
            // 大片纯色加少量细节：UI 内容的典型形态，覆盖重映射的四邻同色捷径。
            else -> if (random.nextInt(6) == 0) random.nextInt() else 0xFFF4F1EE.toInt()
        }
    }

    @Test fun blurMatchesThePreRewriteImplementationForEveryRadius() {
        val random = Random(20261025)
        val sizes = listOf(1 to 1, 1 to 37, 37 to 1, 2 to 2, 3 to 70, 70 to 3, 17 to 23, 84 to 84, 292 to 62, 130 to 300)
        for ((width, height) in sizes) for (radius in 1..32) for (kind in 0..4) {
            val source = image(random, width, height, kind)
            val expected = ReferenceBoxBlur.blur(source, width, height, radius)
            assertArrayEquals("${width}x$height r=$radius kind=$kind", expected,
                ModernBackdropBlur.blur(source, width, height, radius))
            val working = source.copyOf()
            assertArrayEquals("blurInto ${width}x$height r=$radius kind=$kind", expected,
                ModernBackdropBlur.blurInto(working, IntArray(source.size), width, height, radius))
        }
    }

    /** 静态磨砂与实时透镜两个 worker 并发调用：逐线程累加器与共享商表不得串扰。 */
    @Test fun concurrentBlursOnDifferentWidthsStayExact() {
        val random = Random(7)
        val jobs = listOf(292 to 62, 84 to 84, 216 to 480, 31 to 9).map { (width, height) ->
            val source = image(random, width, height, 1)
            Triple(width to height, source, ReferenceBoxBlur.blur(source, width, height, 11))
        }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 64).map { index ->
                val (size, source, _) = jobs[index % jobs.size]
                pool.submit(Callable { ModernBackdropBlur.blur(source, size.first, size.second, 11) })
            }
            results.forEachIndexed { index, future -> assertArrayEquals(jobs[index % jobs.size].third, future.get()) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test fun remapMatchesThePreRewriteImplementation() {
        val random = Random(99)
        repeat(800) { iteration ->
            val dw = 1 + random.nextInt(300)
            val dh = 1 + random.nextInt(120)
            val margin = random.nextInt(16)
            val sw = dw + 2 * margin
            val sh = dh + 2 * margin
            val source = image(random, sw, sh, iteration % 5)
            val strength = if (iteration % 5 == 0) 0f else random.nextFloat() * 1.5f
            val expected = IntArray(dw * dh)
            val actual = IntArray(dw * dh)
            ReferenceLensRemap.remap(source, sw, sh, margin, expected, dw, dh, strength)
            LensRefractionPolicy.remap(source, sw, sh, margin, actual, dw, dh, strength)
            assertArrayEquals("iteration=$iteration", expected, actual)
        }
    }

    /**
     * 四邻同色捷径的前提：任意小数坐标下，同一通道值 v 经四权重插值、加 0.5 截断后仍是 v。
     * 覆盖边界小数（0、最小正数、0.5 两侧、最接近 1 的值）与大量随机小数，每组穷举全部 256 个 v。
     */
    @Test fun identicalNeighboursInterpolateToThemselves() {
        val random = Random(11)
        val specials = floatArrayOf(0f, Float.MIN_VALUE, 1e-7f, 0.25f, 0.5f, Math.nextDown(0.5f), Math.nextUp(0.5f),
            0.75f, Math.nextDown(1f))
        val fractions = FloatArray(2_000) { if (it < specials.size) specials[it] else random.nextFloat() }
        for (fx in fractions) for (step in 0 until 20) {
            val fy = fractions[(step * 97 + fx.toRawBits()).mod(fractions.size)]
            val w00 = (1f - fx) * (1f - fy)
            val w10 = fx * (1f - fy)
            val w01 = (1f - fx) * fy
            val w11 = fx * fy
            for (value in 0..255) {
                val interpolated = value * w00 + value * w10 + value * w01 + value * w11
                assertEquals("v=$value fx=$fx fy=$fy", value, (interpolated + 0.5f).toInt().coerceIn(0, 255))
            }
        }
    }
}

/** 改写前 `ModernBackdropBlur` 的逐字副本（逐通道整数除法、逐列竖向趟）。 */
private object ReferenceBoxBlur {
    fun blur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        var input = source.copyOf()
        var output = IntArray(source.size)
        repeat(3) {
            pass(input, output, width, height, radius, horizontal = true)
            val swap = input; input = output; output = swap
            pass(input, output, width, height, radius, horizontal = false)
            val next = input; input = output; output = next
        }
        return input
    }

    private fun pass(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val stride = if (horizontal) 1 else width
        val window = radius * 2 + 1
        val last = length - 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * width else line
            var a = 0; var r = 0; var g = 0; var b = 0
            for (offset in -radius..radius) {
                val index = if (offset < 0) 0 else if (offset > last) last else offset
                val color = input[base + index * stride]
                a += color ushr 24
                r += (color ushr 16) and 255
                g += (color ushr 8) and 255
                b += color and 255
            }
            for (position in 0 until length) {
                output[base + position * stride] = ((a / window) shl 24) or
                    ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val drop = position - radius
                val dropIndex = if (drop < 0) 0 else if (drop > last) last else drop
                val dropColor = input[base + dropIndex * stride]
                a -= dropColor ushr 24
                r -= (dropColor ushr 16) and 255
                g -= (dropColor ushr 8) and 255
                b -= dropColor and 255
                val add = position + radius + 1
                val addIndex = if (add < 0) 0 else if (add > last) last else add
                val addColor = input[base + addIndex * stride]
                a += addColor ushr 24
                r += (addColor ushr 16) and 255
                g += (addColor ushr 8) and 255
                b += addColor and 255
            }
        }
    }
}

/** 改写前 `LensRefractionPolicy.remap` 的逐字副本（逐通道 `channel()`，无同色捷径）。 */
private object ReferenceLensRemap {
    fun remap(source: IntArray, sw: Int, sh: Int, margin: Int, out: IntArray, dw: Int, dh: Int, strength: Float) {
        val innerW = (sw - 2 * margin).coerceAtLeast(1).toFloat()
        val innerH = (sh - 2 * margin).coerceAtLeast(1).toFloat()
        val maxX = (sw - 1).toFloat()
        val maxY = (sh - 1).toFloat()
        val columnLeft = IntArray(dw)
        val columnRight = IntArray(dw)
        val columnFrac = FloatArray(dw)
        for (x in 0 until dw) {
            val u = (x + 0.5f) / dw * 2f - 1f
            val sx = ((LensRefractionPolicy.lens(u, LensRefractionPolicy.CENTER_GAIN_X * strength,
                LensRefractionPolicy.RIM_PUSH_X * strength) + 1f) * 0.5f * innerW + margin - 0.5f).coerceIn(0f, maxX)
            val x0 = sx.toInt().coerceIn(0, sw - 1)
            columnLeft[x] = x0
            columnRight[x] = (x0 + 1).coerceAtMost(sw - 1)
            columnFrac[x] = sx - x0
        }
        for (y in 0 until dh) {
            val v = (y + 0.5f) / dh * 2f - 1f
            val sy = ((LensRefractionPolicy.lens(v, LensRefractionPolicy.CENTER_GAIN_Y * strength,
                LensRefractionPolicy.RIM_PUSH_Y * strength) + 1f) * 0.5f * innerH + margin - 0.5f).coerceIn(0f, maxY)
            val y0 = sy.toInt().coerceIn(0, sh - 1)
            val y1 = (y0 + 1).coerceAtMost(sh - 1)
            val fy = sy - y0
            for (x in 0 until dw) {
                val x0 = columnLeft[x]
                val x1 = columnRight[x]
                out[y * dw + x] = bilinear(source[y0 * sw + x0], source[y0 * sw + x1],
                    source[y1 * sw + x0], source[y1 * sw + x1], columnFrac[x], fy)
            }
        }
    }

    private fun bilinear(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int {
        val w00 = (1f - fx) * (1f - fy)
        val w10 = fx * (1f - fy)
        val w01 = (1f - fx) * fy
        val w11 = fx * fy
        return (channel(c00, c10, c01, c11, 24, w00, w10, w01, w11) shl 24) or
            (channel(c00, c10, c01, c11, 16, w00, w10, w01, w11) shl 16) or
            (channel(c00, c10, c01, c11, 8, w00, w10, w01, w11) shl 8) or
            channel(c00, c10, c01, c11, 0, w00, w10, w01, w11)
    }

    @Suppress("LongParameterList")
    private fun channel(c00: Int, c10: Int, c01: Int, c11: Int, shift: Int, w00: Float, w10: Float, w01: Float, w11: Float): Int {
        val value = ((c00 ushr shift) and 255) * w00 + ((c10 ushr shift) and 255) * w10 +
            ((c01 ushr shift) and 255) * w01 + ((c11 ushr shift) and 255) * w11
        return (value + 0.5f).toInt().coerceIn(0, 255)
    }
}
