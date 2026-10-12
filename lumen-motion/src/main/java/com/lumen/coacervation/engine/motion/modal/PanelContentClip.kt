package com.lumen.coacervation.engine.motion.modal

import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.view.ScrollingView
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport

/** Layout-time clipping for embedded pages. Never walks the hierarchy from draw or touch. */
internal class PanelContentClip {
    companion object {
        internal fun ownsOutline(view: View): Boolean = view.outlineProvider is EmbeddedOutline
    }
    private val outline = Outline()

    fun applyToHierarchy(view: View) {
        if (view !is ViewGroup) return
        if (view.tag == ElasticInteractionController.CONTAINER_TAG) {
            readBackgroundOutline(view)
            if (outline.radius > 0f) apply(view, outline.radius)
            return
        }
        for (index in 0 until view.childCount) applyToHierarchy(view.getChildAt(index))
    }

    fun apply(panel: ViewGroup, radiusPx: Float) {
        if (!radiusPx.isFinite() || radiusPx <= 0f) return
        for (index in 0 until panel.childCount) visit(panel.getChildAt(index), null, radiusPx)
    }

    private fun visit(view: View, surface: View?, radiusPx: Float) {
        if (view is ScrollView || view is HorizontalScrollView || view is ScrollingView ||
            view is WebView || view is LiquidStretchViewport) {
            // A decorated inline page (e.g. a log page around a RecyclerView) owns all four corners.
            // Do not round the inner list again: its header/footer already belong to that page.
            clip(surface ?: view, radiusPx)
            return
        }
        if (view !is ViewGroup) return
        val decorated = surface ?: view.takeIf { it.background != null }
        for (index in 0 until view.childCount) visit(view.getChildAt(index), decorated, radiusPx)
    }

    private fun clip(view: View, radiusPx: Float) {
        val provider = view.outlineProvider
        if (provider is EmbeddedOutline) {
            if (provider.followsPanel && provider.radiusPx != radiusPx) {
                provider.radiusPx = radiusPx
                view.invalidateOutline()
            }
            return
        }
        // Explicit host outlines/clipping remain authoritative.
        if (view.clipToOutline || provider != null && provider !== ViewOutlineProvider.BACKGROUND) return
        readBackgroundOutline(view)
        val declaredRadius = outline.radius.takeIf { it > 0f } ?: radiusPx
        view.outlineProvider = EmbeddedOutline(declaredRadius, outline.radius <= 0f)
        view.clipToOutline = true
    }

    private fun readBackgroundOutline(view: View) {
        outline.setEmpty()
        val background = view.background ?: return
        // View updates background bounds during draw, after this first layout pass.
        // Empty bounds make GradientDrawable.getOutline return an empty outline even with a radius.
        if (background.bounds.isEmpty && view.width > 0 && view.height > 0) {
            background.setBounds(0, 0, view.width, view.height)
        }
        background.getOutline(outline)
    }

    private class EmbeddedOutline(var radiusPx: Float, val followsPanel: Boolean) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height,
                radiusPx.coerceAtMost(minOf(view.width, view.height).coerceAtLeast(0) * 0.5f))
        }
    }
}
