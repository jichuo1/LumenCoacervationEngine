package com.lumen.coacervation.engine.motion

import android.graphics.Outline
import android.graphics.Rect
import android.view.View

/**
 * 形变折叠端（来源控件）的圆角（适配标准 §14.2）。
 *
 * 来源工程的来源只有两种：27dp 的无背景图标和细长的设置行，于是折叠端一律取短边一半（图标收成正圆、
 * 行收成胶囊）。换成网格方块、大卡片这类来源时，这条规则会让形变从一个圆开始长出来，与卡片本身的圆角对不上。
 * 现在优先用来源**自己声明的圆角**（背景或前景报告的 outline 半径），报不出来才退回短边一半。
 */
public enum class MorphCornerMode {
    /** 默认：优先使用来源声明的圆角。 */
    DECLARED,
    /** 从短边一半的胶囊/圆形起点展开。 */
    CAPSULE
}

public object MorphCornerPolicy {
    /**
     * @param declared 来源声明的圆角（px），NaN 表示没有声明。
     * @return 折叠端圆角，不超过短边一半（再大就不是圆角矩形了）。
     */
    @JvmStatic
    public fun collapsedRadius(declared: Float, width: Float, height: Float): Float =
        collapsedRadius(declared, width, height, MorphCornerMode.DECLARED)

    @JvmStatic
    public fun collapsedRadius(declared: Float, width: Float, height: Float, mode: MorphCornerMode): Float {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return 0f
        val half = minOf(width, height) / 2f
        if (mode == MorphCornerMode.CAPSULE) return half
        return if (declared.isFinite() && declared >= 0f) minOf(declared, half) else half
    }
}

/**
 * 这个 View 声明的圆角（px）：依次取背景、前景的 outline 半径；没有背景、outline 为空或不是圆角矩形时返回 NaN。
 *
 * **0 是"确实声明了直角"**，与"没有声明"（NaN）不同：透明 content 的自绘涟漪报出的就是 0，
 * 所以涟漪要用 content 与 mask 同圆角的写法（`CoverableRippleDrawable.rounded`）。
 */
internal fun View.declaredCornerRadius(): Float {
    val outline = Outline()
    val rect = Rect()
    for (drawable in listOf(background, foreground)) {
        if (drawable == null) continue
        outline.setEmpty()
        val radius = runCatching {
            drawable.getOutline(outline)
            if (outline.getRect(rect)) outline.radius else Float.NaN
        }.getOrDefault(Float.NaN)
        if (radius.isFinite() && radius >= 0f) return radius
    }
    return Float.NaN
}
