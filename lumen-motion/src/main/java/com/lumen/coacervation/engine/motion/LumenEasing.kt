package com.lumen.coacervation.engine.motion

import android.view.animation.PathInterpolator

/**
 * 引擎用到的命名缓动曲线（三次贝塞尔控制点）。每次调用返回新实例：`PathInterpolator` 无状态，但各处持有
 * 自己的一份更便于替换。
 *
 * **曲线没有绝对好坏，只有配不配得上行程长度**（来源工程 2026-09-09 真机实测）：
 * [secondaryExpand] `(0.05, 0.7, 0.1, 1)` 用在气泡（短行程 + 缩放）正好，用在居中形变（27dp 图标 → 满屏长对角线）
 * 是灾难——头 26ms 就走掉 41% 行程，飞行段快到看不见；居中形变用的是 [standard] 形的 `(0.4, 0, 0.2, 1)` / 400ms。
 * 新增动画先按行程长度选曲线，别按"看起来更灵动"。
 */
public object LumenEasing {
    /** Material standard deceleration; preserves hosts using (0, 0, 0.2, 1). */
    @JvmStatic public fun standardDecelerate(): PathInterpolator = PathInterpolator(0f, 0f, 0.2f, 1f)

    /** Material standard acceleration; preserves hosts using (0.4, 0, 1, 1). */
    @JvmStatic public fun standardAccelerate(): PathInterpolator = PathInterpolator(0.4f, 0f, 1f, 1f)

    /** 展开、进入：减速收尾（Material 3 emphasized decelerate）。 */
    @JvmStatic public fun emphasizedDecelerate(): PathInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** 收起、离开：加速开始（Material 3 emphasized accelerate）。 */
    @JvmStatic public fun emphasizedAccelerate(): PathInterpolator = PathInterpolator(0.3f, 0f, 1f, 1f)

    /** 通用位移（长行程形变、提示条滑入）。 */
    @JvmStatic public fun standard(): PathInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** 短行程展开（气泡、二级菜单显隐）：快速建立反馈，保留更长的柔和收尾。 */
    @JvmStatic public fun secondaryExpand(): PathInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** 与 [secondaryExpand] 配对的收起。 */
    @JvmStatic public fun secondaryCollapse(): PathInterpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
}
