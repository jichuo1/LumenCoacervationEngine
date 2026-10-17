package com.lumen.coacervation.engine.host

import android.graphics.Color

/**
 * 控件软阴影配置：让浅色表面从白色/浅色内容里分出来（色罩与白色描边在浅底上会消失）。
 *
 * 阴影分两层：**接触层**紧贴控件边缘的一道窄环（约 1dp，锐利），保证纯白背景上也能读出轮廓；
 * **投射层**是大半径高斯软影，给出浮起的高度感。两层独立调强度。
 *
 * - 默认关闭；开启后对 `LumenActivityDelegate.surface()` 一族背景与 [LumenControlShadow] 的独立用法同时生效。
 * - 阴影画在控件边界**之外**：祖先容器需要 `clipChildren=false` 或预留 [LumenControlShadow.outsetPx] 的间距，
 *   紧贴的列表项会把阴影裁掉（这通常是想要的效果，密集列表本就不该加阴影）。
 * - 纯软件位图缓存实现，API 27+ 可用，不依赖高级材质；纹理按参数量化缓存，进程级共享，
 *   内存告急时随 `LumenEngine.releaseGraphics()` 释放。
 *
 * 所有数值构造时校验；滑块等连续输入先自行收进范围。
 *
 * @property blurRadiusDp 投射层的模糊半径（高斯 σ），0 关闭投射层只留接触环。
 * @property offsetYDp 投射层向下偏移，模拟顶光；0 为四周均匀的环境光影。
 * @property spreadDp 阴影轮廓相对控件的扩张量；负值收紧。
 * @property opacity 投射层中心峰值不透明度（相对颜色的 alpha 倍率）。
 * @property contactOpacity 接触环不透明度；0 关闭接触环。
 * @property color 阴影颜色；默认纯黑，可按页面底色微微调暖/调冷。
 * @property adaptToTheme 深色主题下自动加强投射、减弱接触环（深底上窄环几乎不可见）。
 */
public data class LumenControlShadowOptions(
    val enabled: Boolean = false,
    val blurRadiusDp: Float = 10f,
    val offsetYDp: Float = 3f,
    val spreadDp: Float = 0f,
    val opacity: Float = 0.14f,
    val contactOpacity: Float = 0.10f,
    val color: Int = Color.BLACK,
    val adaptToTheme: Boolean = true
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
