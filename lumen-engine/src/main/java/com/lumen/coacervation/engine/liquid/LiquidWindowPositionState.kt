package com.lumen.coacervation.engine.liquid

import com.lumen.coacervation.engine.geometry.SamplingMatrixMath

/** 窗口级位置门控：监听实际屏幕变换，覆盖 IME 平移，不创建静止帧。 */
internal class LiquidWindowPositionState {
    private val recorded = FloatArray(9)
    private var initialized = false

    fun update(current: FloatArray): Boolean {
        if (current.size < 9 || !SamplingMatrixMath.isFinite(current)) return false
        if (initialized && SamplingMatrixMath.equal(recorded, current)) return false
        current.copyInto(recorded, endIndex = 9)
        initialized = true
        return true
    }
}
