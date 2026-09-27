package com.lumen.coacervation.engine.motion.reveal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import androidx.annotation.MainThread
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.lumen.coacervation.engine.motion.LumenEasing
import kotlin.math.abs

/**
 * 定位并高亮（适配标准 §13.8）：把滚动容器里的某个控件滚到视口上方的停靠点，到位后在控件上闪一次圆角高亮。
 * 来源工程里是设置搜索的跳转定位。
 *
 * 几条从真机来的规则都收在这里：
 * - **等几何稳定再算目标**：目标所在的分组可能正在展开（手风琴）、页面高度可能被异步内容改变——每个 pre-draw
 *   重新算一次停靠点，变了就重定向，而不是按一开始的位置滚过去；
 * - **只有一个请求拥有滚动**：新请求、生命周期取消都会让旧请求的滚动帧与高亮立即失效；
 * - **到位按实际位置判定**，不靠计时器（列表末尾的目标会被钳住，永远到不了算出来的位置）；
 * - 高亮画在目标的 overlay 上，不改它的背景与 alpha（带涟漪的控件改 alpha 会冻结涟漪，§12.2）；
 * - 竖向滚动容器可以是 `ScrollView` / `NestedScrollView`（按绝对位置），也可以是 `RecyclerView` 等任意能竖向
 *   滚动的容器（按剩余距离闭环推进，到底即停，§15.2）；
 * - 目标在**横向轮播**里时（§14.6），先把轮播横向滚到目标完整可见，竖向与横向都到位后才高亮；
 *   比视口还高的目标按顶部对齐，比轮播视口还宽的目标按起始边对齐。
 *
 * 宿主在 `onPause` 调 [cancel]。线程：主线程。
 */
@MainThread
public class LumenReveal @JvmOverloads constructor(
    /** 高亮色（通常是 `palette.primary`）。 */
    private val accentColor: Int,
    private val highlightDelayMs: Long = HIGHLIGHT_DELAY_MS,
    private val highlightDurationMs: Long = HIGHLIGHT_DURATION_MS
) {
    private val request = RevealRequest()
    private var highlightView: View? = null
    private var highlightDrawable: GradientDrawable? = null
    private var highlightAnimator: ValueAnimator? = null
    private var highlightRunnable: Runnable? = null

    /** 是否有进行中的定位。 */
    public val isActive: Boolean get() = request.isActive

    /**
     * @param scrollView 目标所在的竖向滚动容器（`NestedScrollView` / `ScrollView`），它的第一个子 View 是内容。
     * @param settling 目标所在、此刻可能还在动的分组（例如刚被要求展开的手风琴内容）；它们布局完成、
     *   alpha 回到 1、translationY 归零之前不计算停靠点。
     * @param topOffsetPx 停靠点距视口顶部的距离（有顶部悬浮栏时 = 栏高 + 留白，避免目标被栏盖住）。
     * @param afterReveal 到位（且开始高亮）后回调。
     */
    @JvmOverloads
    public fun reveal(
        scrollView: ViewGroup,
        target: View,
        settling: List<View> = emptyList(),
        topOffsetPx: Int = 0,
        highlight: Boolean = true,
        afterReveal: (() -> Unit)? = null
    ) {
        cancel()
        if (!target.isSameOrDescendantOf(scrollView)) return
        val horizontal = startHorizontalReveal(scrollView, target)
        // ScrollView / NestedScrollView：单个内容子 View、支持绝对位置，沿用来源工程的定位；其他容器走相对滚动。
        val absolute = scrollView is android.widget.ScrollView || scrollView is NestedScrollView
        var relative: RelativeScroll? = null
        val rect = Rect()
        var destinationY: Int? = null
        var scrollAnimator: ValueAnimator? = null
        val scrollMotion = RevealScrollMotion(
            currentPosition = { scrollView.scrollY },
            setPosition = { y -> scrollView.scrollTo(0, y) }
        )
        var token = 0L
        val observer = scrollView.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (!request.owns(token)) return@OnPreDrawListener true
            if (!target.isAttachedToWindow || !scrollView.isAttachedToWindow || !target.isSameOrDescendantOf(scrollView)) {
                cancel()
                return@OnPreDrawListener true
            }
            // 等真实的展开几何与最终变换，与动画时长缩放无关。
            if (!scrollView.isLaidOut || scrollView.isLayoutRequested || target.isLayoutRequested ||
                target.width <= 0 || target.height <= 0 ||
                settling.any { it.isLayoutRequested || it.alpha != 1f || it.translationY != 0f }
            ) {
                return@OnPreDrawListener true
            }
            if (!target.isShown) {
                cancel()
                return@OnPreDrawListener true
            }
            target.getDrawingRect(rect)
            scrollView.offsetDescendantRectToMyCoords(target, rect)
            if (!absolute) {
                // RecyclerView 一类：不支持滚到绝对位置、也没有"单个内容子 View"，按剩余距离闭环推进（§15.2）。
                val remaining = rect.top - scrollView.scrollY - topOffsetPx
                val blocked = (remaining > 0 && !scrollView.canScrollVertically(1)) ||
                    (remaining < 0 && !scrollView.canScrollVertically(-1))
                if (abs(remaining) <= 1 || blocked) {
                    relative?.cancel()
                    if (request.complete(token)) {
                        whenSettled(horizontal) {
                            if (highlight) scheduleHighlight(target)
                            afterReveal?.invoke()
                        }
                    }
                    return@OnPreDrawListener true
                }
                val plan = relative
                if (plan == null || !plan.running || abs(plan.expectedRemaining - remaining) > 2) {
                    plan?.cancel()
                    if (plan == null) stopFling(scrollView)
                    relative = RelativeScroll(scrollView, remaining).also { it.start() }
                }
                return@OnPreDrawListener true
            }
            val child = if (scrollView.childCount > 0) scrollView.getChildAt(0) else null
            if (child == null) {
                cancel()
                return@OnPreDrawListener true
            }
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            val range = (child.height + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0) -
                (scrollView.height - scrollView.paddingTop - scrollView.paddingBottom)).coerceAtLeast(0)
            val desiredY = RevealPolicy.verticalDestination(rect.top, topOffsetPx, range)
            if (destinationY != desiredY) {
                // 异步内容可能在定位途中改变页面高度：重定向。
                scrollAnimator?.cancel()
                if (destinationY == null) stopFling(scrollView)
                destinationY = desiredY
                val scrollToken = scrollMotion.retarget(desiredY)
                if (!ValueAnimator.areAnimatorsEnabled() || scrollView.scrollY == desiredY) {
                    scrollMotion.frame(scrollToken, 1f)
                } else {
                    scrollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = SCROLL_DURATION_MS
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            if (request.owns(token)) scrollMotion.frame(scrollToken, it.animatedFraction)
                        }
                        start()
                    }
                }
            }
            // 被钳在列表末尾的目标以实际滚动位置为准，不等计时器。
            if (scrollView.scrollY == destinationY && request.complete(token)) {
                // 横向那一步可能还在滑：两步都到位再高亮，否则高亮会跟着目标横着飞。
                whenSettled(horizontal) {
                    if (highlight) scheduleHighlight(target)
                    afterReveal?.invoke()
                }
            }
            true
        }
        token = request.begin {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            else scrollView.viewTreeObserver.removeOnPreDrawListener(listener)
            scrollMotion.cancel()
            scrollAnimator?.cancel()
            scrollAnimator = null
            relative?.cancel()
            relative = null
            horizontal?.cancel()
        }
        observer.addOnPreDrawListener(listener)
        scrollView.postInvalidateOnAnimation()
    }

    /** 取消进行中的定位与高亮。宿主在 `onPause` 与开始其他导航前调用。 */
    public fun cancel() {
        request.cancel()
        clearHighlight()
    }

    /** 只闪一次高亮（不滚动）。 */
    public fun highlight(target: View) {
        cancel()
        scheduleHighlight(target)
    }

    private fun scheduleHighlight(target: View) {
        clearHighlight()
        highlightView = target
        val runnable = object : Runnable {
            override fun run() {
                if (highlightRunnable !== this) return
                highlightRunnable = null
                if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) {
                    clearHighlight()
                    return
                }
                val density = target.resources.displayMetrics.density
                val drawable = GradientDrawable().apply {
                    cornerRadius = 12f * density
                    setColor(ColorUtils.setAlphaComponent(accentColor, 0x42))
                    setStroke((2f * density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(accentColor, 0xD0))
                    bounds = Rect(0, 0, target.width, target.height)
                    alpha = 0
                }
                target.overlay.add(drawable)
                highlightDrawable = drawable
                val animator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                    duration = highlightDurationMs
                    interpolator = LumenEasing.emphasizedDecelerate()
                    addUpdateListener { drawable.alpha = (255f * (it.animatedValue as Float)).toInt() }
                    addListener(object : AnimatorListenerAdapter() {
                        private fun finish(animation: Animator) {
                            target.overlay.remove(drawable)
                            if (highlightAnimator === animation) {
                                highlightAnimator = null
                                highlightDrawable = null
                                highlightView = null
                            }
                        }

                        override fun onAnimationEnd(animation: Animator) = finish(animation)
                        override fun onAnimationCancel(animation: Animator) = finish(animation)
                    })
                }
                highlightAnimator = animator
                animator.start()
            }
        }
        highlightRunnable = runnable
        target.postDelayed(runnable, highlightDelayMs)
    }

    private fun clearHighlight() {
        val view = highlightView
        val drawable = highlightDrawable
        val animator = highlightAnimator
        val runnable = highlightRunnable
        highlightView = null
        highlightDrawable = null
        highlightAnimator = null
        highlightRunnable = null
        if (runnable != null) view?.removeCallbacks(runnable)
        animator?.cancel()
        if (drawable != null) view?.overlay?.remove(drawable)
    }

    /**
     * 目标与竖向滚动容器之间最近的横向滚动容器（轮播）：把目标横向滚到完整可见。
     * 用逐帧 `scrollBy` 推进，`HorizontalScrollView` 与横向 `RecyclerView` 都适用（后者不在本模块依赖里）。
     * @return 正在进行的横向动画；不需要横向滚动时为 null。
     */
    private fun startHorizontalReveal(scrollView: ViewGroup, target: View): ValueAnimator? {
        var node = target.parent as? ViewGroup
        var carousel: ViewGroup? = null
        while (node != null && node !== scrollView) {
            if (node is HorizontalScrollView || node.canScrollHorizontally(1) || node.canScrollHorizontally(-1)) {
                carousel = node
                break
            }
            node = node.parent as? ViewGroup
        }
        val host = carousel ?: return null
        val rect = Rect(0, 0, target.width, target.height)
        runCatching { host.offsetDescendantRectToMyCoords(target, rect) }.getOrNull() ?: return null
        // offsetDescendantRectToMyCoords 给的是内容坐标（不扣容器自己的滚动量）：HorizontalScrollView 要换成可见坐标；
        // RecyclerView 的子 View 本来就摆在可见位置、scrollX 恒为 0，不受影响。
        rect.offset(-host.scrollX, 0)
        val margin = (12 * host.resources.displayMetrics.density).toInt()
        val dx = RevealPolicy.horizontalDelta(rect.left, rect.right,
            host.paddingLeft, host.width - host.paddingRight, margin)
        if (dx == 0) return null
        if (!ValueAnimator.areAnimatorsEnabled()) {
            host.scrollBy(dx, 0)
            return null
        }
        var applied = 0
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SCROLL_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val next = (dx * it.animatedFraction).toInt()
                host.scrollBy(next - applied, 0)
                applied = next
            }
            start()
        }
    }

    /** 按剩余距离推进的一段滚动：逐帧 scrollBy，容器自己决定能不能再滚（到底即停）。 */
    private class RelativeScroll(private val host: View, private val total: Int) {
        private var applied = 0
        private var animator: ValueAnimator? = null
        val running: Boolean get() = animator?.isRunning == true
        /** 按计划此刻应当还剩多少；与实测差太多（内容高度变了、用户动了）就重定向。 */
        val expectedRemaining: Int get() = total - applied

        fun start() {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                host.scrollBy(0, total)
                applied = total
                return
            }
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SCROLL_DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val next = (total * it.animatedFraction).toInt()
                    host.scrollBy(0, next - applied)
                    applied = next
                }
                start()
            }
        }

        fun cancel() {
            animator?.cancel()
            animator = null
        }
    }

    private fun whenSettled(animator: ValueAnimator?, action: () -> Unit) {
        if (animator == null || !animator.isRunning) {
            action()
            return
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) { if (!cancelled) action() }
        })
    }

    /** 这个请求接管滚动之前，先结束之前的原生惯性滚动。 */
    private fun stopFling(scrollView: ViewGroup) {
        when (scrollView) {
            is NestedScrollView -> scrollView.smoothScrollTo(scrollView.scrollX, scrollView.scrollY, 0)
            is android.widget.ScrollView -> scrollView.smoothScrollTo(scrollView.scrollX, scrollView.scrollY)
        }
    }

    private fun View.isSameOrDescendantOf(ancestor: View): Boolean {
        var node: View? = this
        while (node != null) {
            if (node === ancestor) return true
            node = node.parent as? View
        }
        return false
    }

    public companion object {
        public const val HIGHLIGHT_DELAY_MS: Long = 240L
        public const val HIGHLIGHT_DURATION_MS: Long = 560L
        public const val SCROLL_DURATION_MS: Long = 250L
    }
}

/** 定位的纯几何（适配标准 §14.6）。 */
internal object RevealPolicy {
    /**
     * 横向滚动量：目标完整可见时为 0；超出右缘时让右边露出（再留 [margin]）；超出左缘、或比视口还宽时让起始边对齐。
     * 坐标都在容器的可见坐标系里（已扣掉当前滚动量）。
     */
    fun horizontalDelta(targetLeft: Int, targetRight: Int, viewportLeft: Int, viewportRight: Int, margin: Int): Int {
        if (targetRight <= targetLeft || viewportRight <= viewportLeft) return 0
        val safeMargin = margin.coerceAtLeast(0)
        val width = viewportRight - viewportLeft
        return when {
            targetRight - targetLeft + 2 * safeMargin >= width -> targetLeft - viewportLeft - minOf(safeMargin, (width - (targetRight - targetLeft)).coerceAtLeast(0) / 2)
            targetLeft < viewportLeft + safeMargin -> targetLeft - viewportLeft - safeMargin
            targetRight > viewportRight - safeMargin -> targetRight - viewportRight + safeMargin
            else -> 0
        }
    }

    /** 竖向停靠点：目标顶端对齐到 [topOffset]，钳在可滚动范围内（比视口还高的目标同样按顶部对齐）。 */
    fun verticalDestination(targetTop: Int, topOffset: Int, range: Int): Int =
        (targetTop - topOffset).coerceIn(0, range.coerceAtLeast(0))
}
