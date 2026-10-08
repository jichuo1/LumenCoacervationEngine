package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.sensor.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GravityLightPolicyTest {
    @Test fun displayRotationsMapNaturalGravityIntoScreenAxes(){
        val c=LumenSensorLightOptions(smoothingTimeMs=0,baseAngleDegrees=0f,influence=1f)
        fun sample(rotation:Int):GravityLightPolicy=GravityLightPolicy().apply {reset(c);update(5f,0f,8.5f,rotation,0L,c)}
        assertTrue(sample(0).x>sample(2).x);assertTrue(sample(1).y>sample(3).y)
    }
    @Test fun elapsedTimeFilterIsStableAcrossSensorRates(){
        val c=LumenSensorLightOptions(baseAngleDegrees=90f,maximumAngularSpeedDegrees=360f,changeThresholdDegrees=0f)
        fun run(rate:Int):Float {val p=GravityLightPolicy();p.reset(c);p.update(3f,0f,9f,0,0,c);for(i in 1..rate)p.update(3f,0f,9f,0,i*1_000_000_000L/rate,c);return p.x}
        assertEquals(run(50),run(120),.002f)
    }
    @Test fun flatInvalidAndOutOfOrderEventsStayBounded(){
        val c=LumenSensorLightOptions(smoothingTimeMs=0);val p=GravityLightPolicy();p.reset(c);val baseX=p.x
        assertTrue(p.update(0f,0f,9.81f,0,1L,c));assertEquals(baseX,p.x,.0001f)
        assertFalse(p.update(Float.NaN,0f,1f,0,2,c));assertFalse(p.update(0f,0f,0f,0,2,c));assertFalse(p.update(1f,0f,9f,0,1,c))
        assertEquals(1f,sqrt(p.x*p.x+p.y*p.y+p.z*p.z),.0001f)
    }
    @Test fun thresholdAndUpdateRateStopPublishingStableInput(){
        val c=LumenSensorLightOptions(smoothingTimeMs=0);val p=GravityLightPolicy();p.reset(c)
        assertTrue(p.update(0f,0f,9.81f,0,0,c));for(i in 1..30)assertFalse(p.update(0f,0f,9.81f,0,i*40_000_000L,c))
    }
    @Test fun optionsRejectNonFiniteAndHighRateValues(){
        assertThrows(IllegalArgumentException::class.java){LumenSensorLightOptions(sampleRateHz=201)}
        assertThrows(IllegalArgumentException::class.java){LumenSensorLightOptions(influence=Float.NaN)}
    }
    @Test fun laterSamplesRespectTheExactAngularSpeedLimit(){
        val c=LumenSensorLightOptions(smoothingTimeMs=0,influence=1f,maximumAngularSpeedDegrees=10f,changeThresholdDegrees=0f)
        val p=GravityLightPolicy();p.reset(c);p.update(0f,0f,9f,0,0,c)
        val x=p.x;val y=p.y;val z=p.z;p.update(8f,0f,1f,0,100_000_000,c)
        val degrees=acos((x*p.x+y*p.y+z*p.z).coerceIn(-1f,1f))*180f/PI.toFloat()
        assertTrue("Angular velocity cap exceeded: $degrees",degrees<=1.01f)
    }
}
