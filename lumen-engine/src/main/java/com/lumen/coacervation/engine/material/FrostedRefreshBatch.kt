package com.lumen.coacervation.engine.material

/**
 * 位移通知只记录一次待刷新；pre-draw 之后才发生的变换必须立即补刷，不能等下一帧。
 * draw 只复位阶段标记，不修改 View，也不安排空闲轮询。
 */
internal class FrostedRefreshBatch {
    private var pending = false
    private var preDrawPassed = false

    /** true 表示本帧的 pre-draw 已经过了，调用方必须立刻刷新。 */
    fun request(): Boolean {
        if (preDrawPassed) return true
        pending = true
        return false
    }

    fun beforeDraw(): Boolean {
        preDrawPassed = true
        val refresh = pending
        pending = false
        return refresh
    }

    fun drawn() { preDrawPassed = false }

    fun clear() {
        pending = false
        preDrawPassed = false
    }
}
