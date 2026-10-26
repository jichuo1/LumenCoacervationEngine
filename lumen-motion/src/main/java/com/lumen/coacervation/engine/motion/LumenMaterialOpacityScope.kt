package com.lumen.coacervation.engine.motion

import android.animation.ValueAnimator
import android.os.Looper
import android.view.Choreographer
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.host.LumenMaterialOpacityTarget
import com.lumen.coacervation.engine.widget.LumenSpring
import kotlin.math.abs

/**
 * 一个值驱动一组材质浓度目标（MATERIAL_OPACITY.md §7.3）：每个目标实际收到 `作用域值 × 目标基准值`。
 *
 * - [animateTo] 用 [LumenSpring] 过渡；中途反向只换目标，位置与速度原样续跑（ARCHITECTURE.md §11.2）。
 *   系统关闭动画时等同于 [set]。
 * - 作用域本身也是 [LumenMaterialOpacityTarget]：把它注册进上一级作用域即可嵌套，两级相乘。
 * - 逐帧只遍历注册表、只写浮点；不遍历视图树、不分配。目标由作用域强引用，页面销毁时请 [close]。
 */
@MainThread
public class LumenMaterialOpacityScope @JvmOverloads constructor(
    initial: Float = 1f,
    private val stiffness: Float = DEFAULT_STIFFNESS,
    private val dampingRatio: Float = DEFAULT_DAMPING
) : LumenMaterialOpacityTarget, AutoCloseable {
    private val targets = ArrayList<LumenMaterialOpacityTarget>()
    private var bases = FloatArray(4)
    private var parent = 1f
    private var current = clamp(initial, 1f)
    private var target = current
    private var spring: LumenSpring? = null
    private var springStartNanos = 0L
    private var lastSeconds = 0f
    private var posted = false
    private var closed = false

    private val frame = Choreographer.FrameCallback { now -> onFrame(now) }

    /** 作用域自己的当前值（动画中为插值），不含上一级。 */
    public val value: Float get() = current

    /** 正在过渡时为 true。 */
    public val isAnimating: Boolean get() = spring != null

    public fun register(target: LumenMaterialOpacityTarget) { register(target, 1f) }

    /** 注册 [target]；它收到 `上一级 × 作用域值 × [base]`，并立即应用一次。重复注册只更新 [base]。 */
    public fun register(target: LumenMaterialOpacityTarget, base: Float) {
        if (closed) return
        checkMain()
        require(target !== this) { "A scope cannot contain itself" }
        val b = clamp(base, 1f)
        val index = indexOf(target)
        if (index >= 0) {
            bases[index] = b
            target.setMaterialOpacity(parent * current * b)
            return
        }
        if (targets.size == bases.size) bases = bases.copyOf(bases.size * 2)
        bases[targets.size] = b
        targets.add(target)
        target.setMaterialOpacity(parent * current * b)
    }

    /** 移除 [target]，并把它恢复为 [base]（默认 1，即默认外观）。 */
    @JvmOverloads
    public fun unregister(target: LumenMaterialOpacityTarget, restore: Float = 1f) {
        if (closed) return
        checkMain()
        val index = indexOf(target)
        if (index < 0) return
        targets.removeAt(index)
        System.arraycopy(bases, index + 1, bases, index, targets.size - index)
        target.setMaterialOpacity(clamp(restore, 1f))
    }

    /** 立即设定，取消进行中的过渡。 */
    public fun set(value: Float) {
        if (closed) return
        checkMain()
        stop()
        val next = clamp(value, current)
        target = next
        if (next == current) return
        current = next
        apply()
    }

    /** 弹簧过渡到 [value]；进行中再次调用从当前位置与速度续接。 */
    public fun animateTo(value: Float) {
        if (closed) return
        checkMain()
        val next = clamp(value, target)
        if (!ValueAnimator.areAnimatorsEnabled()) { set(next); return }
        val active = spring
        if (active == null && next == current) { target = next; return }
        if (active != null && next == target) return
        val velocity = active?.velocity(lastSeconds) ?: 0f
        target = next
        spring = LumenSpring(current, next, velocity, stiffness, dampingRatio)
        springStartNanos = 0L
        lastSeconds = 0f
        if (!posted) { posted = true; Choreographer.getInstance().postFrameCallback(frame) }
    }

    /** 作为上一级作用域的目标：这里收到的是上一级给的乘数。 */
    override fun setMaterialOpacity(value: Float) {
        if (closed || value.isNaN()) return
        checkMain()
        val next = value.coerceIn(0f, 1f)
        if (next == parent) return
        parent = next
        apply()
    }

    override fun materialOpacity(): Float = current

    override fun effectiveMaterialOpacity(): Float = parent * current

    /** 停止过渡并释放全部目标（不改变它们当前的浓度）。之后的调用为空操作。 */
    override fun close() {
        if (closed) return
        stop()
        targets.clear()
        closed = true
    }

    private fun onFrame(now: Long) {
        posted = false
        val active = spring ?: return
        if (closed) return
        if (springStartNanos == 0L) springStartNanos = now
        val seconds = (now - springStartNanos) / 1_000_000_000f
        lastSeconds = seconds
        val position = active.value(seconds)
        if (abs(position - target) < REST_POSITION && abs(active.velocity(seconds)) < REST_VELOCITY) {
            spring = null
            current = target
        } else {
            current = position.coerceIn(0f, 1f)
        }
        apply()
        if (spring != null) { posted = true; Choreographer.getInstance().postFrameCallback(frame) }
    }

    private fun apply() {
        val scale = parent * current
        for (i in 0 until targets.size) targets[i].setMaterialOpacity(scale * bases[i])
    }

    private fun stop() {
        spring = null
        if (posted) { posted = false; Choreographer.getInstance().removeFrameCallback(frame) }
    }

    private fun indexOf(target: LumenMaterialOpacityTarget): Int {
        for (i in 0 until targets.size) if (targets[i] === target) return i
        return -1
    }

    private fun clamp(value: Float, fallback: Float): Float = if (value.isNaN()) fallback else value.coerceIn(0f, 1f)

    private fun checkMain() = check(Looper.myLooper() === Looper.getMainLooper()) { "Material opacity scopes require the main thread" }

    private companion object {
        const val DEFAULT_STIFFNESS = 260f
        const val DEFAULT_DAMPING = .92f
        const val REST_POSITION = 1e-3f
        const val REST_VELOCITY = 1e-2f
    }
}
