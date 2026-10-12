package com.lumen.coacervation.engine.interaction

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.widget.LumenSlidingSelection
import kotlin.math.roundToInt

/** Copies one held control outside the retained native clips; the interior is never duplicated. */
internal class ElasticOverflowPortal private constructor(
    private val source: View,
    private val sourceBackdrop: View?,
    private val host: ViewGroup,
    private val bridged: List<View>,
    private val sourceLeft: Float,
    private val sourceTop: Float,
    private val nativeClip: Path,
    private val baselineCrop: Rect?,
    private val visibleBounds: Rect,
    private val sourceOutline: Path?,
    private val backdropOutline: Path?,
    private val clipStamps: List<ClipStamp>,
    private val geometryValid: () -> Boolean,
    onFailure: () -> Unit
) : Drawable() {
    private var attached = false
    private var failed = false
    private val clipProbe = Rect()
    private val outlineProbe = Outline()
    private val failureReceipt = Runnable(onFailure)

    fun bridgesClip(view: View): Boolean = bridged.any { it === view }
    fun bridgesPanelPadding(view: View): Boolean = view === host
    fun copyVisibleBounds(out: Rect) { out.set(visibleBounds) }

    fun ownedClipsValid(): Boolean {
        val expected = baselineCrop.takeIf { attached }
        fun matches(view: View): Boolean = if (expected == null) !view.getClipBounds(clipProbe)
            else view.getClipBounds(clipProbe) && clipProbe == expected
        if (!matches(source) || sourceBackdrop != null && !matches(sourceBackdrop)) return false
        for (index in clipStamps.indices) {
            val stamp = clipStamps[index]
            if (!stamp.matches(clipProbe, outlineProbe, stamp.view !== source && stamp.view !== sourceBackdrop)) return false
        }
        return true
    }

    fun attach() {
        if (attached || failed) return
        if (!ownedClipsValid()) { fail(); return }
        // Install only when the hold owns the gesture. Source pixels previously outside a
        // viewport cannot appear in either the native interior or the overflow copy.
        if (baselineCrop != null) {
            source.clipBounds = baselineCrop
            sourceBackdrop?.clipBounds = baselineCrop
        }
        setBounds(0, 0, host.width, host.height)
        host.overlay.add(this)
        attached = true
    }

    fun detach() {
        if (attached) {
            host.overlay.remove(this)
            if (baselineCrop != null) {
                fun restore(view: View) {
                    if (view.getClipBounds(clipProbe) && clipProbe == baselineCrop) view.clipBounds = null
                }
                restore(source)
                sourceBackdrop?.let(::restore)
            }
        }
        attached = false
    }

    override fun draw(canvas: Canvas) {
        if (!attached || failed) return
        if (!geometryValid()) { fail(); return }
        val saved = canvas.save()
        try {
            if (!canvas.clipRect(0f, 0f, host.width.toFloat(), host.height.toFloat()) ||
                !canvas.clipOutPath(nativeClip)) return
            canvas.translate(sourceLeft + source.translationX, sourceTop + source.translationY)
            canvas.scale(source.scaleX, source.scaleY, source.pivotX, source.pivotY)
            val hasSource = if (baselineCrop != null) canvas.clipRect(baselineCrop)
                else canvas.clipRect(0f, 0f, source.width.toFloat(), source.height.toFloat())
            if (!hasSource) return
            if (sourceBackdrop != null) {
                val backdropSave = canvas.save()
                if (backdropOutline == null || canvas.clipPath(backdropOutline)) sourceBackdrop.draw(canvas)
                canvas.restoreToCount(backdropSave)
            }
            if (sourceOutline != null && !canvas.clipPath(sourceOutline)) return
            source.draw(canvas)
        } catch (_: Throwable) {
            fail()
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun fail() {
        if (failed) return
        failed = true
        host.post(failureReceipt)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private class ClipStamp(val view: View) {
        private val clipsChildBounds = (view.parent as? ViewGroup)?.clipChildren ?: false
        private val clipsPadding = (view as? ViewGroup)?.clipToPadding ?: false
        private val clipsOutline = view.clipToOutline
        private val provider = view.outlineProvider
        private val background = view.background
        private val leftPadding = view.paddingLeft
        private val topPadding = view.paddingTop
        private val rightPadding = view.paddingRight
        private val bottomPadding = view.paddingBottom
        private val outlineBounds = Rect()
        private val outlineClippable: Boolean
        private val outlineRadius: Float
        init {
            val outline = Outline()
            if (clipsOutline) runCatching { view.outlineProvider?.getOutline(view, outline) }
                .onFailure { outline.setEmpty() }
            outlineClippable = outline.canClip()
            outline.getRect(outlineBounds)
            outlineRadius = outline.radius
        }
        fun matches(probe: Rect, outline: Outline, checkBounds: Boolean): Boolean {
            val properties = ((view.parent as? ViewGroup)?.clipChildren ?: false) == clipsChildBounds &&
                ((view as? ViewGroup)?.clipToPadding ?: false) == clipsPadding &&
                view.clipToOutline == clipsOutline && view.outlineProvider === provider &&
                view.paddingLeft == leftPadding && view.paddingTop == topPadding &&
                view.paddingRight == rightPadding && view.paddingBottom == bottomPadding &&
                (!clipsOutline || view.background === background) && (!checkBounds || !view.getClipBounds(probe)) &&
                (view !is LiquidStretchViewport || view.samplingOverscroll == 0f)
            if (!properties || !clipsOutline) return properties
            outline.setEmpty()
            if (runCatching { view.outlineProvider?.getOutline(view, outline) }.isFailure ||
                outline.canClip() != outlineClippable) return false
            return !outlineClippable || outline.getRect(probe) && probe == outlineBounds && outline.radius == outlineRadius
        }
    }

    companion object {
        /** Preparation is bounded and occurs once on DOWN, never during drawing. */
        fun prepare(source: View, root: View, geometryValid: () -> Boolean,
                    onFailure: () -> Unit): ElasticOverflowPortal? {
            val probe = Rect()
            fun plain(view: View): Boolean = view.alpha == 1f && view.scaleX == 1f && view.scaleY == 1f &&
                view.rotation == 0f && view.rotationX == 0f && view.rotationY == 0f &&
                !view.getClipBounds(probe) && view.elevation == 0f && view.translationZ == 0f &&
                view.layerType == View.LAYER_TYPE_NONE
            var visited = 0
            fun drawableTree(view: View, depth: Int = 0): Boolean {
                if (++visited > 128 || depth >= 16 || view is SurfaceView || view is TextureView) return false
                if (view is ViewGroup) {
                    if (view.childCount > 64) return false
                    for (index in 0 until view.childCount) if (!drawableTree(view.getChildAt(index), depth + 1)) return false
                }
                return true
            }
            if (!plain(source) || !drawableTree(source)) return null
            val selection = (source.parent as? View)?.parent as? LumenSlidingSelection
            val backdrop = selection?.overflowBackdrop(source)
            if (backdrop != null && (!plain(backdrop) || !drawableTree(backdrop))) return null
            val ancestors = ArrayList<View>()
            var node = source.parent as? View ?: return null
            var host: ViewGroup? = null
            while (ancestors.size < 32 && node !== root) {
                if (node.tag == ElasticInteractionController.CONTAINER_TAG) { host = node as? ViewGroup; break }
                if (!plain(node)) return null
                if (ElasticClipPolicy.isViewport(node)) {
                    if (node is LiquidStretchViewport) {
                        if (node.samplingOverscroll != 0f || node.scrollX != 0 || node.scrollY != 0) return null
                    } else if ((node !is ScrollView && node !is NestedScrollView) || node is ElasticClipBoundary ||
                        node.scrollX != 0) return null
                }
                ancestors.add(node)
                node = node.parent as? View ?: return null
            }
            val panel = host ?: return null
            if (panel.paddingLeft <= 0 || panel.paddingRight <= 0 || panel.width <= 0 || panel.height <= 0 ||
                panel.scrollX != 0 || panel.scrollY != 0 || !plain(panel)) return null

            val outline = Outline()
            val outlineRect = Rect()
            var readable = true
            fun outlinePath(view: View): Path? {
                if (!view.clipToOutline) return null
                outline.setEmpty()
                if (runCatching { view.outlineProvider?.getOutline(view, outline) }.isFailure) { readable = false; return null }
                if (!outline.canClip()) return null
                if (!outline.getRect(outlineRect) || outline.radius < 0f) { readable = false; return null }
                return Path().apply {
                    addRoundRect(outlineRect.left.toFloat(), outlineRect.top.toFloat(), outlineRect.right.toFloat(),
                        outlineRect.bottom.toFloat(), outline.radius, outline.radius, Path.Direction.CW)
                }
            }
            val sourceShape = outlinePath(source)
            val backdropShape = backdrop?.let(::outlinePath)
            outlinePath(panel)
            if (!readable) return null
            val offsets = FloatArray(ancestors.size * 2)
            var x = 0f
            var y = 0f
            for (index in ancestors.lastIndex downTo 0) {
                val parent = if (index == ancestors.lastIndex) panel else ancestors[index + 1]
                val view = ancestors[index]
                x += view.left + view.translationX - parent.scrollX
                y += view.top + view.translationY - parent.scrollY
                offsets[index * 2] = x
                offsets[index * 2 + 1] = y
            }
            val sourceLeft = source.left + if (ancestors.isEmpty()) -panel.scrollX.toFloat() else offsets[0] - ancestors[0].scrollX
            val sourceTop = source.top + if (ancestors.isEmpty()) -panel.scrollY.toFloat() else offsets[1] - ancestors[0].scrollY
            val native = Path().apply { addRect(0f, 0f, panel.width.toFloat(), panel.height.toFloat(), Path.Direction.CW) }
            val scratch = Path()
            fun intersectRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
                scratch.rewind()
                scratch.addRect(left, top, right, bottom, Path.Direction.CW)
                return native.op(scratch, Path.Op.INTERSECT)
            }
            if (panel.clipToPadding && !intersectRect(panel.paddingLeft.toFloat(), panel.paddingTop.toFloat(),
                    (panel.width - panel.paddingRight).toFloat(), (panel.height - panel.paddingBottom).toFloat())) return null
            val stamps = ArrayList<ClipStamp>(ancestors.size + 3)
            stamps.add(ClipStamp(panel))
            stamps.add(ClipStamp(source))
            backdrop?.let { stamps.add(ClipStamp(it)) }
            for (index in ancestors.indices) {
                val view = ancestors[index]
                val left = offsets[index * 2]
                val top = offsets[index * 2 + 1]
                // clipChildren sets each child's RenderNode clipToBounds; it does not clip
                // the parent's own node. Preserve exactly those fixed intermediate bounds.
                if ((view.parent as? ViewGroup)?.clipChildren == true &&
                    !intersectRect(left, top, left + view.width, top + view.height)) return null
                if (view is ViewGroup && view.clipToPadding && (view.paddingLeft != 0 || view.paddingTop != 0 ||
                        view.paddingRight != 0 || view.paddingBottom != 0) &&
                    !intersectRect(left + view.paddingLeft, top + view.paddingTop,
                        left + view.width - view.paddingRight, top + view.height - view.paddingBottom)) return null
                val shape = outlinePath(view)
                if (!readable) return null
                if (shape != null) {
                    shape.offset(left, top)
                    if (!native.op(shape, Path.Op.INTERSECT)) return null
                }
                stamps.add(ClipStamp(view))
            }
            if (native.isEmpty) return null

            val sourceBounds = Path().apply { addRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), Path.Direction.CW) }
            if (sourceShape != null && !sourceBounds.op(sourceShape, Path.Op.INTERSECT)) return null
            val matrix = Matrix().apply { setTranslate(sourceLeft + source.translationX, sourceTop + source.translationY) }
            if (backdrop != null) {
                val backdropBounds = Path().apply {
                    addRect(0f, 0f, backdrop.width.toFloat(), backdrop.height.toFloat(), Path.Direction.CW)
                }
                if (backdropShape != null && !backdropBounds.op(backdropShape, Path.Op.INTERSECT)) return null
                if (!sourceBounds.op(backdropBounds, Path.Op.UNION)) return null
            }
            sourceBounds.transform(matrix)
            val visible = Path(sourceBounds)
            if (!visible.op(native, Path.Op.INTERSECT) || visible.isEmpty) return null
            val missing = Path(sourceBounds)
            if (!missing.op(native, Path.Op.DIFFERENCE)) return null
            var crop: Rect? = null
            val visibleBounds = RectF()
            visible.computeBounds(visibleBounds, true)
            visibleBounds.offset(-sourceLeft - source.translationX, -sourceTop - source.translationY)
            val candidate = Rect(visibleBounds.left.roundToInt(), visibleBounds.top.roundToInt(),
                visibleBounds.right.roundToInt(), visibleBounds.bottom.roundToInt())
            if (candidate.isEmpty) return null
            if (!missing.isEmpty) {
                // Preserve only crops exactly representable by the public native clipBounds.
                // Unknown paths and a partly cropped rounded corner retain conservative travel.
                scratch.rewind()
                scratch.addRect(candidate.left + sourceLeft + source.translationX, candidate.top + sourceTop + source.translationY,
                    candidate.right + sourceLeft + source.translationX, candidate.bottom + sourceTop + source.translationY, Path.Direction.CW)
                if (!scratch.op(sourceBounds, Path.Op.INTERSECT) || !scratch.op(visible, Path.Op.XOR) || !scratch.isEmpty) return null
                crop = candidate
            }
            return ElasticOverflowPortal(source, backdrop, panel, ancestors, sourceLeft, sourceTop, native, crop, candidate,
                sourceShape, backdropShape, stamps,
                { geometryValid() && selection?.overflowBackdrop(source) === backdrop }, onFailure)
        }
    }
}
