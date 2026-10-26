package com.lumen.coacervation.engine.runtime

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Material families that share one opacity mapping (MATERIAL_OPACITY.md §4). */
internal enum class MaterialKind { FROSTED, LIQUID, FLUENT, MICA, PRISM, PLAIN }

/**
 * Per-channel multipliers for one material at one opacity. Every field is 1 at `p = 1`, so multiplying
 * an existing default by it leaves the current appearance unchanged. Reused by the owner; never allocated per frame.
 */
internal class MaterialOpacityFrame {
    /** Requested and effective (floor / accessibility applied) opacity this frame was resolved for. */
    var requested = 1f
    var effective = 1f
    /** Tint, surface colour or plain fill. */
    var fill = 1f
    var luminosity = 1f
    /** Weight of the sampled (blurred / refracted) backdrop over the real content beneath. */
    var backdrop = 1f
    var noise = 1f
    var scatterStrength = 1f
    var scatterRadius = 1f
    var refraction = 1f
    var specular = 1f
    var chromatic = 1f
    /** Interpolation from neutral (0) to the configured saturation (1). */
    var saturation = 1f
    var highlight = 1f
    var innerShadow = 1f
    var shadow = 1f
    var edge = 1f
    var elevation = 1f
    /** Mica only: weight of the static wallpaper image over the opaque base. */
    var image = 1f

    val isDefault: Boolean get() = effective >= 1f

    fun setDefault() {
        requested = 1f; effective = 1f
        fill = 1f; luminosity = 1f; backdrop = 1f; noise = 1f; scatterStrength = 1f; scatterRadius = 1f
        refraction = 1f; specular = 1f; chromatic = 1f; saturation = 1f; highlight = 1f; innerShadow = 1f
        shadow = 1f; edge = 1f; elevation = 1f; image = 1f
    }
}

/** Pure mapping from material opacity to channel multipliers; no android.graphics. */
internal object MaterialOpacityPolicy {
    /** Fog density constant for the frosted haze curve. */
    const val HAZE_K = 2.4f
    /** Fluent keeps its full blur until opacity drops below this. */
    const val FLUENT_BLUR_GATE = 0.35f
    /** Below this effective backdrop weight a surface needs no backdrop sample at all. */
    const val SAMPLING_EPSILON = 1f / 255f

    fun linear(p: Float): Float = p
    fun pow(p: Float, gamma: Float): Float = if (p <= 0f) 0f else if (p >= 1f) 1f else p.pow(gamma)
    fun haze(p: Float, k: Float = HAZE_K): Float =
        if (p <= 0f) 0f else if (p >= 1f) 1f else ((1f - exp(-k * p)) / (1f - exp(-k))).coerceIn(0f, 1f)
    fun floorMix(p: Float, floor: Float): Float = floor + (1f - floor) * p
    fun gate(p: Float, start: Float, end: Float): Float {
        if (p <= start) return 0f
        if (p >= end) return 1f
        val t = (p - start) / (end - start)
        return t * t * (3f - 2f * t)
    }

    /** NaN keeps [current]; infinities and out-of-range values clamp. */
    fun sanitize(value: Float, current: Float): Float = if (value.isNaN()) current else value.coerceIn(0f, 1f)

    /** Static legibility floors for surfaces that carry text (§6). Mica is opaque at any opacity. */
    fun staticFloor(kind: MaterialKind): Float = when (kind) {
        MaterialKind.FROSTED -> 0.22f
        MaterialKind.LIQUID -> 0.12f
        MaterialKind.FLUENT -> 0.30f
        MaterialKind.MICA -> 0f
        MaterialKind.PRISM -> 0.20f
        MaterialKind.PLAIN -> 0.20f
    }

    /**
     * Effective opacity: reduced transparency forces the default (opaque fallback is handled by each material's
     * existing policy); text-carrying surfaces never go below their floor.
     */
    fun effective(kind: MaterialKind, requested: Float, carriesText: Boolean, reduceTransparency: Boolean): Float {
        if (reduceTransparency) return 1f
        val p = requested.coerceIn(0f, 1f)
        return if (carriesText) maxOf(p, staticFloor(kind)) else p
    }

    /** Writes the multipliers for [kind] at [requested] into [out]. */
    fun resolve(kind: MaterialKind, requested: Float, carriesText: Boolean, reduceTransparency: Boolean, out: MaterialOpacityFrame) {
        val p = effective(kind, requested, carriesText, reduceTransparency)
        if (p >= 1f) { out.setDefault(); out.requested = requested.coerceIn(0f, 1f); return }
        frame(kind, p, out)
        out.requested = requested.coerceIn(0f, 1f)
    }

    /** Channel multipliers at an already effective opacity [p]. */
    fun frame(kind: MaterialKind, p: Float, out: MaterialOpacityFrame) {
        out.setDefault()
        val v = p.coerceIn(0f, 1f)
        out.requested = v; out.effective = v
        if (v >= 1f) return
        when (kind) {
            MaterialKind.FROSTED -> {
                val h = haze(v)
                out.fill = h
                out.backdrop = pow(v, 0.7f)
                out.edge = 0.4f + 0.6f * h
                out.shadow = pow(v, 1.5f)
            }
            MaterialKind.LIQUID -> {
                out.fill = v
                out.backdrop = v
                out.scatterStrength = v * v
                out.scatterRadius = v
                out.refraction = floorMix(v, 0.5f)
                out.specular = floorMix(v, 0.6f)
                out.chromatic = floorMix(v, 0.5f)
                out.edge = floorMix(v, 0.6f)
                out.highlight = floorMix(v, 0.6f)
                out.shadow = pow(v, 1.5f)
            }
            MaterialKind.FLUENT -> {
                out.luminosity = v
                out.fill = pow(v, 1.2f)
                out.backdrop = gate(v, 0f, FLUENT_BLUR_GATE)
                out.noise = sqrt(v)
                out.elevation = floorMix(v, 0.5f)
                out.shadow = out.elevation
            }
            MaterialKind.MICA -> out.image = v
            MaterialKind.PRISM -> {
                out.fill = v
                out.backdrop = v
                out.saturation = v
                out.refraction = floorMix(v, 0.5f)
                out.highlight = floorMix(v, 0.6f)
                out.edge = out.highlight
                out.innerShadow = v
                out.shadow = pow(v, 1.5f)
            }
            MaterialKind.PLAIN -> {
                out.fill = v
                out.edge = floorMix(v, 0.4f)
                out.shadow = pow(v, 1.5f)
            }
        }
    }

    /** Whether a surface at this frame still needs a backdrop sample (§5 low-opacity sampling stop). */
    fun needsBackdrop(frame: MaterialOpacityFrame): Boolean = frame.backdrop > SAMPLING_EPSILON
}

/**
 * One engine Drawable's own opacity, multiplied with its role-wide opacity. Resolves only when the product changes,
 * so a draw costs one multiply and one compare. Pure: no android.graphics.
 */
internal class MaterialOpacityState(private val kind: MaterialKind) {
    var own = 1f
        private set
    var carriesText = true
        private set
    private val frame = MaterialOpacityFrame()
    private var resolvedFor = Float.NaN
    private var resolvedText = true

    /** Returns true when the stored value changed. */
    fun set(value: Float, carriesText: Boolean): Boolean {
        val next = MaterialOpacityPolicy.sanitize(value, own)
        if (next == own && carriesText == this.carriesText) return false
        own = next
        this.carriesText = carriesText
        return true
    }

    /** Frame for this draw, or null at full opacity (callers then keep their exact default path). */
    fun frame(roleOpacity: Float): MaterialOpacityFrame? {
        val combined = (roleOpacity * own).coerceIn(0f, 1f)
        if (combined >= 1f) return null
        if (combined != resolvedFor || carriesText != resolvedText) {
            MaterialOpacityPolicy.resolve(kind, combined, carriesText, reduceTransparency = false, out = frame)
            resolvedFor = combined
            resolvedText = carriesText
        }
        return if (frame.isDefault) null else frame
    }
}
