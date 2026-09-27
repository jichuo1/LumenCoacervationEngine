package com.lumen.coacervation.engine.liquid

import com.lumen.coacervation.engine.model.LiquidParameters

/** 把纯视觉调参转换为 Liquid renderer 参数，不读取偏好或持有 Context。 */
internal object LiquidTokenResolver {
    fun resolve(
        tuning: LiquidVisualTuning,
        profile: LiquidEffectProfile = LiquidEffectProfile.STANDARD,
        dark: Boolean = true
    ): LiquidParameters {
        val realtime = profile == LiquidEffectProfile.REALTIME_CAPTURE
        return LiquidParameters(
            blurRadiusDp = if (realtime) 22f else 20f,
            // The prefiltered backdrop supplies the diffusion. Refraction is a thin rim,
            // not a wide magnifying band that turns every card into a recessed plastic button.
            // 2026-09-24 用户要求边缘高光再薄、过渡更自然：12/8 → 10/6.5。rim 带同时决定
            // 菲涅尔、镜面、焦散的铺展宽度，收窄它就是整圈高光一起变薄。
            refractionHeightDp = if (realtime) 10f else 6.5f,
            refractionAmountDp = if (realtime) 7f else 3.5f,
            depthEffect = if (realtime) 0.16f else 0.06f,
            interiorDistortionDp = if (realtime) 1.75f else 0f,
            // 色散曾经整条关闭，因为旧实现全域等量位移——内部高对比文字也裂成红蓝边。
            // shader 现按 edgeWeight 限域在 rim 带内、位移 <0.02px 直接跳过两次取样，
            // 于是可以给实时档一点亚像素级色散（玻璃厚边的特征），内部零额外开销。
            chromaticShiftDp = if (realtime) REALTIME_CHROMATIC_SHIFT_DP else 0f,
            scatteringRadiusDp = if (realtime) 4f else 0f,
            scatteringStrength = if (realtime) 0.18f else 0f,
            specularStrength = if (realtime) 0.06f else 0f,
            fresnelStrength = if (realtime) 0.025f else 0f,
            causticLuminanceGain = if (realtime) 0.12f else 0f,
            // 内阴影只给深色：浅色玻璃边应读作亮边，rim 带中段压暗的那一圈在近白表面上就是
            // "向里发灰"（2026-09-24 用户反馈浅色胶囊边缘向内灰灰的）。
            innerShadowStrength = if (realtime && dark) 0.018f else 0f,
            // 实时档采的是真实内容，微提饱和让透出的色彩读作"通透"而不是"蒙灰"；
            // 标准档采的是已经调好的光学底图，不动。上界 1.06 防止调参漂移出彩色噪点。
            saturation = if (realtime) {
                (tuning.saturation + REALTIME_SATURATION_BOOST).coerceAtMost(1.06f)
            } else tuning.saturation,
            surfaceAlpha = if (realtime) tuning.cardGlassAlpha * 0.92f
            else tuning.cardGlassAlpha,
            modalSurfaceAlpha = if (realtime) tuning.modalGlassAlpha * 0.96f
            else tuning.modalGlassAlpha,
            motionSurfaceAlpha = if (realtime) tuning.motionGlassAlpha * 0.94f
            else tuning.motionGlassAlpha,
            fallbackSurfaceAlpha = tuning.cardFallbackAlpha,
            fallbackModalSurfaceAlpha = tuning.modalFallbackAlpha,
            fallbackMotionSurfaceAlpha = tuning.motionFallbackAlpha,
            // 实时档由 shader 提供连续高光，Canvas 只保留一条低强度轮廓线。
            highlightWidthDp = 0.35f,
            highlightAlpha = if (realtime) 0.07f else 0.08f,
            // 屏幕 y 轴向下；-145° => (-0.819, -0.574)，光源位于左上方。
            highlightAngleDegrees = -145f,
            effectPaddingDp = if (realtime) 22f else 12f,
            // 实时档底图是锐利截屏，色带风险低于平滑底图，但抑制期与光学直采都会
            // 采到平滑副本；给 ±0.5LSB 足够打散台阶，肉眼不可辨为噪点。
            ditherAmplitude = if (realtime) REALTIME_DITHER_AMPLITUDE else 0f
        )
    }

    /** 亚像素级：只在 rim 带内生效，整块彩边靠 shader 的 edgeWeight 限域排除。 */
    private const val REALTIME_CHROMATIC_SHIFT_DP = 0.6f

    /** 0.98 → 1.03。 */
    private const val REALTIME_SATURATION_BOOST = 0.05f

    /** 1/255 ≈ ±0.5LSB。 */
    private const val REALTIME_DITHER_AMPLITUDE = 1f / 255f
}
