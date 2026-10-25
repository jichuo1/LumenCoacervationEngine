package com.lumen.coacervation.engine.widget

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

/** 外观种类：纯策略按它解析形态、气泡与 HDR 默认值，不依赖 Android 类型。 */
internal enum class SliderKind { SKIN, PLAIN, FLUENT, MICA, PRISM }

/**
 * [LumenSlider] 的纯策略：值映射、吸附与磁吸、RTL 物理坐标、橡皮筋、键盘步长、刻度可见性、HDR 增益、
 * 气泡位置、Fluent 令牌与过渡曲线。不依赖 `android.graphics`，JVM 单测覆盖（`LumenSliderPolicyTest`）。
 *
 * Fluent 数值取自 microsoft-ui-xaml（commit 8463f451）`Slider_themeresources.xaml` 与
 * `Common_themeresources_any.xaml`：轨道 4、外圈 18 + 2·2（Border Margin −2）、圆角 10、内点 12；
 * 内点缩放 Normal 0.86 / PointerOver 1.167 / Pressed 0.71 / Disabled 1.167，时长 167ms（Normal、Disabled）
 * 与 250ms（PointerOver、Pressed），KeySpline 0,0,0,1；颜色令牌见 [FluentSliderTokens]。
 */
internal object LumenSliderPolicy {
    /** 刻度（含两端）上限：每一档都可能画一个刻度点，逐帧只循环这个上限内的次数。 */
    const val MAX_STOPS = 1000
    const val MAX_VISIBLE_TICKS = 41
    const val TICK_MIN_SPACING_DP = 10f

    const val MIN_TOUCH_DP = 48f
    const val DEFAULT_WIDTH_DP = 240f
    const val CAPSULE_HEIGHT_DP = 44f
    const val CAPSULE_ICON_DP = 20f
    const val CAPSULE_ICON_INSET_DP = 14f
    const val CAPSULE_TICK_DP = 4f
    const val CAPSULE_HANDLE_WIDTH_DP = 3f
    const val CAPSULE_HANDLE_INSET_DP = 9f
    /** 胶囊按压时的纵向 lens 放大（与档位条 lensScaleY 同一量级）。 */
    const val CAPSULE_PRESS_SCALE_Y = .06f
    const val CAPSULE_PRESS_SCALE_X = .015f

    const val FLUENT_TRACK_DP = 4f
    const val FLUENT_TRACK_CORNER_DP = 2f
    const val FLUENT_THUMB_DP = 22f
    const val FLUENT_THUMB_CORNER_DP = 10f
    const val FLUENT_INNER_DP = 12f
    const val FLUENT_INNER_NORMAL = .86f
    const val FLUENT_INNER_HOVER = 1.167f
    const val FLUENT_INNER_PRESSED = .71f
    const val FLUENT_INNER_DISABLED = 1.167f
    const val FLUENT_FAST_MS = 167L
    const val FLUENT_NORMAL_MS = 250L
    /** WinUI SliderOutsideTickBarThemeHeight。 */
    const val FLUENT_TICK_LENGTH_DP = 4f
    const val FLUENT_TICK_GAP_DP = 3f

    const val SKIN_TRACK_DP = 8f
    const val SKIN_THUMB_DP = 22f

    const val PRISM_TRACK_DP = 6f
    /**
     * 静止白钮不透明、盖住填充末端的一半：钮越宽，"填充停在钮左缘、钮向右突出"的错位感越强。
     * 30 × 20dp 让被盖住的半宽只有 15dp；胶囊形态的钮比槽矮 12dp、宽高比 1.3。
     */
    const val PRISM_THUMB_WIDTH_DP = 30f
    const val PRISM_THUMB_HEIGHT_DP = 20f
    const val PRISM_CAPSULE_THUMB_INSET_DP = 12f
    const val PRISM_CAPSULE_THUMB_RATIO = 1.3f
    /** 镜片露在填充之外的宽度比例。 */
    const val PRISM_CAPSULE_OVERHANG = .25f
    /** 按下变成镜片时的放大（引擎取值，接近参考库滑块的观感）。 */
    const val PRISM_PRESSED_SCALE_X = 1.45f
    const val PRISM_PRESSED_SCALE_Y = 1.35f

    const val LABEL_HEIGHT_DP = 28f
    const val LABEL_PADDING_DP = 10f
    const val LABEL_GAP_DP = 6f
    const val LABEL_CORNER_DP = 4f

    /** 拖过两端时的拉伸渐近上限；须小于宿主内边距（§12.3）。 */
    const val MAX_STRETCH_DP = 6f
    /** 拉伸到上限时横截面最多收窄的比例。 */
    const val STRETCH_SQUEEZE = .08f

    const val DEFAULT_HDR_HEADROOM = 2f
    const val MAX_HDR_HEADROOM = 4f
    /** HDR 高光峰值透明度（press = 1 时）。 */
    const val HIGHLIGHT_ALPHA = 0.85f
    const val HIGHLIGHT_CAPSULE_RADIUS = 1.25f
    const val HIGHLIGHT_THUMB_RADIUS = 1.5f

    const val POSITION_STIFFNESS = 420f
    const val POSITION_DAMPING = .82f
    const val PRESS_STIFFNESS = 520f
    const val PRESS_DAMPING = .9f
    const val STRETCH_STIFFNESS = 380f
    const val STRETCH_DAMPING = .55f
    /** Prism 镜片两轴不同阻尼形成果冻感（与棱镜选中框同值）。 */
    const val PRISM_SCALE_STIFFNESS = 250f
    const val PRISM_SCALE_X_DAMPING = .6f
    const val PRISM_SCALE_Y_DAMPING = .7f

    /** 横向判定的倾角：与底栏 `NavigationBarGesture` 相同的 1.15。 */
    const val HORIZONTAL_BIAS = 1.15f
    /** 按在滑钮上（含这段外扩）时相对拖动，否则先跳到按下位置。 */
    const val THUMB_HIT_SLOP_DP = 8f

    fun resolveShape(shape: LumenSliderShape, kind: SliderKind): LumenSliderShape = when (shape) {
        LumenSliderShape.AUTO -> if (kind == SliderKind.SKIN || kind == SliderKind.PLAIN) LumenSliderShape.CAPSULE
            else LumenSliderShape.CLASSIC
        else -> shape
    }

    fun showsLabel(label: LumenSliderValueLabel, kind: SliderKind): Boolean = when (label) {
        LumenSliderValueLabel.AUTO -> kind == SliderKind.FLUENT || kind == SliderKind.MICA
        LumenSliderValueLabel.ON_DRAG -> true
        LumenSliderValueLabel.NONE -> false
    }

    fun hdrEnabled(hdr: LumenSliderHdr, kind: SliderKind): Boolean = when (hdr) {
        LumenSliderHdr.AUTO -> kind == SliderKind.SKIN || kind == SliderKind.PLAIN
        LumenSliderHdr.ON -> true
        LumenSliderHdr.OFF -> false
    }

    // ---- 值与比例 ----

    fun fraction(value: Float, from: Float, to: Float): Float {
        if (!value.isFinite() || !(to > from)) return 0f
        return ((value - from) / (to - from)).coerceIn(0f, 1f)
    }

    fun valueAt(fraction: Float, from: Float, to: Float): Float {
        val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        return if (f >= 1f) to else from + (to - from) * f
    }

    /** 完整档数（不含 to 补档）：`from + k·step ≤ to` 的最大 k。 */
    private fun wholeSteps(from: Float, to: Float, step: Float): Int =
        floor((to - from) / step + 1e-4f).toInt().coerceIn(0, MAX_STOPS)

    private fun hasTailStop(from: Float, to: Float, step: Float): Boolean =
        to - (from + wholeSteps(from, to, step) * step) > step * 1e-3f

    /** 刻度个数（含两端）；连续值为 0。 */
    fun stopCount(from: Float, to: Float, step: Float): Int {
        if (!(step > 0f) || !(to > from)) return 0
        return wholeSteps(from, to, step) + 1 + if (hasTailStop(from, to, step)) 1 else 0
    }

    /** 第 [index] 个刻度的值（最后一个补档为 [to]）。 */
    fun stopValue(index: Int, from: Float, to: Float, step: Float): Float {
        val whole = wholeSteps(from, to, step)
        return if (index > whole) to else (from + index * step).coerceAtMost(to)
    }

    fun stopIndex(value: Float, from: Float, to: Float, step: Float): Int {
        if (!(step > 0f) || !(to > from)) return -1
        val whole = wholeSteps(from, to, step)
        val v = if (value.isFinite()) value.coerceIn(from, to) else from
        val k = round((v - from) / step).toInt().coerceIn(0, whole)
        if (hasTailStop(from, to, step)) {
            val candidate = from + k * step
            if (abs(to - v) < abs(v - candidate)) return whole + 1
        }
        return k
    }

    /** 吸附到最近一档；连续值只钳制。 */
    fun snap(value: Float, from: Float, to: Float, step: Float): Float {
        val v = if (value.isFinite()) value.coerceIn(from, to) else from
        if (!(step > 0f)) return v
        return stopValue(stopIndex(v, from, to, step), from, to, step)
    }

    /**
     * 拖动时的磁吸：显示比例朝最近一档收拢。半档边界处与原值连续、单调，[strength] 为 0 时恒等。
     * `out = nearest + half · sign(t) · |t|^(1 + 2·strength)`，`t` 为到最近一档的有符号距离 / 该侧半档宽。
     */
    fun magnet(fraction: Float, from: Float, to: Float, step: Float, strength: Float): Float {
        val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        if (!(step > 0f) || !(strength > 0f)) return f
        val index = stopIndex(valueAt(f, from, to), from, to, step)
        val count = stopCount(from, to, step)
        val nearest = fraction(stopValue(index, from, to, step), from, to)
        val delta = f - nearest
        if (delta == 0f) return nearest
        val neighbor = if (delta > 0f) index + 1 else index - 1
        if (neighbor !in 0 until count) return nearest
        val half = abs(fraction(stopValue(neighbor, from, to, step), from, to) - nearest) / 2f
        if (half <= 0f) return f
        val t = (abs(delta) / half).coerceAtMost(1f)
        val shaped = t.toDouble().pow(1.0 + 2.0 * strength.coerceIn(0f, 1f)).toFloat()
        return (nearest + half * shaped * if (delta > 0f) 1f else -1f).coerceIn(0f, 1f)
    }

    // ---- 物理坐标（RTL 下最小值在右）----

    fun physicalX(fraction: Float, start: Float, end: Float, rtl: Boolean): Float {
        val f = if (fraction.isFinite()) fraction else 0f
        return if (rtl) end - f * (end - start) else start + f * (end - start)
    }

    /** 物理 x 对应的比例，不钳制（越界即两端拉伸的输入）。 */
    fun fractionAt(x: Float, start: Float, end: Float, rtl: Boolean): Float {
        val length = end - start
        if (!x.isFinite() || !(length > 0f)) return 0f
        val f = (x - start) / length
        return if (rtl) 1f - f else f
    }

    fun dragFraction(startFraction: Float, dx: Float, length: Float, rtl: Boolean): Float {
        if (!dx.isFinite() || !(length > 0f)) return startFraction
        return startFraction + dx / length * if (rtl) -1f else 1f
    }

    /** 越界量（px），按值方向：低端为负，高端为正，界内为 0。 */
    fun overshootPx(rawFraction: Float, length: Float): Float = when {
        !rawFraction.isFinite() || !(length > 0f) -> 0f
        rawFraction < 0f -> rawFraction * length
        rawFraction > 1f -> (rawFraction - 1f) * length
        else -> 0f
    }

    /** 有理橡皮筋：`limit · x / (x + 2·limit)`，起点斜率 0.5，渐近 [limit]，保号。 */
    fun rubberBand(overshoot: Float, limit: Float): Float {
        if (!overshoot.isFinite() || !(limit > 0f)) return 0f
        val x = abs(overshoot)
        val band = limit * x / (x + 2f * limit)
        return if (overshoot < 0f) -band else band
    }

    fun stretchScaleAlong(band: Float, length: Float): Float =
        if (!band.isFinite() || !(length > 0f)) 1f else 1f + abs(band) / length

    fun stretchScaleAcross(band: Float, limit: Float): Float =
        if (!band.isFinite() || !(limit > 0f)) 1f else 1f - STRETCH_SQUEEZE * (abs(band) / limit).coerceAtMost(1f)

    /**
     * 拉伸的固定端（物理 x）：低端越界时固定高端，反之固定低端；RTL 镜像。返回 0 = 左缘，1 = 右缘。
     */
    fun stretchPivot(band: Float, rtl: Boolean): Float {
        val lowEnd = band < 0f
        // 低端越界 → 固定高端；LTR 的高端在右（1），RTL 在左（0）。
        return if (lowEnd != rtl) 1f else 0f
    }

    /** 拉伸时移动端的物理位移（px）：LTR 高端向右为正。 */
    fun stretchShiftPx(band: Float, rtl: Boolean): Float = if (rtl) -band else band

    // ---- 键盘与无障碍 ----

    fun keyStep(from: Float, to: Float, step: Float): Float = if (step > 0f) step else (to - from) / 100f

    fun pageStep(from: Float, to: Float, step: Float): Float {
        val tenth = (to - from) / 10f
        if (!(step > 0f)) return tenth
        return (round(tenth / step).coerceAtLeast(1f)) * step
    }

    /** 按方向键后的新值：LTR 右 / RTL 左为增；结果吸附并钳制。 */
    fun keyValue(value: Float, delta: Float, from: Float, to: Float, step: Float): Float =
        snap(value + delta, from, to, step)

    // ---- 刻度与气泡 ----

    fun ticksVisible(stopCount: Int, lengthPx: Float, minSpacingPx: Float): Boolean =
        stopCount in 2..MAX_VISIBLE_TICKS && lengthPx > 0f && lengthPx / (stopCount - 1) >= minSpacingPx

    /** 气泡中心 x：跟随滑钮，允许越出控件 [outset]，不越出更多。 */
    fun labelCenter(thumbX: Float, labelWidth: Float, width: Float, outset: Float): Float {
        val low = labelWidth / 2f - outset
        val high = width - labelWidth / 2f + outset
        return if (low > high) width / 2f else thumbX.coerceIn(low, high)
    }

    /** 默认文案：步长为整数时显示整数，否则保留到步长的小数位（至多两位）。只在值变化时调用。 */
    fun defaultLabel(value: Float, step: Float): String {
        val integral = step > 0f && step == floor(step)
        if (integral || (step == 0f && abs(value) >= 100f)) return round(value).toInt().toString()
        val digits = if (step > 0f && step >= .1f) 1 else 2
        val scale = if (digits == 1) 10f else 100f
        val scaled = round(value * scale) / scale
        val text = scaled.toString()
        val dot = text.indexOf('.')
        return if (dot < 0) text else text.substring(0, (dot + 1 + digits).coerceAtMost(text.length))
    }

    // ---- HDR ----

    /** HDR 高光峰值倍数：不可用、非有限或不超过 SDR 时为 1（SDR 回退）。 */
    fun hdrGain(ratio: Float, headroom: Float, available: Boolean): Float {
        if (!available || !ratio.isFinite() || !headroom.isFinite() || ratio <= 1.01f) return 1f
        return minOf(ratio, headroom, MAX_HDR_HEADROOM).coerceAtLeast(1f)
    }

    /**
     * Prism 胶囊的填充越过镜片中心的距离：镜片约 1/4 露在填充之外，跨着填充边界才看得出放大；
     * 最后 10% 行程平滑过渡到包住镜片并到达槽尾，满值时整条是满的。
     */
    fun prismCapsuleWrap(fraction: Float, thumbWidth: Float, endInset: Float): Float {
        val quarter = thumbWidth * PRISM_CAPSULE_OVERHANG
        val full = thumbWidth / 2f + endInset
        val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        val t = ((f - .9f) / .1f).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        // 填充越过中心 = 半宽 − 露出的 1/4 宽。
        return (thumbWidth / 2f - quarter) + (full - (thumbWidth / 2f - quarter)) * smooth
    }

    /**
     * 放大后的镜片中心：镜片按自身中心放大，在槽的两端会伸出槽外（满值或零值时按住最明显）。
     * 把中心向内收，使放大后的半宽仍落在 [low, high] 之内；静止（scale = 1）时与 [knob] 相同，过渡连续。
     * 槽太短放不下时取中点。
     */
    fun lensCenter(knob: Float, halfWidth: Float, scale: Float, low: Float, high: Float): Float {
        if (!knob.isFinite()) return knob
        val half = halfWidth * (if (scale.isFinite() && scale > 0f) scale else 1f)
        val min = low + half
        val max = high - half
        return if (min > max) (low + high) / 2f else knob.coerceIn(min, max)
    }

    fun highlightAlpha(press: Float): Int = (HIGHLIGHT_ALPHA * press.coerceIn(0f, 1f) * 255f + .5f).toInt()

    // ---- Fluent ----

    fun fluentInnerScale(enabled: Boolean, pressed: Boolean, hovered: Boolean): Float = when {
        !enabled -> FLUENT_INNER_DISABLED
        pressed -> FLUENT_INNER_PRESSED
        hovered -> FLUENT_INNER_HOVER
        else -> FLUENT_INNER_NORMAL
    }

    fun fluentInnerDurationMs(enabled: Boolean, pressed: Boolean, hovered: Boolean): Long =
        if (enabled && (pressed || hovered)) FLUENT_NORMAL_MS else FLUENT_FAST_MS

    /** WinUI `ControlFastOutSlowInKeySpline` = cubic-bezier(0, 0, 0, 1)。按 x 求 y，二分 24 次，无分配。 */
    fun fastOutSlowIn(x: Float): Float = cubicBezier(0f, 0f, 0f, 1f, x)

    fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        if (!x.isFinite() || x <= 0f) return 0f
        if (x >= 1f) return 1f
        var low = 0f
        var high = 1f
        var t = x
        repeat(24) {
            t = (low + high) / 2f
            if (bezier(x1, x2, t) < x) low = t else high = t
        }
        return bezier(y1, y2, t)
    }

    private fun bezier(p1: Float, p2: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
    }

    // ---- 颜色（ARGB Int，纯整数运算）----

    fun alpha(color: Int): Int = color ushr 24
    fun withAlpha(color: Int, alpha: Int): Int = (color and 0xFFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    fun scaleAlpha(color: Int, factor: Float): Int =
        withAlpha(color, (alpha(color) * factor.coerceIn(0f, 1f) + .5f).toInt())

    fun blend(a: Int, b: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        fun mix(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return ((x + (y - x) * r) + .5f).toInt() and 0xFF
        }
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** sRGB 相对亮度（WCAG）。 */
    fun luminance(color: Int): Float {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return (0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)).toFloat()
    }

    /** 与 Fluent、Mica、Prism 一致：按 `palette.background` 亮度判定深浅色。 */
    fun isDark(background: Int): Boolean = luminance(background) < .5f

    /** 胶囊填充的竖向渐变顶端：主色向白提亮。 */
    fun capsuleFillTop(primary: Int): Int = blend(primary, 0xFFFFFFFF.toInt(), .18f)
}

/** WinUI 颜色令牌（浅色 / 深色），出处见 [LumenSliderPolicy]。 */
internal object FluentSliderTokens {
    const val STRONG_FILL_LIGHT = 0x72000000
    const val STRONG_FILL_DARK = 0x8BFFFFFF.toInt()
    const val STRONG_FILL_DISABLED_LIGHT = 0x51000000
    const val STRONG_FILL_DISABLED_DARK = 0x3FFFFFFF
    const val SOLID_FILL_LIGHT = 0xFFFFFFFF.toInt()
    const val SOLID_FILL_DARK = 0xFF454545.toInt()
    const val ACCENT_DISABLED_LIGHT = 0x37000000
    const val ACCENT_DISABLED_DARK = 0x28FFFFFF
    /** AccentFillColorSecondary / Tertiary 的不透明度。 */
    const val ACCENT_SECONDARY_OPACITY = .9f
    const val ACCENT_TERTIARY_OPACITY = .8f
    /** 气泡（ToolTip）背景与文字：SolidBackgroundFillColorQuarternary / TextFillColorPrimary 的近似。 */
    const val TOOLTIP_LIGHT = 0xFFF9F9F9.toInt()
    const val TOOLTIP_DARK = 0xFF2C2C2C.toInt()
    const val TOOLTIP_STROKE_LIGHT = 0x0F000000
    const val TOOLTIP_STROKE_DARK = 0x33000000
    const val TEXT_LIGHT = 0xE4000000.toInt()
    const val TEXT_DARK = 0xFFFFFFFF.toInt()

    fun strongFill(dark: Boolean, enabled: Boolean): Int = when {
        !enabled -> if (dark) STRONG_FILL_DISABLED_DARK else STRONG_FILL_DISABLED_LIGHT
        else -> if (dark) STRONG_FILL_DARK else STRONG_FILL_LIGHT
    }

    fun solidFill(dark: Boolean): Int = if (dark) SOLID_FILL_DARK else SOLID_FILL_LIGHT

    /** 强调色状态：Default / Secondary(90%) / Tertiary(80%) / Disabled。 */
    fun accent(accent: Int, dark: Boolean, enabled: Boolean, pressed: Boolean, hovered: Boolean): Int = when {
        !enabled -> if (dark) ACCENT_DISABLED_DARK else ACCENT_DISABLED_LIGHT
        pressed -> LumenSliderPolicy.scaleAlpha(accent, ACCENT_TERTIARY_OPACITY)
        hovered -> LumenSliderPolicy.scaleAlpha(accent, ACCENT_SECONDARY_OPACITY)
        else -> accent
    }
}

internal enum class SliderIntent { NONE, DRAG, YIELD }

/**
 * 手势判定：超过 slop 后一次性决定。横向占优（倾角 1.15）进入拖动；竖向先动则让给父级，
 * 本次手势不再改值。未判定就松手是轻点。取消与让出都不是轻点。
 */
internal class LumenSliderGesture {
    var active = false
        private set
    var intent = SliderIntent.NONE
        private set

    fun begin() {
        active = true
        intent = SliderIntent.NONE
    }

    fun move(dx: Float, dy: Float, slop: Float): SliderIntent {
        if (!active || intent != SliderIntent.NONE) return intent
        if (!dx.isFinite() || !dy.isFinite()) { intent = SliderIntent.YIELD; return intent }
        val s = if (slop.isFinite() && slop >= 0f) slop else 0f
        if (dx * dx + dy * dy <= s * s) return intent
        intent = if (abs(dx) > abs(dy) * LumenSliderPolicy.HORIZONTAL_BIAS) SliderIntent.DRAG else SliderIntent.YIELD
        return intent
    }

    /** 程序化开始拖动（例如非滚动容器里按下即跳值后继续跟手）。 */
    fun forceDrag() {
        if (active && intent == SliderIntent.NONE) intent = SliderIntent.DRAG
    }

    /** 返回是否是轻点。 */
    fun finish(cancelled: Boolean): Boolean {
        if (!active) return false
        active = false
        return !cancelled && intent == SliderIntent.NONE
    }

    fun cancel() { active = false }
}
