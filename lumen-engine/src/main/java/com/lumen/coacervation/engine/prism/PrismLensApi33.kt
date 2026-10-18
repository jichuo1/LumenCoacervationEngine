package com.lumen.coacervation.engine.prism

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/**
 * 棱镜折射的 RenderEffect（API 33）。shader 只编译一次，效果在几何或参数变化时重建
 * （由 `GlowChromeGlassApi31` 的量化比对控制，不会逐帧新建）。
 */
@RequiresApi(33)
internal object PrismLensApi33 {
    /** 返回不透明句柄：调用方不出现 `RuntimeShader` 类型。 */
    fun create(): Any = RuntimeShader(PrismShaders.LENS)

    fun effect(lens: Any, input: RenderEffect, width: Int, height: Int, padding: Int, radiusPx: Float,
        recipe: PrismRecipe, density: Float): RenderEffect {
        val shader = lens as RuntimeShader
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("offset", -padding.toFloat(), -padding.toFloat())
        shader.setFloatUniform("radius", radiusPx)
        shader.setFloatUniform("refractionHeight", recipe.refractionHeightDp * density)
        // 负值：向内取样，与上游一致。
        shader.setFloatUniform("refractionAmount", -recipe.refractionAmountDp * density)
        shader.setFloatUniform("depthEffect", recipe.depthEffect)
        shader.setFloatUniform("dispersion", recipe.dispersion)
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), input)
    }
}
