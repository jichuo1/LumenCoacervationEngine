package com.lumen.coacervation.engine.morph

/**
 * 液态形变的纯策略：进度收口、宽度/圆角插值、内容 alpha 与位移。
 * 不依赖 android.graphics，供控件与 JVM 测试共用。
 */
public object LumenMorphPolicy {
    /** 文字开始淡入的进度；之前保持全透明。 */
    public const val CONTENT_FADE_START: Float = .35f

    public fun clamp01(value: Float): Float =
        if (!value.isFinite()) 0f else value.coerceIn(0f, 1f)

    /** 当前宽度占外层宽度的比例：收起比例 → 1。 */
    public fun widthFraction(progress: Float, collapsedFraction: Float): Float =
        collapsedFraction + (1f - collapsedFraction) * clamp01(progress)

    /** 收起圆角 → 展开圆角；进度可被弹簧过冲，调用方负责再收进短边一半。 */
    public fun radiusPx(progress: Float, collapsedRadiusPx: Float, expandedRadiusPx: Float): Float =
        collapsedRadiusPx + (expandedRadiusPx - collapsedRadiusPx) * clamp01(progress)

    /** 文字 alpha：关闭淡入时恒为 1（仍会被形状裁切）。 */
    public fun contentAlpha(progress: Float, fade: Boolean): Float {
        if (!fade) return 1f
        val p = clamp01(progress)
        if (p <= CONTENT_FADE_START) return 0f
        return (p - CONTENT_FADE_START) / (1f - CONTENT_FADE_START)
    }

    /** 收起态中心 → 展开态中心的位移；progress=1 时为 0。 */
    public fun contentOffset(progress: Float, collapsedCenter: Float, expandedCenter: Float): Float =
        (collapsedCenter - expandedCenter) * (1f - clamp01(progress))
}
