package com.lumen.coacervation.engine.widget

import com.lumen.coacervation.engine.motion.LumenSpringSpec

/**
 * 底栏选中框的棱镜（液态玻璃）效果，对应参考库 LiquidBottomTabs 的选中胶囊：
 *
 * - **静止**：选中框只显示宿主的 SELECTION 背景（例如 `NavigationBarSelectionMask` 色），框内的图标与标签
 *   用选中色绘制，框外用常规色——选中色严格以选中框边缘为界，随选中框滑动逐像素过渡。
 * - **按下 / 拖动**：选中框按 [pressedScale] 膨胀并以 X/Y 不同阻尼的弹簧"果冻"晃动，按横向速度挤压拉伸；
 *   框内内容按 [contentMagnification] 放大并在边缘折射（[lensHeightDp] / [lensAmountDp]，可选色散），
 *   出现边缘高光、外阴影、内阴影与一层极淡的暗色，静止背景随之淡出。
 *
 * 每一项都可单独调到 0 关闭。折射与色散需要 API 33；更低版本保留放大、缩放、果冻、高光与阴影。
 * 外阴影需要 API 28（硬件加速的模糊遮罩）。
 *
 * @property pressedScale 按下时选中框的缩放（参考库 78 / 56）。
 * @property velocityDeformation 速度挤压拉伸的倍率，0 关闭。参考库：横向 ÷(1 − 0.075v)、纵向 ×(1 − 0.025v)，
 *   v 为以"可选范围/秒"计的速度，两轴各自限制在 ±0.2。
 * @property scaleXSpring / scaleYSpring 果冻弹簧；默认取参考库（刚度 250，阻尼比 0.6 / 0.7）。
 */
public data class LumenPrismSelectionOptions(
    val pressedScale: Float = 78f / 56f,
    val contentMagnification: Float = 1.2f,
    val lensHeightDp: Float = 10f,
    val lensAmountDp: Float = 14f,
    val dispersion: Float = 1f,
    val velocityDeformation: Float = 1f,
    val highlightOpacity: Float = 0.5f,
    val shadowOpacity: Float = 0.1f,
    val innerShadowOpacity: Float = 0.15f,
    val pressedWashOpacity: Float = 0.03f,
    val scaleXSpring: LumenSpringSpec = LumenSpringSpec(250f, .6f),
    val scaleYSpring: LumenSpringSpec = LumenSpringSpec(250f, .7f)
) {
    init {
        bounded("pressedScale", pressedScale, 1f, 1.8f)
        bounded("contentMagnification", contentMagnification, 1f, 1.6f)
        bounded("lensHeightDp", lensHeightDp, 0f, 32f)
        bounded("lensAmountDp", lensAmountDp, 0f, 32f)
        bounded("dispersion", dispersion, 0f, 1f)
        bounded("velocityDeformation", velocityDeformation, 0f, 2f)
        bounded("highlightOpacity", highlightOpacity, 0f, 1f)
        bounded("shadowOpacity", shadowOpacity, 0f, 0.4f)
        bounded("innerShadowOpacity", innerShadowOpacity, 0f, 0.6f)
        bounded("pressedWashOpacity", pressedWashOpacity, 0f, 0.2f)
    }

    public companion object {
        @JvmField
        public val DEFAULT: LumenPrismSelectionOptions = LumenPrismSelectionOptions()

        private fun bounded(name: String, value: Float, min: Float, max: Float) {
            require(value.isFinite() && value in min..max) { "$name must be in [$min, $max], was $value" }
        }
    }
}
