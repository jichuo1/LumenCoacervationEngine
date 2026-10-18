/*
   Portions adapted from Kyant AndroidLiquidGlass (backdrop/src/commonMain/kotlin/com/kyant/backdrop/internal/Shaders.kt)
   at commit 65ab177e90e5c1d8c62e70cf7755841982da65f6.

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

package com.lumen.coacervation.engine.prism

/**
 * 棱镜玻璃的 AGSL 源码（纯字符串，不含平台类型；编译在 `*Api33` 隔离类里）。
 *
 * 与上游的差异：
 * - 只用一个圆角半径，并在 shader 内钳到短边一半（上游按未居中的坐标选角，混合圆角会选错角）；
 * - 法线与径向方向在零向量处不做 normalize（上游在中心线与拐点处会产生 NaN）；
 * - 色散强度为 0–1 的连续量（上游只有开关），合成结果钳到合法的预乘颜色；
 * - 折射量钳到短边一半，取样不越出录制节点。
 */
internal object PrismShaders {
    private const val SDF = """
float2 prismSign(float2 p) {
    return float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
}

float prismSd(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    return length(max(q, 0.0)) - radius + min(max(q.x, q.y), 0.0);
}

float2 prismGrad(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - (halfSize - float2(radius));
    float2 m = max(q, 0.0);
    float l = length(m);
    if (l > 0.0001) return prismSign(p) * (m / l);
    float gx = step(q.y, q.x);
    return prismSign(p) * float2(gx, 1.0 - gx);
}
"""

    /** 作为 RenderEffect 作用在采样节点上：`content` 是节点内容（已模糊、已调饱和度）。 */
    const val LENS = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float radius;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float dispersion;
$SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 c = coord + offset - halfSize;
    float r = min(radius, min(halfSize.x, halfSize.y));
    float sd = prismSd(c, halfSize, r);
    if (refractionHeight <= 0.0 || -sd >= refractionHeight) return content.eval(coord);
    sd = min(sd, 0.0);
    float x = 1.0 - (-sd) / refractionHeight;
    // Inward pull, at most half the short side: samples stay inside the recorded node.
    float amount = max(refractionAmount, -min(halfSize.x, halfSize.y));
    float d = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * amount;
    float gradRadius = min(r * 1.5, min(halfSize.x, halfSize.y));
    float lc = length(c);
    float2 g = prismGrad(c, halfSize, gradRadius) + depthEffect * (lc > 0.0001 ? c / lc : float2(0.0));
    float lg = length(g);
    float2 grad = lg > 0.0001 ? g / lg : float2(0.0);
    float2 refracted = coord + d * grad;
    if (dispersion <= 0.0) return content.eval(refracted);
    float2 o = d * grad * (dispersion * (c.x * c.y) / max(halfSize.x * halfSize.y, 0.0001));
    half4 color = half4(0.0);
    half4 red = content.eval(refracted + o);
    color.r += red.r / 3.5; color.a += red.a / 7.0;
    half4 orange = content.eval(refracted + o * (2.0 / 3.0));
    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;
    half4 yellow = content.eval(refracted + o * (1.0 / 3.0));
    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;
    half4 green = content.eval(refracted);
    color.g += green.g / 3.5; color.a += green.a / 7.0;
    half4 cyan = content.eval(refracted - o * (1.0 / 3.0));
    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;
    half4 blue = content.eval(refracted - o * (2.0 / 3.0));
    color.b += blue.b / 3.0; color.a += blue.a / 7.0;
    half4 purple = content.eval(refracted - o);
    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;
    // Channels come from different taps; keep the result a valid premultiplied colour.
    color.rgb = min(color.rgb, half3(color.a));
    return color;
}
"""

    /** 作为 Paint shader 画高光描边：坐标是 Drawable 所在画布的局部坐标。 */
    const val HIGHLIGHT = """
uniform float2 origin;
uniform float2 size;
uniform float radius;
layout(color) uniform half4 color;
uniform float2 light;
uniform float falloff;
$SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 c = coord - origin - halfSize;
    float r = min(radius, min(halfSize.x, halfSize.y));
    float gradRadius = min(r * 1.5, min(halfSize.x, halfSize.y));
    float2 g = prismGrad(c, halfSize, gradRadius);
    float intensity = pow(abs(dot(g, light)), falloff);
    return color * intensity;
}
"""
}
