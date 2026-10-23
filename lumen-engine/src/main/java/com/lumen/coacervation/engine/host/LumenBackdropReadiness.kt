package com.lumen.coacervation.engine.host

import androidx.annotation.MainThread

/** A background may defer a morph's first visible frame until its custom pixels or stable fallback exist. */
public interface LumenBackdropReadiness {
    /** Prepare a fully skinned first motion frame; ordinary first-load reveals may remain unchanged. */
    @MainThread public fun prepareForMotion() = Unit
    @get:MainThread public val isReadyForMotion: Boolean
}
