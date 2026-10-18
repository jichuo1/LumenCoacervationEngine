package com.lumen.coacervation.engine.host

import android.graphics.Color

/**
 * 棱镜玻璃（Prism）配置：按 Kyant AndroidLiquidGlass 的玻璃配方——饱和度增强 → 模糊 → 圆角矩形边缘折射
 * （可选色散）→ 表面色 → 边缘高光 → 内阴影，外加控件软阴影与按压光晕。
 *
 * 每一层都可以单独关闭或调参（"可插拔"）：关闭某层只少画那一层，其余层不变。所有数值构造时校验，
 * 滑块等连续输入先自行收进范围。默认值取自参考库底栏容器（blur 8dp、saturation 1.5、lens 24dp/24dp、
 * 表面 #FAFAFA / #121212 @ 40%、0.5dp 白色高光、24dp 阴影），见 `docs/PRISM_MATERIAL.md`。
 *
 * 外阴影由本材质自己画在控件边界之外：祖先需要 `clipChildren = false`（与控件软阴影相同），
 * 也不要再用 `LumenControlShadow` 包一层，否则阴影叠加两次。
 *
 * @property enabled false 时直接显示不透明回退色，不采样背景。
 * @property cornerRadiusDp 圆角；null 为胶囊（半径取高度一半，上限 128dp）。
 * @property reduceTransparency 无障碍"降低透明度"：显示回退色。
 * @property reduceMotion 关闭按压光晕与回退淡入淡出的动画（立即到位）。
 * @property fallbackOnLowRamDevice 低内存设备显示回退色。
 * @property fallbackOnPowerSave 省电模式显示回退色。
 * @property shadow 外阴影（参考库 `Shadow.Default` 的高斯模糊阴影）。
 */
public data class LumenPrismOptions(
    val enabled: Boolean = true,
    val cornerRadiusDp: Float? = null,
    val blur: LumenPrismBlurOptions = LumenPrismBlurOptions(),
    val lens: LumenPrismLensOptions = LumenPrismLensOptions(),
    val surface: LumenPrismSurfaceOptions = LumenPrismSurfaceOptions(),
    val highlight: LumenPrismHighlightOptions = LumenPrismHighlightOptions(),
    val innerShadow: LumenPrismInnerShadowOptions = LumenPrismInnerShadowOptions(),
    val press: LumenPrismPressOptions = LumenPrismPressOptions(),
    val shadow: LumenPrismShadowOptions = LumenPrismShadowOptions(),
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val fallbackOnLowRamDevice: Boolean = true,
    val fallbackOnPowerSave: Boolean = false
) {
    init {
        cornerRadiusDp?.let { require(it.isFinite() && it in 0f..128f) { "cornerRadiusDp must be in [0, 128], was $it" } }
    }
}

/**
 * 模糊与饱和度（参考库的 `vibrancy()` + `blur()`）。API 31+ 由 GPU 节点完成；API 27–30 走引擎软件模糊，
 * 该路径不做饱和度增强。
 *
 * @property enabled 只控制模糊；饱和度独立生效（设为 1 关闭）。
 * @property radiusDp 模糊半径，0 只保留饱和度与折射。
 * @property saturation 饱和度倍率，1 不变；按 Rec.709 亮度权重（0.213 / 0.715 / 0.072）计算。
 */
public data class LumenPrismBlurOptions(
    val enabled: Boolean = true,
    val radiusDp: Float = 8f,
    val saturation: Float = 1.5f
) {
    init {
        prismBounded("radiusDp", radiusDp, 0f, 48f)
        prismBounded("saturation", saturation, 0f, 2f)
    }
}

/**
 * 边缘折射（参考库 `lens()`）：只在距边缘 [refractionHeightDp] 的带内偏移取样，圆弧剖面
 * `1 − sqrt(1 − x²)`，边缘处偏移 [refractionAmountDp]、向内取样。需要 API 33（RuntimeShader），
 * 更低版本只有模糊。
 *
 * @property depthEffect 0–1：把径向方向混入法线，形成"凸透镜"式的整体放大感。
 * @property dispersion 0–1：色散强度，按四个象限 `x·y` 分布、轴线上为 0，七次取样合成。
 */
public data class LumenPrismLensOptions(
    val enabled: Boolean = true,
    val refractionHeightDp: Float = 24f,
    val refractionAmountDp: Float = 24f,
    val depthEffect: Float = 0f,
    val dispersion: Float = 0f
) {
    init {
        prismBounded("refractionHeightDp", refractionHeightDp, 0f, 64f)
        prismBounded("refractionAmountDp", refractionAmountDp, 0f, 64f)
        prismBounded("depthEffect", depthEffect, 0f, 1f)
        prismBounded("dispersion", dispersion, 0f, 1f)
    }
}

/**
 * 表面色：画在折射后的背景之上。颜色或不透明度为 null 时按深浅色取参考库默认
 * （浅色 #FAFAFA、深色 #121212，40%）。深浅色以 `palette.background` 亮度 < 0.5 判定，与 Fluent / Mica 一致。
 * [tintColor] 只取 RGB，透明度由 [tintOpacity] 决定。
 *
 * 回退（背景不可用、降低透明度、省电等）显示一块不透明色：[fallbackColor] 非 null 时直接用它（只取 RGB）；
 * 否则是深浅色的中性底（#FAFAFA / #121212）上叠 [fallbackOpacity] 的表面色——默认表面色与中性底同色，
 * 自定义表面色（例如强调色）则在回退时保留色相。
 */
public data class LumenPrismSurfaceOptions(
    val tintColor: Int? = null,
    val tintOpacity: Float? = null,
    val fallbackColor: Int? = null,
    val fallbackOpacity: Float = 0.86f
) {
    init {
        tintOpacity?.let { prismBounded("tintOpacity", it, 0f, 1f) }
        prismBounded("fallbackOpacity", fallbackOpacity, 0f, 1f)
    }
}

/**
 * 边缘高光（参考库 `Highlight.Default`）：沿轮廓内侧的细边，强度 `|dot(法线, 光向)|^falloff`，
 * 光向 [angleDegrees]（屏幕坐标，y 向下）对应的两条对角边最亮。按加色混合叠加，[color] 只取 RGB。
 * API 33 以下没有角度分布，退化为均匀细边。加色白光在浅色内容上几乎看不见，这与参考库一致；
 * 浅色主题里玻璃边缘主要靠外阴影区分。
 */
public data class LumenPrismHighlightOptions(
    val enabled: Boolean = true,
    val widthDp: Float = 0.5f,
    val opacity: Float = 0.5f,
    val angleDegrees: Float = 45f,
    val falloff: Float = 1f,
    val color: Int = Color.WHITE
) {
    init {
        prismBounded("widthDp", widthDp, 0f, 4f)
        prismBounded("opacity", opacity, 0f, 1f)
        prismBounded("angleDegrees", angleDegrees, -360f, 360f)
        prismBounded("falloff", falloff, 0f, 8f)
    }
}

/**
 * 外阴影：参考库 `Shadow.Default`——圆角矩形高斯模糊（模糊半径 [radiusDp]，按 Android BlurMaskFilter
 * 语义），向下 [offsetYDp]，颜色 [color] 的 [opacity]。只画在玻璃轮廓之外，不压暗透过玻璃的内容。
 * 需要 API 28（硬件加速的模糊遮罩），更低版本不画。
 */
public data class LumenPrismShadowOptions(
    val enabled: Boolean = true,
    val radiusDp: Float = 24f,
    val offsetYDp: Float = 4f,
    val opacity: Float = 0.10f,
    val color: Int = Color.BLACK
) {
    init {
        prismBounded("radiusDp", radiusDp, 0f, 48f)
        prismBounded("offsetYDp", offsetYDp, -16f, 16f)
        prismBounded("opacity", opacity, 0f, 0.5f)
    }
}

/** 内阴影：沿顶边向内渐隐的暗带，默认关闭（参考库底栏不使用）。 */
public data class LumenPrismInnerShadowOptions(
    val enabled: Boolean = false,
    val radiusDp: Float = 24f,
    val opacity: Float = 0.15f,
    val color: Int = Color.BLACK
) {
    init {
        prismBounded("radiusDp", radiusDp, 0f, 48f)
        prismBounded("opacity", opacity, 0f, 0.6f)
    }
}

/**
 * 按压反馈（参考库 `InteractiveHighlight`）：以按压点为中心的径向白光（全强到 0.75 倍短边、
 * 1.5 倍短边处为 0）加一层平铺白光，均为加色混合。由背景的 `state_pressed` 与热点驱动，
 * 不安装触摸监听，不改变 View 的缩放（缩放交给引擎的长按弹性）。
 */
public data class LumenPrismPressOptions(
    val enabled: Boolean = true,
    val glowOpacity: Float = 0.15f,
    val washOpacity: Float = 0.08f
) {
    init {
        prismBounded("glowOpacity", glowOpacity, 0f, 0.5f)
        prismBounded("washOpacity", washOpacity, 0f, 0.3f)
    }
}

/** [BUDGET_EXCEEDED]：宿主会话的表面数已满，未能绑定采样。 */
public enum class LumenPrismFallbackReason {
    NONE, DISABLED, REDUCE_TRANSPARENCY, LOW_RAM_DEVICE, POWER_SAVE, NO_BACKDROP, BUDGET_EXCEEDED, BACKDROP_UNAVAILABLE, CLOSED
}

/**
 * @property glassVisible 当前帧是否画出了采样背景（含淡入中）。
 * @property lensActive 折射是否生效（API 33+、折射开启且采样走 GPU）。
 * @property dark 当前按深色主题取默认值。
 */
public data class LumenPrismDiagnostics(
    val closed: Boolean,
    val glassVisible: Boolean,
    val fallbackReason: LumenPrismFallbackReason,
    val backdropBackend: LumenSurfaceBackend,
    val lensActive: Boolean,
    val dark: Boolean,
    val draws: Long
)

private fun prismBounded(name: String, value: Float, min: Float, max: Float) {
    require(value.isFinite() && value in min..max) { "$name must be in [$min, $max], was $value" }
}
