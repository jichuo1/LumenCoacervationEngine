package com.lumen.coacervation.engine.widget

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.widget.NestedScrollView

/** Natural content height capped at [maxHeightPx], with typed nested fling dispatch for engine stretch. */
@SuppressLint("ViewConstructor")
public class LumenMaxHeightScrollView(context: Context, private val maxHeightPx: Int) : NestedScrollView(context) {
    init { isFillViewport = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeightPx <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
        }
    }
}
