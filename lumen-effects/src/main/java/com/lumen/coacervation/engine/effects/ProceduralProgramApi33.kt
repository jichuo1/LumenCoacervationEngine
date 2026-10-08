package com.lumen.coacervation.engine.effects

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/** Built-in generators only. No background input shader, capture, offscreen layer, or network program. */
@RequiresApi(33)
internal class ProceduralProgramApi33:AutoCloseable {
    private var shader:RuntimeShader?=RuntimeShader(PROGRAM)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    fun draw(canvas:Canvas,bounds:RectF,c:LumenProceduralOptions,density:Float,phase:Float,seed:Int,alpha:Float):Boolean {
        val s=shader?:return false;if(!canvas.isHardwareAccelerated)return false
        s.setFloatUniform("size",bounds.width(),bounds.height());s.setFloatUniform("origin",bounds.left,bounds.top)
        s.setFloatUniform("style",c.kind.ordinal.toFloat(),c.radiusDp*density,c.opacity*alpha,phase)
        s.setColorUniform("tint",c.color)
        s.setFloatUniform("film",c.film.thicknessNm,c.film.iridescence,c.film.roughness,c.film.angleDegrees/180f*3.14159265f)
        s.setFloatUniform("filmIntensity",c.film.intensity)
        s.setFloatUniform("paper",if(c.paper.grainEnabled)c.paper.grainCount.toFloat()else 0f,c.paper.grainSizeDp*density,c.paper.contrast,c.paper.relief)
        s.setFloatUniform("energy",c.energy.bands.toFloat(),c.energy.wavelengthDp*density,c.energy.intensity,c.energy.speed)
        s.setFloatUniform("seed",(seed and 4095).toFloat())
        paint.shader=s;canvas.drawRect(bounds,paint);return true
    }
    override fun close(){paint.shader=null;shader=null}
}
private const val PROGRAM="""
uniform float2 size; uniform float2 origin; uniform float4 style;
layout(color) uniform half4 tint;
uniform float4 film; uniform float filmIntensity; uniform float4 paper; uniform float4 energy; uniform float seed;
float hash(float2 p){return fract(sin(dot(p,float2(12.9898,78.233))+seed)*43758.5453);}
half4 main(float2 point){
    float2 p=point-origin;float radius=min(style.y,min(size.x,size.y)*0.5);
    float2 q=abs(p-size*0.5)-size*0.5+radius;
    float distance=length(max(q,float2(0.0)))+min(max(q.x,q.y),0.0)-radius;
    float cover=1.0-smoothstep(-0.75,0.75,distance);
    float3 color=float3(tint.rgb);
    if(style.x<0.5){
        float projection=dot(p/max(size,float2(1.0))-0.5,float2(cos(film.w),sin(film.w)));
        float optical=film.x*(0.4+0.6*(projection+0.5));
        float3 interference=0.5+0.5*cos(12.5663706*optical/float3(650.0,510.0,440.0));
        float amount=film.y*(1.0-film.z*0.7)*filmIntensity;
        color=clamp(mix(color,interference,clamp(amount,0.0,1.0)),float3(0.0),float3(1.0));
    }else if(style.x<1.5){
        if(paper.x>0.0){
            float cell=max(sqrt(size.x*size.y/paper.x),1.0);float2 tile=floor(p/cell);
            float2 centre=float2(hash(tile),hash(tile+17.0))*0.6+0.2;
            float dotMask=1.0-smoothstep(paper.y*0.5,paper.y,length((fract(p/cell)-centre)*cell));
            float shade=(hash(tile+31.0)-0.5)*paper.z+paper.w*0.05;
            color=clamp(color+float3(dotMask*shade),float3(0.0),float3(1.0));
        }
    }else{
        float radial=length(p-size*0.5);float wave=0.5+0.5*cos(radial/max(energy.y,1.0)*6.2831853-energy.w*style.w*6.2831853);
        float burst=sin(clamp(style.w,0.0,1.0)*3.14159265);
        float rings=pow(wave,max(energy.x,1.0))*burst*energy.z;
        color=clamp(color+float3(rings*0.35,rings*0.15,rings*0.5),float3(0.0),float3(1.0));
    }
    float alpha=float(tint.a)*style.z*cover;
    if(style.x>1.5)alpha*=max(sin(clamp(style.w,0.0,1.0)*3.14159265),0.0)*clamp(energy.z,0.0,1.0);
    return half4(half3(color*alpha),half(alpha));
}
"""
