package com.lumen.coacervation.engine.host

import com.lumen.coacervation.engine.model.SurfaceRole

/** Starting points, not persisted choices. Every field can still be changed with copy(). */
public object LumenSurfacePresets {
    @JvmStatic public fun floating(material: LumenSurfaceMaterial = LumenSurfaceMaterial.FROSTED): LumenSurfaceOptions =
        LumenSurfaceOptions(material = material)

    @JvmStatic @JvmOverloads
    public fun fadingBand(hold: Float = 0.25f, end: Float = 1f, intervalMs: Long = 0L): LumenSurfaceOptions =
        LumenSurfaceOptions(role = SurfaceRole.TOP_BAR, radiusDp = 0f, edgeEnabled = false,
            sampling = LumenSurfaceSampling(refractionEnabled = false, minIntervalMs = intervalMs,
                fadeEnabled = true, fadeHold = hold, fadeEnd = end))

    @JvmStatic public fun staticPanel(): LumenSurfaceOptions =
        LumenSurfaceOptions(material = LumenSurfaceMaterial.STATIC, role = SurfaceRole.MODAL,
            radiusDp = 16f, sampling = LumenSurfaceSampling(enabled = false))
}
