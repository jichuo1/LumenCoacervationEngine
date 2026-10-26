package com.lumen.coacervation.engine.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.glow.GlowLegibilityPolicy
import com.lumen.coacervation.engine.touch.GlowConfig
import com.lumen.coacervation.engine.host.LumenMaterialOpacity
import com.lumen.coacervation.engine.host.LumenMaterialOpacityTarget
import com.lumen.coacervation.engine.touch.GlowFrame
import com.lumen.coacervation.engine.touch.GlowState
import com.lumen.coacervation.engine.touch.TouchGlowRenderer
import com.lumen.coacervation.engine.touch.reachablePileRoomPx
import kotlin.math.abs
import kotlin.math.roundToInt

/** 触点高光的基础 alpha：与改造前逐字一致，改造只加自适应，不改观感基准。 */
private const val NAVIGATION_GLOW_BASE_ALPHA = 72

/** 可读性补偿的标签光晕：固定半径，alpha 随补偿量线性增长到此上限。 */
private const val LABEL_HALO_DP = 3f
private const val LABEL_HALO_ALPHA = 0.55f

public enum class NavigationBarSurface { BAR, SELECTION }
public data class NavigationBarColors(val text: Int, val selectedText: Int, val highlight: Int)

/**
 * Native floating capsule. The host owns page navigation and the shared skin/backdrop session.
 *
 * 背景可由宿主包一层控件软阴影；阴影画在胶囊之外，因此承载本 View 的父容器也必须
 * `clipChildren = false`。本 View 自身不 `clipToOutline`，只给触点光晕按胶囊轮廓裁剪。
 */
@SuppressLint("ViewConstructor")
public class LumenNavigationBar(
    context: Context,
    titles: List<CharSequence>,
    icons: IntArray,
    private val colors: NavigationBarColors,
    backgroundFactory: (NavigationBarSurface, Float) -> Drawable,
    private val onSelect: (Int) -> Unit,
    private val onUserInteraction: () -> Unit,
    private val onVisualMovement: () -> Unit
) : FrameLayout(context), LumenMaterialOpacityTarget {
    private val count = titles.size.also {
        require(it in 1..NavigationBarMotion.MAX_ITEMS && icons.size == it) {
            "LumenNavigationBar supports 1..${NavigationBarMotion.MAX_ITEMS} items with one icon each"
        }
    }
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val inset = dp(NavigationBarMotion.INSET_DP.toFloat())
    private val maximumTravel = dp(NavigationBarMotion.MAX_TRAVEL_DP)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tintSteps = Array(33) { ColorStateList.valueOf(ColorUtils.blendARGB(colors.text, colors.selectedText, it / 32f)) }
    private val renderedTint = IntArray(count) { -1 }
    private var legibilityStep = 0
    private val iconViews = ArrayList<ImageView>(count)
    private val labelViews = ArrayList<TextView>(count)
    private val items = ArrayList<NavigationItem>(count)
    private val selection = View(context).apply {
        background = backgroundFactory(NavigationBarSurface.SELECTION, 28f)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    /** Optional liquid-glass selection (see [setPrismSelection]); null keeps the classic selection box. */
    private var prism: PrismSelectionRenderer? = null
    private var prismFrames: ValueAnimator? = null
    /** Last target handed to [animatePress]; prism springs retarget to it when options change mid-press. */
    private var pressTarget = 0f
    private var appliedRestAlpha = 255
    private val glow = GlowView(context)
    private val gesture = NavigationBarGesture()
    private var selected = 0
    private var pagerPosition = 0f
    private var displayedPosition = 0f
    private var pendingScrubPage: Int? = null
    private var touchActive = false
    private var ignorePointers = false
    private var interactionNotified = false
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var initialIndicator = 0f
    private var initialOffsetX = 0f
    private var initialOffsetY = 0f
    private var lastMoveTime = 0L
    private var press = 0f
    private var pressVelocity = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var offsetVelocityX = 0f
    private var offsetVelocityY = 0f
    private var glowX = 0f
    private var glowY = 0f
    private var pressAnimator: ValueAnimator? = null
    private var reboundAnimator: ValueAnimator? = null
    private var pressGeneration = 0L
    private var reboundGeneration = 0L
    private var indicatorSettling = false
    private var disposed = false
    private var materialOpacity = 1f
    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL
    private val contentWidth: Float get() = (width - inset * 2f).coerceAtLeast(0f)
    private val slotWidth: Float get() = contentWidth / count

    init {
        background = backgroundFactory(NavigationBarSurface.BAR, 32f)
        clipChildren = false
        clipToPadding = false
        // 背景里裹着控件软阴影：本 View 的 clipToOutline 会连背景一起裁掉外侧阴影，
        // 因此只让触点光晕按胶囊轮廓裁剪，栏本身不再裁。
        clipToOutline = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        addView(selection)
        glow.outlineProvider = outlineProvider
        glow.clipToOutline = true
        addView(glow)
        repeat(count) { index ->
            val item = NavigationItem(context, index).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = titles[index]
                setOnClickListener {
                    if (!disposed && this@LumenNavigationBar.isEnabled) {
                        notifyUserInteraction()
                        pendingScrubPage = null
                        onSelect(index)
                        if (!touchActive) {
                            glowX = left + width / 2f
                            glowY = this@LumenNavigationBar.height / 2f
                            animatePress(.8f) { animatePress(0f) }
                        }
                    }
                }
                setOnFocusChangeListener { _, _ -> glow.invalidate() }
                setOnKeyListener { _, code, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false
                    else {
                        val delta = when (code) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> if (rtl) 1 else -1
                            KeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) -1 else 1
                            else -> 0
                        }
                        if (delta != 0 && index + delta in 0 until count) items[index + delta].requestFocus()
                        else false
                    }
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(icons[index])
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = TextView(context).apply {
                text = titles[index]
                textSize = 12f
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(26f).roundToInt(), dp(26f).roundToInt()))
            item.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3f).roundToInt()
            })
            iconViews += icon
            labelViews += label
            items += item
            addView(item)
        }
        setSelectedPage(0)
        applyVisuals()
    }

    /**
     * 选中框的棱镜（液态玻璃）效果；null 恢复经典选中框（与未调用时逐帧一致）。
     * 可随时切换或改参，不影响选中状态、手势与无障碍。详见 [LumenPrismSelectionOptions]。
     */
    fun setPrismSelection(options: LumenPrismSelectionOptions?) {
        if (disposed) return
        if (options == null) {
            val renderer = prism ?: return
            stopPrismFrames()
            renderer.release()
            prism = null
            selection.background?.alpha = 255
            appliedRestAlpha = 255
            renderedTint.fill(-1)
            applyVisuals(notifyPositionChanged = false)
            invalidate()
            return
        }
        val renderer = prism ?: PrismSelectionRenderer(density).also { prism = it }
        renderer.configure(options, tintSteps[tintSteps.size - 1].defaultColor, pressTarget)
        renderedTint.fill(-1)
        applyVisuals(notifyPositionChanged = false)
        invalidate()
    }

    val isPrismSelectionEnabled: Boolean get() = prism != null

    /** Continuous pager progress is authoritative except while the user owns a scrub preview. */
    fun setPageProgress(value: Float, notifyPositionChanged: Boolean = true) {
        if (disposed) return
        val previous = pagerPosition
        pagerPosition = NavigationBarMotion.position(value, count)
        if (touchActive) return
        pendingScrubPage?.let { target ->
            val distance = abs(pagerPosition - target)
            if (distance > .002f && distance <= abs(previous - target) + .01f) return
            pendingScrubPage = null
        }
        indicatorSettling = false
        displayedPosition = pagerPosition
        applyVisuals(notifyPositionChanged)
    }

    /** Changes semantics only; it does not jump the lens ahead of the pager's continuous position. */
    fun setSelectedPage(index: Int) {
        if (disposed) return
        selected = index.coerceIn(0, count - 1)
        if (pendingScrubPage != null && pendingScrubPage != selected) {
            pendingScrubPage = null
            indicatorSettling = false
            if (!touchActive) displayedPosition = pagerPosition
        }
        for (i in 0 until count) items.getOrNull(i)?.isSelected = i == selected
        applyVisuals()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 默认宽度按项数放宽（每项约 72dp，至少 320dp）；宿主给了确定宽度时以宿主为准。
        val measuredW = resolveSize(dp(maxOf(320f, count * 72f)).roundToInt(), widthMeasureSpec)
        val available = (measuredW - inset * 2f).coerceAtLeast(0f)
        var naturalItemHeight = 0
        for (i in 0 until count) {
            val itemWidth = ((i + 1) * available / count).roundToInt() - (i * available / count).roundToInt()
            items[i].measure(MeasureSpec.makeMeasureSpec(itemWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            naturalItemHeight = maxOf(naturalItemHeight, items[i].measuredHeight)
        }
        val desiredHeight = maxOf(dp(NavigationBarMotion.BAR_HEIGHT_DP.toFloat()).roundToInt(),
            naturalItemHeight + (inset * 2f).roundToInt())
        val measuredH = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(measuredW, measuredH)
        val itemHeight = (measuredH - inset * 2f).roundToInt().coerceAtLeast(0)
        for (i in 0 until count) {
            val itemWidth = ((i + 1) * available / count).roundToInt() - (i * available / count).roundToInt()
            items[i].measure(MeasureSpec.makeMeasureSpec(itemWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(itemHeight, MeasureSpec.EXACTLY))
        }
        selection.measure(MeasureSpec.makeMeasureSpec((available / count).roundToInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(itemHeight, MeasureSpec.EXACTLY))
        glow.measure(MeasureSpec.makeMeasureSpec(measuredW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(measuredH, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val topInset = inset.roundToInt()
        for (i in 0 until count) {
            val physical = if (rtl) count - 1 - i else i
            val x = (inset + physical * slotWidth).roundToInt()
            items[i].layout(x, topInset, x + items[i].measuredWidth, topInset + items[i].measuredHeight)
        }
        selection.layout(topInset, topInset, topInset + selection.measuredWidth, topInset + selection.measuredHeight)
        glow.layout(0, 0, width, height)
        applyVisuals()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) resetInteraction()
        invalidateOutline()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val renderer = prism
        // Rebuilds the selected-colour copy only when item geometry or label scroll changed; steady frames skip it.
        if (renderer != null && width > 0 && height > 0) {
            renderer.prepare(width, height, items, iconViews, labelViews, selection.width, selection.height)
        }
        super.dispatchDraw(canvas)
        renderer?.draw(canvas)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val renderer = prism
        if (renderer == null || !renderer.ready || child !is NavigationItem) return super.drawChild(canvas, child, drawingTime)
        // Inside the pill the selected-colour copy replaces the real item, as the reference's tinted tab row does.
        val save = canvas.save()
        canvas.clipOutPath(renderer.pillPath)
        val result = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)
        return result
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (disposed || !isEnabled || width <= 0) return false
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            ignorePointers = true
            cancelTouch()
            return true
        }
        if (ignorePointers && event.actionMasked != MotionEvent.ACTION_DOWN) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ignorePointers = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignorePointers = false
                touchActive = true
                interactionNotified = false
                pointerId = event.getPointerId(0)
                downRawX = event.rawX; downRawY = event.rawY
                downLocalX = event.x; downLocalY = event.y
                initialIndicator = displayedPosition
                // A bounded rebound may have an unclamped spring value; grab the frame actually shown.
                initialOffsetX = translationX; initialOffsetY = translationY
                offsetX = initialOffsetX; offsetY = initialOffsetY
                lastMoveTime = event.eventTime
                pendingScrubPage = null
                stopRebound()
                val selectedLeft = inset + NavigationBarMotion.physicalSlot(displayedPosition, count, rtl) * slotWidth
                val index = NavigationBarMotion.indexAt(event.x, contentWidth, inset, count, rtl)
                gesture.begin(index, event.x >= selectedLeft && event.x <= selectedLeft + slotWidth)
                glowX = event.x; glowY = event.y
                animatePress(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive || event.getPointerId(0) != pointerId) { cancelTouch(); return true }
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (gesture.move(dx, dy, slop)) notifyUserInteraction()
                if (gesture.intent == NavigationBarIntent.SCRUB) {
                    displayedPosition = NavigationBarMotion.scrubPosition(initialIndicator, dx, slotWidth, count, rtl)
                }
                val factor = NavigationBarMotion.displacementScale(dx, dy, maximumTravel, dp(48f))
                val nextX = initialOffsetX + dx * factor
                val nextY = initialOffsetY + dy * factor
                val bounded = NavigationBarMotion.travelClampScale(nextX, nextY, maximumTravel)
                val elapsed = (event.eventTime - lastMoveTime).coerceAtLeast(1L)
                offsetVelocityX = ((nextX * bounded - offsetX) * 1000f / elapsed).coerceIn(-dp(240f), dp(240f))
                offsetVelocityY = ((nextY * bounded - offsetY) * 1000f / elapsed).coerceIn(-dp(240f), dp(240f))
                offsetX = nextX * bounded; offsetY = nextY * bounded
                lastMoveTime = event.eventTime
                glowX = downLocalX + dx
                glowY = downLocalY + dy
                applyVisuals()
            }
            MotionEvent.ACTION_UP -> {
                if (!touchActive) return true
                val localX = downLocalX + event.rawX - downRawX
                val localY = downLocalY + event.rawY - downRawY
                val releaseIndex = if (localX in 0f..width.toFloat() && localY in 0f..height.toFloat())
                    NavigationBarMotion.indexAt(localX, contentWidth, inset, count, rtl) else -1
                val scrubbed = gesture.intent == NavigationBarIntent.SCRUB
                val target = gesture.finish(false, displayedPosition, releaseIndex, count)
                if (event.eventTime - lastMoveTime > 100L) { offsetVelocityX = 0f; offsetVelocityY = 0f }
                if (target != null) {
                    if (scrubbed) {
                        notifyUserInteraction()
                        pendingScrubPage = target.takeIf { abs(pagerPosition - it) > .002f }
                        onSelect(target)
                    } else items[target].performClick()
                }
                touchActive = false
                pointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                animatePress(0f)
                reboundTo(if (scrubbed && target != null) target.toFloat() else pagerPosition)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun notifyUserInteraction() {
        if (!touchActive || !interactionNotified) {
            interactionNotified = true
            onUserInteraction()
        }
    }

    private fun cancelTouch() {
        gesture.cancel()
        touchActive = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        pendingScrubPage = null
        offsetVelocityX = 0f; offsetVelocityY = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        animatePress(0f)
        reboundTo(pagerPosition)
    }

    private fun animatePress(target: Float, after: (() -> Unit)? = null) {
        pressTarget = target
        prism?.setPressTarget(target)
        pressGeneration++
        pressAnimator?.cancel(); pressAnimator = null
        if (disposed) return
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            press = target; pressVelocity = 0f; applyVisuals(); after?.invoke(); return
        }
        val token = pressGeneration
        val spring = LumenSpring(press, target, pressVelocity)
        val animation = ValueAnimator.ofFloat(0f, NavigationBarMotion.SPRING_DURATION_MS / 1000f).apply {
            duration = NavigationBarMotion.SPRING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (token == pressGeneration) {
                    val seconds = it.animatedFraction * NavigationBarMotion.SPRING_DURATION_MS / 1000f
                    press = spring.value(seconds).coerceIn(0f, 1f)
                    pressVelocity = spring.velocity(seconds)
                    applyVisuals()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != pressGeneration) return
                    pressAnimator = null; press = target; pressVelocity = 0f
                    applyVisuals(); after?.invoke()
                }
            })
        }
        pressAnimator = animation
        animation.start()
    }

    private fun reboundTo(target: Float) {
        stopRebound()
        if (disposed) return
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            offsetX = 0f; offsetY = 0f; offsetVelocityX = 0f; offsetVelocityY = 0f
            displayedPosition = NavigationBarMotion.position(target, count)
            applyVisuals(); return
        }
        val token = reboundGeneration
        val x = LumenSpring(offsetX, 0f, offsetVelocityX)
        val y = LumenSpring(offsetY, 0f, offsetVelocityY)
        val indicator = LumenSpring(displayedPosition, target)
        indicatorSettling = true
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NavigationBarMotion.SPRING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (token == reboundGeneration) {
                    val seconds = it.animatedFraction * NavigationBarMotion.SPRING_DURATION_MS / 1000f
                    offsetX = x.value(seconds); offsetY = y.value(seconds)
                    offsetVelocityX = x.velocity(seconds); offsetVelocityY = y.velocity(seconds)
                    if (indicatorSettling) displayedPosition = NavigationBarMotion.position(indicator.value(seconds), count)
                    applyVisuals()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != reboundGeneration) return
                    reboundAnimator = null
                    offsetX = 0f; offsetY = 0f; offsetVelocityX = 0f; offsetVelocityY = 0f
                    if (indicatorSettling) displayedPosition = NavigationBarMotion.position(target, count)
                    indicatorSettling = false
                    applyVisuals()
                }
            })
        }
        reboundAnimator = animation
        animation.start()
    }

    private fun stopRebound() {
        reboundGeneration++
        reboundAnimator?.cancel(); reboundAnimator = null
        indicatorSettling = false
    }

    private fun applyVisuals(notifyPositionChanged: Boolean = true) {
        if (disposed) return
        var moved = false
        val travel = NavigationBarMotion.travelClampScale(offsetX, offsetY, maximumTravel)
        val x = offsetX * travel
        val y = offsetY * travel
        if (translationX != x) { translationX = x; moved = true }
        if (translationY != y) { translationY = y; moved = true }
        val slot = NavigationBarMotion.physicalSlot(displayedPosition, count, rtl)
        val lensX = slot * slotWidth
        val renderer = prism
        if (renderer == null) {
            val scaleX = NavigationBarMotion.lensScaleX(press)
            val scaleY = NavigationBarMotion.lensScaleY(press)
            if (selection.translationX != lensX) { selection.translationX = lensX; moved = true }
            if (selection.scaleX != scaleX) { selection.scaleX = scaleX; moved = true }
            if (selection.scaleY != scaleY) { selection.scaleY = scaleY; moved = true }
        } else if (applyPrism(renderer, lensX)) moved = true
        for (i in 0 until count) {
            // Prism mode: every real item keeps the plain colour; the pill's copy carries the selected colour.
            val step = if (renderer != null) 0 else ((1f - abs(displayedPosition - i)).coerceIn(0f, 1f) * 32f).roundToInt()
            if (renderedTint[i] != step && i < iconViews.size) {
                renderedTint[i] = step
                iconViews[i].imageTintList = tintSteps[step]
                labelViews[i].setTextColor(tintSteps[step])
            }
        }
        glow.updateGesture(
            press = press,
            offsetX = offsetX,
            offsetY = offsetY,
            centerX = glowX,
            centerY = glowY,
            barWidth = width,
            barHeight = height,
            viewShiftX = x - initialOffsetX,
            viewShiftY = y - initialOffsetY
        )
        if (renderer != null) {
            if (renderer.needsFrames()) startPrismFrames() else stopPrismFrames()
        }
        if (moved && notifyPositionChanged) onVisualMovement()
    }

    /**
     * Lays out the prism pill and the selection View's transform for this frame. The bar re-records (pill copy and
     * item clip live in its display list) only when the pill changed. Returns true when the selection View moved.
     */
    private fun applyPrism(renderer: PrismSelectionRenderer, lensX: Float): Boolean {
        val left = renderer.pill.left; val top = renderer.pill.top; val right = renderer.pill.right; val bottom = renderer.pill.bottom
        val progress = renderer.progress
        renderer.layoutPill(selection.left + lensX, selection.top.toFloat(), selection.width.toFloat(), selection.height.toFloat(),
            displayedPosition, press, count,
            // Only the user's own motion squashes the pill: scrubbing or the rebound after it, not pager swipes.
            trackVelocity = (touchActive && gesture.intent == NavigationBarIntent.SCRUB) || indicatorSettling,
            still = !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled())
        if (renderer.pill.left != left || renderer.pill.top != top || renderer.pill.right != right ||
            renderer.pill.bottom != bottom || renderer.progress != progress) invalidate()
        var moved = false
        if (selection.translationX != lensX) { selection.translationX = lensX; moved = true }
        if (selection.scaleX != renderer.scaleX) { selection.scaleX = renderer.scaleX; moved = true }
        if (selection.scaleY != renderer.scaleY) { selection.scaleY = renderer.scaleY; moved = true }
        // The rest fill fades through the drawable's own alpha: no offscreen layer, no doubled rim.
        val restAlpha = ((1f - renderer.progress) * 255f).roundToInt()
        if (restAlpha != appliedRestAlpha) { appliedRestAlpha = restAlpha; selection.background?.alpha = restAlpha }
        return moved
    }

    /** Prism frame source tick: advances only the pill. No glow update and no host movement callback. */
    private fun prismFrame() {
        val renderer = prism ?: run { stopPrismFrames(); return }
        // The press and rebound animators already drive applyVisuals this frame.
        if (pressAnimator?.isRunning == true || reboundAnimator?.isRunning == true) return
        applyPrism(renderer, NavigationBarMotion.physicalSlot(displayedPosition, count, rtl) * slotWidth)
        if (!renderer.needsFrames()) stopPrismFrames()
    }

    /** Keeps the jelly springs and velocity decay ticking after the press and rebound animators have ended. */
    private fun startPrismFrames() {
        if (prismFrames != null || disposed || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) return
        val frames = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NavigationBarMotion.SPRING_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { prismFrame() }
            // Animator scale dropping to 0 ends it from outside: forget it so the next motion can start a new one.
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (prismFrames === animation) prismFrames = null }
            })
        }
        // start() delivers the first update synchronously: publish the animator first, or applyVisuals re-enters here.
        prismFrames = frames
        frames.start()
    }

    private fun stopPrismFrames() {
        val frames = prismFrames ?: return
        prismFrames = null
        frames.cancel()
    }

    /**
     * 悬浮栏可读性补偿（`GlowFloatingChrome` 驱动）：前景同向加强 + 标签光晕，0 = 原色、无光晕。
     * 量化到 1/32：过渡动画逐帧调用，同一档不重复改色。选中强调色只推一小份，保住色相。
     */
    fun setLegibility(boost: Float, haloColor: Int) {
        if (disposed) return
        val step = (boost.coerceIn(0f, 1f) * 32f).roundToInt()
        if (step == legibilityStep) return
        legibilityStep = step
        val amount = step / 32f
        val text = GlowLegibilityPolicy.foreground(colors.text, amount)
        val selectedText = GlowLegibilityPolicy.foreground(
            colors.selectedText, amount, GlowLegibilityPolicy.ACCENT_FOREGROUND_PUSH
        )
        for (i in tintSteps.indices) {
            tintSteps[i] = ColorStateList.valueOf(ColorUtils.blendARGB(text, selectedText, i / 32f))
        }
        renderedTint.fill(-1)
        // The copy is a coverage mask: a colour change needs only the new paint colour and one re-record.
        prism?.let { it.setColor(tintSteps[tintSteps.size - 1].defaultColor); invalidate() }
        // 光晕半径固定、只调 alpha：半径逐帧变会让文字阴影的模糊核反复重建。
        val halo = ColorUtils.setAlphaComponent(haloColor, (LABEL_HALO_ALPHA * amount * 255f).roundToInt())
        for (label in labelViews) {
            if (step == 0) label.setShadowLayer(0f, 0f, 0f, 0) else label.setShadowLayer(dp(LABEL_HALO_DP), 0f, 0f, halo)
        }
        applyVisuals(notifyPositionChanged = false)
    }

    private fun resetInteraction() {
        gesture.cancel()
        touchActive = false; ignorePointers = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        pendingScrubPage = null
        pressGeneration++; pressAnimator?.cancel(); pressAnimator = null
        stopRebound()
        press = 0f; pressVelocity = 0f
        stopPrismFrames()
        prism?.resetMotion()
        offsetX = 0f; offsetY = 0f; offsetVelocityX = 0f; offsetVelocityY = 0f
        displayedPosition = pagerPosition
        parent?.requestDisallowInterceptTouchEvent(false)
        applyVisuals()
        // press 已归零 => 本帧起光晕不可见，此刻 reset 不构成可见跳变。
        glow.resetGestureState()
    }


    /**
     * 材质浓度（MATERIAL_OPACITY.md §4.7）：只作用于栏背景（引擎背景按其材质映射，宿主自己的 Drawable 不变）；
     * 选中框与文字是状态与内容，保持不变。逐帧可调。
     */
    override fun setMaterialOpacity(value: Float) {
        if (disposed || value.isNaN()) return
        val next = value.coerceIn(0f, 1f)
        if (next == materialOpacity) return
        materialOpacity = next
        LumenMaterialOpacity.set(background, next)
    }

    override fun materialOpacity(): Float = materialOpacity

    override fun effectiveMaterialOpacity(): Float = materialOpacity

    fun dispose() {
        resetInteraction()
        prism?.release()
        disposed = true
    }

    override fun onDetachedFromWindow() {
        resetInteraction()
        super.onDetachedFromWindow()
    }

    private inner class NavigationItem(context: Context, private val index: Int) : LinearLayout(context) {
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
            info.isSelected = selected == index
        }

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_SPACE && !event.isCanceled) return performClick()
            return super.onKeyUp(keyCode, event)
        }
    }

    /**
     * 触点高光：跟随手指的椭圆光晕，几何由 [AdaptiveGlowPolicy] 逐帧算出（见同名文件顶部的
     * 连续域规则），绘制由 [TouchGlowRenderer] 完成（shader 只建一次，每帧只写 localMatrix
     * 与 paint.alpha）。速度取渲染位移的差分估计——拖动、回弹、打断三条路径同一定义，
     * 避免"哪个信号新鲜用哪个"的模式切换。reset 只发生在 press == 0 的不可见边界。
     */
    private inner class GlowView(context: Context) : View(context) {
        private val radius = dp(64f)
        private val renderer = TouchGlowRenderer(colors.highlight, radius)
        private val config = GlowConfig.create(
            density = density,
            maxTravelPx = maximumTravel,
            travelEpsPx = dp(GlowConfig.TRAVEL_EPS_DP),
            velocityRefPxPerSec = dp(GlowConfig.VELOCITY_REF_DP_PER_SEC),
            edgeBandPx = dp(GlowConfig.EDGE_BAND_DP),
            continuousEdgePile = true
        )
        private val frame = GlowFrame()
        private val state = GlowState()
        private var lastUpdateNanos = 0L
        private var lastOffsetX = 0f
        private var lastOffsetY = 0f
        private val screenLoc = IntArray(2)
        private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            color = ColorUtils.setAlphaComponent(colors.selectedText, 160)
        }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        /** 每个 press/rebound 动画帧与每个 MOVE 调用一次；由 applyVisuals 驱动，不新增时钟。 */
        fun updateGesture(
            press: Float,
            offsetX: Float,
            offsetY: Float,
            centerX: Float,
            centerY: Float,
            barWidth: Int,
            barHeight: Int,
            viewShiftX: Float = 0f,
            viewShiftY: Float = 0f
        ) {
            val now = System.nanoTime()
            val dt = if (lastUpdateNanos == 0L) GlowState.DEFAULT_DT_SECONDS
            else ((now - lastUpdateNanos).coerceAtLeast(0L)) / 1_000_000_000f
            lastUpdateNanos = now
            val elapsed = dt.coerceAtLeast(GlowState.MIN_DT_SECONDS)
            frame.press = press
            frame.offsetX = offsetX
            frame.offsetY = offsetY
            frame.velocityX = (offsetX - lastOffsetX) / elapsed
            frame.velocityY = (offsetY - lastOffsetY) / elapsed
            lastOffsetX = offsetX
            lastOffsetY = offsetY
            // 触点换算到当前系：glowX/Y 是按下时刻坐标系，bar 自身已平移 viewShift——
            // 与 TouchHighlight 同一修正，避免视图平移被重复计入越界量与 room。
            val touchX = centerX - viewShiftX
            val touchY = centerY - viewShiftY
            frame.centerX = touchX
            frame.centerY = touchY
            frame.boundsWidth = barWidth.toFloat()
            frame.boundsHeight = barHeight.toFloat()
            frame.cornerRadius = barHeight / 2f // 与 outline 的胶囊圆角一致，边缘距离因此精确
            // 可触达空间：底栏两侧/下缘贴近屏幕边缘时触点走不满 pileRefPx——
            // 剩余空间交给策略层压缩满额行程，贴屏边缘方向也能堆出完整"集中"。
            getLocationOnScreen(screenLoc)
            val metrics = resources.displayMetrics
            frame.pileRoomPx = reachablePileRoomPx(
                screenLoc[0], screenLoc[1],
                screenLoc[0] + width, screenLoc[1] + height,
                metrics.widthPixels, metrics.heightPixels,
                touchX, touchY, barWidth.toFloat(), barHeight.toFloat()
            )
            state.update(frame, dt, radius, NAVIGATION_GLOW_BASE_ALPHA, config)
            invalidate()
        }

        /** 只在不可见边界调用（resetInteraction / dispose）。 */
        fun resetGestureState() {
            lastUpdateNanos = 0L
            lastOffsetX = 0f
            lastOffsetY = 0f
            state.reset()
        }

        override fun onDraw(canvas: Canvas) {
            renderer.draw(canvas, state.shape)
            for (i in 0 until items.size) {
                val item = items[i]
                if (item.hasFocus()) canvas.drawRoundRect(item.left + dp(3f), item.top + dp(3f),
                    item.right - dp(3f), item.bottom - dp(3f), dp(18f), dp(18f), focusPaint)
            }
        }
    }
}
