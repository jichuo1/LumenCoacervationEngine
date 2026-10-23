package com.lumen.coacervation.engine.runtime

/**
 * One published pixel pair per renderer kind, never a View/Context or a mutable renderer session.
 * Eviction only drops references: display lists in another window may still use these pixels.
 * Soft and Liquid each own one slot, so the process retention ceiling is 8 MiB, independent of page count.
 * A Liquid source has two pixel buffers, each bounded by its existing 2 MiB allocation policy.
 */
internal class PreparedBackdropCache<K : Any, V : Any> : LumenMemoryPressureListener, AutoCloseable {
    private val lock = Any()
    private var key: K? = null
    private var value: V? = null
    private var bytes = 0L
    private var closed = false
    val retainedBytes: Long get() = synchronized(lock) { bytes }

    init { LumenMemoryPressureHub.addListener(this) }

    fun get(expected: K): V? = synchronized(lock) { if (key == expected) value else null }

    fun put(key: K, value: V, byteCount: Long): Boolean = synchronized(lock) {
        if (closed) return false
        if (byteCount <= 0L || byteCount > MAX_BYTES_PER_RENDERER) {
            clear(); return false
        }
        this.key = key; this.value = value; bytes = byteCount
        true
    }

    fun clear() = synchronized(lock) { key = null; value = null; bytes = 0L }
    override fun onReleaseGraphics() = clear()
    override fun close() {
        synchronized(lock) { closed = true; clear() }
        LumenMemoryPressureHub.removeListener(this)
    }

    companion object {
        const val MAX_BYTES_PER_RENDERER = 4L * 1024 * 1024
        const val MAX_PROCESS_BYTES = MAX_BYTES_PER_RENDERER * 2
    }
}
