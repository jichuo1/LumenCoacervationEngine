package com.lumen.coacervation.engine.host

/**
 * Fluent acrylic presets. Values follow WinUI's `AcrylicInAppFillColor*Brush` theme resources;
 * ACCENT tints with [com.lumen.coacervation.engine.model.LumenPalette.primary].
 */
public enum class LumenAcrylicKind { DEFAULT, BASE, ACCENT }

/** Fluent elevation values (Windows "Layering and elevation"). Android dp are derived, see FLUENT_MATERIAL.md. */
public enum class LumenFluentElevation(public val fluentValue: Int) {
    LAYER(1), CONTROL(2), CARD(8), TOOLTIP(16), FLYOUT(32), DIALOG(128)
}

/** Why the solid fallback colour is shown instead of acrylic. NONE means acrylic is requested and allowed. */
public enum class LumenAcrylicFallbackReason {
    NONE, DISABLED, REDUCE_TRANSPARENCY, LOW_RAM_DEVICE, POWER_SAVE, WINDOW_INACTIVE,
    NO_BACKDROP, BACKDROP_UNAVAILABLE, CLOSED
}

/** EXACT uses the platform luminosity/colour blend modes (API 29+); APPROXIMATE uses source-over layers. */
public enum class LumenAcrylicBlend { EXACT, APPROXIMATE }

/**
 * In-app acrylic recipe: opaque fallback base, blurred backdrop, luminosity blend, colour tint, 2% noise.
 *
 * Null colours and opacities take the [kind] theme defaults. When [tintColor] or [tintOpacity] is
 * customised and [luminosityOpacity] is null, luminosity is derived with WinUI's formula.
 */
public data class LumenAcrylicOptions(
    val enabled: Boolean = true,
    val kind: LumenAcrylicKind = LumenAcrylicKind.DEFAULT,
    val tintColor: Int? = null,
    val tintOpacity: Float? = null,
    val luminosityOpacity: Float? = null,
    val fallbackColor: Int? = null,
    /** Platform blur radius; 48dp is the closest allowed value to Fluent's 30 standard deviations. */
    val blurRadiusDp: Float = 48f,
    val noiseOpacity: Float = 0.02f,
    /** Background-acrylic behaviour: fall back while the window is not focused. In-app acrylic keeps it off. */
    val fallbackWhenWindowInactive: Boolean = false,
    val fallbackOnPowerSave: Boolean = true,
    val fallbackOnLowRamDevice: Boolean = true,
    /** False forces APPROXIMATE blending, for devices whose advanced blend modes misbehave. */
    val exactBlend: Boolean = true
) {
    init {
        require(tintOpacity == null || tintOpacity.isFinite() && tintOpacity in 0f..1f)
        require(luminosityOpacity == null || luminosityOpacity.isFinite() && luminosityOpacity in 0f..1f)
        require(blurRadiusDp.isFinite() && blurRadiusDp in 0f..48f)
        require(noiseOpacity.isFinite() && noiseOpacity in 0f..0.1f)
    }
}

/** Contour and shadow. Shadows use the platform View elevation; the stroke is drawn inside the bounds. */
public data class LumenFluentElevationOptions(
    val level: LumenFluentElevation = LumenFluentElevation.FLYOUT,
    val shadowEnabled: Boolean = true,
    val strokeEnabled: Boolean = true,
    val strokeWidthDp: Float = 1f,
    /** Uses the on-accent stroke pair, for controls filled with the accent colour. */
    val accentStroke: Boolean = false,
    /** Fluent controls drop from elevation 2 to 1 while pressed; other levels halve. */
    val pressedLowersElevation: Boolean = true
) {
    init { require(strokeWidthDp.isFinite() && strokeWidthDp in 0.5f..2f) }
}

/** Everything is optional; the control's text, children, clicks and accessibility stay host-owned. */
public data class LumenFluentControlOptions(
    val acrylic: LumenAcrylicOptions = LumenAcrylicOptions(),
    val elevation: LumenFluentElevationOptions = LumenFluentElevationOptions(),
    /** Null uses Fluent's ControlCornerRadius (4dp) for LAYER/CONTROL and OverlayCornerRadius (8dp) otherwise. */
    val cornerRadiusDp: Float? = null,
    /** Subtle hover/pressed fills drawn over the material. */
    val stateLayerEnabled: Boolean = true,
    /** Two-ring focus visual, shown only for keyboard focus (not in touch mode). */
    val focusVisualEnabled: Boolean = true,
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false
) {
    init { require(cornerRadiusDp == null || cornerRadiusDp.isFinite() && cornerRadiusDp in 0f..128f) }
}

public data class LumenFluentDiagnostics(
    val closed: Boolean,
    /** Acrylic layers contribute to the last frame (crossfade weight above zero). */
    val acrylicVisible: Boolean,
    val fallbackReason: LumenAcrylicFallbackReason,
    val backdropBackend: LumenSurfaceBackend,
    val blend: LumenAcrylicBlend,
    val draws: Long,
    val transitions: Long,
    val noiseTextureBytes: Int
)
