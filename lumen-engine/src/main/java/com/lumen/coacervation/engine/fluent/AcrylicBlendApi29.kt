package com.lumen.coacervation.engine.fluent

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.RequiresApi

/**
 * Holds the API 29 [BlendMode] type (ENGINEERING_RULES §2). Both modes use W3C non-separable formulas:
 * LUMINOSITY keeps the backdrop's hue and saturation, COLOR keeps the backdrop's luminosity.
 * WinUI swaps the two enum names because of a Composition bug; the visual intent is what is reproduced here.
 */
@RequiresApi(29)
internal class AcrylicBlendApi29 {
    private val luminosity = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.LUMINOSITY }
    private val color = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.COLOR }

    fun drawLuminosity(canvas: Canvas, rect: RectF, argb: Int) {
        luminosity.color = argb
        canvas.drawRect(rect, luminosity)
    }

    fun drawColor(canvas: Canvas, rect: RectF, argb: Int) {
        color.color = argb
        canvas.drawRect(rect, color)
    }
}
