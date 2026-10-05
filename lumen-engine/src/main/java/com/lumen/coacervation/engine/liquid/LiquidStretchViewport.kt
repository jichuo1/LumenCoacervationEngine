package com.lumen.coacervation.engine.liquid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EdgeEffect
import android.widget.FrameLayout
import androidx.core.graphics.withRotation
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.core.widget.EdgeEffectCompat
import com.lumen.coacervation.engine.interaction.ElasticGestureClaim
import kotlin.math.abs
import kotlin.math.roundToInt

public enum class LiquidStretchEdge {
    NONE,
    TOP,
    BOTTOM
}

internal enum class LiquidStretchUnconsumedAction {
    PULL_AND_CONSUME,
    ABSORB_AND_PROPAGATE,
    PROPAGATE
}

/**
 * 滚动容器若在 `dispatchTouchEvent` 里观察手势，实现本接口：视口接住回弹后会把整段手势
 * 直接交给容器的 `onTouchEvent`（绕过 `dispatchTouchEvent`），观察必须经这里补上。
 */
public interface LiquidStretchGestureObserver {
    fun observeTouch(event: MotionEvent)
}

/** 与 Android View 无关的方向、距离和速度收敛规则。 */
internal object LiquidStretchOverscrollPolicy {
    private const val MAX_ABSORB_VELOCITY = 100_000

    fun pullEdge(dyUnconsumed: Int): LiquidStretchEdge = when {
        dyUnconsumed < 0 -> LiquidStretchEdge.TOP
        dyUnconsumed > 0 -> LiquidStretchEdge.BOTTOM
        else -> LiquidStretchEdge.NONE
    }

    fun releaseEdge(
        dy: Int,
        topDistance: Float,
        bottomDistance: Float
    ): LiquidStretchEdge = when {
        dy > 0 && topDistance > 0f -> LiquidStretchEdge.TOP
        dy < 0 && bottomDistance > 0f -> LiquidStretchEdge.BOTTOM
        else -> LiquidStretchEdge.NONE
    }

    fun normalizedDistance(deltaPixels: Int, viewportHeight: Int): Float {
        if (viewportHeight <= 0) return 0f
        return (abs(deltaPixels).toFloat() / viewportHeight.toFloat()).coerceIn(0f, 1f)
    }

    fun displacement(pointerX: Float, viewportWidth: Int, edge: LiquidStretchEdge): Float {
        if (viewportWidth <= 0) return 0.5f
        val fromLeft = (pointerX / viewportWidth.toFloat()).coerceIn(0f, 1f)
        return if (edge == LiquidStretchEdge.BOTTOM) 1f - fromLeft else fromLeft
    }

    fun consumedPixels(
        edge: LiquidStretchEdge,
        consumedDistance: Float,
        viewportHeight: Int
    ): Int {
        val magnitude = (abs(consumedDistance) * viewportHeight.coerceAtLeast(0)).roundToInt()
        return when (edge) {
            LiquidStretchEdge.TOP -> -magnitude
            LiquidStretchEdge.BOTTOM -> magnitude
            LiquidStretchEdge.NONE -> 0
        }
    }

    fun releaseConsumedPixels(
        edge: LiquidStretchEdge,
        consumedDistance: Float,
        viewportHeight: Int
    ): Int {
        val magnitude = (abs(consumedDistance) * viewportHeight.coerceAtLeast(0)).roundToInt()
        return when (edge) {
            LiquidStretchEdge.TOP -> magnitude
            LiquidStretchEdge.BOTTOM -> -magnitude
            LiquidStretchEdge.NONE -> 0
        }
    }

    fun absorbVelocity(velocityY: Float): Int =
        abs(velocityY).roundToInt().coerceIn(1, MAX_ABSORB_VELOCITY)

    fun unconsumedAction(
        isTouch: Boolean,
        hasFlingVelocity: Boolean
    ): LiquidStretchUnconsumedAction = when {
        isTouch -> LiquidStretchUnconsumedAction.PULL_AND_CONSUME
        hasFlingVelocity -> LiquidStretchUnconsumedAction.ABSORB_AND_PROPAGATE
        else -> LiquidStretchUnconsumedAction.PROPAGATE
    }

    fun shouldReleaseOnStop(
        isTouch: Boolean,
        nonTouchAbsorbed: Boolean,
        nonTouchAdjusted: Boolean
    ): Boolean = isTouch || (!nonTouchAbsorbed && nonTouchAdjusted)

    /**
     * 翻转阈值：另一边必须比当前边大出这个倍数才夺走主导权。
     *
     * 没有迟滞时两条同时衰减的回弹会**反复穿越**——而渲染层对"方向变了"是无条件发布的
     * （强度量化拦不住它），每穿越一次就把整组可见玻璃表面重录一遍。短页面最容易撞上：
     * 内容几乎不滚，一甩就到边，反向再甩时上一条回弹还在衰减，两边同时非零
     * （2026-09-22 用户报告"短页面内短时间触发两个相反方向回弹会有迟滞感"）。
     *
     * 1.25 只影响"两边都非零且量级相近"的那一小段；任一边归零时仍然立刻交接，
     * 不会把方向卡在已经消失的那条边上。
     */
    const val EDGE_FLIP_RATIO = 1.25f

    /**
     * 当前占主导的回弹边：两侧可能瞬时都非零（一侧衰减中、另一侧刚拉起）。方向必须随距离
     * 一起上报——回弹光学高光要按"哪条边在拉伸"投射到对应边缘，只报标量距离会让四个方向
     * 的高光完全一致（2026-09-21 用户实证：四边等亮描边违反方向直觉）。
     *
     * [current] 是上一次发布的边，用于迟滞（见 [EDGE_FLIP_RATIO]）；传 NONE 即无状态判定。
     */
    fun dominantEdge(
        topDistance: Float,
        bottomDistance: Float,
        current: LiquidStretchEdge = LiquidStretchEdge.NONE
    ): LiquidStretchEdge {
        val top = topDistance.takeIf { it > 0f } ?: 0f
        val bottom = bottomDistance.takeIf { it > 0f } ?: 0f
        if (top <= 0f && bottom <= 0f) return LiquidStretchEdge.NONE
        if (top <= 0f) return LiquidStretchEdge.BOTTOM
        if (bottom <= 0f) return LiquidStretchEdge.TOP
        return when (current) {
            LiquidStretchEdge.TOP ->
                if (bottom > top * EDGE_FLIP_RATIO) LiquidStretchEdge.BOTTOM else LiquidStretchEdge.TOP
            LiquidStretchEdge.BOTTOM ->
                if (top > bottom * EDGE_FLIP_RATIO) LiquidStretchEdge.TOP else LiquidStretchEdge.BOTTOM
            LiquidStretchEdge.NONE ->
                if (top >= bottom) LiquidStretchEdge.TOP else LiquidStretchEdge.BOTTOM
        }
    }
}

/**
 * 让滚动前景共享同一个 Android 12+ stretch RenderNode，底层 Activity 背景保持静止。
 *
 * 内部滚动容器不再自己绘制 EdgeEffect；它先把未消费距离交给本父层，本父层在完成 child 绘制后
 * 调用 EdgeEffect.draw。viewport 不复制根底图，也不绘制任何边界光学环，因此系统 stretch 只
 * 作用于控件组成的前景 RenderNode。
 */
@SuppressLint("ViewConstructor")
internal class LiquidStretchViewport private constructor(
    context: Context,
    private val scrollTarget: View,
    private val isStretchAllowed: () -> Boolean,
    private val onStretchDistance: (Float, LiquidStretchEdge) -> Unit
) : FrameLayout(context), NestedScrollingParent3, ElasticGestureClaim {

    private val nestedParentHelper = NestedScrollingParentHelper(this)
    private val topEffect = EdgeEffect(context)
    private val bottomEffect = EdgeEffect(context)
    private val legacyConsumed = IntArray(2)
    private var pointerX = 0f
    private var lastFlingVelocityY = 0f
    private var nonTouchAbsorbed = false
    private var nonTouchAdjusted = false

    init {
        // 无背景的 ViewGroup 默认会绕过 draw() 直接 dispatchDraw()；必须关闭该快路径，
        // 才能在 child 绘制完成后把 EdgeEffect stretch 应用到这个前景 RenderNode。
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scrollTarget.overScrollMode = View.OVER_SCROLL_NEVER
        addView(
            scrollTarget,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0) {
            topEffect.setSize(width, height)
            bottomEffect.setSize(width, height)
        }
    }

    /**
     * 本次手势是否由"接住回弹"开始（2026-09-23 用户要求回弹可打断）。
     *
     * 只冻结形变不够：按下事件照常下发时，手指下的卡片会进入按压态，原地松手就被当成点击
     * （真机实证：按住正在回弹的页面，松手打开了"设置备份与恢复"）。平台 `NestedScrollView`
     * 接住自己的 stretch 时会立刻把整段手势判给自己；这里的 stretch 由本视口代画，滚动容器
     * 看不到它，所以由本视口拦下整段手势、原样转给滚动容器自己的 [View.onTouchEvent]——
     * 拖动、甩动、松手回弹都走滚动容器原来的逻辑，内容收不到按下/点击/长按。
     */
    private var catchingStretch = false

    /** 接住回弹的手势不属于内容：全局长按弹性不得给手指下的控件点亮按压高光。 */
    override val claimsCurrentGesture: Boolean get() = catchingStretch

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        pointerX = event.x
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastFlingVelocityY = 0f
            catchingStretch = if (isAllowedNow()) {
                stopEffectsForTouch()
            } else {
                finishStretch()
                false
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            catchingStretch = false
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        catchingStretch || super.onInterceptTouchEvent(event)

    /** 只在接住回弹的手势里生效：事件换算到滚动容器坐标后直接交给它，不经过它的子 View。 */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!catchingStretch) return super.onTouchEvent(event)
        val forwarded = MotionEvent.obtain(event)
        try {
            forwarded.offsetLocation(
                scrollX - scrollTarget.left - scrollTarget.translationX,
                scrollY - scrollTarget.top - scrollTarget.translationY
            )
            (scrollTarget as? LiquidStretchGestureObserver)?.observeTouch(forwarded)
            scrollTarget.onTouchEvent(forwarded)
        } finally {
            forwarded.recycle()
        }
        return true
    }

    /** 上一次发布的主导边；迟滞判定要拿它当基准，否则两条回弹会反复夺权。 */
    private var publishedEdge = LiquidStretchEdge.NONE
    var samplingOverscroll = 0f
        private set
    private var samplingBeforeContent = false

    /**
     * 每帧的强度/方向发布点。`draw()` 前后各调一次：前者反映本帧输入累积的形变，
     * 后者反映 EdgeEffect 自己推进后的值，两次都要让渲染层看到，否则回弹尾段会漏。
     */
    private fun publishStretch(topDistance: Float, bottomDistance: Float) {
        val edge = LiquidStretchOverscrollPolicy.dominantEdge(topDistance, bottomDistance, publishedEdge)
        publishedEdge = edge
        if (samplingBeforeContent) {
            samplingOverscroll = when (edge) {
                LiquidStretchEdge.TOP -> LiquidStretchSamplingPolicy.intensity(topDistance)
                LiquidStretchEdge.BOTTOM -> -LiquidStretchSamplingPolicy.intensity(bottomDistance)
                LiquidStretchEdge.NONE -> 0f
            }
        }
        onStretchDistance(maxOf(topDistance, bottomDistance), edge)
    }

    override fun draw(canvas: Canvas) {
        if (!isAllowedNow()) {
            super.draw(canvas)
            finishStretch()
            return
        }
        var topDistance = EdgeEffectCompat.getDistance(topEffect)
        var bottomDistance = EdgeEffectCompat.getDistance(bottomEffect)
        publishStretch(topDistance, bottomDistance)
        // Hardware stretch updates the whole RecordingCanvas node, rather than painting on top.
        // Advance it before recording children so their inverse sampling uses this exact frame.
        val nativeStretch = Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated
        samplingBeforeContent = nativeStretch
        if (!nativeStretch) super.draw(canvas)
        var continueDrawing = false
        // 实时取样（LiveBackdropSampler）会把内容根重绘进软件 Canvas；Android 12+ 的
        // stretch EdgeEffect 在非 RecordingCanvas 上 draw() 会直接清零并放弃效果，
        // 取样路径必须跳过效果绘制，否则每一帧都会把正在累积的形变量抹掉。
        val effectsDrawable = Build.VERSION.SDK_INT < 31 || canvas.isHardwareAccelerated
        if (!topEffect.isFinished && effectsDrawable) {
            continueDrawing = topEffect.draw(canvas) || continueDrawing
        }
        if (!bottomEffect.isFinished && effectsDrawable) {
            canvas.withRotation(
                degrees = 180f,
                pivotX = width * 0.5f,
                pivotY = height * 0.5f
            ) {
                continueDrawing = bottomEffect.draw(this) || continueDrawing
            }
        }
        topDistance = EdgeEffectCompat.getDistance(topEffect)
        bottomDistance = EdgeEffectCompat.getDistance(bottomEffect)
        publishStretch(topDistance, bottomDistance)
        if (nativeStretch) super.draw(canvas)
        samplingBeforeContent = false
        if (continueDrawing) postInvalidateOnAnimation()
    }

    override fun onStartNestedScroll(
        child: View,
        target: View,
        axes: Int,
        type: Int
    ): Boolean {
        val allowed = isAllowedNow() && axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0
        if (!allowed) finishStretch()
        return allowed
    }

    override fun onNestedScrollAccepted(
        child: View,
        target: View,
        axes: Int,
        type: Int
    ) {
        nestedParentHelper.onNestedScrollAccepted(child, target, axes, type)
        if (type == ViewCompat.TYPE_NON_TOUCH) {
            nonTouchAbsorbed = false
            nonTouchAdjusted = false
        }
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        nestedParentHelper.onStopNestedScroll(target, type)
        val isTouch = type == ViewCompat.TYPE_TOUCH
        if (LiquidStretchOverscrollPolicy.shouldReleaseOnStop(
                isTouch = isTouch,
                nonTouchAbsorbed = nonTouchAbsorbed,
                nonTouchAdjusted = nonTouchAdjusted
            )
        ) {
            releaseEffects()
        }
        if (!isTouch) {
            lastFlingVelocityY = 0f
            nonTouchAbsorbed = false
            nonTouchAdjusted = false
        }
    }

    override fun onNestedPreScroll(
        target: View,
        dx: Int,
        dy: Int,
        consumed: IntArray,
        type: Int
    ) {
        if (!isAllowedNow() || height <= 0) return
        val edge = LiquidStretchOverscrollPolicy.releaseEdge(
            dy = dy,
            topDistance = EdgeEffectCompat.getDistance(topEffect),
            bottomDistance = EdgeEffectCompat.getDistance(bottomEffect)
        )
        val effect = when (edge) {
            LiquidStretchEdge.TOP -> topEffect
            LiquidStretchEdge.BOTTOM -> bottomEffect
            LiquidStretchEdge.NONE -> return
        }
        val deltaDistance = -LiquidStretchOverscrollPolicy.normalizedDistance(dy, height)
        val released = EdgeEffectCompat.onPullDistance(
            effect,
            deltaDistance,
            LiquidStretchOverscrollPolicy.displacement(pointerX, width, edge)
        )
        consumed[1] += LiquidStretchOverscrollPolicy.releaseConsumedPixels(
            edge,
            released,
            height
        )
        if (type == ViewCompat.TYPE_NON_TOUCH && released != 0f) {
            nonTouchAdjusted = true
        }
        if (EdgeEffectCompat.getDistance(effect) == 0f) effect.onRelease()
        postInvalidateOnAnimation()
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        if (!isAllowedNow() || height <= 0 || dyUnconsumed == 0) return
        val edge = LiquidStretchOverscrollPolicy.pullEdge(dyUnconsumed)
        val effect = when (edge) {
            LiquidStretchEdge.TOP -> {
                if (!bottomEffect.isFinished) bottomEffect.onRelease()
                topEffect
            }
            LiquidStretchEdge.BOTTOM -> {
                if (!topEffect.isFinished) topEffect.onRelease()
                bottomEffect
            }
            LiquidStretchEdge.NONE -> return
        }

        val effectChanged = when (LiquidStretchOverscrollPolicy.unconsumedAction(
            isTouch = type == ViewCompat.TYPE_TOUCH,
            hasFlingVelocity = lastFlingVelocityY != 0f
        )) {
            LiquidStretchUnconsumedAction.PULL_AND_CONSUME -> {
                val pulled = EdgeEffectCompat.onPullDistance(
                    effect,
                    LiquidStretchOverscrollPolicy.normalizedDistance(dyUnconsumed, height),
                    LiquidStretchOverscrollPolicy.displacement(pointerX, width, edge)
                )
                consumed[1] += LiquidStretchOverscrollPolicy.consumedPixels(
                    edge,
                    pulled,
                    height
                )
                true
            }

            LiquidStretchUnconsumedAction.ABSORB_AND_PROPAGATE -> {
                effect.onAbsorb(
                    LiquidStretchOverscrollPolicy.absorbVelocity(lastFlingVelocityY)
                )
                lastFlingVelocityY = 0f
                nonTouchAbsorbed = true
                // 必须保持 consumed 不变，让 NestedScrollView 终止已经撞边的 OverScroller。
                true
            }

            LiquidStretchUnconsumedAction.PROPAGATE -> false
        }
        if (effectChanged) postInvalidateOnAnimation()
    }

    override fun onNestedFling(
        target: View,
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean
    ): Boolean {
        if (isAllowedNow() && velocityY != 0f) lastFlingVelocityY = velocityY
        return false
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean =
        false

    override fun getNestedScrollAxes(): Int = nestedParentHelper.nestedScrollAxes

    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)
    }

    override fun onStopNestedScroll(target: View) {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)
    }

    override fun onNestedPreScroll(
        target: View,
        dx: Int,
        dy: Int,
        consumed: IntArray
    ) {
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int
    ) {
        legacyConsumed.fill(0)
        onNestedScroll(
            target,
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            type,
            legacyConsumed
        )
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int
    ) {
        onNestedScroll(
            target,
            dxConsumed,
            dyConsumed,
            dxUnconsumed,
            dyUnconsumed,
            ViewCompat.TYPE_TOUCH
        )
    }

    fun finishStretch() {
        val hadEffect = !topEffect.isFinished || !bottomEffect.isFinished
        topEffect.finish()
        bottomEffect.finish()
        lastFlingVelocityY = 0f
        nonTouchAbsorbed = false
        nonTouchAdjusted = false
        // 迟滞基准一并复位：下一轮回弹要按"无状态"重新选边，不能沿用上一轮的主导边。
        publishedEdge = LiquidStretchEdge.NONE
        samplingOverscroll = 0f
        onStretchDistance(0f, LiquidStretchEdge.NONE)
        if (hadEffect) invalidate()
    }

    override fun onDetachedFromWindow() {
        finishStretch()
        super.onDetachedFromWindow()
    }

    private fun releaseEffects() {
        var released = false
        if (!topEffect.isFinished) {
            topEffect.onRelease()
            released = true
        }
        if (!bottomEffect.isFinished) {
            bottomEffect.onRelease()
            released = true
        }
        if (released) postInvalidateOnAnimation()
    }

    /**
     * 与 NestedScrollView.stopGlowAnimations 对齐：新手势接管回弹，但不突兀清零形变量。
     *
     * @return 确实接住了正在显示的回弹；调用方据此把整段手势交给滚动容器。
     */
    private fun stopEffectsForTouch(): Boolean {
        var stopped = false
        if (EdgeEffectCompat.getDistance(topEffect) > 0f) {
            EdgeEffectCompat.onPullDistance(
                topEffect,
                0f,
                LiquidStretchOverscrollPolicy.displacement(
                    pointerX,
                    width,
                    LiquidStretchEdge.TOP
                )
            )
            stopped = true
        }
        if (EdgeEffectCompat.getDistance(bottomEffect) > 0f) {
            EdgeEffectCompat.onPullDistance(
                bottomEffect,
                0f,
                LiquidStretchOverscrollPolicy.displacement(
                    pointerX,
                    width,
                    LiquidStretchEdge.BOTTOM
                )
            )
            stopped = true
        }
        if (stopped) postInvalidateOnAnimation()
        return stopped
    }

    private fun isAllowedNow(): Boolean =
        runCatching(isStretchAllowed).getOrDefault(false)

    companion object {
        fun installAround(
            scrollTarget: View,
            isStretchAllowed: () -> Boolean,
            onStretchDistance: (Float, LiquidStretchEdge) -> Unit
        ): LiquidStretchViewport? {
            val parent = (scrollTarget.parent as? ViewGroup) ?: return null
            val index = parent.indexOfChild(scrollTarget).takeIf { it >= 0 } ?: return null
            val originalLayoutParams = scrollTarget.layoutParams
            parent.removeViewAt(index)
            return try {
                val viewport = LiquidStretchViewport(
                    context = scrollTarget.context,
                    scrollTarget = scrollTarget,
                    isStretchAllowed = isStretchAllowed,
                    onStretchDistance = onStretchDistance
                )
                parent.addView(viewport, index, originalLayoutParams)
                viewport
            } catch (throwable: Throwable) {
                val temporaryParent = (scrollTarget.parent as? ViewGroup)
                temporaryParent?.removeView(scrollTarget)
                if (scrollTarget.parent == null) {
                    parent.addView(scrollTarget, index.coerceAtMost(parent.childCount), originalLayoutParams)
                }
                throw throwable
            }
        }
    }
}
