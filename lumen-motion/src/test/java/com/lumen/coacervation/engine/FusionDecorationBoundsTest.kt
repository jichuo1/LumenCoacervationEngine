package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.host.LumenSurfaceGeometryOptions
import com.lumen.coacervation.engine.widget.FusionDecorationBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class FusionDecorationBoundsTest {
    @Test fun onlyEnabledStagesExpandAndShadowTailHasEnoughSpace(){
        assertEquals(4f,FusionDecorationBounds.haloDp(LumenSurfaceGeometryOptions()),0f)
        assertEquals(8f,FusionDecorationBounds.haloDp(LumenSurfaceGeometryOptions(fusionEnabled=true)),0f)
        assertEquals(92f,FusionDecorationBounds.haloDp(LumenSurfaceGeometryOptions(fusionEnabled=true,shadowEnabled=true,shadowRadiusDp=24f)),0f)
    }
}
