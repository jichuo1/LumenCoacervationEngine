package com.lumen.coacervation.engine.liquid

/**
 * Main-thread, per-renderer cumulative work counters. A snapshot is allocated only on request.
 * Counts describe engine work, not display Hz, elapsed CPU/GPU time or power consumption.
 * No View, content, source path or configuration value is retained in the snapshot.
 */
public data class LiquidWorkDiagnostics(
    val positionNotifications: Long,
    /** Attached-window queue visits, including marks coalesced into an existing batch. */
    val windowsQueued: Long,
    val refreshPasses: Long,
    /** Surface-map entries visited by batched refresh passes, including other windows. */
    val surfaceVisits: Long,
    /** invalidate calls made by batched refresh passes, excluding initial frame triggers. */
    val surfaceInvalidations: Long,
    val captureRequests: Long,
    val capturePixels: Long,
    val identicalCaptures: Long,
    val idleProbeWakeups: Long,
    val refreshConfigureCalls: Long,
    val refreshRestoreCalls: Long,
    val windowPreferenceWrites: Long,
    val idle: Boolean
)
