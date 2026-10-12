package com.lumen.coacervation.engine.liquid

import android.view.View
import androidx.annotation.MainThread
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Reused per window; the owner remains available when a detached scroll host publishes zero. */
public class LiquidStretchWindowUpdate internal constructor(window: View, public val state: LiquidStretchWindowState) {
    private val owner = WeakReference(window)
    public val window: View? get() = owner.get()
}

/** Shared by native and adapted renderers. Windows never retain each other or share stretch optics. */
@MainThread
public class LiquidStretchWindowRegistry {
    private val windows = WeakHashMap<View, LiquidStretchWindowUpdate>()
    private val hosts = WeakHashMap<View, ScrollHostState>()
    private val windowHosts = WeakHashMap<View, ArrayList<ScrollHostState>>()

    private class ScrollHostState(host: View, window: View) {
        val host = WeakReference(host)
        val window = WeakReference(window)
        var distance = 0f
        var intensity = 1f
        var direction = 0f
    }

    /** Returns the existing mutable state only when a visible sampling/optical step changed. */
    public fun update(window: View, distance: Float, intensity: Float, direction: Float): LiquidStretchWindowState? {
        val entry = entryFor(window.rootView)
        return if (entry.state.update(distance, intensity, direction)) entry.state else null
    }

    /** Each host contributes independently; ending one rebound cannot clear another active host. */
    public fun updateForScrollHost(
        scrollHost: View,
        distance: Float,
        intensity: Float,
        direction: Float
    ): LiquidStretchWindowUpdate? {
        var owner = hosts[scrollHost]
        val previousWindow = owner?.window?.get()
        val window = if (!scrollHost.isAttachedToWindow && previousWindow != null) previousWindow else scrollHost.rootView
        val boundedDistance = if (scrollHost.isAttachedToWindow && distance.isFinite())
            distance.coerceAtLeast(0f) else 0f
        if (owner == null && boundedDistance == 0f) return null
        if (owner != null && previousWindow !== window) {
            windowHosts[previousWindow]?.remove(owner)
            previousWindow?.let { publishHosts(it) }
            hosts.remove(scrollHost)
            owner = null
        }
        if (boundedDistance == 0f) {
            windowHosts[window]?.remove(owner)
            hosts.remove(scrollHost)
        } else {
            if (owner == null) {
                owner = ScrollHostState(scrollHost, window)
                hosts[scrollHost] = owner
                windowHosts.getOrPut(window) { ArrayList() }.add(owner)
            }
            owner.distance = boundedDistance
            owner.intensity = if (intensity.isFinite()) intensity.coerceAtLeast(1f) else 1f
            owner.direction = if (direction.isFinite()) direction.coerceIn(-1f, 1f) else 0f
        }
        return publishHosts(window)
    }

    public fun stateFor(host: View?): LiquidStretchWindowState? = host?.let { windows[it.rootView]?.state }

    private fun entryFor(window: View): LiquidStretchWindowUpdate =
        windows.getOrPut(window) { LiquidStretchWindowUpdate(window, LiquidStretchWindowState()) }

    private fun publishHosts(window: View): LiquidStretchWindowUpdate? {
        val entry = entryFor(window)
        var distance = 0f
        var intensity = 1f
        var top = 0f
        var bottom = 0f
        val owners = windowHosts[window]
        var index = 0
        while (owners != null && index < owners.size) {
            val owner = owners[index]
            if (owner.host.get() == null || owner.window.get() !== window) {
                owners.removeAt(index)
                continue
            }
            distance = maxOf(distance, owner.distance)
            intensity = maxOf(intensity, owner.intensity)
            if (owner.direction < 0f) top = maxOf(top, owner.distance)
            else if (owner.direction > 0f) bottom = maxOf(bottom, owner.distance)
            index++
        }
        val current = when {
            entry.state.edgeDirection < 0f -> LiquidStretchEdge.TOP
            entry.state.edgeDirection > 0f -> LiquidStretchEdge.BOTTOM
            else -> LiquidStretchEdge.NONE
        }
        val direction = when (LiquidStretchOverscrollPolicy.dominantEdge(top, bottom, current)) {
            LiquidStretchEdge.TOP -> -1f
            LiquidStretchEdge.BOTTOM -> 1f
            LiquidStretchEdge.NONE -> 0f
        }
        return if (entry.state.update(distance, intensity, direction)) entry else null
    }

    public fun clear() { hosts.clear(); windowHosts.clear(); windows.clear() }
}
