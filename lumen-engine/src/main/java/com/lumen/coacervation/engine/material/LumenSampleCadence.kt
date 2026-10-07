package com.lumen.coacervation.engine.material

/** A surface keeps its own stale frame until its processing interval is due. */
internal class LumenSampleCadence {
    var pending = true
        private set
    private var lastSampleNanos = 0L
    private var hasSample = false

    fun invalidate() { pending = true }
    fun discardPending() { pending = false }
    fun reset() { hasSample = false; pending = true }
    fun sampled(now: Long) { lastSampleNanos = now; hasSample = true; pending = false }

    fun remainingMs(now: Long, intervalMs: Long): Long {
        if (!pending) return Long.MAX_VALUE
        if (!hasSample) return 0L
        val remaining = intervalMs * 1_000_000L - (now - lastSampleNanos)
        return if (remaining <= 0L) 0L else (remaining + 999_999L) / 1_000_000L
    }
}
