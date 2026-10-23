package com.lumen.coacervation.engine.host

import com.lumen.coacervation.engine.runtime.PreparedBackdropCache

/**
 * Optional bridge for host-owned backdrop renderers. Keep one cache per renderer kind, never per page.
 * Keys must be immutable configuration snapshots; values may contain only already published pixels
 * and independent drawing state, never Views, Activities, Contexts or closures capturing them.
 * Each cache retains one entry of at most 4 MiB. Account for every pixel buffer in [put].
 * Eviction/graphics pressure only drops references; it never recycles or closes published pixels.
 */
public class LumenPreparedBackdropCache<K : Any, V : Any> : AutoCloseable {
    private val cache = PreparedBackdropCache<K, V>()
    public val retainedBytes: Long get() = cache.retainedBytes
    public fun get(key: K): V? = cache.get(key)
    public fun put(key: K, value: V, byteCount: Long): Boolean = cache.put(key, value, byteCount)
    public fun clear() = cache.clear()
    override fun close() = cache.close()
}
