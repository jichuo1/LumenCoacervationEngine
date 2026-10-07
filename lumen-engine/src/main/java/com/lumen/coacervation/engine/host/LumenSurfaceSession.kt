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
import com.lumen.coacervation.engine.material.LumenRawSampleProfile
import com.lumen.coacervation.engine.material.LumenSampleEvents
import com.lumen.coacervation.engine.geometry.LumenSurfaceField
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureHub
import com.lumen.coacervation.engine.runtime.LumenMemoryPressureListener
import com.lumen.coacervation.engine.runtime.LumenGraphicsCounters
import com.lumen.coacervation.engine.runtime.LumenSourceTimeline
import com.lumen.coacervation.engine.runtime.LumenSourceGraph
import java.util.IdentityHashMap
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
    private var nextSourceId = 1L
    private val retiredGraphics = LumenGraphicsCounters()
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
        return bindSource(view,surface,source,LumenSurfaceEnhancements.DEFAULT)
    }

    public fun bind(view: View,surface: LumenSurfaceOptions,source: View?,enhancements: LumenSurfaceEnhancements): LumenSurfaceBinding =
        bindSource(view,surface,source?.let(::ViewContentSource),enhancements)

    public fun bindSource(view: View,surface: LumenSurfaceOptions,source: LumenContentSource?,enhancements: LumenSurfaceEnhancements): LumenSurfaceBinding {
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
        entry.enhancements=enhancements
        entry.graphics.enabled=enhancements.debug.countersEnabled
        entry.graphics.timingEnabled=enhancements.debug.timingEnabled
        entries.add(entry)
        group?.refreshCounterOptions()
        entry.rebuild()
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
        groups.forEach { it.dirty = true; it.timeline.contentChanged(); it.sampler.invalidate() }
        entries.forEach { it.invalidateFrame() }
    }

    /** Recomputes full geometry on the next draw; no Activity or global touch dispatch is required. */
    public fun notifyPositionChanged() {
        if(closed)return
        checkMain();memoryReleased=false
        groups.forEach { it.sampler.invalidate() }
        entries.forEach { it.invalidateFrame() }
    }

    public fun pause() {
        if (closed || paused) return
        checkMain(); paused = true
        groups.forEach { it.sampler.suspend(); it.releaseGpu() }
        entries.forEach { it.releaseGpu(); it.resetState(LumenSurfaceFailure.PAUSED); it.invalidateFrame() }
    }

    public fun resume() {
        if (closed) return
        checkMain(); paused = false; memoryReleased = false
        groups.forEach { it.sampler.resume(); it.dirty = true }
        entries.forEach { it.resetState(); it.invalidateFrame() }
    }

    /** Drop graphics only. Explicit resume/content change may restore them; failures stay sticky. */
    public fun releaseGraphics() {
        if (closed) return
        checkMain(); memoryReleased = true
        groups.forEach { it.release() }
        entries.forEach { it.releaseGpu(); it.resetState(LumenSurfaceFailure.MEMORY_PRESSURE); it.invalidateFrame() }
    }

    public fun diagnostics(): LumenSurfaceDiagnostics {
        if (!closed) checkMain()
        return LumenSurfaceDiagnostics(closed, paused, entries.size, gpuDraws, softwareDraws, staticDraws,
            retiredRecordings + groups.sumOf { it.recordings + it.retiredSoftware + it.sampler.recordings }, firstDraws, lastFailure, paletteGeneration)
    }

    public fun performanceDiagnostics(): LumenSurfacePerformance {
        if(!closed)checkMain()
        val total=LumenGraphicsCounters().apply { absorb(retiredGraphics) }
        entries.forEach {total.absorb(it.graphics)};groups.forEach {total.absorb(it.graphics)}
        return LumenSurfacePerformance(total.contentRecordings,total.proxyRecordings,total.effectBuilds,total.shaderCompilations,
            total.softwareRequests,total.softwareCompletions,total.staleCompletions,total.contentRecordingNanos,total.softwareProcessingNanos,
            budget.used,contentPixels(),surfacePixels(),entries.any {it.enhancements.debug.timingEnabled})
    }

    internal fun updateEnhancements(id: Long,value: LumenSurfaceEnhancements) {
        if(closed)return
        checkMain()
        entries.firstOrNull {it.id==id}?.let {
            if(it.enhancements==value)return
            it.enhancements=value;it.graphics.enabled=value.debug.countersEnabled;it.graphics.timingEnabled=value.debug.timingEnabled
            it.group?.refreshCounterOptions();it.rebuild()
        }
    }

    internal fun updatePress(id: Long,x: Float,y: Float,pressure: Float) {
        if(closed)return
        checkMain();require(x.isFinite()&&y.isFinite()&&kotlin.math.abs(x)<=8192f&&kotlin.math.abs(y)<=8192f&&pressure.isFinite()&&pressure in 0f..1f)
        entries.firstOrNull {it.id==id}?.let {it.frame.touchX=x;it.frame.touchY=y;it.frame.pressure=pressure;it.invalidateFrame()}
    }
    internal fun updateShapes(id: Long,ax: Float,ay: Float,aw: Float,ah: Float,bx: Float,by: Float,bw: Float,bh: Float,second: Boolean) {
        if(closed)return
        checkMain();require(ax.isFinite()&&ay.isFinite()&&bx.isFinite()&&by.isFinite())
        require(aw.isFinite()&&ah.isFinite()&&bw.isFinite()&&bh.isFinite()&&aw>0f&&ah>0f&&bw>=0f&&bh>=0f)
        require(maxOf(aw,ah,bw,bh)<=8192f&&maxOf(kotlin.math.abs(ax),kotlin.math.abs(ay),kotlin.math.abs(bx),kotlin.math.abs(by))<=8192f)
        entries.firstOrNull {it.id==id}?.let {
            if(!it.frame.customShape){
                if(Build.VERSION.SDK_INT>=31)it.gpu?.close()
                it.gpu=null;it.gpuPixels=0L
            }
            val f=it.frame;f.customShape=true;f.secondShape=second
            f.shapeA[0]=ax;f.shapeA[1]=ay;f.shapeA[2]=aw;f.shapeA[3]=ah
            f.shapeB[0]=bx;f.shapeB[1]=by;f.shapeB[2]=bw;f.shapeB[3]=bh;it.invalidateFrame()
        }
    }
    internal fun updateLight(id: Long,x: Float,y: Float,z: Float) {
        if(closed)return
        checkMain();require(x.isFinite()&&y.isFinite()&&z.isFinite()&&z>=0f)
        val length=kotlin.math.sqrt(x*x+y*y+z*z);require(length.isFinite()&&length>1e-5f)
        entries.firstOrNull {it.id==id}?.let {it.frame.lightOverride=true;it.frame.lightX=x/length;it.frame.lightY=y/length;it.frame.lightZ=z/length;it.invalidateFrame()}
    }
    internal fun clearShapes(id:Long){
        if(closed)return
        checkMain()
        entries.firstOrNull {it.id==id}?.let {
            if(!it.frame.customShape)return
            it.frame.customShape=false;it.frame.secondShape=false
            if(!it.enhanced()){
                it.releaseGpu();it.group?.dirty=true
                it.host.get()?.let {view->it.group?.sampler?.unregister(view)}
            }
            it.resetState();it.invalidateFrame()
        }
    }
    internal fun frameTime(id: Long,now: Long,manual: Boolean) {
        if(closed)return
        checkMain();require(now>=0L)
        entries.firstOrNull {it.id==id}?.let {it.frame.frameNanos=now;it.frame.manualClock=manual;it.invalidateFrame()}
    }
    internal fun ripple(id: Long,x: Float,y: Float) {
        if(closed)return
        checkMain();require(x.isFinite()&&y.isFinite()&&kotlin.math.abs(x)<=8192f&&kotlin.math.abs(y)<=8192f)
        entries.firstOrNull {it.id==id}?.let {
            val f=it.frame;val slot=f.rippleCursor;f.rippleCursor=(slot+1)%it.enhancements.press.maxRipples
            f.rippleX[slot]=x;f.rippleY[slot]=y;f.rippleBorn[slot]=if(f.manualClock)f.frameNanos else System.nanoTime();it.invalidateFrame()
        }
    }
    internal fun clearTransient(id: Long) {if(closed)return;checkMain();entries.firstOrNull {it.id==id}?.let {it.frame.clearTransient();it.invalidateFrame()}}
    internal fun qualityHint(id: Long,pressure: Float) {
        if(closed)return
        checkMain();require(pressure.isFinite()&&pressure in 0f..1f)
        entries.firstOrNull {it.id==id}?.let {it.pressureHint=pressure;it.invalidateFrame()}
    }
    internal fun isBound(id: Long): Boolean {if(closed)return false;checkMain();return entries.any {it.id==id}}

    private fun sourceGraph(source: LumenContentSource,tracked: MutableList<LumenVersionedContentSource>): LumenSourceGraph.Result {
        tracked.clear()
        val ids=IdentityHashMap<LumenContentSource,Long>()
        val nodes=LinkedHashMap<Long,LongArray>()
        var exceeded=false
        fun collect(current: LumenContentSource): Long {
            ids[current]?.let {return it}
            if(ids.size>=32){exceeded=true;return -1L}
            val id=ids.size.toLong()+1L;ids[current]=id
            if(current is LumenVersionedContentSource)tracked.add(current)
            val deps=(current as? LumenVersionedContentSource)?.dependencies.orEmpty()
            if(deps.size>32){exceeded=true;nodes[id]=longArrayOf(-1L);return id}
            nodes[id]=LongArray(deps.size) {collect(deps[it])}
            return id
        }
        return try { collect(source);if(exceeded)LumenSourceGraph.Result.BUDGET_EXCEEDED else LumenSourceGraph.validate(nodes) }catch(_:Throwable){LumenSourceGraph.Result.MISSING_DEPENDENCY}
    }

    internal fun regions(id: Long): LumenSurfaceSamplingRegions? {
        if(closed)return null
        checkMain()
        val entry=entries.firstOrNull {it.id==id}?:return null
        val host=entry.host.get()?:return null
        val group=entry.group?:return null
        entry.updateRegions(host)
        fun region(rect: RectF,space: LumenRegionSpace)=LumenSurfaceRegion(rect.left,rect.top,rect.right,rect.bottom,space)
        return LumenSurfaceSamplingRegions(region(entry.shapeBounds,LumenRegionSpace.TARGET_LOCAL),
            region(entry.requiredBounds,LumenRegionSpace.TARGET_LOCAL),region(entry.recordedBounds,LumenRegionSpace.SOURCE_LOCAL),
            region(entry.materializedBounds,LumenRegionSpace.EXECUTION_PIXELS),region(entry.outputBounds,LumenRegionSpace.TARGET_LOCAL),group.id,group.timeline.epoch,
            group.timeline.contentVersion,group.timeline.capturedVersion,group.timeline.estimatedAgeNanos(System.nanoTime()),group.availability)
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
        retiredGraphics.absorb(entry.graphics)
        entry.releaseGpu(); entry.lenses = null; entries.remove(entry)
        if (entries.isEmpty()) unregisterTrim()
        entry.group?.let { group ->
            if (entries.none { it.group === group }) {
                retiredRecordings += group.recordings + group.retiredSoftware + group.sampler.recordings
                group.graphics.retireInto(retiredGraphics)
                group.close(); groups.remove(group)
            } else group.refreshCounterOptions()
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
        val id=nextSourceId++
        val graphics=LumenGraphicsCounters()
        val timeline=LumenSourceTimeline()
        private val trackedSources=ArrayList<LumenVersionedContentSource>()
        var graphResult=sourceGraph(source,trackedSources)
        private var trackedEpochs=snapshotEpochs()
        private fun snapshotEpochs()=LongArray(trackedSources.size){runCatching {trackedSources[it].sourceEpoch}.getOrDefault(-1L)}
        private fun dependenciesChanged(): Boolean {
            for(i in 0 until trackedSources.size)if(runCatching {trackedSources[i].sourceEpoch}.getOrDefault(-1L)!=trackedEpochs[i])return true
            return false
        }
        var declaredEpoch=runCatching {(source as? LumenVersionedContentSource)?.sourceEpoch?:0L}.getOrDefault(0L)
        var declaredVersion=runCatching {(source as? LumenVersionedContentSource)?.contentVersion?:0L}.getOrDefault(0L)
        var availability=readAvailability()
        private fun readAvailability(): LumenSourceAvailability {
            return try {
                for(i in 0 until trackedSources.size){
                    val current=trackedSources[i]
                    if(current.sourceEpoch<0L||current.contentVersion<0L)return LumenSourceAvailability.TEMPORARILY_UNAVAILABLE
                    if(current.availability!=LumenSourceAvailability.READY)return current.availability
                }
                LumenSourceAvailability.READY
            }catch(_:Throwable){LumenSourceAvailability.TEMPORARILY_UNAVAILABLE}
        }
        private val sampleEvents=object:LumenSampleEvents {
            override val sourceEpoch: Long get()=timeline.epoch
            override val contentVersion: Long get()=timeline.contentVersion
            override fun onCompleted(epoch: Long,version: Long,started: Long,finished: Long,published: Boolean) {
                if(published&&!closed)timeline.completed(epoch,version,started,finished)
            }
        }
        var sampler = makeSampler()
        var capture: SurfaceCaptureApi31? = null
        var dirty = true
        var failed = false
        var budgetRejected = false
        var recordings = 0L
        var retiredSoftware = 0L
        private var observer: ViewTreeObserver? = null
        private var lastRecording = 0L
        private var autoSuspended = false
        init { source.coordinateView.addOnAttachStateChangeListener(this); observe() }
        private fun makeSampler() = LiveBackdropSampler(density, ViewSamplingMatrix(), recordContent = { recordSource(source, it) }, sharedBudget = budget, counters = graphics,events=sampleEvents).apply { bindSource(source.coordinateView) }
        fun refreshCounterOptions() {
            graphics.enabled=entries.any {it.group===this&&it.enhancements.debug.countersEnabled}
            graphics.timingEnabled=entries.any {it.group===this&&it.enhancements.debug.timingEnabled}
        }
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
        override fun onWindowFocusChanged(hasFocus: Boolean) { dirty = true; entries.forEach { if (it.group === this) it.invalidateFrame() } }
        override fun onScrollChanged() { dirty = true; timeline.contentChanged(); sampler.invalidate() }
        override fun onGlobalLayout() { dirty = true; timeline.contentChanged(); sampler.invalidate() }
        override fun onViewAttachedToWindow(v: View) { observe(); dirty = true; sampler.resume() }
        override fun onViewDetachedFromWindow(v: View) {
            // A source Window disappearing invalidates all its targets, including Story dialog overlays.
            for (i in entries.size - 1 downTo 0) if (entries[i].group === this) remove(entries[i])
        }
        override fun onPreDraw(): Boolean {
            if (closed) return true
            val content = source.coordinateView
            val versioned=source as? LumenVersionedContentSource
            if(versioned!=null){
                try {
                    val epoch=versioned.sourceEpoch;val version=versioned.contentVersion
                    require(epoch>=0L&&version>=0L)
                    if(declaredEpoch!=epoch){
                        declaredEpoch=epoch;timeline.sourceChanged();graphResult=sourceGraph(source,trackedSources);trackedEpochs=snapshotEpochs()
                        sampler.releaseAll();releaseGpu();dirty=true
                        entries.forEach {if(it.group===this){it.releaseGpu();it.resetState()}}
                    }
                    if(declaredVersion!=version){declaredVersion=version;timeline.contentChanged();dirty=true;sampler.invalidate()}
                }catch(_:Throwable){availability=LumenSourceAvailability.TEMPORARILY_UNAVAILABLE}
            }
            if(dependenciesChanged()){
                graphResult=sourceGraph(source,trackedSources);trackedEpochs=snapshotEpochs();timeline.sourceChanged()
                sampler.releaseAll();releaseGpu();dirty=true
                entries.forEach {if(it.group===this){it.releaseGpu();it.resetState()}}
            }
            availability=readAvailability()
            if(graphResult!=LumenSourceGraph.Result.VALID||availability!=LumenSourceAvailability.READY){
                if(!autoSuspended){
                    timeline.sourceChanged();sampler.suspend();releaseGpu();autoSuspended=true
                    entries.forEach {if(it.group===this){it.releaseGpu();it.resetState(LumenSurfaceFailure.NO_SOURCE)}}
                }
                return true
            }
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
                if(entry.enhanced())continue
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
                    timeline.requested(now)
                    val measuredStart=if(graphics.timingEnabled)System.nanoTime()else 0L
                    if (current.record(recorder, options, available)) {
                        budgetRejected = false; dirty = false; lastRecording = now; recordings++
                        graphics.contentRecorded(if(measuredStart!=0L)System.nanoTime()-measuredStart else 0L)
                        timeline.completed(timeline.epoch,timeline.contentVersion,now,System.nanoTime())
                    }
                    else { budgetRejected = true; lastFailure = LumenSurfaceFailure.BUDGET_EXCEEDED }
                } catch (_: Throwable) { failed = true; releaseGpu(); lastFailure = LumenSurfaceFailure.GPU_FAILED }
            }
            return true
        }
        private var trailingPosted = false
        private val trailing = Runnable {
            trailingPosted = false
            if (!closed && !paused && !memoryReleased) {
                dirty = true
                for (entry in entries) if (entry.group === this) entry.invalidateFrame()
            }
        }
        fun releaseGpu() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) capture?.close()
            capture = null; dirty = true; budgetRejected = false
        }
        fun release() { timeline.sourceChanged();releaseGpu(); sampler.suspend(); autoSuspended = true }
        fun close() {
            unobserve(); main.removeCallbacks(trailing)
            source.coordinateView.removeOnAttachStateChangeListener(this)
            releaseGpu(); sampler.close()
        }
    }

    private fun failure(host: View, entry: Entry): LumenSurfaceFailure {
        val group=entry.group?:return LumenSurfaceFailure.NO_SOURCE
        val source = group.source
        if(group.graphResult==LumenSourceGraph.Result.BUDGET_EXCEEDED)return LumenSurfaceFailure.BUDGET_EXCEEDED
        if(group.graphResult==LumenSourceGraph.Result.CYCLE)return LumenSurfaceFailure.SELF_FEEDBACK
        if(group.graphResult!=LumenSourceGraph.Result.VALID||group.availability!=LumenSourceAvailability.READY)return LumenSurfaceFailure.NO_SOURCE
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
        var original = view.background
        var enhancements=LumenSurfaceEnhancements.DEFAULT
        val graphics=LumenGraphicsCounters()
        val frame=LumenSurfaceRenderState()
        val quality=LumenSurfaceQualityPolicy()
        var pressureHint=0f
        var direct: DirectSurfaceProgramApi33?=null
        var rawProfile=LumenRawSampleProfile(1f)
        var rawSampling=config.sampling
        var rawDetail: LumenDetailMode?=null
        fun applyDetail(detail: LumenDetailMode) {
            if(rawDetail==detail)return
            rawDetail=detail
            val q=enhancements.quality;val sampling=appearance.sampling
            val cap=if(q.enabled&&detail==LumenDetailMode.LOW)maxOf(1024,q.maxBitmapPixels/2)else q.maxBitmapPixels
            val interval=if(q.enabled)maxOf(sampling.minIntervalMs,q.bitmapIntervalMs,if(detail==LumenDetailMode.LOW)56L else 0L)else sampling.minIntervalMs
            rawSampling=sampling.copy(maxSoftwarePixels=minOf(sampling.maxSoftwarePixels,cap),minIntervalMs=interval)
        }
        var appearance=config
        val drawable = SurfaceDrawable(this)
        fun invalidateFrame(){drawable.invalidateSelf();host.get()?.invalidate()}
        var gpu: SurfaceCaptureApi31.Glass? = null
        var lenses: SurfaceCaptureApi31.LensCache? = null
        var detached = false
        val visibleRegion = Rect()
        val shapeBounds=RectF();val requiredBounds=RectF();val recordedBounds=RectF();val materializedBounds=RectF();val outputBounds=RectF()
        val regionMatrix=Matrix()
        fun updateRegions(view: View) {
            val w=view.width.toFloat();val h=view.height.toFloat();val f=frame
            shapeBounds.set(if(f.customShape)f.shapeA[0]else 0f,if(f.customShape)f.shapeA[1]else 0f,
                if(f.customShape)f.shapeA[0]+f.shapeA[2]else w,if(f.customShape)f.shapeA[1]+f.shapeA[3]else h)
            if(enhancements.geometry.fusionEnabled&&f.secondShape&&!enhancements.material.reduceMotion){
                shapeBounds.union(f.shapeB[0],f.shapeB[1],f.shapeB[0]+f.shapeB[2],f.shapeB[1]+f.shapeB[3])
                val fusion=enhancements.geometry.fusionRadiusDp*density/4f;shapeBounds.inset(-fusion,-fusion)
            }
            outputBounds.set(0f,0f,w,h)
            val c=appearance
            val pad=if(enhanced())rawProfile.haloDp*density else maxOf(if(c.sampling.refractionEnabled)20f*c.sampling.refractionStrength else 0f,
                if(c.sampling.blurEnabled)c.sampling.blurRadiusDp*2f else 0f)*density+2f
            requiredBounds.set(-pad,-pad,w+pad,h+pad)
            recordedBounds.setEmpty();materializedBounds.setEmpty()
            val g=group?:return
            if(g.sampler.samplingBounds(view,recordedBounds,materializedBounds))return
            if(Build.VERSION.SDK_INT>=31&&g.capture?.recorded==true&&stateBackend==LumenSurfaceBackend.GPU){
                val source=g.source.coordinateView;recordedBounds.set(0f,0f,source.width.toFloat(),source.height.toFloat())
                // Legacy RenderNode is materialized at its outline, leaving the padded recording outside this rectangle.
                materializedBounds.set(pad,pad,pad+w,pad+h)
            }
        }
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
                if (view.background !== drawable) original = view.background
                val left = view.paddingLeft; val top = view.paddingTop; val right = view.paddingRight; val bottom = view.paddingBottom
                view.background = drawable; view.setPadding(left, top, right, bottom)
            } else if (view.background === drawable) {
                replaceBackground(view, original); group?.sampler?.unregister(view)
            }
            resetState()
            view.invalidate()
        }
        fun releaseGpu() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) gpu?.close()
            if(Build.VERSION.SDK_INT>=33)direct?.close()
            direct=null
            gpu = null; gpuPixels = 0L
        }
        fun enhanced(): Boolean {
            val e=enhancements
            return frame.customShape||e.geometry.cornersEnabled||e.geometry.fusionEnabled||e.geometry.shadowEnabled||e.press.enabled||e.press.rippleEnabled||
                e.light.enabled||e.progressiveBlur.enabled||e.material.intent!=LumenMaterialIntent.UNCHANGED||e.material.reduceTransparency||
                e.material.normalizeBySize||e.material.saturation!=1f
        }
        fun rebuild() {
            appearance=LumenMaterialRecipe.resolve(config,enhancements.material,palette)
            val requirements=LumenSurfaceEffectFactory.requirements(appearance,enhancements)
            rawProfile=LumenRawSampleProfile(requirements.haloDp,requirements.weakBlurDp,requirements.strongBlurDp)
            rawDetail=null;applyDetail(if(enhancements.quality.enabled)enhancements.quality.mode else LumenDetailMode.HIGH)
            val keep=if(enhanced()&&appearance.sampling.enabled&&appearance.material!=LumenSurfaceMaterial.STATIC)direct else null
            if(keep!=null)direct=null
            releaseGpu();direct=keep;drawable.configure();resetState();group?.dirty=true
            host.get()?.let {group?.sampler?.unregister(it)};invalidateFrame()
        }
        fun resetState(reason: LumenSurfaceFailure = LumenSurfaceFailure.FRAME_PENDING) {
            val dynamic = options.enabled && appearance.enabled && appearance.opacity > 0f && appearance.sampling.enabled &&
                appearance.material != LumenSurfaceMaterial.STATIC && appearance.sampling.backend != LumenSurfaceBackend.STATIC
            val failure = if (!dynamic) LumenSurfaceFailure.NONE else when {
                paused -> LumenSurfaceFailure.PAUSED
                memoryReleased -> LumenSurfaceFailure.MEMORY_PRESSURE
                group!=null&&group.graphResult==LumenSourceGraph.Result.CYCLE -> LumenSurfaceFailure.SELF_FEEDBACK
                group!=null&&group.graphResult==LumenSourceGraph.Result.BUDGET_EXCEEDED -> LumenSurfaceFailure.BUDGET_EXCEEDED
                group!=null&&(group.graphResult!=LumenSourceGraph.Result.VALID||group.availability!=LumenSourceAvailability.READY) -> LumenSurfaceFailure.NO_SOURCE
                else -> reason
            }
            report(LumenSurfaceBackend.STATIC, failure, false)
        }
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
        private val shapeA=Path();private val shapeB=Path();private val cornerValues=FloatArray(4);private val pathRadii=FloatArray(8)
        private val rawMatrix=Matrix();private val rawPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val boundsPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE}
        private var alphaValue = 255
        private var gradient: Shader? = null
        private var edgeGradient: Shader? = null
        private var staticGradient: Shader? = null
        private var colorAlpha = 255
        private var edgeAlpha = 70
        init { configure() }
        fun configure() {
            val c = entry.appearance
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
            val c = entry.appearance
            if (recordingDepth > 0 || closed || entry.detached || !options.enabled || !c.enabled || c.opacity == 0f || host.alpha <= 0f || bounds.isEmpty) return
            var reason = when { !c.sampling.enabled || c.material == LumenSurfaceMaterial.STATIC || c.sampling.backend == LumenSurfaceBackend.STATIC -> LumenSurfaceFailure.NONE; paused -> LumenSurfaceFailure.PAUSED; memoryReleased -> LumenSurfaceFailure.MEMORY_PRESSURE; else -> failure(host, entry) }
            var backend = LumenSurfaceBackend.STATIC
            val alpha = c.opacity * alphaValue / 255f
            var visibleEffect = false
            var enhancedPainted=false
            val save = canvas.save()
            if (c.clipBackground&&!entry.enhanced()) canvas.clipPath(clip)
            try {
                if (reason == LumenSurfaceFailure.NONE && c.sampling.enabled && c.material != LumenSurfaceMaterial.STATIC &&
                    c.sampling.backend != LumenSurfaceBackend.STATIC) {
                    val group = entry.group!!
                    if (group.failed) reason = LumenSurfaceFailure.GPU_FAILED
                    else if (group.budgetRejected) reason = LumenSurfaceFailure.BUDGET_EXCEEDED
                    val content = group.source.coordinateView
                    if (options.pauseWhenWindowUnfocused && !content.hasWindowFocus()) reason = LumenSurfaceFailure.PAUSED
                    else if(entry.enhanced()) {
                        val detail=entry.quality.update(entry.pressureHint,if(entry.frame.manualClock)entry.frame.frameNanos else System.nanoTime(),entry.enhancements.quality)
                        entry.applyDetail(detail)
                        val q=entry.enhancements.quality
                        val execution=bounds.width().toLong()*bounds.height()
                        if(execution>q.maxExecutionPixels||execution+surfacePixels()-entry.gpuPixels>options.maxGpuSurfacePixels||
                            maxOf(bounds.width(),bounds.height())>options.maxGpuDimension||entry.enhancements.progressiveBlur.enabled&&bounds.height()/density>entry.enhancements.progressiveBlur.maxBandHeightDp)reason=LumenSurfaceFailure.BUDGET_EXCEEDED
                        else if(LumenSurfacePolicy.softwareAllowed(c.sampling)) {
                            group.sampler.registerRaw(host,entry.rawSampling,entry.rawProfile)
                            val clear=group.sampler.rawShader(host)
                            if(clear!=null){
                                if(Build.VERSION.SDK_INT>=33&&canvas.isHardwareAccelerated&&!entry.gpuFailed&&LumenSurfaceEffectFactory.plan(Build.VERSION.SDK_INT,c,true)==LumenSurfaceEffectFactory.Plan.DIRECT_BITMAP){
                                    try {
                                        val program=entry.direct?:LumenSurfaceEffectFactory.direct(entry.graphics).also {entry.direct=it}
                                        entry.frame.rtl=host.layoutDirection==View.LAYOUT_DIRECTION_RTL
                                        updateNormalTransform(host)
                                        enhancedPainted=program.draw(canvas,rectangle,c,entry.enhancements,entry.frame,palette,density,clear,
                                            group.sampler.rawShader(host,1)?:clear,group.sampler.rawShader(host,2)?:clear,
                                            group.sampler.rawScale(host),group.sampler.rawMargin(host),group.sampler.rawWidth(host),group.sampler.rawHeight(host),alpha,detail)
                                        if(enhancedPainted){entry.gpuPixels=execution;backend=LumenSurfaceBackend.GPU;gpuDraws++}
                                    }catch(_:Throwable){entry.gpuFailed=true;entry.direct?.close();entry.direct=null;reason=LumenSurfaceFailure.GPU_FAILED}
                                }
                                if(!enhancedPainted){
                                    configureEnhancedPath(c,host)
                                    canvas.clipPath(shapeA)
                                    val source=group.sampler.rawShader(host,2)?:clear
                                    rawMatrix.setScale(group.sampler.rawScale(host),group.sampler.rawScale(host))
                                    rawMatrix.postTranslate(-group.sampler.rawMargin(host),-group.sampler.rawMargin(host))
                                    source.setLocalMatrix(rawMatrix);rawPaint.shader=source;rawPaint.alpha=(255*alpha).roundToInt()
                                    canvas.drawRect(rectangle,rawPaint);backend=LumenSurfaceBackend.SOFTWARE;softwareDraws++
                                }
                            } else reason=if(group.sampler.hasFailed)LumenSurfaceFailure.SOFTWARE_FAILED else if(group.sampler.budgetRejected)LumenSurfaceFailure.BUDGET_EXCEEDED else LumenSurfaceFailure.FRAME_PENDING
                        } else reason=LumenSurfaceFailure.GPU_UNAVAILABLE
                    }
                    else {
                        if (c.sampling.backend != LumenSurfaceBackend.SOFTWARE &&
                            (!canvas.isHardwareAccelerated || Build.VERSION.SDK_INT < 31)) reason = LumenSurfaceFailure.GPU_UNAVAILABLE
                        if (canvas.isHardwareAccelerated && LumenSurfacePolicy.gpuAllowed(Build.VERSION.SDK_INT, c.sampling.backend, true, entry.gpuFailed || group.failed) &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && group.capture?.recorded == true) {
                            try {
                                if (matrices.sourceToTarget(content, host, sampleMatrix)) {
                                    val blur=if(c.sampling.blurEnabled)c.sampling.blurRadiusDp else 0f
                                    val pad = ((maxOf(20f * c.sampling.refractionStrength, blur * 2f) * density).roundToInt() + 2)
                                    val area=(bounds.width().toLong() + 2 * pad) * (bounds.height().toLong() + 2 * pad)
                                    val pixels = area
                                    val allocated = surfacePixels() - entry.gpuPixels
                                    if (pixels + allocated > options.maxGpuSurfacePixels || entry.enhancements.quality.enabled&&pixels>entry.enhancements.quality.maxExecutionPixels ||
                                        bounds.width() + 2 * pad > options.maxGpuDimension || bounds.height() + 2 * pad > options.maxGpuDimension) {
                                        reason = LumenSurfaceFailure.BUDGET_EXCEEDED
                                    } else {
                                    entry.gpuPixels = pixels
                                    val glass = entry.gpu ?: SurfaceCaptureApi31.Glass(c, density, ColorUtils.calculateLuminance(palette.background) < .5, entry.lenses ?: SurfaceCaptureApi31.LensCache().also { entry.lenses = it },entry.graphics).also { entry.gpu = it }
                                    group.capture!!.draw(glass, canvas, bounds, c.radiusDp * density, sampleMatrix, alpha)
                                    backend = LumenSurfaceBackend.GPU; gpuDraws++; group.sampler.unregister(host)
                                    }
                                } else reason = LumenSurfaceFailure.INVALID_GEOMETRY
                            } catch (_: Throwable) { entry.gpuFailed = true; entry.releaseGpu(); reason = LumenSurfaceFailure.GPU_FAILED }
                        }
                        if (backend != LumenSurfaceBackend.GPU && LumenSurfacePolicy.softwareAllowed(c.sampling)) {
                            group.sampler.register(host, c.sampling)
                            if (group.sampler.draw(canvas, rectangle, c.radiusDp * density, host, (255 * alpha).roundToInt())) {
                                backend = LumenSurfaceBackend.SOFTWARE; softwareDraws++
                            } else if (group.sampler.hasFailed) reason = LumenSurfaceFailure.SOFTWARE_FAILED
                            else if (group.sampler.budgetRejected) reason = LumenSurfaceFailure.BUDGET_EXCEEDED
                            else if (reason == LumenSurfaceFailure.NONE) reason = LumenSurfaceFailure.FRAME_PENDING
                        }
                    }
                }
                if (backend == LumenSurfaceBackend.STATIC) {
                    staticDraws++
                    if (reason == LumenSurfaceFailure.NONE && c.sampling.enabled && c.material != LumenSurfaceMaterial.STATIC &&
                        c.sampling.backend != LumenSurfaceBackend.STATIC) reason = LumenSurfaceFailure.FRAME_PENDING
                }
                val tint = if (!c.tintEnabled) 0f else if (backend == LumenSurfaceBackend.STATIC) c.fallbackTintOpacity else c.tintOpacity
                visibleEffect = backend != LumenSurfaceBackend.STATIC || c.tintEnabled && tint > 0f && colorAlpha > 0 ||
                    c.edgeEnabled && c.edgeWidthDp > 0f && edgeAlpha > 0
                // Static fallback deliberately remains readable; opacity affects background only.
                if(!enhancedPainted){
                if(entry.enhanced()){configureEnhancedPath(c,host);if(c.clipBackground)canvas.clipPath(shapeA)}
                fill.shader = if (backend == LumenSurfaceBackend.STATIC) staticGradient else gradient
                fill.alpha = if (fill.shader != null) (if (c.tintEnabled) 255 * alpha else 0f).roundToInt() else
                    (colorAlpha * tint * alpha).roundToInt()
                if(entry.enhanced())canvas.drawPath(shapeA,fill)else canvas.drawRoundRect(rectangle, c.radiusDp * density, c.radiusDp * density, fill)
                if (c.edgeEnabled && c.edgeWidthDp > 0f) {
                    edge.alpha = ((if (edgeGradient != null) 255 else edgeAlpha) * alpha).roundToInt()
                    if(entry.enhanced())canvas.drawPath(shapeA,edge)else canvas.drawRoundRect(rectangle, c.radiusDp * density, c.radiusDp * density, edge)
                }
                }
            } finally { canvas.restoreToCount(save) }
            if(entry.enhancements.debug.drawSamplingBounds){
                entry.updateRegions(host)
                boundsPaint.strokeWidth=entry.enhancements.debug.boundsLineWidthDp*density
                fun drawRegion(rect: RectF,color: Int){boundsPaint.color=color;boundsPaint.alpha=(entry.enhancements.debug.boundsOpacity*255).roundToInt();canvas.drawRect(rect,boundsPaint)}
                drawRegion(entry.shapeBounds,Color.GREEN);drawRegion(entry.requiredBounds,Color.RED)
                val g=entry.group
                if(g!=null&&matrices.sourceToTarget(g.source.coordinateView,host,entry.regionMatrix)){
                    entry.regionMatrix.mapRect(rectangle,entry.recordedBounds);drawRegion(rectangle,Color.MAGENTA)
                }
                rectangle.set(entry.materializedBounds)
                if(entry.enhanced()&&g!=null){val scale=g.sampler.rawScale(host);val margin=g.sampler.rawMargin(host);rectangle.set(rectangle.left*scale-margin,rectangle.top*scale-margin,rectangle.right*scale-margin,rectangle.bottom*scale-margin)}
                else if(entry.stateBackend==LumenSurfaceBackend.GPU){val pad=entry.requiredBounds.left;rectangle.offset(pad,pad)}
                drawRegion(rectangle,Color.CYAN);drawRegion(entry.outputBounds,Color.BLUE);rectangle.set(bounds)
            }
            entry.report(backend, reason, (canvas.isHardwareAccelerated || !host.isHardwareAccelerated) && visibleEffect && host.isShown && host.isAttachedToWindow && alpha > 0f)
        }
        private fun configureEnhancedPath(c: LumenSurfaceOptions,host: View) {
            val f=entry.frame;val e=entry.enhancements.geometry
            val corners=e.corners;val mirror=e.mirrorCornersInRtl&&host.layoutDirection==View.LAYOUT_DIRECTION_RTL
            fun add(path: Path,x: Float,y: Float,w: Float,h: Float) {
                val tl=if(e.cornersEnabled)if(mirror)corners.topRight else corners.topLeft else c.radiusDp
                val tr=if(e.cornersEnabled)if(mirror)corners.topLeft else corners.topRight else c.radiusDp
                val br=if(e.cornersEnabled)if(mirror)corners.bottomLeft else corners.bottomRight else c.radiusDp
                val bl=if(e.cornersEnabled)if(mirror)corners.bottomRight else corners.bottomLeft else c.radiusDp
                LumenSurfaceField.normaliseCorners(w,h,tl*density,tr*density,br*density,bl*density,cornerValues)
                for(i in 0..3){pathRadii[2*i]=cornerValues[i];pathRadii[2*i+1]=cornerValues[i]}
                path.reset();rectangle.set(x,y,x+w,y+h);path.addRoundRect(rectangle,pathRadii,Path.Direction.CW)
            }
            add(shapeA,if(f.customShape)f.shapeA[0]else 0f,if(f.customShape)f.shapeA[1]else 0f,if(f.customShape)f.shapeA[2]else bounds.width().toFloat(),if(f.customShape)f.shapeA[3]else bounds.height().toFloat())
            if(e.fusionEnabled&&f.secondShape&&!entry.enhancements.material.reduceMotion){add(shapeB,f.shapeB[0],f.shapeB[1],f.shapeB[2],f.shapeB[3]);shapeA.op(shapeB,Path.Op.UNION)}
            rectangle.set(bounds)
        }
        private fun updateNormalTransform(host: View) {
            val values=entry.frame.matrixValues;val normal=entry.frame.normalTransform
            normal[0]=1f;normal[1]=0f;normal[2]=0f;normal[3]=1f
            if(!matrices.localToScreen(host,values)||kotlin.math.abs(values[6])>1e-5f||kotlin.math.abs(values[7])>1e-5f)return
            val determinant=values[0]*values[4]-values[1]*values[3]
            if(!determinant.isFinite()||kotlin.math.abs(determinant)<1e-5f)return
            val inverse=1f/determinant
            if(maxOf(kotlin.math.abs(values[4]*inverse),kotlin.math.abs(values[3]*inverse),kotlin.math.abs(values[1]*inverse),kotlin.math.abs(values[0]*inverse))>1000f)return
            normal[0]=values[4]*inverse;normal[1]=-values[3]*inverse;normal[2]=-values[1]*inverse;normal[3]=values[0]*inverse
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
    public val isBound: Boolean get()=session.get()?.isBound(id)==true
    public fun update(options: LumenSurfaceOptions) { session.get()?.update(id, options) }
    public fun updateEnhancements(options: LumenSurfaceEnhancements) { session.get()?.updateEnhancements(id, options) }
    public fun samplingRegions(): LumenSurfaceSamplingRegions? = session.get()?.regions(id)
    public fun setPressPixels(x: Float,y: Float,pressure: Float) {session.get()?.updatePress(id,x,y,pressure)}
    public fun setShapesPixels(ax: Float,ay: Float,aw: Float,ah: Float,bx: Float,by: Float,bw: Float,bh: Float,secondShape: Boolean) {session.get()?.updateShapes(id,ax,ay,aw,ah,bx,by,bw,bh,secondShape)}
    public fun clearCustomShapes(){session.get()?.clearShapes(id)}
    public fun setLightDirection(x: Float,y: Float,z: Float) {session.get()?.updateLight(id,x,y,z)}
    public fun setFrameTimeNanos(now: Long,manual: Boolean) {session.get()?.frameTime(id,now,manual)}
    public fun emitRipplePixels(x: Float,y: Float) {session.get()?.ripple(id,x,y)}
    public fun clearTransientEffects() {session.get()?.clearTransient(id)}
    public fun setQualityPressure(pressure: Float) {session.get()?.qualityHint(id,pressure)}
    public fun diagnostics(): LumenSurfaceState? = session.get()?.state(id)
    override fun close() { session.get()?.detach(id) }
}
