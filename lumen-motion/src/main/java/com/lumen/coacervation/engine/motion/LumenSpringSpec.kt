package com.lumen.coacervation.engine.motion

/**
 * 弹簧参数规格：`stiffness`（刚度）与 `dampingRatio`（阻尼比），默认即引擎长按回弹在用的 310 / 0.7。
 *
 * 除直接给物理量外，也可用设计口语的"周期 + 弹跳"构造（[ofResponseBounce]）：
 * `stiffness = (2π / response)²`、`dampingRatio = 1 − bounce`。这组换算与 ColorOS `COUISpringForce`
 * 的语义一致，厂商设计稿里的"response 0.3s、bounce 0.15"可直接映射。
 *
 * 超范围在构造时抛出 [IllegalArgumentException]；`dampingRatio` 恒小于 1（欠阻尼），
 * 临界/过阻尼场景请用插值器，弹簧轴不覆盖那种形态。
 */
public data class LumenSpringSpec(
    val stiffness: Float = 310f,
    val dampingRatio: Float = .7f
) {
    init {
        require(stiffness.isFinite() && stiffness in MIN_STIFFNESS..MAX_STIFFNESS) {
            "stiffness must be in [$MIN_STIFFNESS, $MAX_STIFFNESS], was $stiffness"
        }
        require(dampingRatio.isFinite() && dampingRatio in MIN_DAMPING_RATIO..MAX_DAMPING_RATIO) {
            "dampingRatio must be in [$MIN_DAMPING_RATIO, $MAX_DAMPING_RATIO], was $dampingRatio"
        }
    }

    public companion object {
        public const val MIN_STIFFNESS: Float = 20f
        public const val MAX_STIFFNESS: Float = 5000f
        public const val MIN_DAMPING_RATIO: Float = .05f
        public const val MAX_DAMPING_RATIO: Float = .95f

        /** 引擎原样（长按回弹在用的刚度 310、阻尼比 0.7）。 */
        @JvmField
        public val DEFAULT: LumenSpringSpec = LumenSpringSpec()

        /**
         * 以"周期（秒）+ 弹跳（0 无回弹过冲）"构造。周期映射刚度 `(2π / response)²`，
         * 弹跳映射阻尼比 `1 − bounce`；两个参数都会收进合法范围（周期 0.08–1.5s）。
         */
        @JvmStatic
        public fun ofResponseBounce(responseSeconds: Float, bounce: Float): LumenSpringSpec {
            require(responseSeconds.isFinite() && responseSeconds in 0.08f..1.5f) {
                "responseSeconds must be in [0.08, 1.5], was $responseSeconds"
            }
            require(bounce.isFinite() && bounce in 0f..0.9f) {
                "bounce must be in [0, 0.9], was $bounce"
            }
            val omega = (2.0 * Math.PI / responseSeconds)
            return LumenSpringSpec(
                (omega * omega).toFloat().coerceIn(MIN_STIFFNESS, MAX_STIFFNESS),
                (1f - bounce).coerceIn(MIN_DAMPING_RATIO, MAX_DAMPING_RATIO))
        }
    }
}
