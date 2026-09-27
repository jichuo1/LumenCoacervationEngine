package com.lumen.coacervation.engine.interaction

import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

internal enum class ElasticGestureDecision { OBSERVE, YIELD, CAPTURE, DRAG }

internal enum class ElasticNodeRole { BLOCKED, TRAVERSE, TARGET }

/** Static opacity belongs to visual styling; only animation/state changes make a hit unstable. */
internal object ElasticEligibilityPolicy {
    fun stableOpacity(alpha: Float, transientState: Boolean, animationActive: Boolean): Boolean =
        alpha.isFinite() && alpha > 0f && alpha <= 1f && !transientState && !animationActive

    fun unchangedOpacity(
        captured: Float, current: Float, transientState: Boolean, animationActive: Boolean
    ): Boolean = captured == current && stableOpacity(current, transientState, animationActive)

    /** Window roots and full-content dismiss surfaces keep their descendants eligible. */
    fun nodeRole(
        alpha: Float,
        transientState: Boolean,
        animationActive: Boolean,
        clickable: Boolean,
        windowRoot: Boolean,
        width: Int,
        height: Int,
        windowContentWidth: Int,
        windowContentHeight: Int,
        containerOnly: Boolean = false
    ): ElasticNodeRole {
        if (!stableOpacity(alpha, transientState, animationActive)) return ElasticNodeRole.BLOCKED
        if (!clickable || windowRoot || containerOnly || width <= 0 || height <= 0 ||
            windowContentWidth <= 0 || windowContentHeight <= 0
        ) return ElasticNodeRole.TRAVERSE
        val fillsWindow = width.toDouble() >= windowContentWidth * .9 &&
            height.toDouble() >= windowContentHeight * .9
        return if (fillsWindow) ElasticNodeRole.TRAVERSE else ElasticNodeRole.TARGET
    }
}

/** Once a normal scroll crosses slop it can never turn into an elastic hold later in that stream. */
internal class ElasticGestureGate {
    private var downTime = 0L
    private var lastTime = 0L
    private var decision = ElasticGestureDecision.YIELD

    fun begin(timeMillis: Long) {
        downTime = timeMillis
        lastTime = timeMillis
        decision = ElasticGestureDecision.OBSERVE
    }

    fun move(timeMillis: Long, dx: Float, dy: Float, slop: Float): ElasticGestureDecision {
        if (decision == ElasticGestureDecision.YIELD) return decision
        if (timeMillis < lastTime || !dx.isFinite() || !dy.isFinite() || !slop.isFinite() || slop < 0f) {
            return yield()
        }
        lastTime = timeMillis
        if (decision == ElasticGestureDecision.CAPTURE || decision == ElasticGestureDecision.DRAG) {
            decision = ElasticGestureDecision.DRAG
        } else if (dx.toDouble() * dx + dy.toDouble() * dy > slop.toDouble() * slop) {
            decision = if (timeMillis - downTime >= ElasticMotionPolicy.HOLD_MILLIS) {
                ElasticGestureDecision.CAPTURE
            } else ElasticGestureDecision.YIELD
        }
        return decision
    }

    fun yield(): ElasticGestureDecision {
        decision = ElasticGestureDecision.YIELD
        return decision
    }
}

/** Mutable output, reused by the View controller for both gesture and animation frames. */
internal class ElasticVector(var x: Float = 0f, var y: Float = 0f)

internal object ElasticMotionPolicy {
    const val HOLD_MILLIS = 160L
    const val MAX_SETTLE_MILLIS = 720L
    const val PRESS_DEPTH = .016f
    private const val DRAG_RESPONSE = .22f

    fun positionLimit(width: Int, height: Int, density: Float): Float {
        if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0f) return 0f
        return minOf(18f * density, minOf(width, height) * .16f)
    }

    /** A single radial budget makes diagonal and axial drags rotation-equivalent. */
    fun drag(dx: Float, dy: Float, slop: Float, limit: Float, out: ElasticVector) {
        if (!dx.isFinite() || !dy.isFinite() || !slop.isFinite() || !limit.isFinite() ||
            slop < 0f || limit <= 0f
        ) {
            out.x = 0f
            out.y = 0f
            return
        }
        val distance = sqrt(dx.toDouble() * dx + dy.toDouble() * dy)
        val amount = if (distance > slop)
            limit * tanh(DRAG_RESPONSE * (distance - slop) / limit) / distance else 0.0
        out.x = (dx * amount).toFloat()
        out.y = (dy * amount).toFloat()
    }

    /** Regrabbing keeps the rendered position; an existing spring overshoot cannot grow further. */
    fun dragFrom(originX: Float, originY: Float, dx: Float, dy: Float,
                 slop: Float, limit: Float, out: ElasticVector) {
        drag(dx, dy, slop, limit, out)
        if (!originX.isFinite() || !originY.isFinite() || !dx.isFinite() || !dy.isFinite() ||
            !slop.isFinite() || slop < 0f || !limit.isFinite() || limit <= 0f) return
        val originLength = sqrt(originX.toDouble() * originX + originY.toDouble() * originY)
        val x = originX.toDouble() + out.x
        val y = originY.toDouble() + out.y
        val length = sqrt(x * x + y * y)
        val budget = maxOf(limit.toDouble(), originLength)
        val scale = if (length > budget) budget / length else 1.0
        out.x = (x * scale).toFloat()
        out.y = (y * scale).toFloat()
    }

    fun scale(press: Float, dx: Float, dy: Float, limit: Float, out: ElasticVector) {
        val p = if (press.isFinite()) press.coerceIn(0f, 1.1f) else 0f
        val x = if (limit > 0f && dx.isFinite()) (abs(dx) / limit).coerceIn(0f, 1f) else 0f
        val y = if (limit > 0f && dy.isFinite()) (abs(dy) / limit).coerceIn(0f, 1f) else 0f
        val pressed = 1f - PRESS_DEPTH * p
        out.x = pressed + .035f * x - .009f * y
        out.y = pressed + .035f * y - .009f * x
    }

    fun releaseVelocity(previous: Float, current: Float, elapsedMillis: Long, limit: Float): Float {
        if (!previous.isFinite() || !current.isFinite() || !limit.isFinite() || limit <= 0f ||
            elapsedMillis !in 1L..80L
        ) return 0f
        return ((current - previous) * 1000f / elapsedMillis).coerceIn(-limit * 20f, limit * 20f)
    }
}

/**
 * 长按拖动光晕的调参换算（[com.lumen.coacervation.engine.model.LumenEffectTuning] 的两个 drag 倍率）。
 * 倍率为 1 时逐位返回原值。
 */
internal object ElasticGlowTuning {
    /** 基准 alpha（0..255）；越界堆积的增益由光晕策略在此之上叠加，最终仍钳在 255。 */
    fun baseAlpha(base: Int, intensity: Float): Int =
        if (intensity == 1f) base else (base * intensity).roundToInt().coerceIn(0, 255)

    fun radius(base: Float, scale: Float): Float = if (scale == 1f) base else base * scale
}

/** Exact damped-spring solution; stepping twice is consistent with one frame of the same duration. */
internal class ElasticSpringAxis(var value: Float = 0f, var velocity: Float = 0f) {
    fun advance(seconds: Float, target: Float, stiffness: Float = 310f, dampingRatio: Float = .7f) {
        if (!seconds.isFinite() || seconds <= 0f) return
        if (!value.isFinite() || !velocity.isFinite() || !target.isFinite() ||
            !stiffness.isFinite() || stiffness <= 0f || !dampingRatio.isFinite() ||
            dampingRatio <= 0f || dampingRatio >= 1f
        ) {
            value = if (target.isFinite()) target else 0f
            velocity = 0f
            return
        }
        val omega = sqrt(stiffness.toDouble())
        val decay = dampingRatio * omega
        val frequency = omega * sqrt(1.0 - dampingRatio * dampingRatio)
        val displacement = value.toDouble() - target
        val sineCoefficient = (velocity + decay * displacement) / frequency
        val time = seconds.toDouble()
        val envelope = exp(-decay * time)
        val cosine = cos(frequency * time)
        val sine = sin(frequency * time)
        val position = displacement * cosine + sineCoefficient * sine
        val speed = -displacement * frequency * sine + sineCoefficient * frequency * cosine
        value = (target + envelope * position).toFloat()
        velocity = (envelope * (speed - decay * position)).toFloat()
    }

    fun atRest(target: Float, tolerance: Float): Boolean =
        abs(value - target) <= tolerance && abs(velocity) <= tolerance * 20f

    fun reset(position: Float = 0f) {
        value = position
        velocity = 0f
    }
}

internal data class ElasticTransform(
    val translationX: Float, val translationY: Float, val scaleX: Float, val scaleY: Float
)

/** The weak key is never referenced by a value. A late callback only owns its exact lease identity. */
internal class ElasticTransformLeases<Key : Any, Owner : Any> {
    internal class Lease<Owner : Any>(val original: ElasticTransform, owner: Owner) {
        val owner = WeakReference(owner)
        var writtenX = original.translationX
        var writtenY = original.translationY
        var writtenScaleX = original.scaleX
        var writtenScaleY = original.scaleY

        fun record(x: Float, y: Float, scaleX: Float, scaleY: Float) {
            writtenX = x
            writtenY = y
            writtenScaleX = scaleX
            writtenScaleY = scaleY
        }
    }

    private val leases = WeakHashMap<Key, Lease<Owner>>()

    fun owner(key: Key): Owner? = leases[key]?.owner?.get()
    fun current(key: Key): Lease<Owner>? = leases[key]

    fun acquire(key: Key, owner: Owner, observed: ElasticTransform): Lease<Owner> =
        Lease(leases[key]?.original ?: observed, owner).also {
            it.record(observed.translationX, observed.translationY, observed.scaleX, observed.scaleY)
            leases[key] = it
        }

    fun owns(key: Key, lease: Lease<Owner>): Boolean = leases[key] === lease

    fun release(key: Key, lease: Lease<Owner>): Boolean {
        if (!owns(key, lease)) return false
        leases.remove(key)
        return true
    }
}
