package com.lumen.coacervation.engine.widget

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Original, bounded geometry for a native four-item floating navigation surface. */
internal object NavigationBarMotion {
    /** 胶囊底栏最多 6 项（来源工程固定 4 项；5 项底栏很常见，6 项以上应当改用侧边栏或"更多"）。 */
    const val MAX_ITEMS = 6
    /** 分段档位条最多 8 段（来源工程 4 段）。 */
    const val MAX_SEGMENTS = 8
    const val BAR_HEIGHT_DP = 64
    const val INSET_DP = 4
    const val MAX_TRAVEL_DP = 4f
    const val SPRING_DURATION_MS = 460L

    fun position(value: Float, count: Int): Float =
        if (value.isFinite()) value.coerceIn(0f, (count.coerceAtLeast(1) - 1).toFloat()) else 0f

    fun nearestPage(value: Float, count: Int): Int = position(value, count).roundToInt()

    fun physicalSlot(value: Float, count: Int, rtl: Boolean): Float =
        if (rtl) count.coerceAtLeast(1) - 1 - position(value, count) else position(value, count)

    fun indexAt(x: Float, contentWidth: Float, inset: Float, count: Int, rtl: Boolean): Int {
        if (count < 1 || contentWidth <= 0f || !contentWidth.isFinite() || !inset.isFinite() || !x.isFinite()) return -1
        val physical = ((x - inset) / (contentWidth / count)).toInt().coerceIn(0, count - 1)
        return if (rtl) count - 1 - physical else physical
    }

    fun scrubPosition(start: Float, distanceX: Float, slotWidth: Float, count: Int, rtl: Boolean): Float {
        if (slotWidth <= 0f || !distanceX.isFinite()) return position(start, count)
        return position(start + distanceX / slotWidth * if (rtl) -1f else 1f, count)
    }

    /** The vector magnitude approaches the limit; diagonal motion never gets a larger budget. */
    fun displacementScale(dx: Float, dy: Float, limit: Float, resistance: Float): Float {
        if (!dx.isFinite() || !dy.isFinite() || limit <= 0f || resistance <= 0f) return 0f
        return limit / (resistance + sqrt(dx * dx + dy * dy))
    }

    fun travelClampScale(x: Float, y: Float, limit: Float): Float {
        if (!x.isFinite() || !y.isFinite() || limit <= 0f) return 0f
        val length = sqrt(x * x + y * y)
        return if (length > limit) limit / length else 1f
    }

    fun lensScaleX(press: Float): Float = 1f + .055f * press.coerceIn(0f, 1f)
    fun lensScaleY(press: Float): Float = 1f + .07f * press.coerceIn(0f, 1f)
}

/** Analytic damped spring. Retargets preserve position/velocity; no frame-time allocations. */
public class LumenSpring(start: Float, target: Float, velocity: Float = 0f) {
    private val target = if (target.isFinite()) target else 0f
    private val displacement = (if (start.isFinite()) start else this.target) - this.target
    private val decay = 15.6f
    private val frequency = 12.51559f
    private val sine = ((if (velocity.isFinite()) velocity else 0f) + decay * displacement) / frequency

    fun value(seconds: Float): Float {
        val t = seconds.coerceAtLeast(0f)
        return target + exp(-decay * t) * (displacement * cos(frequency * t) + sine * sin(frequency * t))
    }

    fun velocity(seconds: Float): Float {
        val t = seconds.coerceAtLeast(0f)
        val c = cos(frequency * t)
        val s = sin(frequency * t)
        return exp(-decay * t) * (-decay * (displacement * c + sine * s) +
            frequency * (-displacement * s + sine * c))
    }
}

internal enum class NavigationBarIntent { NONE, SCRUB, ELASTIC }

/** DOWN only previews. Cancellation, multi-touch and vertical elastic movement never select a page. */
internal class NavigationBarGesture {
    private var active = false
    private var downIndex = -1
    private var canScrub = false
    var intent = NavigationBarIntent.NONE
        private set

    fun begin(index: Int, allowScrub: Boolean) {
        active = true
        downIndex = index
        canScrub = allowScrub
        intent = NavigationBarIntent.NONE
    }

    fun move(dx: Float, dy: Float, slop: Float): Boolean {
        if (!active || intent != NavigationBarIntent.NONE || !dx.isFinite() || !dy.isFinite()) return false
        if (dx * dx + dy * dy <= slop * slop) return false
        intent = if (canScrub && abs(dx) > abs(dy) * 1.15f) NavigationBarIntent.SCRUB
            else NavigationBarIntent.ELASTIC
        return true
    }

    fun finish(cancelled: Boolean, progress: Float, releaseIndex: Int, count: Int): Int? {
        if (!active) return null
        active = false
        if (cancelled) return null
        return when (intent) {
            NavigationBarIntent.SCRUB -> NavigationBarMotion.nearestPage(progress, count)
            NavigationBarIntent.NONE -> downIndex.takeIf { it == releaseIndex && it in 0 until count }
            NavigationBarIntent.ELASTIC -> null
        }
    }

    fun cancel() { active = false }
}
