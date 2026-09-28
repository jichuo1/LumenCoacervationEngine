package com.lumen.coacervation.engine.model

/**
 * 宿主可调的视效强度：全部是相对引擎默认值的**倍率**，默认 1 即引擎原样。
 *
 * 引擎的每处数值都是真机调出来的，这里不暴露具体的 dp 或 alpha，只让宿主整体放大或收小。
 * 倍率由引擎按各处原值换算，所以同一个倍率在两套材质、各种表面角色上保持同样的相对变化。
 *
 * 生效时机：
 * - 表面边缘高光（[edgeHighlightWidth]、[edgeHighlightIntensity]）在会话创建时读取，改动后宿主需要重建
 *   Activity（与切换材质相同）；
 * - 长按拖动光晕（[dragGlowIntensity]、[dragGlowRadius]）在每次按下时读取，可以即时生效，见
 *   `LumenElasticInteraction` 的 `effectTuning` 参数。
 *
 * 超出范围的值在构造时抛出 [IllegalArgumentException]；来自滑块等连续输入时先用 [clamped] 收进范围。
 *
 * @property edgeHighlightWidth 表面边缘高光的厚度倍率，范围 [MIN_EDGE_WIDTH]..[MAX_EDGE_WIDTH]。
 *   柔光材质缩放边框描边；高级材质同时缩放折射 rim 带（菲涅尔、镜面高光铺展的宽度）与轮廓描边。
 * @property edgeHighlightIntensity 表面边缘高光的亮度倍率，范围 0..[MAX_EDGE_INTENSITY]；0 关闭边缘高光。
 * @property dragGlowIntensity 长按拖动时触点光晕的亮度倍率，范围 0..[MAX_DRAG_GLOW_INTENSITY]；0 关闭光晕，形变照常。
 * @property dragGlowRadius 长按拖动时触点光晕的半径倍率，范围 [MIN_DRAG_GLOW_RADIUS]..[MAX_DRAG_GLOW_RADIUS]。
 */
public data class LumenEffectTuning(
    val edgeHighlightWidth: Float = 1f,
    val edgeHighlightIntensity: Float = 1f,
    val dragGlowIntensity: Float = 1f,
    val dragGlowRadius: Float = 1f
) {
    init {
        requireIn("edgeHighlightWidth", edgeHighlightWidth, MIN_EDGE_WIDTH, MAX_EDGE_WIDTH)
        requireIn("edgeHighlightIntensity", edgeHighlightIntensity, 0f, MAX_EDGE_INTENSITY)
        requireIn("dragGlowIntensity", dragGlowIntensity, 0f, MAX_DRAG_GLOW_INTENSITY)
        requireIn("dragGlowRadius", dragGlowRadius, MIN_DRAG_GLOW_RADIUS, MAX_DRAG_GLOW_RADIUS)
    }

    public companion object {
        public const val MIN_EDGE_WIDTH: Float = 0.25f
        public const val MAX_EDGE_WIDTH: Float = 4f
        public const val MAX_EDGE_INTENSITY: Float = 3f
        public const val MAX_DRAG_GLOW_INTENSITY: Float = 4f
        public const val MIN_DRAG_GLOW_RADIUS: Float = 0.5f
        public const val MAX_DRAG_GLOW_RADIUS: Float = 2f

        /** 引擎原样。 */
        @JvmField
        public val DEFAULT: LumenEffectTuning = LumenEffectTuning()

        /** 把任意输入收进合法范围后构造；非有限值（NaN、无穷）按 1 处理。 */
        @JvmStatic
        @JvmOverloads
        public fun clamped(
            edgeHighlightWidth: Float = 1f,
            edgeHighlightIntensity: Float = 1f,
            dragGlowIntensity: Float = 1f,
            dragGlowRadius: Float = 1f
        ): LumenEffectTuning = LumenEffectTuning(
            edgeHighlightWidth = edgeHighlightWidth.finiteOrOne().coerceIn(MIN_EDGE_WIDTH, MAX_EDGE_WIDTH),
            edgeHighlightIntensity = edgeHighlightIntensity.finiteOrOne().coerceIn(0f, MAX_EDGE_INTENSITY),
            dragGlowIntensity = dragGlowIntensity.finiteOrOne().coerceIn(0f, MAX_DRAG_GLOW_INTENSITY),
            dragGlowRadius = dragGlowRadius.finiteOrOne().coerceIn(MIN_DRAG_GLOW_RADIUS, MAX_DRAG_GLOW_RADIUS)
        )

        private fun Float.finiteOrOne(): Float = if (isFinite()) this else 1f

        private fun requireIn(name: String, value: Float, min: Float, max: Float) {
            require(value.isFinite() && value >= min && value <= max) { "$name must be in [$min, $max], was $value" }
        }
    }
}
