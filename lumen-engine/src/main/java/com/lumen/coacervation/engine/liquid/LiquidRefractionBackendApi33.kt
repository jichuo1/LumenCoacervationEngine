/*
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

/*
 * Shader stability and linear-sRGB saturation portions are adapted from
 * AndroidLiquidGlassView v1.0.5, Copyright (c) 2025 QmDeve / Donny Yale,
 * licensed under the MIT License. See THIRD_PARTY_NOTICES.md and
 * third_party/AndroidLiquidGlassView-LICENSE.txt.
 */

package com.lumen.coacervation.engine.liquid

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import com.lumen.coacervation.engine.model.LiquidParameters
import com.lumen.coacervation.engine.model.LiquidRenderBackend
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * View renderer adaptation of AndroidLiquidGlass `Shaders.kt` at commit `65ab177`, with the
 * safe rounded-rectangle gradient and color treatment from AndroidLiquidGlassView v1.0.5.
 *
 * Modifications: extracted the rounded-rectangle refraction program, removed Compose/Skia wrappers,
 * uses one Android RuntimeShader per Activity backend, binds the module-owned stable or real-time
 * backdrop, and supplies equal corner radii from the View Drawable contract.
 */
@RequiresApi(33)
internal class LiquidRefractionBackendApi33(
    private val parameters: LiquidParameters,
    private val density: Float
) : LiquidBackendDriver {
    override val backend = LiquidRenderBackend.REFRACTION
    override val requiresBackdrop = true

    private val shader = RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        this.shader = this@LiquidRefractionBackendApi33.shader
    }
    private var source: LiquidBackdropSource? = null
    private var appliedScatterTapMode = FULL_SCATTER_TAPS
    private val samplingValues = FloatArray(9)

    init {
        shader.applyLiquidOpticalUniforms(parameters, density)
    }

    override fun bindBackdrop(source: LiquidBackdropSource) {
        check(!source.isClosed) { "Cannot bind a closed Liquid backdrop" }
        this.source = source
        // RuntimeShader 子输入不会继承外层 Paint.FILTER_BITMAP_FLAG；DEFAULT 在此会退化为最近邻。
        source.bitmapShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        shader.setInputShader("content", source.bitmapShader)
        // content.eval 使用位图像素坐标；映射跟随已绑定的折射位图，而非显示底图尺寸。
        shader.setFloatUniform(
            "backdropScale",
            source.refractionWidth.toFloat() / source.fullWidth.toFloat(),
            source.refractionHeight.toFloat() / source.fullHeight.toFloat()
        )
        // 根空间下 backdrop 的有效范围；shader 用它把折射位移收在页面之内。
        shader.setFloatUniform(
            "backdropExtent",
            source.fullWidth.toFloat(),
            source.fullHeight.toFloat()
        )
    }

    override fun drawBackdrop(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        viewX: Int,
        viewY: Int,
        opticalIntensity: Float,
        stretchDirY: Float,
        contentAlpha: Float,
        motionLite: Boolean,
        localToBackdrop: Matrix?,
        stretchSampling: FloatArray?
    ) {
        checkNotNull(source) { "Liquid refraction backdrop is not bound" }
        shader.setFloatUniform("size", bounds.width().toFloat(), bounds.height().toFloat())
        shader.setFloatUniform("offset", -bounds.left.toFloat(), -bounds.top.toFloat())
        // offset 只把形状归零；纹理仍按承载 View 的完整局部坐标取样。
        if (localToBackdrop != null) localToBackdrop.getValues(samplingValues)
        val mapped = localToBackdrop != null && !LiquidRefreshVisibilityPolicy.isTranslationOnly(samplingValues)
        // 常见的静止、滚动和面板平移只需相加，仍保留小数位；避免逐像素做矩阵乘法和除法。
        val originX = if (localToBackdrop != null && !mapped) samplingValues[2] else viewX.toFloat()
        val originY = if (localToBackdrop != null && !mapped) samplingValues[5] else viewY.toFloat()
        shader.setFloatUniform("backdropOrigin", originX + bounds.left, originY + bounds.top)
        shader.setFloatUniform("backdropMapped", if (mapped) 1f else 0f)
        if (mapped) {
            shader.setFloatUniform("backdropMapX", samplingValues[0], samplingValues[1], samplingValues[2])
            shader.setFloatUniform("backdropMapY", samplingValues[3], samplingValues[4], samplingValues[5])
            shader.setFloatUniform("backdropMapW", samplingValues[6], samplingValues[7], samplingValues[8])
        }
        shader.setFloatUniform("stretchSampling", stretchSampling?.get(0) ?: 0f,
            stretchSampling?.get(1) ?: 0f, stretchSampling?.get(2) ?: 0f)
        shader.setFloatUniform("cornerRadii", radiusPx, radiusPx, radiusPx, radiusPx)
        shader.setFloatUniform("opticalIntensity", opticalIntensity.coerceIn(1f, 1.85f))
        shader.setFloatUniform("stretchDirY", stretchDirY.coerceIn(-1f, 1f))
        shader.setFloatUniform("motionLite", if (motionLite) 1f else 0f)
        // paint.alpha 与 shader 输出 alpha 相乘：浮动表面借此透出真实下层内容，
        // 不需要 shader 侧再开一个 uniform。其余表面恒为 255，与旧版逐像素一致。
        paint.alpha = (contentAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        // 全屏模态按面积降到 2 抽样散射；卡片级表面保持 4 抽样，权重两侧都守恒。
        val reducedTaps = LiquidRealtimeCapturePolicy.useReducedScatterTaps(
            bounds.width(),
            bounds.height()
        )
        val tapMode = if (reducedTaps) REDUCED_SCATTER_TAPS else FULL_SCATTER_TAPS
        if (appliedScatterTapMode != tapMode) {
            shader.setFloatUniform("scatterTapMode", tapMode)
            appliedScatterTapMode = tapMode
        }
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            radiusPx,
            radiusPx,
            paint
        )
    }

    override fun close() {
        source = null
        paint.shader = null
    }

    private companion object {
        const val REDUCED_SCATTER_TAPS = 0f
    }
}

private const val FULL_SCATTER_TAPS = 1f

/**
 * 与表面几何无关的光学 uniform：折射、散射、色散、边缘光与抖动。窗口玻璃（本后端）与悬浮栏
 * 内容节点玻璃（`LiquidChromeBackdropApi31`）共用同一份，两条路径的 rim 因此逐项一致。
 */
@RequiresApi(33)
internal fun RuntimeShader.applyLiquidOpticalUniforms(parameters: LiquidParameters, density: Float) {
    setFloatUniform("refractionHeight", (parameters.refractionHeightDp * density).coerceAtLeast(0.1f))
    setFloatUniform("refractionAmount", parameters.refractionAmountDp * density)
    setFloatUniform("depthEffect", parameters.depthEffect)
    setFloatUniform("interiorDistortion", parameters.interiorDistortionDp * density)
    setFloatUniform("chromaticShift", parameters.chromaticShiftDp * density)
    setFloatUniform("scatteringRadius", parameters.scatteringRadiusDp * density)
    setFloatUniform("scatteringStrength", parameters.scatteringStrength)
    setFloatUniform("chromaMultiplier", parameters.saturation)
    // 屏幕 y 轴向下；约定 L = (cos θ, sin θ)，外法线朝向光源的边缘被点亮。
    val lightRadians = Math.toRadians(parameters.highlightAngleDegrees.toDouble())
    setFloatUniform("lightDirection", cos(lightRadians).toFloat(), sin(lightRadians).toFloat())
    setFloatUniform("specularStrength", parameters.specularStrength)
    setFloatUniform("fresnelStrength", parameters.fresnelStrength)
    setFloatUniform("causticLuminanceGain", parameters.causticLuminanceGain)
    setFloatUniform("innerShadowStrength", parameters.innerShadowStrength)
    setFloatUniform("scatterTapMode", FULL_SCATTER_TAPS)
    setFloatUniform("dither", parameters.ditherAmplitude)
    setFloatUniform("nodeInput", 0f)
    setFloatUniform("backdropMapped", 0f)
    setFloatUniform("stretchSampling", 0f, 0f, 0f)
}

internal const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;
// RenderEffect 节点子输入只有本次输出裁切范围可用；窗口 BitmapShader 没有此限制。
uniform float nodeInput;

uniform float2 size;
uniform float2 offset;
uniform float2 backdropScale;
uniform float2 backdropOrigin;
uniform float2 backdropExtent;
uniform float backdropMapped;
uniform float3 backdropMapX;
uniform float3 backdropMapY;
uniform float3 backdropMapW;
// Viewport root-space top, height, signed HWUI stretch intensity.
uniform float3 stretchSampling;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float interiorDistortion;
uniform float chromaticShift;
uniform float scatteringRadius;
uniform float scatteringStrength;
uniform float chromaMultiplier;
uniform float opticalIntensity;
uniform float2 lightDirection;
uniform float specularStrength;
uniform float fresnelStrength;
uniform float causticLuminanceGain;
uniform float innerShadowStrength;
uniform float scatterTapMode;
// 超出回弹方向：-1 = 顶部下拉（上边缘发光），+1 = 底部上拉，0 = 无回弹。
uniform float stretchDirY;
// 位移抑制期置 1：跳过多次散射取样，只保留单次取样与边缘光项。
uniform float motionLite;
// 输出抖动幅度（0..1 色域）：平滑底图在 8 位量化下会出色带，±0.5LSB 噪声把台阶打散。
// 标准档为 0，整条分支被 uniform 门掉。
uniform float dither;

const half3 rgbToY = half3(0.2126, 0.7152, 0.0722);

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float safeSign(float value) {
    return value < 0.0 ? -1.0 : 1.0;
}

float2 safeNormalize(float2 value, float2 fallback) {
    float len = length(value);
    if (len > 0.001) return value / len;
    return fallback;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        float2 outside = max(cornerCoord, 0.0);
        float outsideLength = length(outside);
        if (outsideLength > 0.001) {
            return sign(coord) * (outside / outsideLength);
        }
        float useX = step(cornerCoord.y, cornerCoord.x);
        return float2(
            useX * safeSign(coord.x),
            (1.0 - useX) * safeSign(coord.y)
        );
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

half4 saturateColor(half4 color, float amount) {
    half3 linear = toLinearSrgb(color.rgb);
    float y = dot(linear, rgbToY);
    half3 gray = half3(y);
    half3 saturated = fromLinearSrgb(mix(gray, linear, amount));
    return half4(saturated, color.a);
}

float2 backdropPoint(float2 canvasCoord) {
    float2 root = canvasCoord + offset + backdropOrigin;
    if (backdropMapped > 0.5) {
        float3 p = float3(canvasCoord, 1.0);
        float w = dot(backdropMapW, p);
        root = float2(dot(backdropMapX, p), dot(backdropMapY, p)) / w;
    }
    // HWUI warps the foreground after this shader. Look up the eventual output position
    // so that the picture in the moving glass still matches the stationary background.
    if (stretchSampling.y > 0.0 && abs(stretchSampling.z) > 0.0) {
        float q = (root.y - stretchSampling.x) / stretchSampling.y;
        bool bottom = stretchSampling.z < 0.0;
        if (bottom) q = 1.0 - q;
        float s = abs(stretchSampling.z);
        float k = 1.0 + s;
        q = k * k * q / (1.0 + 0.3 * s + 0.7 * s * k * q);
        if (bottom) q = 1.0 - q;
        root.y = stretchSampling.x + q * stretchSampling.y;
    }
    return root;
}

half4 sampleContent(float2 canvasCoord) {
    float2 rootCoord = backdropPoint(canvasCoord);
    if (nodeInput > 0.5) {
        rootCoord = clamp(rootCoord, backdropOrigin + float2(0.5),
            backdropOrigin + size - float2(0.5));
    }
    return content.eval(rootCoord * backdropScale);
}

// 色散限域在 rim 带内（乘 edgeWeight），且位移小于像素级时直接跳过两次取样。
// 整块彩边正是旧实现关闭色散的原因：全域等量位移会让内部高对比文字也裂成红蓝边。
half4 sampleRefracted(float2 canvasCoord, float2 direction, float edgeBoost, float edgeWeight) {
    half4 center = sampleContent(canvasCoord);
    if (chromaticShift <= 0.001) return center;
    float shift = chromaticShift * edgeBoost * edgeWeight;
    if (shift < 0.02) return center;
    float2 axis = safeNormalize(direction, float2(1.0, 0.0));
    half red = sampleContent(canvasCoord + axis * shift).r;
    half blue = sampleContent(canvasCoord - axis * shift).b;
    return half4(red, center.g, blue, center.a);
}

half4 sampleScattered(
    float2 canvasCoord,
    float2 direction,
    float edgeWeight,
    float interiorLens,
    float edgeReach,
    float edgeBoost
) {
    half4 core = sampleRefracted(canvasCoord, direction, edgeBoost, edgeWeight);
    if (scatteringStrength <= 0.001 || scatteringRadius <= 0.001) return core;

    float spatialWeight = clamp(edgeWeight * 0.82 + interiorLens * 0.34, 0.0, 1.0);
    float amount = clamp(scatteringStrength * spatialWeight, 0.0, 0.72);
    // 权重可忽略时 mix 的结果与 core 在 8 位量化下不可分辨，直接省掉 2-4 次纹理取样。
    if (amount < 0.004) return core;

    float2 normal = safeNormalize(direction, float2(0.0, 1.0));
    float2 tangent = float2(-normal.y, normal.x);
    float radius = scatteringRadius * edgeBoost * mix(0.42, 1.0, edgeWeight)
        * edgeReach;
    half4 tangentPositive = sampleContent(canvasCoord + tangent * radius);
    half4 tangentNegative = sampleContent(canvasCoord - tangent * radius);
    // 两条分支的权重都归一化到 1.0，降抽样不会改变整体亮度，只降低扩散的各向同性。
    half4 diffused;
    if (scatterTapMode > 0.5) {
        half4 normalPositive = sampleContent(canvasCoord + normal * radius * 0.58);
        half4 normalNegative = sampleContent(canvasCoord - normal * radius * 0.58);
        diffused = core * 0.46
            + (tangentPositive + tangentNegative) * 0.16
            + (normalPositive + normalNegative) * 0.11;
    } else {
        diffused = core * 0.56 + (tangentPositive + tangentNegative) * 0.22;
    }
    half4 scattered = mix(core, diffused, amount);
    // 内容感知焦散：背后越亮，边缘的白色焦散越强。亮度用 sRGB 近似，避免额外一次线性化。
    float backdropLuma = dot(scattered.rgb, rgbToY);
    float caustic = edgeWeight * scatteringStrength * 0.075 * edgeBoost
        * (1.0 + causticLuminanceGain * backdropLuma);
    scattered.rgb = mix(scattered.rgb, half3(1.0), clamp(caustic, 0.0, 0.2));
    return scattered;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float2 safeHalfSize = max(halfSize, float2(1.0));
    float2 normalizedCoord = centeredCoord / safeHalfSize;
    float radial = clamp(length(normalizedCoord), 0.0, 1.0);
    float interiorLens = max(1.0 - radial * radial, 0.0);
    float2 interiorOffset = normalizedCoord * interiorDistortion * interiorLens
        * opticalIntensity;
    float2 interiorDirection = safeNormalize(centeredCoord, float2(1.0, 0.0));

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    float insideDistance = max(-sd, 0.0);

    // 深内部早退（标准档）。三个条件缺一不可，它们正好是"折射带之外还剩什么"的全部来源：
    // interiorDistortion 驱动 interiorOffset；scatteringStrength 的散射项在 edgeWeight=0
    // 时仍由 interiorLens 供权（**不是** 0）；chromaticShift 在旧实现里无视 edgeWeight。
    // 三者为零时，下面整段的 d / innerShadow / fresnel / specular 逐项含 edgeWeight 因子，
    // 结果恒等于原始采样。edgeWidthBoost 需要 shapeGrad 才能算，这里用它的上界
    // （stretchFacing ≤ 1）做保守判据——只会少退出，不会错退出。
    // ⚠️ 必须保留 saturateColor：chromaMultiplier 是 0.98，直接返回原样本会让内面
    // 少掉那 2% 去饱和，与边缘带接不上（方案原稿写的 `return sampleContent(coord)` 就漏了）。
    float edgeWidthBoostMax = 1.0 + 0.45 * max(opticalIntensity - 1.0, 0.0);
    bool deepInterior = insideDistance >= refractionHeight * edgeWidthBoostMax;
    if (interiorDistortion <= 0.001 && scatteringStrength <= 0.001 && chromaticShift <= 0.001) {
        if (deepInterior) {
            return saturateColor(sampleContent(coord), chromaMultiplier);
        }
    }

    // 运动降级档的深内部同样可以早退：lite 本来就只取一次样，而 edgeWeight==0 让折射位移、
    // 焦散、内阴影、菲涅尔、镜面逐项归零，结果恒等于"按 interiorOffset 取一次样再调饱和"。
    // 省掉的是 SDF 梯度、方向归一化与三段边缘光的 ALU——卡片内面占了玻璃像素的绝大多数，
    // 而运动期正是 GPU 最紧的时候（真机：高级材质手风琴 GPU 50th 7ms / 90th 9ms，
    // `Slow issue draw commands` 占掉帧的 46/47，帧间隔中位 16.6ms＝每两帧丢一帧）。
    if (motionLite > 0.5 && deepInterior) {
        float2 liteRoot = backdropPoint(coord);
        float2 liteMargin = min(liteRoot, backdropExtent - liteRoot);
        float liteNearest = max(min(liteMargin.x, liteMargin.y), 0.0);
        float liteReach = clamp(
            liteNearest / max(refractionAmount * opticalIntensity + scatteringRadius * opticalIntensity, 1.0),
            0.0, 1.0
        );
        return saturateColor(sampleContent(coord + interiorOffset * liteReach), chromaMultiplier);
    }
    float smoothRadius = max(radius * 1.5, min(refractionHeight * 1.6, 48.0));
    float gradRadius = min(smoothRadius, min(halfSize.x, halfSize.y));
    float2 shapeGrad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);

    // 超出回弹的方向性高光：边缘外法线与回弹方向（stretchDirY：顶部下拉 -1、
    // 底部上拉 +1）做点积投影，面向回弹方向的边缘吃满 opticalIntensity 增益，
    // 对侧保持基准 1，侧缘随法线夹角无极过渡——不再是四边等亮的均匀描边。
    // 折射带宽度按同一投影加宽，"厚度"也随回弹力度连续变化。
    // stretchDirY==0（无回弹）时点积恒为 0，edgeBoost 会被钉死在 1——opticalIntensity
    // 的常驻下限（浮动条凝光）静止时完全不生效。按 |dir| 在全向与定向间连续混合：
    // 静止时增益均匀点亮整圈，回弹方向出现后平滑收拢到对应边缘。
    float dirFacing = clamp(
        dot(safeNormalize(shapeGrad, float2(0.0, -1.0)), float2(0.0, stretchDirY)),
        0.0, 1.0
    );
    float stretchFacing = mix(1.0, dirFacing, abs(stretchDirY));
    float edgeBoost = 1.0 + max(opticalIntensity - 1.0, 0.0) * stretchFacing;
    float edgeWidthBoost = 1.0 + 0.45 * max(opticalIntensity - 1.0, 0.0) * stretchFacing;
    float edgePhase = clamp(
        1.0 - insideDistance / max(refractionHeight * edgeWidthBoost, 0.1), 0.0, 1.0
    );
    // Cubic smoothstep has zero derivatives at both ends, preventing a flashing band when
    // the real-time source advances to the next buffer.
    float edgeWeight = edgePhase * edgePhase * (3.0 - 2.0 * edgePhase);
    float d = edgeWeight * refractionAmount * edgeBoost;
    float2 depthGrad = safeNormalize(centeredCoord, shapeGrad);
    float2 grad = safeNormalize(
        shapeGrad + depthEffect * edgeWeight * depthGrad,
        shapeGrad
    );
    float2 direction = safeNormalize(mix(interiorDirection, grad, edgeWeight), grad);

    // 页面最左右（及上下）边缘的收敛。
    //
    // 折射把采样点朝表面外侧推最多 `refractionAmount * opticalIntensity`，散射再加一圈半径。
    // 当玻璃贴着页面边缘时这些采样会越过 backdrop 范围，而 `content` 是 CLAMP 平铺的
    // BitmapShader——越界部分全部取到边缘那一列像素，于是整条带被横向抹开，表现为"背景被拉伸"。
    // 这里按当前像素到 backdrop 边界的可用余量线性收敛位移强度：远离边缘时 `edgeReach` 为 1，
    // 画面中部完全不受影响；贴边时收敛到 0，折射平滑变浅而不是让平铺模式去补像素。
    // 代价是每像素约 9 条 ALU，没有额外纹理读取、没有新的 pass。
    float2 rootCoord = backdropPoint(coord);
    float2 edgeMargin = min(rootCoord, backdropExtent - rootCoord);
    float nearestMargin = max(min(edgeMargin.x, edgeMargin.y), 0.0);
    float sampleReach = max(
        refractionAmount * opticalIntensity + scatteringRadius * opticalIntensity,
        1.0
    );
    if (nodeInput > 0.5) {
        float2 nodeMargin = min(rootCoord - backdropOrigin - float2(0.5),
            backdropOrigin + size - float2(0.5) - rootCoord);
        nearestMargin = max(min(nodeMargin.x, nodeMargin.y), 0.0);
        sampleReach = max(2.0 * (refractionAmount + scatteringRadius +
            interiorDistortion + chromaticShift) * opticalIntensity, 1.0);
    }
    // 线性收敛保证位移不超过余量；smoothstep 在 t>0.5 时会大于 t，反而重新越界。
    float edgeReach = clamp(nearestMargin / sampleReach, 0.0, 1.0);

    float2 refractedCoord = coord + (interiorOffset + d * grad) * edgeReach;
    // 位移抑制期驱动层已把 content 换成平滑稳定底图，散射多抽样没有收益——
    // 只保留单次取样；边缘光项（innerShadow/Fresnel/镜面/焦散）照常计算，
    // 表面在运动中与静止态保持同一条 rim 光晕，不再"消失再加载"。
    half4 color;
    if (motionLite > 0.5) {
        half4 lite = sampleContent(refractedCoord);
        // 与 sampleScattered 同一条内容感知焦散：lite 省掉的是散射多抽样，
        // 不是这道边缘亮度——缺了它，抑制/解除切换瞬间边缘高光会明暗一档。
        float liteLuma = dot(lite.rgb, rgbToY);
        float liteCaustic = edgeWeight * scatteringStrength * 0.075 * edgeBoost
            * (1.0 + causticLuminanceGain * liteLuma);
        lite.rgb = mix(lite.rgb, half3(1.0), clamp(liteCaustic, 0.0, 0.2));
        color = saturateColor(lite, chromaMultiplier);
    } else {
        color = saturateColor(
            sampleScattered(refractedCoord, direction, edgeWeight, interiorLens, edgeReach, edgeBoost),
            chromaMultiplier
        );
    }

    // 折射带内侧的环境遮蔽：在带的中段最深、两端归零，制造"玻璃有厚度"的立体感。
    if (innerShadowStrength > 0.001) {
        float band = clamp(edgeWeight * (1.0 - edgeWeight) * 4.0, 0.0, 1.0);
        color.rgb *= half3(1.0 - innerShadowStrength * band);
    }

    // 掠射角增亮（Fresnel）：反射率随入射角上升，边缘因此比正面更亮。
    // 乘 edgeReach：贴着屏幕边缘时折射位移已收敛为 0，此处再保留全强度亮带就成了一条与玻璃
    // 无关的粗白边（折射带宽 refractionHeight 达 34dp）。让它随折射一起收敛，屏幕最外侧的
    // 高光因此变窄，画面中部的玻璃边缘逐像素不变。
    if (fresnelStrength > 0.001) {
        float fresnel = edgeWeight * edgeWeight * edgeWeight
            * fresnelStrength * edgeBoost * edgeReach;
        color.rgb = mix(color.rgb, half3(1.0), clamp(fresnel, 0.0, 0.5));
    }

    // 定向镜面高光：三次曲线比可变指数更柔和且只需乘法；mix 避免加白后提前截断。
    // 双叶：主叶朝光源，对侧补一支 0.3 倍的弱叶——真实玻璃厚边在背光侧也有一道
    // 掠射反光，单叶会让另外两条边完全无光、读作"贴图"而不是"有厚度的玻璃"。
    // 纯 ALU，不增加纹理取样，且同样被 edgeWeight 限域在 rim 带内。
    if (specularStrength > 0.001) {
        float facing = clamp(dot(grad, lightDirection), 0.0, 1.0);
        float facingBack = clamp(-dot(grad, lightDirection), 0.0, 1.0);
        float specularLobe = facing * facing * facing
            + 0.3 * facingBack * facingBack * facingBack;
        // edgeWeight²：贴边处与原来一样亮，向内收得更快、尾部更柔——高光读作一条细光边，
        // 而不是铺满整条 rim 带的亮带（2026-09-24 用户要求"边缘高光薄一点，过渡更自然"）。
        float specular = specularLobe * edgeWeight * edgeWeight
            * specularStrength * edgeBoost * edgeReach;
        color.rgb = mix(color.rgb, half3(1.0), clamp(specular, 0.0, 0.35));
    }

    // 输出抖动：平滑底图（稳定光学副本、抑制期的 lite 路径）在 8 位量化下会出现色带。
    // ±0.5LSB 的白噪声把台阶打散，视觉上是纹理而不是环。乘 color.a 保持预乘一致性。
    // 哈希刻意不用 `sin`：那是每个玻璃像素一次超越函数，而抖动只需要空间上不相关的
    // 低频噪声。乘加取小数部分同样满足，且是纯 MAD。
    if (dither > 0.0) {
        float noise = fract(dot(coord, float2(0.0731429, 0.0517288)) * 137.0);
        color.rgb += half3((noise - 0.5) * dither * color.a);
    }
    return color;
}
"""
