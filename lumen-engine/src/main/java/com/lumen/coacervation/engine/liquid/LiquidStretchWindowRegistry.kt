package com.lumen.coacervation.engine.liquid

import android.view.View
import androidx.annotation.MainThread
import java.util.WeakHashMap

/** Shared by native and adapted renderers. Windows never retain each other or share stretch optics. */
@MainThread
public class LiquidStretchWindowRegistry {
    private val windows = WeakHashMap<View, LiquidStretchWindowState>()

    /** Returns the existing mutable state only when a visible sampling/optical step changed. */
    public fun update(window: View, distance: Float, intensity: Float, direction: Float): LiquidStretchWindowState? {
        val state = windows.getOrPut(window.rootView) { LiquidStretchWindowState() }
        return if (state.update(distance, intensity, direction)) state else null
    }

    public fun stateFor(host: View?): LiquidStretchWindowState? = host?.let { windows[it.rootView] }

    public fun clear() { windows.clear() }
}
