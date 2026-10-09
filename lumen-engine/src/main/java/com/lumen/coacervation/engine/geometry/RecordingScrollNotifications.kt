package com.lumen.coacervation.engine.geometry

/** Retains scroll hosts only until the current synchronous outer source recording finishes. */
internal class RecordingScrollNotifications<T : Any> {
    private val pending = ArrayList<T>()
    var depth = 0
        private set
    val pendingCount: Int get() = pending.size

    fun beginRecord() { depth++ }

    fun defer(host: T) {
        if (depth == 0) return
        for (index in pending.indices) if (pending[index] === host) return
        pending.add(host)
    }

    fun finishRecord(active: Boolean, notify: (T) -> Unit) {
        if (depth == 0) return
        depth--
        if (depth != 0) return
        try {
            if (active) {
                var index = 0
                while (index < pending.size) notify(pending[index++])
            }
        } finally { pending.clear() }
    }

    /** Closing or pausing can drop hosts, while finally still balances any in-progress record. */
    fun clear() { pending.clear() }
}
