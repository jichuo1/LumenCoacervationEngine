package com.lumen.coacervation.engine.material

import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenEffectTuningPolicy
import com.lumen.coacervation.engine.model.SurfaceRole
import java.util.concurrent.atomic.AtomicReferenceArray
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

    /**
     * 透镜"输入未变即沿用"比对位图的独立预算（每个采样器）：密度 3 下顶栏、底栏胶囊与三个圆按钮
     * 合计约 229 KiB，局部会话的多个表面按先来先得分配，超出后只是不做比对、照常全量处理。
     */
    const val LIVE_SAMPLE_MEMO_BYTES = 512L * 1024

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
    /** 窗口和的上限：最大窗口 65（半径 32）× 255。两条 16 位通道各自装得下，互不进位。 */
    private const val MAX_WINDOW_SUM = 65 * 255
    private const val LANES = 0x00FF00FF

    /**
     * 每种窗口一张商表 `table[sum] = sum / window`（窗口 2r+1，长度 window×255+1）。
     * 全部 32 种半径合计约 272 KiB，实际同进程只会用到其中几种；表内容只由窗口决定，
     * 并发首次构建时谁先发布用谁，结果相同。
     */
    private val quotients = AtomicReferenceArray<ByteArray>(MAX_WINDOW_SUM / 255 + 1)

    /** 竖向趟的逐列累加器，按线程复用：静态磨砂与实时透镜两个 worker 并发调用时互不共享。 */
    private val columns = ThreadLocal<IntArray>()

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
        val table = quotientTable(radius * 2 + 1)
        val accumulators = columnAccumulators(width)
        var input = pixels
        var output = scratch
        repeat(3) {
            horizontal(input, output, width, height, radius, table)
            val swap = input; input = output; output = swap
            vertical(input, output, width, height, radius, table, accumulators)
            val next = input; input = output; output = next
        }
        return input
    }

    private fun quotientTable(window: Int): ByteArray {
        quotients.get(window)?.let { return it }
        val table = ByteArray(window * 255 + 1) { (it / window).toByte() }
        quotients.compareAndSet(window, null, table)
        return quotients.get(window)
    }

    /** 前 width 个是 R/B 累加，后 width 个是 A/G 累加。 */
    private fun columnAccumulators(width: Int): IntArray {
        val existing = columns.get()
        if (existing != null && existing.size >= width * 2) return existing
        return IntArray(width * 2).also(columns::set)
    }

    /**
     * 滑动窗口盒式模糊的横向一趟。输出与逐通道 `sum / window` 的原实现逐位相同：
     * - R/B、A/G 各打包进一个 Int 的两条 16 位通道一起累加（`color and 0x00FF00FF`），
     *   窗口和最大 [MAX_WINDOW_SUM] = 16575 < 65536，通道之间不会进位；
     * - 整数除法换成查商表，商与 `sum / window` 完全相同。
     *
     * ⚠️ **不要把窗口进出写成局部函数**：捕获可变局部变量的局部 fun 会让 Kotlin 把累加器
     * 装箱成 `Ref.IntRef` 挪到堆上（2026-09-22 真机实测 2.0–4.6ms/次，柔光掉帧主因）。
     * 也不要改回 Long 乘倒数：HotSpot 上更快，ART 上反而慢 7–15%（2026-10-25 模拟器实测）。
     */
    private fun horizontal(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, table: ByteArray) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Backdrop replaced")
        val last = width - 1
        for (line in 0 until height) {
            val base = line * width
            var rb = 0
            var ag = 0
            for (offset in -radius..radius) {
                val color = input[base + (if (offset < 0) 0 else if (offset > last) last else offset)]
                rb += color and LANES
                ag += (color ushr 8) and LANES
            }
            for (position in 0 until width) {
                output[base + position] = ((table[ag ushr 16].toInt() and 255) shl 24) or
                    ((table[rb ushr 16].toInt() and 255) shl 16) or
                    ((table[ag and 0xFFFF].toInt() and 255) shl 8) or
                    (table[rb and 0xFFFF].toInt() and 255)
                val drop = position - radius
                val add = position + radius + 1
                val dropColor = input[base + (if (drop < 0) 0 else drop)]
                val addColor = input[base + (if (add > last) last else add)]
                rb += (addColor and LANES) - (dropColor and LANES)
                ag += ((addColor ushr 8) and LANES) - ((dropColor ushr 8) and LANES)
            }
        }
    }

    /**
     * 竖向一趟：按行推进、每列一组累加器，内存顺序访问（原实现逐列跨行跳读）。
     * 每列的窗口和、钳位与商都与逐列实现相同，输出逐位不变。
     */
    private fun vertical(
        input: IntArray, output: IntArray, width: Int, height: Int, radius: Int,
        table: ByteArray, accumulators: IntArray
    ) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Backdrop replaced")
        val last = height - 1
        java.util.Arrays.fill(accumulators, 0, width * 2, 0)
        for (offset in -radius..radius) {
            val row = (if (offset < 0) 0 else if (offset > last) last else offset) * width
            for (x in 0 until width) {
                val color = input[row + x]
                accumulators[x] += color and LANES
                accumulators[width + x] += (color ushr 8) and LANES
            }
        }
        for (y in 0 until height) {
            val outRow = y * width
            val drop = y - radius
            val add = y + radius + 1
            val dropRow = (if (drop < 0) 0 else drop) * width
            val addRow = (if (add > last) last else add) * width
            for (x in 0 until width) {
                val rb = accumulators[x]
                val ag = accumulators[width + x]
                output[outRow + x] = ((table[ag ushr 16].toInt() and 255) shl 24) or
                    ((table[rb ushr 16].toInt() and 255) shl 16) or
                    ((table[ag and 0xFFFF].toInt() and 255) shl 8) or
                    (table[rb and 0xFFFF].toInt() and 255)
                val dropColor = input[dropRow + x]
                val addColor = input[addRow + x]
                accumulators[x] = rb + (addColor and LANES) - (dropColor and LANES)
                accumulators[width + x] = ag + ((addColor ushr 8) and LANES) - ((dropColor ushr 8) and LANES)
            }
        }
    }
}
