package com.lumen.coacervation.engine.material

import androidx.annotation.ChecksSdkIntAtLeast
import android.os.Build
import android.util.Log
import com.lumen.coacervation.engine.glow.GlowBackdropTarget
import com.lumen.coacervation.engine.glow.GlowChromeGlassApi31
import android.graphics.Bitmap
import android.animation.ValueAnimator
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.lumen.coacervation.engine.background.AmbientBackdropScene
import com.lumen.coacervation.engine.background.LiquidBackgroundMode
import com.lumen.coacervation.engine.background.LiquidBackgroundConfig
import com.lumen.coacervation.engine.background.LiquidBackgroundStore
import com.lumen.coacervation.engine.glow.GlowEngine
import com.lumen.coacervation.engine.glow.GlowEngineCallbacks
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.SkinId
import com.lumen.coacervation.engine.liquid.LiquidMotionSurfaceFrameProvider
import com.lumen.coacervation.engine.geometry.SamplingMatrixMath
import com.lumen.coacervation.engine.geometry.ScrollSurfaceScope
import com.lumen.coacervation.engine.geometry.ViewSamplingMatrix
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.host.LumenMaterialLegibility
import com.lumen.coacervation.engine.runtime.MaterialKind
import com.lumen.coacervation.engine.runtime.MaterialOpacityPolicy
import com.lumen.coacervation.engine.runtime.MaterialOpacityState
import com.lumen.coacervation.engine.host.MaterialOpacityDrawable
import com.lumen.coacervation.engine.runtime.PreparedBackdropCache
import com.lumen.coacervation.engine.host.LumenBackdropReadiness
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/**
 * Static ambient frost for every surface, plus a live lens sample for floating chrome.
 * 柔光材质的 [GlowEngine] 实现；高级材质会话里它同时是休眠的回退引擎。
 *
 * 静态部分只采窗口底图（不含文字、其他窗口）；悬浮/顶栏表面另由 [LiveBackdropSampler] 对宿主用
 * [bindContentSource] 指定的内容层做低分辨率透镜采样，让从胶囊下方穿过的内容被模糊与折射。
 */
internal class FrostedMaterialRenderer(
    private val palette: LumenPalette,
    private val density: Float,
    private val effectTuning: LumenEffectTuning = LumenEffectTuning.DEFAULT,
    backgroundContext: Context? = null
) : GlowEngine {
    override val skin: SkinId get() = SkinId.MATERIAL_YOU

    private val dark = ColorUtils.calculateLuminance(palette.background) < .5
    private val backgroundContext = backgroundContext?.applicationContext
    private var root: View? = null
    /** 显示底图与可丢弃的模糊采样分开；内存降级不能抹掉用户背景。 */
    private var rootBackdrop: Bitmap? = null
    private var frame: ModernBackdropFrame? = null
    private var sampleShader: BitmapShader? = null
    internal var revealFraction = 0f
        private set
    private var revealAnimator: ValueAnimator? = null
    private val shaderMatrix = Matrix()
    private val samplingMatrices = ViewSamplingMatrix()
    private val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rootPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val movedTransform = FloatArray(9)
    private val sourceTransform = FloatArray(9)
    private class SurfaceTransform {
        val target = FloatArray(9)
        val source = FloatArray(9)
        /** Role of the surface drawn on this View; a material-opacity change redraws only its role. */
        var role: SurfaceRole? = null
    }
    private val surfaces = WeakHashMap<View, SurfaceTransform>()
    /** Material opacity per role (MATERIAL_OPACITY.md §4.1); each surface multiplies it with its own value. */
    private val materialOpacity = FloatArray(SurfaceRole.entries.size) { 1f }
    private class RefreshWindow(
        val observer: WeakReference<ViewTreeObserver>,
        val batch: FrostedRefreshBatch,
        val scroll: ViewTreeObserver.OnScrollChangedListener,
        val preDraw: ViewTreeObserver.OnPreDrawListener,
        val draw: ViewTreeObserver.OnDrawListener
    )
    private val windows = WeakHashMap<View, RefreshWindow>()
    private val lifecycle = FrostedMaterialLifecycle()
    private val generation: Long get() = lifecycle.generation
    private val closed: Boolean get() = lifecycle.isClosed
    private var width = 0
    private var height = 0
    private var failedWidth = 0
    private var failedHeight = 0
    private var workerStarted = false
    /** [config] 为 null 表示自动氛围背景：它只由配色、尺寸与密度决定，与背景配置无关。 */
    private data class BackdropKey(val palette: LumenPalette, val config: LiquidBackgroundConfig?,
        val width: Int, val height: Int, val density: Float)
    /** 只表示"自定义背景待就绪"：动效就绪判定与淡入跳过都依赖这一语义，自动背景不写这里。 */
    private var pendingBackdropKey: BackdropKey? = null
    /** 本次请求的自动背景缓存键；请求自定义背景时为 null。 */
    private var pendingAutoBackdropKey: BackdropKey? = null
    private var motionBackdropRequested = false
    private companion object {
        val preparedBackdrops = PreparedBackdropCache<BackdropKey, ModernBackdropFrame>(
            maxEntries = PreparedBackdropCache.ENGINE_ENTRIES)
    }
    private var work: Future<*>? = null
    private val worker by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "Lumen-SoftFrost").apply { isDaemon = true } } }
    private val handler = Handler(Looper.getMainLooper())
    // 与透镜采集共用矩阵工具：采集录制里每张卡片的静态磨砂映射都走这里，采集期间的祖先备忘才能生效。
    private val live = LiveBackdropSampler(density, samplingMatrices)
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> requestBackdrop(r - l, b - t) }

    /**
     * 悬浮栏宿主 → 内容节点玻璃（C 期，API 31+，见 [FrostedChromeGlassApi31]）。登记后该栏不再走
     * [LiveBackdropSampler] 的主线程录制 + 后台软件模糊。glass 为 GlowChromeGlassApi31，声明成
     * Any 以免 API 31 以下加载本类时解析它。
     */
    private class ChromeGlass(val target: GlowBackdropTarget, val glass: Any)
    private val chromeGlass = WeakHashMap<View, ChromeGlass>()
    private val chromeTransform = Matrix()
    private val chromeBounds = Rect()
    /** 节点路径任一次出错即永久停用，退回软件透镜。 */
    private var chromeGlassBroken = false

    /** 悬浮表面下方的内容层（表面的兄弟，不含表面自身）；未绑定则悬浮表面只有静态磨砂。 */
    override fun bindContentSource(view: View) {
        if (closed) return
        live.bindSource(view)
        surfaces.keys.forEach(View::invalidate)
    }

    /** 柔光不会失败：没有健康确认，也没有致命回退。 */
    override fun bindRoot(root: View, callbacks: GlowEngineCallbacks): Boolean = bindRoot(root)

    fun bindRoot(view: View): Boolean {
        if (closed || (root != null && root !== view)) return false
        if (root === view) return true
        root = view
        view.addOnLayoutChangeListener(layoutListener)
        view.background = object : Drawable(), LumenBackdropReadiness {
            override fun prepareForMotion() {
                motionBackdropRequested = true
                if (pendingBackdropKey == null) return
                revealAnimator?.cancel()
                revealFraction = 1f
                view.invalidate()
            }
            override val isReadyForMotion: Boolean
                get() = closed || pendingBackdropKey == null || frame != null || failedWidth > 0
            private var drawingAlpha = 255
            override fun draw(canvas: Canvas) {
                val current = rootBackdrop
                rootPaint.color = ColorUtils.setAlphaComponent(palette.background, drawingAlpha)
                canvas.drawRect(bounds, rootPaint)
                if (current != null) {
                    rootPaint.alpha = (drawingAlpha * revealFraction).toInt()
                    canvas.drawBitmap(current, null, bounds, rootPaint)
                }
            }
            override fun setAlpha(alpha: Int) { drawingAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
            override fun getAlpha() = drawingAlpha
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        requestBackdrop(view.width, view.height)
        return true
    }

    override fun surface(fallbackColor: Int, radiusDp: Float, role: SurfaceRole): Drawable =
        ModernSurfaceDrawable(this, fallbackColor, radiusDp * density, density,
            ModernMaterialPolicy.surface(role, dark, effectTuning), effectTuning.edgeHighlightWidth, role = role)

    override fun setMaterialOpacity(role: SurfaceRole, value: Float) {
        if (closed) return
        val index = role.ordinal
        val next = MaterialOpacityPolicy.sanitize(value, materialOpacity[index])
        if (next == materialOpacity[index]) return
        materialOpacity[index] = next
        materialChanged(1 shl index)
    }

    /** Every role at once ([values] by role ordinal): one pass over the surfaces instead of one per role. */
    internal fun setMaterialOpacities(values: FloatArray) {
        if (closed) return
        var changed = 0
        for (i in materialOpacity.indices) {
            val next = MaterialOpacityPolicy.sanitize(values.getOrElse(i) { 1f }, materialOpacity[i])
            if (next != materialOpacity[i]) { materialOpacity[i] = next; changed = changed or (1 shl i) }
        }
        if (changed != 0) materialChanged(changed)
    }

    private fun materialChanged(roles: Int) {
        onMaterialChanged()
        for ((view, transform) in surfaces) {
            val role = transform.role
            if (role == null || (roles shr role.ordinal) and 1 != 0) view.invalidate()
        }
    }

    internal fun materialOpacityOf(role: SurfaceRole): Float = materialOpacity[role.ordinal]

    // ---- Material opacity: real blur radius once the opacity is still (MATERIAL_OPACITY.md §13) ----

    /** False while any material opacity is moving: surfaces cross-fade the full-radius frost meanwhile. */
    internal var materialSettled = true
        private set
    /** One thinner frost per settle: several roles at different opacities must not keep evicting each other. */
    private var thinRequested = false
    private var thinLevel = -1
    private var thinBitmap: Bitmap? = null
    private var thinShader: BitmapShader? = null
    private var thinWork: Future<*>? = null
    private var thinToken = 0L
    /** Touched only on the frost worker. */
    @Volatile private var thinPixels: IntArray? = null
    @Volatile private var thinScratch: IntArray? = null
    /** Uptime of the last material change; the settle runnable is posted once and re-arms itself. */
    private var materialChangedAt = 0L
    private var settlePosted = false
    private val materialSettle: Runnable = object : Runnable {
        override fun run() {
            settlePosted = false
            if (closed || materialSettled) return
            val wait = materialChangedAt + MaterialOpacityPolicy.SETTLE_MILLIS - SystemClock.uptimeMillis()
            if (wait > 0L) { settlePosted = true; handler.postDelayed(this, wait); return }
            materialSettled = true
            for (view in surfaces.keys) view.invalidate()
        }
    }

    internal fun onMaterialChanged() {
        if (closed) return
        materialSettled = false
        thinRequested = false
        materialChangedAt = SystemClock.uptimeMillis()
        if (!settlePosted) { settlePosted = true; handler.postDelayed(materialSettle, MaterialOpacityPolicy.SETTLE_MILLIS) }
    }

    /**
     * The static frost re-blurred at [level], or null while it is not ready (the caller then cross-fades the full
     * frost). Requests it on the frost worker once per settle; at most one thinner frost (≤ 640 KiB) is kept.
     */
    internal fun thinFrost(level: Int): BitmapShader? {
        if (level == thinLevel) return thinShader
        if (!materialSettled || thinRequested || closed || !lifecycle.canWork) return null
        val current = frame ?: return null
        thinRequested = true
        thinWork?.cancel(true)
        val token = ++thinToken
        val source = current.original
        val fullRadius = ModernMaterialPolicy.blurRadius(source.width, width, density)
        val radius = (fullRadius * MaterialOpacityPolicy.levelFraction(level)).roundToInt().coerceIn(0, 32)
        val recipient = WeakReference(this)
        workerStarted = true
        thinWork = worker.submit {
            val result = runCatching {
                val w = source.width; val h = source.height
                // Worker-only buffers, reused across settles (the frost worker is single-threaded).
                val pixels = thinPixels?.takeIf { it.size == w * h } ?: IntArray(w * h).also { thinPixels = it }
                val scratch = thinScratch?.takeIf { it.size == w * h } ?: IntArray(w * h).also { thinScratch = it }
                source.getPixels(pixels, 0, w, 0, 0, w, h)
                val blurred = if (radius == 0) pixels else ModernBackdropBlur.blurInto(pixels, scratch, w, h, radius)
                createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                    setPixels(blurred, 0, w, 0, 0, w, h); prepareToDraw()
                }
            }.getOrNull()
            handler.post { recipient.get()?.acceptThinFrost(token, current, level, result) }
        }
        return null
    }

    private fun acceptThinFrost(token: Long, source: ModernBackdropFrame, level: Int, bitmap: Bitmap?) {
        if (closed || token != thinToken || frame !== source || bitmap == null) return
        thinWork = null
        // The previous thin bitmap may still be in a display list: drop the reference, never recycle.
        thinBitmap = bitmap
        thinShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        thinLevel = level
        for (view in surfaces.keys) view.invalidate()
    }

    private fun releaseThinFrost() {
        thinWork?.cancel(true); thinWork = null
        thinToken++
        thinBitmap = null; thinShader = null; thinLevel = -1; thinRequested = false
        // A cancelled job may still be running: dropping the references is enough, it keeps its own until it ends.
        thinPixels = null; thinScratch = null
    }

    internal fun setLiveBlurScale(view: View, scale: Float) { live.setBlurScale(view, scale) }

    internal fun liveBlurScale(view: View): Float = live.publishedBlurScale(view)

    /** A clear surface stops its live lens: nothing of the sample would be visible. */
    internal fun stopLiveSample(view: View) { live.unregister(view) }

    private fun requestBackdrop(newWidth: Int, newHeight: Int) {
        if (!lifecycle.canWork || newWidth <= 0 || newHeight <= 0 || (width == newWidth && height == newHeight && (frame != null || work != null))) return
        if (failedWidth == newWidth && failedHeight == newHeight) return
        width = newWidth; height = newHeight
        val token = lifecycle.beginRequest() ?: return
        work?.cancel(true)
        val recipient = WeakReference(this)
        val colors = palette
        val scale = density
        val completion = handler
        val context = backgroundContext
        // 返回已有页面或改变尺寸时重读配置；后台只接收本次不可变快照。
        val config = context?.let { LiquidBackgroundStore.read(it).config }
        val key = config?.takeIf { it.mode == LiquidBackgroundMode.CUSTOM }
            ?.let { BackdropKey(colors, it, newWidth, newHeight, scale) }
        pendingBackdropKey = key
        val autoKey = if (key == null) BackdropKey(colors, null, newWidth, newHeight, scale) else null
        pendingAutoBackdropKey = autoKey
        // 改回自动背景时照旧释放自定义图片条目；自动背景条目留给下一个页面与下一种根尺寸。
        if (key == null) preparedBackdrops.removeIf { it.config != null }
        val prepared = key?.let(preparedBackdrops::get)
        if (prepared != null) {
            acceptBackdrop(token, prepared, animateReveal = false)
            return
        }
        // 自动背景命中时照常淡入：与后台生成后到达的观感一致，只是不再等后台线程。
        val preparedAuto = autoKey?.let(preparedBackdrops::get)
        if (preparedAuto != null) {
            acceptBackdrop(token, preparedAuto)
            return
        }
        val isDark = dark
        workerStarted = true
        work = worker.submit {
            val result = runCatching {
                ModernBackdropFactory.create(colors, newWidth, newHeight, scale) { targetWidth, targetHeight ->
                    if (context == null || config?.mode != LiquidBackgroundMode.CUSTOM) null
                    else LiquidBackgroundStore.decodeBackdrop(context, config, targetWidth, targetHeight,
                        colors.background, isDark)
                }
            }.getOrNull()
            completion.post { recipient.get()?.acceptBackdrop(token, result) }
        }
    }

    private fun acceptBackdrop(token: Long, result: ModernBackdropFrame?, animateReveal: Boolean = true) {
        if (!lifecycle.accepts(token)) return
        work = null
        if (result == null) {
            failedWidth = width; failedHeight = height
            return // A readable neutral surface remains; retry only on resize or a later foreground session.
        }
        frame = result
        (if (result.custom) pendingBackdropKey else pendingAutoBackdropKey)?.let { key ->
            preparedBackdrops.put(key, result,
                result.original.allocationByteCount.toLong() + result.blurred.allocationByteCount)
        }
        rootBackdrop = result.original
        releaseThinFrost()
        sampleShader = BitmapShader(result.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        samplePaint.shader = sampleShader
        revealAnimator?.cancel()
        revealFraction = if (animateReveal && !(motionBackdropRequested && pendingBackdropKey != null) &&
            ValueAnimator.areAnimatorsEnabled()) 0f else 1f
        if (revealFraction == 0f) {
            revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 160L
                addUpdateListener {
                    if (!closed && generation == token) {
                        revealFraction = it.animatedValue as Float
                        root?.invalidate(); surfaces.keys.forEach(View::invalidate)
                    }
                }
                start()
            }
        }
        root?.invalidate()
        surfaces.keys.forEach(View::invalidate)
    }

    internal fun drawSample(canvas: Canvas, bounds: RectF, radius: Float, view: View?, opacity: Int,
        thin: BitmapShader? = null): Boolean {
        if (closed || view == null) return false
        val current = frame ?: return false
        val sourceRoot = root ?: return false
        if (!samplingMatrices.bitmapToTarget(sourceRoot, view, current.blurred.width, current.blurred.height, shaderMatrix)) return false
        // The thinner frost has the blurred frost's size, so it shares the mapping.
        val shader = thin ?: sampleShader
        shader?.setLocalMatrix(shaderMatrix)
        if (thin != null) samplePaint.shader = thin
        samplePaint.alpha = opacity
        canvas.drawRoundRect(bounds, radius, radius, samplePaint)
        if (thin != null) samplePaint.shader = sampleShader
        return true
    }

    internal fun drawLiveSample(canvas: Canvas, bounds: RectF, radius: Float, view: View?, opacity: Int): Boolean {
        if (closed || view == null || !lifecycle.canWork) return false
        val chrome = chromeGlass[view]
        if (chrome != null && canvas.isHardwareAccelerated && drawChromeGlass(chrome, canvas, bounds, radius, view, opacity)) {
            // 节点玻璃接管后，软件采样不能再为这个栏录制。
            live.unregister(view)
            return true
        }
        live.register(view)
        return live.draw(canvas, bounds, radius, view, opacity)
    }

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    override val supportsSurfaceBackdrop: Boolean
        get() = !closed && !chromeGlassBroken && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override val supportsDescendantSurfaceBackdrop: Boolean
        get() = supportsSurfaceBackdrop

    override fun setSurfaceBackdrop(host: View, target: GlowBackdropTarget?) {
        if (closed) return
        if (target == null || !supportsSurfaceBackdrop) {
            chromeGlass.remove(host)?.let { entry ->
                closeChromeGlass(entry)
                host.invalidate()
            }
            return
        }
        val existing = chromeGlass[host]
        if (existing?.target === target) return
        existing?.let(::closeChromeGlass)
        // 构造里会编译 AGSL：失败即永久停用节点路径，不能抛进调用方的 pre-draw。
        val glass = runCatching { FrostedChromeGlassApi31.create(density) }.getOrElse { error ->
            chromeGlassBroken = true
            Log.w(TAG, "chrome glass unavailable", error)
            chromeGlass.remove(host)
            host.invalidate()
            return
        }
        chromeGlass[host] = ChromeGlass(target, glass)
        host.invalidate()
    }

    private fun closeChromeGlass(entry: ChromeGlass) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (entry.glass as GlowChromeGlassApi31).close()
    }

    /** 返回 false 表示本次不可用（内容节点尚未录制、宿主脱离容器），调用方照常走软件透镜。 */
    private fun drawChromeGlass(
        entry: ChromeGlass,
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        view: View,
        opacity: Int
    ): Boolean {
        if (chromeGlassBroken || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val content = entry.target.recordedCapture() ?: return false
        if (!entry.target.matrixTo(view, chromeTransform)) return false
        bounds.roundOut(chromeBounds)
        return runCatching {
            (entry.glass as GlowChromeGlassApi31).draw(
                canvas = canvas,
                bounds = chromeBounds,
                radiusPx = radius,
                content = content,
                contentOffsetX = 0f,
                contentOffsetY = 0f,
                alpha = opacity / 255f,
                opticalIntensity = 1f,
                stretchDirY = 0f,
                underlay = null,
                contentToHost = chromeTransform
            )
        }.onFailure { error ->
            chromeGlassBroken = true
            Log.w(TAG, "chrome glass disabled", error)
            chromeGlass.values.forEach(::closeChromeGlass)
            chromeGlass.clear()
        }.isSuccess
    }

    internal fun register(view: View, role: SurfaceRole? = null) {
        if (!lifecycle.canWork) return
        val position = surfaces[view] ?: SurfaceTransform().also { surfaces[view] = it }
        if (role != null) position.role = role
        // 与批量比对使用相同的自上而下乘法次序，避免浮点结合顺序差异把静止表面误判为移动。
        samplingMatrices.withAncestorMemo {
            if (!samplingMatrices.localToScreen(view, position.target)) position.target.fill(Float.NaN)
            val source = root
            if (source == null || !samplingMatrices.localToScreen(source, position.source)) position.source.fill(Float.NaN)
        }
        registerRefreshWindow(view.rootView)
    }

    private fun registerRefreshWindow(windowRoot: View) {
        val observer = windowRoot.viewTreeObserver
        val existing = windows[windowRoot]
        if (existing?.observer?.get() === observer && observer.isAlive) return
        existing?.let(::removeRefreshWindow)
        val reference = WeakReference(windowRoot)
        val batch = FrostedRefreshBatch()
        val scroll = ViewTreeObserver.OnScrollChangedListener {
            live.invalidate()
            if (lifecycle.canWork && batch.request()) reference.get()?.let(::flushPositionChanges)
        }
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (batch.beforeDraw() && lifecycle.canWork) reference.get()?.let(::flushPositionChanges)
            true
        }
        val draw = ViewTreeObserver.OnDrawListener { batch.drawn() }
        windows[windowRoot] = RefreshWindow(WeakReference(observer), batch, scroll, preDraw, draw)
        observer.addOnScrollChangedListener(scroll)
        observer.addOnPreDrawListener(preDraw)
        observer.addOnDrawListener(draw)
    }

    private fun removeRefreshWindow(state: RefreshWindow) {
        state.batch.clear()
        state.observer.get()?.takeIf { it.isAlive }?.let { observer ->
            observer.removeOnScrollChangedListener(state.scroll)
            observer.removeOnPreDrawListener(state.preDraw)
            observer.removeOnDrawListener(state.draw)
        }
    }

    /**
     * 显式动画/形变的位移通知（`ActivitySkinSession.notifyPositionChanged` 转发）。
     *
     * 与滚动走同一条路径、同一个采样节奏：显式动画期曾经整段冻结透镜采样，顶栏/底栏
     * 透出的背景要等动画播完才跳变（2026-09-23 用户报告）。尖刺改由后台线程消化。
     */
    override fun notifyPositionChanged() = onPositionChanged()

    override fun notifyScrollPositionChanged(scrollHost: View) {
        if (!lifecycle.canWork || !scrollHost.isAttachedToWindow) return
        live.invalidate()
        flushPositionChanges(scrollHost.rootView, scrollHost)
    }

    private fun onPositionChanged() {
        if (!lifecycle.canWork) return
        live.invalidate()
        val iterator = windows.entries.iterator()
        while (iterator.hasNext()) {
            val (windowRoot, state) = iterator.next()
            if (!windowRoot.isAttachedToWindow) {
                removeRefreshWindow(state)
                iterator.remove()
            } else if (state.batch.request()) {
                // 手风琴等可能在本监听之后的 pre-draw 才写回属性；同帧补刷，绝不推迟一帧。
                flushPositionChanges(windowRoot)
            }
        }
    }

    private fun flushPositionChanges(windowRoot: View, scrollHost: View? = null) {
        if (!lifecycle.canWork) return
        // 本段只读几何；所有可见表面共用祖先前缀，备忘严格不跨回调保存。
        samplingMatrices.withAncestorMemo {
            val source = root
            val sourceValid = source != null && samplingMatrices.localToScreen(source, sourceTransform)
            val iterator = surfaces.entries.iterator()
            while (iterator.hasNext()) {
                val (view, position) = iterator.next()
                if (!view.isAttachedToWindow) { iterator.remove(); continue }
                if (view.rootView !== windowRoot || !view.isShown) continue
                if (scrollHost != null && !ScrollSurfaceScope.contains(view, scrollHost) { it.parent as? View }) continue
                if (!sourceValid || !samplingMatrices.localToScreen(view, movedTransform) ||
                    !SamplingMatrixMath.equal(position.target, movedTransform) ||
                    !SamplingMatrixMath.equal(position.source, sourceTransform)) view.invalidate()
            }
        }
    }

    fun releaseMemory() {
        lifecycle.invalidate()
        windows.values.forEach { it.batch.clear() }
        work?.cancel(true); work = null
        revealAnimator?.cancel(); revealAnimator = null
        if (!lifecycle.canWork) {
            rootBackdrop = null
            revealFraction = 0f
        } else if (rootBackdrop != null) {
            // 前台只释放模糊与实时透镜，继续显示受同一像素预算约束的稳定底图。
            revealFraction = 1f
        }
        // Never recycle a bitmap that can still be referenced by a hardware display list.
        frame = null; sampleShader = null; samplePaint.shader = null
        releaseThinFrost()
        live.releaseAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            chromeGlass.values.forEach { (it.glass as GlowChromeGlassApi31).releaseDisplayList() }
        }
        root?.invalidate(); surfaces.keys.forEach(View::invalidate)
    }

    fun stop() {
        lifecycle.stop()
        live.suspend()
        releaseMemory()
    }

    fun resume() {
        if (!lifecycle.resume()) return
        failedWidth = 0; failedHeight = 0
        live.resume()
        root?.let { requestBackdrop(it.width, it.height) }
    }

    // 作为高级材质的休眠回退时同样跟随生命周期：Liquid 可能在本次前台会话中途失败并切过来。
    override fun onStart() = resume()
    override fun onStop() = stop()

    // 阈值沿用会话层原实现；API 34 起平台不再下发 RUNNING_* 级别，更高的级别照样满足条件。
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            preparedBackdrops.clear(); releaseMemory()
        }
    }

    override fun onLowMemory() { preparedBackdrops.clear(); releaseMemory() }

    override fun close() {
        if (closed) return
        lifecycle.close()
        handler.removeCallbacks(materialSettle)
        releaseMemory()
        live.close()
        root?.removeOnLayoutChangeListener(layoutListener)
        windows.values.forEach(::removeRefreshWindow)
        windows.clear(); surfaces.clear(); root = null
        chromeGlass.values.forEach(::closeChromeGlass)
        chromeGlass.clear()
        if (workerStarted) worker.shutdownNow()
    }
}

private const val TAG = "Lumen-SoftFrost"

/** Prepared allows bind-before-onStart. Stopped and closed states cannot create or accept work. */
internal class FrostedMaterialLifecycle {
    private var stopped = false
    var isClosed = false
        private set
    var generation = 0L
        private set
    val canWork: Boolean get() = !stopped && !isClosed

    fun beginRequest(): Long? {
        if (!canWork) return null
        return ++generation
    }

    fun accepts(token: Long): Boolean = canWork && generation == token
    fun invalidate() { generation++ }
    fun stop() { stopped = true; invalidate() }
    fun resume(): Boolean {
        if (isClosed) return false
        stopped = false
        return true
    }
    fun close() { isClosed = true; stopped = true; invalidate() }
}

private data class ModernBackdropFrame(val original: Bitmap, val blurred: Bitmap, val custom: Boolean)

private object ModernBackdropFactory {
    fun create(
        palette: LumenPalette, width: Int, height: Int, density: Float,
        customBackground: ((Int, Int) -> Bitmap?)? = null
    ): ModernBackdropFrame {
        val (w, h) = ModernMaterialPolicy.sampleSize(width, height)
        // 自定义资产解码/哈希只在已有后台线程执行；失败只回退自动背景。
        val custom = runCatching { customBackground?.invoke(w, h) }.getOrNull()
        val original = custom ?: createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                val dark = ColorUtils.calculateLuminance(palette.background) < .5
                val canvas = Canvas(bitmap)
                AmbientBackdropScene.paint(canvas, palette, w, h, dark)
            }
        val pixels = IntArray(w * h)
        original.getPixels(pixels, 0, w, 0, 0, w, h)
        val blurredPixels = ModernBackdropBlur.blur(pixels, w, h, ModernMaterialPolicy.blurRadius(w, width, density))
        val blurred = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        blurred.setPixels(blurredPixels, 0, w, 0, 0, w, h)
        // 低分辨率背景放大后不再带颗粒斑块；磨砂采样仍使用独立的模糊副本。
        original.prepareToDraw(); blurred.prepareToDraw()
        return ModernBackdropFrame(original, blurred, custom != null)
    }
}

/** A direct background keeps its View callback, including in separately hosted Dialog windows. */
private class ModernSurfaceDrawable(
    private val renderer: FrostedMaterialRenderer?,
    private val color: Int,
    private val radius: Float,
    private val density: Float,
    private val style: ModernSurfaceStyle,
    /** [LumenEffectTuning.edgeHighlightWidth]；边框 alpha 已由 [ModernMaterialPolicy.surface] 换算进 [style]。 */
    edgeWidthScale: Float = 1f,
    private val tintOnly: Boolean = false,
    private val role: SurfaceRole? = null
) : Drawable(), MaterialOpacityDrawable {
    private val materialState = MaterialOpacityState(MaterialKind.FROSTED).apply {
        set(1f, LumenMaterialLegibility.AUTO.carriesText(role))
    }

    override fun setMaterialOpacity(value: Float, legibility: LumenMaterialLegibility) {
        if (materialState.set(value, legibility.carriesText(role))) { renderer?.onMaterialChanged(); invalidateSelf() }
    }

    override fun materialOpacity(): Float = materialState.own

    private val rect = RectF()
    private val edgeRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeWidth = ModernMaterialPolicy.edgeStrokePx(density, edgeWidthScale) }
    private val edgeShader = LinearGradient(0f, 0f, 0f, 1f,
        ColorUtils.setAlphaComponent(Color.WHITE, style.upperEdgeAlpha),
        ColorUtils.setAlphaComponent(Color.WHITE, style.lowerEdgeAlpha), Shader.TileMode.CLAMP)
    private val edgeMatrix = Matrix()
    private var edgeTop = Float.NaN
    private var edgeBottom = Float.NaN
    private var drawingAlpha = 255

    init { edge.shader = edgeShader }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
    }

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        val motionProvider = view as? LiquidMotionSurfaceFrameProvider
        var drawRadius = radius
        var drawColor = color
        if (motionProvider != null) {
            motionProvider.copyLiquidMotionBounds(rect)
            drawRadius = motionProvider.liquidMotionCornerRadiusPx()
            drawColor = motionProvider.liquidMotionFallbackColor()
        } else rect.set(bounds)
        // An uninitialized or collapsed motion frame must never fall back to the full View rectangle.
        if (rect.isEmpty || !rect.left.isFinite() || !rect.top.isFinite() ||
            !rect.right.isFinite() || !rect.bottom.isFinite() || !drawRadius.isFinite()) return
        drawRadius = drawRadius.coerceIn(0f, minOf(rect.width(), rect.height()) * .5f)
        // ARGB belongs to every caller, including ordinary diagnostic entry Views without a motion provider.
        val frameAlpha = FrostedMotionSurfaceAlpha.frameAlpha(drawColor, drawingAlpha)
        if (frameAlpha <= 0) return
        // register 记录的是"上一次**硬件**绘制时"的位置，供位移通知判断要不要重录显示列表。
        // 透镜采集的离屏软件回放不能写它：既是每表面 60–90µs 的重复矩阵计算，又会用当前位置
        // 覆盖掉显示列表真正录制时的位置——滚动通知在 pre-draw 之后才发，比对就会误判"没动"。
        if (view != null && canvas.isHardwareAccelerated) renderer?.register(view, role)
        val tintAlpha = if (tintOnly) style.tintAlpha
            else (255 + (style.tintAlpha - 255) * (renderer?.revealFraction ?: 0f)).toInt()
        val material = materialState.frame(if (role != null) renderer?.materialOpacityOf(role) ?: 1f else 1f)
        val backdropWeight = material?.backdrop ?: 1f
        val fillWeight = material?.fill ?: 1f
        val tintFull = tintAlpha * frameAlpha / 255
        val overlayAlpha = (tintFull * fillWeight).roundToInt()
        // Preserve the host's ARGB opacity without allocating an offscreen saveLayer for each frame.
        // Material opacity thins the frost (sample) and the haze (tint) separately: the fog clears.
        // While it moves the full-radius frost cross-fades; once settled a frost at the real radius stays opaque.
        val radiusWeight = material?.radiusBackdrop ?: 1f
        val fullSample = FrostedMotionSurfaceAlpha.sampleAlpha(frameAlpha, tintFull)
        val level = if (material == null) MaterialOpacityPolicy.BLUR_LEVELS else MaterialOpacityPolicy.blurLevel(material.blurRadius)
        val thin = if (level < MaterialOpacityPolicy.BLUR_LEVELS) renderer?.thinFrost(level) else null
        val sampleAlpha = (fullSample * (if (thin != null) radiusWeight else backdropWeight)).roundToInt()
        val sampling = maxOf(backdropWeight, radiusWeight) > MaterialOpacityPolicy.SAMPLING_EPSILON
        var sampled = tintOnly || sampling && renderer?.drawSample(canvas, rect, drawRadius, view, sampleAlpha, thin) == true
        // 悬浮表面再叠一层实时透镜采样：静态磨砂在下，穿过胶囊的内容在上，色罩最后。
        if (style.live && sampling && view != null && renderer != null) {
            val wanted = if (material == null || !renderer.materialSettled) 1f
                else MaterialOpacityPolicy.settledBlurScale(material.blurRadius)
            val liveThin = wanted < 1f && renderer.liveBlurScale(view) == wanted
            val liveAlpha = (fullSample * (if (liveThin) radiusWeight else backdropWeight)).roundToInt()
            if (renderer.drawLiveSample(canvas, rect, drawRadius, view, liveAlpha)) sampled = true
            renderer.setLiveBlurScale(view, wanted)
        }
        if (style.live && !sampling && view != null) renderer?.stopLiveSample(view)
        // Unsampled, the fallback stands in for the frost and thins out with it.
        val unsampledAlpha = (tintFull * fillWeight + (frameAlpha - tintFull) * backdropWeight).roundToInt()
        fill.color = ColorUtils.setAlphaComponent(drawColor, if (sampled) overlayAlpha else unsampledAlpha)
        canvas.drawRoundRect(rect, drawRadius, drawRadius, fill)
        // Motion hosts own their collapsing stroke. A second full-opacity edge would flash at handoff.
        // 边缘高光默认关闭（alpha 全 0）时直接跳过描边，省一次 drawRoundRect。
        if (motionProvider == null && (style.upperEdgeAlpha > 0 || style.lowerEdgeAlpha > 0)) {
            edgeRect.set(rect)
            edgeRect.inset(edge.strokeWidth / 2, edge.strokeWidth / 2)
            if (edgeTop != rect.top || edgeBottom != rect.bottom) {
                edgeTop = rect.top; edgeBottom = rect.bottom
                edgeMatrix.setScale(1f, rect.height().coerceAtLeast(1f))
                edgeMatrix.postTranslate(0f, rect.top)
                edgeShader.setLocalMatrix(edgeMatrix)
            }
            edge.alpha = (frameAlpha * (material?.edge ?: 1f)).roundToInt()
            canvas.drawRoundRect(edgeRect, (drawRadius - edge.strokeWidth / 2).coerceAtLeast(0f),
                (drawRadius - edge.strokeWidth / 2).coerceAtLeast(0f), edge)
        }
    }

    override fun setAlpha(alpha: Int) { drawingAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = drawingAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; edge.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    /**
     * 静态几何报告真实圆角；缺省实现是无半径矩形，会让弹性长按高光与 View 阴影
     * 都按方形裁剪。运动变形帧（motionProvider）只存在于 draw，outline 用静态
     * bounds + 构造半径即可——高光在 DOWN 时一次性取样。
     */
    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) outline.setEmpty()
        else outline.setRoundRect(bounds, radius)
    }
}

/** A source-over tint and sample together retain the exact caller-owned motion opacity. */
internal object FrostedMotionSurfaceAlpha {
    fun frameAlpha(color: Int, drawableAlpha: Int): Int =
        (color ushr 24) * drawableAlpha.coerceIn(0, 255) / 255

    fun sampleAlpha(frameAlpha: Int, overlayAlpha: Int): Int {
        val frame = frameAlpha.coerceIn(0, 255)
        val overlay = overlayAlpha.coerceIn(0, frame)
        if (overlay == 255) return 0
        return ((frame - overlay) * 255f / (255 - overlay)).roundToInt().coerceIn(0, 255)
    }
}

internal object ModernMaterialDrawables {
    /** 胶囊内部只叠着色和边框，继承父胶囊的实时光学图，不另采静态/旧帧背景。 */
    fun chromeOverlay(color: Int, radiusPx: Float, density: Float, role: SurfaceRole, dark: Boolean,
                      tuning: LumenEffectTuning = LumenEffectTuning.DEFAULT): Drawable =
        ModernSurfaceDrawable(null, color, radiusPx, density, ModernMaterialPolicy.surface(role, dark, tuning),
            tuning.edgeHighlightWidth, tintOnly = true)

    // 与 ModernBackdropFactory 同一套色阶语言：顶部向 surface 轻抬、底部沉向
    // surfaceVariant——条款同意页等无皮肤兜底背景也不再是一块纯色。
    fun neutralWindow(palette: LumenPalette): Drawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            ColorUtils.blendARGB(palette.background, palette.surface, .34f),
            palette.background,
            ColorUtils.blendARGB(palette.background, palette.surfaceVariant, .3f)
        ))

    fun fallback(color: Int, radiusPx: Float, density: Float, role: SurfaceRole, dark: Boolean,
                 tuning: LumenEffectTuning = LumenEffectTuning.DEFAULT): Drawable =
        ModernSurfaceDrawable(null, color, radiusPx, density, ModernMaterialPolicy.surface(role, dark, tuning),
            tuning.edgeHighlightWidth)
}
