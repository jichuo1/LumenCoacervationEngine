package com.lumen.coacervation.engine.sensor

import kotlin.math.*

/** Pure mutable policy: fixed storage, natural-device gravity mapped into screen axes (Y down). */
internal class GravityLightPolicy {
    var x=0f;private set
    var y=0f;private set
    var z=1f;private set
    private var last=Long.MIN_VALUE
    private var published=Long.MIN_VALUE
    private var offsetX=0f;private var offsetY=0f
    private var previousX=0f;private var previousY=0f;private var previousZ=1f
    fun reset(options:LumenSensorLightOptions){
        last=Long.MIN_VALUE;published=Long.MIN_VALUE;offsetX=0f;offsetY=0f
        val angle=options.baseAngleDegrees*PI/180.0;val horizontal=sqrt(1f-options.baseAltitude*options.baseAltitude)
        x=cos(angle).toFloat()*horizontal;y=sin(angle).toFloat()*horizontal;z=options.baseAltitude
        previousX=x;previousY=y;previousZ=z
    }
    fun update(gx:Float,gy:Float,gz:Float,rotation:Int,now:Long,options:LumenSensorLightOptions):Boolean {
        if(!gx.isFinite()||!gy.isFinite()||!gz.isFinite()||rotation !in 0..3||now<0L||last!=Long.MIN_VALUE&&now<=last)return false
        val length=sqrt(gx*gx+gy*gy+gz*gz);if(!length.isFinite()||length<.1f)return false
        val dx=gx/length;val dy=gy/length
        val sx=when(rotation){0->dx;1->dy;2->-dx;else->-dy}
        val sy=when(rotation){0->-dy;1->dx;2->dy;else->-dx}
        val dt=if(last==Long.MIN_VALUE)0f else ((now-last)/1_000_000_000f).coerceIn(0f,.25f);last=now
        val limit=sin(options.maximumTiltDegrees*PI/180.0).toFloat()
        val flat=dx*dx+dy*dy<options.flatThreshold*options.flatThreshold
        val tx=if(flat)0f else sx.coerceIn(-limit,limit)*options.influence*if(options.invertX)-1f else 1f
        val ty=if(flat)0f else sy.coerceIn(-limit,limit)*options.influence*if(options.invertY)-1f else 1f
        val weight=if(options.smoothingTimeMs==0L)1f else 1f-exp(-dt/(options.smoothingTimeMs/1000f))
        offsetX+=(tx-offsetX)*weight;offsetY+=(ty-offsetY)*weight
        val angle=options.baseAngleDegrees*PI/180.0;val horizontal=sqrt(1f-options.baseAltitude*options.baseAltitude)
        var nx=cos(angle).toFloat()*horizontal+offsetX;var ny=sin(angle).toFloat()*horizontal+offsetY;var nz=options.baseAltitude
        var n=sqrt(nx*nx+ny*ny+nz*nz);nx/=n;ny/=n;nz/=n
        val radians=acos((x*nx+y*ny+z*nz).coerceIn(-1f,1f))
        val step=options.maximumAngularSpeedDegrees*PI.toFloat()/180f*dt
        if(dt>0f&&radians>step&&radians>1e-5f){val divisor=sin(radians);val oldWeight=sin(radians-step)/divisor;val newWeight=sin(step)/divisor
            nx=x*oldWeight+nx*newWeight;ny=y*oldWeight+ny*newWeight;nz=z*oldWeight+nz*newWeight;n=sqrt(nx*nx+ny*ny+nz*nz);nx/=n;ny/=n;nz/=n}
        x=nx;y=ny;z=nz
        if(published!=Long.MIN_VALUE&&now-published<options.minimumUpdateIntervalMs*1_000_000L)return false
        val change=acos((x*previousX+y*previousY+z*previousZ).coerceIn(-1f,1f))*180f/PI.toFloat()
        if(published!=Long.MIN_VALUE&&change<options.changeThresholdDegrees)return false
        published=now;previousX=x;previousY=y;previousZ=z;return true
    }
}
