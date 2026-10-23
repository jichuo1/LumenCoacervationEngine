package com.lumen.coacervation.engine.host

import androidx.annotation.MainThread

/** A background may defer a morph's first visible frame until its custom pixels or stable fallback exist. */
public interface LumenBackdropReadiness {
    @get:MainThread public val isReadyForMotion: Boolean
}
