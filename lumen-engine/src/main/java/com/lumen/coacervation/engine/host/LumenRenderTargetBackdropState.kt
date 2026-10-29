package com.lumen.coacervation.engine.host

/** Separate from the released surface diagnostics ABI; GPU still includes both GPU implementations. */
public data class LumenRenderTargetBackdropState(
    val requested: Boolean,
    val active: Boolean,
    val failed: Boolean,
    val draws: Long
)
