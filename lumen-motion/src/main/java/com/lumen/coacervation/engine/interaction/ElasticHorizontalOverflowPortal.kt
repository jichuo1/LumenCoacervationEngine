package com.lumen.coacervation.engine.interaction

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

/**
 * Draw only one held control's horizontal overflow into its panel's padding.
 * Native drawing still owns the viewport interior, including the original alpha,
 * overlay and material. The portal neither reparents nor hides the source View.
 * No bitmap, offscreen layer, idle clock or ancestor clip flag is needed.
 */
internal class ElasticHorizontalOverflowPortal private constructor(
    private val source: View,
    private val host: ViewGroup,
    private val bridged: List<View>,
    private val sourceLeft: Float,
    private val sourceTop: Float,
    private val viewportLeft: Float,
    private val viewportRight: Float,
    private val clipTop: Float,
    private val clipBottom: Float,
    private val geometryValid: () -> Boolean,
    onFailure: () -> Unit
) : Drawable() {
    private var attached = false
    private var failed = false
    private val failureReceipt = Runnable(onFailure)

    fun bridgesHorizontalClip(view: View): Boolean = bridged.any { it === view }
    fun bridgesPanelPadding(view: View): Boolean = view === host

    fun attach() {
        if (attached || failed) return
        setBounds(0, 0, host.width, host.height)
        host.overlay.add(this)
        attached = true
    }

    fun detach() {
        if (attached) host.overlay.remove(this)
        attached = false
    }

    override fun draw(canvas: Canvas) {
        if (!attached || failed) return
        if (!geometryValid()) {
            failed = true
            host.post(failureReceipt)
            return
        }
        val saved = canvas.save()
        try {
            // These are panel coordinates, independent of the source's transform.
            // Preserve vertical scrolling and draw no duplicate interior pixels.
            canvas.clipRect(0f, clipTop, host.width.toFloat(), clipBottom)
            canvas.clipOutRect(viewportLeft, clipTop, viewportRight, clipBottom)
            canvas.translate(sourceLeft + source.translationX, sourceTop + source.translationY)
            canvas.scale(source.scaleX, source.scaleY, source.pivotX, source.pivotY)
            canvas.clipRect(0f, 0f, source.width.toFloat(), source.height.toFloat())
            source.draw(canvas)
        } catch (_: Throwable) {
            failed = true
            // Drawing is not an application callback stack. A superseded owner
            // is checked by the controller when this bounded failure is delivered.
            host.post(failureReceipt)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Unknown custom viewports and clipping paths remain hard boundaries. */
        fun prepare(source: View, root: View, geometryValid: () -> Boolean,
                    onFailure: () -> Unit): ElasticHorizontalOverflowPortal? {
            fun plain(view: View): Boolean = view.alpha == 1f && view.scaleX == 1f && view.scaleY == 1f &&
                view.rotation == 0f && view.rotationX == 0f && view.rotationY == 0f &&
                !view.clipToOutline && view.clipBounds == null && view.elevation == 0f &&
                view.translationZ == 0f && view.layerType == View.LAYER_TYPE_NONE
            var visited = 0
            fun drawableTree(view: View, depth: Int = 0): Boolean {
                if (++visited > 128 || depth >= 16 || view is SurfaceView || view is TextureView) return false
                if (view is ViewGroup) {
                    if (view.childCount > 64) return false
                    for (index in 0 until view.childCount) {
                        if (!drawableTree(view.getChildAt(index), depth + 1)) return false
                    }
                }
                return true
            }
            if (!plain(source) || !drawableTree(source)) return null
            val ancestors = ArrayList<View>()
            var node = source.parent as? View ?: return null
            var viewport: ScrollView? = null
            var host: ViewGroup? = null
            while (ancestors.size < 32 && node !== root) {
                if (node.tag == ElasticInteractionController.CONTAINER_TAG) {
                    host = node as? ViewGroup
                    break
                }
                if (!plain(node)) return null
                if (ElasticClipPolicy.isViewport(node)) {
                    if (viewport != null || node !is ScrollView || node is ElasticClipBoundary || node.scrollX != 0) return null
                    viewport = node
                }
                ancestors.add(node)
                node = node.parent as? View ?: return null
            }
            val panel = host ?: return null
            val scroll = viewport ?: return null
            val paddedViewport = scroll.clipToPadding && (scroll.paddingLeft != 0 || scroll.paddingTop != 0 ||
                scroll.paddingRight != 0 || scroll.paddingBottom != 0)
            if ((!scroll.clipChildren && !paddedViewport) || scroll.childCount != 1) return null
            val content = scroll.getChildAt(0)
            val innerLeft = scroll.paddingLeft.toFloat()
            val innerRight = (scroll.width - scroll.paddingRight).toFloat()
            // Native clipping must agree with the strip excluded from the copy.
            // Tight wrap-content children and already unclipped viewports retain
            // their original drawing rather than acquiring duplicate pixels.
            if (content.left + content.translationX != innerLeft ||
                content.right + content.translationX != innerRight) return null
            // An explicit local panel provides the horizontal room. A full-window
            // wrapper, a viewport alone, or a panel without inset does not opt in.
            if (panel.paddingLeft <= 0 || panel.paddingRight <= 0 ||
                panel.width <= 0 || panel.height <= 0 || panel.scrollX != 0 || panel.scrollY != 0) return null
            val viewportIndex = ancestors.indexOf(scroll)
            var x = source.left.toFloat()
            var y = source.top.toFloat()
            var top = 0f
            var bottom = panel.height.toFloat()
            if (panel.clipToPadding) {
                top = panel.paddingTop.toFloat()
                bottom -= panel.paddingBottom
            }
            var viewportLeft = 0f
            var viewportRight = 0f
            var sourceInViewport = 0f
            // First derive each ancestor origin in panel coordinates. This array
            // lives only for preparation; drawing uses the fixed scalar geometry.
            val offsets = FloatArray(ancestors.size * 2)
            var px = 0f
            var py = 0f
            for (index in ancestors.lastIndex downTo 0) {
                val parent = if (index == ancestors.lastIndex) panel else ancestors[index + 1]
                val view = ancestors[index]
                px += view.left + view.translationX - parent.scrollX
                py += view.top + view.translationY - parent.scrollY
                offsets[index * 2] = px
                offsets[index * 2 + 1] = py
            }
            x += offsets[0] - ancestors[0].scrollX
            y += offsets[1] - ancestors[0].scrollY
            for (index in viewportIndex until ancestors.size) {
                val view = ancestors[index] as? ViewGroup ?: continue
                val left = offsets[index * 2]
                val upper = offsets[index * 2 + 1]
                if (view.clipChildren) {
                    top = maxOf(top, upper)
                    bottom = minOf(bottom, upper + view.height)
                }
                val padded = view.clipToPadding && (view.paddingLeft != 0 || view.paddingTop != 0 ||
                    view.paddingRight != 0 || view.paddingBottom != 0)
                if (padded) {
                    top = maxOf(top, upper + view.paddingTop)
                    bottom = minOf(bottom, upper + view.height - view.paddingBottom)
                }
                if (view === scroll) {
                    viewportLeft = left + if (padded) view.paddingLeft else 0
                    viewportRight = left + view.width - if (padded) view.paddingRight else 0
                    sourceInViewport = x + source.translationX - viewportLeft
                }
            }
            if (viewportLeft <= 0f || viewportRight >= panel.width || clipInvalid(top, bottom) ||
                sourceInViewport < 0f || sourceInViewport + source.width > viewportRight - viewportLeft) return null
            return ElasticHorizontalOverflowPortal(source, panel, ancestors.subList(viewportIndex, ancestors.size),
                x, y, viewportLeft, viewportRight, top, bottom, geometryValid, onFailure)
        }

        private fun clipInvalid(top: Float, bottom: Float): Boolean = !top.isFinite() || !bottom.isFinite() || top >= bottom
    }
}
