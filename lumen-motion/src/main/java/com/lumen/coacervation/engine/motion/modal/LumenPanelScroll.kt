package com.lumen.coacervation.engine.motion.modal

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.annotation.MainThread
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.ScrollingView
import androidx.core.widget.NestedScrollView
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.motion.R

/**
 * Prepares vertical scrolling surfaces in a modal. Typed scroll hosts whose axis is not yet
 * available are checked after layout; horizontal and two-axis hosts retain platform overscroll.
 * A pending check ends when the axis is known or the host detaches. Call [prepare] again to
 * discover inserted hosts or recheck an unwrapped host whose axis changed. An installed
 * viewport retains its vertical contract; preparation does not convert or unwrap it.
 */
@MainThread
public object LumenPanelScroll {
    public fun prepare(root: View, installStretch: (View) -> View?) {
        if (root !is LiquidStretchViewport && root.parent !is LiquidStretchViewport &&
            (root is NestedScrollingChild3 || root is ScrollView)) {
            when (axis(root)) {
                Axis.VERTICAL -> {
                    (root.getTag(R.id.lumen_panel_pending_scroll) as? PendingScroll)?.dispose()
                    installStretch(root)
                }
                Axis.HORIZONTAL ->
                    (root.getTag(R.id.lumen_panel_pending_scroll) as? PendingScroll)?.dispose()
                Axis.UNKNOWN -> {
                    val pending = root.getTag(R.id.lumen_panel_pending_scroll) as? PendingScroll
                    if (pending == null) PendingScroll(root, installStretch).observe()
                    else pending.installStretch = installStretch
                }
            }
        }
        prepareChildren(root, installStretch)
    }

    private fun prepareChildren(root: View, installStretch: (View) -> View?) {
        if (root is ViewGroup) {
            // Installation replaces a child at the same index with the same LayoutParams.
            for (index in 0 until root.childCount) prepare(root.getChildAt(index), installStretch)
        }
    }

    private enum class Axis { VERTICAL, HORIZONTAL, UNKNOWN }

    private fun axis(view: View): Axis {
        // These hosts have a vertical contract even before their first layout or with no rows.
        if (view is ScrollView || view is NestedScrollView) return Axis.VERTICAL
        val scrolling = view as? ScrollingView
        val horizontal = view.canScrollHorizontally(-1) || view.canScrollHorizontally(1) ||
            (scrolling != null && (scrolling.computeHorizontalScrollRange() > 0 ||
                scrolling.computeHorizontalScrollExtent() > 0 || scrolling.computeHorizontalScrollOffset() != 0))
        // The viewport only owns Y. A two-axis host must retain its native edge treatment.
        if (horizontal) return Axis.HORIZONTAL
        val vertical = view.canScrollVertically(-1) || view.canScrollVertically(1) ||
            (scrolling != null && (scrolling.computeVerticalScrollRange() > 0 ||
                scrolling.computeVerticalScrollExtent() > 0 || scrolling.computeVerticalScrollOffset() != 0))
        return if (vertical) Axis.VERTICAL else Axis.UNKNOWN
    }

    private class PendingScroll(
        private val view: View,
        var installStretch: (View) -> View?
    ) : ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        private var observer: ViewTreeObserver? = null
        private var disposed = false

        fun observe() {
            view.setTag(R.id.lumen_panel_pending_scroll, this)
            view.addOnAttachStateChangeListener(this)
            observeCurrentTree()
        }

        private fun observeCurrentTree() {
            if (disposed) return
            val current = view.viewTreeObserver
            if (observer === current) return
            observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
            // A floating observer is merged into the window observer during initial attach.
            current.removeOnGlobalLayoutListener(this)
            current.addOnGlobalLayoutListener(this)
            observer = current
        }

        override fun onGlobalLayout() {
            // ViewTreeObserver dispatches a snapshot, so removal can precede this queued call.
            if (disposed) return
            if (view.parent is LiquidStretchViewport) {
                dispose()
                return
            }
            val resolved = axis(view)
            if (resolved == Axis.UNKNOWN) return
            // Installation reparents the host and may detach it. Remove our observers first;
            // a failed installation must not leave an automatic retry on every layout.
            dispose()
            if (resolved == Axis.VERTICAL) {
                // A deferred callback cannot be caught by prepare's original caller. The
                // installer's rollback remains authoritative; leave a failed host unchanged.
                val currentInstaller = installStretch
                val installSafely: (View) -> View? = { target -> runCatching { currentInstaller(target) }.getOrNull() }
                val installed = installSafely(view) ?: return
                // Reparenting also detaches inline scroll hosts, ending their old pending
                // checks. Prepare that subtree again so the nearest viewport still owns Y.
                prepareChildren(view, installSafely)
                // A wrapper added after layout needs the next traversal before its first draw.
                // This listener holds only the observer and removes itself on that same frame.
                val tree = installed.viewTreeObserver
                tree.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (tree.isAlive) tree.removeOnPreDrawListener(this)
                        return false
                    }
                })
            }
        }

        override fun onViewAttachedToWindow(view: View) = observeCurrentTree()

        override fun onViewDetachedFromWindow(view: View) = dispose()

        fun dispose() {
            if (disposed) return
            disposed = true
            observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
            val current = view.viewTreeObserver
            if (current !== observer && current.isAlive) current.removeOnGlobalLayoutListener(this)
            observer = null
            view.removeOnAttachStateChangeListener(this)
            if (view.getTag(R.id.lumen_panel_pending_scroll) === this) {
                view.setTag(R.id.lumen_panel_pending_scroll, null)
            }
        }
    }
}
