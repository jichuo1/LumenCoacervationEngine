package com.lumen.coacervation.engine.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.MainThread

/**
 * 小型过渡（适配标准 §13.9），全部从来源工程的界面细节里提炼，都支持被下一次调用打断（最新的调用胜出）。
 */
@MainThread
public object MicroMotion {

    /**
     * 文字切换：120ms 加速淡出 → 在看不见时换文字（父布局重排不会被看见）→ 180ms 减速淡入到 [restAlpha]。
     * 快速连续切换只保留最新文案；文字相同时只把透明度补回 [restAlpha]。未挂载时直接写入。
     */
    @JvmStatic
    @JvmOverloads
    public fun swapText(view: TextView, text: CharSequence, restAlpha: Float = 1f) {
        view.animate().withEndAction(null).cancel()
        if (!view.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            view.text = text
            view.alpha = restAlpha
            return
        }
        if (view.text.toString() == text.toString()) {
            view.animate().alpha(restAlpha).setDuration(180L).setInterpolator(LumenEasing.emphasizedDecelerate()).start()
            return
        }
        view.animate().alpha(0f).setDuration(120L).setInterpolator(LumenEasing.emphasizedAccelerate())
            .withEndAction {
                view.text = text
                view.animate().alpha(restAlpha).setDuration(180L)
                    .setInterpolator(LumenEasing.emphasizedDecelerate()).start()
            }.start()
    }

    /**
     * 角标弹出：以底边的"起始侧"为轴心，(0.55, 0.7) 缩放 + 向另一侧、向下错开 (4dp, 3dp) + 透明，320ms 减速长到原位。
     * 已显示时只把在途动画收回原位。
     *
     * 轴心应当朝向角标所依附的控件：角标挂在图标**右上角**（从右到左布局里是左上角）时用默认值；
     * 挂在起始侧上角时传 [growFromEnd] = true。起始侧按角标自己的布局方向解析（§15.5）。
     */
    @JvmStatic
    @JvmOverloads
    public fun showBadge(badge: View, growFromEnd: Boolean = false) {
        badge.animate().setListener(null).cancel()
        val density = badge.resources.displayMetrics.density
        val rtl = badge.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val pivotAtLeft = rtl == growFromEnd
        if (badge.visibility != View.VISIBLE) {
            badge.alpha = 0f
            badge.scaleX = 0.55f
            badge.scaleY = 0.7f
            badge.translationX = (if (pivotAtLeft) 4f else -4f) * density
            badge.translationY = 3f * density
            badge.visibility = View.VISIBLE
        }
        badge.pivotX = if (pivotAtLeft) 0f else badge.width.toFloat()
        badge.pivotY = badge.height.toFloat()
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(320L).setInterpolator(LumenEasing.emphasizedDecelerate()).start()
    }

    /** 角标收起：180ms 缩回并淡出，结束后 INVISIBLE（保留占位，避免布局跳动）。 */
    @JvmStatic
    public fun hideBadge(badge: View) {
        badge.animate().setListener(null).cancel()
        if (badge.visibility != View.VISIBLE || !badge.isAttachedToWindow) {
            badge.visibility = View.INVISIBLE
            return
        }
        badge.animate().alpha(0f).scaleX(0.55f).scaleY(0.7f)
            .setDuration(180L).setInterpolator(LumenEasing.emphasizedDecelerate())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    badge.visibility = View.INVISIBLE
                    badge.animate().setListener(null)
                }
            }).start()
    }

    /**
     * 子项显隐：父容器做一次 `ChangeBounds + Fade` 过渡——展开 260ms（短行程展开曲线）、收起 220ms（配对的收起曲线）。
     * 新的过渡开始前先结束父容器上的旧过渡，连续切换不会叠成两段。父容器未布局或子项未挂载时直接切换。
     */
    @JvmStatic
    @JvmOverloads
    public fun setVisible(parent: ViewGroup, child: View, visible: Boolean, animate: Boolean = true) {
        val targetVisibility = if (visible) View.VISIBLE else View.GONE
        if (child.visibility == targetVisibility) return
        if (!animate || !parent.isLaidOut || !child.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            child.visibility = targetVisibility
            return
        }
        TransitionManager.endTransitions(parent)
        TransitionManager.beginDelayedTransition(parent, TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(ChangeBounds())
            addTransition(Fade())
            duration = if (visible) 260L else 220L
            interpolator = if (visible) LumenEasing.secondaryExpand() else LumenEasing.secondaryCollapse()
        })
        child.visibility = targetVisibility
    }

    /**
     * 提示条出现：[root] 里其余内容用 `ChangeBounds` 让位、提示条淡入并从下方 24dp 滑上来（320ms，标准曲线）；
     * 隐藏时同一过渡收起。出现时会朗读 [announcement]（可为 null）。
     */
    @JvmStatic
    @JvmOverloads
    public fun revealHint(root: ViewGroup?, hint: View, show: Boolean, announcement: CharSequence? = null) {
        if ((hint.visibility == View.VISIBLE) == show) return
        hint.animate().cancel()
        val curve = LumenEasing.standard()
        val animated = ValueAnimator.areAnimatorsEnabled()
        if (animated && root != null && root.isLaidOut) {
            TransitionManager.beginDelayedTransition(root, TransitionSet()
                .addTransition(ChangeBounds()).addTransition(Fade().addTarget(hint))
                .setDuration(320L).setInterpolator(curve))
        }
        hint.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            hint.translationY = if (animated) 24 * hint.resources.displayMetrics.density else 0f
            if (animated) hint.animate().translationY(0f).setDuration(320L).setInterpolator(curve).start()
            if (announcement != null) hint.announceForAccessibility(announcement)
        } else {
            hint.translationY = 0f
        }
    }
}
