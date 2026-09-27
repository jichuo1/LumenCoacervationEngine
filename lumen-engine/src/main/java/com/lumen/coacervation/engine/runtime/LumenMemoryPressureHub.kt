package com.lumen.coacervation.engine.runtime

import androidx.annotation.MainThread
import java.util.WeakHashMap

/**
 * 宿主进程内、可丢弃图形资源的弱登记表。
 *
 * 进程级内存告急（例如宿主自己的内存协调器发出的 TRIM）不一定经过 Activity.onTrimMemory，所以不能只靠各
 * Activity 自己的回调。这里只释放高级材质的图形资源；不关页面、不清偏好。宿主经 LumenEngine.releaseGraphics() 触发。
 */
internal fun interface LumenMemoryPressureListener {
    fun onReleaseGraphics()
}

internal object LumenMemoryPressureHub {
    private val lock = Any()
    private val listeners = WeakHashMap<LumenMemoryPressureListener, Unit>()

    fun addListener(listener: LumenMemoryPressureListener) {
        synchronized(lock) { listeners[listener] = Unit }
    }

    fun removeListener(listener: LumenMemoryPressureListener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    @MainThread
    fun releaseGraphics() {
        snapshot().forEach { listener ->
            runCatching { listener.onReleaseGraphics() }
        }
    }


    private fun snapshot(): List<LumenMemoryPressureListener> = synchronized(lock) {
        listeners.keys.toList()
    }
}
