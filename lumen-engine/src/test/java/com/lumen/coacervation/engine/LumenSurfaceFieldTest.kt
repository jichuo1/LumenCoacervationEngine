package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.geometry.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class LumenSurfaceFieldTest {
    @Test fun cornerNormalisationPreservesRatiosAndAllAdjacentConstraints() {
        val out=FloatArray(4)
        LumenSurfaceField.normaliseCorners(100f,60f,80f,40f,20f,10f,out)
        assertEquals(2f,out[0]/out[1],.0001f)
        assertTrue(out[0]+out[1]<=100.001f&&out[3]+out[2]<=100.001f)
        assertTrue(out[0]+out[3]<=60.001f&&out[1]+out[2]<=60.001f)
    }
    @Test fun cornerLargerThanHalfWidthKeepsTheCorrectArcBeyondTheCentre() {
        val out=LumenDistanceResult()
        LumenSurfaceField.roundedRect(60f,20f,100f,100f,floatArrayOf(80f,10f,10f,10f),out)
        assertEquals(kotlin.math.sqrt(20f*20f+60f*60f)-80f,out.distance,.001f)
        assertTrue(out.x<0f&&out.y<0f)
    }
    @Test fun sharpRectangleHasFiniteGradientsInsideAndOutside() {
        val out=LumenDistanceResult();val radii=FloatArray(4)
        for(x in -20..120)for(y in -20..80){
            LumenSurfaceField.roundedRect(x.toFloat(),y.toFloat(),100f,60f,radii,out)
            assertTrue(out.distance.isFinite()&&out.x.isFinite()&&out.y.isFinite())
            if(x in 1..99&&y in 1..59)assertTrue(out.distance<0f)
        }
    }
    @Test fun smoothUnionHasConservativeExpansionAndStableZeroBlend() {
        val a=LumenDistanceResult();val b=LumenDistanceResult();val out=LumenDistanceResult();val random=Random(1)
        repeat(1000){
            a.distance=random.nextFloat()*40f-20f;b.distance=random.nextFloat()*40f-20f
            a.x=1f;a.y=0f;b.x=-1f;b.y=0f
            val k=random.nextFloat()*64f+.01f
            LumenSurfaceField.smoothUnion(a,b,k,out)
            val min=minOf(a.distance,b.distance)
            assertTrue(out.distance>=min-k/4f-.0001f&&out.distance<=min+.0001f)
            LumenSurfaceField.smoothUnion(a,b,0f,out);assertEquals(min,out.distance,0f)
        }
    }
    @Test fun analyticUnionGradientAgreesWithFiniteDifferencesAwayFromSeams() {
        val a=LumenDistanceResult();val b=LumenDistanceResult();val out=LumenDistanceResult();val radii=floatArrayOf(12f,4f,18f,6f)
        fun field(x: Float,y: Float): Float {
            LumenSurfaceField.roundedRect(x,y,80f,60f,radii,a)
            LumenSurfaceField.roundedRect(x-65f,y-8f,80f,60f,radii,b)
            LumenSurfaceField.smoothUnion(a,b,24f,out);return out.distance
        }
        for(x in 52..78 step 3)for(y in 18..40 step 3){
            val gx=(field(x+.01f,y.toFloat())-field(x-.01f,y.toFloat()))/.02f
            val gy=(field(x.toFloat(),y+.01f)-field(x.toFloat(),y-.01f))/.02f
            field(x.toFloat(),y.toFloat())
            assertEquals(gx,out.x,.002f);assertEquals(gy,out.y,.002f)
        }
    }
    @Test fun progressiveWeightsRemainNormalisedAtAllStrengths() {
        val weights=FloatArray(3)
        for(i in 0..100)for(strength in listOf(0f,.5f,1f)){
            LumenSurfaceField.progressiveWeights(i/100f,0f,.65f,.4f,1f,strength,weights)
            assertEquals(1f,weights.sum(),.00001f);weights.forEach {assertTrue(it in 0f..1f)}
        }
    }
    @Test fun pressIsLocalAndHasNoSlopeAtTheTouchCentre() {
        val out=FloatArray(2)
        LumenSurfaceField.pressSlope(0f,0f,3f,10f,out);assertEquals(0f,out[0],0f);assertEquals(0f,out[1],0f)
        LumenSurfaceField.pressSlope(5f,0f,3f,10f,out);assertTrue(out[0]<0f);assertEquals(0f,out[1],0f)
        LumenSurfaceField.pressSlope(1000f,1000f,3f,10f,out);assertEquals(0f,out[0],.000001f)
    }
}
