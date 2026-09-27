package com.lumen.coacervation.engine.material

import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenEffectTuningPolicy
import com.lumen.coacervation.engine.model.SurfaceRole
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** [live]：表面下方的内容层要经 LiveBackdropSampler 透镜采样（悬浮胶囊/顶栏）。 */
internal data class ModernSurfaceStyle(
    val tintAlpha: Int,
    val upperEdgeAlpha: Int,
    val lowerEdgeAlpha: Int,
    val live: Boolean = false
)

/** Pure appearance policy: caller-owned radii are deliberately never changed here. */
internal object ModernMaterialPolicy {
    const val MAX_BACKDROP_PIXELS = 160_000
    const val SAMPLE_SCALE = 0.20f

    /**
     * 实时透镜底图的最小重采样间隔（毫秒）。
     *
     * 每个悬浮表面一次采样 = 一次内容层软件光栅化 + 预乘/模糊/透镜重映射/提亮/解预乘
     * 五趟逐像素。挂在 pre-draw 上等于跟着刷新率跑：120Hz 设备实测 **traversals 中位
     * 13.92ms**（同页同滚动，高级材质只有 0.23ms——它走 PixelCopy 异步路径），掉帧率
     * 48%，GPU 却只有 5ms，是纯 UI 线程瓶颈。
     *
     * 运动中把重采样降到约 35Hz：透镜纹理最多滞后一个间隔，而它是 10dp 模糊后的
     * 低分辨率映射，滞后在视觉上不可辨；**静止态逐字不变**——收尾一定补一次采样
     * （[LiveBackdropSampler] 的 trailing 重投递），所以停下来看到的仍是精确映射。
     *
     * 滚动与显式动画（手风琴、翻页）**共用这一个节奏**。曾给显式动画单设过"整段不采、
     * 静默 96ms 后补采"的冻结窗口来躲 UI 线程尖刺，用户看到的是顶栏/底栏透出的背景等
     * 动画播完才瞬间跳变（2026-09-23）；尖刺的正解是把逐像素运算挪到后台线程，见
     * [LiveBackdropSampler]。
     */
    const val LIVE_SAMPLE_MIN_INTERVAL_MS = 28L

    fun sampleSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(SAMPLE_SCALE.toDouble(), sqrt(MAX_BACKDROP_PIXELS.toDouble() / (width.toDouble() * height)))
        var w = (width * scale).toInt().coerceAtLeast(1)
        var h = (height * scale).toInt().coerceAtLeast(1)
        // Extremely narrow windows can hit the minimum one-pixel axis.
        if (w.toLong() * h > MAX_BACKDROP_PIXELS) {
            if (w > h) w = (MAX_BACKDROP_PIXELS / h).coerceAtLeast(1)
            else h = (MAX_BACKDROP_PIXELS / w).coerceAtLeast(1)
        }
        return w to h
    }

    /** 边框描边宽度（px）：原值 `max(density, 1) × 0.65`，按厚度倍率缩放。 */
    fun edgeStrokePx(density: Float, widthScale: Float): Float =
        density.coerceAtLeast(1f) * .65f * (if (widthScale.isFinite() && widthScale > 0f) widthScale else 1f)

    /** 同 [surface]，边框顶/底 alpha 按 [LumenEffectTuning.edgeHighlightIntensity] 缩放。 */
    fun surface(role: SurfaceRole, dark: Boolean, tuning: LumenEffectTuning): ModernSurfaceStyle {
        val base = surface(role, dark)
        if (tuning.edgeHighlightIntensity == 1f) return base
        return base.copy(
            upperEdgeAlpha = LumenEffectTuningPolicy.edgeAlpha(base.upperEdgeAlpha, tuning),
            lowerEdgeAlpha = LumenEffectTuningPolicy.edgeAlpha(base.lowerEdgeAlpha, tuning)
        )
    }

    fun surface(role: SurfaceRole, dark: Boolean): ModernSurfaceStyle {
        val tint = when (role) {
            // 呼出面板与浮动条同一套"映射+薄罩"语言：透镜采样给出真实内容映射，
            // 色罩保持较厚一档——采样源是未压暗的 pager，罩厚一点近似 scrim 的
            // 压暗效果，面板内映射的亮度才不会比周围底色亮一截。
            SurfaceRole.MODAL -> if (dark) 208 else 210
            // 悬浮表面叠了实时透镜采样，色罩要更薄才"通透"；可读性由模糊与折射保证而非遮盖。
            // 再压薄一档：提亮后的透镜纹理要在暗色内容下也能被看见，61% 的罩会把它埋掉。
            SurfaceRole.FLOATING -> if (dark) 120 else 112
            SurfaceRole.TOP_BAR -> if (dark) 146 else 132
            SurfaceRole.SELECTED_ITEM -> if (dark) 218 else 210
            SurfaceRole.MOTION_SURFACE -> if (dark) 218 else 216
            SurfaceRole.FILLED_BUTTON -> 235
            SurfaceRole.TEXT_BUTTON -> if (dark) 166 else 158
            else -> if (dark) 206 else 204
        }
        val upper = when (role) {
            SurfaceRole.TOP_BAR -> 0
            SurfaceRole.MODAL -> if (dark) 34 else 120
            SurfaceRole.SELECTED_ITEM -> if (dark) 16 else 60
            // 悬浮条多一档顶沿高光：透镜是"玻璃"不是"雾"，边沿需要看得见的受光。
            SurfaceRole.FLOATING -> if (dark) 56 else 140
            else -> if (dark) 26 else 112
        }
        val lower = when (role) {
            SurfaceRole.TOP_BAR -> 0
            SurfaceRole.MODAL -> if (dark) 14 else 28
            SurfaceRole.SELECTED_ITEM -> if (dark) 5 else 12
            SurfaceRole.FLOATING -> if (dark) 16 else 32
            else -> if (dark) 10 else 24
        }
        // 呼出面板同样参与实时透镜采样：面板正下方就是被 scrim 压暗的 pager 内容，
        // 映射后经较厚色罩压回 scrim 亮度，读作"内容透进磨砂"而不是一块死灰。
        val live = role == SurfaceRole.FLOATING || role == SurfaceRole.TOP_BAR ||
            role == SurfaceRole.MODAL
        return ModernSurfaceStyle(tint, upper, lower, live)
    }

    fun blurRadius(sampleWidth: Int, fullWidth: Int, density: Float): Int =
        (22f * density * sampleWidth / fullWidth.coerceAtLeast(1)).roundToInt().coerceIn(2, 18)
}

/**
 * Three separable box passes approximate a Gaussian; only used off the UI thread on bounded bitmaps.
 * Stateless and reentrant: the static frost worker and the live-lens worker call it concurrently.
 */
internal object ModernBackdropBlur {
    fun blur(source: IntArray, width: Int, height: Int, radius: Int): IntArray =
        blurInto(source.copyOf(), IntArray(source.size), width, height, radius)

    /**
     * 热路径变体：复用调用方的两块缓冲，逐帧零分配。
     *
     * [pixels] 会被就地改写，结果落在返回的那一块缓冲里（三轮双向共 6 趟，缓冲交换偶数次，
     * 因此返回的恒是 [pixels] 本身；调用方仍应按返回值取用，不要假设这一点）。
     */
    fun blurInto(pixels: IntArray, scratch: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        require(scratch.size == pixels.size)
        require(radius in 1..32)
        var input = pixels
        var output = scratch
        repeat(3) {
            pass(input, output, width, height, radius, horizontal = true)
            val swap = input; input = output; output = swap
            pass(input, output, width, height, radius, horizontal = false)
            val next = input; input = output; output = next
        }
        return input
    }

    /**
     * 滑动窗口盒式模糊的一趟。
     *
     * ⚠️ **不要把窗口进出写成局部函数**：捕获可变局部变量的局部 fun 会让 Kotlin 把
     * `a/r/g/b` 装箱成 `Ref.IntRef` 挪到堆上，每次自增都变成堆读写、还多一次调用。
     * 真机实测（102×102 采样图、半径 10、6 趟）原写法 **2.0–4.6ms/次**，是柔光皮肤
     * UI 线程掉帧的主因；手工内联后同样的算法只要几百微秒。输出逐位不变——钳位、
     * 累加顺序与整数除法都保持原样。
     */
    private fun pass(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Backdrop replaced")
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
