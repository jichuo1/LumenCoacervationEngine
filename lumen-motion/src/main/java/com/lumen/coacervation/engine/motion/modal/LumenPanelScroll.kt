package com.lumen.coacervation.engine.motion.modal

import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.core.view.NestedScrollingChild3
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport

/** One construction-time pass for typed nested scrolling surfaces in a prepared modal. */
@MainThread
public object LumenPanelScroll {
    public fun prepare(root: View, installStretch: (View) -> View?) {
        if (root is LiquidStretchViewport || root.parent is LiquidStretchViewport) return
        if (root is NestedScrollingChild3 && installStretch(root) != null) return
        if (root is ViewGroup) {
            // Installation replaces a child at the same index with the same LayoutParams.
            for (index in 0 until root.childCount) prepare(root.getChildAt(index), installStretch)
        }
    }
}
