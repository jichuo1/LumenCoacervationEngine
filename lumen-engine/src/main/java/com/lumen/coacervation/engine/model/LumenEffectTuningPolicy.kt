package com.lumen.coacervation.engine.model

import kotlin.math.roundToInt

/**
 * 把 [LumenEffectTuning] 的边缘高光倍率换算到各处原值上。纯函数，不依赖 `android.graphics`。
 *
 * 长按拖动光晕的换算在 lumen-motion 的 `ElasticGlowTuning`。
 *
 * 不变量：倍率全为 1 时，每个函数都逐位返回原值——默认调参下画面与引入调参前完全一致。
 */
internal object LumenEffectTuningPolicy {

    /**
     * 高级材质参数。
     *
     * - 厚度：rim 带（`refractionHeightDp`）同时决定菲涅尔、镜面、焦散的铺展宽度，缩放它就是整圈高光一起变厚/变薄；
     *   轮廓描边同比缩放。
     * - 折射位移只随变薄同比收小、不随变厚放大：同样的位移压进更窄的 rim，折射梯度会陡成一圈扭曲；
     *   而加厚只是高光铺得更宽，不该连带放大透镜。
     * - `effectPaddingDp` 必须包住 rim 与各项采样位移（见 LiquidTokenResolverTest），rim 加厚时同步撑大。
     * - 亮度：镜面、菲涅尔、轮廓描边的强度同比缩放。
     */
    fun applyTo(parameters: LiquidParameters, tuning: LumenEffectTuning): LiquidParameters {
        val width = tuning.edgeHighlightWidth
        val intensity = tuning.edgeHighlightIntensity
        if (width == 1f && intensity == 1f) return parameters
        val refractionHeight = parameters.refractionHeightDp * width
        val requiredPadding = refractionHeight + parameters.interiorDistortionDp +
            parameters.scatteringRadiusDp + parameters.chromaticShiftDp
        return parameters.copy(
            refractionHeightDp = refractionHeight,
            refractionAmountDp = parameters.refractionAmountDp * minOf(width, 1f),
            highlightWidthDp = parameters.highlightWidthDp * width,
            specularStrength = parameters.specularStrength * intensity,
            fresnelStrength = parameters.fresnelStrength * intensity,
            highlightAlpha = (parameters.highlightAlpha * intensity).coerceAtMost(1f),
            effectPaddingDp = maxOf(parameters.effectPaddingDp, requiredPadding)
        )
    }

    /**
     * 向内铺开的高光带宽度。加宽时不超过表面短边的一半——再宽的描边会越过中线、画出表面之外；
     * 但不会窄于原值，默认倍率下与引入调参前逐位一致。
     */
    fun bandWidth(base: Float, tuning: LumenEffectTuning, halfMinSide: Float): Float {
        val scaled = base * tuning.edgeHighlightWidth
        return if (tuning.edgeHighlightWidth <= 1f) scaled else minOf(scaled, maxOf(base, halfMinSide))
    }

    /** 边缘高光的 alpha（0..255）。 */
    fun edgeAlpha(base: Int, tuning: LumenEffectTuning): Int =
        if (tuning.edgeHighlightIntensity == 1f) base
        else (base * tuning.edgeHighlightIntensity).roundToInt().coerceIn(0, 255)
}
