package com.lumen.coacervation.engine.motion.reveal

/** 同一时刻只有一个"定位"请求拥有滚动与高亮。替换、完成与生命周期取消都会释放它的工作。 */
public class RevealRequest {
    private var generation = 0L
    private var release: (() -> Unit)? = null
    private var completed = false
    private var completedToken = 0L
    val isActive: Boolean get() = release != null

    fun begin(onRelease: () -> Unit): Long {
        cancel()
        release = onRelease
        return generation
    }

    fun owns(token: Long): Boolean = token == generation && release != null

    fun complete(token: Long): Boolean {
        if (!owns(token)) return false
        releaseCurrent(markCompleted = true)
        return true
    }

    internal fun completeWhenSettled(token: Long, verticalSettled: Boolean, horizontalSettled: Boolean): Boolean =
        verticalSettled && horizontalSettled && complete(token)

    /** Completion callbacks are posted; a later cancellation/replacement invalidates them too. */
    internal fun isCompleted(token: Long): Boolean = completed && completedToken == token && release == null

    fun cancel() = releaseCurrent(markCompleted = false)

    private fun releaseCurrent(markCompleted: Boolean) {
        val token = generation
        generation++
        val previous = release
        release = null
        // Record before invoking user code: a reentrant begin/cancel revokes this handoff.
        completed = markCompleted
        completedToken = token
        previous?.invoke()
    }
}
