package com.lumen.coacervation.engine.host

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.geometry.ViewSamplingMatrix
import com.lumen.coacervation.engine.material.LiveBackdropSampler
import com.lumen.coacervation.engine.material.LumenSampleBudget
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureHub
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureListener
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** A reversible local session. It never changes a root background, hierarchy, Window or preferences. */
@MainThread
public class LumenSurfaceSession @JvmOverloads constructor(
    context: Context,
    palette: LumenPalette,
    options: LumenSurfaceSessionOptions = LumenSurfaceSessionOptions()
) : AutoCloseable {
    private val appContext = context.applicationContext ?: context
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private var palette = palette
    private var options = options
    private val entries = ArrayList<Entry>()
    private val groups = ArrayList<Group>()
    private val matrices = ViewSamplingMatrix()
    private var budget = LumenSampleBudget(options.maxSoftwareBytes.toLong())
    private var nextId = 1L
    private var closed = false
    private var paused = false
    private var memoryReleased = false
    private var paletteGeneration = 0L
    private var gpuDraws = 0L
    private var softwareDraws = 0L
    private var staticDraws = 0L
    private var firstDraws = 0L
    private var lastFailure = LumenSurfaceFailure.NONE
    private var retiredRecordings = 0L
    private var recordingDepth = 0
    private var listener: LumenSurfaceListener? = null
    private var trimRegistered = false
    private val memoryListener = LumenMemoryPressureListener { releaseGraphics() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val trim = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = releaseGraphics()
        override fun onTrimMemory(level: Int) {
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) releaseGraphics()
        }
    }

    init {
        checkMain()
        LumenMemoryPressureHub.addListener(memoryListener)
    }

    /** A direct View source must not contain the injected target. Custom sources must omit it. */
    @JvmOverloads
    public fun bind(view: View, surface: LumenSurfaceOptions = LumenSurfaceOptions(), source: View? = null): LumenSurfaceBinding =
        bindSource(view, surface, source?.let(::ViewContentSource))

    public fun bindSource(view: View, surface: LumenSurfaceOptions, source: LumenContentSource?): LumenSurfaceBinding {
        if (closed) return LumenSurfaceBinding(null, -1L)
        checkMain()
        entries.firstOrNull { it.host.get() === view }?.let { remove(it) }
        require(entries.size < options.maxSurfaces) { "Surface count exceeds session budget" }
        val group = source?.let { supplied ->
            groups.firstOrNull { it.source === supplied || it.source.coordinateView === supplied.coordinateView &&
                it.source is ViewContentSource && supplied is ViewContentSource }
                ?: Group(supplied).also(groups::add)
        }
        val entry = Entry(nextId++, view, surface, group)
        entries.add(entry)
        registerTrim()
        view.addOnAttachStateChangeListener(entry)
        entry.install()
        return LumenSurfaceBinding(this, entry.id)
    }

    public fun setListener(value: LumenSurfaceListener?) {
        if (closed) return
        checkMain(); listener = value
    }

    public fun updatePalette(value: LumenPalette) {
        if (closed) return
        checkMain()
        if (palette == value) return
        palette = value; paletteGeneration++
        entries.forEach { it.rebuild() }
    }

    /** Resource-budget changes release prior buffers before installing the new limits. */
    public fun updateOptions(value: LumenSurfaceSessionOptions) {
        if (closed) return
        checkMain()
        if (options == value) return
        require(value.maxSurfaces >= entries.size) { "Detach surfaces before lowering their count limit" }
        unregisterTrim(); options = value
        groups.forEach { it.release() }
        budget = LumenSampleBudget(value.maxSoftwareBytes.toLong())
        groups.forEach { it.replaceSampler() }
        entries.forEach { it.rebuild(); it.install() }
        registerTrim()
    }

    public fun notifyContentChanged() {
        if (closed) return
        checkMain(); memoryReleased = false
        groups.forEach { it.dirty = true; it.sampler.invalidate() }
        entries.forEach { it.host.get()?.invalidate() }
    }

    /** Recomputes full geometry on the next draw; no Activity or global touch dispatch is required. */
    public fun notifyPositionChanged() = notifyContentChanged()

    public fun pause() {
        if (closed || paused) return
        checkMain(); paused = true
        groups.forEach { it.sampler.suspend(); it.releaseGpu() }
        entries.forEach { it.releaseGpu(); it.host.get()?.invalidate() }
    }

    public fun resume() {
        if (closed) return
        checkMain(); paused = false; memoryReleased = false
        groups.forEach { it.sampler.resume(); it.dirty = true }
        entries.forEach { it.host.get()?.invalidate() }
    }

    /** Drop graphics only. Explicit resume/content change may restore them; failures stay sticky. */
    public fun releaseGraphics() {
        if (closed) return
        checkMain(); memoryReleased = true
        groups.forEach { it.release() }
        entries.forEach { it.releaseGpu(); it.host.get()?.invalidate() }
    }

    public fun diagnostics(): LumenSurfaceDiagnostics {
        if (!closed) checkMain()
        return LumenSurfaceDiagnostics(closed, paused, entries.size, gpuDraws, softwareDraws, staticDraws,
            retiredRecordings + groups.sumOf { it.recordings + it.retiredSoftware + it.sampler.recordings }, firstDraws, lastFailure, paletteGeneration)
    }

    internal fun state(id: Long): LumenSurfaceState? {
        if (closed) return null
        checkMain()
        val entry = entries.firstOrNull { it.id == id } ?: return null
        val effective = if (entry.stateBackend == LumenSurfaceBackend.STATIC) LumenSurfaceMaterial.STATIC else
            if (entry.stateBackend == LumenSurfaceBackend.GPU && Build.VERSION.SDK_INT >= 33 &&
                entry.config.material == LumenSurfaceMaterial.LIQUID && entry.config.sampling.refractionEnabled &&
                entry.config.sampling.refractionStrength > 0f) LumenSurfaceMaterial.LIQUID else LumenSurfaceMaterial.FROSTED
        return LumenSurfaceState(id, entry.config.material, effective, entry.stateBackend, entry.stateFailure, entry.first, paletteGeneration)
    }

    private fun recordSource(source: LumenContentSource, canvas: Canvas) {
        recordingDepth++
        try { source.drawContent(canvas) } finally { recordingDepth-- }
    }

    private fun contentPixels(): Long {
        var total = 0L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) for (i in 0 until groups.size) total += groups[i].capture?.pixels ?: 0L
        return total
    }
    private fun surfacePixels(): Long {
        var total = 0L
        for (i in 0 until entries.size) total += entries[i].gpuPixels
        return total
    }

    internal fun update(id: Long, value: LumenSurfaceOptions) {
        if (closed) return
        checkMain()
        entries.firstOrNull { it.id == id }?.let { entry ->
            if (entry.config != value) { entry.config = value; entry.rebuild(); entry.install() }
        }
    }

    internal fun detach(id: Long) {
        if (closed) return
        checkMain(); entries.firstOrNull { it.id == id }?.let(::remove)
    }

    private fun remove(entry: Entry) {
        if (entry.detached) return
        entry.detached = true
        entry.host.get()?.let { view ->
            view.removeOnAttachStateChangeListener(entry)
            if (options.restoreBackgroundOnDetach && view.background === entry.drawable) replaceBackground(view, entry.original)
            entry.group?.sampler?.unregister(view)
        }
        main.removeCallbacks(entry.dispatch)
        entry.releaseGpu(); entry.lenses = null; entries.remove(entry)
        if (entries.isEmpty()) unregisterTrim()
        entry.group?.let { group ->
            if (entries.none { it.group === group }) {
                retiredRecordings += group.recordings + group.retiredSoftware + group.sampler.recordings
                group.close(); groups.remove(group)
            }
        }
    }

    private fun replaceBackground(view: View, background: Drawable?) {
        val left = view.paddingLeft; val top = view.paddingTop; val right = view.paddingRight; val bottom = view.paddingBottom
        view.background = background
        view.setPadding(left, top, right, bottom)
    }

    private fun registerTrim() {
        if (options.registerMemoryCallbacks && entries.isNotEmpty() && !trimRegistered) {
            appContext.registerComponentCallbacks(trim); trimRegistered = true
        }
    }
    private fun unregisterTrim() {
        if (trimRegistered) { appContext.unregisterComponentCallbacks(trim); trimRegistered = false }
    }
    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper()) { "Local surface APIs require the main thread" }

    override fun close() {
        if (closed) return
        checkMain()
        while (entries.isNotEmpty()) remove(entries.last())
        closed = true; listener = null
        unregisterTrim(); LumenMemoryPressureHub.removeListener(memoryListener)
    }

    private inner class Group(val source: LumenContentSource) : ViewTreeObserver.OnPreDrawListener,
        ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnWindowFocusChangeListener, View.OnAttachStateChangeListener {
        private val recorder = object : LumenContentSource {
            override val coordinateView: View get() = source.coordinateView
            override fun drawContent(canvas: Canvas) = recordSource(source, canvas)
        }
        var sampler = makeSampler()
        var capture: SurfaceCaptureApi31? = null
        var dirty = true
        var failed = false
        var recordings = 0L
        var retiredSoftware = 0L
        private var observer: ViewTreeObserver? = null
        private var lastRecording = 0L
        private var autoSuspended = false
        init { source.coordinateView.addOnAttachStateChangeListener(this); observe() }
        private fun makeSampler() = LiveBackdropSampler(density, ViewSamplingMatrix(), recordContent = { recordSource(source, it) }, sharedBudget = budget).apply { bindSource(source.coordinateView) }
        fun replaceSampler() { retiredSoftware += sampler.recordings; sampler.close(); sampler = makeSampler(); dirty = true }
        private fun observe() {
            val current = source.coordinateView.viewTreeObserver
            if (observer === current) return
            unobserve(); observer = current
            current.addOnPreDrawListener(this); current.addOnScrollChangedListener(this); current.addOnGlobalLayoutListener(this); current.addOnWindowFocusChangeListener(this)
        }
        private fun unobserve() {
            observer?.takeIf { it.isAlive }?.let {
                it.removeOnPreDrawListener(this); it.removeOnScrollChangedListener(this); it.removeOnGlobalLayoutListener(this); it.removeOnWindowFocusChangeListener(this)
            }
            observer = null
        }
        override fun onWindowFocusChanged(hasFocus: Boolean) { dirty = true; entries.forEach { if (it.group === this) it.host.get()?.invalidate() } }
        override fun onScrollChanged() { dirty = true; sampler.invalidate() }
        override fun onGlobalLayout() { dirty = true; sampler.invalidate() }
        override fun onViewAttachedToWindow(v: View) { observe(); dirty = true; sampler.resume() }
        override fun onViewDetachedFromWindow(v: View) {
            // A source Window disappearing invalidates all its targets, including Story dialog overlays.
            for (i in entries.size - 1 downTo 0) if (entries[i].group === this) remove(entries[i])
        }
        override fun onPreDraw(): Boolean {
            if (closed) return true
            val content = source.coordinateView
            if (paused || memoryReleased || !options.enabled || !content.isAttachedToWindow ||
                options.autoPauseWhenHidden && !content.isShown || options.pauseWhenWindowUnfocused && !content.hasWindowFocus()) {
                if (!autoSuspended) { sampler.suspend(); releaseGpu(); for (i in 0 until entries.size) if (entries[i].group === this) entries[i].releaseGpu(); autoSuspended = true }
                return true
            }
            if (autoSuspended) { sampler.resume(); autoSuspended = false; dirty = true }
            if (failed) return true
            var interval = Long.MAX_VALUE
            var wanted = false
            for (i in 0 until entries.size) {
                val entry = entries[i]
                val host = entry.host.get() ?: continue
                if (entry.group !== this || !entry.config.enabled || !entry.config.sampling.enabled ||
                    entry.config.material == LumenSurfaceMaterial.STATIC || entry.config.opacity == 0f) continue
                if (failure(host, entry) != LumenSurfaceFailure.NONE) { sampler.unregister(host); entry.releaseGpu(); continue }
                if (LumenSurfacePolicy.gpuAllowed(Build.VERSION.SDK_INT, entry.config.sampling.backend, true, entry.gpuFailed)) {
                    wanted = true; interval = minOf(interval, entry.config.sampling.minIntervalMs)
                }
            }
            if (!wanted) { releaseGpu(); return true }
            if (!dirty && !content.isDirty) return true
            val now = System.nanoTime()
            if (lastRecording != 0L && (now - lastRecording) / 1_000_000 < interval) {
                // Reuse one trailing callback, rather than spin while the source is static.
                if (!trailingPosted) { trailingPosted = true; main.postDelayed(trailing, interval - (now - lastRecording) / 1_000_000) }
                return true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val current = capture ?: SurfaceCaptureApi31().also { capture = it }
                    val total = contentPixels()
                    val available = (options.maxGpuContentPixels - total + current.pixels).coerceAtLeast(0L).toInt()
                    if (current.record(recorder, options, available)) { dirty = false; lastRecording = now; recordings++ }
                    else lastFailure = LumenSurfaceFailure.BUDGET_EXCEEDED
                } catch (_: Throwable) { failed = true; releaseGpu(); lastFailure = LumenSurfaceFailure.GPU_FAILED }
            }
            return true
        }
        private var trailingPosted = false
        private val trailing = Runnable {
            trailingPosted = false
            if (!closed && !paused && !memoryReleased) {
                dirty = true
                for (entry in entries) if (entry.group === this) entry.host.get()?.invalidate()
            }
        }
        fun releaseGpu() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) capture?.close()
            capture = null; dirty = true
        }
        fun release() { releaseGpu(); sampler.suspend(); autoSuspended = true }
        fun close() {
            unobserve(); main.removeCallbacks(trailing)
            source.coordinateView.removeOnAttachStateChangeListener(this)
            releaseGpu(); sampler.close()
        }
    }

    private fun failure(host: View, entry: Entry): LumenSurfaceFailure {
        val source = entry.group?.source ?: return LumenSurfaceFailure.NO_SOURCE
        val view = source.coordinateView
        if (!host.isAttachedToWindow || !view.isAttachedToWindow) return LumenSurfaceFailure.DETACHED
        if (options.autoPauseWhenHidden && (!host.isShown || !view.isShown)) return LumenSurfaceFailure.HIDDEN
        if (host.windowToken == null || host.windowToken != view.windowToken) return LumenSurfaceFailure.DIFFERENT_WINDOW
        if (!source.excludesSurfaces) {
            for (i in 0 until entries.size) {
                val candidate = entries[i]
                if (!candidate.config.enabled) continue
                var parent: View? = candidate.host.get()
                while (parent != null) {
                    if (parent === view) return LumenSurfaceFailure.SELF_FEEDBACK
                    parent = parent.parent as? View
                }
            }
        }
        if (host.width <= 0 || host.height <= 0 || view.width <= 0 || view.height <= 0) return LumenSurfaceFailure.INVALID_GEOMETRY
        return LumenSurfaceFailure.NONE
    }

    private inner class Entry(val id: Long, view: View, var config: LumenSurfaceOptions, val group: Group?) : View.OnAttachStateChangeListener {
        val host = WeakReference(view)
        val original = view.background
        val drawable = SurfaceDrawable(this)
        var gpu: SurfaceCaptureApi31.Glass? = null
        var lenses: SurfaceCaptureApi31.LensCache? = null
        var detached = false
        val visibleRegion = Rect()
        var gpuPixels = 0L
        var gpuFailed = false
        var first = false
        var stateBackend = LumenSurfaceBackend.STATIC
        var stateFailure = LumenSurfaceFailure.NONE
        var eventFirst = false
        var pending = false
        val dispatch = Runnable {
            pending = false
            if (!closed && entries.contains(this)) {
                val initial = eventFirst; eventFirst = false
                try { listener?.onSurfaceState(id, stateBackend, stateFailure, initial) }
                catch (_: Throwable) { lastFailure = LumenSurfaceFailure.CALLBACK_FAILED }
            }
        }
        fun install() {
            val view = host.get() ?: return
            if (config.enabled && options.enabled) {
                val left = view.paddingLeft; val top = view.paddingTop; val right = view.paddingRight; val bottom = view.paddingBottom
                view.background = drawable; view.setPadding(left, top, right, bottom)
            } else if (view.background === drawable) {
                replaceBackground(view, original); group?.sampler?.unregister(view)
            }
            view.invalidate()
        }
        fun releaseGpu() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) gpu?.close()
            gpu = null; gpuPixels = 0L
        }
        fun rebuild() { releaseGpu(); drawable.configure(); group?.dirty = true; host.get()?.let { group?.sampler?.unregister(it); it.invalidate() } }
        fun report(backend: LumenSurfaceBackend, reason: LumenSurfaceFailure, visible: Boolean) {
            val initial = visible && !first && host.get()?.getGlobalVisibleRect(visibleRegion) == true
            if (initial) { first = true; firstDraws++; eventFirst = true }
            val changed = backend != stateBackend || reason != stateFailure || initial
            stateBackend = backend; stateFailure = reason; lastFailure = reason
            if (options.diagnosticsEnabled && changed) {
                if (!pending) { pending = true; main.post(dispatch) }
            }
        }
        override fun onViewAttachedToWindow(v: View) { group?.dirty = true }
        override fun onViewDetachedFromWindow(v: View) { if (!closed) remove(this) }
    }

    private inner class SurfaceDrawable(val entry: Entry) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rectangle = RectF()
        private val clip = Path()
        private val sampleMatrix = Matrix()
        private val gradientMatrix = Matrix()
        private var alphaValue = 255
        private var gradient: Shader? = null
        private var edgeGradient: Shader? = null
        private var staticGradient: Shader? = null
        private var colorAlpha = 255
        private var edgeAlpha = 70
        init { configure() }
        fun configure() {
            val c = entry.config
            val color = c.color ?: palette.surface
            colorAlpha = Color.alpha(color)
            fill.color = ColorUtils.setAlphaComponent(color, 255)
            edgeAlpha = (70 * c.edgeIntensity).roundToInt().coerceIn(0, 255)
            edge.color = ColorUtils.setAlphaComponent(palette.primary, 255)
            edge.strokeWidth = c.edgeWidthDp * density
            gradient = null; edgeGradient = null; staticGradient = null
            if (c.sampling.fadeEnabled) {
                fun mask(base: Int): Shader {
                    val colors = IntArray(33)
                    val stops = FloatArray(33)
                    for (i in colors.indices) {
                        val t = i / 32f; stops[i] = t
                        val weight = LumenSurfacePolicy.fade(t, c.sampling.fadeHold, c.sampling.fadeEnd,
                            c.sampling.fadeDirection == LumenSurfaceFadeDirection.BOTTOM_TO_TOP)
                        colors[i] = ColorUtils.setAlphaComponent(base, (Color.alpha(base) * weight).roundToInt())
                    }
                    return LinearGradient(0f, 0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP)
                }
                gradient = mask(ColorUtils.setAlphaComponent(color, (colorAlpha * c.tintOpacity).roundToInt()))
                staticGradient = mask(ColorUtils.setAlphaComponent(color, (colorAlpha * c.fallbackTintOpacity).roundToInt()))
                edgeGradient = mask(ColorUtils.setAlphaComponent(edge.color, edgeAlpha))
            }
            fill.shader = gradient; edge.shader = edgeGradient
            onBoundsChange(bounds)
        }
        override fun onBoundsChange(bounds: Rect) {
            rectangle.set(bounds)
            clip.reset(); clip.addRoundRect(rectangle, entry.config.radiusDp * density, entry.config.radiusDp * density, Path.Direction.CW)
            gradientMatrix.setScale(1f, bounds.height().toFloat()); gradientMatrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
            gradient?.setLocalMatrix(gradientMatrix); staticGradient?.setLocalMatrix(gradientMatrix); edgeGradient?.setLocalMatrix(gradientMatrix)
        }
        override fun draw(canvas: Canvas) {
            val host = entry.host.get() ?: return
            val c = entry.config
            if (recordingDepth > 0 || closed || entry.detached || !options.enabled || !c.enabled || c.opacity == 0f || host.alpha <= 0f || bounds.isEmpty) return
            var reason = when { !c.sampling.enabled || c.material == LumenSurfaceMaterial.STATIC || c.sampling.backend == LumenSurfaceBackend.STATIC -> LumenSurfaceFailure.NONE; paused -> LumenSurfaceFailure.PAUSED; memoryReleased -> LumenSurfaceFailure.MEMORY_PRESSURE; else -> failure(host, entry) }
            var backend = LumenSurfaceBackend.STATIC
            val alpha = c.opacity * alphaValue / 255f
            var visibleEffect = false
            val save = canvas.save()
            if (c.clipBackground) canvas.clipPath(clip)
            try {
                if (reason == LumenSurfaceFailure.NONE && c.sampling.enabled && c.material != LumenSurfaceMaterial.STATIC) {
                    val group = entry.group!!
                    if (group.failed) reason = LumenSurfaceFailure.GPU_FAILED
                    val content = group.source.coordinateView
                    if (options.pauseWhenWindowUnfocused && !content.hasWindowFocus()) reason = LumenSurfaceFailure.PAUSED
                    else {
                        if (canvas.isHardwareAccelerated && LumenSurfacePolicy.gpuAllowed(Build.VERSION.SDK_INT, c.sampling.backend, true, entry.gpuFailed || group.failed) &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && group.capture?.recorded == true) {
                            try {
                                if (matrices.sourceToTarget(content, host, sampleMatrix)) {
                                    val pad = ((maxOf(20f * c.sampling.refractionStrength, (if (c.sampling.blurEnabled) c.sampling.blurRadiusDp else 0f) * 2f) * density).roundToInt() + 2)
                                    val pixels = (bounds.width().toLong() + 2 * pad) * (bounds.height().toLong() + 2 * pad)
                                    val allocated = surfacePixels() - entry.gpuPixels
                                    if (pixels + allocated > options.maxGpuSurfacePixels || bounds.width() + 2 * pad > options.maxGpuDimension || bounds.height() + 2 * pad > options.maxGpuDimension) {
                                        reason = LumenSurfaceFailure.BUDGET_EXCEEDED
                                    } else {
                                    entry.gpuPixels = pixels
                                    val glass = entry.gpu ?: SurfaceCaptureApi31.Glass(c, density, ColorUtils.calculateLuminance(palette.background) < .5, entry.lenses ?: SurfaceCaptureApi31.LensCache().also { entry.lenses = it }).also { entry.gpu = it }
                                    group.capture!!.draw(glass, canvas, bounds, c.radiusDp * density, sampleMatrix, alpha)
                                    backend = LumenSurfaceBackend.GPU; gpuDraws++; group.sampler.unregister(host)
                                    }
                                } else reason = LumenSurfaceFailure.INVALID_GEOMETRY
                            } catch (_: Throwable) { entry.gpuFailed = true; entry.releaseGpu(); reason = LumenSurfaceFailure.GPU_FAILED }
                        }
                        if (backend != LumenSurfaceBackend.GPU && LumenSurfacePolicy.softwareAllowed(c.sampling, entry.gpuFailed || group.failed)) {
                            group.sampler.register(host, c.sampling)
                            if (group.sampler.draw(canvas, rectangle, c.radiusDp * density, host, (255 * alpha).roundToInt())) {
                                backend = LumenSurfaceBackend.SOFTWARE; softwareDraws++
                            } else if (group.sampler.hasFailed) reason = LumenSurfaceFailure.SOFTWARE_FAILED
                            else if (group.sampler.budgetRejected) reason = LumenSurfaceFailure.BUDGET_EXCEEDED
                            else if (reason == LumenSurfaceFailure.NONE) reason = LumenSurfaceFailure.FRAME_PENDING
                        }
                    }
                }
                if (backend == LumenSurfaceBackend.STATIC) staticDraws++
                val tint = if (!c.tintEnabled) 0f else if (backend == LumenSurfaceBackend.STATIC) c.fallbackTintOpacity else c.tintOpacity
                visibleEffect = backend != LumenSurfaceBackend.STATIC || c.tintEnabled && tint > 0f && colorAlpha > 0 ||
                    c.edgeEnabled && c.edgeWidthDp > 0f && edgeAlpha > 0
                // Static fallback deliberately remains readable; opacity affects background only.
                fill.shader = if (backend == LumenSurfaceBackend.STATIC) staticGradient else gradient
                fill.alpha = if (fill.shader != null) (if (c.tintEnabled) 255 * alpha else 0f).roundToInt() else
                    (colorAlpha * tint * alpha).roundToInt()
                canvas.drawRoundRect(rectangle, c.radiusDp * density, c.radiusDp * density, fill)
                if (c.edgeEnabled && c.edgeWidthDp > 0f) {
                    edge.alpha = ((if (edgeGradient != null) 255 else edgeAlpha) * alpha).roundToInt(); canvas.drawRoundRect(rectangle, c.radiusDp * density, c.radiusDp * density, edge)
                }
            } finally { canvas.restoreToCount(save) }
            entry.report(backend, reason, (canvas.isHardwareAccelerated || !host.isHardwareAccelerated) && visibleEffect && host.isShown && host.isAttachedToWindow && alpha > 0f)
        }
        override fun setAlpha(alpha: Int) { alphaValue = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun getAlpha(): Int = alphaValue
        override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; edge.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

/** A binding owns only the background it installed. Closing it cannot undo an unrelated host change. */
@MainThread
public class LumenSurfaceBinding internal constructor(session: LumenSurfaceSession?, public val id: Long) : AutoCloseable {
    private val session = WeakReference(session)
    public fun update(options: LumenSurfaceOptions) { session.get()?.update(id, options) }
    public fun diagnostics(): LumenSurfaceState? = session.get()?.state(id)
    override fun close() { session.get()?.detach(id) }
}
