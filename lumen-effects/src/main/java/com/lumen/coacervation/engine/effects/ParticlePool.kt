package com.lumen.coacervation.engine.effects

import kotlin.math.*

/** Analytic age integration is deterministic across frame rates; all 128 slots are allocated once. */
internal class ParticlePool(seed:Int) {
    val x=FloatArray(128);val y=FloatArray(128);val radius=FloatArray(128);val alpha=FloatArray(128)
    private val startX=FloatArray(128);private val startY=FloatArray(128);private val velocityX=FloatArray(128);private val velocityY=FloatArray(128)
    private val born=LongArray(128){Long.MIN_VALUE};private val duration=LongArray(128);private val gravity=FloatArray(128)
    private var random=if(seed==0)1 else seed;private var last=Long.MIN_VALUE
    var live=0;private set
    var dropped=0L;private set
    fun clear(){born.fill(Long.MIN_VALUE);alpha.fill(0f);live=0;last=Long.MIN_VALUE}
    fun reseed(seed:Int){clear();random=if(seed==0)1 else seed}
    fun shiftTime(nanos:Long){if(nanos<=0)return;for(i in 0..127)if(born[i]!=Long.MIN_VALUE)born[i]+=nanos;if(last!=Long.MIN_VALUE)last+=nanos}
    private fun next():Float{var n=random;n=n xor(n shl 13);n=n xor(n ushr 17);n=n xor(n shl 5);random=n;return (n ushr 8)/16777216f}
    fun emit(px:Float,py:Float,density:Float,now:Long,c:LumenParticleOptions):Int {
        require(px.isFinite()&&py.isFinite()&&density.isFinite()&&density>0f&&now>=0L)
        if(!c.enabled)return 0
        update(now)
        var pixels=0f;for(i in 0..127)if(born[i]!=Long.MIN_VALUE)pixels+=4f*radius[i]*radius[i]+2f*radius[i]*c.trailLengthDp*density
        var added=0
        repeat(c.burstCount){
            val slot=(0 until c.maximumParticles).firstOrNull{born[it]==Long.MIN_VALUE}
            val r=c.radiusDp*density*(.5f+next()*.5f);val area=4f*r*r+2f*r*c.trailLengthDp*density
            if(slot==null||area+pixels>c.maximumParticlePixels){dropped++;return@repeat}
            val angle=(c.directionDegrees+(next()-.5f)*c.spreadDegrees)*PI/180.0;val speed=c.speedDpPerSecond*density*(.6f+.4f*next())
            startX[slot]=px;startY[slot]=py;x[slot]=px;y[slot]=py;velocityX[slot]=cos(angle).toFloat()*speed;velocityY[slot]=sin(angle).toFloat()*speed
            radius[slot]=r;gravity[slot]=c.gravityDpPerSecondSquared*density;born[slot]=now;duration[slot]=c.lifetimeMs*1_000_000L
            alpha[slot]=1f;pixels+=area;added++
        }
        update(now);return added
    }
    fun update(now:Long){
        if(last!=Long.MIN_VALUE&&now<last){clear();return};last=now;live=0
        for(i in 0..127){val birth=born[i];if(birth==Long.MIN_VALUE)continue;val age=now-birth
            if(age>=duration[i]){born[i]=Long.MIN_VALUE;alpha[i]=0f;continue}
            val t=age/1_000_000_000f;x[i]=startX[i]+velocityX[i]*t;y[i]=startY[i]+velocityY[i]*t+.5f*gravity[i]*t*t
            alpha[i]=(1f-age.toFloat()/duration[i]).coerceIn(0f,1f);live++
        }
    }
}
