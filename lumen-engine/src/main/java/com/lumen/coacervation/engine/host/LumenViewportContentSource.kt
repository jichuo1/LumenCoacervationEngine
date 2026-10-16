package com.lumen.coacervation.engine.host

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.geometry.LocalViewDrawing
import com.lumen.coacervation.engine.geometry.ViewSamplingMatrix
import com.lumen.coacervation.engine.geometry.ViewportSamplingBounds

/**
 * Records only the union of the supplied surfaces' sampling regions. Each region supplies its View,
 * horizontal/vertical inset and blur padding in that View's pixels; transforms and outward rounding
 * are applied in the source's local coordinates.
 *
 * Call [sync] before the session's pre-draw when regions move. A ViewGroup source receives an invisible
 * overlay coordinate proxy, so capture textures have the cropped size without changing host layout.
 * Other Views retain their original texture size and only clip drawing. A software draw of the proxy
 * samples the owner's native foreground for contrast probes, independently of custom source pixels.
 *
 * The source coordinate View must remain the same for this object's lifetime; recreate the wrapper
 * if the source replaces it. [close] removes the proxy without closing the underlying source. Regions
 * must include every injected surface which could appear in the source; a source which does not
 * exclude surfaces is prohibited if any such surface is inside it.
 */
@MainThread
public class LumenViewportContentSource(
    private val source: LumenContentSource,
    private val regions: (consumer: (View, Float, Float, Float) -> Unit) -> Unit
) : LumenVersionedContentSource, AutoCloseable {
    public constructor(content: View, regions: (consumer: (View, Float, Float, Float) -> Unit) -> Unit) :
        this(ViewContentSource(content), regions)

    private val content = source.coordinateView
    private val versioned = source as? LumenVersionedContentSource
    private val mapping = ViewSamplingMatrix()
    private val proxyMapping = ViewSamplingMatrix()
    private val proxyMatrix = Matrix()
    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val region = RectF()
    private val bounds = ViewportSamplingBounds()
    private var versionSignature = 0L
    private val snapshotVersion: () -> Long = {
        val declared = versioned?.contentVersion ?: 0L
        if (declared < 0L) declared else {
            versionSignature = declared
            mixVersion(bounds.left.toLong()); mixVersion(bounds.top.toLong())
            mixVersion(bounds.right.toLong()); mixVersion(bounds.bottom.toLong())
            // Isolated background sources remain authoritative: the injected surfaces may dirty
            // their owner without changing the pixels supplied by the custom source.
            if (versioned == null || !source.excludesSurfaces) visitVersion(content, 0)
            versionSignature and Long.MAX_VALUE
        }
    }
    private var unsafe = false
    private var syncFailed = false
    private var drawing = false
    private var closed = false
    private val viewport = (content as? ViewGroup)?.let { owner ->
        object : View(owner.context) {
            // OverlayViewGroup has no normal parent chain; View.isShown() otherwise returns false.
            override fun isShown(): Boolean = !closed && owner.isShown

            override fun getMatrix(): Matrix {
                // The overlay also omits its owner's coordinate ancestry. Bridge that ancestry
                // explicitly, then cancel the proxy's ordinary left/top contribution. Use a
                // separate mapper because the outer sampling mapper is currently reading us.
                val overlay = parent as? View
                if (overlay != null && proxyMapping.sourceToTarget(owner, overlay, proxyMatrix)) {
                    proxyMatrix.preTranslate(left.toFloat(), top.toFloat())
                    proxyMatrix.postTranslate(-left.toFloat(), -top.toFloat())
                } else proxyMatrix.reset()
                return proxyMatrix
            }

            override fun onDraw(canvas: Canvas) {
                // Even alpha=0 overlays may be recorded. Hardware re-entry would record the owner
                // recursively; only the software foreground probe needs actual pixels here.
                if (!canvas.isHardwareAccelerated) draw(canvas, foregroundOnly = true)
            }
        }.apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            owner.overlay.add(this)
        }
    }

    override val coordinateView: View = viewport ?: content
    override val observeCoordinateViewInvalidations: Boolean get() = false
    override val dependencies: List<LumenContentSource> = listOf(source)
    override val sourceEpoch: Long get() = versioned?.sourceEpoch ?: 0L
    override val excludesSurfaces: Boolean get() = source.excludesSurfaces
    override val availability: LumenSourceAvailability
        get() = when {
            closed || unsafe || source.coordinateView !== content -> LumenSourceAvailability.PROHIBITED
            syncFailed -> LumenSourceAvailability.TEMPORARILY_UNAVAILABLE
            else -> versioned?.availability ?: LumenSourceAvailability.READY
        }
    override val contentVersion: Long
        get() = mapping.withAncestorMemo(snapshotVersion)

    private fun mixVersion(value: Long) { versionSignature = versionSignature * 31L + value }

    private fun visitVersion(view: View, depth: Int) {
        if (depth >= 128 || view.visibility != View.VISIBLE || view.alpha <= 0f ||
            !mapping.sourceToTarget(view, coordinateView, matrix)) return
        region.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        matrix.mapRect(region)
        val intersects = region.intersects(0f, 0f, coordinateView.width.toFloat(), coordinateView.height.toFloat())
        if (!intersects && (view !is ViewGroup || view.clipChildren)) return
        mixVersion(System.identityHashCode(view).toLong())
        mixVersion(view.width.toLong()); mixVersion(view.height.toLong())
        mixVersion(view.scrollX.toLong()); mixVersion(view.scrollY.toLong()); mixVersion(view.alpha.toBits().toLong())
        // A hardware ViewGroup can remain clean while only its retained descendant is dirty.
        mixVersion(if (view.isDirty) view.drawingTime.coerceAtLeast(0L) else -1L)
        matrix.getValues(matrixValues)
        for (value in matrixValues) mixVersion(value.toBits().toLong())
        if (view is ViewGroup) for (index in 0 until view.childCount) visitVersion(view.getChildAt(index), depth + 1)
    }

    private val consumeRegion: (View, Float, Float, Float) -> Unit = consume@ { view, insetX, insetY, padding ->
        if (!source.excludesSurfaces && isWithin(view, content)) unsafe = true
        if (!view.isShown || view.width <= 0 || view.height <= 0 ||
            !insetX.isFinite() || !insetY.isFinite() || !padding.isFinite() ||
            !mapping.sourceToTarget(view, content, matrix)) return@consume
        val x = insetX.coerceIn(0f, (view.width - 1f) / 2f)
        val y = insetY.coerceIn(0f, (view.height - 1f) / 2f)
        val margin = padding.coerceAtLeast(0f)
        region.set(x - margin, y - margin, view.width - x + margin, view.height - y + margin)
        matrix.mapRect(region)
        bounds.include(region.left, region.top, region.right, region.bottom)
    }

    init { sync() }

    /** Returns whether the sampling bounds changed. A closed source cannot be reattached. */
    public fun sync(): Boolean {
        if (closed) return false
        bounds.clear()
        unsafe = false
        syncFailed = false
        try {
            regions(consumeRegion)
        } catch (_: Exception) {
            syncFailed = true
            return false
        }
        val oldLeft = bounds.left; val oldTop = bounds.top
        val oldRight = bounds.right; val oldBottom = bounds.bottom
        bounds.resolve(content.width, content.height)
        val changed = oldLeft != bounds.left || oldTop != bounds.top ||
            oldRight != bounds.right || oldBottom != bounds.bottom
        if (changed) viewport?.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
        return changed
    }

    override fun drawContent(canvas: Canvas) = draw(canvas, foregroundOnly = false)

    private fun draw(canvas: Canvas, foregroundOnly: Boolean) {
        if (drawing || availability != LumenSourceAvailability.READY) return
        if (viewport != null && !mapping.sourceToTarget(content, viewport, matrix)) return
        drawing = true
        val saved = canvas.save()
        try {
            if (viewport != null) {
                canvas.clipRect(0, 0, viewport.width, viewport.height)
                canvas.concat(matrix)
            } else {
                canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            canvas.clipRect(0, 0, content.width, content.height)
            if (foregroundOnly) LocalViewDrawing.draw(content, canvas) else source.drawContent(canvas)
        } finally {
            canvas.restoreToCount(saved)
            drawing = false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        viewport?.let { (content as ViewGroup).overlay.remove(it) }
    }

    private fun isWithin(view: View, ancestor: View): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < 128) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return current != null
    }
}
