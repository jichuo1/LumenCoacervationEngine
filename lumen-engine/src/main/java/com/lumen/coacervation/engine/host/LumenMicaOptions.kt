package com.lumen.coacervation.engine.host

/** Opaque window foundations. BASE_ALT exposes more wallpaper colour and supports a commanding layer. */
public enum class LumenMicaKind { BASE, BASE_ALT }

public enum class LumenMicaFallbackReason {
    NONE, DISABLED, HIGH_CONTRAST, REDUCE_TRANSPARENCY, LOW_RAM_DEVICE, POWER_SAVE,
    WINDOW_INACTIVE, NO_BACKGROUND, MEMORY_PRESSURE, RENDER_UNAVAILABLE, CLOSED
}

/** Layers placed over the single Mica foundation; these never sample or blur content. */
public enum class LumenMicaLayer { COMMAND, CONTENT, CARD }

/**
 * Optional Android adaptation of Mica, not a Windows compositor implementation.
 * Null tint/opacity values follow the kind and palette. High contrast uses the host palette background.
 * Text and accessibility remain host-owned; forward the host's accessibility settings explicitly.
 */
public data class LumenMicaOptions(
    val enabled: Boolean = true,
    val kind: LumenMicaKind = LumenMicaKind.BASE,
    val tintColor: Int? = null,
    val tintOpacity: Float? = null,
    val luminosityOpacity: Float? = null,
    val fallbackColor: Int? = null,
    val fallbackWhenWindowInactive: Boolean = true,
    val fallbackOnPowerSave: Boolean = true,
    val fallbackOnLowRamDevice: Boolean = true,
    val highContrast: Boolean = false,
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false
) {
    init {
        require(tintOpacity == null || tintOpacity.isFinite() && tintOpacity in 0f..1f)
        require(luminosityOpacity == null || luminosityOpacity.isFinite() && luminosityOpacity in 0f..1f)
    }
}

public data class LumenMicaDiagnostics(
    val closed: Boolean,
    val kind: LumenMicaKind,
    val fallbackReason: LumenMicaFallbackReason,
    /** Contribution to the last draw; may remain true during a fallback transition. */
    val micaVisible: Boolean,
    val snapshotBytes: Int,
    val draws: Long,
    val transitions: Long
)
