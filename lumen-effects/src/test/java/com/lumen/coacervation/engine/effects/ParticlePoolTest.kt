package com.lumen.coacervation.engine.effects

import org.junit.Assert.*
import org.junit.Test

class ParticlePoolTest {
    @Test fun fixedPoolAndAreaBudgetDropOverflow(){
        val p=ParticlePool(1);val c=LumenParticleOptions(enabled=true,maximumParticles=4,burstCount=64)
        assertEquals(4,p.emit(100f,100f,1f,0,c));assertEquals(60L,p.dropped);assertEquals(4,p.live)
        assertEquals(0,p.emit(100f,100f,1f,0,c))
    }
    @Test fun integrationDoesNotDependOnFrameRateAndSeedReplays(){
        val c=LumenParticleOptions(enabled=true,lifetimeMs=2000)
        fun run(rate:Int):ParticlePool=ParticlePool(123).apply{emit(100f,100f,1f,0,c);for(i in 1..rate)update(i*1_000_000_000L/rate)}
        val a=run(30);val b=run(120);assertArrayEquals(a.x,b.x,0f);assertArrayEquals(a.y,b.y,0f)
    }
    @Test fun lifetimeBackwardSeekAndClearStopThePool(){
        val p=ParticlePool(1);val c=LumenParticleOptions(enabled=true,lifetimeMs=100)
        p.emit(1f,1f,1f,0,c);p.update(100_000_000);assertEquals(0,p.live)
        p.emit(1f,1f,1f,200_000_000,c);p.update(199_000_000);assertEquals(0,p.live)
    }
    @Test fun particlePixelsAreBoundedIndependentlyOfSlotCount(){
        val p=ParticlePool(1);val c=LumenParticleOptions(enabled=true,radiusDp=12f,maximumParticlePixels=1024)
        p.emit(1f,1f,2f,0,c);assertTrue(p.live<24);assertTrue(p.dropped>0)
    }
    @Test fun defaultsDoNotStartAnEmitter(){val p=ParticlePool(1);assertEquals(0,p.emit(1f,1f,1f,0,LumenParticleOptions()))}
}
