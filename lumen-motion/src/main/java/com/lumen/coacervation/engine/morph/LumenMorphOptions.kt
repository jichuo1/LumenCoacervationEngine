package com.lumen.coacervation.engine.morph

import com.lumen.coacervation.engine.motion.LumenSpringSpec

/**
 * 液态形变（1 格 ↔ 2 格磁贴）的配置。
 *
 * 形变由三条**独立弹簧**驱动：表面宽度、圆角、内容。宽度和圆角的响应/回弹错开时，
 * 胶囊会先被“挤出去”、圆角再追上，形成液滴感；内容使用更快的弹簧，形成领先/滞后。
 * 所有参数在构造时校验；Demo 的连续滑块可直接映射到这些字段。
 *
 * @property enabled 是否启用弹簧动画；false 时状态切换直接落到目标值（仍保留几何/内容变化）。
 * @property collapsedFraction 收起态宽度占外层 2 格宽度的比例（0.2–1，默认 0.5 即 1 格）。
 * @property collapsedRadiusDp 收起态圆角（dp）；运行时会收进短边的一半，默认 80dp 即圆形。
 * @property expandedRadiusDp 展开态圆角（dp）。
 * @property cornerSmoothing 连续曲率平滑量（0–0.6）；0 为普通圆角，越大越接近 squircle。
 * @property contentScale 展开态图标缩放（0.5–2，默认 1）。
 * @property fadeLabel 展开时文字是否淡入；false 时文字恒定 alpha，仅被形状裁切。
 * @property edgeHighlight 是否绘制贴边高光（跟随 LumenEffectTuning 的厚度/亮度）。
 * @property contactLine 是否绘制贴边接触线。
 * @property contactAlpha 接触线 alpha（0–0.3，默认 0.08）。
 * @property widthSpring 表面宽度弹簧。
 * @property cornerSpring 圆角/平滑弹簧。
 * @property contentSpring 内容位移/缩放/淡入弹簧。
 */
public data class LumenMorphOptions(
    val enabled: Boolean = true,
    val collapsedFraction: Float = 0.5f,
    val collapsedRadiusDp: Float = 80f,
    val expandedRadiusDp: Float = 28f,
    val cornerSmoothing: Float = 0.25f,
    val contentScale: Float = 1f,
    val fadeLabel: Boolean = true,
    val edgeHighlight: Boolean = true,
    val contactLine: Boolean = true,
    val contactAlpha: Float = 0.08f,
    val widthSpring: LumenSpringSpec = LumenSpringSpec.ofResponseBounce(0.35f, 0.17f),
    val cornerSpring: LumenSpringSpec = LumenSpringSpec.ofResponseBounce(0.35f, 0.16f),
    val contentSpring: LumenSpringSpec = LumenSpringSpec.ofResponseBounce(0.2f, 0f)
) {
    init {
        require(collapsedFraction.isFinite() && collapsedFraction in .2f..1f) {
            "collapsedFraction must be in [0.2, 1], was $collapsedFraction"
        }
        require(collapsedRadiusDp.isFinite() && collapsedRadiusDp in 0f..200f) {
            "collapsedRadiusDp must be in [0, 200], was $collapsedRadiusDp"
        }
        require(expandedRadiusDp.isFinite() && expandedRadiusDp in 0f..200f) {
            "expandedRadiusDp must be in [0, 200], was $expandedRadiusDp"
        }
        require(cornerSmoothing.isFinite() && cornerSmoothing in 0f..0.6f) {
            "cornerSmoothing must be in [0, 0.6], was $cornerSmoothing"
        }
        require(contentScale.isFinite() && contentScale in .5f..2f) {
            "contentScale must be in [0.5, 2], was $contentScale"
        }
        require(contactAlpha.isFinite() && contactAlpha in 0f..0.3f) {
            "contactAlpha must be in [0, 0.3], was $contactAlpha"
        }
    }

    public companion object {
        @JvmField
        public val DEFAULT: LumenMorphOptions = LumenMorphOptions()
    }
}
