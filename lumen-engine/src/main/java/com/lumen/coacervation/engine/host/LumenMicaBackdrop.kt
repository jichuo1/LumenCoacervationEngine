package com.lumen.coacervation.engine.host

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.lumen.coacervation.engine.fluent.MicaBackdropDrawable
import com.lumen.coacervation.engine.fluent.MicaPolicy
import com.lumen.coacervation.engine.fluent.luminance
import com.lumen.coacervation.engine.fluent.opaque
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureHub
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureListener
import java.lang.ref.WeakReference

/**
 * Optional Mica foundation for a host-owned window root. Install once per window; child surfaces use
 * [layerBackground] instead of another Mica backdrop. The host supplies a prepared wallpaper/background
 * snapshot, palette and accessibility choices. No live content is captured and no persisted skin is changed.
 * Close on destruction; late calls after close are harmless. Root padding is preserved.
 */
@MainThread
public class LumenMicaBackdrop @JvmOverloads constructor(
    view: View,
    palette: LumenPalette,
    options: LumenMicaOptions = LumenMicaOptions(),
    image: LumenMicaImage? = null
) : AutoCloseable {
    private val host = WeakReference(view)
    private val context = view.context.applicationContext
    private val density = view.resources.displayMetrics.density
    private val power = context.getSystemService(PowerManager::class.java)
    private val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    private val original = view.background
    private val drawable = MicaBackdropDrawable()
    private var palette = palette
    private var options = options
    private var image = image
    private var memoryReleased = false
    private var observer: ViewTreeObserver? = null
    private var receiverRegistered = false
    private var reason = LumenMicaFallbackReason.NO_BACKGROUND
    private var closed = false
    public val isClosed: Boolean get() = closed

    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { refreshPolicy() }
    private val powerChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (!closed) refreshPolicy() }
    }
    private val memory = LumenMemoryPressureListener {
        if (!closed) {
            this@LumenMicaBackdrop.image = null
            memoryReleased = true
            drawable.releaseImage()
            refreshPolicy()
        }
    }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { observe(v); refreshPolicy() }
        override fun onViewDetachedFromWindow(v: View) { unobserve() }
    }

    init {
        checkMain()
        require(view.background !is MicaBackdropDrawable) { "Close the existing Mica backdrop before replacing it" }
        drawable.configure(options, palette, image)
        replaceBackground(view, drawable)
        view.addOnAttachStateChangeListener(attach)
        LumenMemoryPressureHub.addListener(memory)
        if (view.isAttachedToWindow) observe(view)
        refreshPolicy()
    }

    public fun update(value: LumenMicaOptions) {
        if (closed) return
        checkMain()
        drawable.configure(value, palette, image)
        options = value
        refreshPolicy()
    }

    public fun updatePalette(value: LumenPalette) {
        if (closed) return
        checkMain()
        drawable.configure(options, value, image)
        palette = value
        refreshPolicy()
    }

    /** Apply only current background results on the main thread; null selects the solid fallback. */
    public fun setImage(value: LumenMicaImage?) {
        if (closed) return
        checkMain()
        drawable.configure(options, palette, value)
        image = value
        memoryReleased = false
        refreshPolicy()
    }

    /** Re-read focus and power policy, e.g. after host settings change. No polling is used. */
    public fun refreshPolicy() {
        if (closed) return
        checkMain()
        val view = host.get() ?: return
        reason = MicaPolicy.fallback(options, lowRam, power?.isPowerSaveMode == true,
            view.isAttachedToWindow && !view.hasWindowFocus(), image != null, memoryReleased)
        if (reason == LumenMicaFallbackReason.NONE && !drawable.hasMaterial)
            reason = LumenMicaFallbackReason.RENDER_UNAVAILABLE
        drawable.setPolicy(reason)
    }

    /**
     * A low-opacity Fluent fill with an optional 1dp card contour; no additional wallpaper sampling.
     * Recreate and assign after changing kind, theme or accessibility settings. The host owns the Drawable.
     */
    public fun layerBackground(layer: LumenMicaLayer = LumenMicaLayer.CONTENT, radiusDp: Float = 8f): Drawable {
        checkMain()
        require(radiusDp.isFinite() && radiusDp in 0f..128f)
        val dark = luminance(palette.background) < 0.5
        val solid = options.highContrast || options.reduceTransparency
        val color = if (solid) opaque(palette.background) else MicaPolicy.layerColor(layer, options.kind, dark)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
            if (layer == LumenMicaLayer.CARD && !solid) setStroke(maxOf(1, density.toInt()),
                if (dark) 0x12FFFFFF else 0x0F000000)
        }
    }

    public fun diagnostics(): LumenMicaDiagnostics {
        if (!closed) checkMain()
        return LumenMicaDiagnostics(closed, options.kind, if (closed) LumenMicaFallbackReason.CLOSED else reason,
            !closed && drawable.micaVisible, drawable.snapshotBytes, drawable.draws, drawable.transitions)
    }

    override fun close() {
        if (closed) return
        checkMain()
        closed = true
        unobserve()
        LumenMemoryPressureHub.removeListener(memory)
        host.get()?.let { view ->
            view.removeOnAttachStateChangeListener(attach)
            if (view.background === drawable) replaceBackground(view, original)
        }
        image = null
        drawable.release()
    }

    private fun observe(view: View) {
        val current = view.viewTreeObserver
        if (observer === current) return
        unobserve()
        observer = current
        current.addOnWindowFocusChangeListener(focus)
        ContextCompat.registerReceiver(context, powerChanged, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unobserve() {
        observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
        observer = null
        if (receiverRegistered) {
            context.unregisterReceiver(powerChanged)
            receiverRegistered = false
        }
    }

    private fun replaceBackground(view: View, background: Drawable?) {
        val left = view.paddingLeft; val top = view.paddingTop; val right = view.paddingRight; val bottom = view.paddingBottom
        view.background = background
        view.setPadding(left, top, right, bottom)
    }

    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper()) { "Mica backdrop APIs require the main thread" }
}
