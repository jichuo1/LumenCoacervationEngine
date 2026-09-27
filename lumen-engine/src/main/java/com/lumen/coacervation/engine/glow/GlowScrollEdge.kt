package com.lumen.coacervation.engine.glow

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** 悬浮栏所在的滚动边缘。 */
public enum class GlowScrollEdge { TOP, BOTTOM }

/**
 * 滚动边缘溶解的纯几何（Apple 称 scroll edge effect）：内容滚进悬浮栏下方时，逐渐溶进窗口底图。
 *
 * 溶解量 = 覆盖度 × 竖向剖面：
 * - **覆盖度**只看"有多少内容已经滚进栏下"。静止在顶部时内容从栏下沿开始，覆盖度为 0，
 *   页标题不会被无端淡化；滚过 [fadeLengthPx]（约等于栏高）后满覆盖。
 * - **剖面**从栏外沿到栏内再到栏内沿逐级减弱，出栏后 [tailPx] 内归零，边界是一段软过渡
 *   而不是一条硬线。
 */
public object GlowScrollEdgePolicy {
    /** 外沿（屏幕边到胶囊外缘）：几乎全溶，内容在这条窄缝里只剩影子。 */
    const val OUTER_ALPHA = 0.9f
    /** 胶囊中线：图标与标签所在的位置。 */
    const val CENTER_ALPHA = 0.62f
    /** 胶囊内沿。 */
    const val INNER_ALPHA = 0.36f
    const val TAIL_DP = 24f
    /** 覆盖度的发布精度：1/64 以内的变化不重录溶解层。 */
    const val COVERAGE_QUANTUM = 1f / 64f
    private const val MIN_STOP_GAP = 1e-3f

    fun topCoverage(scrollY: Int, fadeLengthPx: Float): Float =
        if (fadeLengthPx <= 0f) 0f else (scrollY / fadeLengthPx).coerceIn(0f, 1f)

    fun bottomCoverage(scrollY: Int, scrollRange: Int, fadeLengthPx: Float): Float =
        if (fadeLengthPx <= 0f) 0f else ((scrollRange - scrollY) / fadeLengthPx).coerceIn(0f, 1f)

    /** 翻页途中两页各占一部分：按页位置在相邻两页的覆盖度之间插值。 */
    fun pagerCoverage(position: Float, pageCount: Int, coverageOf: (Int) -> Float): Float {
        if (pageCount <= 0) return 0f
        val clamped = position.coerceIn(0f, (pageCount - 1).toFloat())
        val base = floor(clamped).toInt()
        val fraction = clamped - base
        val current = coverageOf(base)
        if (fraction <= 0f || base + 1 >= pageCount) return current
        return current + (coverageOf(base + 1) - current) * fraction
    }

    fun quantize(coverage: Float): Float =
        ((coverage.coerceIn(0f, 1f) / COVERAGE_QUANTUM).roundToInt() * COVERAGE_QUANTUM).coerceIn(0f, 1f)

    /**
     * 溶解层的渐变剖面，坐标是溶解层自身的局部 y（0 = 靠屏幕边的一端）。
     *
     * @param capsuleNearPx 胶囊靠屏幕边的外缘到溶解层起点的距离
     * @param capsuleFarPx 胶囊内缘到溶解层起点的距离
     * @param lengthPx 溶解层总长度（= 胶囊内缘 + 尾段）
     * @return 与 [LinearGradient] 对应的 (positions, alphas)，positions 严格递增、落在 0..1
     */
    fun profile(capsuleNearPx: Float, capsuleFarPx: Float, lengthPx: Float): Pair<FloatArray, FloatArray> {
        require(lengthPx > 0f)
        // 退化几何（胶囊贴边、没有尾段）下把停靠点夹开，保证严格递增。
        val far = (capsuleFarPx / lengthPx).coerceIn(MIN_STOP_GAP * 2f, 1f - MIN_STOP_GAP)
        val near = (capsuleNearPx / lengthPx).coerceIn(0f, far)
        val center = ((near + far) * 0.5f).coerceIn(MIN_STOP_GAP, far - MIN_STOP_GAP)
        val positions = floatArrayOf(0f, center, far, 1f)
        return positions to floatArrayOf(OUTER_ALPHA, CENTER_ALPHA, INNER_ALPHA, 0f)
    }
}

/**
 * 滚动边缘溶解层：铺满内容容器、叠在内容之上与悬浮栏之下，只在胶囊所在的那条带里把窗口底图
 * 按剖面 × 覆盖度画回来。
 *
 * 只画一次带 [LinearGradient] 遮罩的底图矩形（引擎的 [GlowEngine.drawWindowBackdrop]），
 * 不开离屏层；覆盖度到 1 之后滚动不再重录。铺满容器而不是按带宽布局：胶囊几何变化只需要
 * 重录，不触发任何 requestLayout。
 */
@SuppressLint("ViewConstructor")
internal class GlowScrollEdgeView(
    context: Context,
    val edge: GlowScrollEdge,
    private val tailPx: Float,
    private val engine: () -> GlowEngine?
) : View(context) {
    private val band = RectF()
    private var gradient: Shader? = null
    private var capsuleTop = Float.NaN
    private var capsuleBottom = Float.NaN
    private var gradientHeight = -1

    var coverage: Float = 0f
        set(value) {
            val next = GlowScrollEdgePolicy.quantize(value)
            if (abs(next - field) < GlowScrollEdgePolicy.COVERAGE_QUANTUM * 0.5f) return
            field = next
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    /** 胶囊在本层局部坐标下的竖向范围；NaN 表示胶囊不存在（不溶解）。 */
    fun setCapsule(top: Float, bottom: Float) {
        if (top == capsuleTop && bottom == capsuleBottom) return
        capsuleTop = top
        capsuleBottom = bottom
        gradient = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (coverage <= 0f || width <= 0 || height <= 0 || capsuleTop.isNaN() || capsuleBottom.isNaN()) return
        val glow = engine() ?: return
        val h = height.toFloat()
        if (edge == GlowScrollEdge.TOP) {
            band.set(0f, 0f, width.toFloat(), (capsuleBottom + tailPx).coerceIn(1f, h))
        } else {
            band.set(0f, (capsuleTop - tailPx).coerceIn(0f, h - 1f), width.toFloat(), h)
        }
        val mask = gradient?.takeIf { gradientHeight == height } ?: buildGradient()
        glow.drawWindowBackdrop(canvas, this, band, mask, coverage)
    }

    /** 渐变建在本层局部坐标：TOP 从 y=0（屏幕边）往下，BOTTOM 从 y=height 往上。 */
    private fun buildGradient(): Shader {
        val shader = if (edge == GlowScrollEdge.TOP) {
            val length = band.bottom
            val (positions, alphas) = GlowScrollEdgePolicy.profile(capsuleTop, capsuleBottom, length)
            LinearGradient(0f, 0f, 0f, length, colors(alphas), positions, Shader.TileMode.CLAMP)
        } else {
            val h = height.toFloat()
            val length = h - band.top
            val (positions, alphas) = GlowScrollEdgePolicy.profile(h - capsuleBottom, h - capsuleTop, length)
            LinearGradient(0f, h, 0f, band.top, colors(alphas), positions, Shader.TileMode.CLAMP)
        }
        gradient = shader
        gradientHeight = height
        return shader
    }

    private fun colors(alphas: FloatArray) =
        IntArray(alphas.size) { Color.argb((alphas[it] * 255f).roundToInt(), 0, 0, 0) }
}
