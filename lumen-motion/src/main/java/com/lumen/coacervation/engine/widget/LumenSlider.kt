package com.lumen.coacervation.engine.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.lumen.coacervation.engine.host.LumenAcrylicOptions
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.host.LumenFluentControl
import com.lumen.coacervation.engine.host.LumenFluentControlOptions
import com.lumen.coacervation.engine.host.LumenFluentElevation
import com.lumen.coacervation.engine.host.LumenFluentElevationOptions
import com.lumen.coacervation.engine.host.LumenControlShadow
import com.lumen.coacervation.engine.host.LumenControlShadowOptions
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenMaterialLegibility
import com.lumen.coacervation.engine.host.LumenMaterialOpacity
import com.lumen.coacervation.engine.host.LumenMaterialOpacityTarget
import com.lumen.coacervation.engine.interaction.ElasticSpringAxis
import com.lumen.coacervation.engine.liquid.LiquidChoiceDrawable
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.widget.LumenSliderPolicy as Policy

/**
 * 通用拖动滑块（自 1.5.5）：连续值或离散刻度，五种材质外观（[LumenSliderAppearance]）。
 *
 * - **胶囊**（亮度条式）：填充即数值，可放图标（越过填充时逐像素反色），相对拖动不跳值；
 * - **细轨**：滑钮 + 轨道；按在滑钮上相对拖动，按在别处跟随手指；
 * - 轻点跳到点击位置；横向超过 slop 才接管手势，竖向先动让给父级滚动；
 * - 离散刻度拖动时磁吸、越档轻触觉，松手弹簧落到准确刻度；拖过两端橡皮筋拉伸，松手回弹；
 * - 按住时手势处亮起 HDR 高光（API 34+ 且显示器有余量；否则 SDR 回退），松手淡出，静止不占余量。
 *
 * 自带手势：引擎的长按弹性与翻页器自动排除本控件，宿主不需要打 `EXCLUDED_TAG`。
 * 按压放大、拉伸、数值气泡与滑钮阴影会画出边界，直接宿主容器**必须** `clipChildren = false`、
 * `clipToPadding = false`（适配标准 §12.3）。销毁时调用 [dispose]。全部入口在主线程调用。
 */
@MainThread
public class LumenSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), LumenMaterialOpacityTarget {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val maxStretch = dp(Policy.MAX_STRETCH_DP)

    private val trackLayer = TrackLayer(context)
    private val groove = View(context)
    private val thumb = ThumbView(context)

    private var configured = false
    private var disposed = false
    private var materialOpacity = 1f
    private var range = LumenSliderRange()
    private var palette: LumenPalette = LumenPalette.neutral(false)
    private var appearance: LumenSliderAppearance = LumenSliderAppearance.Plain
    private var options = LumenSliderOptions()
    private var kind = SliderKind.PLAIN
    private var shape = LumenSliderShape.CAPSULE
    private var dark = false
    private var showLabel = false
    private var hdr = false
    private var fluent: LumenFluentControl? = null
    private var skin: LumenActivityDelegate? = null
    private var fillDrawable: LiquidChoiceDrawable? = null
    private var icon: Drawable? = null
    private var iconOnFilter: PorterDuffColorFilter? = null
    private var iconOffFilter: PorterDuffColorFilter? = null

    private var valueListener: LumenSliderValueListener? = null
    private var slideListener: LumenSliderSlideListener? = null

    /** 数值文案（数值气泡与无障碍朗读），null 时按步长自动格式化。 */
    public var valueFormatter: LumenSliderValueFormatter? = null
        set(value) { field = value; refreshValueText() }

    /** 当前值（已吸附）。 */
    public var value: Float = 0f
        private set

    public val valueFrom: Float get() = range.from
    public val valueTo: Float get() = range.to
    public val stepSize: Float get() = range.step

    /** 用户正按住拖动。 */
    public val isDragging: Boolean get() = dragging

    // ---- 运动状态（逐帧零分配）----
    private val position = ElasticSpringAxis()
    private var positionTarget = 0f
    private val press = ElasticSpringAxis()
    private var pressTarget = 0f
    private val stretch = ElasticSpringAxis()
    private val prismX = ElasticSpringAxis(1f)
    private val prismY = ElasticSpringAxis(1f)
    private var innerValue = Policy.FLUENT_INNER_NORMAL
    private var innerFrom = Policy.FLUENT_INNER_NORMAL
    private var innerTo = Policy.FLUENT_INNER_NORMAL
    private var innerStart = 0L
    private var innerDuration = 0L
    private var hovered = false
    private var dragging = false
    private var pressedVisual = false
    private var frameScheduled = false
    private var lastFrameNanos = 0L
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }

    // ---- 手势 ----
    private val gesture = LumenSliderGesture()
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var startFraction = 0f
    private var absoluteDrag = false
    private var delayedPress = false
    private var lastMoveTime = 0L
    private var lastDisplayed = 0f
    private var velocity = 0f
    private var lastStop = -1
    private var lastEdge = 0

    // ---- 几何（px，布局时计算）----
    private var trackStart = 0f
    private var trackEnd = 0f
    private val grooveRect = RectF()
    private var thumbWidth = 0f
    private var thumbHeight = 0f
    private var appliedScaleX = Float.NaN
    private var appliedScaleY = Float.NaN
    private var appliedThumbX = Float.NaN
    private var appliedThumbScaleX = Float.NaN
    private var appliedThumbScaleY = Float.NaN
    private var thumbCenterX = 0f
    private var knobX = 0f
    private var visualPress = 0f

    // ---- 数值气泡与焦点环 ----
    private var valueText = ""
    private var labelWidth = 0f
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    private val labelFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val labelRect = RectF()
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val focusRect = RectF()

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        thumb.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        trackLayer.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        groove.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        trackLayer.clipChildren = false
        trackLayer.addView(groove)
        addView(trackLayer)
        addView(thumb)
    }

    /**
     * 配置范围、配色、外观与选项，并对齐到 [value]（不带动画、不回调）。可再次调用以更换外观；
     * 旧外观创建的 Fluent 控制器先关闭。
     */
    @JvmOverloads
    public fun configure(
        range: LumenSliderRange,
        palette: LumenPalette,
        appearance: LumenSliderAppearance,
        options: LumenSliderOptions = LumenSliderOptions(),
        value: Float = range.from
    ) {
        if (disposed) return
        releaseMaterials()
        this.range = range
        this.palette = palette
        this.appearance = appearance
        this.options = options
        kind = when (appearance) {
            is LumenSliderAppearance.Skin -> SliderKind.SKIN
            is LumenSliderAppearance.Fluent -> SliderKind.FLUENT
            is LumenSliderAppearance.Mica -> SliderKind.MICA
            is LumenSliderAppearance.Prism -> SliderKind.PRISM
            LumenSliderAppearance.Plain -> SliderKind.PLAIN
        }
        shape = Policy.resolveShape(options.shape, kind)
        showLabel = Policy.showsLabel(options.valueLabel, kind)
        hdr = Policy.hdrEnabled(options.hdrHighlight, kind)
        skin = (appearance as? LumenSliderAppearance.Skin)?.lumen
        dark = Policy.isDark(palette.background)
        configured = true
        cancelInteraction()
        this.value = Policy.snap(value, range.from, range.to, range.step)
        lastStop = Policy.stopIndex(this.value, range.from, range.to, range.step)
        positionTarget = Policy.fraction(this.value, range.from, range.to)
        position.reset(positionTarget)
        press.reset(0f); pressTarget = 0f
        stretch.reset(0f); prismX.reset(1f); prismY.reset(1f)
        innerFrom = innerTargetScale(); innerTo = innerFrom; innerValue = innerFrom; innerDuration = 0L
        computeThumbSize()
        installMaterials()
        applyMaterialOpacity()
        refreshValueText()
        requestLayout()
        invalidateAll()
    }

    /** 主题切换：材质、令牌与图标颜色随之更新；数值与运动状态保持。 */
    public fun updatePalette(palette: LumenPalette) {
        if (disposed || !configured) return
        this.palette = palette
        dark = Policy.isDark(palette.background)
        fluent?.update(fluentOptions())
        fluent?.updatePalette(palette)
        installGroove()
        installThumbBackground()
        resolveColors()
        applyMaterialOpacity()
        invalidateAll()
    }

    /**
     * 材质浓度（MATERIAL_OPACITY.md §4.7）：槽体与滑钮的材质随之变薄，数值填充、刻度、把手与 HDR 高光不变
     * （它们是内容而不是材质）。空槽保留下限，始终看得出可拖动的范围。逐帧可调，不重建材质。
     */
    override fun setMaterialOpacity(value: Float) {
        if (disposed || value.isNaN()) return
        val next = value.coerceIn(0f, 1f)
        if (next == materialOpacity) return
        materialOpacity = next
        applyMaterialOpacity()
    }

    override fun materialOpacity(): Float = materialOpacity

    override fun effectiveMaterialOpacity(): Float = materialOpacity

    private fun applyMaterialOpacity() {
        if (disposed) return
        val p = materialOpacity
        val track = groove.background
        // Engine grooves map the opacity per material; plain grooves keep a visible floor as the track affordance.
        if (!LumenMaterialOpacity.set(track, p, LumenMaterialLegibility.DECORATIVE))
            track?.alpha = (255f * (TRACK_FLOOR + (1f - TRACK_FLOOR) * p)).toInt().coerceIn(0, 255)
        fluent?.setMaterialOpacity(p)
        LumenMaterialOpacity.set(thumb.background, p, LumenMaterialLegibility.DECORATIVE)
        thumb.invalidate()
    }

    /** 程序设置值（吸附、钳制）；不回调值监听。 */
    @JvmOverloads
    public fun setValue(value: Float, animate: Boolean = true) {
        if (disposed || !configured || !value.isFinite()) return
        val snapped = Policy.snap(value, range.from, range.to, range.step)
        this.value = snapped
        lastStop = Policy.stopIndex(snapped, range.from, range.to, range.step)
        refreshValueText()
        positionTarget = Policy.fraction(snapped, range.from, range.to)
        if (!dragging) {
            if (!animate) position.reset(positionTarget)
            scheduleFrame()
        }
    }

    public fun setOnValueChangeListener(listener: LumenSliderValueListener?) { valueListener = listener }

    public fun setOnSlideListener(listener: LumenSliderSlideListener?) { slideListener = listener }

    /** 胶囊内的图标（亮度、音量等）；越过填充的部分用填充上的前景色。细轨形态不显示。 */
    public fun setIcon(icon: Drawable?) {
        this.icon = icon?.mutate()
        layoutIcon()
        trackLayer.invalidate()
    }

    /** 宿主在没有布局的情况下移动本控件（动画、手动滚动）时转发，让玻璃重新取样。 */
    public fun notifyPositionChanged() {
        if (disposed) return
        notifyMaterials()
    }

    /** 关闭本控件创建的 Fluent 控制器、停止帧回调；之后的调用为空操作。 */
    public fun dispose() {
        if (disposed) return
        cancelInteraction()
        releaseMaterials()
        removeFrame()
        valueListener = null
        slideListener = null
        disposed = true
    }

    // ---------------------------------------------------------------- 材质

    private fun computeThumbSize() {
        val capsule = shape == LumenSliderShape.CAPSULE
        when {
            capsule && kind == SliderKind.PRISM -> {
                thumbHeight = dp(options.capsuleHeightDp) - dp(Policy.PRISM_CAPSULE_THUMB_INSET_DP)
                thumbWidth = thumbHeight * Policy.PRISM_CAPSULE_THUMB_RATIO
            }
            capsule -> { thumbWidth = 0f; thumbHeight = 0f }
            kind == SliderKind.FLUENT || kind == SliderKind.MICA -> {
                thumbWidth = dp(Policy.FLUENT_THUMB_DP); thumbHeight = thumbWidth
            }
            kind == SliderKind.PRISM -> {
                thumbWidth = dp(Policy.PRISM_THUMB_WIDTH_DP); thumbHeight = dp(Policy.PRISM_THUMB_HEIGHT_DP)
            }
            else -> { thumbWidth = dp(Policy.SKIN_THUMB_DP); thumbHeight = thumbWidth }
        }
        thumb.visibility = if (thumbWidth > 0f) VISIBLE else GONE
    }

    private fun installMaterials() {
        thumb.background = null
        installGroove()
        when (val a = appearance) {
            is LumenSliderAppearance.Fluent, is LumenSliderAppearance.Mica ->
                fluent = LumenFluentControl(thumb, palette, fluentOptions())
            else -> installThumbBackground()
        }
        resolveColors()
    }

    private fun prismOptions(): LumenPrismOptions =
        (appearance as? LumenSliderAppearance.Prism)?.options ?: LumenSliderAppearance.Prism.LENS

    private fun installThumbBackground() {
        if (thumbWidth <= 0f) return
        when (kind) {
            SliderKind.SKIN, SliderKind.PLAIN -> thumb.background = LiquidChoiceDrawable(thumbWidth.toInt(),
                thumbHeight.toInt(), density, palette.surface, palette.primary, palette.onPrimary, palette.textPrimary, thumb = true)
            SliderKind.PRISM -> {
                // 镜片外阴影：引擎控件软阴影（进程级纹理缓存，只画在边界外），随滑钮缩放一起放大。
                val s = prismOptions().shadow
                thumb.background = if (!s.enabled) null else LumenControlShadow.shadowOnly(thumbHeight / 2f / density, density,
                    LumenControlShadowOptions(enabled = true, blurRadiusDp = s.radiusDp.coerceIn(0f, 32f),
                        offsetYDp = s.offsetYDp.coerceIn(-8f, 16f), opacity = s.opacity.coerceIn(0f, .6f),
                        contactOpacity = PRISM_CONTACT_OPACITY, color = s.color), dark)
            }
            else -> Unit
        }
    }

    private fun installGroove() {
        val capsule = shape == LumenSliderShape.CAPSULE
        val trackHeight = trackHeightPx()
        val radius = trackHeight / 2f
        val lumen = skin
        fillDrawable = null
        groove.background = when (kind) {
            SliderKind.SKIN, SliderKind.PLAIN -> {
                if (!capsule) fillDrawable = choice().also { it.state = FILL_STATE }
                if (capsule && lumen != null) lumen.surface(palette.surface, radius / density, SurfaceRole.SELECTED_ITEM)
                else choice().also { it.state = GROOVE_STATE }
            }
            SliderKind.FLUENT, SliderKind.MICA -> GradientDrawable().apply {
                cornerRadius = if (capsule) radius else dp(Policy.FLUENT_TRACK_CORNER_DP)
                setColor(fluentTrackColor())
            }
            SliderKind.PRISM -> GradientDrawable().apply {
                cornerRadius = radius
                setColor(if (dark) 0x2EFFFFFF else 0x24000000)
            }
        }
    }

    private fun choice() = LiquidChoiceDrawable(
        (width.takeIf { it > 0 } ?: dp(Policy.DEFAULT_WIDTH_DP).toInt()), trackHeightPx().toInt().coerceAtLeast(1),
        density, palette.surface, palette.primary, palette.onPrimary, palette.textPrimary)

    private fun highContrast() = (appearance as? LumenSliderAppearance.Mica)?.highContrast == true

    private fun fluentTrackColor(): Int =
        if (highContrast()) palette.textSecondary else FluentSliderTokens.strongFill(dark, isEnabled)

    private fun fluentOptions(): LumenFluentControlOptions {
        val elevated = (appearance as? LumenSliderAppearance.Fluent)?.elevated ?: false
        return LumenFluentControlOptions(
            acrylic = LumenAcrylicOptions(enabled = false,
                fallbackColor = if (highContrast()) palette.background else FluentSliderTokens.solidFill(dark)),
            elevation = LumenFluentElevationOptions(level = LumenFluentElevation.CONTROL,
                shadowEnabled = elevated, strokeEnabled = !highContrast()),
            cornerRadiusDp = Policy.FLUENT_THUMB_CORNER_DP,
            stateLayerEnabled = false,
            focusVisualEnabled = false,
            reduceMotion = options.reduceMotion
        )
    }

    private fun releaseMaterials() {
        fluent?.close(); fluent = null
    }

    private fun trackHeightPx(): Float = when {
        shape == LumenSliderShape.CAPSULE -> dp(options.capsuleHeightDp)
        kind == SliderKind.FLUENT || kind == SliderKind.MICA -> dp(Policy.FLUENT_TRACK_DP)
        kind == SliderKind.PRISM -> dp(Policy.PRISM_TRACK_DP)
        else -> dp(Policy.SKIN_TRACK_DP)
    }

    // ---- 颜色：只在配置、主题、可用性变化时计算 ----
    private var fillTop = 0
    private var fillBottom = 0
    private var tickOn = 0
    private var tickOff = 0
    private var handleColor = 0
    private var grooveTint = 0

    private fun resolveColors() {
        val fluentKind = kind == SliderKind.FLUENT || kind == SliderKind.MICA
        when {
            fluentKind -> {
                fillTop = palette.primary; fillBottom = palette.primary
                tickOn = fluentTrackColor(); tickOff = tickOn
            }
            kind == SliderKind.PRISM -> {
                fillTop = palette.primary; fillBottom = palette.primary
                tickOn = Policy.withAlpha(palette.onPrimary, 150); tickOff = Policy.withAlpha(palette.textSecondary, 110)
            }
            else -> {
                fillTop = Policy.capsuleFillTop(palette.primary); fillBottom = palette.primary
                tickOn = Policy.withAlpha(palette.onPrimary, 140); tickOff = Policy.withAlpha(palette.textSecondary, 110)
            }
        }
        handleColor = palette.onPrimary
        // 窗口材质槽体在同色卡片上几乎看不出边界：叠一层很淡的前景色，让空槽可辨。
        grooveTint = if (kind == SliderKind.SKIN && skin != null) Policy.withAlpha(palette.textPrimary, if (dark) 0x1A else 0x12) else 0
        iconOnFilter = PorterDuffColorFilter(palette.onPrimary, PorterDuff.Mode.SRC_IN)
        iconOffFilter = PorterDuffColorFilter(palette.textSecondary, PorterDuff.Mode.SRC_IN)
        if (fluentKind) {
            labelFill.color = if (dark) FluentSliderTokens.TOOLTIP_DARK else FluentSliderTokens.TOOLTIP_LIGHT
            labelStroke.color = if (dark) FluentSliderTokens.TOOLTIP_STROKE_DARK else FluentSliderTokens.TOOLTIP_STROKE_LIGHT
            labelPaint.color = if (dark) FluentSliderTokens.TEXT_DARK else FluentSliderTokens.TEXT_LIGHT
        } else {
            labelFill.color = Policy.withAlpha(palette.surface, 255)
            labelStroke.color = Policy.withAlpha(palette.textPrimary, 28)
            labelPaint.color = palette.textPrimary
        }
        focusPaint.color = palette.primary
        val disabledAlpha = if (isEnabled || fluentKind) 1f else DISABLED_ALPHA
        trackLayer.alpha = disabledAlpha
        thumb.alpha = disabledAlpha
        trackLayer.rebuildShaders()
        thumb.resolveColors()
    }

    // ---------------------------------------------------------------- 布局

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(Policy.DEFAULT_WIDTH_DP).toInt(), widthMeasureSpec)
        val natural = maxOf(dp(Policy.MIN_TOUCH_DP), if (configured) trackHeightPx() else 0f).toInt()
        val h = resolveSize(natural, heightMeasureSpec)
        setMeasuredDimension(w, h)
        trackLayer.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        thumb.measure(MeasureSpec.makeMeasureSpec(thumbWidth.toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(thumbHeight.toInt(), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        trackLayer.layout(0, 0, w, h)
        val trackHeight = if (configured) trackHeightPx() else 0f
        grooveRect.set(0f, (h - trackHeight) / 2f, w.toFloat(), (h + trackHeight) / 2f)
        groove.layout(0, grooveRect.top.toInt(), w, grooveRect.bottom.toInt())
        val inset = when {
            thumbWidth <= 0f -> 0f
            shape == LumenSliderShape.CAPSULE -> thumbWidth / 2f + dp(Policy.PRISM_CAPSULE_THUMB_INSET_DP) / 2f
            else -> thumbWidth / 2f
        }
        trackStart = inset
        trackEnd = (w - inset).coerceAtLeast(inset)
        val thumbTop = ((h - thumbHeight) / 2f).toInt()
        thumb.layout(0, thumbTop, thumbWidth.toInt(), thumbTop + thumbHeight.toInt())
        thumb.pivotX = thumbWidth / 2f
        thumb.pivotY = thumbHeight / 2f
        trackLayer.onGeometry()
        layoutIcon()
        appliedScaleX = Float.NaN
        applyVisuals()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 槽体与填充都按 bounds 绘制，尺寸变化不重建 Drawable；只结束进行中的手势。
        if (configured && (w != oldw || h != oldh)) cancelInteraction()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        layoutIcon()
        applyVisuals()
    }

    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL

    private fun layoutIcon() {
        val drawable = icon ?: return
        val size = dp(Policy.CAPSULE_ICON_DP).toInt()
        val inset = dp(Policy.CAPSULE_ICON_INSET_DP).toInt()
        val top = ((height - size) / 2f).toInt()
        val left = if (rtl) width - inset - size else inset
        drawable.setBounds(left, top, left + size, top + size)
    }

    // ---------------------------------------------------------------- 运动

    private fun instant(): Boolean = options.reduceMotion || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow

    private fun scheduleFrame() {
        if (disposed || !configured) return
        if (instant()) {
            settleAll()
            applyVisuals()
            return
        }
        if (!frameScheduled) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun removeFrame() {
        if (frameScheduled) Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
        lastFrameNanos = 0L
    }

    private fun settleAll() {
        if (!dragging) { position.reset(positionTarget); stretch.reset(0f) }
        press.reset(pressTarget)
        prismX.reset(prismScaleTarget(Policy.PRISM_PRESSED_SCALE_X))
        prismY.reset(prismScaleTarget(Policy.PRISM_PRESSED_SCALE_Y))
        innerValue = innerTo; innerDuration = 0L
    }

    private fun prismScaleTarget(pressed: Float): Float =
        if (options.reduceMotion) 1f else 1f + (pressed - 1f) * pressTarget

    private fun onFrame(now: Long) {
        frameScheduled = false
        if (disposed || !configured) return
        val dt = if (lastFrameNanos == 0L) DEFAULT_DT else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, MAX_DT)
        lastFrameNanos = now
        var moving = false
        if (!dragging) {
            position.advance(dt, positionTarget, Policy.POSITION_STIFFNESS, Policy.POSITION_DAMPING)
            if (position.atRest(positionTarget, POSITION_REST)) position.reset(positionTarget) else moving = true
            stretch.advance(dt, 0f, Policy.STRETCH_STIFFNESS, Policy.STRETCH_DAMPING)
            if (stretch.atRest(0f, STRETCH_REST_PX)) stretch.reset(0f) else moving = true
        }
        press.advance(dt, pressTarget, Policy.PRESS_STIFFNESS, Policy.PRESS_DAMPING)
        if (press.atRest(pressTarget, PRESS_REST)) press.reset(pressTarget) else moving = true
        if (kind == SliderKind.PRISM) {
            val tx = prismScaleTarget(Policy.PRISM_PRESSED_SCALE_X)
            val ty = prismScaleTarget(Policy.PRISM_PRESSED_SCALE_Y)
            prismX.advance(dt, tx, Policy.PRISM_SCALE_STIFFNESS, Policy.PRISM_SCALE_X_DAMPING)
            prismY.advance(dt, ty, Policy.PRISM_SCALE_STIFFNESS, Policy.PRISM_SCALE_Y_DAMPING)
            if (prismX.atRest(tx, PRESS_REST)) prismX.reset(tx) else moving = true
            if (prismY.atRest(ty, PRESS_REST)) prismY.reset(ty) else moving = true
        }
        if (innerDuration > 0L) {
            val t = ((now - innerStart) / 1e6f / innerDuration).coerceIn(0f, 1f)
            innerValue = innerFrom + (innerTo - innerFrom) * Policy.fastOutSlowIn(t)
            if (t >= 1f) { innerValue = innerTo; innerDuration = 0L } else moving = true
        }
        applyVisuals()
        if (moving) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            lastFrameNanos = 0L
        }
    }

    private fun innerTargetScale(): Float = Policy.fluentInnerScale(isEnabled, pressedVisual, hovered)

    private fun retargetInner() {
        val target = innerTargetScale()
        if (target == innerTo) return
        innerFrom = innerValue
        innerTo = target
        innerStart = System.nanoTime()
        innerDuration = Policy.fluentInnerDurationMs(isEnabled, pressedVisual, hovered)
        scheduleFrame()
    }

    private fun setPressVisual(visual: Boolean) {
        if (pressedVisual == visual) return
        pressedVisual = visual
        pressTarget = if (visual) 1f else 0f
        if (visual) trackLayer.prepareHighlight()
        thumb.isPressed = visual
        retargetInner()
        scheduleFrame()
    }

    private fun applyVisuals() {
        if (!configured || width == 0 || height == 0) return
        val w = width.toFloat()
        val p = press.value.coerceIn(0f, 1f)
        visualPress = p
        val capsule = shape == LumenSliderShape.CAPSULE
        val band = stretch.value
        val lensX = if (capsule) 1f + Policy.CAPSULE_PRESS_SCALE_X * p else 1f
        val lensY = if (capsule) 1f + Policy.CAPSULE_PRESS_SCALE_Y * p else 1f
        // 先按中心做按压 lens，再按固定端做拉伸；两次缩放合成为一次 pivot 缩放，过渡连续。
        val along = Policy.stretchScaleAlong(band, w)
        val fixed = Policy.stretchPivot(band, rtl) * w
        val sx = lensX * along
        val sy = lensY * Policy.stretchScaleAcross(band, maxStretch)
        val offset = fixed * (1f - along) + along * (w / 2f) * (1f - lensX)
        val pivotX = if (kotlin.math.abs(1f - sx) < 1e-5f) w / 2f else offset / (1f - sx)
        if (sx != appliedScaleX || sy != appliedScaleY) {
            trackLayer.pivotX = pivotX
            trackLayer.pivotY = height / 2f
            trackLayer.scaleX = sx
            trackLayer.scaleY = sy
        }
        val fraction = position.value.coerceIn(0f, 1f)
        val knob = Policy.physicalX(fraction, trackStart, trackEnd, rtl)
        // Prism 胶囊：镜片约 1/4 露在填充之外（跨着边界才有透镜放大），满值时过渡到包住镜片。
        val wrap = if (capsule && thumbWidth > 0f)
            Policy.prismCapsuleWrap(fraction, thumbWidth, dp(Policy.PRISM_CAPSULE_THUMB_INSET_DP) / 2f) else 0f
        val fillEnd = if (rtl) knob - wrap else knob + wrap
        trackLayer.setFill(fillEnd, knob, p)
        knobX = knob
        thumbCenterX = offset + sx * knob
        var thumbX = appliedThumbX
        var thumbSx = 1f
        var thumbSy = 1f
        if (thumbWidth > 0f) {
            when (kind) {
                SliderKind.PRISM -> {
                    thumbSx = prismX.value * NavigationBarPrismPolicy.squashX(velocity * p, 1f)
                    thumbSy = prismY.value * NavigationBarPrismPolicy.squashY(velocity * p, 1f)
                    // 放大的镜片不伸出槽外：中心按当前缩放向内收，镜片里的内容跟着实际中心取。
                    val edge = if (capsule) (grooveRect.height() - thumbHeight) / 2f else 0f
                    val lens = Policy.lensCenter(knob, thumbWidth / 2f, maxOf(thumbSx, 1f),
                        grooveRect.left + edge, grooveRect.right - edge)
                    knobX = lens
                    thumbCenterX = offset + sx * lens
                }
                SliderKind.SKIN, SliderKind.PLAIN -> { thumbSx = 1f + SKIN_THUMB_PRESS * p; thumbSy = thumbSx }
                else -> Unit
            }
            thumbX = thumbCenterX - thumbWidth / 2f
            thumb.translationX = thumbX
            thumb.scaleX = thumbSx
            thumb.scaleY = thumbSy
            thumb.setPress(p)
        }
        val moved = sx != appliedScaleX || sy != appliedScaleY || thumbX != appliedThumbX ||
            thumbSx != appliedThumbScaleX || thumbSy != appliedThumbScaleY
        // 槽体（窗口材质表面）只随轨道层变换移动。镜片自绘轨道内容，不采样背景，无需通知。
        if (sx != appliedScaleX || sy != appliedScaleY) skin?.notifyPositionChanged(this)
        if (moved && kind == SliderKind.PRISM) thumb.invalidate()
        appliedScaleX = sx; appliedScaleY = sy; appliedThumbX = thumbX
        appliedThumbScaleX = thumbSx; appliedThumbScaleY = thumbSy
        if (showLabel || isFocused) invalidate()
    }

    private fun notifyMaterials() {
        skin?.notifyPositionChanged(this)
    }

    private fun invalidateAll() {
        appliedScaleX = Float.NaN
        trackLayer.invalidate()
        thumb.invalidate()
        invalidate()
        applyVisuals()
    }

    // ---------------------------------------------------------------- 值

    private fun commit(newValue: Float, fromUser: Boolean) {
        if (newValue == value) return
        value = newValue
        val stop = Policy.stopIndex(newValue, range.from, range.to, range.step)
        val edge = when (newValue) { range.from -> -1; range.to -> 1; else -> 0 }
        if (fromUser && options.haptics && ((range.step > 0f && stop != lastStop) || (range.step == 0f && edge != 0 && edge != lastEdge))) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        lastStop = stop
        lastEdge = edge
        refreshValueText()
        valueListener?.onValueChange(this, newValue, fromUser)
    }

    /** 键盘、无障碍、轻点：带动画落到新值并通知。 */
    private fun setFromUser(newValue: Float) {
        val snapped = Policy.snap(newValue, range.from, range.to, range.step)
        positionTarget = Policy.fraction(snapped, range.from, range.to)
        val changed = snapped != value
        commit(snapped, fromUser = true)
        if (changed) announce()
        scheduleFrame()
    }

    private fun refreshValueText() {
        if (!configured) return
        valueText = valueFormatter?.format(value)?.toString() ?: Policy.defaultLabel(value, range.step)
        labelWidth = labelPaint.measureText(valueText) + 2f * dp(Policy.LABEL_PADDING_DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) stateDescription = valueText
        if (showLabel) invalidate()
    }

    private fun announce() {
        // API 30+ 的 stateDescription 变化由平台自动通报；更早的版本补发一次选择事件（与 ProgressBar 一致）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    // ---------------------------------------------------------------- 触摸

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = configured && !disposed

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!configured || disposed || !isEnabled || width <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture.begin()
                pointerId = event.getPointerId(0)
                downX = event.x; downY = event.y
                startFraction = position.value.coerceIn(0f, 1f)
                lastDisplayed = startFraction
                velocity = 0f
                lastMoveTime = event.eventTime
                absoluteDrag = shape == LumenSliderShape.CLASSIC &&
                    kotlin.math.abs(event.x - thumbCenterX) > thumbWidth / 2f + dp(Policy.THUMB_HIT_SLOP_DP)
                delayedPress = inScrollingContainer()
                if (!delayedPress) setPressVisual(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gesture.active) return true
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                val x = event.getX(index)
                if (gesture.intent == SliderIntent.NONE) {
                    when (gesture.move(x - downX, event.getY(index) - downY, slop)) {
                        SliderIntent.DRAG -> startDrag(x)
                        SliderIntent.YIELD -> { setPressVisual(false); parent?.requestDisallowInterceptTouchEvent(false) }
                        SliderIntent.NONE -> Unit
                    }
                }
                if (gesture.intent == SliderIntent.DRAG) updateDrag(x, event.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 跟踪的手指抬起时交给剩下的第一根手指，从它的位置继续相对拖动。
                val up = event.actionIndex
                if (event.getPointerId(up) == pointerId) {
                    val next = if (up == 0) 1 else 0
                    pointerId = event.getPointerId(next)
                    downX = event.getX(next) ; downY = event.getY(next)
                    startFraction = position.value.coerceIn(0f, 1f)
                    absoluteDrag = false
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(pointerId)
                val x = if (index >= 0) event.getX(index) else downX
                val intent = gesture.intent
                val tap = gesture.finish(cancelled = false)
                when {
                    intent == SliderIntent.DRAG -> finishDrag(event.eventTime)
                    tap -> {
                        if (delayedPress) setPressVisual(true)
                        tapFraction = Policy.fractionAt(x, trackStart, trackEnd, rtl).coerceIn(0f, 1f)
                        performClick()
                        setPressVisual(false)
                    }
                    else -> setPressVisual(false)
                }
                pointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasDragging = gesture.intent == SliderIntent.DRAG
                gesture.finish(cancelled = true)
                if (wasDragging) finishDrag(event.eventTime) else setPressVisual(false)
                pointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    private var tapFraction = Float.NaN

    /** 轻点经 performClick 提交（无障碍服务也能收到点击事件）；没有轻点位置时只走默认的点击监听。 */
    override fun performClick(): Boolean {
        val handled = super.performClick()
        val f = tapFraction
        tapFraction = Float.NaN
        if (!f.isNaN() && configured && !disposed && isEnabled) setFromUser(Policy.valueAt(f, range.from, range.to))
        return handled
    }

    private fun inScrollingContainer(): Boolean {
        var p = parent
        while (p is ViewGroup) {
            if (p.shouldDelayChildPressedState()) return true
            p = p.parent
        }
        return false
    }

    private fun startDrag(x: Float) {
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        setPressVisual(true)
        // 相对拖动从越过 slop 的位置起算，不吃掉 slop 那一段，也不跳值。
        if (!absoluteDrag) {
            downX = x
            startFraction = position.value.coerceIn(0f, 1f)
        }
        lastEdge = when (value) { range.from -> -1; range.to -> 1; else -> 0 }
        slideListener?.onSlideStarted(this)
        scheduleFrame()
    }

    private fun updateDrag(x: Float, time: Long) {
        val length = trackEnd - trackStart
        val raw = if (absoluteDrag) Policy.fractionAt(x, trackStart, trackEnd, rtl)
            else Policy.dragFraction(startFraction, x - downX, length, rtl)
        val band = if (options.reduceMotion) 0f else Policy.rubberBand(Policy.overshootPx(raw, length), maxStretch)
        stretch.value = band
        stretch.velocity = 0f
        val clamped = raw.coerceIn(0f, 1f)
        val snapped = Policy.snap(Policy.valueAt(clamped, range.from, range.to), range.from, range.to, range.step)
        val displayed = Policy.magnet(clamped, range.from, range.to, range.step, options.magnetStrength)
        val elapsed = (time - lastMoveTime) / 1000f
        if (elapsed > 0f) velocity += ((displayed - lastDisplayed) / elapsed - velocity) * VELOCITY_SMOOTHING
        lastMoveTime = time
        lastDisplayed = displayed
        position.value = displayed
        position.velocity = velocity
        positionTarget = displayed
        commit(snapped, fromUser = true)
        applyVisuals()
    }

    private fun finishDrag(time: Long) {
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        if (time - lastMoveTime > RELEASE_STALE_MS) velocity = 0f
        position.velocity = velocity
        positionTarget = Policy.fraction(value, range.from, range.to)
        velocity = 0f
        setPressVisual(false)
        announce()
        slideListener?.onSlideFinished(this, value)
        scheduleFrame()
    }

    private fun cancelInteraction() {
        if (gesture.active) gesture.cancel()
        val wasDragging = dragging
        dragging = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        if (pressedVisual) {
            pressedVisual = false
            pressTarget = 0f
            thumb.isPressed = false
        }
        if (wasDragging) {
            parent?.requestDisallowInterceptTouchEvent(false)
            positionTarget = Policy.fraction(value, range.from, range.to)
            slideListener?.onSlideFinished(this, value)
        }
        velocity = 0f
    }

    override fun onDetachedFromWindow() {
        cancelInteraction()
        removeFrame()
        settleAll()
        applyVisuals()
        super.onDetachedFromWindow()
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled == isEnabled) return
        super.setEnabled(enabled)
        // 滑钮的材质（Fluent 单色描边、开关滑钮的禁用配色）按自己的 enabled 状态取令牌。
        thumb.isEnabled = enabled
        if (!enabled) cancelInteraction()
        if (configured) {
            if (kind == SliderKind.FLUENT || kind == SliderKind.MICA) {
                (groove.background as? GradientDrawable)?.setColor(fluentTrackColor())
            }
            resolveColors()
            retargetInner()
            invalidateAll()
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val next = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> true
            MotionEvent.ACTION_HOVER_EXIT -> false
            else -> hovered
        }
        if (next != hovered) { hovered = next; retargetInner() }
        return super.onHoverEvent(event)
    }

    // ---------------------------------------------------------------- 键盘与无障碍

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (configured && !disposed && isEnabled) {
            val stepValue = Policy.keyStep(range.from, range.to, range.step)
            val page = Policy.pageStep(range.from, range.to, range.step)
            val target = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> value + if (rtl) -stepValue else stepValue
                KeyEvent.KEYCODE_DPAD_LEFT -> value + if (rtl) stepValue else -stepValue
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> value + stepValue
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> value - stepValue
                KeyEvent.KEYCODE_PAGE_UP -> value + page
                KeyEvent.KEYCODE_PAGE_DOWN -> value - page
                KeyEvent.KEYCODE_MOVE_HOME -> range.from
                KeyEvent.KEYCODE_MOVE_END -> range.to
                else -> Float.NaN
            }
            if (!target.isNaN()) {
                setFromUser(target)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.SeekBar"

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!configured) return
        @Suppress("DEPRECATION")
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, range.from, range.to, value)
        if (isEnabled) {
            if (value > range.from) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            if (value < range.to) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (configured && !disposed && isEnabled) {
            // 与 SeekBar 一致：无刻度时每次 5%。
            val increment = if (range.step > 0f) range.step else (range.to - range.from) / 20f
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { setFromUser(value + increment); return true }
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { setFromUser(value - increment); return true }
                android.R.id.accessibilityActionSetProgress -> {
                    val target = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN) ?: Float.NaN
                    if (target.isFinite()) { setFromUser(target); return true }
                    return false
                }
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    // ---------------------------------------------------------------- 气泡与焦点环

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!configured) return
        if (isFocused && !isInTouchMode) {
            val inset = dp(3f)
            focusRect.set(grooveRect.left - inset, grooveRect.top - inset, grooveRect.right + inset, grooveRect.bottom + inset)
            if (thumbHeight > 0f && shape == LumenSliderShape.CLASSIC) {
                focusRect.top = minOf(focusRect.top, (height - thumbHeight) / 2f - inset)
                focusRect.bottom = maxOf(focusRect.bottom, (height + thumbHeight) / 2f + inset)
            }
            val r = focusRect.height() / 2f
            canvas.drawRoundRect(focusRect, r, r, focusPaint)
        }
        val alpha = visualPress
        if (!showLabel || alpha <= 0.01f || valueText.isEmpty()) return
        val labelHeight = dp(Policy.LABEL_HEIGHT_DP)
        val anchorTop = if (thumbHeight > 0f) (height - thumbHeight) / 2f else grooveRect.top
        val bottom = anchorTop - dp(Policy.LABEL_GAP_DP)
        val cx = Policy.labelCenter(thumbCenterX, labelWidth, width.toFloat(), maxStretch)
        labelRect.set(cx - labelWidth / 2f, bottom - labelHeight, cx + labelWidth / 2f, bottom)
        val a = (alpha * 255f).toInt()
        val fillAlpha = labelFill.alpha
        val strokeAlpha = labelStroke.alpha
        val textAlpha = labelPaint.alpha
        labelFill.alpha = fillAlpha * a / 255
        labelStroke.alpha = strokeAlpha * a / 255
        labelPaint.alpha = textAlpha * a / 255
        val corner = dp(Policy.LABEL_CORNER_DP)
        canvas.drawRoundRect(labelRect, corner, corner, labelFill)
        canvas.drawRoundRect(labelRect, corner, corner, labelStroke)
        val baseline = labelRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(valueText, cx, baseline, labelPaint)
        labelFill.alpha = fillAlpha
        labelStroke.alpha = strokeAlpha
        labelPaint.alpha = textAlpha
    }

    // ---------------------------------------------------------------- 轨道层

    /**
     * 槽体（材质背景所在的子 View）之上画填充、刻度、手势处高光与图标；Prism 镜片用 [drawLens] 把同一份
     * 内容画进滑钮。所有 Paint / Path / Shader 只在几何或配色变化时构建。
     */
    private inner class TrackLayer(context: Context) : FrameLayout(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val fillPath = Path()
        private val fillRect = RectF()
        private val handleRect = RectF()
        private var fillEnd = 0f
        private var knob = 0f
        private var press = 0f
        private var builtGain = Float.NaN
        private var builtRadius = Float.NaN

        init { setWillNotDraw(false) }

        // 槽体由滑块按形态摆放；FrameLayout 自己的布局会把它撑满整层。
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = Unit

        fun onGeometry() {
            clip.rewind()
            val r = grooveRect.height() / 2f
            clip.addRoundRect(grooveRect, r, r, Path.Direction.CW)
            rebuildShaders()
        }

        fun rebuildShaders() {
            if (grooveRect.isEmpty) return
            fillPaint.shader = if (fillTop != fillBottom) LinearGradient(0f, grooveRect.top, 0f, grooveRect.bottom,
                fillTop, fillBottom, Shader.TileMode.CLAMP) else null
            fillPaint.color = fillBottom
            builtRadius = Float.NaN
            prepareHighlight()
            invalidate()
        }

        /** 按下时读一次显示器 HDR 余量；增益或半径不变时不重建 Shader。 */
        fun prepareHighlight() {
            if (!hdr || grooveRect.isEmpty) return
            val gain = if (Build.VERSION.SDK_INT >= 34) {
                Policy.hdrGain(SliderHdrHighlightApi34.headroom(display), options.hdrHeadroom, available = true)
            } else 1f
            val radius = highlightRadius()
            if (gain == builtGain && radius == builtRadius) return
            builtGain = gain
            builtRadius = radius
            highlightPaint.shader = if (gain > 1f && Build.VERSION.SDK_INT >= 34) {
                SliderHdrHighlightApi34.shader(radius, gain)
            } else RadialGradient(0f, 0f, radius, intArrayOf(WHITE, Policy.withAlpha(WHITE, 140), 0),
                floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
        }

        private fun highlightRadius(): Float = if (shape == LumenSliderShape.CAPSULE)
            grooveRect.height() * Policy.HIGHLIGHT_CAPSULE_RADIUS
            else maxOf(thumbHeight, grooveRect.height()) * Policy.HIGHLIGHT_THUMB_RADIUS

        fun setFill(end: Float, knobX: Float, pressValue: Float) {
            if (end == fillEnd && knobX == knob && pressValue == press) return
            fillEnd = end
            knob = knobX
            press = pressValue
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            drawContent(canvas)
        }

        /** 镜片里的内容：槽体背景 + 与轨道层相同的填充与刻度（由滑钮按自身缩放放大）。 */
        fun drawLens(canvas: Canvas) {
            groove.background?.let { canvas.withTranslation(groove.left.toFloat(), groove.top.toFloat()) { it.draw(this) } }
            drawContent(canvas)
        }

        private fun drawContent(canvas: Canvas) {
            if (!configured || grooveRect.isEmpty) return
            val isRtl = rtl
            val left = if (isRtl) fillEnd else grooveRect.left
            val right = if (isRtl) grooveRect.right else fillEnd
            if (shape == LumenSliderShape.CAPSULE) drawCapsule(canvas, left, right, isRtl)
            else drawClassic(canvas, left, right)
        }

        private fun drawCapsule(canvas: Canvas, left: Float, right: Float, isRtl: Boolean) {
            val h = grooveRect.height()
            val r = h / 2f
            canvas.withClip(clip) {
                // 填充向起始侧外延一个高度，使只有末端是圆头；值为 0 时整段落在裁剪外。
                if (grooveTint != 0) {
                    groovePaint.color = grooveTint
                    drawRect(grooveRect, groovePaint)
                }
                fillRect.set(if (isRtl) left else left - h, grooveRect.top, if (isRtl) right + h else right, grooveRect.bottom)
                canvas.drawRoundRect(fillRect, r, r, fillPaint)
                // 填充末端一个半高内不画刻度：那里是把手（或镜片）的位置。
                drawTicks(canvas, left, right, dp(Policy.CAPSULE_TICK_DP) / 2f, includeEnds = false,
                    avoidX = if (thumbWidth > 0f) knob else fillEnd, avoidRadius = if (thumbWidth > 0f) thumbWidth / 2f + h * .2f else h * .5f)
                if (thumbWidth <= 0f && right - left >= h) {
                    // 填充末端的竖向把手：隐形把手的视觉提示，按压时更亮。
                    val inward = dp(Policy.CAPSULE_HANDLE_INSET_DP)
                    val x = if (isRtl) left + inward else right - inward
                    val half = dp(Policy.CAPSULE_HANDLE_WIDTH_DP) / 2f
                    val handleHeight = h * .4f
                    handleRect.set(x - half, grooveRect.centerY() - handleHeight / 2f, x + half, grooveRect.centerY() + handleHeight / 2f)
                    handlePaint.color = Policy.withAlpha(handleColor, (110 + 110 * press).toInt())
                    canvas.drawRoundRect(handleRect, half, half, handlePaint)
                }
                val cx = if (thumbWidth > 0f) fillEnd else if (isRtl) fillEnd + h * .3f else fillEnd - h * .3f
                // 高光收在填充的圆头形状之内：不溢进空槽，也不被矩形截断。
                fillPath.rewind()
                fillPath.addRoundRect(fillRect, r, r, Path.Direction.CW)
                withClip(fillPath) { drawHighlight(this, cx) }
            }
            drawIcon(canvas, left, right)
        }

        private fun drawClassic(canvas: Canvas, left: Float, right: Float) {
            val trackRadius = grooveRect.height() / 2f
            fillRect.set(left, grooveRect.top, right, grooveRect.bottom)
            val fill = fillDrawable
            if (fill != null) {
                if (right - left > 0f) {
                    fill.setBounds(left.toInt(), grooveRect.top.toInt(), right.toInt(), grooveRect.bottom.toInt())
                    fill.draw(canvas)
                }
            } else {
                if (kind == SliderKind.FLUENT || kind == SliderKind.MICA) {
                    fillPaint.shader = null
                    fillPaint.color = FluentSliderTokens.accent(palette.primary, dark, this@LumenSlider.isEnabled, pressedVisual, hovered)
                }
                val corner = if (kind == SliderKind.FLUENT || kind == SliderKind.MICA) dp(Policy.FLUENT_TRACK_CORNER_DP) else trackRadius
                if (right - left > 0f) canvas.drawRoundRect(fillRect, corner, corner, fillPaint)
            }
            if (kind == SliderKind.FLUENT || kind == SliderKind.MICA) drawFluentTicks(canvas)
            else drawTicks(canvas, left, right, grooveRect.height() * .2f, includeEnds = false,
                avoidX = if (kind == SliderKind.PRISM) knob else Float.NaN, avoidRadius = thumbWidth / 2f)
            if (hdr) drawHighlight(canvas, fillEnd)
        }

        private fun drawTicks(
            canvas: Canvas, left: Float, right: Float, radius: Float, includeEnds: Boolean,
            avoidX: Float = Float.NaN, avoidRadius: Float = 0f
        ) {
            if (!options.showTicks) return
            val count = Policy.stopCount(range.from, range.to, range.step)
            val length = trackEnd - trackStart
            if (!Policy.ticksVisible(count, length, dp(Policy.TICK_MIN_SPACING_DP))) return
            val isRtl = rtl
            val cy = grooveRect.centerY()
            val first = if (includeEnds) 0 else 1
            val last = if (includeEnds) count - 1 else count - 2
            for (i in first..last) {
                val f = Policy.fraction(Policy.stopValue(i, range.from, range.to, range.step), range.from, range.to)
                val x = Policy.physicalX(f, trackStart, trackEnd, isRtl)
                if (kotlin.math.abs(x - avoidX) < avoidRadius) continue
                tickPaint.color = if (x in left..right) tickOn else tickOff
                canvas.drawCircle(x, cy, radius, tickPaint)
            }
        }

        /** WinUI 外置刻度：轨道下方 1dp × 4dp 的竖线，含两端。 */
        private fun drawFluentTicks(canvas: Canvas) {
            if (!options.showTicks) return
            val count = Policy.stopCount(range.from, range.to, range.step)
            if (!Policy.ticksVisible(count, trackEnd - trackStart, dp(Policy.TICK_MIN_SPACING_DP))) return
            val isRtl = rtl
            val top = (height + thumbHeight) / 2f + dp(Policy.FLUENT_TICK_GAP_DP)
            val bottom = top + dp(Policy.FLUENT_TICK_LENGTH_DP)
            val half = density / 2f
            tickPaint.color = tickOn
            for (i in 0 until count) {
                val f = Policy.fraction(Policy.stopValue(i, range.from, range.to, range.step), range.from, range.to)
                val x = Policy.physicalX(f, trackStart, trackEnd, isRtl)
                canvas.drawRect(x - half, top, x + half, bottom, tickPaint)
            }
        }

        private fun drawHighlight(canvas: Canvas, cx: Float) {
            if (!hdr || press <= 0f || highlightPaint.shader == null) return
            highlightPaint.alpha = Policy.highlightAlpha(press)
            canvas.withTranslation(cx, grooveRect.centerY()) { drawCircle(0f, 0f, builtRadius, highlightPaint) }
        }

        private fun drawIcon(canvas: Canvas, left: Float, right: Float) {
            val drawable = icon ?: return
            val bounds = drawable.bounds
            if (bounds.isEmpty) return
            canvas.withClip(left, grooveRect.top, right, grooveRect.bottom) {
                drawable.colorFilter = iconOnFilter
                drawable.draw(this)
            }
            canvas.withSave {
                clipOutRect(left, grooveRect.top, right, grooveRect.bottom)
                drawable.colorFilter = iconOffFilter
                drawable.draw(this)
            }
        }
    }

    // ---------------------------------------------------------------- 滑钮

    /**
     * 背景归材质（Fluent 控件、引擎开关滑钮或 Prism 外阴影）；这里画 Fluent 内点与 Prism 镜片。
     *
     * Prism 镜片不采样背景：采样来源必须完全覆盖玻璃（引擎玻璃的回退色不透明），而滑块轨道层大部分透明，
     * 采样结果在浅色主题下是一块白、深色下一块黑。镜片改为把轨道内容原样画进滑钮，由滑钮自身缩放放大；
     * 轨道以外保持透明，透出真实背景。亮边、内阴影、表面色与按压光取自 [LumenPrismOptions]。
     */
    private inner class ThumbView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private val rimRect = RectF()
        private val lensPath = Path()
        private var tintAlpha = 0
        private var press = 0f
        private var inner = Float.NaN

        fun resolveColors() {
            paint.color = if (kind == SliderKind.PRISM) WHITE else palette.primary
            buildLens()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            buildLens()
        }

        private fun buildLens() {
            if (kind != SliderKind.PRISM || width <= 0 || height <= 0) return
            val o = prismOptions()
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            rect.set(0f, 0f, w, h)
            lensPath.rewind()
            lensPath.addRoundRect(rect, r, r, Path.Direction.CW)
            val tintRgb = o.surface.tintColor ?: if (dark) PRISM_DARK_TINT else PRISM_LIGHT_TINT
            tintAlpha = ((o.surface.tintOpacity ?: DEFAULT_PRISM_TINT) * 255f).toInt().coerceIn(0, 255)
            tintPaint.color = Policy.withAlpha(tintRgb, tintAlpha)
            innerPaint.shader = if (o.innerShadow.enabled) LinearGradient(0f, 0f, 0f, h * .55f,
                Policy.withAlpha(o.innerShadow.color, (o.innerShadow.opacity * 255f).toInt()), 0, Shader.TileMode.CLAMP) else null
            glowPaint.shader = if (o.press.enabled && o.press.glowOpacity > 0f) RadialGradient(w / 2f, h / 2f, maxOf(w, h) * .6f,
                Policy.withAlpha(WHITE, (o.press.glowOpacity * 255f).toInt()), 0, Shader.TileMode.CLAMP) else null
            val stroke = o.highlight.widthDp * density
            rimPaint.strokeWidth = stroke
            rimRect.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f)
            // 亮边按高光角度从亮到淡再到亮：45° 时左上与右下亮、两侧淡，与棱镜玻璃的高光方向一致。
            val angle = Math.toRadians(o.highlight.angleDegrees.toDouble())
            val dx = (kotlin.math.cos(angle) * w / 2).toFloat()
            val dy = (kotlin.math.sin(angle) * h / 2).toFloat()
            val bright = Policy.withAlpha(o.highlight.color, (o.highlight.opacity * 255f).toInt())
            rimPaint.shader = if (o.highlight.enabled && o.highlight.opacity > 0f && stroke > 0f) LinearGradient(
                w / 2f - dx, h / 2f - dy, w / 2f + dx, h / 2f + dy,
                intArrayOf(bright, Policy.scaleAlpha(bright, .25f), bright), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP) else null
        }

        fun setPress(value: Float) {
            if (value == press && innerValue == inner) return
            press = value
            inner = innerValue
            invalidate()
        }

        override fun onCreateDrawableState(extraSpace: Int): IntArray {
            // 引擎开关滑钮的"选中"配色：滑钮总是落在填充上。
            val state = super.onCreateDrawableState(extraSpace + 1)
            return mergeDrawableStates(state, CHECKED_STATE)
        }

        override fun onDraw(canvas: Canvas) {
            when (kind) {
                SliderKind.FLUENT, SliderKind.MICA -> {
                    paint.color = FluentSliderTokens.accent(palette.primary, dark, this@LumenSlider.isEnabled, pressedVisual, hovered)
                    val r = dp(Policy.FLUENT_INNER_DP) / 2f * innerValue
                    canvas.drawCircle(width / 2f, height / 2f, r, paint)
                }
                SliderKind.PRISM -> drawPrism(canvas)
                else -> Unit
            }
        }

        private fun drawPrism(canvas: Canvas) {
            val r = height / 2f
            val lens = (press * 255f).toInt().coerceIn(0, 255)
            if (lens > 0) {
                // 在 Canvas 接收者的 lambda 外取本 View 的尺寸与位置（Canvas 也有 width）。
                val dx = width / 2f - knobX
                val dy = -top.toFloat()
                canvas.withClip(lensPath) {
                    // 镜片中心对准轨道上的滑钮位置；滑钮自身的缩放就是放大倍数。
                    withTranslation(dx, dy) { trackLayer.drawLens(this) }
                    tintPaint.alpha = (tintAlpha * lens / 255 * materialOpacity).toInt()
                    drawRect(rect, tintPaint)
                    if (innerPaint.shader != null) { innerPaint.alpha = lens; drawRect(rect, innerPaint) }
                    if (glowPaint.shader != null) { glowPaint.alpha = lens; drawRect(rect, glowPaint) }
                }
                if (rimPaint.shader != null) {
                    rimPaint.alpha = lens
                    val rr = r - rimPaint.strokeWidth / 2f
                    canvas.drawRoundRect(rimRect, rr, rr, rimPaint)
                }
            }
            val cover = 255 - lens
            if (cover > 0) {
                paint.alpha = cover
                canvas.drawRoundRect(rect, r, r, paint)
                paint.alpha = 255
            }
        }
    }

    private companion object {
        const val PRISM_LIGHT_TINT = 0xFAFAFA
        const val PRISM_DARK_TINT = 0x121212
        const val DEFAULT_PRISM_TINT = .06f
        const val PRISM_CONTACT_OPACITY = .04f
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DISABLED_ALPHA = .38f
        /** Empty-track alpha at zero material opacity: the drag range stays discoverable. */
        const val TRACK_FLOOR = .35f
        const val SKIN_THUMB_PRESS = .12f
        const val DEFAULT_DT = 1f / 60f
        const val MAX_DT = .05f
        const val POSITION_REST = 1e-4f
        const val PRESS_REST = 1e-3f
        const val STRETCH_REST_PX = .1f
        const val VELOCITY_SMOOTHING = .6f
        const val RELEASE_STALE_MS = 100L
        val CHECKED_STATE = intArrayOf(android.R.attr.state_checked)
        val GROOVE_STATE = intArrayOf(android.R.attr.state_enabled)
        val FILL_STATE = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked)
    }
}
