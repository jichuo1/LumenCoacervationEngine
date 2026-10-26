package com.lumen.coacervation.engine.fluent

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.animation.AnimationUtils
import com.lumen.coacervation.engine.host.LumenAcrylicBlend
import com.lumen.coacervation.engine.host.LumenAcrylicFallbackReason
import com.lumen.coacervation.engine.host.LumenFluentControlOptions
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.runtime.MaterialKind
import com.lumen.coacervation.engine.runtime.MaterialOpacityFrame
import com.lumen.coacervation.engine.runtime.MaterialOpacityPolicy
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fluent control background, layered as WinUI's AcrylicBrush:
 * opaque fallback base → blurred backdrop (draw-only session binding) → luminosity → colour tint → noise →
 * crossfade to fallback → subtle state fill → elevation stroke → keyboard focus rings.
 *
 * The draw path allocates nothing and builds no shader; gradients are rebuilt only when tokens change.
 * Animation work runs only while a transition is in flight.
 */
internal class FluentControlDrawable(private val density: Float, private val host: Host) : Drawable() {
    internal interface Host {
        /** Current backdrop binding, or null when acrylic is not sampling. */
        val backdrop: LumenSurfaceBinding?
        val keyboardFocusVisible: Boolean
        fun onInteractionChanged(pressed: Boolean)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The tile is drawn 1:1 in device pixels, so bitmap filtering would only blur the grain. */
    private val noisePaint = Paint()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val strokeRect = RectF()
    private val focusRect = RectF()
    private val clip = Path()
    private val strokeMatrix = Matrix()
    private var strokeShader: LinearGradient? = null
    private var blend: AcrylicBlendApi29? = null
    private var noise: Bitmap? = null

    private var options = LumenFluentControlOptions()
    private var dark = false
    private var recipe = FluentAcrylicPolicy.resolve(options.acrylic, false, 0)
    private var stroke = FluentElevationPolicy.stroke(dark = false, accent = false, enabled = true)
    private var radius = 0f
    private var alphaValue = 255
    /** Material opacity multipliers (MATERIAL_OPACITY.md §4.3); all 1 by default. */
    val material = MaterialOpacityFrame()

    private var enabled = true
    private var pressed = false
    private var hovered = false
    private var focused = false

    private var policy = LumenAcrylicFallbackReason.NONE
    private val availability = AcrylicAvailability()
    private val crossfade = FluentTransition(0f)
    private val overlay = FluentTransition(1f)
    private var overlayFrom = 0
    private var overlayTo = 0
    private var lastWeight = 0f
    var lastBackend = LumenSurfaceBackend.STATIC
        private set
    var draws = 0L
        private set
    private var released = false
    private var recheckPending = false
    private val recheck = Runnable { recheckPending = false; invalidateSelf() }

    val transitions: Long get() = crossfade.retargets + overlay.retargets
    val acrylicVisible: Boolean get() = lastWeight > 0f
    val blendMode: LumenAcrylicBlend get() = if (blend != null) LumenAcrylicBlend.EXACT else LumenAcrylicBlend.APPROXIMATE
    val noiseBytes: Int get() = if (noise != null) FluentNoiseTexture.BYTES else 0
    val fallbackReason: LumenAcrylicFallbackReason
        get() = if (policy == LumenAcrylicFallbackReason.NONE && !availability.available)
            LumenAcrylicFallbackReason.BACKDROP_UNAVAILABLE else policy

    fun configure(value: LumenFluentControlOptions, palette: LumenPalette) {
        if (released) return
        options = value
        dark = luminance(palette.background) < 0.5
        recipe = FluentAcrylicPolicy.resolve(value.acrylic, dark, palette.primary)
        radius = (value.cornerRadiusDp ?: FluentElevationPolicy.cornerDp(value.elevation.level)) * density
        val wantsNoise = value.acrylic.enabled && recipe.noiseAlpha > 0
        if (wantsNoise && noise == null) {
            val bitmap = FluentNoiseTexture.acquire()
            noise = bitmap
            noisePaint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        } else if (!wantsNoise && noise != null) {
            releaseNoise()
        }
        noisePaint.alpha = recipe.noiseAlpha
        blend = if (value.acrylic.exactBlend && Build.VERSION.SDK_INT >= 29) blend ?: AcrylicBlendApi29() else null
        strokePaint.strokeWidth = value.elevation.strokeWidthDp * density
        overlayTo = targetOverlay()
        overlayFrom = overlayTo
        overlay.snap(1f)
        rebuildStroke()
        onBoundsChange(bounds)
        invalidateSelf()
    }

    /** Called by the controller when power, transparency, focus or backdrop policy changes. */
    fun setPolicy(reason: LumenAcrylicFallbackReason) {
        if (released || reason == policy) return
        policy = reason
        if (reason != LumenAcrylicFallbackReason.NONE) {
            availability.reset()
            unscheduleSelf(recheck); recheckPending = false
        }
        retargetCrossfade(now())
        invalidateSelf()
    }

    /** Resolves the material opacity; returns true when the elevation multiplier changed. */
    /**
     * Platform shadow inputs, quantised to [SHADOW_STEPS]: changing `View.elevation` or the outline alpha makes the
     * RenderThread rebuild the shadow, so an opacity animation updates them a few dozen times, not every frame.
     */
    var shadowElevation = 1f
        private set
    var shadowOutline = 1f
        private set

    /** Resolves the material opacity; returns [ELEVATION_CHANGED] / [OUTLINE_CHANGED] flags for the quantised shadow. */
    fun setMaterialOpacity(requested: Float, carriesText: Boolean, reduceTransparency: Boolean): Int {
        if (released) return 0
        MaterialOpacityPolicy.resolve(MaterialKind.FLUENT, requested, carriesText, reduceTransparency, material)
        invalidateSelf()
        var changed = 0
        val elevation = quantise(material.elevation)
        if (elevation != shadowElevation) { shadowElevation = elevation; changed = changed or ELEVATION_CHANGED }
        val outline = quantise(material.backdrop)
        if (outline != shadowOutline) { shadowOutline = outline; changed = changed or OUTLINE_CHANGED }
        return changed
    }

    private fun quantise(value: Float): Float =
        if (value >= 1f) 1f else (value * SHADOW_STEPS).roundToInt() / SHADOW_STEPS.toFloat()

    fun release() {
        if (released) return
        released = true
        unscheduleSelf(recheck); recheckPending = false
        releaseNoise()
        blend = null
        strokeShader = null
        strokePaint.shader = null
    }

    override fun draw(canvas: Canvas) {
        if (released || bounds.isEmpty) return
        draws++
        val now = now()
        val binding = if (policy == LumenAcrylicFallbackReason.NONE) host.backdrop else null
        // Below the blur gate the backdrop (and the opaque base under it) gives way to the real content.
        val backdrop = material.backdrop
        val sampling = MaterialOpacityPolicy.needsBackdrop(material)
        val save = canvas.save()
        try {
            canvas.clipPath(clip)
            // WinUI composites the backdrop over an opaque fallback so the blur never mixes with transparency.
            fill.color = withAlpha(recipe.fallback, (alphaValue * backdrop).roundToInt())
            canvas.drawRect(rect, fill)
            if (binding != null) binding.setMaterialWeights(backdrop, 1f, 1f, 1f, material.blurRadius, material.radiusBackdrop)
            if (binding != null && !binding.isRecordingContent && sampling) {
                // The Drawable alpha (a motion fade) applies to the backdrop as well as to the acrylic layers.
                binding.draw(canvas, alphaValue / 255f)
                lastBackend = binding.lastBackend()
                if (availability.report(lastBackend != LumenSurfaceBackend.STATIC, now)) retargetCrossfade(now)
                // A static view may not redraw on its own; make sure a pending loss is confirmed.
                val pending = availability.pendingLossIn(now)
                if (pending >= 0L && !recheckPending) {
                    recheckPending = true
                    scheduleSelf(recheck, SystemClock.uptimeMillis() + pending + 1L)
                }
            } else if (binding == null) {
                lastBackend = LumenSurfaceBackend.STATIC
            }
            val weight = crossfade.value(now, eased = true)
            lastWeight = weight
            if (weight > 0f) drawAcrylic(canvas)
            if (weight < 1f) {
                // The fallback stands in for the backdrop, so it thins out with the backdrop weight.
                fill.color = withAlpha(recipe.fallback, ((1f - weight) * alphaValue * backdrop).roundToInt())
                canvas.drawRect(rect, fill)
            }
            if (options.stateLayerEnabled) {
                val color = overlayColor(now)
                if (alpha(color) > 0) {
                    fill.color = withAlpha(color, alpha(color) * alphaValue / 255)
                    canvas.drawRect(rect, fill)
                }
            }
        } finally {
            canvas.restoreToCount(save)
        }
        if (options.elevation.strokeEnabled) {
            // A solid stroke carries its token alpha in the colour; Paint.setAlpha would overwrite it with opaque.
            if (strokeShader == null) strokePaint.color = withAlpha(stroke.body, alpha(stroke.body) * alphaValue / 255)
            else strokePaint.alpha = alphaValue
            canvas.drawRoundRect(strokeRect, max(0f, radius - strokePaint.strokeWidth / 2f),
                max(0f, radius - strokePaint.strokeWidth / 2f), strokePaint)
        }
        if (options.focusVisualEnabled && focused && host.keyboardFocusVisible) drawFocus(canvas)
        if (crossfade.isRunning(now) || overlay.isRunning(now)) invalidateSelf()
    }

    private fun drawAcrylic(canvas: Canvas) {
        val exact = blend
        val luminosity = withAlpha(recipe.luminosity, (alpha(recipe.luminosity) * alphaValue / 255f * material.luminosity).roundToInt())
        val tint = withAlpha(recipe.tint, (alpha(recipe.tint) * alphaValue / 255f * material.fill).roundToInt())
        if (exact != null && Build.VERSION.SDK_INT >= 29) {
            exact.drawLuminosity(canvas, rect, luminosity)
            if (alpha(tint) > 0) exact.drawColor(canvas, rect, tint)
        } else {
            wash.color = withAlpha(luminosity, FluentAcrylicPolicy.approximateLuminosityAlpha(alpha(luminosity)))
            canvas.drawRect(rect, wash)
            if (alpha(tint) > 0) {
                wash.color = tint
                canvas.drawRect(rect, wash)
            }
        }
        if (noise != null) {
            noisePaint.alpha = (recipe.noiseAlpha * material.noise * alphaValue / 255f).roundToInt()
            if (noisePaint.alpha > 0) canvas.drawRect(rect, noisePaint)
        }
    }

    private fun drawFocus(canvas: Canvas) {
        val outer = FluentElevationPolicy.FOCUS_OUTER_DP * density
        val inner = FluentElevationPolicy.FOCUS_INNER_DP * density
        focusPaint.color = FluentElevationPolicy.focusOuter(dark)
        focusPaint.strokeWidth = outer
        focusRect.set(rect)
        focusRect.inset(outer / 2f, outer / 2f)
        canvas.drawRoundRect(focusRect, max(0f, radius - outer / 2f), max(0f, radius - outer / 2f), focusPaint)
        focusPaint.color = FluentElevationPolicy.focusInner(dark)
        focusPaint.strokeWidth = inner
        focusRect.set(rect)
        focusRect.inset(outer + inner / 2f, outer + inner / 2f)
        canvas.drawRoundRect(focusRect, max(0f, radius - outer - inner / 2f), max(0f, radius - outer - inner / 2f), focusPaint)
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val half = strokePaint.strokeWidth / 2f
        strokeRect.set(rect)
        strokeRect.inset(half, half)
        if (stroke.bandAtBottom) {
            strokeMatrix.setScale(1f, -1f)
            strokeMatrix.postTranslate(0f, rect.bottom)
        } else {
            strokeMatrix.setTranslate(0f, rect.top)
        }
        strokeShader?.setLocalMatrix(strokeMatrix)
    }

    private fun rebuildStroke() {
        stroke = FluentElevationPolicy.stroke(dark, options.elevation.accentStroke, enabled)
        if (stroke.band == stroke.body) {
            strokeShader = null
            strokePaint.shader = null
            strokePaint.color = stroke.body
        } else {
            // Absolute 0..3dp band from the edge, clamped to the body colour beyond it.
            val band = FluentElevationPolicy.STROKE_BAND_DP * density
            strokeShader = LinearGradient(0f, 0f, 0f, band, intArrayOf(stroke.band, stroke.band, stroke.body),
                floatArrayOf(0f, FluentElevationPolicy.STROKE_BAND_STOP, 1f), Shader.TileMode.CLAMP)
            strokePaint.shader = strokeShader
        }
    }

    override fun isStateful(): Boolean = true

    override fun hasFocusStateSpecified(): Boolean = options.focusVisualEnabled

    override fun onStateChange(state: IntArray): Boolean {
        var nextEnabled = false
        var nextPressed = false
        var nextHovered = false
        var nextFocused = false
        for (value in state) when (value) {
            android.R.attr.state_enabled -> nextEnabled = true
            android.R.attr.state_pressed -> nextPressed = true
            android.R.attr.state_hovered -> nextHovered = true
            android.R.attr.state_focused -> nextFocused = true
        }
        if (nextEnabled == enabled && nextPressed == pressed && nextHovered == hovered && nextFocused == focused) return false
        val enabledChanged = nextEnabled != enabled
        val pressedChanged = nextPressed != pressed
        enabled = nextEnabled; pressed = nextPressed; hovered = nextHovered; focused = nextFocused
        if (enabledChanged) {
            rebuildStroke()
            onBoundsChange(bounds)
        }
        val target = targetOverlay()
        if (target != overlayTo) {
            val now = now()
            overlayFrom = overlayColor(now)
            overlayTo = target
            overlay.snap(0f)
            overlay.retarget(1f, now, transitionMs(FluentElevationPolicy.STATE_TRANSITION_MS), eased = false)
        }
        if (pressedChanged || enabledChanged) host.onInteractionChanged(pressed && enabled)
        return true
    }

    private fun targetOverlay(): Int =
        if (!enabled) 0 else FluentElevationPolicy.subtleFill(dark, hovered, pressed)

    private fun overlayColor(now: Long): Int {
        val t = overlay.value(now, eased = false)
        if (t >= 1f) return overlayTo
        // A transparent end borrows the other end's RGB, so fading in or out never passes through black.
        val fromRgb = if (alpha(overlayFrom) == 0) overlayTo else overlayFrom
        val toRgb = if (alpha(overlayTo) == 0) overlayFrom else overlayTo
        return argb(lerp(alpha(overlayFrom), alpha(overlayTo), t), lerp(red(fromRgb), red(toRgb), t),
            lerp(green(fromRgb), green(toRgb), t), lerp(blue(fromRgb), blue(toRgb), t))
    }

    private fun lerp(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).roundToInt()

    private fun retargetCrossfade(now: Long) {
        val target = if (policy == LumenAcrylicFallbackReason.NONE && availability.available) 1f else 0f
        crossfade.retarget(target, now, transitionMs(FluentAcrylicPolicy.FALLBACK_TRANSITION_MS), eased = true)
    }

    private fun transitionMs(full: Long): Long =
        if (options.reduceMotion || !ValueAnimator.areAnimatorsEnabled()) 0L else full

    private fun now(): Long = AnimationUtils.currentAnimationTimeMillis()

    private fun releaseNoise() {
        if (noise == null) return
        noise = null
        noisePaint.shader = null
        FluentNoiseTexture.release()
    }

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) return
        outline.setRoundRect(bounds, radius)
        // The platform shadow is drawn under the whole outline: a see-through control must not reveal it.
        outline.alpha = alphaValue / 255f * shadowOutline
    }

    override fun setAlpha(alpha: Int) {
        val value = alpha.coerceIn(0, 255)
        if (value == alphaValue) return
        alphaValue = value
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        wash.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val ELEVATION_CHANGED = 1
        const val OUTLINE_CHANGED = 2
        /** 1/32 steps: below what a shadow's darkness change can show, and few rebuilds per animation. */
        const val SHADOW_STEPS = 32
    }
}
