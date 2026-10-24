package com.lumen.coacervation.engine.effects

/** Cumulative configuration work; created only by an explicit main-thread diagnostics request. */
public data class LumenEffectWorkDiagnostics(
    val configureCalls: Long,
    val grainRegenerations: Long,
    val gradientBuilds: Long
)
