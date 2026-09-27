package com.lumen.coacervation.engine.motion.pager

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Switch
import com.lumen.coacervation.engine.motion.InterruptibleMotionSession
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * 横向分页器：页数不限（来源工程固定 4 页，已去掉上限，适配标准 §15.1）；只有与视口相交的页参与渲染，
 * 其余页保持 INVISIBLE 但不销毁。每一页都常驻内存，页数很多、页面很重时宿主应当自己按需填充页内容。
 */
public class LumenPagePager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var selectedPage: Int = 0
        private set
    val pagePosition: Float get() = position
    var onPageSelected: (Int) -> Unit = {}
    /** Called before the first moving frame of a continuous motion, never on an undecided DOWN. */
    var onMotionStarted: () -> Unit = {}
    /** Effective horizontal takeover or successful keyboard/accessibility navigation, before selection changes. */
    var onUserInteraction: () -> Unit = {}
    /** After actual page translation/visibility changes, so skin sampling origins follow this frame. */
    var onPositionChanged: () -> Unit = {}
    /**
     * 当前这段运动的锚点（本 View 坐标系的 y）：横向拖动接手时取按下位置；底栏/键盘/搜索这类导航为 NaN，
     * 由调用方决定锚在哪里（底栏在底部）。只在运动开始时更新，供链式联动按"离手指远近"排先后。
     */
    var motionAnchorY: Float = Float.NaN
        private set

    /**
     * 开关类控件的（轨道, 滑块）Drawable，边界是该控件自己的坐标。按下落在开关**控件行**上、但不在轨道/滑块
     * 附近时，横向拖动仍然可以翻页；落在拨钮上则交给开关自己。返回 null 表示不是开关。
     *
     * 默认只识别框架 [Switch]。来源工程判的是 AppCompat 的 `SwitchCompat`；引擎不依赖 AppCompat，
     * 使用它的宿主传入 `LumenControls::switchParts`（lumen-controls）。
     */
    var switchParts: (View) -> Pair<Drawable?, Drawable?>? = { frameworkSwitchParts(it) }
    val isSettled: Boolean
        get() = motionLifecycle.isSettled && !gestureOwned && animator == null && abs(position - selectedPage) < .0001f

    private var position = 0f
    private var animator: ValueAnimator? = null
    private val motion = InterruptibleMotionSession()
    private val motionLifecycle = PageMotionLifecycle()
    private val originalAccessibility = IdentityHashMap<View, Int>()
    private val originalVisibility = IdentityHashMap<View, Int>()
    private val configuration = ViewConfiguration.get(context)
    private val touchSlop = configuration.scaledPagingTouchSlop.toFloat()
    private val edgeFallback = 24f * resources.displayMetrics.density
    private var leftGestureInset = 0
    private var rightGestureInset = 0
    private var tracker: VelocityTracker? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var dragStart = 0f
    private var gestureBlocked = true
    private var gestureOwned = false
    private var blockChildStream = false
    private val pageWidth: Int get() = (width - paddingLeft - paddingRight).coerceAtLeast(0)
    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL
    private val isAtRest: Boolean get() = isSettled
    private val activePage: View? get() = if (selectedPage in 0 until childCount) getChildAt(selectedPage) else null

    init {
        clipChildren = true
        clipToPadding = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        originalAccessibility[child] = child.importantForAccessibility
        originalVisibility[child] = child.visibility
        updatePageAccessibility()
        applyPosition()
    }

    override fun onViewRemoved(child: View) {
        originalAccessibility.remove(child)?.let { child.importantForAccessibility = it }
        originalVisibility.remove(child)?.let { child.visibility = it }
        child.translationX = 0f
        super.onViewRemoved(child)
        stopMotion()
        clearGesture()
        val previous = selectedPage
        selectedPage = PageMotionPolicy.selected(selectedPage, childCount)
        position = selectedPage.toFloat()
        motionLifecycle.finishMotion()
        updatePageAccessibility()
        applyPosition()
        if (previous != selectedPage) onPageSelected(selectedPage)
    }

    fun selectPage(index: Int, animate: Boolean = true) {
        gestureBlocked = true
        motionAnchorY = Float.NaN
        settle(
            PageMotionPolicy.selected(index, childCount), motion.velocity(SystemClock.uptimeMillis()), animate,
            navigation = true
        )
    }

    private fun settle(index: Int, velocity: Float = 0f, animate: Boolean = true, navigation: Boolean = false) {
        stopMotion()
        val target = PageMotionPolicy.selected(index, childCount)
        val changed = selectedPage != target
        if (changed) activePage?.clearFocus()
        selectedPage = target
        updatePageAccessibility()
        val token = motion.generation
        if (!animate || !isAttachedToWindow || pageWidth <= 0 || !ValueAnimator.areAnimatorsEnabled() || abs(position - target) < .0001f) {
            position = target.toFloat()
            motionLifecycle.finishMotion()
            applyPosition()
            motion.reset(position, SystemClock.uptimeMillis())
            updatePageAccessibility()
        } else {
            if (motionLifecycle.beginMotion()) onMotionStarted()
            if (!motion.owns(token)) return
            val base = if (navigation) PageMotionPolicy.navigationDuration(position, target.toFloat())
            else PageMotionPolicy.duration(position, target.toFloat())
            val duration = PageMotionPolicy.handoffDuration(base, position, target.toFloat(), velocity)
            val continuation = PageMotionContinuation(position, target, velocity, duration, childCount, navigation)
            motion.reset(position, SystemClock.uptimeMillis())
            val nextAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = LinearInterpolator()
                addUpdateListener {
                    if (motion.owns(token)) {
                        position = continuation.value(it.animatedFraction)
                        motion.sample(position, SystemClock.uptimeMillis())
                        applyPosition()
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!motion.owns(token)) return
                        animator = null
                        position = target.toFloat()
                        motionLifecycle.finishMotion()
                        applyPosition()
                        motion.reset(position, SystemClock.uptimeMillis())
                        updatePageAccessibility()
                    }
                })
            }
            animator = nextAnimator
            nextAnimator.start()
        }
        if (changed) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED)
            onPageSelected(target)
        }
    }

    private fun stopMotion() {
        motion.invalidate()
        animator?.cancel()
        animator = null
    }

    private fun applyPosition() {
        val step = pageWidth * PageMotionPolicy.direction(rtl)
        var positionChanged = false
        for (index in 0 until childCount) {
            val child: View = getChildAt(index)
            val translation = (index - position) * step
            if (child.translationX != translation) {
                child.translationX = translation
                positionChanged = true
            }
            // INVISIBLE retains measurement and the searchable tree. It also makes isShown false,
            // before Liquid's conservative transform check can invalidate an entire offscreen page.
            val visibility = if (PageMotionPolicy.isPageVisible(index, position, childCount)) VISIBLE else INVISIBLE
            if (child.visibility != visibility) {
                child.visibility = visibility
                positionChanged = true
            }
        }
        if (positionChanged) onPositionChanged()
    }

    private fun updatePageAccessibility() {
        for (index in 0 until childCount) {
            val child: View = getChildAt(index)
            child.importantForAccessibility = if (index == selectedPage && isAtRest)
                originalAccessibility[child] ?: IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyPosition()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) resetToSelection()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (isLaidOut) resetToSelection()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            leftGestureInset = insets.systemGestureInsets.left
            rightGestureInset = insets.systemGestureInsets.right
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) beginGesture(event)
        tracker?.addMovement(event)
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (!gestureBlocked) {
                gestureBlocked = true
                if (gestureOwned) settle(selectedPage)
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            clearGesture()
        }
        return handled
    }

    private fun beginGesture(event: MotionEvent) {
        clearGesture()
        blockChildStream = !isAtRest
        downX = event.x
        downY = event.y
        pointerId = event.getPointerId(0)
        gestureBlocked = childCount < 2 || pageWidth <= 0 ||
            downX <= maxOf(edgeFallback, leftGestureInset.toFloat()) ||
            downX >= width - maxOf(edgeFallback, rightGestureInset.toFloat()) ||
            protectedChildAt(this, downX, downY)
        if (gestureBlocked) return
        // 按下不冻结进行中的动画：竖滑或轻点时动画照常走完，不再"停一下再按零速度重起"。
        // 只有判定为横向拖动（tryOwnGesture 接手）那一刻，才从动画当前位置把页面交给手指。
        tracker = VelocityTracker.obtain()
    }

    /** Only inspected on DOWN: no tree walk or coordinate allocations in animation/drag frames. */
    private fun protectedChildAt(view: View, x: Float, y: Float): Boolean {
        val parts = switchParts(view)
        if (parts != null) {
            // Switch.draw() sets track/thumb bounds in its own View coordinates. Read a
            // value snapshot: Drawable.getBounds() may return its mutable internal Rect.
            fun snapshot(drawable: Drawable?): SwitchTouchBounds? =
                drawable?.bounds?.let { SwitchTouchBounds(it.left, it.top, it.right, it.bottom) }
            return PageMotionPolicy.protectsSwitchTouch(x, y, view.width, view.height,
                view.isLaidOut && !view.isLayoutRequested && !view.isDirty,
                snapshot(parts.first), snapshot(parts.second), configuration.scaledTouchSlop)
        }
        if (view !== this && (view is CompoundButton || view is SeekBar ||
                view.canScrollHorizontally(-1) || view.canScrollHorizontally(1))) return true
        if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) {
            val child: View = view.getChildAt(index)
            if (child.visibility != VISIBLE) continue
            val localX = x + view.scrollX - child.left - child.translationX
            val localY = y + view.scrollY - child.top - child.translationY
            if (localX >= 0 && localX < child.width && localY >= 0 && localY < child.height &&
                protectedChildAt(child, localX, localY)) return true
        }
        return false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (gestureOwned) return true
        if (event.actionMasked == MotionEvent.ACTION_MOVE) tryOwnGesture(event)
        if (animator != null) blockChildStream = true
        return gestureOwned || blockChildStream
    }

    private fun tryOwnGesture(event: MotionEvent) {
        if (gestureBlocked || event.pointerCount != 1) return
        val pointer = event.findPointerIndex(pointerId)
        if (pointer < 0) {
            gestureBlocked = true
            return
        }
        val dx = event.getX(pointer) - downX
        val dy = event.getY(pointer) - downY
        if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
            gestureBlocked = true
        } else if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
            gestureOwned = true
            // 接手：停在动画此刻的位置，以当前手指 x 为零点，接手帧位置连续、不跳。
            stopMotion()
            dragStart = position
            downX = event.getX(pointer)
            motionAnchorY = downY
            val notifyUser = motionLifecycle.beginUserGesture()
            val notifyMotion = motionLifecycle.beginMotion()
            activePage?.clearFocus()
            updatePageAccessibility()
            if (notifyMotion) onMotionStarted()
            if (notifyUser) onUserInteraction()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!gestureOwned) tryOwnGesture(event)
                if (gestureOwned && !gestureBlocked) {
                    val pointer = event.findPointerIndex(pointerId)
                    if (pointer < 0) {
                        gestureBlocked = true
                        settle(selectedPage)
                    } else {
                        val delta = PageMotionPolicy.logicalDelta(event.getX(pointer) - downX, pageWidth, rtl)
                        position = PageMotionPolicy.dragPosition(dragStart, delta, childCount)
                        applyPosition()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gestureOwned && !gestureBlocked) {
                    tracker?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                    val pixels = tracker?.getXVelocity(pointerId) ?: 0f
                    val velocity = if (abs(pixels) >= configuration.scaledMinimumFlingVelocity)
                        PageMotionPolicy.logicalDelta(pixels, pageWidth, rtl) else 0f
                    settle(PageMotionPolicy.releasePage(selectedPage, position, velocity, childCount), velocity)
                } else if (!gestureBlocked) performClick()
            }
            MotionEvent.ACTION_CANCEL -> if (gestureOwned) settle(selectedPage)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && !gestureOwned) gestureBlocked = true
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun clearGesture() {
        tracker?.recycle()
        tracker = null
        pointerId = MotionEvent.INVALID_POINTER_ID
        gestureBlocked = true
        gestureOwned = false
        blockChildStream = false
        motionLifecycle.finishUserGesture()
        if (animator == null && abs(position - selectedPage) < .0001f) motionLifecycle.finishMotion()
        parent?.requestDisallowInterceptTouchEvent(false)
        updatePageAccessibility()
    }

    private fun resetToSelection() {
        stopMotion()
        clearGesture()
        position = selectedPage.toFloat()
        motionLifecycle.finishMotion()
        applyPosition()
        motion.reset(position, SystemClock.uptimeMillis())
        updatePageAccessibility()
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        val next = PageMotionPolicy.physicalPageTarget(selectedPage, direction, rtl)
        return next != selectedPage && next in 0 until childCount
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (isAtRest) activePage?.addFocusables(views, direction, focusableMode)
        if (isFocusable) views.add(this)
    }

    override fun addTouchables(views: ArrayList<View>) {
        if (isAtRest) activePage?.addTouchables(views)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BACK must reach Activity dispatch on three-button navigation too (API 27-32).
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.isSystem) return super.dispatchKeyEvent(event)
        if (isAtRest && super.dispatchKeyEvent(event)) return true
        val contentKey = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
            else -> false
        }
        if (!isAtRest && contentKey) return true
        if (event.action != KeyEvent.ACTION_DOWN || event.hasModifiers(KeyEvent.META_ALT_ON))
            return if (isAtRest) false else super.dispatchKeyEvent(event)
        val physical = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return if (isAtRest) false else super.dispatchKeyEvent(event)
        }
        if (!canScrollHorizontally(physical)) return false
        return navigateFromUser(PageMotionPolicy.physicalPageTarget(selectedPage, physical, rtl))
    }

    private fun navigateFromUser(target: Int): Boolean = PageUserNavigation.request(
        selectedPage, target, childCount, onUserInteraction
    ) { selectPage(it) }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = childCount > 1
        info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(1, childCount, false,
            AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE)
        if (selectedPage > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (selectedPage < childCount - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        if (canScrollHorizontally(-1)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
        if (canScrollHorizontally(1)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.isScrollable = childCount > 1
        event.itemCount = childCount
        event.fromIndex = selectedPage
        event.toIndex = selectedPage
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val next = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> selectedPage + 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> selectedPage - 1
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id ->
                PageMotionPolicy.physicalPageTarget(selectedPage, -1, rtl)
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id ->
                PageMotionPolicy.physicalPageTarget(selectedPage, 1, rtl)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return navigateFromUser(next)
    }

    override fun onDetachedFromWindow() {
        resetToSelection()
        super.onDetachedFromWindow()
    }

    public companion object {
        /** 框架 [Switch] 的（轨道, 滑块）；其他控件返回 null。[switchParts] 的默认值。 */
        @JvmStatic
        public fun frameworkSwitchParts(view: View): Pair<Drawable?, Drawable?>? =
            (view as? Switch)?.let { it.trackDrawable to it.thumbDrawable }
    }
}
