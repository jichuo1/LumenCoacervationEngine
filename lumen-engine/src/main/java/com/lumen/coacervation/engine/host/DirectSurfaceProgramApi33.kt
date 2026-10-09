package com.lumen.coacervation.engine.host

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import com.lumen.coacervation.engine.geometry.LumenSurfaceField
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.material.LensRefractionPolicy
import com.lumen.coacervation.engine.runtime.LumenGraphicsCounters
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Direct paint uniforms support continuous geometry/light without a new RenderEffect snapshot. */
@RequiresApi(33)
internal class DirectSurfaceProgramApi33(private val counters: LumenGraphicsCounters) : AutoCloseable {
    private var shader: RuntimeShader? = RuntimeShader(PROGRAM).also {counters.shaderCompiled()}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val radiiA=FloatArray(4);private val radiiB=FloatArray(4)
    private val ripple=FloatArray(4)

    fun draw(canvas: Canvas,bounds: RectF,c: LumenSurfaceOptions,e: LumenSurfaceEnhancements,
        state: LumenSurfaceRenderState,palette: LumenPalette,density: Float,
        clear: Shader,weak: Shader,strong: Shader,scale: Float,margin: Float,inputWidth: Float,inputHeight: Float,
        alpha: Float,detail: LumenDetailMode): Boolean {
        val s=shader?:return false
        if(!canvas.isHardwareAccelerated)return false
        val width=bounds.width();val height=bounds.height()
        val a=state.shapeA;val b=state.shapeB
        val ax=if(state.customShape)a[0]else 0f;val ay=if(state.customShape)a[1]else 0f
        val aw=if(state.customShape)a[2]else width;val ah=if(state.customShape)a[3]else height
        val geometry=e.geometry
        val corners=geometry.corners
        val mirror=geometry.mirrorCornersInRtl&&state.rtl
        val tl=if(geometry.cornersEnabled)if(mirror)corners.topRight else corners.topLeft else c.radiusDp
        val tr=if(geometry.cornersEnabled)if(mirror)corners.topLeft else corners.topRight else c.radiusDp
        val br=if(geometry.cornersEnabled)if(mirror)corners.bottomLeft else corners.bottomRight else c.radiusDp
        val bl=if(geometry.cornersEnabled)if(mirror)corners.bottomRight else corners.bottomLeft else c.radiusDp
        LumenSurfaceField.normaliseCorners(aw,ah,tl*density,tr*density,br*density,bl*density,radiiA)
        LumenSurfaceField.normaliseCorners(b[2],b[3],tl*density,tr*density,br*density,bl*density,radiiB)
        s.setInputShader("clearInput",clear);s.setInputShader("weakInput",weak);s.setInputShader("strongInput",strong)
        s.setFloatUniform("viewport",width,height,-bounds.left,-bounds.top)
        s.setFloatUniform("inputInfo",scale,margin,inputWidth,inputHeight)
        s.setFloatUniform("shapeA",ax,ay,aw,ah);s.setFloatUniform("shapeB",b[0],b[1],b[2],b[3])
        s.setFloatUniform("cornersA",radiiA);s.setFloatUniform("cornersB",radiiB)
        val canFuse=geometry.fusionEnabled&&state.secondShape&&!e.material.reduceMotion
        s.setFloatUniform("geometry",if(canFuse)geometry.fusionRadiusDp*density else 0f,if(canFuse)1f else 0f,
            geometry.antiAliasWidthDp*density,if(e.debug.samplingRange==LumenSamplingRangeMode.PADDED_EXPERIMENTAL)1f else 0f)
        val progressive=e.progressiveBlur
        s.setFloatUniform("progressive",if(progressive.enabled)progressive.strength else 0f,
            if(progressive.direction==LumenSurfaceFadeDirection.BOTTOM_TO_TOP)1f else 0f,
            progressive.weakStart,progressive.weakEnd)
        s.setFloatUniform("progressiveEnd",progressive.strongStart,progressive.strongEnd,
            if(progressive.enabled)1f else 0f,if(c.sampling.blurEnabled)1f else 0f)
        val short=minOf(aw,ah).coerceAtLeast(1f)
        val ref=if(c.sampling.refractionEnabled)c.sampling.refractionStrength*8f*density else 0f
        val refLimit=if(e.material.normalizeBySize)short*e.material.maxRefractionFraction else 48f*density
        val press=e.press;val moving=!e.material.reduceMotion
        s.setFloatUniform("touch",state.touchX,state.touchY,if(press.enabled&&moving)state.pressure else 0f,press.radiusFraction*short)
        s.setFloatUniform("optics",minOf(ref,refLimit),press.displacementDp*density,
            if(detail==LumenDetailMode.HIGH)minOf(e.material.chromaticStrength*density,if(e.material.normalizeBySize)short*.05f else 2f*density)else 0f,e.material.saturation)
        val warpBudget=(if(c.sampling.refractionEnabled)8f*c.sampling.refractionStrength else 0f)+
            (if(moving&&press.enabled)8f else 0f)+(if(moving&&press.rippleEnabled)press.rippleAmplitudeDp*press.maxRipples else 0f)
        s.setFloatUniform("warpLimit",warpBudget*density)
        s.setFloatUniform("pressLight",if(press.enabled)press.highlightStrength else 0f)
        val light=e.light
        val radians=light.angleDegrees*Math.PI/180.0
        val horizontal=sqrt(1f-light.altitude*light.altitude)
        s.setFloatUniform("light",if(state.lightOverride)state.lightX else cos(radians).toFloat()*horizontal,
            if(state.lightOverride)state.lightY else sin(radians).toFloat()*horizontal,
            if(state.lightOverride)state.lightZ else light.altitude,if(light.enabled)light.intensity else 0f)
        s.setFloatUniform("normalMap",state.normalTransform)
        val lightEdge=if(e.material.normalizeBySize)minOf(light.edgeWidthDp*density,short*e.material.maxEdgeFraction)else light.edgeWidthDp*density
        s.setFloatUniform("lightStyle",if(detail==LumenDetailMode.LOW)0f else light.specularStrength,light.specularPower,lightEdge,
            if(light.transformNormals)1f else 0f)
        s.setColorUniform("tintColor",c.color?:palette.surface)
        s.setFloatUniform("backdropOpacity",c.backdropOpacity)
        s.setFloatUniform("softLens",if(c.material==LumenSurfaceMaterial.FROSTED)1f else 0f,
            if(c.sampling.refractionEnabled)c.sampling.refractionStrength else 0f,
            LensRefractionPolicy.LUMINANCE_GAIN,LensRefractionPolicy.LUMINANCE_BIAS/255f)
        val customEdge=c.edgeTopColor!=null||c.edgeBottomColor!=null
        val topEdge=c.edgeTopColor?:if(customEdge)(palette.primary and 0xFFFFFF)or(70 shl 24)else palette.primary
        s.setColorUniform("edgeColor",topEdge)
        s.setColorUniform("edgeColorBottom",c.edgeBottomColor?:topEdge)
        val strokeWidth=if(e.material.normalizeBySize)minOf(c.edgeWidthDp*density,short*e.material.maxEdgeFraction)else c.edgeWidthDp*density
        s.setFloatUniform("style",if(c.tintEnabled)c.tintOpacity else 0f,if(c.edgeEnabled)strokeWidth else 0f,
            (if(customEdge)1f else 70f/255f)*c.edgeIntensity,alpha)
        s.setFloatUniform("fade",if(c.sampling.fadeEnabled)1f else 0f,c.sampling.fadeHold,c.sampling.fadeEnd,
            if(c.sampling.fadeDirection==LumenSurfaceFadeDirection.BOTTOM_TO_TOP)1f else 0f)
        val shadowWidth=if(e.material.normalizeBySize)minOf(geometry.shadowRadiusDp*density,short*.25f)else geometry.shadowRadiusDp*density
        s.setFloatUniform("shadow",if(geometry.shadowEnabled&&detail!=LumenDetailMode.LOW)shadowWidth else 0f,geometry.shadowOpacity)
        val now=if(state.manualClock)state.frameNanos else System.nanoTime()
        for(i in 0..3){
            val born=state.rippleBorn[i]
            val age=if(born==Long.MIN_VALUE)Float.MAX_VALUE else (now-born).coerceAtLeast(0L)/1_000_000_000f
            ripple[0]=state.rippleX[i];ripple[1]=state.rippleY[i];ripple[2]=age
            val ringLimit=when(detail){LumenDetailMode.LOW->0;LumenDetailMode.BALANCED->minOf(2,press.maxRipples);LumenDetailMode.HIGH->press.maxRipples}
            ripple[3]=if(moving&&press.rippleEnabled&&i<ringLimit&&age<=press.rippleLifetimeMs/1000f)1f else 0f
            when(i){0->s.setFloatUniform("rippleA",ripple);1->s.setFloatUniform("rippleB",ripple);2->s.setFloatUniform("rippleC",ripple);else->s.setFloatUniform("rippleD",ripple)}
        }
        s.setFloatUniform("rippleStyle",press.rippleAmplitudeDp*density,press.rippleSpeedDpPerSecond*density,
            press.rippleWidthDp*density,press.rippleLifetimeMs/1000f)
        paint.shader=s;canvas.drawRect(bounds,paint)
        return true
    }
    override fun close(){paint.shader=null;shader=null}
}

private const val PROGRAM="""
uniform shader clearInput;
uniform shader weakInput;
uniform shader strongInput;
uniform float4 viewport;
uniform float4 inputInfo;
uniform float4 shapeA;
uniform float4 shapeB;
uniform float4 cornersA;
uniform float4 cornersB;
uniform float4 geometry;
uniform float4 progressive;
uniform float4 progressiveEnd;
uniform float4 touch;
uniform float4 optics;
uniform float warpLimit;
uniform float pressLight;
uniform float4 light;
uniform float4 normalMap;
uniform float4 lightStyle;
layout(color) uniform half4 tintColor;
uniform float backdropOpacity;
uniform float4 softLens;
layout(color) uniform half4 edgeColor;
layout(color) uniform half4 edgeColorBottom;
uniform float4 style;
uniform float4 fade;
uniform float2 shadow;
uniform float4 rippleA;
uniform float4 rippleB;
uniform float4 rippleC;
uniform float4 rippleD;
uniform float4 rippleStyle;

float ramp(float x,float a,float b){if(b<=a)return x>=b?1.0:0.0;float t=clamp((x-a)/(b-a),0.0,1.0);return t*t*(3.0-2.0*t);}
float3 corner(float2 p,float r,float3 current){float n=length(p);float d=n-r;if(d>current.x)return float3(d,n>0.00001?p/n:float2(0.0));return current;}
float3 rectField(float2 point,float4 box,float4 radii){
    float2 p=point-box.xy;float2 centre=p-box.zw*0.5;
    float2 q=abs(centre)-box.zw*0.5;float2 outside=max(q,float2(0.0));float n=length(outside);
    float d=n+min(max(q.x,q.y),0.0);
    float2 signPoint=float2(centre.x<0.0?-1.0:1.0,centre.y<0.0?-1.0:1.0);
    float2 gradient=n>0.00001?signPoint*outside/n:(q.x>q.y?float2(signPoint.x,0.0):float2(0.0,signPoint.y));
    float3 result=float3(d,gradient);
    if(p.x<radii.x&&p.y<radii.x)result=corner(p-float2(radii.x),radii.x,result);
    if(p.x>box.z-radii.y&&p.y<radii.y)result=corner(p-float2(box.z-radii.y,radii.y),radii.y,result);
    if(p.x>box.z-radii.z&&p.y>box.w-radii.z)result=corner(p-box.zw+float2(radii.z),radii.z,result);
    if(p.x<radii.w&&p.y>box.w-radii.w)result=corner(p-float2(radii.w,box.w-radii.w),radii.w,result);
    return result;
}
float3 field(float2 p){
    float3 a=rectField(p,shapeA,cornersA);
    if(geometry.y<0.5)return a;
    float3 b=rectField(p,shapeB,cornersB);
    if(geometry.x<=0.0)return a.x<=b.x?a:b;
    float h=clamp(0.5+0.5*(b.x-a.x)/geometry.x,0.0,1.0);
    return float3(h*a.x+(1.0-h)*b.x-geometry.x*h*(1.0-h),h*a.yz+(1.0-h)*b.yz);
}
float2 imagePoint(float2 p){
    if(geometry.w<0.5)p=clamp(p,float2(0.5),viewport.xy-float2(0.5));
    return clamp((p+inputInfo.y)/inputInfo.x,float2(0.5),inputInfo.zw-float2(0.5));
}
half4 background(float2 p){
    float2 pixel=imagePoint(p);
    if(progressiveEnd.z<0.5)return progressiveEnd.w>0.5?strongInput.eval(pixel):clearInput.eval(pixel);
    float y=clamp(p.y/viewport.y,0.0,1.0);if(progressive.y>0.5)y=1.0-y;
    float a=ramp(y,progressive.z,progressive.w)*progressive.x;
    float b=ramp(y,progressiveEnd.x,progressiveEnd.y)*progressive.x;
    return clearInput.eval(pixel)*half((1.0-b)*(1.0-a))+weakInput.eval(pixel)*half((1.0-b)*a)+strongInput.eval(pixel)*half(b);
}
float2 rippleSlope(float2 p,float4 ring){
    if(ring.w<0.5)return float2(0.0);
    float2 delta=p-ring.xy;float distance=length(delta);
    float front=ring.z*rippleStyle.y;float width=max(rippleStyle.z,1.0);
    float offset=(distance-front)/width;
    float fadeOut=1.0-clamp(ring.z/rippleStyle.w,0.0,1.0);
    float slope=-offset*exp(-0.5*offset*offset)*rippleStyle.x/width*fadeOut;
    return distance>0.0001?delta/distance*slope:float2(0.0);
}
half4 saturation(half4 c){
    if(c.a<=0.00001)return half4(0.0);
    float3 straight=float3(c.rgb)/float(c.a);
    float3 linear=toLinearSrgb(straight);float luminance=dot(linear,float3(0.2126,0.7152,0.0722));
    float3 result=fromLinearSrgb(clamp(float3(luminance)+(linear-float3(luminance))*optics.w,float3(0.0),float3(1.0)));
    return half4(half3(result*float(c.a)),c.a);
}
float softAxis(float u,float gain,float push){
    float c=clamp(u,-1.0,1.0);float rim=ramp(abs(c),0.62,1.0);
    return c*(1.0-gain*(1.0-c*c))+(c<0.0?-1.0:1.0)*push*rim*rim;
}
float2 softPoint(float2 point){
    float2 size=max(shapeA.zw,float2(1.0));float2 local=point-shapeA.xy;
    float2 gain=float2(0.06,0.16)*softLens.y;float2 push=float2(0.10,0.34)*softLens.y;
    float2 u=local/size*2.0-1.0;
    float2 source=shapeA.xy+(float2(softAxis(u.x,gain.x,push.x),softAxis(u.y,gain.y,push.y))+1.0)*0.5*size;
    float2 room=max(min(local-float2(0.5),size-float2(0.5)-local),float2(0.0));
    float2 budget=max(size*max(abs(push),abs(gain)*0.385),float2(1.0));
    source=point+(source-point)*clamp(room/budget,0.0,1.0);
    return clamp(source,shapeA.xy+float2(0.5),shapeA.xy+size-float2(0.5));
}
half4 main(float2 coord){
    float2 p=coord+viewport.zw;float3 f=field(p);float aa=max(geometry.z,0.25);
    float coverage=1.0-ramp(f.x,-aa,aa);
    float edgeWidth=max(lightStyle.z,style.y);
    float edgeWeight=1.0-ramp(abs(f.x),0.0,max(edgeWidth*2.0,1.0));
    float gn=length(f.yz);float2 direction=gn>0.00001?f.yz/gn:float2(0.0);
    float sigma=max(touch.w,1.0);float2 delta=p-touch.xy;
    float bump=optics.y*touch.z*exp(-dot(delta,delta)/(2.0*sigma*sigma));
    float2 pressSlope=-delta/(sigma*sigma)*bump;
    float2 waves=rippleSlope(p,rippleA)+rippleSlope(p,rippleB)+rippleSlope(p,rippleC)+rippleSlope(p,rippleD);
    float2 displacement=direction*optics.x*edgeWeight+(pressSlope+waves)*sigma;
    float travel=length(displacement);if(travel>warpLimit)displacement*=warpLimit/travel;
    half4 color=background(softLens.x>0.5?softPoint(p):p+displacement);
    if(softLens.x>0.5)color=half4(clamp(color.rgb*half(softLens.z)+half(softLens.w)*color.a,half3(0.0),half3(color.a)),color.a);
    if(softLens.x<0.5&&optics.z>0.001&&edgeWeight>0.01){
        half4 red=background(p+displacement+direction*optics.z*edgeWeight);
        half4 blue=background(p+displacement-direction*optics.z*edgeWeight);
        color=half4(min(red.r,color.a),color.g,min(blue.b,color.a),color.a);
    }
    color=saturation(color);
    float2 normalXY=direction*edgeWeight-pressSlope-waves;
    if(lightStyle.w>0.5)normalXY=float2(normalMap.x*normalXY.x+normalMap.y*normalXY.y,normalMap.z*normalXY.x+normalMap.w*normalXY.y);
    float3 normal=normalize(float3(normalXY,1.0));
    float3 halfVector=normalize(light.xyz+float3(0.0,0.0,1.0));
    float shine=(max(dot(normal,light.xyz),0.0)*0.06+pow(max(dot(normal,halfVector),0.0),lightStyle.y)*lightStyle.x*edgeWeight)*light.w;
    shine+=length(pressSlope)*pressLight;
    color.rgb=min(color.rgb+half3(shine*float(color.a)),half3(color.a));
    float tintAlpha=float(tintColor.a)*style.x;
    color*=half(backdropOpacity);
    color=half4(tintColor.rgb*half(tintAlpha),half(tintAlpha))+color*half(1.0-tintAlpha);
    float stroke=(1.0-ramp(abs(f.x),0.0,max(style.y,0.0001)))*style.z;
    if(style.y<=0.0)stroke=0.0;
    half4 edge=mix(edgeColor,edgeColorBottom,half(clamp(p.y/viewport.y,0.0,1.0)));
    stroke*=float(edge.a);
    color=half4(edge.rgb*half(stroke),half(stroke))+color*half(1.0-stroke);
    float dissolve=1.0;
    if(fade.x>0.5){float q=p.y/viewport.y;if(fade.w>0.5)q=1.0-q;dissolve=1.0-ramp(q,fade.y,fade.z);}
    color*=half(coverage*dissolve*style.w);
    if(shadow.x>0.0&&f.x>0.0){float alpha=exp(-f.x*f.x/(2.0*shadow.x*shadow.x))*shadow.y*(1.0-coverage)*style.w;color+=half4(0.0,0.0,0.0,alpha)*(1.0-color.a);}
    return color;
}
"""
