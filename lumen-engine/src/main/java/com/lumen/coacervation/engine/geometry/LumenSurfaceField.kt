package com.lumen.coacervation.engine.geometry

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Mutable destination reused by frame callers: distance followed by the unnormalised gradient. */
internal class LumenDistanceResult { var distance=0f;var x=0f;var y=0f }

internal object LumenSurfaceField {
    /** CSS adjacent-edge constraints; keep the relative radius proportions. TL, TR, BR, BL. */
    fun normaliseCorners(width: Float,height: Float,tl: Float,tr: Float,br: Float,bl: Float,out: FloatArray) {
        var scale=1f
        if(tl+tr>0f)scale=minOf(scale,width/(tl+tr))
        if(bl+br>0f)scale=minOf(scale,width/(bl+br))
        if(tl+bl>0f)scale=minOf(scale,height/(tl+bl))
        if(tr+br>0f)scale=minOf(scale,height/(tr+br))
        scale=scale.coerceIn(0f,1f)
        out[0]=tl*scale;out[1]=tr*scale;out[2]=br*scale;out[3]=bl*scale
    }

    /** Corner regions rather than quadrant selection: a CSS radius can exceed half the short edge. */
    fun roundedRect(x: Float,y: Float,width: Float,height: Float,radii: FloatArray,out: LumenDistanceResult) {
        val px=x-width*.5f;val py=y-height*.5f
        val qx=abs(px)-width*.5f;val qy=abs(py)-height*.5f
        val ox=maxOf(qx,0f);val oy=maxOf(qy,0f);val length=sqrt(ox*ox+oy*oy)
        out.distance=length+minOf(maxOf(qx,qy),0f)
        if(length>1e-5f){out.x=sign(px)*ox/length;out.y=sign(py)*oy/length}
        else if(qx>qy){out.x=sign(px);out.y=0f}else{out.x=0f;out.y=sign(py)}
        if(x<radii[0]&&y<radii[0])corner(x-radii[0],y-radii[0],radii[0],out)
        if(x>width-radii[1]&&y<radii[1])corner(x-width+radii[1],y-radii[1],radii[1],out)
        if(x>width-radii[2]&&y>height-radii[2])corner(x-width+radii[2],y-height+radii[2],radii[2],out)
        if(x<radii[3]&&y>height-radii[3])corner(x-radii[3],y-height+radii[3],radii[3],out)
    }
    private fun corner(x: Float,y: Float,radius: Float,out: LumenDistanceResult) {
        val length=sqrt(x*x+y*y);val d=length-radius
        if(d>out.distance){out.distance=d;if(length>1e-5f){out.x=x/length;out.y=y/length}else{out.x=0f;out.y=0f}}
    }
    fun smoothUnion(a: LumenDistanceResult,b: LumenDistanceResult,radius: Float,out: LumenDistanceResult) {
        if(radius<=0f){val p=if(a.distance<=b.distance)a else b;out.distance=p.distance;out.x=p.x;out.y=p.y;return}
        val h=(.5f+.5f*(b.distance-a.distance)/radius).coerceIn(0f,1f)
        out.distance=h*a.distance+(1f-h)*b.distance-radius*h*(1f-h)
        out.x=h*a.x+(1f-h)*b.x;out.y=h*a.y+(1f-h)*b.y
    }
    fun pressSlope(dx: Float,dy: Float,amplitude: Float,sigma: Float,out: FloatArray) {
        if(sigma<=0f){out[0]=0f;out[1]=0f;return}
        val variance=sigma*sigma;val height=amplitude*exp(-(dx*dx+dy*dy)/(2f*variance))
        out[0]=-dx/variance*height;out[1]=-dy/variance*height
    }
    fun progressiveWeights(position: Float,weakStart: Float,weakEnd: Float,strongStart: Float,strongEnd: Float,strength: Float,out: FloatArray) {
        val a=smooth(position,weakStart,weakEnd)*strength;val b=smooth(position,strongStart,strongEnd)*strength
        out[0]=(1f-b)*(1f-a);out[1]=(1f-b)*a;out[2]=b
    }
    fun smooth(x: Float,start: Float,end: Float): Float {
        if(end<=start)return if(x>=end)1f else 0f
        val t=((x-start)/(end-start)).coerceIn(0f,1f);return t*t*(3f-2f*t)
    }
    private fun sign(x: Float)=if(x<0f)-1f else 1f
}
