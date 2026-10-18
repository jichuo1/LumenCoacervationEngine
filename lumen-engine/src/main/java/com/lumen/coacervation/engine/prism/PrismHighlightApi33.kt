package com.lumen.coacervation.engine.prism

import android.graphics.Paint
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/** 高光的角度分布（API 33）：shader 建一次，几何与颜色变化时只改 uniform。 */
@RequiresApi(33)
internal class PrismHighlightApi33 {
    private val shader = RuntimeShader(PrismShaders.HIGHLIGHT)

    fun install(paint: Paint) { paint.shader = shader }

    fun configure(left: Float, top: Float, width: Float, height: Float, radius: Float,
        color: Int, lightX: Float, lightY: Float, falloff: Float) {
        shader.setFloatUniform("origin", left, top)
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("radius", radius)
        shader.setColorUniform("color", color)
        shader.setFloatUniform("light", lightX, lightY)
        shader.setFloatUniform("falloff", falloff)
    }
}
