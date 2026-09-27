package com.lumen.coacervation.engine.motion.reveal

/** 同一时刻只有一个"定位"请求拥有滚动与高亮。替换、完成与生命周期取消都会释放它的工作。 */
internal class RevealRequest {
    private var generation = 0L
    private var release: (() -> Unit)? = null
    val isActive: Boolean get() = release != null

    fun begin(onRelease: () -> Unit): Long {
        cancel()
        release = onRelease
        return generation
    }

    fun owns(token: Long): Boolean = token == generation && release != null

    fun complete(token: Long): Boolean {
        if (!owns(token)) return false
        cancel()
        return true
    }

    fun cancel() {
        generation++
        val previous = release
        release = null
        previous?.invoke()
    }
}
