package com.lumen.coacervation.engine.runtime

import com.lumen.coacervation.engine.host.LumenSourceAvailability
import com.lumen.coacervation.engine.host.LumenVersionedContentSource

/**
 * 单次预绘制内对一个来源图的观察。
 *
 * 根来源的 `contentVersion` 在宿主实现里可能要遍历整棵可见子树并计算签名，而同一次 onPreDraw 里它先被读来
 * 判断内容变化，随后可用性汇总又把追踪列表（根来源总在其中）里每个来源的版本读一遍——根的版本因此在同一
 * 回调内被读了两次。这里让汇总复用已经读到的根版本；其它来源仍按需读取一次。
 *
 * 不做「同一时间戳的一帧只读一次」的粗缓存：版本只在**单次回调内**共享，跨回调、跨监听器每次重读，
 * 布局、视口或异步 tile 发布造成的变化不会被旧值遮住。
 */
internal object LumenSourceObservation {
    /** 尚未读到根版本（或读取失败）；汇总时对根重新读取，保持原有的异常 / 负值语义。 */
    const val NO_VERSION: Long = Long.MIN_VALUE

    /**
     * 汇总可用性。[root] 与 [rootVersion] 是本次回调已读到的根及其版本，传 [NO_VERSION] 表示没有可复用的值。
     * 任一来源的 epoch / 版本为负或读取抛出都视为暂时不可用；第一个非 READY 的来源决定结果。
     */
    fun availability(
        tracked: List<LumenVersionedContentSource>,
        root: LumenVersionedContentSource? = null,
        rootVersion: Long = NO_VERSION
    ): LumenSourceAvailability {
        return try {
            for (index in 0 until tracked.size) {
                val current = tracked[index]
                // 读取顺序与短路保持原样：先 epoch，epoch 为负就不再读版本。
                if (current.sourceEpoch < 0L) return LumenSourceAvailability.TEMPORARILY_UNAVAILABLE
                val version = if (current === root && rootVersion != NO_VERSION) rootVersion else current.contentVersion
                if (version < 0L) return LumenSourceAvailability.TEMPORARILY_UNAVAILABLE
                if (current.availability != LumenSourceAvailability.READY) return current.availability
            }
            LumenSourceAvailability.READY
        } catch (_: Throwable) {
            LumenSourceAvailability.TEMPORARILY_UNAVAILABLE
        }
    }
}
