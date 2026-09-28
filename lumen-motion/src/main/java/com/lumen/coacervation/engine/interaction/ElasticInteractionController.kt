package com.lumen.coacervation.engine.interaction

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.AbsSeekBar
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.touch.GlowConfig
import com.lumen.coacervation.engine.touch.GlowFrame
import com.lumen.coacervation.engine.touch.GlowState
import com.lumen.coacervation.engine.touch.TouchGlowRenderer
import com.lumen.coacervation.engine.touch.reachablePileRoomPx
import java.lang.ref.WeakReference

/**
 * One controller per module Activity/Dialog window, wrapping the original dispatch entry point.
 *
 * [dispatch] accepts window MotionEvents (raw coordinates are used for hit testing). Pass the real
 * original dispatcher exactly once through its lambda; do not also install this as an OnTouchListener.
 * Ordinary DOWN/MOVE/UP retain their original stream. A held drag sends one CANCEL through that same
 * dispatcher, then consumes the remainder. Plain UP restores geometry BEFORE dispatch so a click can
 * capture its anchor for ModalTitleMotion. No click, checked state, alpha or ViewPropertyAnimator is
 * owned here. [clear] restores immediately; [dispose] also removes root listeners.
 *
 * The tree is inspected only on DOWN. Later frames validate that cached path and update four View
 * properties plus one overlay; they never capture a bitmap or run an idle polling clock.
 */
@MainThread
public class ElasticInteractionController(
    private val root: View,
    private val notifyPositionChanged: (View) -> Unit = {},
    private val isExcluded: (View) -> Boolean = { it.tag == EXCLUDED_TAG },
    private val highlightColor: Int = Color.WHITE,
    /**
     * 每次按下新控件时读取一次：形变程度与触点光晕的倍率（[LumenEffectTuning.dragDeformation]、
     * [LumenEffectTuning.dragGlowIntensity]、[LumenEffectTuning.dragGlowRadius]）。
     */
    private val effectTuning: () -> LumenEffectTuning = { LumenEffectTuning.DEFAULT }
) {
    private enum class Motion { NONE, PRESS, DRAG, RELEASE }

    private val density = root.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(root.context).scaledTouchSlop.toFloat()
    private val gate = ElasticGestureGate()
    private val xAxis = ElasticSpringAxis()
    private val yAxis = ElasticSpringAxis()
    private val pressAxis = ElasticSpringAxis()
    private val drag = ElasticVector()
    private val scale = ElasticVector(1f, 1f)
    private val rootLocation = IntArray(2)
    private val currentRootLocation = IntArray(2)
    private val choreographer by lazy { Choreographer.getInstance() }
    private val path = ArrayList<GeometryStamp>()
    private var target: View? = null
    private var highlightHost: View? = null
    private var groupWidth = 0
    private var groupHeight = 0
    private var gapLeft = 0f
    private var gapTop = 0f
    private var gapRight = 0f
    private var gapBottom = 0f
    private val neighborGaps = FloatArray(4)
    private var lease: ElasticTransformLeases.Lease<ElasticInteractionController>? = null
    private var highlight: TouchHighlight? = null
    private var highlightAttached = false
    private var motion = Motion.NONE
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var grabbedX = 0f
    private var grabbedY = 0f
    private var windowContentWidth = 0
    private var windowContentHeight = 0
    private var limit = 0f
    /** 本次按压的形变倍率，按下新控件时取自 [effectTuning]；回弹中重新抓住同一控件时沿用。 */
    private var deformation = 1f
    private var lastMoveTime = 0L
    private var motionStartedAt = 0L
    private var lastFrameNanos = 0L
    private var framePosted = false
    private var suppressRemainder = false
    private var interceptParent: ViewParent? = null
    private val clipReliefs = ArrayList<ClipRelief>()
    private var focusObserver: ViewTreeObserver? = null
    private var disposed = false

    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (!focused) clear()
    }
    private val targetAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { clear() }
    }
    private val rootAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { observeFocus() }
        override fun onViewDetachedFromWindow(view: View) {
            clear()
            stopObservingFocus()
        }
    }
    private val frame = Choreographer.FrameCallback { time -> animateFrame(time) }

    init {
        root.addOnAttachStateChangeListener(rootAttachListener)
        if (root.isAttachedToWindow) observeFocus()
    }

    fun dispatch(event: MotionEvent, dispatchOriginal: (MotionEvent) -> Boolean): Boolean {
        if (disposed) return dispatchOriginal(event)
        try {
            return dispatchActive(event, dispatchOriginal)
        } catch (failure: Throwable) {
            clear()
            throw failure
        }
    }

    private fun dispatchActive(event: MotionEvent, original: (MotionEvent) -> Boolean): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            suppressRemainder = false
            begin(event)
            val preparedTarget = target
            val preparedLease = lease
            val handled = original(event)
            // Native ancestors must classify DOWN before adding an overlay makes the source dirty.
            // The original dispatcher may synchronously clear this controller, detach the source,
            // or start a transition; never install visuals into a superseded preparation.
            if (target !== preparedTarget || lease !== preparedLease) return handled
            // 祖先容器已把整段手势接管（回弹视口接住回弹）：内容没收到按下，不点亮高光。
            val claimed = preparedTarget != null && ElasticGestureClaim.claimedAbove(preparedTarget)
            if (!handled || !validGeometry() || claimed) clear() else activatePreparedPress()
            return handled
        }

        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val consumed = suppressRemainder
            clear()
            suppressRemainder = false
            return if (consumed) true else original(event)
        }
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            (pointerId != MotionEvent.INVALID_POINTER_ID && event.getPointerId(0) != pointerId)
        ) {
            clear()
            return if (suppressRemainder) true else original(event)
        }

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (suppressRemainder) {
                suppressRemainder = false
                releaseIntercept()
                gate.yield()
                pointerId = MotionEvent.INVALID_POINTER_ID
                if (motion == Motion.DRAG && validGeometry()) {
                    if (event.eventTime - lastMoveTime > 80L) {
                        xAxis.velocity = 0f
                        yAxis.velocity = 0f
                    }
                    motion = Motion.RELEASE
                    motionStartedAt = SystemClock.uptimeMillis()
                    lastFrameNanos = 0L
                    scheduleFrame()
                } else clear()
                return true
            }
            // A normal click may synchronously open a morphing modal from this exact View.
            clear()
            return original(event)
        }

        if (event.actionMasked == MotionEvent.ACTION_MOVE && target != null) {
            if (!validGeometry() || !ValueAnimator.areAnimatorsEnabled()) {
                clear()
                return if (suppressRemainder) true else original(event)
            }
            var decision = ElasticGestureDecision.OBSERVE
            val rawOffsetX = event.rawX - event.x
            val rawOffsetY = event.rawY - event.y
            // Batched MOVE history can contain a fast scroll before the hold deadline.
            for (index in 0 until event.historySize) {
                decision = gate.move(event.getHistoricalEventTime(index),
                    event.getHistoricalX(0, index) + rawOffsetX - downX,
                    event.getHistoricalY(0, index) + rawOffsetY - downY, touchSlop)
                if (decision == ElasticGestureDecision.YIELD) break
            }
            if (decision != ElasticGestureDecision.YIELD) {
                decision = gate.move(event.eventTime, event.rawX - downX, event.rawY - downY, touchSlop)
            }
            if (decision == ElasticGestureDecision.YIELD) {
                clear()
                return if (suppressRemainder) true else original(event)
            }
            if (decision == ElasticGestureDecision.CAPTURE || decision == ElasticGestureDecision.DRAG) {
                if (!suppressRemainder) {
                    // A parent may have intercepted DOWN despite a clickable descendant hit.
                    // The native pressed state confirms that this candidate received the stream;
                    // custom touch owners that do not set it retain their complete original input.
                    // 检查命中控件而非形变组：组可能是外层无点击语义的包裹层
                    // （GitHub 图标的角标 FrameLayout），它永远不会进入 pressed 状态，
                    // 而真正拿到事件流的里面那枚 ImageView 会。拿组来判会把长按拖动直接误杀。
                    val pressedView = highlightHost ?: target
                    if (pressedView?.isPressed != true) {
                        clear()
                        return original(event)
                    }
                    suppressRemainder = true
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    try { original(cancel) } finally { cancel.recycle() }
                    // Dispatching CANCEL may cause the application to replace/detach the target.
                    if (!validGeometry()) {
                        clear()
                        return true
                    }
                    interceptParent = target?.parent
                    interceptParent?.requestDisallowInterceptTouchEvent(true)
                    motion = Motion.DRAG
                    relieveAncestorClipping()
                }
                dragTo(event.rawX - downX, event.rawY - downY, event.eventTime)
                return true
            }
        }
        return if (suppressRemainder) true else original(event)
    }

    /** End local visuals synchronously. An already cancelled business stream stays cancelled to UP. */
    fun clear() {
        gate.yield()
        pointerId = MotionEvent.INVALID_POINTER_ID
        releaseIntercept()
        removeVisual(restore = true, releaseLease = true)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        clear()
        root.removeOnAttachStateChangeListener(rootAttachListener)
        stopObservingFocus()
    }

    private fun begin(event: MotionEvent) {
        gate.yield()
        releaseIntercept()
        pointerId = MotionEvent.INVALID_POINTER_ID
        if (event.pointerCount != 1 || !root.isAttachedToWindow || !root.hasWindowFocus() ||
            !ValueAnimator.areAnimatorsEnabled() || !event.rawX.isFinite() || !event.rawY.isFinite()
        ) {
            clear()
            return
        }
        root.getLocationOnScreen(rootLocation)
        // A full-content dismiss scrim may exclude status/navigation bars but still fill its window.
        @Suppress("DEPRECATION")
        val systemInsets = root.rootWindowInsets
        @Suppress("DEPRECATION")
        val horizontalInsets = (systemInsets?.systemWindowInsetLeft ?: 0) +
            (systemInsets?.systemWindowInsetRight ?: 0)
        @Suppress("DEPRECATION")
        val verticalInsets = (systemInsets?.systemWindowInsetTop ?: 0) +
            (systemInsets?.systemWindowInsetBottom ?: 0)
        windowContentWidth = (root.width - horizontalInsets).coerceAtLeast(1)
        windowContentHeight = (root.height - verticalInsets).coerceAtLeast(1)
        val localX = event.rawX - rootLocation[0]
        val localY = event.rawY - rootLocation[1]
        var leftEdge = 24f * density
        var rightEdge = leftEdge
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val insets = root.rootWindowInsets?.systemGestureInsets
            if (insets != null) {
                leftEdge = maxOf(leftEdge, insets.left.toFloat())
                rightEdge = maxOf(rightEdge, insets.right.toFloat())
            }
        }
        if (localX <= leftEdge || localX >= root.width - rightEdge) {
            clear()
            return
        }
        val hitPath = ArrayList<View>()
        val hit = hitTarget(root, localX, localY, hitPath, 0)
        val view = hit?.view
        if (view == null) {
            clear()
            return
        }
        val group = resolveMotionGroup(view, hitPath)
        // highlightHost 必须在 removeVisual 之后赋值：removeVisual 会把它清空，
        // 而 continuing 分支之外每次新按压都会先清一次旧视觉。提前赋值会让本次
        // 命中的控件丢失，捕获门槛（看命中控件的 pressed 状态）随之失效——
        // GitHub 图标的形变组是外层角标 FrameLayout，永远不 pressed。
        val continuing = target === group && lease?.let { leases.owns(group, it) } == true
        if (continuing) {
            stopFrames()
            path.clear()
        } else {
            // 先取调参：宿主 lambda 抛异常时还没有动过任何视觉与租约。
            val tuning = effectTuning()
            removeVisual(restore = true, releaseLease = true)
            leases.owner(group)?.relinquish(group)
            val observed = ElasticTransform(group.translationX, group.translationY, group.scaleX, group.scaleY)
            val owned = leases.acquire(group, this, observed)
            lease = owned
            target = group
            xAxis.reset(observed.translationX - owned.original.translationX)
            yAxis.reset(observed.translationY - owned.original.translationY)
            deformation = tuning.dragDeformation
            // 接手回弹中的控件：按本次倍率下的按压深度反推，渲染尺寸才连续。
            pressAxis.reset(ElasticDeformationTuning.pressFromScale(
                observed.scaleX / owned.original.scaleX, deformation))
            highlight = TouchHighlight(view, density, highlightColor, tuning.dragGlowIntensity, tuning.dragGlowRadius)
            view.addOnAttachStateChangeListener(targetAttachListener)
        }
        highlightHost = view
        for (ancestor in hitPath) path.add(GeometryStamp(ancestor))
        downX = event.rawX
        downY = event.rawY
        downLocalX = hit.x
        downLocalY = hit.y
        pointerId = event.getPointerId(0)
        grabbedX = xAxis.value
        grabbedY = yAxis.value
        xAxis.velocity = 0f
        yAxis.velocity = 0f
        lastMoveTime = event.eventTime
        limit = ElasticDeformationTuning.travelLimit(
            ElasticMotionPolicy.positionLimit(group.width, group.height, density), deformation)
        groupWidth = group.width
        groupHeight = group.height
        captureGroupGaps(group)
        gate.begin(event.eventTime)
        motion = Motion.PRESS
        motionStartedAt = SystemClock.uptimeMillis()
    }

    private fun activatePreparedPress() {
        val view = highlightHost ?: target ?: return
        val visual = highlight ?: return
        if (!highlightAttached) {
            view.overlay.add(visual)
            highlightAttached = true
        }
        highlight?.moveTo(downLocalX, downLocalY)
        scheduleFrame()
    }

    private fun dragTo(dx: Float, dy: Float, eventTime: Long) {
        ElasticMotionPolicy.dragFrom(grabbedX, grabbedY, dx, dy, touchSlop, limit, drag)
        // 全向可拖：把行程上限同时作为每个方向的最低预算传给钳制——否则 WRAP_CONTENT
        // 父容器里最后一个子元素的尾边间隙为 0，该方向（典型如向下）被整体钳死。
        // 朝相邻卡片的方向不越过邻居（网格、瀑布流、横向轮播），见 captureGroupGaps。
        ElasticMotionGroupPolicy.clampToParent(
            drag.x, drag.y, gapLeft, gapTop, gapRight, gapBottom, drag)
        val elapsed = eventTime - lastMoveTime
        xAxis.velocity = ElasticMotionPolicy.releaseVelocity(xAxis.value, drag.x, elapsed, limit)
        yAxis.velocity = ElasticMotionPolicy.releaseVelocity(yAxis.value, drag.y, elapsed, limit)
        xAxis.value = drag.x
        yAxis.value = drag.y
        lastMoveTime = eventTime
        highlight?.moveTo(downLocalX + dx, downLocalY + dy)
        applyVisual()
        if (!pressAxis.atRest(1f, .0003f)) scheduleFrame()
    }

    private fun animateFrame(timeNanos: Long) {
        framePosted = false
        if (!validGeometry() || !ValueAnimator.areAnimatorsEnabled()) {
            clear()
            return
        }
        val seconds = if (lastFrameNanos == 0L) 1f / 120f
        else ((timeNanos - lastFrameNanos).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
        lastFrameNanos = timeNanos
        val pressed = if (motion == Motion.RELEASE) 0f else 1f
        pressAxis.advance(seconds, pressed)
        if (motion == Motion.RELEASE) {
            xAxis.advance(seconds, 0f)
            yAxis.advance(seconds, 0f)
        }
        val rest = pressAxis.atRest(pressed, .0003f) &&
            (motion != Motion.RELEASE || (xAxis.atRest(0f, .025f * density) &&
                yAxis.atRest(0f, .025f * density)))
        val expired = motion != Motion.DRAG &&
            SystemClock.uptimeMillis() - motionStartedAt >= ElasticMotionPolicy.MAX_SETTLE_MILLIS
        if ((rest || expired) && motion == Motion.RELEASE) {
            clear()
            return
        }
        if (rest || expired) {
            pressAxis.reset(pressed)
            if (motion == Motion.RELEASE) {
                xAxis.reset()
                yAxis.reset()
            }
        }
        applyVisual()
        if (!rest && !expired) scheduleFrame() else lastFrameNanos = 0L
    }

    private fun applyVisual() {
        val view = target ?: return
        val owned = lease ?: return
        if (!leases.owns(view, owned)) return
        val original = owned.original
        ElasticMotionPolicy.scale(pressAxis.value, xAxis.value, yAxis.value, limit, scale)
        val capPx = ElasticMotionGroupPolicy.STRETCH_CAP_DP * density
        val tx = original.translationX + xAxis.value
        val ty = original.translationY + yAxis.value
        val sx = original.scaleX * ElasticMotionGroupPolicy.cappedScale(
            ElasticDeformationTuning.scale(scale.x, deformation), groupWidth, capPx)
        val sy = original.scaleY * ElasticMotionGroupPolicy.cappedScale(
            ElasticDeformationTuning.scale(scale.y, deformation), groupHeight, capPx)
        owned.record(tx, ty, sx, sy)
        view.translationX = tx
        view.translationY = ty
        view.scaleX = sx
        view.scaleY = sy
        highlight?.update(pressAxis.value, xAxis.value, yAxis.value, xAxis.velocity, yAxis.velocity,
            xAxis.value - grabbedX, yAxis.value - grabbedY)
        notifyPositionChanged(view)
    }

    /** The visual unit that must bounce: the hit view promoted to its surface owner. */
    private fun resolveMotionGroup(view: View, hitPath: List<View>): View {
        val chain = ArrayList<View>(hitPath.size)
        var node: View? = view
        while (node != null) {
            chain.add(node)
            if (node === root) break
            node = node.parent as? View ?: break
        }
        if (chain.size < 2) return view
        val nodes = ArrayList<ElasticGroupNode>(chain.size)
        for (v in chain) nodes.add(ElasticGroupNode(
            hasSurface = v.background?.alpha ?: 0 > 0,
            fillsWindow = v.width >= windowContentWidth * .9f && v.height >= windowContentHeight * .9f,
            containerOnly = v.tag == CONTAINER_TAG,
            childCount = (v as? ViewGroup)?.childCount ?: 0))
        var depth = ElasticMotionGroupPolicy.promotionDepth(nodes).coerceAtMost(chain.lastIndex)
        // A tight wrapper (the badge frame around the GitHub icon) leaves the promoted node
        // zero travel room once clampToParent runs; climb until some parent adds slack.
        // Window-fillers and the root never become the drag unit.
        val slack = 4f * density
        while (depth < chain.lastIndex) {
            val parent = chain[depth + 1]
            if (parent === root || parent !is ViewGroup) break
            if (parent.width >= windowContentWidth * .9f && parent.height >= windowContentHeight * .9f) break
            val current = chain[depth]
            if (!ElasticMotionGroupPolicy.isTightWrap(
                    (current.left - parent.paddingLeft).coerceAtLeast(0).toFloat(),
                    (current.top - parent.paddingTop).coerceAtLeast(0).toFloat(),
                    (parent.width - parent.paddingRight - current.right).coerceAtLeast(0).toFloat(),
                    (parent.height - parent.paddingBottom - current.bottom).coerceAtLeast(0).toFloat(),
                    slack)
            ) break
            depth++
        }
        return chain[depth]
    }

    /**
     * 每个方向的行程上限（px），只在按下时算一次：
     * 父容器内缘按 max(间隙, limit) 放行；朝同层兄弟时不越过兄弟（留出拉伸余量）。
     * 竖向列表里只有上下有邻居；网格、瀑布流、横向轮播里四个方向都可能有。
     */
    private fun captureGroupGaps(group: View) {
        val parent = group.parent as? ViewGroup ?: run {
            gapLeft = limit; gapTop = limit; gapRight = limit; gapBottom = limit; return
        }
        val count = parent.childCount
        val siblings = FloatArray(count * 4) { Float.NaN }
        for (index in 0 until count) {
            val sibling = parent.getChildAt(index)
            if (sibling === group || sibling.visibility != View.VISIBLE || sibling.width <= 0 || sibling.height <= 0) continue
            siblings[index * 4] = sibling.left + sibling.translationX
            siblings[index * 4 + 1] = sibling.top + sibling.translationY
            siblings[index * 4 + 2] = sibling.right + sibling.translationX
            siblings[index * 4 + 3] = sibling.bottom + sibling.translationY
        }
        ElasticMotionGroupPolicy.neighborGaps(group.left.toFloat(), group.top.toFloat(),
            group.right.toFloat(), group.bottom.toFloat(), siblings, neighborGaps)
        val margin = ElasticMotionGroupPolicy.STRETCH_CAP_DP * density
        fun bound(parentGap: Int, neighbor: Float) =
            ElasticMotionGroupPolicy.travelBound(parentGap.coerceAtLeast(0).toFloat(), neighbor, limit, margin)
        gapLeft = bound(group.left - parent.paddingLeft, neighborGaps[0])
        gapTop = bound(group.top - parent.paddingTop, neighborGaps[1])
        gapRight = bound(parent.width - parent.paddingRight - group.right, neighborGaps[2])
        gapBottom = bound(parent.height - parent.paddingBottom - group.bottom, neighborGaps[3])
    }

    private fun validGeometry(): Boolean {        val view = target ?: return false
        val owned = lease ?: return false
        if (disposed || !root.hasWindowFocus() || !leases.owns(view, owned)) return false
        root.getLocationOnScreen(currentRootLocation)
        if (currentRootLocation[0] != rootLocation[0] || currentRootLocation[1] != rootLocation[1]) return false
        for (stamp in path) if (!stamp.matches(if (stamp.view.get() === view) owned else null)) return false
        return true
    }

    private fun scheduleFrame() {
        if (framePosted || disposed || motion == Motion.NONE) return
        framePosted = true
        choreographer.postFrameCallback(frame)
    }

    private fun stopFrames() {
        if (framePosted) choreographer.removeFrameCallback(frame)
        framePosted = false
        lastFrameNanos = 0L
    }

    private fun removeVisual(restore: Boolean, releaseLease: Boolean) {
        stopFrames()
        val view = target
        val owned = lease
        motion = Motion.NONE
        target = null
        lease = null
        path.clear()
        restoreAncestorClipping()
        if (view != null) {
            highlightHost?.removeOnAttachStateChangeListener(targetAttachListener)
            if (highlightAttached) {
                val host = highlightHost ?: view
                highlight?.let { host.overlay.remove(it) }
            }
            if (owned != null && leases.owns(view, owned)) {
                if (restore) {
                    // An external animator can take a property while pressed. Never overwrite it.
                    if (view.translationX == owned.writtenX) view.translationX = owned.original.translationX
                    if (view.translationY == owned.writtenY) view.translationY = owned.original.translationY
                    if (view.scaleX == owned.writtenScaleX) view.scaleX = owned.original.scaleX
                    if (view.scaleY == owned.writtenScaleY) view.scaleY = owned.original.scaleY
                }
                if (releaseLease) leases.release(view, owned)
                if (restore) notifyPositionChanged(view)
            }
        }
        highlight = null
        highlightHost = null
        highlightAttached = false
    }

    /** Preserve the first baseline when another window/controller acquires this same View. */
    private fun relinquish(view: View) {
        if (target !== view) return
        gate.yield()
        pointerId = MotionEvent.INVALID_POINTER_ID
        releaseIntercept()
        removeVisual(restore = false, releaseLease = false)
    }

    private fun releaseIntercept() {
        val parent = interceptParent
        interceptParent = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    /**
     * 拖动期临时放行祖先链的裁剪：拉伸与位移让形变组溢出自身布局边界，而祖先
     * 的 clipChildren/clipToPadding 默认 true，会把溢出部分裁成一条矩形断边。
     * 只在 DRAG 期间关闭（常态裁剪语义不变），收尾由 removeVisual 统一恢复。
     * clipToOutline 不动——IconAnchoredMotionLayer 之类的容器靠它做形变边界，
     * 且展开后自行恢复，不属于"矩形断框"的来源。
     */
    private fun relieveAncestorClipping() {
        val view = target ?: return
        var node = view.parent
        while (node != null && clipReliefs.size < 32) {
            val group = node as? ViewGroup
            if (group != null && (group.clipChildren || group.clipToPadding)) {
                clipReliefs += ClipRelief(group, group.clipChildren, group.clipToPadding)
                group.clipChildren = false
                group.clipToPadding = false
            }
            node = if (node === root) null else node.parent
        }
    }

    private fun restoreAncestorClipping() {
        for (index in clipReliefs.indices) {
            val relief = clipReliefs[index]
            relief.view.clipChildren = relief.clipChildren
            relief.view.clipToPadding = relief.clipToPadding
        }
        clipReliefs.clear()
    }

    /** 一次拖动放行的祖先裁剪原值；view 是强引用，存活期不超过一条手势流。 */
    private class ClipRelief(
        val view: ViewGroup,
        val clipChildren: Boolean,
        val clipToPadding: Boolean
    )

    private fun observeFocus() {
        stopObservingFocus()
        if (disposed) return
        root.viewTreeObserver.also {
            focusObserver = it
            it.addOnWindowFocusChangeListener(focusListener)
        }
    }

    private fun stopObservingFocus() {
        focusObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        focusObserver = null
    }

    private data class Hit(val view: View?, val x: Float = 0f, val y: Float = 0f)

    /** A blocked child blocks its clickable ancestor too; it cannot fall through as empty space. */
    private fun hitTarget(view: View, x: Float, y: Float, resultPath: MutableList<View>, depth: Int): Hit? {
        if (view.visibility != View.VISIBLE || x < 0f || y < 0f || x >= view.width || y >= view.height) return null
        if (depth >= 64 || !view.isEnabled || isExcluded(view) || view.isLongClickable ||
            view.isContextClickable || view is EditText || view is AbsSeekBar ||
            // Switch rows keep their native press/toggle semantics; the whole row
            // (track and text alike) opts out of the elastic hold-and-drag gesture.
            // 来源工程判的是 SwitchCompat / Switch；引擎不依赖 AppCompat，改判"开关类 CompoundButton"
            // （CheckBox、RadioButton 以外的，含 Switch、SwitchCompat、SwitchMaterial、ToggleButton）。
            isSwitchLike(view) || !stableTransformAtDown(view) ||
            (view is TextView && (view.isTextSelectable || view.movementMethod != null))
        ) return Hit(null)
        val role = ElasticEligibilityPolicy.nodeRole(
            view.alpha, view.hasTransientState(), view.animation?.hasEnded() == false,
            view.isClickable, view === root, view.width, view.height, windowContentWidth, windowContentHeight,
            containerOnly = view.tag == CONTAINER_TAG
        )
        if (role == ElasticNodeRole.BLOCKED) return Hit(null)
        resultPath.add(view)
        if (view is ViewGroup) {
            val children = ArrayList<View>(view.childCount)
            for (index in 0 until view.childCount) children.add(view.getChildAt(index))
            // Stable sorting keeps the normal child order when Z is equal; reverse is front to back.
            children.sortWith(compareBy { it.z })
            for (index in children.lastIndex downTo 0) {
                val child = children[index]
                if (child.visibility != View.VISIBLE) continue
                val point = floatArrayOf(x + view.scrollX - child.left, y + view.scrollY - child.top)
                if (!child.matrix.isIdentity) {
                    val inverse = Matrix()
                    if (!child.matrix.invert(inverse)) continue
                    inverse.mapPoints(point)
                }
                val childHit = hitTarget(child, point[0], point[1], resultPath, depth + 1)
                if (childHit != null) {
                    // A leased transform is safe only on the visual target, never above another one.
                    if (childHit.view != null && (view.scaleX != 1f || view.scaleY != 1f)) return Hit(null)
                    return childHit
                }
            }
        }
        if (role == ElasticNodeRole.TARGET) {
            // Interactive scrolling widgets retain their drag even after a stationary hold.
            if (view.canScrollHorizontally(-1) || view.canScrollHorizontally(1) ||
                view.canScrollVertically(-1) || view.canScrollVertically(1)
            ) return Hit(null)
            return Hit(view, x, y)
        }
        resultPath.removeAt(resultPath.lastIndex)
        return null
    }

    private fun isSwitchLike(view: View): Boolean =
        view is CompoundButton && view !is CheckBox && view !is RadioButton

    private fun stableTransformAtDown(view: View): Boolean {
        if (!view.isAttachedToWindow || !view.isLaidOut || view.isLayoutRequested ||
            view.rotation != 0f || view.rotationX != 0f || view.rotationY != 0f ||
            !view.translationX.isFinite() || !view.translationY.isFinite()
        ) return false
        val owned = leases.current(view)
        return if (owned != null) {
            view.translationX == owned.writtenX && view.translationY == owned.writtenY &&
                view.scaleX == owned.writtenScaleX && view.scaleY == owned.writtenScaleY &&
                owned.original.scaleX == 1f && owned.original.scaleY == 1f
        } else view.scaleX == 1f && view.scaleY == 1f
    }

    private class GeometryStamp(source: View) {
        val view = WeakReference(source)
        private val parent = WeakReference(source.parent)
        private val width = source.width
        private val height = source.height
        private val left = source.left
        private val top = source.top
        private val scrollX = source.scrollX
        private val scrollY = source.scrollY
        private val x = source.translationX
        private val y = source.translationY
        private val scaleX = source.scaleX
        private val scaleY = source.scaleY
        private val alpha = source.alpha

        fun matches(owned: ElasticTransformLeases.Lease<ElasticInteractionController>?): Boolean {
            val v = view.get() ?: return false
            return v.isAttachedToWindow && v.visibility == View.VISIBLE && v.isEnabled &&
                v.parent === parent.get() && v.width == width && v.height == height &&
                v.left == left && v.top == top && v.scrollX == scrollX && v.scrollY == scrollY &&
                ElasticEligibilityPolicy.unchangedOpacity(alpha, v.alpha, v.hasTransientState(),
                    v.animation?.hasEnded() == false) &&
                v.rotation == 0f && v.rotationX == 0f && v.rotationY == 0f &&
                v.translationX == (owned?.writtenX ?: x) && v.translationY == (owned?.writtenY ?: y) &&
                v.scaleX == (owned?.writtenScaleX ?: scaleX) && v.scaleY == (owned?.writtenScaleY ?: scaleY)
        }
    }

    /**
     * Cached vector highlight; no bitmap, background replacement or View alpha changes.
     *
     * 几何与底栏/scrub 条共用 [GlowState]/[GlowConfig]（见 AdaptiveGlowPolicy 的连续域规则），
     * 但三处按表面语义分流：这里 `axialBoost = 0`（亮度均匀，方向性增亮只属于 scrub 表面）、
     * `edgeBandPx = 0`（clipPath 本就把高亮裁在控件圆角内，不需要第二道边缘衰减）、
     * `travelEpsPx = 0`（位移输入已减掉 touchSlop）。裁剪路径 [clip] 保持不变。
     */
    private class TouchHighlight(
        private val view: View,
        density: Float,
        color: Int,
        glowIntensity: Float,
        glowRadiusScale: Float
    ) : Drawable() {
        private val width = view.width.toFloat()
        private val height = view.height.toFloat()
        private val radius = ElasticGlowTuning.radius(maxOf(width, height) * .7f, glowRadiusScale)
        private val baseAlpha = ElasticGlowTuning.baseAlpha(HIGHLIGHT_BASE_ALPHA, glowIntensity)
        private val renderer = TouchGlowRenderer(color, radius)
        private val state = GlowState()
        private val frame = GlowFrame()
        private val limitPx = ElasticMotionPolicy.positionLimit(view.width, view.height, density)
        // 长按高亮走**流动模型**（oriented = false）：等向胀缩 + 光心流动滞后，
        // 不随手势方向转向。这个场景下手指会连续改变方向，任何"朝向手势的长条"
        // 都会被迫反复转轴——实测单帧亮核横甩 6.6px、亮度脉冲 34%。没有取向轴之后，
        // 反向只是位置量摆回，天然平滑（用户 2026-09-21 指定的方向）。
        private val config = GlowConfig.create(
            density = density,
            maxTravelPx = limitPx,
            travelEpsPx = 0f,
            velocityRefPxPerSec = limitPx * VELOCITY_REF_FACTOR,
            edgeBandPx = 0f,
            axialBoost = 0f,
            oriented = false,
            // 基准 alpha 只有 32（底栏/scrub 是 72）：越界堆积的增亮增益加大，
            // 否则钉在边缘的光团在小控件上亮度不足以读出"集中"——顶栏/弹窗行同款诉求。
            pileGain = 2.0f
        )
        private var centerX = width * .5f
        private var centerY = height * .5f
        private var lastUpdateNanos = 0L
        private var corner = Float.NaN
        private val clip = Path()
        private val screenLoc = IntArray(2)

        init {
            // 高光必须沿控件的显示边缘裁剪。半径依次取背景、前景的 outline；
            // **空 outline 与 RADIUS_UNDEFINED（负无穷）** 视为"该层没有声明圆角"，
            // 绝不能当成 0 让圆角矩形退化成直角——圆形图标的透明四角便会显出方形高光。
            // 而 **0.0f 是"确实声明了直角"**：RippleDrawable.getOutline() 只报第一个非
            // mask 层，自绘涟漪的 content 与 mask 同圆角（`CoverableRippleDrawable.rounded`），
            // 这里拿到的才是设计圆角；content 若漏了
            // 圆角，报出的 0 会让高光按方角裁剪、与涟漪边缘割裂。
            val outline = Outline()
            val rect = Rect()
            view.background?.getOutline(outline)
            if (outline.getRect(rect) && outline.radius.isFinite()) corner = outline.radius
            if (corner.isNaN()) {
                view.foreground?.getOutline(outline)
                if (outline.getRect(rect) && outline.radius.isFinite()) corner = outline.radius
            }
            if (corner.isNaN()) corner = minOf(16f * density, height / 2f)
            // 半径等于短边一半的圆角矩形恰是正圆/正椭圆，圆形按钮由此得到圆形高光。
            corner = corner.coerceIn(0f, minOf(width, height) * .5f)
            clip.addRoundRect(0f, 0f, width, height, corner, corner, Path.Direction.CW)
            setBounds(0, 0, view.width, view.height)
        }

        /** 只记录触点（glow 中心，不钳制——越界量由策略层换成贴边堆积）；每帧形状由 [update] 统一计算。 */
        fun moveTo(x: Float, y: Float) {
            centerX = x
            centerY = y
        }

        /**
         * 每个动画帧与每次拖动调用一次。速度直接用弹性轴自身的速度（拖动与回弹
         * 两条路径都在更新它），不需要第二套差分估计。
         */
        fun update(press: Float, offsetX: Float, offsetY: Float, velocityX: Float, velocityY: Float,
                   viewShiftX: Float, viewShiftY: Float) {
            val now = System.nanoTime()
            val dt = if (lastUpdateNanos == 0L) GlowState.DEFAULT_DT_SECONDS
            else ((now - lastUpdateNanos).coerceAtLeast(0L)) / 1_000_000_000f
            lastUpdateNanos = now
            frame.press = press
            frame.offsetX = offsetX
            frame.offsetY = offsetY
            frame.velocityX = velocityX
            frame.velocityY = velocityY
            // 触点坐标换算到当前系：centerX/Y 记录的是"按下时刻坐标系"（downLocal + 原始
            // delta），而宿主视图随平移组移动了 viewShift——不换算会把视图平移重复计入
            // 越界量（+|t|），同时 room（实时屏位）收缩 |t|：堆积量被双向放大成弹簧的
            // 函数，回弹瞬间强度骤降（用户 2026-09-21 抓帧实证）。
            val touchX = centerX - viewShiftX
            val touchY = centerY - viewShiftY
            frame.centerX = touchX
            frame.centerY = touchY
            frame.boundsWidth = width
            frame.boundsHeight = height
            frame.cornerRadius = corner
            // 可触达空间：控件贴屏幕边缘时触点走不满 pileRefPx——把剩余空间交给策略层
            // 压缩满额行程，贴边控件也能堆出完整"集中"。getLocationOnScreen 取实时屏位
            // （含当前平移），与当前系触点自洽：最大可达越界 = 当前边→屏缘距离。
            view.getLocationOnScreen(screenLoc)
            val metrics = view.resources.displayMetrics
            frame.pileRoomPx = reachablePileRoomPx(
                screenLoc[0], screenLoc[1],
                screenLoc[0] + view.width, screenLoc[1] + view.height,
                metrics.widthPixels, metrics.heightPixels,
                touchX, touchY, width, height
            )
            state.update(frame, dt, radius, baseAlpha, config)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (!state.shape.visible) return
            val save = canvas.save()
            canvas.clipPath(clip)
            renderer.draw(canvas, state.shape)
            canvas.restoreToCount(save)
        }

        // 高亮的 alpha 完全由手势状态经连续域映射给出；Drawable 的这两个入口不参与。
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        /** Set on a custom interaction owner such as LumenNavigationBar, or supply [isExcluded]. */
        const val EXCLUDED_TAG = "lumen.elastic.excluded"
        /** A modal/container can host elastic controls but must not itself deform on blank-space taps. */
        const val CONTAINER_TAG = "lumen.elastic.container"
        /** 触点高光的基础 alpha；与改造前 `32 * progress` 的峰值逐字一致。 */
        private const val HIGHLIGHT_BASE_ALPHA = 32
        /** 速度归一化参考 = limit × 20，与 releaseVelocity 的 clamp 同口径。 */
        private const val VELOCITY_REF_FACTOR = 20f
        private val leases = ElasticTransformLeases<View, ElasticInteractionController>()
    }
}
