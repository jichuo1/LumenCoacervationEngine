package com.lumen.coacervation.engine.fluent

import com.lumen.coacervation.engine.host.LumenFluentElevation
import kotlin.math.max
import kotlin.math.min

/**
 * Fluent contour, state layer and focus tokens from microsoft-ui-xaml `Common_themeresources_any.xaml`
 * and `CornerRadius_themeresources.xaml`; elevation values from Windows "Layering and elevation".
 */
internal object FluentElevationPolicy {
    /** One Fluent elevation unit in Android dp: control 2 → 0.5dp, card 8 → 2dp, flyout 32 → 8dp. */
    const val ANDROID_DP_PER_FLUENT_UNIT = 0.25f
    /** Dialog 128 would be 32dp; capped at Material's dialog elevation so shadows stay inside typical parents. */
    const val MAX_ANDROID_ELEVATION_DP = 24f
    /** ControlElevationBorderBrush: absolute 0..3 px gradient with stops at 0.33 and 1.0. */
    const val STROKE_BAND_DP = 3f
    const val STROKE_BAND_STOP = 0.33f
    const val CONTROL_CORNER_DP = 4f
    const val OVERLAY_CORNER_DP = 8f
    /** ControlFasterAnimationDuration, used by Fluent brush transitions between visual states. */
    const val STATE_TRANSITION_MS = 83L
    /** FocusVisualPrimaryThickness / FocusVisualSecondaryThickness. */
    const val FOCUS_OUTER_DP = 2f
    const val FOCUS_INNER_DP = 1f
    /** Fluent renders the same elevation more intensely in dark theme; light shadows are scaled down. */
    const val LIGHT_SHADOW_ALPHA = 0xB3

    fun fluentValue(level: LumenFluentElevation, pressed: Boolean, pressedLowers: Boolean): Int {
        val value = level.fluentValue
        return if (pressed && pressedLowers) max(1, value / 2) else value
    }

    fun androidElevationDp(fluentValue: Int): Float = min(fluentValue * ANDROID_DP_PER_FLUENT_UNIT, MAX_ANDROID_ELEVATION_DP)

    fun cornerDp(level: LumenFluentElevation): Float =
        if (level == LumenFluentElevation.LAYER || level == LumenFluentElevation.CONTROL) CONTROL_CORNER_DP else OVERLAY_CORNER_DP

    /**
     * [band] is drawn over the outer [STROKE_BAND_DP] at the top or bottom edge and fades into [body].
     * Light theme and accent brushes are flipped (ScaleY=-1), so the darker band sits at the bottom.
     */
    class Stroke(val band: Int, val body: Int, val bandAtBottom: Boolean)

    fun stroke(dark: Boolean, accent: Boolean, enabled: Boolean): Stroke {
        val default = when {
            accent -> 0x14FFFFFF
            dark -> 0x12FFFFFF
            else -> 0x0F000000
        }
        if (!enabled) return Stroke(default, default, false)
        val secondary = when {
            accent -> if (dark) 0x23000000 else 0x66000000
            dark -> 0x18FFFFFF
            else -> 0x29000000
        }
        return Stroke(secondary, default, accent || !dark)
    }

    /** SubtleFillColorSecondary (hover) and SubtleFillColorTertiary (pressed); transparent at rest. */
    fun subtleFill(dark: Boolean, hovered: Boolean, pressed: Boolean): Int = when {
        pressed -> if (dark) 0x0AFFFFFF else 0x06000000
        hovered -> if (dark) 0x0FFFFFFF else 0x09000000
        else -> 0
    }

    fun focusOuter(dark: Boolean): Int = if (dark) 0xFFFFFFFF.toInt() else 0xE4000000.toInt()
    fun focusInner(dark: Boolean): Int = if (dark) 0xB3000000.toInt() else 0xB3FFFFFF.toInt()
    fun shadowColor(dark: Boolean): Int = withAlpha(0, if (dark) 0xFF else LIGHT_SHADOW_ALPHA)
}

/**
 * Interruptible scalar transition. Retargeting starts from the current value and scales the duration by
 * the remaining distance, so a reversal never jumps.
 */
internal class FluentTransition(initial: Float) {
    private var from = initial
    private var to = initial
    private var start = 0L
    private var duration = 0L
    val target: Float get() = to
    var retargets = 0L
        private set

    fun isRunning(now: Long): Boolean = duration > 0L && now - start < duration

    /** [fullDurationMs] covers a 0→1 change; zero snaps immediately. */
    fun retarget(value: Float, now: Long, fullDurationMs: Long, eased: Boolean) {
        if (value == to) return
        val current = value(now, eased)
        from = current
        to = value
        start = now
        duration = if (fullDurationMs <= 0L) 0L else max(1L, (fullDurationMs * kotlin.math.abs(value - current)).toLong())
        retargets++
    }

    fun value(now: Long, eased: Boolean): Float {
        if (duration <= 0L) return to
        val elapsed = now - start
        if (elapsed <= 0L) return from
        if (elapsed >= duration) {
            duration = 0L
            return to
        }
        val linear = elapsed.toFloat() / duration
        val t = if (eased) FluentAcrylicPolicy.crossfadeEase(linear) else linear
        return from + (to - from) * t
    }

    fun snap(value: Float) {
        from = value
        to = value
        duration = 0L
    }
}

/**
 * Backdrop availability with hysteresis. Gaining is immediate; losing requires [loseAfterMs] of
 * continuous unsampled frames, so a single pending or throttled frame never flashes the fallback.
 */
internal class AcrylicAvailability(private val loseAfterMs: Long = LOSE_AFTER_MS) {
    var available = false
        private set
    private var unavailableSince = Long.MIN_VALUE

    /** Returns true when [available] changed. */
    fun report(sampled: Boolean, now: Long): Boolean {
        if (sampled) {
            unavailableSince = Long.MIN_VALUE
            if (available) return false
            available = true
            return true
        }
        if (!available) return false
        if (unavailableSince == Long.MIN_VALUE) {
            unavailableSince = now
            return false
        }
        if (now - unavailableSince < loseAfterMs) return false
        available = false
        unavailableSince = Long.MIN_VALUE
        return true
    }

    /** Milliseconds until a pending loss can be confirmed, or -1 when none is pending. */
    fun pendingLossIn(now: Long): Long =
        if (!available || unavailableSince == Long.MIN_VALUE) -1L else max(0L, loseAfterMs - (now - unavailableSince))

    fun reset() {
        available = false
        unavailableSince = Long.MIN_VALUE
    }

    companion object { const val LOSE_AFTER_MS = 120L }
}
