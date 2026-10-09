package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.host.*
import com.lumen.coacervation.engine.material.LumenSampleBudget
import com.lumen.coacervation.engine.material.LensRefractionPolicy
import org.junit.Assert.*
import org.junit.Test

class LumenSurfacePolicyTest {
    @Test fun linearFadeHasConstantRateAndRespectsDirectionAndBounds() {
        for (reverse in listOf(false, true)) {
            for (i in 0..100) {
                val x = .2f + .6f * i / 100f
                val fraction = if (reverse) 1f - x else x
                assertEquals(1f - i / 100f,
                    LumenSurfacePolicy.fade(fraction, .2f, .8f, reverse, LumenSurfaceFadeCurve.LINEAR), .00001f)
            }
        }
        assertEquals(1f, LumenSurfacePolicy.fade(0f, .2f, .8f, false, LumenSurfaceFadeCurve.LINEAR), 0f)
        assertEquals(0f, LumenSurfacePolicy.fade(1f, .2f, .8f, false, LumenSurfaceFadeCurve.LINEAR), 0f)
        assertEquals(0f, LumenSurfacePolicy.fade(.6f, .5f, .5f, false, LumenSurfaceFadeCurve.LINEAR), 0f)
    }

    @Test fun defaultFadePreservesExistingSmoothCurve() {
        assertEquals(LumenSurfaceFadeCurve.SMOOTH, LumenSurfaceSampling().fadeCurve)
        for (i in 0..100) {
            val t = i / 100f
            assertEquals(1f - t * t * (3f - 2f * t), LumenSurfacePolicy.fade(t, 0f, 1f, false), .00001f)
        }
    }

    @Test fun fadeIsMonotonicAndReversible() {
        var previous = 1f
        for (i in 0..100) {
            val x = i / 100f
            val value = LumenSurfacePolicy.fade(x, .2f, .8f, false)
            assertTrue(value in 0f..previous)
            assertEquals(value, LumenSurfacePolicy.fade(1f - x, .2f, .8f, true), .00001f)
            previous = value
        }
        assertEquals(1f, LumenSurfacePolicy.fade(.2f, .2f, .8f, false), 0f)
        assertEquals(0f, LumenSurfacePolicy.fade(.8f, .2f, .8f, false), 0f)
    }

    @Test fun collapsedFadeDoesNotProduceNan() {
        assertEquals(1f, LumenSurfacePolicy.fade(.5f, .5f, .5f, false), 0f)
        assertEquals(0f, LumenSurfacePolicy.fade(.6f, .5f, .5f, false), 0f)
    }

    @Test fun softwareBudgetHoldsForThinHugeAndOrdinarySurfaces() {
        for ((w,h) in listOf(1 to 100_000, 100_000 to 1, 1440 to 3168, Int.MAX_VALUE to Int.MAX_VALUE, 13 to 17)) {
            for (budget in listOf(1024,24000,96000)) {
                val divisor = LumenSurfacePolicy.softwareDivisor(w,h,1f,budget)
                val pixels = ((w.toLong()+divisor-1)/divisor)*((h.toLong()+divisor-1)/divisor)
                assertTrue("$w x $h exceeds $budget",pixels <= budget)
            }
        }
    }

    @Test fun gpuScalingHasARealBudgetAndNeverUpscales() {
        for ((w,h) in listOf(1 to Int.MAX_VALUE,Int.MAX_VALUE to 1,1440 to 3168,6000 to 8000)) {
            val scale=LumenSurfacePolicy.gpuScale(w,h,1f,65536)
            assertTrue(scale>0f && scale<=1f)
            val width=maxOf(1,(w*scale).toInt());val height=maxOf(1,(h*scale).toInt())
            assertTrue(width.toLong()*height<=65536)
        }
    }

    @Test fun softwareBudgetIncludesSeparatelyRoundedPadding() {
        for ((w,h) in listOf(1 to Int.MAX_VALUE,Int.MAX_VALUE to 1,1440 to 3168,13 to 17)) {
            for (budget in listOf(1024,24000,96000)) for (padding in listOf(1,32,160)) {
                val divisor=LumenSurfacePolicy.softwareDivisor(w,h,1f,budget,padding)
                val margin=((padding.toLong()+divisor-1)/divisor).coerceAtLeast(1)
                val width=(w.toLong()+divisor-1)/divisor+2*margin
                val height=(h.toLong()+divisor-1)/divisor+2*margin
                assertTrue("Padded $w x $h exceeds $budget",width*height<=budget)
            }
        }
    }

    @Test fun forcedBackendsAndFailureAreExplicit() {
        assertFalse(LumenSurfacePolicy.gpuAllowed(27,LumenSurfaceBackend.GPU,true,false))
        assertFalse(LumenSurfacePolicy.gpuAllowed(34,LumenSurfaceBackend.AUTO,true,true))
        assertFalse(LumenSurfacePolicy.gpuAllowed(34,LumenSurfaceBackend.SOFTWARE,true,false))
        assertTrue(LumenSurfacePolicy.gpuAllowed(31,LumenSurfaceBackend.AUTO,true,false))
        assertFalse(LumenSurfacePolicy.softwareAllowed(LumenSurfaceSampling(softwareFallback=false)))
        assertFalse(LumenSurfacePolicy.softwareAllowed(LumenSurfaceSampling(backend=LumenSurfaceBackend.GPU,softwareFallback=false)))
        assertTrue(LumenSurfacePolicy.softwareAllowed(LumenSurfaceSampling(backend=LumenSurfaceBackend.SOFTWARE,softwareFallback=false)))
        assertFalse(LumenSurfacePolicy.softwareAllowed(LumenSurfaceSampling(enabled=false)))
    }

    @Test fun sharedBuffersCannotExceedTheSessionLimit() {
        val budget=LumenSampleBudget(100)
        assertTrue(budget.reserve(60));assertFalse(budget.reserve(41))
        assertEquals(60,budget.used)
        budget.release(60);assertTrue(budget.reserve(100));assertFalse(budget.reserve(Long.MAX_VALUE))
        budget.release(100);assertEquals(0,budget.used)
    }

    @Test fun invalidAndNonFiniteSliderInputIsRejected() {
        val invalid=listOf<()->Any>(
            { LumenSurfaceSampling(blurRadiusDp=Float.NaN) },
            { LumenSurfaceSampling(softwareScale=0f) },
            { LumenSurfaceSampling(fadeHold=.8f,fadeEnd=.2f) },
            { LumenSurfaceSampling(maxSoftwarePixels=Int.MAX_VALUE) },
            { LumenSurfaceOptions(opacity=Float.POSITIVE_INFINITY) },
            { LumenSurfaceOptions(radiusDp=-1f) },
            { LumenSurfaceSessionOptions(maxSoftwareBytes=Int.MAX_VALUE) },
            { LumenSurfaceSessionOptions(maxGpuDimension=Int.MAX_VALUE) })
        invalid.forEach { create -> assertThrows(IllegalArgumentException::class.java) { create() } }
    }

    @Test fun zeroStrengthSamplesTheUnwarpedImage() {
        val pixels=IntArray(16*16) { 0xff000000.toInt() or (it shl 8) }
        val output=IntArray(pixels.size)
        LensRefractionPolicy.remap(pixels,16,16,0,output,16,16,0f)
        pixels.indices.forEach { assertEquals(pixels[it],output[it]) }
    }
}
