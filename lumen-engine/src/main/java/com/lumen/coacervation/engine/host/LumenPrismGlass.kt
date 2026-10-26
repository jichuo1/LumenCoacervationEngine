package com.lumen.coacervation.engine.host

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.prism.PrismGlassDrawable
import com.lumen.coacervation.engine.prism.PrismPolicy
import com.lumen.coacervation.engine.runtime.MaterialOpacityPolicy
import java.lang.ref.WeakReference

/**
 * 棱镜玻璃（Prism）：给任意 View 装上液态玻璃背景——饱和度增强的模糊背景、圆角矩形边缘折射（可选色散）、
 * 表面色、边缘高光、内阴影、按压光晕与外阴影，每层都可在 [LumenPrismOptions] 里单独开关与调参。
 * 文字、子 View、点击与无障碍仍归宿主；[close] 恢复原背景。
 *
 * 背景来自宿主持有的 [LumenSurfaceSession] 对 [source] 的采样，[source] 不能包含 [view]（与
 * [LumenSurfaceSession.bind] 同一规则）。没有会话时显示不透明回退色。与高级材质（`SkinId.LIQUID`）
 * 互不依赖：本类不改变窗口材质，也不参与其健康确认。
 *
 * 平台分级：API 33+ 完整配方；API 31–32 没有折射（模糊与饱和度仍在）；API 27–30 走会话的软件模糊，
 * 没有饱和度与折射；外阴影需要 API 28；表面色、高光、内阴影与按压光各级一致。
 * 外阴影画在边界之外，祖先需要 `clipChildren = false`。
 */
@MainThread
public class LumenPrismGlass @JvmOverloads constructor(
    view: View,
    palette: LumenPalette,
    options: LumenPrismOptions = LumenPrismOptions(),
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
    private var materialOpacity = 1f
    /** The host has set a material opacity: a binding created later still pre-compiles its blur levels. */
    private var materialUsed = false
    private var legibility = LumenMaterialLegibility.AUTO
    private var closed = false
    public val isClosed: Boolean get() = closed

    private val original: Drawable? = view.background
    /** Installed directly as the background (no wrapper), so its invalidations always reach the View. */
    private val drawable = PrismGlassDrawable(density, object : PrismGlassDrawable.Host {
        override val backdrop: LumenSurfaceBinding? get() = binding
    })

    private val focus = ViewTreeObserver.OnWindowFocusChangeListener { refreshPolicy() }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { observe(v); refreshPolicy() }
        override fun onViewDetachedFromWindow(v: View) { unobserve() }
    }

    init {
        checkMain()
        require(source !== view) { "The prism source must not be the glass view itself" }
        drawable.configure(options, palette)
        replaceBackground(view, drawable)
        view.addOnAttachStateChangeListener(attach)
        if (view.isAttachedToWindow) observe(view)
        refreshPolicy()
    }

    public fun update(value: LumenPrismOptions) {
        if (closed) return
        checkMain()
        options = value
        bindRejected = false
        // One session call: the GPU glass is rebuilt at most once per update.
        binding?.updatePrism(PrismPolicy.recipe(value), surfaceOptions())
        drawable.configure(value, palette)
        resolveMaterial()
        refreshPolicy()
    }

    override fun setMaterialOpacity(value: Float) { setMaterialOpacity(value, legibility) }

    /**
     * As [setMaterialOpacity]; [legibility] declares whether the glass carries text (default: it does).
     * Tint, blur, inner and outer shadow and the highlight follow continuously; the GPU refraction and
     * saturation stay as configured (they are part of the platform effect chain, rebuilt only by [update]).
     */
    public fun setMaterialOpacity(value: Float, legibility: LumenMaterialLegibility) {
        if (closed) return
        checkMain()
        materialUsed = true
        binding?.requestBlurWarm()
        val next = MaterialOpacityPolicy.sanitize(value, materialOpacity)
        if (next == materialOpacity && legibility == this.legibility) return
        materialOpacity = next
        this.legibility = legibility
        resolveMaterial()
    }

    override fun materialOpacity(): Float = materialOpacity

    override fun effectiveMaterialOpacity(): Float = drawable.material.effective

    private fun resolveMaterial() {
        drawable.setMaterialOpacity(materialOpacity, legibility.carriesText(null), options.reduceTransparency)
    }

    /** Live theme switch: the tint and fallback defaults follow `palette.background` luminance. */
    public fun updatePalette(value: LumenPalette) {
        if (closed) return
        checkMain()
        palette = value
        drawable.configure(options, value)
    }

    /** Forward when the glass moves without a layout pass (animation, manual scroll), as for surface bindings. */
    public fun notifyPositionChanged() {
        if (closed) return
        checkMain()
        binding?.notifyPositionChanged()
    }

    public fun diagnostics(): LumenPrismDiagnostics {
        if (!closed) checkMain()
        return LumenPrismDiagnostics(
            closed = closed,
            glassVisible = !closed && drawable.glassVisible,
            fallbackReason = when {
                closed -> LumenPrismFallbackReason.CLOSED
                bindRejected && drawable.fallbackReason == LumenPrismFallbackReason.NO_BACKDROP -> LumenPrismFallbackReason.BUDGET_EXCEEDED
                else -> drawable.fallbackReason
            },
            backdropBackend = drawable.lastBackend,
            lensActive = !closed && binding?.prismLensActive() == true,
            dark = drawable.dark,
            draws = drawable.draws
        )
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
        }
        drawable.release()
    }

    private fun refreshPolicy() {
        if (closed) return
        val view = host.get() ?: return
        val base = PrismPolicy.policyFallback(options, lowRamDevice, power?.isPowerSaveMode == true)
        if (PrismPolicy.releasesBackdrop(base)) releaseBinding() else ensureBinding(view)
        val reason = if (base == LumenPrismFallbackReason.NONE && binding == null) LumenPrismFallbackReason.NO_BACKDROP else base
        drawable.setPolicy(reason)
    }

    private fun ensureBinding(view: View) {
        if (binding?.isBound == true) return
        binding = null
        val owner = session ?: return
        val content = sourceRef?.get() ?: return
        if (owner.isClosed || bindRejected) return
        try {
            binding = owner.bindViewDrawOnly(view, surfaceOptions(), content, preserveBackdropColors = true,
                prism = PrismPolicy.recipe(options)).takeIf { it.isBound }
            if (materialUsed) binding?.requestBlurWarm()
        } catch (_: IllegalArgumentException) {
            // The host's session surface budget is full; the glass falls back on its own, the page is unaffected.
            bindRejected = true
        }
    }

    private fun releaseBinding() {
        binding?.close()
        binding = null
    }

    private fun surfaceOptions(): LumenSurfaceOptions {
        val blur = if (options.blur.enabled) options.blur.radiusDp else 0f
        return LumenSurfaceOptions(material = LumenSurfaceMaterial.FROSTED, role = SurfaceRole.FLOATING,
            radiusDp = PrismPolicy.cornerRadiusDp(options), tintEnabled = false, edgeEnabled = false,
            // refractionStrength 0: the session's budget padding then matches the prism node (blur only).
            sampling = LumenSurfaceSampling(blurEnabled = blur > 0f, blurRadiusDp = blur, refractionEnabled = false,
                refractionStrength = 0f))
    }

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

    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper()) { "Prism glass APIs require the main thread" }
}
