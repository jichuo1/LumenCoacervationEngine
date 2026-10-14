package com.lumen.coacervation.engine.motion

import android.animation.ValueAnimator
import androidx.annotation.RequiresApi

@RequiresApi(33)
internal object AnimatorDurationScaleApi33 {
    fun current(): Float = ValueAnimator.getDurationScale()
}
