package com.lumen.coacervation.engine.material

/** Main-thread accounting shared across all content sources of a local session. */
internal class LumenSampleBudget(private val limit: Long) {
    var used: Long = 0L
        private set
    fun reserve(bytes: Long): Boolean {
        if (bytes < 0L || bytes > limit - used) return false
        used += bytes
        return true
    }
    fun release(bytes: Long) { used = (used - bytes).coerceAtLeast(0L) }
}
