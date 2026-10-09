package com.lumen.coacervation.engine.motion

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewPropertyAnimator

/** Android animator entry points for host-owned business callbacks and existing timing profiles. */
public object LumenAnimator {
    @JvmStatic public fun ofFloat(vararg values: Float): ValueAnimator = ValueAnimator.ofFloat(*values)
    @JvmStatic public fun ofInt(vararg values: Int): ValueAnimator = ValueAnimator.ofInt(*values)
    @JvmStatic public fun property(view: View): ViewPropertyAnimator = view.animate()
}
