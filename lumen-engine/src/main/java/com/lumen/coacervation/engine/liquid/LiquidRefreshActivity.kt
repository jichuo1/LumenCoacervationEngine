package com.lumen.coacervation.engine.liquid

/** A fallback probe samples pixels without taking ownership of a high refresh-rate preference. */
internal class LiquidRefreshActivity {
    enum class Phase { ACTIVE, IDLE, PROBING }

    var phase = Phase.ACTIVE
        private set
    private var restoredDrawPending = false

    fun preferenceRestored(changed: Boolean) { restoredDrawPending = changed }

    /** WindowManager can redraw solely because we returned preferred Hz; verify pixels first. */
    fun consumeRestoredDraw(): Boolean {
        val pending = restoredDrawPending
        restoredDrawPending = false
        return pending
    }

    /** Only an active owner needs to restore its preference when entering idle. */
    fun enterIdle(): Boolean {
        val release = phase == Phase.ACTIVE
        // Some windows apply preferred Hz without drawing. A completed unchanged probe expires
        // that old redraw hint; a later real draw must acquire the current preference immediately.
        if (phase == Phase.PROBING) restoredDrawPending = false
        phase = Phase.IDLE
        return release
    }

    fun beginProbe(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.PROBING
        return true
    }

    /** Returns whether the caller must configure the current display/thermal/throughput target. */
    fun wake(): Boolean {
        val acquire = phase != Phase.ACTIVE
        phase = Phase.ACTIVE
        restoredDrawPending = false
        return acquire
    }

    /** Lifecycle teardown resets scheduling state without acquiring a window preference. */
    fun reset() { phase = Phase.ACTIVE; restoredDrawPending = false }
}
