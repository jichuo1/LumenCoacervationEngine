package com.lumen.coacervation.engine.interaction

import android.view.View

/**
 * 由祖先容器实现：声明当前这段手势已经归它，不属于手指下的内容。
 *
 * [ElasticInteractionController] 挂在 Activity 的 `dispatchTouchEvent` 上，按下时按命中测试自己选中
 * 控件；只要这次按下被处理了，就会给控件点亮按压高光。回弹视口接住正在回弹的页面时，按下事件
 * 不下发给内容（2026-09-23），此时控件上不该出现按压高光。
 */
public interface ElasticGestureClaim {
    /** 本次按下是否已被本容器整段接管；只在按下分发完成后被查询。 */
    val claimsCurrentGesture: Boolean

    companion object {
        /** [view] 的任一祖先是否声明接管了当前手势。 */
        fun claimedAbove(view: View): Boolean {
            var parent = view.parent
            var depth = 0
            while (parent is View && depth++ < 128) {
                if (parent is ElasticGestureClaim && parent.claimsCurrentGesture) return true
                parent = parent.parent
            }
            return false
        }
    }
}
