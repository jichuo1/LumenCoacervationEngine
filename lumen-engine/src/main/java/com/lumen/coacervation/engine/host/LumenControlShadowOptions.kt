package com.lumen.coacervation.engine.host

import android.graphics.Color

/**
 * 控件软阴影配置：让浅色表面从白色/浅色内容里分出来（色罩与白色描边在浅底上会消失）。
 *
 * 阴影按"到控件轮廓的距离"烘焙成连续剖面，分两个可独立调强度的尺度：
 * **宽幅环境影**（1.6× 模糊半径的高斯尾）给浮起感；**贴边接触带**（轮廓外约 1dp 的软带）补一点接触。
 * 剖面只画在控件**边界之外**，内侧完全不画：控件边缘保持自己的干净轮廓，叠加多个控件时不会互相压出暗边。
 *
 * - 默认关闭；开启后对 `LumenActivityDelegate.surface()` 一族背景与 [LumenControlShadow] 的独立用法同时生效。
 * - 阴影画在控件边界之外：祖先容器需要 `clipChildren=false` 或按 [LumenControlShadow.outsetPx] 预留间距，
 *   紧贴的列表项会把阴影裁掉（这通常是想要的效果，密集列表本就不该加阴影）。
 * - 纯软件位图缓存实现，API 27+ 可用，不依赖高级材质；纹理按参数量化缓存，进程级共享，
 *   内存告急时随 `LumenEngine.releaseGraphics()` 释放。
 *
 * 所有数值构造时校验；滑块等连续输入先自行收进范围。
 *
 * @property blurRadiusDp 环境影的衰减尺度；实际高斯 σ 为 1.6× 此值，范围更宽、过渡更柔。
 * @property offsetYDp 顶光偏移：正值加强底部、减弱顶部，0 为四周均匀。偏移烘焙成竖向增益而不是平移纹理，
 *   因此内边界始终贴着控件轮廓，不会在边缘露出亮缝。
 * @property spreadDp 阴影轮廓相对控件的扩张量；负值收紧。
 * @property opacity 环境影峰值不透明度（相对颜色的 alpha 倍率）。
 * @property contactOpacity 贴边接触带峰值不透明度；0 关闭接触带，只留环境影。
 * @property color 阴影颜色；默认纯黑，可按页面底色微微调暖/调冷。
 * @property adaptToTheme 深色主题下自动加强环境影、减弱接触带（深底上窄带几乎不可见）。
 * @property softenOverlap 相邻同级控件落在外扩阴影范围内时，降低本控件的阴影强度，
 *   避免两个控件的阴影交叉处叠加过黑；默认 false（叠加）。这是同级布局的近似检测，不改变纹理与缓存。
 */
public data class LumenControlShadowOptions(
    val enabled: Boolean = false,
    val blurRadiusDp: Float = 10f,
    val offsetYDp: Float = 3f,
    val spreadDp: Float = 0f,
    val opacity: Float = 0.14f,
    val contactOpacity: Float = 0.06f,
    val color: Int = Color.BLACK,
    val adaptToTheme: Boolean = true,
    val softenOverlap: Boolean = false
) {
    init {
        bounded("blurRadiusDp", blurRadiusDp, 0f, 32f)
        bounded("offsetYDp", offsetYDp, -8f, 16f)
        bounded("spreadDp", spreadDp, -4f, 8f)
        bounded("opacity", opacity, 0f, 0.6f)
        bounded("contactOpacity", contactOpacity, 0f, 0.4f)
    }

    public companion object {
        /** 关闭状态的单例。 */
        @JvmField
        public val DISABLED: LumenControlShadowOptions = LumenControlShadowOptions()

        private fun bounded(name: String, value: Float, min: Float, max: Float) {
            require(value.isFinite() && value in min..max) { "$name must be in [$min, $max], was $value" }
        }
    }
}
