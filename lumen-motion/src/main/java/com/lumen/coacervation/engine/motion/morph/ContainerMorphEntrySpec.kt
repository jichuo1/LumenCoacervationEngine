package com.lumen.coacervation.engine.motion.morph

/** 来源入口与全屏形变终点共用的视觉参数（来源工程的诊断入口取值），避免两层交接时出现边界跳变。 */
public object ContainerMorphEntrySpec {
    const val CORNER_RADIUS_DP = 12f
    const val STROKE_WIDTH_DP = 2f
    private const val LIGHT_SCRIM_ALPHA = 0x52
    private const val DARK_SCRIM_ALPHA = 0x48
    const val STROKE_ALPHA = 0x30
    const val SURFACE_HANDOFF_EXPANSION = 0.025f

    fun scrimAlpha(darkTheme: Boolean): Int = if (darkTheme) {
        DARK_SCRIM_ALPHA
    } else {
        LIGHT_SCRIM_ALPHA
    }

    /**
     * 入口底色，也是诊断页从入口长到全屏的形变表面颜色（两处必须一致，否则交接跳变）。
     *
     * 基色随主题：深色用文字灰（在深底上是提亮），浅色用表面色。2026-09-24 柔光皮肤浅色下
     * 原来也用文字灰（深灰 #323B42 × 32%），入口是一块中灰底，打开诊断页时这块灰从按钮
     * 扩到全屏，读作"深色的形变"；高级材质的玻璃会把它冲淡所以没暴露。不透明度不变。
     */
    fun surfaceColor(darkTheme: Boolean, textGray: Int, surface: Int): Int =
        (scrimAlpha(darkTheme) shl 24) or ((if (darkTheme) textGray else surface) and 0x00FFFFFF)
}
