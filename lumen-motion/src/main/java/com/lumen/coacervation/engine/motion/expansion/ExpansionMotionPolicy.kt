package com.lumen.coacervation.engine.motion.expansion

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 手风琴分节（「兼容」「外观」「净化/增强进阶」）展开/收起的运动学策略层。
 *
 * 全部状态只有一个进度标量 p ∈ [0,1]：0 = 收起视觉，1 = 展开视觉。
 * 动画期间布局始终保持展开态，兄弟控件与卡片的"收起位置"由 translationY/clipBounds
 * 视觉补偿表达；p→0 收尾时 GONE 触发的重布局与同帧 offsets 复位互相抵消——
 * 布局跳变被完全吸收，任意时刻反转都从当前 p 与速度续跑，无 ghost、无瞬移。
 *
 * 与 [AdaptiveGlowPolicy]/[ElasticMotionPolicy] 同风格：纯 Kotlin、不碰 android 类型，
 * 可在 JVM 单测里直接跑。
 */
internal object ExpansionMotionPolicy {

    /** 弹簧刚度（p/s² 单位制）。~300 → ω≈17 rad/s，整段 settle ≈0.3s。 */
    const val STIFFNESS = 300f

    /**
     * 阻尼比。略低于 1 让展开尾端有极轻的过冲（兄弟控件跟随微沉回弹），
     * 符合 M3 Expressive 空间弹簧取向；不够克制时调高到 1。
     */
    const val DAMPING_RATIO = 0.94f

    /**
     * 静止判定：|p-target| 与 |v| 同时低于阈值即收敛。
     * 阈值不能太紧——近零段弹簧以亚像素速度爬行，卡片会顶着 clip 边"冻结"
     * 几百毫秒后才触发收尾 GONE，读作"截断态滞留后跳变"（2026-09-22 真机实测
     * f58→f73 冻结 ~450ms）。0.003 对应 ~7px 残差（2400px 内容），同帧收尾吸收。
     */
    const val REST_P = 0.003f
    const val REST_V = 0.06f

    /** 收尾残差的像素上界：最后一帧的一次性位移不得超过它。 */
    const val REST_TOLERANCE_PX = 1f

    /** 收尾速度的像素上界（px/s）：60Hz 下约 1px/帧。 */
    const val REST_SPEED_PX_PER_SECOND = 60f

    /** 阈值下界，防止超长行程把弹簧拖成无限爬行。 */
    const val MIN_REST_P = 0.0002f
    const val MIN_REST_V = 0.004f

    /**
     * 归一化阈值必须按**这次真实行程**换算（2026-09-22 真机实测）。
     *
     * `REST_P` 是 p 上的量，乘上行程才是像素：视角跟随让 p 同时驱动 1700px 的滚动
     * 位移，0.003 × 1700 ≈ 5px——最后一帧一次性走掉 5px，而此前每帧只走 1px，读作
     * "落定又顿一下"。短行程（分节只有几百 px）换算出来仍高于 `REST_P`，被钳回原值，
     * 行为逐字不变，不会把小分节拖成长尾。
     */
    fun restThresholds(travelPx: Float): Pair<Float, Float> {
        if (!travelPx.isFinite() || travelPx <= 0f) return REST_P to REST_V
        val p = (REST_TOLERANCE_PX / travelPx).coerceIn(MIN_REST_P, REST_P)
        val v = (REST_SPEED_PX_PER_SECOND / travelPx).coerceIn(MIN_REST_V, REST_V)
        return p to v
    }

    /** 文字行显影羽化带宽度（dp）：揭示沿扫过行顶后，该行在此行程内完成显现。 */
    const val FEATHER_DP = 22f

    /** 行显现时的上浮归位幅度（dp）。 */
    const val ROW_SETTLE_DP = 6f

    /** 内容整体在展开过程中的轻微上浮（dp）。 */
    const val CONTENT_RISE_DP = 8f

    /**
     * 折叠牌堆的行距（dp）：收起时各行向内容顶部差分折叠，相邻行顶间距压到
     * 该值——每行只露出顶部一条"牌边"，行体依次叠盖，读作一摞收拢的卡片。
     */
    const val ROW_STACK_PEEK_DP = 18f

    /** 箭头全程转角。 */
    const val CHEVRON_DEGREES = 180f

    /** 单帧最大步长（秒）：掉帧/后台恢复时钳制 dt，防止弹簧爆发。 */
    const val MAX_STEP_SECONDS = 0.05f

    /**
     * 归一化进度上的阻尼弹簧。p 为位置、v 为速度（p/秒）、target 为目标值。
     * 半隐式欧拉积分：先更新速度再更新位置，帧率不稳时也保持能量形态。
     * [step] 返回 true 表示已静止并精确落在目标上。
     */
    internal class Spring(
        var p: Float = 0f,
        var v: Float = 0f,
        var target: Float = 0f
    ) {
        /** 本轮的静止阈值，由 [restThresholds] 按真实像素行程换算；默认即原常量。 */
        var restP: Float = REST_P
        var restV: Float = REST_V

        fun adoptTravel(travelPx: Float) {
            val (p, v) = restThresholds(travelPx)
            restP = p
            restV = v
        }

        fun step(dtSeconds: Float): Boolean {
            val dt = dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)
            val damping = 2f * DAMPING_RATIO * sqrt(STIFFNESS)
            v += (-STIFFNESS * (p - target) - damping * v) * dt
            p += v * dt
            if (abs(p - target) < restP && abs(v) < restV) {
                p = target
                v = 0f
                return true
            }
            return false
        }
    }

    /** 线性空间进度：弹簧输出本身即空间曲线，不做二次缓动以免双重软化。 */
    fun spatial(p: Float): Float = p

    /**
     * 内容整体淡入曲线：前 ~55% 行程完成显现，尾段留给位移归位，
     * 避免"p 快结束时文字还在淡入"的拖尾感。
     */
    fun contentAlpha(p: Float): Float = (p / 0.55f).coerceIn(0f, 1f)

    /**
     * 行级联显影：揭示沿 clipY（content 绘制坐标）扫过行顶 drawnTop 后，
     * 该行在 featherPx 行程内线性显现；沿以下/以上为 1/0。
     * 纯几何——行高不一、收起反向、中途打断全部自动正确。
     */
    fun rowReveal(clipY: Float, drawnTop: Float, featherPx: Float): Float {
        if (featherPx <= 0f) return if (clipY >= drawnTop) 1f else 0f
        return ((clipY - drawnTop) / featherPx).coerceIn(0f, 1f)
    }

    /**
     * 行折叠目标位（content 绘制坐标系下的行顶）：
     * 展开态各行在自然位 rowTop；收起时全部折向内容顶部，行距压成
     * index·stackPeekPx 的等差牌堆。行序单调保持（永不互穿），底部行
     * 行程最大——链式折叠/扇出由几何次序自动产生，打断反向直接倒带。
     */
    fun rowFoldedTop(
        index: Int,
        rowTopPx: Float,
        spatial: Float,
        stackPeekPx: Float
    ): Float = rowTopPx * spatial + index * stackPeekPx * (1f - spatial)
}
