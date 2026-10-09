package com.lumen.coacervation.engine.geometry

/** A scroll offset moves descendants, never the scroll host itself or a neighbouring page. */
internal object ScrollSurfaceScope {
    inline fun <T : Any> contains(surface: T, scrollHost: T, parentOf: (T) -> T?): Boolean {
        if (surface === scrollHost) return false
        var ancestor = parentOf(surface)
        while (ancestor != null) {
            if (ancestor === scrollHost) return true
            ancestor = parentOf(ancestor)
        }
        return false
    }
}
