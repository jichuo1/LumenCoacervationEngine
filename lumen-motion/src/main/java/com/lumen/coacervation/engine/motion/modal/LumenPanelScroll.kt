package com.lumen.coacervation.engine.motion.modal

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.annotation.MainThread
import androidx.core.view.NestedScrollingChild3
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport

/** One construction-time pass for vertical scrolling surfaces in a prepared modal. */
@MainThread
public object LumenPanelScroll {
    public fun prepare(root: View, installStretch: (View) -> View?) {
        if (root !is LiquidStretchViewport && root.parent !is LiquidStretchViewport &&
            (root is NestedScrollingChild3 || root is ScrollView)) {
            installStretch(root)
        }
        if (root is ViewGroup) {
            // Installation replaces a child at the same index with the same LayoutParams.
            for (index in 0 until root.childCount) prepare(root.getChildAt(index), installStretch)
        }
    }
}
