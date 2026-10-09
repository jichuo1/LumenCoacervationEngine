package com.lumen.coacervation.engine.host

import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.glow.GlowContentProbe
import com.lumen.coacervation.engine.glow.GlowContentSample
import com.lumen.coacervation.engine.glow.GlowLegibilityPolicy
import com.lumen.coacervation.engine.glow.GlowSurfaceOptics
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Local dark-glass compensation for hosts whose native foreground must remain unchanged.
 * Samples at most 8 Hz, uses the engine's content statistics and restores base options on close.
 * Close together with the source region's surface session; no Activity or Window is taken over.
 */
@MainThread
public class LumenSurfaceLegibilityController(private val source: View, private val surfaceColor: Int,
    private val foreground: Int, private val ceiling: Float = .92f) : AutoCloseable {
    private class Entry(val binding: LumenSurfaceBinding, var options: LumenSurfaceOptions) {
        var from = options.tintOpacity * 255f
        var target = from.roundToInt()
        var started = 0L
        var sample: GlowContentSample? = null
        var applied = Float.NaN
    }
    private val entries = WeakHashMap<View, Entry>()
    private val location = IntArray(2)
    private val observer = source.viewTreeObserver
    private var lastProbe = 0L
    private var failed = false
    private var closed = false
    private var probe: GlowContentProbe? = null
    private var pending: List<Entry> = emptyList()
    private val preDraw = ViewTreeObserver.OnPreDrawListener { frame(); true }

    init {
        require(ceiling.isFinite() && ceiling in 0f..1f)
        observer.addOnPreDrawListener(preDraw)
    }

    public fun bind(view: View, binding: LumenSurfaceBinding, options: LumenSurfaceOptions) {
        val color = options.color ?: surfaceColor
        if (closed || !options.sampling.enabled || !options.tintEnabled || GlowLegibilityPolicy.encodedLuma(color) >= .5f) {
            entries.remove(view)
            return
        }
        val previous = entries[view]
        if (previous == null || previous.binding !== binding || previous.options.tintOpacity != options.tintOpacity || previous.options.color != options.color)
            entries[view] = Entry(binding, options)
        else { previous.options = options; previous.applied = Float.NaN }
    }

    private fun frame() {
        if (closed || !source.isAttachedToWindow || !source.isShown) return
        val now = SystemClock.uptimeMillis()
        val visible = entries.entries.filter { (view, entry) ->
            view.isAttachedToWindow && view.isShown && view.alpha > 0f && entry.binding.isBound
        }
        for ((view, entry) in visible) {
            val color = entry.options.color ?: surfaceColor
            val base = (entry.options.tintOpacity * 255f).roundToInt()
            val optics = GlowSurfaceOptics(color, base / 255f, ceiling.coerceAtLeast(base / 255f), 1f)
            val boost = entry.sample?.let { GlowLegibilityPolicy.target(it, foreground, optics).boost } ?: 0f
            val desired = ((optics.baseTintAlpha + (optics.maxTintAlpha - optics.baseTintAlpha) * boost) * 255f).roundToInt()
            val current = entry.from + (entry.target - entry.from) * ((now - entry.started) / 240f).coerceIn(0f, 1f)
            val hysteresis = (ceiling * 255f - base) * GlowLegibilityPolicy.HYSTERESIS
            if (desired != entry.target && (abs(desired - entry.target) >= hysteresis || desired == base || desired >= (ceiling * 255f).toInt())) {
                entry.from = current; entry.target = desired; entry.started = now
            }
            val opacity = current.roundToInt().coerceIn(0, 255) / 255f
            if (entry.applied != opacity) {
                entry.binding.update(entry.options.copy(tintOpacity = opacity, fallbackTintOpacity = opacity))
                entry.applied = opacity
            }
            if (now - entry.started < 240L) view.postInvalidateOnAnimation()
        }
        if (failed || visible.isEmpty() || now - lastProbe < 125L || probe?.inFlight == true) return
        source.getLocationOnScreen(location)
        val sourceX = location[0]; val sourceY = location[1]
        val regions = visible.map { (view, entry) ->
            view.getLocationOnScreen(location)
            val shape = entry.binding.samplingRegions()?.shapeBounds
            val rect = if (shape != null) RectF(shape.left, shape.top, shape.right, shape.bottom)
                else RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            rect.offset((location[0] - sourceX).toFloat(), (location[1] - sourceY).toFloat())
            rect
        }
        val sampler = probe ?: GlowContentProbe { samples ->
            pending.forEachIndexed { index, entry -> entry.sample = samples.getOrNull(index) }
            pending = emptyList()
            entries.keys.forEach { it.postInvalidateOnAnimation() }
        }.also { probe = it }
        pending = visible.map { it.value }
        lastProbe = now
        runCatching { sampler.probe(source, regions) { canvas, _ -> canvas.drawColor(surfaceColor) } }
            .onFailure { failed = true; probe?.close(); probe = null; pending = emptyList() }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (observer.isAlive) observer.removeOnPreDrawListener(preDraw)
        // A floating observer can have merged into the live window observer after attach.
        source.viewTreeObserver.takeIf { it !== observer && it.isAlive }?.removeOnPreDrawListener(preDraw)
        probe?.close(); probe = null; pending = emptyList()
        entries.values.forEach { if (it.binding.isBound) it.binding.update(it.options) }
        entries.clear()
    }
}
