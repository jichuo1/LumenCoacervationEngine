package com.lumen.coacervation.engine.liquid

import com.lumen.coacervation.engine.model.LiquidParameters
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.material.ModernBackdropBlur
import kotlin.math.roundToInt

/** One bounded optical copy per custom static backdrop, prepared only by the background loader. */
internal object LiquidOpticalSamplingPolicy {
    const val BLUR_RADIUS_DP = 20f
    const val MAX_OPTICAL_BYTES = 2L * 1024L * 1024L
    // Input pixels + two blur work arrays + output bitmap. Root/previous sources are separate owners.
    const val MAX_PREPARATION_BYTES = MAX_OPTICAL_BYTES * 4L

    fun sampledRadius(sampleWidth: Int, fullWidth: Int, density: Float): Int {
        require(sampleWidth > 0 && fullWidth > 0 && density.isFinite() && density > 0f)
        return (BLUR_RADIUS_DP * density * sampleWidth / fullWidth).roundToInt().coerceIn(1, 24)
    }

    fun opticalBytes(width: Int, height: Int): Long {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_OPTICAL_BYTES / 4L)
        return width.toLong() * height * 4L
    }

    fun soften(pixels: IntArray, width: Int, height: Int, fullWidth: Int, density: Float): IntArray {
        opticalBytes(width, height)
        require(width.toLong() * height == pixels.size.toLong())
        return ModernBackdropBlur.blur(pixels, width, height, sampledRadius(width, fullWidth, density))
    }
}

/** Separation stays subtle; top chrome has no rectangular outline of its own. */
internal object LiquidSurfaceEdgePolicy {
    fun alphaMultiplier(role: SurfaceRole): Float = when (role) {
        SurfaceRole.TOP_BAR, SurfaceRole.WINDOW -> 0f
        SurfaceRole.SELECTED_ITEM -> .35f
        SurfaceRole.CARD -> .45f
        SurfaceRole.FLOATING -> .55f
        // 模态描边走"顶沿提亮、竖向落回"的渐变高光，基础亮度与卡片一致即可——
        // 亮核集中在顶沿，整圈不需要更高强度。
        SurfaceRole.MODAL -> .45f
        else -> .5f
    }
}

/**
 * Liquid 的纯视觉调参结果。
 *
 * 表面透明度集中在同一策略中，避免卡片与模态层各自硬编码后再次出现过重实色遮罩；
 * 背景氛围由 [com.lumen.coacervation.engine.background.AmbientBackdropScene]
 * 与标准磨砂皮肤共享同一配方，不再单独调参。该结构不依赖 Android 对象，便于 JVM 测试约束范围。
 */
internal data class LiquidVisualTuning(
    val cardGlassAlpha: Float,
    val modalGlassAlpha: Float,
    val motionGlassAlpha: Float,
    val cardFallbackAlpha: Float,
    val modalFallbackAlpha: Float,
    val motionFallbackAlpha: Float,
    val saturation: Float
) {
    init {
        require(cardGlassAlpha in 0f..1f)
        require(modalGlassAlpha in 0f..1f)
        require(motionGlassAlpha in 0f..1f)
        require(cardFallbackAlpha in 0f..1f)
        require(modalFallbackAlpha in 0f..1f)
        require(motionFallbackAlpha in 0f..1f)
        require(cardFallbackAlpha >= cardGlassAlpha)
        require(modalFallbackAlpha >= modalGlassAlpha)
        require(motionFallbackAlpha >= motionGlassAlpha)
        require(saturation in 0f..2f)
    }
}

/** 深浅色只改变强度，不改变 underlay 的固定空间布局，保证重建结果确定。 */
internal object LiquidVisualTuningPolicy {
    fun resolve(dark: Boolean): LiquidVisualTuning = if (dark) {
        LiquidVisualTuning(
            cardGlassAlpha = 0.26f,
            // 模态表面在弹窗里采的是已过滤的光学底图（无锐利文字），不再需要高不透明度
            // 来压透字——降回通透区间，让渐变/预模糊底图的空间感透上来。
            // 再减一档：玻璃层本身已按 glassContentAlpha 透出 scrim 压暗的底页。
            modalGlassAlpha = 0.55f,
            motionGlassAlpha = 0.36f,
            cardFallbackAlpha = 0.72f,
            modalFallbackAlpha = 0.93f,
            motionFallbackAlpha = 0.78f,
            saturation = 0.98f
        )
    } else {
        LiquidVisualTuning(
            cardGlassAlpha = 0.30f,
            // 亮色模式底页被 scrim 压暗后透入会拉低面板明度，色罩比暗色留厚一档
            // 保住文本对比度；通透仍由 glassContentAlpha 承担。
            modalGlassAlpha = 0.60f,
            motionGlassAlpha = 0.40f,
            cardFallbackAlpha = 0.78f,
            modalFallbackAlpha = 0.94f,
            motionFallbackAlpha = 0.82f,
            saturation = 0.98f
        )
    }
}

/**
 * 悬浮栏可读性补偿在高级材质表面上的落点（见 `GlowLegibility`）。
 *
 * - boost：着色不透明度最多加 [TINT_RANGE]。
 * - edgeDefinition：一圈暗色描边 + 一条内缩暗带，把浅色胶囊从亮背景里分出来。都画在表面
 *   自身范围内——底栏 `clipToOutline`，画到外面的投影会被裁掉。
 *
 * 暗带是**渐变**的：贴边最深、按 (1−t)³ 缓出到 [EDGE_BAND_DP] 处归零。2026-09-24 浅色模式
 * 真机取样：原来 8dp 等宽匀色暗带在内沿处从 225 硬跳到 237，读成"胶囊里套了一圈灰框"，
 * 边缘与中心过渡生硬。改用 [EDGE_BAND_STEPS] 条外沿对齐、宽度递增的描边叠出坡度：
 * 每条描边外沿都与表面边缘重合，只有内沿各不相同，所以没有接缝，也不需要离屏层或 shader。
 *
 * 边缘颜色随主题：深色用黑（暗边），浅色用白（亮边）。2026-09-24 用户反馈浅色下两条胶囊
 * 的边缘和深色一样发深、显脏；浅色玻璃的边应读作被照亮的亮边，由白色描边 + 向内渐隐的
 * 白色亮带表达，剖面与暗带相同，只换颜色和强度（[EDGE_RING_ALPHA_LIGHT]、
 * [EDGE_BAND_PEAK_ALPHA_LIGHT]）。
 */
internal object LiquidLegibilityTuning {
    const val TINT_RANGE = 0.3f
    const val MAX_TINT_ALPHA = 0.92f
    const val EDGE_RING_ALPHA = 0.08f
    const val EDGE_BAND_PEAK_ALPHA = 0.10f
    const val EDGE_RING_ALPHA_LIGHT = 0.55f

    /**
     * 浅色亮带：比暗带更宽、更强、衰减更缓（二次方），亮色一路缓缓过渡进去。2026-09-24 真机：
     * 0.30 峰值 + 14dp 三次方时，亮边约 10px 内就落回表面本色，内部读作灰；底栏 rim 还因折射
     * 采到栏外较暗像素，内侧有一道 231 的暗带，需要亮带盖住。
     */
    const val EDGE_BAND_PEAK_ALPHA_LIGHT = 0.55f
    const val EDGE_BAND_DP_LIGHT = 18f
    const val EDGE_BAND_DP = 14f
    const val EDGE_BAND_STEPS = 16

    /**
     * 距外沿第 [index] 级（1 起）处的目标暗度：(1−t)³ 缓出，贴边最深、内沿切线趋平，
     * 与中心无折角。2026-09-24 用户要求"过渡还要更细腻"：由 (1−t)² / 10 级改为三次方 / 16 级。
     */
    fun edgeBandDepthAlpha(index: Int, peak: Float = EDGE_BAND_PEAK_ALPHA, lightProfile: Boolean = false): Float {
        if (index < 1 || index > EDGE_BAND_STEPS) return 0f
        val t = (index - 0.5f) / EDGE_BAND_STEPS
        val f = 1f - t
        return if (lightProfile) peak * f * f else peak * f * f * f
    }

    /**
     * 第 [step] 条描边（宽 = step/N × 暗带宽）的 8 位 alpha。按**累积目标先取整再差分**：
     * 逐条取整会让 1~2 级的小差值忽大忽小，叠出不均匀的细台阶。
     */
    fun edgeBandStepAlpha255(
        step: Int,
        strength: Float,
        peak: Float = EDGE_BAND_PEAK_ALPHA,
        lightProfile: Boolean = false
    ): Int {
        fun cumulative(index: Int) = (255f * edgeBandDepthAlpha(index, peak, lightProfile) * strength).roundToInt()
        return (cumulative(step) - cumulative(step + 1)).coerceAtLeast(0)
    }

    /** 加厚上限：基线 + [TINT_RANGE]，不超过 [MAX_TINT_ALPHA]，也不低于基线。 */
    fun ceiling(base: Float): Float = (base + TINT_RANGE).coerceAtMost(MAX_TINT_ALPHA).coerceAtLeast(base)

    /** 与策略同一条线性映射：boost 0 → 基线，1 → [ceiling]。 */
    fun tintAlpha(base: Float, boost: Float): Float = base + (ceiling(base) - base) * boost.coerceIn(0f, 1f)
}

/** 普通、模态与形变表面在 GPU/fallback 下的透明度映射，集中为可穷举测试的纯策略。 */
internal object LiquidSurfaceAlphaPolicy {
    fun resolve(
        role: SurfaceRole,
        translucentFallback: Boolean,
        parameters: LiquidParameters
    ): Float = when {
        role == SurfaceRole.MODAL && translucentFallback ->
            parameters.fallbackModalSurfaceAlpha
        role == SurfaceRole.MODAL -> parameters.modalSurfaceAlpha
        role == SurfaceRole.MOTION_SURFACE && translucentFallback ->
            parameters.fallbackMotionSurfaceAlpha
        role == SurfaceRole.MOTION_SURFACE -> parameters.motionSurfaceAlpha
        // 浮动条走"更高级"的取色：玻璃层已按 glassContentAlpha 让真实下层参与，
        // 色罩只需要一层极薄的中性染色——罩厚了会把透入的清晰内容重新糊掉。
        role == SurfaceRole.FLOATING -> if (translucentFallback)
            (parameters.fallbackSurfaceAlpha + .03f).coerceAtMost(1f)
            else (parameters.surfaceAlpha + .02f).coerceAtMost(1f)
        role == SurfaceRole.TOP_BAR -> if (translucentFallback)
            parameters.fallbackSurfaceAlpha else parameters.surfaceAlpha * .8f
        role == SurfaceRole.SELECTED_ITEM -> if (translucentFallback)
            (parameters.fallbackSurfaceAlpha + .12f).coerceAtMost(1f)
            else (parameters.surfaceAlpha + .20f).coerceAtMost(1f)
        translucentFallback -> parameters.fallbackSurfaceAlpha
        else -> parameters.surfaceAlpha
    }

    /**
     * 玻璃折射层本身的输出不透明度：< 1 时**真实下层内容**参与合成——浮动条/选中胶囊
     * 借此透出位于其下方的滚动内容（"对下取色"），而不是只折射合成底图。
     * 折射截屏里表面区域本就被抑制遮罩换成稳定底图，所以这不引入任何反馈回路。
     * 普通卡片与模态层保持 1：它们的下层就是窗口底色，全不透反而更干净。
     */
    fun glassContentAlpha(role: SurfaceRole): Float = when (role) {
        // 0.65（2026-09-23 可读性改造，原 0.42）：直透从 58% 降到 35%。原值下栏里叠着一层
        // 58% 的锐利文字，与图标标签串读；"对下取色"改由滚动边缘溶解 + 自适应补偿承担，
        // 见 GlowFloatingChrome。
        SurfaceRole.FLOATING -> 0.65f
        // 呼出面板参考浮动条同一套"透出下层"做法：弹窗下面是 scrim 压暗的
        // 底页，38% 透入读作通透玻璃而非灰蒙遮罩；模态行文本的可读性由
        // scrim 自身的压暗与色罩兜底，不需要把玻璃层糊满。
        SurfaceRole.MODAL -> 0.62f
        SurfaceRole.SELECTED_ITEM -> 0.55f
        else -> 1f
    }
}
