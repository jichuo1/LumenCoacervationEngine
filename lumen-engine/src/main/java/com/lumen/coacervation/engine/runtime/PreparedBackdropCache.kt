package com.lumen.coacervation.engine.runtime

/**
 * Published pixel pairs per renderer kind, never a View/Context or a mutable renderer session.
 * Eviction only drops references: display lists in another window may still use these pixels.
 * Core Soft and Liquid each own one default cache, so their ceiling is 8 MiB, independent of page count.
 * Explicit host presentation budgets are separate and must be included in the host's memory accounting.
 * A Liquid source has two pixel buffers, each bounded by its existing 2 MiB allocation policy.
 *
 * [maxEntries] 默认 1（宿主桥接 `LumenPreparedBackdropCache` 的语义不变）。引擎自己的两个渲染器
 * 用 [ENGINE_ENTRIES] 个 LRU 槽，竖屏、横屏与输入法压缩高度这几种根尺寸来回切换时不必重建；
 * 总字节仍受 [maxBytes] 约束，超出时按最久未用淘汰。
 */
internal class PreparedBackdropCache<K : Any, V : Any>(
    private val maxBytes: Long = MAX_BYTES_PER_RENDERER,
    private val maxEntries: Int = 1
) : LumenMemoryPressureListener, AutoCloseable {
    private class Entry<V>(val value: V, val bytes: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(4, 0.75f, true)
    private var bytes = 0L
    private var closed = false
    val retainedBytes: Long get() = synchronized(lock) { bytes }

    init {
        require(maxBytes in 1L..MAX_HOST_BYTES) { "Backdrop cache budget must be between 1 byte and 34 MiB" }
        require(maxEntries in 1..ENGINE_ENTRIES) { "Backdrop cache keeps between 1 and $ENGINE_ENTRIES entries" }
        LumenMemoryPressureHub.addListener(this)
    }

    fun get(expected: K): V? = synchronized(lock) { entries[expected]?.value }

    fun put(key: K, value: V, byteCount: Long): Boolean = synchronized(lock) {
        if (closed) return false
        if (byteCount <= 0L || byteCount > maxBytes) {
            clear(); return false
        }
        entries.remove(key)?.let { bytes -= it.bytes }
        entries[key] = Entry(value, byteCount)
        bytes += byteCount
        val eldest = entries.entries.iterator()
        while (entries.size > maxEntries || bytes > maxBytes) {
            val evicted = eldest.next()
            if (evicted.key == key) break
            bytes -= evicted.value.bytes
            eldest.remove()
        }
        true
    }

    /** 只丢引用：例如宿主改回自动背景后，释放旧的自定义图片条目。 */
    fun removeIf(predicate: (K) -> Boolean) = synchronized(lock) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) { bytes -= entry.value.bytes; iterator.remove() }
        }
    }

    fun clear() = synchronized(lock) { entries.clear(); bytes = 0L }
    override fun onReleaseGraphics() = clear()
    override fun close() {
        synchronized(lock) { closed = true; clear() }
        LumenMemoryPressureHub.removeListener(this)
    }

    companion object {
        const val MAX_BYTES_PER_RENDERER = 4L * 1024 * 1024
        const val MAX_PROCESS_BYTES = MAX_BYTES_PER_RENDERER * 2
        const val MAX_HOST_BYTES = 34L * 1024 * 1024
        /** 竖屏、横屏、输入法压缩高度：同一配色下根尺寸的真实工作集（PreparedBackdropCacheTest 守住）。 */
        const val ENGINE_ENTRIES = 3
    }
}
