package com.lumen.coacervation.engine.host

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.fluent.FluentAcrylicPolicy
import com.lumen.coacervation.engine.fluent.FluentControlDrawable
import com.lumen.coacervation.engine.fluent.FluentElevationPolicy
import com.lumen.coacervation.engine.fluent.luminance
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.runtime.MaterialOpacityPolicy
import java.lang.ref.WeakReference

/**
 * Fluent acrylic and elevation for one View: installs a background, drives the platform elevation shadow,
 * and restores whatever it still owns on [close]. Text, children, clicks and accessibility stay host-owned.
 *
 * The blurred backdrop comes from a host-owned [LumenSurfaceSession] sampling [source], which must not contain
 * [view] (the same rule as [LumenSurfaceSession.bind]). Without a session, the control shows Fluent's solid
 * fallback colour, exactly as WinUI does wherever acrylic is unavailable.
 */
@MainThread
public class LumenFluentControl @JvmOverloads constructor(
    view: View,
    palette: LumenPalette,
    options: LumenFluentControlOptions = LumenFluentControlOptions(),
    private val session: LumenSurfaceSession? = null,
    source: View? = null
) : AutoCloseable, LumenMaterialOpacityTarget {
    private val host = WeakReference(view)
    private val sourceRef: WeakReference<View>? = source?.let(::WeakReference)
    private val density = view.resources.displayMetrics.density
    private val lowRamDevice = (view.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    private val power: PowerManager? = view.context.applicationContext.getSystemService(PowerManager::class.java)
    private var palette = palette
    private var options = options
    private var binding: LumenSurfaceBinding? = null
    private var bindRejected = false
    private var observer: ViewTreeObserver? = null
    private var pressed = false
    private var materialOpacity = 1f
    private var legibility = LumenMaterialLegibility.AUTO
    private var closed = false
    public val isClosed: Boolean get() = closed

    private val original: Drawable? = view.background
    private val originalElevation = view.elevation
    private var ownedElevation = Float.NaN
    private var originalSpotShadow = 0
    private var originalAmbientShadow = 0
    private var ownedShadowColor: Int? = null

    private val drawable = FluentControlDrawable(density, object : FluentControlDrawable.Host {
        override val backdrop: LumenSurfaceBinding? get() = binding
        override val keyboardFocusVisible: Boolean get() = host.get()?.isInTouchMode == false
        override fun onInteractionChanged(pressed: Boolean) {
            this@LumenFluentControl.pressed = pressed
            applyElevation()
        }
    })

    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { refreshPolicy() }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { observe(v); refreshPolicy() }
        override fun onViewDetachedFromWindow(v: View) { unobserve() }
    }

    init {
        checkMain()
        require(source !== view) { "The acrylic source must not be the control itself" }
        drawable.configure(options, palette)
        replaceBackground(view, drawable)
        view.addOnAttachStateChangeListener(attach)
        if (view.isAttachedToWindow) observe(view)
        applyShadowColor()
        applyElevation()
        refreshPolicy()
    }

    public fun update(value: LumenFluentControlOptions) {
        if (closed) return
        checkMain()
        val samplingChanged = value.acrylic.blurRadiusDp != options.acrylic.blurRadiusDp ||
            cornerDp(value) != cornerDp(options)
        options = value
        bindRejected = false
        if (samplingChanged) binding?.update(surfaceOptions())
        drawable.configure(value, palette)
        resolveMaterial()
        applyShadowColor()
        applyElevation()
        refreshPolicy()
    }

    override fun setMaterialOpacity(value: Float) { setMaterialOpacity(value, legibility) }

    /** As [setMaterialOpacity]; [legibility] declares whether the control carries text (default: it does). */
    public fun setMaterialOpacity(value: Float, legibility: LumenMaterialLegibility) {
        if (closed) return
        checkMain()
        val next = MaterialOpacityPolicy.sanitize(value, materialOpacity)
        if (next == materialOpacity && legibility == this.legibility) return
        materialOpacity = next
        this.legibility = legibility
        if (resolveMaterial()) applyElevation()
    }

    override fun materialOpacity(): Float = materialOpacity

    override fun effectiveMaterialOpacity(): Float = drawable.material.effective

    private fun resolveMaterial(): Boolean =
        drawable.setMaterialOpacity(materialOpacity, legibility.carriesText(null), options.reduceTransparency)

    public fun updatePalette(value: LumenPalette) {
        if (closed) return
        checkMain()
        palette = value
        drawable.configure(options, value)
        applyShadowColor()
    }

    /** Forward when the control moves without a layout pass (animation, manual scroll), as for surface bindings. */
    public fun notifyPositionChanged() {
        if (closed) return
        checkMain()
        binding?.notifyPositionChanged()
    }

    public fun diagnostics(): LumenFluentDiagnostics {
        if (!closed) checkMain()
        return LumenFluentDiagnostics(closed, !closed && drawable.acrylicVisible,
            if (closed) LumenAcrylicFallbackReason.CLOSED else drawable.fallbackReason,
            drawable.lastBackend, drawable.blendMode, drawable.draws, drawable.transitions, drawable.noiseBytes)
    }

    override fun close() {
        if (closed) return
        checkMain()
        closed = true
        binding?.close()
        binding = null
        unobserve()
        host.get()?.let { view ->
            view.removeOnAttachStateChangeListener(attach)
            if (view.background === drawable) replaceBackground(view, original)
            if (!ownedElevation.isNaN() && view.elevation == ownedElevation) view.elevation = originalElevation
            restoreShadowColor(view)
        }
        ownedElevation = Float.NaN
        drawable.release()
    }

    private fun refreshPolicy() {
        if (closed) return
        val view = host.get() ?: return
        val acrylic = options.acrylic
        val inactive = acrylic.fallbackWhenWindowInactive && view.isAttachedToWindow && !view.hasWindowFocus()
        val powerSave = acrylic.fallbackOnPowerSave && power?.isPowerSaveMode == true
        val base = FluentAcrylicPolicy.policyFallback(acrylic.enabled, options.reduceTransparency,
            acrylic.fallbackOnLowRamDevice && lowRamDevice, powerSave, inactive, hasBackdrop = true)
        if (FluentAcrylicPolicy.releasesBackdrop(base)) releaseBinding() else ensureBinding(view)
        val reason = if (base == LumenAcrylicFallbackReason.NONE && binding == null) LumenAcrylicFallbackReason.NO_BACKDROP else base
        drawable.setPolicy(reason)
    }

    private fun ensureBinding(view: View) {
        if (binding?.isBound == true) return
        binding = null
        val owner = session ?: return
        val content = sourceRef?.get() ?: return
        if (owner.isClosed || bindRejected) return
        try {
            binding = owner.bindViewDrawOnly(view, surfaceOptions(), content, preserveBackdropColors = true).takeIf { it.isBound }
        } catch (_: IllegalArgumentException) {
            // The host's session surface budget is full; acrylic falls back on its own, the page is unaffected.
            bindRejected = true
        }
    }

    private fun releaseBinding() {
        binding?.close()
        binding = null
    }

    private fun surfaceOptions(): LumenSurfaceOptions {
        val blur = options.acrylic.blurRadiusDp
        return LumenSurfaceOptions(material = LumenSurfaceMaterial.FROSTED, role = SurfaceRole.FLOATING,
            radiusDp = cornerDp(options), tintEnabled = false, edgeEnabled = false,
            sampling = LumenSurfaceSampling(blurEnabled = blur > 0f, blurRadiusDp = blur, refractionEnabled = false))
    }

    private fun cornerDp(value: LumenFluentControlOptions): Float =
        value.cornerRadiusDp ?: FluentElevationPolicy.cornerDp(value.elevation.level)

    private fun applyElevation() {
        if (closed) return
        val view = host.get() ?: return
        val owned = ownedElevation
        // The host changed elevation after us: it owns the shadow now.
        if (!owned.isNaN() && view.elevation != owned) return
        val elevation = options.elevation
        if (!elevation.shadowEnabled) {
            if (!owned.isNaN()) view.elevation = originalElevation
            ownedElevation = Float.NaN
            return
        }
        val value = FluentElevationPolicy.fluentValue(elevation.level, pressed, elevation.pressedLowersElevation)
        // A thinner material sits lower: the platform shadow follows the elevation multiplier.
        val px = FluentElevationPolicy.androidElevationDp(value) * density * drawable.material.elevation
        view.elevation = px
        ownedElevation = px
    }

    private fun applyShadowColor() {
        if (Build.VERSION.SDK_INT < 28) return
        val view = host.get() ?: return
        val owned = ownedShadowColor
        if (owned != null && (view.outlineSpotShadowColor != owned || view.outlineAmbientShadowColor != owned)) return
        if (!options.elevation.shadowEnabled) {
            restoreShadowColor(view)
            return
        }
        if (owned == null) {
            originalSpotShadow = view.outlineSpotShadowColor
            originalAmbientShadow = view.outlineAmbientShadowColor
        }
        val color = FluentElevationPolicy.shadowColor(dark = isDark())
        view.outlineSpotShadowColor = color
        view.outlineAmbientShadowColor = color
        ownedShadowColor = color
    }

    private fun restoreShadowColor(view: View) {
        if (Build.VERSION.SDK_INT < 28) return
        val owned = ownedShadowColor ?: return
        if (view.outlineSpotShadowColor == owned) view.outlineSpotShadowColor = originalSpotShadow
        if (view.outlineAmbientShadowColor == owned) view.outlineAmbientShadowColor = originalAmbientShadow
        ownedShadowColor = null
    }

    private fun isDark(): Boolean = luminance(palette.background) < 0.5

    private fun observe(view: View) {
        val current = view.viewTreeObserver
        if (observer === current) return
        unobserve()
        observer = current
        current.addOnWindowFocusChangeListener(focus)
    }

    private fun unobserve() {
        observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
        observer = null
    }

    private fun replaceBackground(view: View, background: Drawable?) {
        val left = view.paddingLeft; val top = view.paddingTop; val right = view.paddingRight; val bottom = view.paddingBottom
        view.background = background
        view.setPadding(left, top, right, bottom)
    }

    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper()) { "Fluent control APIs require the main thread" }
}
