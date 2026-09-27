package com.lumen.coacervation.engine.liquid

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * 小型控件（开关轨道/滑块、复选框）的统一材质层。
 *
 * 与卡片同一套玻璃语言，但高光必须"软"——生硬的线性白带是塑料感的主要来源：
 * ① **混色渐变**：竖向两端都做混色——顶端温和混白、底端混 onAccent（选中）或 outline
 *    （未选中）压深；顶端的白只留一点方向性，高光亮度全部交给边缘层，避免两层白叠加；
 * ② **沿边晕染**：高光不再画成横穿控件表面的白带，而是沉进边框——描边本身带竖向
 *    渐变（亮端在顶、底端落回语义描边色），另有一条贴边内晕把光从边缘向控件内部
 *    柔化衰减，亮部贴着轮廓线走，没有硬边也没有断口；
 * ③ **描边**：渐变描边与卡片同一套高光语言——选中/交互态高光落在 accent 色描边上
 *    （顶沿向白提亮、底端落回 accent），而不是一圈平白；未选中由 outline 混白提亮、
 *    底端回落 outline 保证可辨识；
 *    取色全部来自 Monet（surface/accent/onAccent/outline），不引入新色源。
 *
 * 所有 Shader 与几何都在状态/边界变化时构建；draw 只改 paint alpha 再绘制。竖向渐变不依赖
 * 水平边界，因此滑块平移造成的边界变化不会重建 Shader（由 [LiquidControlGradientCache] 守住；
 * 高光层的 stop 位置是常量，缓存键只取会变的顶/底/峰值色）。
 */
public class LiquidChoiceDrawable(
    private val width: Int,
    private val height: Int,
    private val density: Float,
    private val surface: Int,
    private val accent: Int,
    private val onAccent: Int,
    private val outline: Int,
    private val checkbox: Boolean = false,
    private val thumb: Boolean = false
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val mark = if (checkbox) Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    } else null
    private val rect = RectF()
    private val rimRect = RectF()
    private val tick = if (checkbox) Path() else null
    private val gradientCache = LiquidControlGradientCache()
    private val edgeGlowCache = LiquidControlGradientCache()
    private val rimGlowCache = LiquidControlGradientCache()
    private var visualState = LiquidControlStyle.resolve(true, false, false, false)
    private var drawableAlpha = 255
    private var radius = 0f
    private var rimRadius = 0f
    /**
     * 浅色主题（表面接近白）。深色下"表面混 accent"天然落在中间调，白滑块对比足够；
     * 浅色下同一配比混出的是很淡的灰绿，白滑块几乎贴在轨道上，未选中轨道也只剩一条淡描边
     * （2026-09-24 用户报告"浅色模式下开关颜色较浅"）。浅色只加深，不改深色观感。
     */
    private val lightTheme = ColorUtils.calculateLuminance(surface) > 0.5

    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = height
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val next = LiquidControlStyle.resolve(android.R.attr.state_enabled in state,
            android.R.attr.state_checked in state, android.R.attr.state_pressed in state,
            android.R.attr.state_focused in state)
        if (next == visualState) return false
        visualState = next
        updatePaints()
        invalidateSelf()
        return true
    }
    override fun onBoundsChange(bounds: Rect) {
        val inset = if (checkbox) 3f * density else density
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        // checkbox 圆角按盒体比例取（约 20%）：控件小改后圆角仍与盒体协调，不是固定死值。
        radius = if (checkbox) rect.height() * 0.20f else rect.height() / 2f
        // 内沿晕光的几何只依赖边界，不依赖状态：描边中心落在 rect 内缩半线宽处，
        // 整条光带都在形状内，不需要裁剪路径。
        val rimInset = RIM_WIDTH_DP * density / 2f
        rimRect.set(rect.left + rimInset, rect.top + rimInset,
            rect.right - rimInset, rect.bottom - rimInset)
        rimRadius = (radius - rimInset).coerceAtLeast(0f)
        tick?.apply {
            reset()
            moveTo(rect.left + rect.width() * .22f, rect.top + rect.height() * .51f)
            lineTo(rect.left + rect.width() * .43f, rect.top + rect.height() * .72f)
            lineTo(rect.left + rect.width() * .79f, rect.top + rect.height() * .29f)
        }
        updatePaints()
    }
    private fun updatePaints() {
        val checked = visualState.selected
        // 选中态的表达收进描边：轨道填充只做 accent 染色而不是整块实色（大面积色块是"生硬"感
        // 的主要来源），accent 渐变描边承担状态提示；thumb 仍用 onAccent 实心保证可读，
        // checkbox 面积小，保留实色 accent 填充让对勾语义一眼可辨。
        val base = when {
            thumb && checked -> onAccent
            checked && !checkbox -> ColorUtils.blendARGB(surface, accent,
                if (lightTheme) CHECKED_TRACK_WASH_LIGHT else CHECKED_TRACK_WASH)
            checked -> accent
            // 浅色未选中**开关轨道**压一层 outline，白滑块才从轨道里分得出来。复选框没有滑块，
            // 铺灰只会得到一块深灰方块（2026-09-24 用户报告「管理常用」勾选框在浅色下偏深），
            // 复选框保持表面色，靠加深的描边辨识。
            lightTheme && !thumb && !checkbox -> ColorUtils.blendARGB(surface, outline, UNCHECKED_TRACK_SHADE_LIGHT)
            else -> surface
        }
        val alpha = if (lightTheme && !thumb && !(checkbox && !checked)) maxOf(LiquidControlStyle.fillAlpha(visualState),
            if (checked) CHECKED_FILL_ALPHA_LIGHT else UNCHECKED_FILL_ALPHA_LIGHT)
            else LiquidControlStyle.fillAlpha(visualState)
        val thumbAlpha = if (checked) 255 else 220
        val bottom = rect.bottom.coerceAtLeast(rect.top + 1f)
        // 混色：顶端只留一点白提亮（高光层才负责亮），底端混深色压深，整体是有方向的柔光。
        val startColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(base, Color.WHITE, if (thumb) .20f else .16f), alpha)
        val endColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(base, if (checked) onAccent else outline, if (thumb) .20f else .14f),
            if (thumb) thumbAlpha else alpha)
        if (gradientCache.update(rect.top, bottom, startColor, endColor)) {
            fill.shader = LinearGradient(0f, rect.top, 0f, bottom,
                startColor, endColor, Shader.TileMode.CLAMP)
        }
        // 描边带竖向渐变：高光沉进边框线条——选中/交互态用 accent 渐变（顶沿向白提亮、
        // 底端落回 accent），未选中由 outline 向白混色，两侧随过渡自然晕开。
        val edgeBase = if (visualState.emphasized || checked) accent else outline
        val edgeTop = if (visualState.emphasized || checked)
            ColorUtils.blendARGB(accent, Color.WHITE, EDGE_TOP_BLEND_CHECKED)
            else ColorUtils.blendARGB(outline, Color.WHITE, EDGE_TOP_BLEND_UNCHECKED)
        edge.strokeWidth = (if (visualState.emphasized) 1.1f else .7f) * density
        val edgeBottom = rect.bottom.coerceAtLeast(rect.top + 1f)
        if (edgeGlowCache.update(rect.top, edgeBottom, edgeTop, edgeBase)) {
            edge.shader = LinearGradient(0f, rect.top, 0f, edgeBottom,
                edgeTop, edgeBase, Shader.TileMode.CLAMP)
        }
        // 内沿晕光：贴边的软描边，亮端贴顶、按固定行程渐隐——光从边框向控件内部晕，
        // 而不是一条横穿表面的白带。几何无状态、无裁剪，与描边共用同一条竖向光轴。
        rim.strokeWidth = RIM_WIDTH_DP * density
        val rimPeakAlpha = when {
            visualState.emphasized -> RIM_PEAK_EMPHASIZED
            checked -> RIM_PEAK_CHECKED
            else -> RIM_PEAK_UNCHECKED
        }
        // 选中态的内晕也染 accent：与描边同一色相，不会出现"白圈压在色块上"的塑料感。
        val rimLight = if (checked) ColorUtils.blendARGB(accent, Color.WHITE, RIM_ACCENT_BLEND)
            else Color.WHITE
        val rimPeak = ColorUtils.setAlphaComponent(rimLight, rimPeakAlpha)
        val rimUpper = ColorUtils.setAlphaComponent(rimLight,
            (rimPeakAlpha * RIM_UPPER_ALPHA_RATIO).roundToInt())
        val rimLower = ColorUtils.setAlphaComponent(rimLight,
            (rimPeakAlpha * RIM_LOWER_ALPHA_RATIO).roundToInt())
        val rimFadeBottom = rimRect.top + rect.height() * RIM_FADE_FRACTION
        if (rimGlowCache.update(rimRect.top, rimFadeBottom, rimPeak, Color.TRANSPARENT)) {
            rim.shader = LinearGradient(0f, rimRect.top, 0f, rimFadeBottom,
                intArrayOf(rimPeak, rimUpper, rimLower, Color.TRANSPARENT),
                floatArrayOf(0f, RIM_UPPER_FRACTION, RIM_LOWER_FRACTION, 1f),
                Shader.TileMode.CLAMP)
        }
        mark?.color = onAccent
        mark?.strokeWidth = 1.8f * density
    }
    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val alpha = drawableAlpha * LiquidControlStyle.opacity(visualState) / 255
        fill.alpha = alpha
        rim.alpha = alpha
        edge.alpha = alpha * (if (visualState.emphasized) 150 else if (visualState.selected) 130
            else if (lightTheme) UNCHECKED_EDGE_ALPHA_LIGHT else 46) / 255
        mark?.alpha = alpha
        canvas.drawRoundRect(rect, radius, radius, fill)
        // checkbox 面积小（24dp 量级），内沿晕光在这种尺度上只剩一条生硬亮带；
        // 简洁化为填充 + 渐变描边两层，高光全部收进描边里。
        if (!checkbox) canvas.drawRoundRect(rimRect, rimRadius, rimRadius, rim)
        canvas.drawRoundRect(rect, radius, radius, edge)
        if (checkbox && visualState.selected) canvas.drawPath(requireNotNull(tick), requireNotNull(mark))
    }
    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = drawableAlpha
    override fun setColorFilter(filter: ColorFilter?) {
        fill.colorFilter = filter; edge.colorFilter = filter; mark?.colorFilter = filter
        invalidateSelf()
    }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** 未选中描边亮端的混白比例：基色是 outline，向白混到 72% 作顶沿高光。 */
        const val EDGE_TOP_BLEND_UNCHECKED = 0.72f

        /** 选中/交互态描边亮端向白的混色比例：高光亮核留在 accent 描边里，不洗成纯白圈。 */
        const val EDGE_TOP_BLEND_CHECKED = 0.55f

        /** 选中态轨道填充里 accent 的占比：染色而非实色块。 */
        const val CHECKED_TRACK_WASH = 0.42f

        /** 浅色主题的选中轨道：accent 为主，白滑块与轨道拉开明度差。 */
        const val CHECKED_TRACK_WASH_LIGHT = 0.80f
        const val CHECKED_FILL_ALPHA_LIGHT = 235

        /** 浅色主题的未选中轨道：表面混 outline，填充加厚，描边加深。 */
        const val UNCHECKED_TRACK_SHADE_LIGHT = 0.28f
        const val UNCHECKED_FILL_ALPHA_LIGHT = 190
        const val UNCHECKED_EDGE_ALPHA_LIGHT = 96

        /** 选中态内晕的 accent 向白混色比例：晕光与描边同色相、亮核仍偏白。 */
        const val RIM_ACCENT_BLEND = 0.45f

        /**
         * 内沿晕光线宽（dp）。描边中心内缩半线宽，整条光带落在形状内、免裁剪；
         * 1.8dp 的窄带贴着轮廓线走，峰值可以更亮而不显"白纸条"。
         */
        const val RIM_WIDTH_DP = 1.8f

        /** 晕光自顶边向下渐隐的行程（占控件高比例）；收得更快，高光始终贴着边。 */
        const val RIM_FADE_FRACTION = 0.45f

        /** 晕光峰值透明度（贴边窄带用）。 */
        const val RIM_PEAK_EMPHASIZED = 0x66
        const val RIM_PEAK_CHECKED = 0x50
        const val RIM_PEAK_UNCHECKED = 0x30

        /**
         * 两个中停的位置与相对峰值的 alpha 比例。四段逼近 (1−t)² 缓出：
         * 贴边最亮 → 上段快速回落 → 长尾温柔归零，没有线性渐变的拐点棱线。
         */
        const val RIM_UPPER_FRACTION = 0.30f
        const val RIM_UPPER_ALPHA_RATIO = 0.5f
        const val RIM_LOWER_FRACTION = 0.62f
        const val RIM_LOWER_ALPHA_RATIO = 0.15f
    }
}
