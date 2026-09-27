package com.lumen.coacervation.engine.motion.morph

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.view.doOnPreDraw
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.motion.InterruptibleMotionContinuation
import com.lumen.coacervation.engine.motion.InterruptibleMotionPhase as MotionState
import com.lumen.coacervation.engine.motion.InterruptibleMotionPolicy
import com.lumen.coacervation.engine.motion.InterruptibleMotionSession
import com.lumen.coacervation.engine.motion.MorphCornerPolicy
import com.lumen.coacervation.engine.motion.MotionRect
import kotlin.math.abs

/**
 * 来源页一侧：从一个入口条目打开"条目形变成全屏"的目标 Activity（适配标准 §13.5）。
 *
 * 目标 Activity 的主题必须是透明窗口（`windowIsTranslucent=true`、透明 `windowBackground`、不 dim），
 * 收缩时才能露出下面的来源页；它的内容根由 [ContainerMorphHost] 承载、由 [ContainerMorphController] 驱动。
 */
@MainThread
public object ContainerMorphLauncher {
    /**
     * 登记来源入口、把当前几何写进 Intent、启动目标并关掉系统转场。
     * [entry] 是整个入口条目，[title] 是入口里的标题 TextView（与目标页标题相同文字时会做标题迁移）。
     */
    @JvmStatic
    @JvmOverloads
    public fun launch(
        source: Activity,
        destination: Class<out Activity>,
        entry: View,
        title: TextView,
        configure: (Intent) -> Unit = {},
        start: (Intent) -> Unit = source::startActivity
    ) {
        val registry = ContainerMorphOriginRegistry.of(destination)
        registry.register(entry, title, source.findViewById(android.R.id.content))
        val intent = Intent(source, destination)
        configure(intent)
        registry.snapshot()?.putInto(intent)
        start(intent)
        suppressLegacyTransition(source)
    }

    /** 来源页销毁时清掉登记（只清属于这个入口的那份）。 */
    @JvmStatic
    public fun clear(destination: Class<out Activity>, entry: View?) {
        ContainerMorphOriginRegistry.of(destination).clear(entry)
    }

    @Suppress("DEPRECATION")
    internal fun suppressLegacyTransition(activity: Activity) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) activity.overridePendingTransition(0, 0)
    }
}

/**
 * 目标页一侧：驱动入口 → 全屏的可 seek 形变，含打开、关闭、预测式返回（拖动预览、取消回弹、松手续接）
 * 与打断续接（适配标准 §13.5）。
 *
 * 从来源工程 `DiagnosticsActivity` 抽出，状态机与时长原样保留。宿主负责：
 * 1. `setContentView(host)` → `host.installContentInsets()` → `host.replacePage(page, toolbarTitle)`；
 * 2. `lumen.bindRoot(host.liquidBackdropRoot())`；
 * 3. 把返回事件转给 [beginPredictiveBack] / [progressPredictiveBack] / [cancelPredictiveBack] / [commitBack]；
 * 4. 调用 [start]；`onDestroy` 调 [onDestroy]。
 *
 * @param allowLaunchOriginForExit 通常传 `savedInstanceState == null`：重建后的页面不再信任启动时的旧几何。
 * @param isBusinessBlocked 业务上暂时不允许返回（导出进行中、选择器打开、页内弹窗开着……）。
 * @param onMotionStarted 形变开始前（宿主在这里结束回弹视口等会与形变冲突的效果）。
 * @param onExpanded 到达展开端（宿主在这里安装回弹、渲染延后的内容）。
 */
@MainThread
public class ContainerMorphController @JvmOverloads constructor(
    private val activity: Activity,
    public val host: ContainerMorphHost,
    private val lumen: LumenActivityDelegate,
    destination: Class<out Activity>,
    private val launchOrigin: ContainerMorphOrigin?,
    private val allowLaunchOriginForExit: Boolean,
    private val destinationTitle: () -> TextView?,
    private val collapsedCornerRadiusDp: Float = ContainerMorphEntrySpec.CORNER_RADIUS_DP,
    private val isBusinessBlocked: () -> Boolean = { false },
    private val onMotionStarted: () -> Unit = {},
    private val onExpanded: () -> Unit = {}
) {
    private enum class BackTarget { NONE, FINISH_ACTIVITY, BLOCKED }

    private val registry = ContainerMorphOriginRegistry.of(destination)
    private val density get() = activity.resources.displayMetrics.density
    private var motionGeometry: ContainerMorphGeometry? = null
    private var motionAnimator: ValueAnimator? = null
    private val motionSession = InterruptibleMotionSession()
    private var motionWasInterrupted = false
    private var motionTitleMode = ContainerMorphTitleMode.SOURCE_TITLE
    private var motionContentTiming = ContainerMorphContentTiming.TIMED
    private var motionState = MotionState.PREPARING_ENTRY
    private var backTarget = BackTarget.NONE
    private var gestureStartExpansion = 1f
    private var predictiveMotionActive = false
    private var finishingAfterMotion = false

    private val enterInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val closeInterpolator = PathInterpolator(
        ContainerMorphSpec.CLOSE_EASING_X1, ContainerMorphSpec.CLOSE_EASING_Y1,
        ContainerMorphSpec.CLOSE_EASING_X2, ContainerMorphSpec.CLOSE_EASING_Y2
    )
    private val commitInterpolator = PathInterpolator(
        ContainerMorphSpec.COMMIT_EASING_X1, ContainerMorphSpec.COMMIT_EASING_Y1,
        ContainerMorphSpec.COMMIT_EASING_X2, ContainerMorphSpec.COMMIT_EASING_Y2
    )
    private val cancelInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val predictiveBackInterpolator = PathInterpolator(0f, 0f, 0f, 1f)

    init {
        host.onWindowSizeChangedDuringMotion = ::handleMotionWindowSizeChange
        host.onContentMoved = { lumen.notifyPositionChanged() }
    }

    /** 已到达并停在展开端（回弹视口的 `isStretchAllowed` 应当以此为准）。 */
    public val isSettledExpanded: Boolean
        get() = motionState == MotionState.EXPANDED && motionAnimator == null &&
            host.expansion >= 0.999f && !predictiveMotionActive && !finishingAfterMotion

    /** 页内容是否可以正常渲染（入场动画中延后的内容应当等它为 true 再渲染，并在 [onExpanded] 里补上）。 */
    public val isExpanded: Boolean get() = motionState == MotionState.EXPANDED && motionAnimator == null

    // ---------------- 入场 ----------------

    /** 在 `onCreate` 末尾调用。[isFreshLaunch] 通常为 `savedInstanceState == null`（重建不再播入场）。 */
    public fun start(isFreshLaunch: Boolean) {
        motionState = MotionState.PREPARING_ENTRY
        val entryToken = motionSession.invalidate()
        val canResolveOrigin = launchOrigin != null || registry.snapshot() != null
        val shouldAnimate = isFreshLaunch && canResolveOrigin && ValueAnimator.areAnimatorsEnabled()
        if (shouldAnimate) host.prepareFirstFrameForEntry()
        host.doOnPreDraw {
            if (activity.isFinishing || activity.isDestroyed || !motionSession.owns(entryToken) ||
                motionState != MotionState.PREPARING_ENTRY) return@doOnPreDraw
            if (!shouldAnimate) {
                completeExpandedMotion()
                return@doOnPreDraw
            }
            val geometry = resolveMotionGeometry()
            if (geometry == null) {
                completeExpandedMotion()
                return@doOnPreDraw
            }
            motionGeometry = geometry
            motionContentTiming = ContainerMorphContentTiming.TIMED
            motionTitleMode = if (geometry.titleMotionEnabled) {
                ContainerMorphTitleMode.SOURCE_TITLE
            } else {
                ContainerMorphTitleMode.HIDDEN
            }
            onMotionStarted()
            motionState = MotionState.ENTERING
            host.beginMotion()
            host.applyExpansion(geometry = geometry, value = 0f, titleMode = motionTitleMode,
                contentTiming = motionContentTiming)
            host.post {
                if (activity.isFinishing || activity.isDestroyed || !motionSession.owns(entryToken) ||
                    motionState != MotionState.ENTERING) return@post
                animateMotionTo(targetExpansion = 1f, durationMs = ENTER_DURATION_MS,
                    interpolator = enterInterpolator, onEnd = ::completeExpandedMotion)
            }
        }
    }

    // ---------------- 返回 ----------------

    /** 预测式返回开始（`OnBackPressedCallback.handleOnBackStarted` / `OnBackAnimationCallback.onBackStarted`）。 */
    public fun beginPredictiveBack() {
        if (predictiveMotionActive || finishingAfterMotion || activity.isFinishing || activity.isDestroyed) return
        predictiveMotionActive = false
        backTarget = resolveBackTarget()
        if (backTarget != BackTarget.FINISH_ACTIVITY || !ValueAnimator.areAnimatorsEnabled()) return
        cancelMotionAnimator()
        prepareExitMotion(ContainerMorphContentTiming.PREDICTIVE)
        gestureStartExpansion = host.expansion
        predictiveMotionActive = true
        motionState = MotionState.PREDICTIVE_BACK
        motionSession.reset(gestureStartExpansion, SystemClock.uptimeMillis())
    }

    /** 预测式返回进度 0..1。 */
    public fun progressPredictiveBack(rawProgress: Float) {
        if (backTarget != BackTarget.FINISH_ACTIVITY || !predictiveMotionActive) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            predictiveMotionActive = false
            completeExpandedMotion()
            return
        }
        val progress = predictiveBackInterpolator.getInterpolation(rawProgress.coerceIn(0f, 1f))
        applyMotionExpansion(gestureStartExpansion * (1f - progress))
    }

    /** 预测式返回取消：按当前速度回弹到展开端。 */
    public fun cancelPredictiveBack() {
        if (backTarget == BackTarget.FINISH_ACTIVITY && predictiveMotionActive) {
            predictiveMotionActive = false
            motionState = MotionState.CANCELLING_BACK
            animateMotionTo(targetExpansion = 1f, durationMs = BACK_CANCEL_DURATION_MS,
                interpolator = cancelInterpolator, retarget = motionWasInterrupted,
                onEnd = ::completeExpandedMotion)
        }
        predictiveMotionActive = false
        backTarget = BackTarget.NONE
    }

    /** 返回提交（手势松手、三键返回、页内返回按钮都走这里）：收缩回入口后 `finish()`。 */
    public fun commitBack() {
        if (predictiveMotionActive && isBusinessBlocked()) {
            cancelPredictiveBack()
            return
        }
        val target = if (backTarget == BackTarget.NONE) resolveBackTarget() else backTarget
        val hadInteractiveStart = predictiveMotionActive
        predictiveMotionActive = false
        backTarget = BackTarget.NONE
        if (target == BackTarget.FINISH_ACTIVITY) requestClose(interactiveCommit = hadInteractiveStart)
    }

    /** 在目标 Activity `onDestroy` 调用。 */
    public fun onDestroy() {
        cancelMotionAnimator()
        host.onWindowSizeChangedDuringMotion = null
        host.onContentMoved = null
    }

    private fun resolveBackTarget(): BackTarget = when {
        !InterruptibleMotionPolicy.canNavigate(motionState,
            businessBlocked = isBusinessBlocked() || finishingAfterMotion) -> BackTarget.BLOCKED
        else -> BackTarget.FINISH_ACTIVITY
    }

    private fun handleMotionWindowSizeChange() {
        if (finishingAfterMotion) return
        val wasClosing = motionState == MotionState.CLOSING
        cancelMotionAnimator()
        backTarget = BackTarget.NONE
        predictiveMotionActive = false
        motionGeometry = null
        if (wasClosing) {
            applyMotionExpansion(0f)
            finishAfterMotion()
        } else completeExpandedMotion()
    }

    private fun prepareExitMotion(contentTiming: ContainerMorphContentTiming) {
        onMotionStarted()
        if (InterruptibleMotionPolicy.preserveFrame(motionState)) {
            // Keep the same geometry, title mode and TIMED/PREDICTIVE profile until a stable endpoint.
            motionWasInterrupted = true
            return
        }
        motionWasInterrupted = false
        motionGeometry = resolveMotionGeometry(preferLiveOrigin = true)
        motionContentTiming = contentTiming
        motionTitleMode = if (motionGeometry?.titleMotionEnabled == true) {
            ContainerMorphTitleMode.SOURCE_TITLE
        } else {
            ContainerMorphTitleMode.HIDDEN
        }
        host.beginMotion()
        applyMotionExpansion(host.expansion)
    }

    private fun requestClose(interactiveCommit: Boolean) {
        if (isBusinessBlocked()) return
        if (finishingAfterMotion || motionState == MotionState.CLOSING || motionState == MotionState.FINISHED) return
        val retarget = InterruptibleMotionPolicy.preserveFrame(motionState) && !interactiveCommit
        cancelMotionAnimator()
        if (!interactiveCommit) prepareExitMotion(ContainerMorphContentTiming.TIMED)
        motionState = MotionState.CLOSING
        val currentExpansion = host.expansion
        if (!ValueAnimator.areAnimatorsEnabled() || currentExpansion <= 0.001f) {
            applyMotionExpansion(0f)
            finishAfterMotion()
            return
        }
        val baseDuration = if (interactiveCommit) BACK_COMMIT_DURATION_MS else CLOSE_DURATION_MS
        val duration = ContainerMorphSpec.closeDurationMs(
            baseDurationMs = baseDuration,
            currentExpansion = currentExpansion,
            minimumDurationMs = MIN_CLOSE_DURATION_MS
        )
        animateMotionTo(targetExpansion = 0f, durationMs = duration,
            interpolator = if (interactiveCommit) commitInterpolator else closeInterpolator,
            retarget = retarget, onEnd = ::finishAfterMotion)
    }

    private fun animateMotionTo(
        targetExpansion: Float,
        durationMs: Long,
        interpolator: TimeInterpolator,
        retarget: Boolean = false,
        onEnd: () -> Unit
    ) {
        cancelMotionAnimator()
        val startExpansion = host.expansion
        if (!ValueAnimator.areAnimatorsEnabled() || durationMs <= 0L ||
            abs(startExpansion - targetExpansion) <= 0.001f
        ) {
            applyMotionExpansion(targetExpansion)
            onEnd()
            return
        }
        val actualDuration = if (retarget && targetExpansion == 1f)
            InterruptibleMotionPolicy.remainingDuration(durationMs, startExpansion, targetExpansion) else durationMs
        val now = SystemClock.uptimeMillis()
        val continuation = if (retarget) InterruptibleMotionContinuation(startExpansion, targetExpansion,
            motionSession.velocity(now), actualDuration) else null
        motionSession.reset(startExpansion, now)
        val token = motionSession.generation
        val animator = ValueAnimator.ofFloat(startExpansion, targetExpansion)
        val expansionDelta = targetExpansion - startExpansion
        motionAnimator = animator
        animator.duration = actualDuration
        animator.interpolator = if (continuation != null) LinearInterpolator() else interpolator
        animator.addUpdateListener { valueAnimator ->
            if (motionSession.owns(token) && motionAnimator === animator) {
                applyMotionExpansion(continuation?.value(valueAnimator.animatedFraction)
                    ?: (startExpansion + expansionDelta * valueAnimator.animatedFraction))
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                val current = motionSession.owns(token) && motionAnimator === animator
                if (current) motionAnimator = null
                if (current && !cancelled && !activity.isFinishing && !activity.isDestroyed) onEnd()
            }
        })
        animator.start()
    }

    private fun applyMotionExpansion(expansion: Float) {
        motionSession.sample(expansion, SystemClock.uptimeMillis())
        val geometry = motionGeometry
        if (geometry != null) {
            host.applyExpansion(geometry = geometry, value = expansion, titleMode = motionTitleMode,
                contentTiming = motionContentTiming)
        } else {
            host.applyFallbackExpansion(value = expansion, contentTravelPx = CONTENT_TRAVEL_DP * density,
                contentTiming = motionContentTiming)
            host.blockInteraction(true)
        }
    }

    private fun completeExpandedMotion() {
        if (activity.isFinishing || activity.isDestroyed || finishingAfterMotion) return
        host.showExpandedImmediately()
        motionGeometry = null
        motionContentTiming = ContainerMorphContentTiming.TIMED
        motionState = MotionState.EXPANDED
        motionWasInterrupted = false
        motionSession.reset(1f, SystemClock.uptimeMillis())
        predictiveMotionActive = false
        backTarget = BackTarget.NONE
        onExpanded()
    }

    private fun finishAfterMotion() {
        if (finishingAfterMotion || activity.isFinishing || activity.isDestroyed) return
        finishingAfterMotion = true
        val finishToken = motionSession.invalidate()
        motionState = MotionState.FINISHED
        host.blockInteraction(true)
        // Animator 的最终 update 与 onEnd 发生在同一帧；延后一帧 finish，确保 expansion=0 已提交给合成器，
        // 让下层真实入口自然接管，避免关闭末尾闪现。
        host.postOnAnimation {
            if (activity.isFinishing || activity.isDestroyed || !motionSession.owns(finishToken)) return@postOnAnimation
            activity.finish()
            ContainerMorphLauncher.suppressLegacyTransition(activity)
        }
    }

    private fun cancelMotionAnimator() {
        motionSession.invalidate()
        val animator = motionAnimator ?: return
        motionAnimator = null
        animator.removeAllUpdateListeners()
        animator.removeAllListeners()
        animator.cancel()
    }

    private fun resolveMotionGeometry(preferLiveOrigin: Boolean = false): ContainerMorphGeometry? {
        if (host.width <= 0 || host.height <= 0) return null
        val display = host.display ?: return null
        val tolerancePx = 4f * density
        val liveOrigin = registry.snapshot(allowHidden = preferLiveOrigin)
        val originCandidates = if (allowLaunchOriginForExit) {
            if (preferLiveOrigin) sequenceOf(liveOrigin, launchOrigin) else sequenceOf(launchOrigin, liveOrigin)
        } else {
            sequenceOf(registry.snapshot(allowHidden = preferLiveOrigin))
        }
        val origin = originCandidates.filterNotNull().firstOrNull { candidate ->
            candidate.displayId == display.displayId && candidate.displayRotation == display.rotation
        } ?: return null
        val hostLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        val mappedOrigin = ContainerMorphCoordinateMapper.map(
            origin = origin,
            destinationWindowWidth = host.width,
            destinationWindowHeight = host.height,
            destinationWindowLeftOnScreen = hostLocation[0].toFloat(),
            destinationWindowTopOnScreen = hostLocation[1].toFloat(),
            tolerancePx = tolerancePx
        ) ?: return null
        val collapsedBounds = mappedOrigin.entryBounds
        val collapsedTitleBounds = mappedOrigin.titleBounds
        val expandedBounds = MotionRect(0f, 0f, host.width.toFloat(), host.height.toFloat())
        val title = destinationTitle() ?: return null
        if (title.width <= 0 || title.height <= 0) return null
        // 两端都按文字本身对齐，不按 View 边框（标题带内边距时收尾会横跳，§15.4）。
        val expandedTitleBounds = title.boundsWithin(host)?.toTextBounds(title) ?: return null
        if (!collapsedBounds.isValid || !collapsedTitleBounds.isValid || !expandedTitleBounds.isValid) return null
        return ContainerMorphGeometry(
            collapsedBounds = collapsedBounds,
            expandedBounds = expandedBounds,
            collapsedTitleBounds = collapsedTitleBounds,
            expandedTitleBounds = expandedTitleBounds,
            collapsedTitleTextSizePx = origin.titleTextSizePx,
            expandedTitleTextSizePx = title.textSize,
            // 入口卡片自己声明的圆角优先；报不出时用构造参数（适配标准 §14.2）。
            collapsedCornerRadiusPx = MorphCornerPolicy.collapsedRadius(
                origin.entryCornerRadiusPx.takeIf { it.isFinite() } ?: (collapsedCornerRadiusDp * density),
                collapsedBounds.width, collapsedBounds.height),
            contentTravelPx = CONTENT_TRAVEL_DP * density,
            titleMotionEnabled = ContainerMorphSpec.canMoveTitle(
                collapsedLineCount = origin.titleLineCount,
                expandedLineCount = title.lineCount,
                collapsedIsLeftToRight = origin.titleLayoutDirection == View.LAYOUT_DIRECTION_LTR,
                expandedIsLeftToRight = title.layoutDirection == View.LAYOUT_DIRECTION_LTR
            )
        )
    }

    public companion object {
        public const val ENTER_DURATION_MS: Long = 370L
        public const val CLOSE_DURATION_MS: Long = 320L
        public const val BACK_COMMIT_DURATION_MS: Long = 210L
        public const val BACK_CANCEL_DURATION_MS: Long = 210L
        public const val MIN_CLOSE_DURATION_MS: Long = 80L
        public const val CONTENT_TRAVEL_DP: Float = 12f

        /** 目标 Activity 的 `onCreate` 里、`setContentView` 之前调用：关掉 API 34+ 的系统开关转场。 */
        @JvmStatic
        public fun suppressSystemTransitions(activity: Activity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
            }
        }
    }
}
