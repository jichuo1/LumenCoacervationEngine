package com.lumen.coacervation.engine.geometry

/** Only a completed hardware draw records this relative source-to-target footprint. */
internal class ScrollSamplingGeometry {
    private val recorded = FloatArray(9)
    private var initialized = false

    fun needsRefresh(current: FloatArray): Boolean =
        current.size >= 9 && SamplingMatrixMath.isFinite(current) &&
            (!initialized || !SamplingMatrixMath.equal(recorded, current))

    fun record(current: FloatArray) {
        if (current.size < 9 || !SamplingMatrixMath.isFinite(current)) return
        current.copyInto(recorded, endIndex = 9)
        initialized = true
    }
}
