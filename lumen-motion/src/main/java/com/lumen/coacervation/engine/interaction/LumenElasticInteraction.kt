package com.lumen.coacervation.engine.interaction

import android.app.Activity
import android.app.Dialog
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.annotation.MainThread
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.model.LumenEffectTuning

/**
 * 一个 Activity（及其弹窗窗口）的全局长按弹性交互：长按控件后拖动，控件跟手形变位移、触点高光流动，
 * 松手弹簧回弹（适配标准 §12）。
 *
 * 来源工程里这部分写在 Activity 基类里；抽离后由宿主持有一个实例并在三处接线：
 * - `dispatchTouchEvent`：先 `lumen.onDispatchTouchEvent(event)`，再 `return elastic.dispatch(event) { super.dispatchTouchEvent(it) }`；
 * - `onPause` / `onStop`：[clear]（只收回视觉，原有点击/选中监听保持权威）；
 * - `onDestroy`：[dispose]（在委托 `onDestroy()` 之前）。
 *
 * 弹窗窗口由 [installDialog] 接入（`LumenModalPresenter` 会自动调用）。
 *
 * 触点光晕的亮度与半径取自 [effectTuning]，**每次长按开始时**读取一次：默认跟随委托的
 * [LumenActivityDelegate.effectTuning]（Activity 内固定）；需要设置页滑块即时预览时，传入读取宿主当前值的 lambda。
 */
@MainThread
public class LumenElasticInteraction(
    private val activity: Activity,
    private val lumen: LumenActivityDelegate,
    /** 判定某个 View 不参与弹性（默认看 [ElasticInteractionController.EXCLUDED_TAG]）。 */
    private val isExcluded: (View) -> Boolean = { it.tag == ElasticInteractionController.EXCLUDED_TAG },
    /** 长按拖动光晕的调参来源，见类说明。 */
    private val effectTuning: () -> LumenEffectTuning = { lumen.effectTuning }
) {
    private data class DialogInteraction(val controller: ElasticInteractionController, val release: () -> Unit)

    private var windowController: ElasticInteractionController? = null
    private val dialogInteractions = linkedMapOf<Window, DialogInteraction>()
    private var disposed = false

    /**
     * 包住 Activity 原来的分发入口。原分发只能经 [superDispatch] 传入一次；不要再把控制器装成 OnTouchListener。
     * 普通按下/移动/抬起保持原事件流；判定为"按住后拖动"时向原分发发一次 CANCEL，再消费剩余事件。
     */
    public fun dispatch(event: MotionEvent, superDispatch: (MotionEvent) -> Boolean): Boolean {
        if (disposed) return superDispatch(event)
        val controller = windowController ?: ElasticInteractionController(
            root = activity.window.decorView,
            notifyPositionChanged = { lumen.notifyPositionChanged() },
            isExcluded = isExcluded,
            highlightColor = lumen.palette.primary,
            effectTuning = effectTuning
        ).also { windowController = it }
        return controller.dispatch(event, superDispatch)
    }

    /** 立即收回所有窗口上的形变与高光；原有点击/选中监听不受影响。 */
    public fun clear() {
        windowController?.clear()
        dialogInteractions.values.forEach { it.controller.clear() }
    }

    /**
     * 给弹窗窗口装上同一套弹性交互（在 `setContentView` 之后调用）。弹窗窗口移除时自动释放；
     * 返回的函数可以提前释放，重复调用安全。
     */
    public fun installDialog(dialog: Dialog): () -> Unit {
        if (disposed) return {}
        val window = dialog.window ?: return {}
        dialogInteractions[window]?.let { return it.release }
        val original = window.callback ?: return {}
        val controller = ElasticInteractionController(window.decorView,
            notifyPositionChanged = { lumen.notifyPositionChanged() },
            isExcluded = isExcluded,
            highlightColor = lumen.palette.primary,
            effectTuning = effectTuning)
        val callback = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean =
                controller.dispatch(event) { forwarded -> original.dispatchTouchEvent(forwarded) }
        }
        var released = false
        var detachListener: View.OnAttachStateChangeListener? = null
        val release = {
            if (!released) {
                released = true
                detachListener?.let { window.decorView.removeOnAttachStateChangeListener(it) }
                controller.dispose()
                if (window.callback === callback) window.callback = original
                dialogInteractions.remove(window)
            }
            Unit
        }
        // DecorView detaches when the dialog window is dismissed; release without
        // waiting for a caller-side dismiss listener so bulk installs stay leak-free.
        detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { release() }
        }
        window.decorView.addOnAttachStateChangeListener(detachListener)
        dialogInteractions[window] = DialogInteraction(controller, release)
        window.callback = callback
        return release
    }

    /** 释放全部控制器与弹窗回调包装。在委托 `onDestroy()` 之前调用。幂等。 */
    public fun dispose() {
        if (disposed) return
        disposed = true
        windowController?.dispose()
        windowController = null
        dialogInteractions.values.toList().forEach { it.release() }
    }
}
