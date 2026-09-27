package com.lumen.coacervation.engine.glow

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.annotation.RequiresApi

/** 只用于两条实时胶囊；效果链构造后复用，不做历史帧混合或 CPU 回读。 */
@RequiresApi(31)
internal object GlowChromeBlurApi31 {
    fun create(radiusPx: Float): RenderEffect {
        val preRadius = GlowChromeBlurPolicy.prefilterRadius(radiusPx)
        if (preRadius == 0f) return RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
        val prefilter = RenderEffect.createBlurEffect(preRadius, preRadius, Shader.TileMode.CLAMP)
        val mainRadius = GlowChromeBlurPolicy.mainRadius(radiusPx)
        return RenderEffect.createBlurEffect(mainRadius, mainRadius, prefilter, Shader.TileMode.CLAMP)
    }
}
