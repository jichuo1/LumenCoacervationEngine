/*
   The rounded-rect refraction and dispersion math is adapted from Kyant AndroidLiquidGlass
   (backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt) at commit
   65ab177e90e5c1d8c62e70cf7755841982da65f6.

   Copyright 2025 Kyant

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.lumen.coacervation.engine.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi

/**
 * 选中框内的选中色内容（API 33）：对 ALPHA_8 遮罩按选中框做放大、圆角矩形边缘折射与可选色散，
 * 输出 `选中色 × 覆盖度`。shader 与遮罩 BitmapShader 只在遮罩重建时创建，逐帧只改 uniform。
 */
@RequiresApi(33)
internal class PrismSelectionShaderApi33 {
    private val shader = RuntimeShader(SOURCE)
    private var maskShader: BitmapShader? = null
    private var appliedColor = 0
    private var colorApplied = false

    fun setMask(mask: Bitmap, barToMask: Matrix) {
        val next = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        // The child shader is evaluated in bar coordinates; its local matrix maps mask pixels back to the bar.
        next.setLocalMatrix(barToMask)
        maskShader = next
        shader.setInputShader("mask", next)
    }

    fun install(paint: Paint) { paint.shader = shader }

    fun configure(centerX: Float, centerY: Float, halfWidth: Float, halfHeight: Float, radius: Float,
        lensHeight: Float, lensAmount: Float, dispersion: Float, magnification: Float, color: Int) {
        shader.setFloatUniform("center", centerX, centerY)
        shader.setFloatUniform("halfSize", halfWidth, halfHeight)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("refractionHeight", lensHeight)
        shader.setFloatUniform("refractionAmount", -lensAmount)
        shader.setFloatUniform("dispersion", dispersion)
        shader.setFloatUniform("magnification", magnification)
        // setColorUniform converts through Color/float[]: only on an actual colour change, never per frame.
        if (color != appliedColor || !colorApplied) {
            appliedColor = color
            colorApplied = true
            shader.setColorUniform("tint", color)
        }
    }

    private companion object {
        const val SOURCE = """
uniform shader mask;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float dispersion;
uniform float magnification;
layout(color) uniform half4 tint;

float2 prismSign(float2 p) {
    return float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
}

float prismSd(float2 p, float2 h, float r) {
    float2 q = abs(p) - (h - float2(r));
    return length(max(q, 0.0)) - r + min(max(q.x, q.y), 0.0);
}

float2 prismGrad(float2 p, float2 h, float r) {
    float2 q = abs(p) - (h - float2(r));
    float2 m = max(q, 0.0);
    float l = length(m);
    if (l > 0.0001) return prismSign(p) * (m / l);
    float gx = step(q.y, q.x);
    return prismSign(p) * float2(gx, 1.0 - gx);
}

half4 main(float2 coord) {
    // Colour uniforms arrive unpremultiplied; outputs must be premultiplied.
    half4 solid = half4(tint.rgb * tint.a, tint.a);
    float2 c = coord - center;
    float r = min(radius, min(halfSize.x, halfSize.y));
    float2 base = center + c / magnification;
    float sd = prismSd(c, halfSize, r);
    if (refractionHeight <= 0.0 || -sd >= refractionHeight) {
        return solid * mask.eval(base).a;
    }
    sd = min(sd, 0.0);
    float x = 1.0 - (-sd) / refractionHeight;
    float d = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * max(refractionAmount, -min(halfSize.x, halfSize.y));
    float gradRadius = min(r * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = prismGrad(c, halfSize, gradRadius);
    float2 refracted = base + d * grad;
    if (dispersion <= 0.0) {
        return solid * mask.eval(refracted).a;
    }
    float2 o = d * grad * (dispersion * (c.x * c.y) / max(halfSize.x * halfSize.y, 0.0001));
    float a0 = mask.eval(refracted + o).a;
    float a1 = mask.eval(refracted + o * (2.0 / 3.0)).a;
    float a2 = mask.eval(refracted + o * (1.0 / 3.0)).a;
    float a3 = mask.eval(refracted).a;
    float a4 = mask.eval(refracted - o * (1.0 / 3.0)).a;
    float a5 = mask.eval(refracted - o * (2.0 / 3.0)).a;
    float a6 = mask.eval(refracted - o).a;
    float red = (a0 + a1 + a2) / 3.5 + a6 / 7.0;
    float green = a1 / 7.0 + (a2 + a3 + a4) / 3.5;
    float blue = (a4 + a5 + a6) / 3.0;
    float alpha = (a0 + a1 + a2 + a3 + a4 + a5 + a6) / 7.0;
    // Premultiplied output: the tint's alpha scales colour and coverage alike; channels from different taps
    // are clamped to the coverage so the colour stays valid.
    half3 rgb = min(half3(tint.r * red, tint.g * green, tint.b * blue), half3(alpha));
    return half4(rgb, alpha) * tint.a;
}
"""
    }
}
