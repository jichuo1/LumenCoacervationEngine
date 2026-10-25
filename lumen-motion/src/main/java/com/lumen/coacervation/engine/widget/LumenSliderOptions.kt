package com.lumen.coacervation.engine.widget

import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.host.LumenPrismOptions

/**
 * 滑块的取值范围（自 1.5.5）。[step] 为 0 表示连续值；大于 0 时值吸附到 `from + k·step`，
 * 范围不是 step 整数倍时 [to] 作为最后一档。构造时校验，拒绝非有限值与空范围。
 */
public data class LumenSliderRange(
    val from: Float = 0f,
    val to: Float = 1f,
    val step: Float = 0f
) {
    init {
        require(from.isFinite() && to.isFinite() && to > from) { "LumenSliderRange needs finite from < to, was $from..$to" }
        require(step.isFinite() && step >= 0f && step <= to - from) { "step must be in [0, ${to - from}], was $step" }
        require(step == 0f || (to - from) / step <= LumenSliderPolicy.MAX_STOPS) {
            "at most ${LumenSliderPolicy.MAX_STOPS} steps, was ${(to - from) / step}"
        }
    }
}

/** 形态。[AUTO] 按外观选择：柔光/Liquid、Plain 用亮度条式胶囊，Fluent/Mica/Prism 用细轨 + 滑钮。 */
public enum class LumenSliderShape {
    AUTO,
    /** 手机亮度条式粗胶囊：填充即数值，可放图标。 */
    CAPSULE,
    /** 细轨 + 滑钮。 */
    CLASSIC
}

/** 数值气泡。[AUTO]：Fluent/Mica 拖动时显示（WinUI ThumbToolTip），其余不显示。 */
public enum class LumenSliderValueLabel { AUTO, NONE, ON_DRAG }

/**
 * 手势处的 HDR 高光。[AUTO]：柔光/Liquid、Plain 开启；Fluent/Mica 关闭（不属于 Fluent 视觉语言）；
 * Prism 关闭（高光正好落在镜片下，会把折射洗成白块，镜片自带按压光晕）。
 * 开启时 API 34+ 且显示器报告 HDR 余量才输出超过 SDR 白的颜色；窗口的 `COLOR_MODE_HDR` 由宿主决定，
 * 未开启时平台按 SDR 钳制，观感与 SDR 回退一致。
 */
public enum class LumenSliderHdr { AUTO, OFF, ON }

/**
 * 滑块的可选项。全部在构造时校验。
 *
 * - [capsuleHeightDp]：胶囊形态的高度，28–64dp；
 * - [hdrHeadroom]：HDR 高光峰值相对 SDR 白的倍数上限，1–4，实际取 min(本值, 显示器当前余量)；
 * - [magnetStrength]：离散刻度拖动时的磁吸强度，0（无）–1；
 * - [haptics]：越过刻度或到达两端时的轻触觉，受系统与 View 的触觉开关约束；
 * - [reduceMotion]：立即落位，不做橡皮筋、放大与弹簧。
 */
public data class LumenSliderOptions(
    val shape: LumenSliderShape = LumenSliderShape.AUTO,
    val valueLabel: LumenSliderValueLabel = LumenSliderValueLabel.AUTO,
    val hdrHighlight: LumenSliderHdr = LumenSliderHdr.AUTO,
    val capsuleHeightDp: Float = LumenSliderPolicy.CAPSULE_HEIGHT_DP,
    val hdrHeadroom: Float = LumenSliderPolicy.DEFAULT_HDR_HEADROOM,
    val magnetStrength: Float = 0.5f,
    val showTicks: Boolean = true,
    val haptics: Boolean = true,
    val reduceMotion: Boolean = false
) {
    init {
        require(capsuleHeightDp.isFinite() && capsuleHeightDp in 28f..64f) { "capsuleHeightDp must be in [28, 64], was $capsuleHeightDp" }
        require(hdrHeadroom.isFinite() && hdrHeadroom in 1f..LumenSliderPolicy.MAX_HDR_HEADROOM) {
            "hdrHeadroom must be in [1, ${LumenSliderPolicy.MAX_HDR_HEADROOM}], was $hdrHeadroom"
        }
        require(magnetStrength.isFinite() && magnetStrength in 0f..1f) { "magnetStrength must be in [0, 1], was $magnetStrength" }
    }
}

/**
 * 滑块的材质外观。所有外观都只通过引擎公开入口取材质，滑块不自己选择 Liquid 或柔光。
 */
public sealed class LumenSliderAppearance {
    /**
     * 跟随窗口材质（柔光 / Liquid）：胶囊槽是 `lumen.surface(…, SELECTED_ITEM)`，填充与滑钮沿用引擎开关的
     * 软高光语言。[lumen] 为 null 时槽体退为纯渐变（与 [Plain] 相同）。
     */
    public class Skin(public val lumen: LumenActivityDelegate?) : LumenSliderAppearance()

    /** WinUI Slider：细轨、外圈 + 强调色内点滑钮、拖动气泡；滑钮的层级描边与阴影来自 `LumenFluentControl`。 */
    public class Fluent(public val elevated: Boolean = true) : LumenSliderAppearance()

    /** 放在云母层上的 Fluent 滑块：平面、只保留层级描边；[highContrast] 时改用调色板纯色。 */
    public class Mica(public val highContrast: Boolean = false) : LumenSliderAppearance()

    /**
     * 棱镜镜片：静止时是不透明白色胶囊钮，按住变成放大轨道的透明镜片（两轴果冻弹簧、速度挤压）。
     *
     * 镜片**不采样背景**、不需要会话：引擎玻璃要求采样来源完全覆盖玻璃，而滑块轨道大部分透明，采样只会得到
     * 一块回退色。镜片把轨道内容画进滑钮并由滑钮缩放放大，轨道以外透出真实背景；亮边、内阴影、表面色、
     * 按压光与外阴影读 [options]（默认 [LENS]），与棱镜玻璃同一套参数。不做 AGSL 折射与色散。
     */
    public class Prism(
        public val options: LumenPrismOptions = LENS
    ) : LumenSliderAppearance() {
        public companion object {
            /**
             * 滑钮镜片配方：表面色 6%、1dp 亮边、浅内阴影、10% 按压光、紧凑外阴影（10dp / 2dp / 16%）、无按压洗白。
             * 卡片默认的 40% 表面色在 20dp 高的钮上会糊成一块白色。模糊与折射参数保留给需要卡片玻璃的宿主，镜片不使用。
             */
            @JvmField
            public val LENS: LumenPrismOptions = LumenPrismOptions().let {
                it.copy(
                    blur = it.blur.copy(enabled = false, saturation = 1.3f),
                    lens = it.lens.copy(refractionHeightDp = 8f, refractionAmountDp = 10f, depthEffect = 0f, dispersion = 0f),
                    surface = it.surface.copy(tintOpacity = .06f),
                    highlight = it.highlight.copy(widthDp = 1f, opacity = .8f),
                    innerShadow = it.innerShadow.copy(enabled = true, radiusDp = 8f, opacity = .12f),
                    press = it.press.copy(glowOpacity = .1f, washOpacity = 0f),
                    shadow = it.shadow.copy(radiusDp = 10f, offsetYDp = 2f, opacity = .16f)
                )
            }
        }
    }

    /** 只用调色板，不接任何引擎表面。 */
    public object Plain : LumenSliderAppearance()
}

/** 值变化回调。拖动中按吸附后的值逐次通知；程序 `setValue` 不回调。原始类型参数，逐次不装箱。 */
public fun interface LumenSliderValueListener {
    public fun onValueChange(slider: LumenSlider, value: Float, fromUser: Boolean)
}

/** 拖动开始与结束（松手、取消）。需要"松手才提交"的设置在 [onSlideFinished] 里提交。 */
public interface LumenSliderSlideListener {
    public fun onSlideStarted(slider: LumenSlider) {}
    public fun onSlideFinished(slider: LumenSlider, value: Float) {}
}

/** 数值文案：用于数值气泡与无障碍朗读，只在值变化时调用。 */
public fun interface LumenSliderValueFormatter {
    public fun format(value: Float): CharSequence
}
