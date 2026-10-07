package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.host.*
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.*
import org.junit.Test

class LumenEnhancementPolicyTest {
    @Test fun factoryKeepsLegacyAndLowVersionPlansExplicit(){
        assertEquals(LumenSurfaceEffectFactory.Plan.LEGACY_NODE,LumenSurfaceEffectFactory.plan(34,LumenSurfaceOptions(),false))
        assertEquals(LumenSurfaceEffectFactory.Plan.COMPAT_CONTOUR,LumenSurfaceEffectFactory.plan(27,LumenSurfaceOptions(),true))
        assertEquals(LumenSurfaceEffectFactory.Plan.DIRECT_BITMAP,LumenSurfaceEffectFactory.plan(33,LumenSurfaceOptions(),true))
        assertEquals(LumenSurfaceEffectFactory.Plan.OPAQUE,LumenSurfaceEffectFactory.plan(34,LumenSurfaceOptions(material=LumenSurfaceMaterial.STATIC),true))
    }
    @Test fun stagesDeclareOnlyTheirOwnedCaptureAndFiniteExpansion(){
        val e=LumenSurfaceEnhancements(progressiveBlur=LumenProgressiveBlurOptions(enabled=true),press=LumenLocalPressOptions(enabled=true,rippleEnabled=true))
        val stage=LumenSurfaceEffectFactory.requirements(LumenSurfaceOptions(),e)
        assertTrue(stage.captureBackground);assertTrue(stage.needsTouch);assertTrue(stage.needsTime)
        assertEquals(4f,stage.weakBlurDp,0f);assertEquals(16f,stage.strongBlurDp,0f);assertTrue(stage.haloDp<128f)
        assertFalse(LumenSurfaceEffectFactory.requirements(LumenSurfaceOptions(material=LumenSurfaceMaterial.STATIC),e).captureBackground)
        assertFalse(LumenSurfaceEffectFactory.requirements(LumenSurfaceOptions(),e.copy(material=LumenMaterialRecipeOptions(reduceMotion=true))).needsTime)
    }
    @Test fun accessibleRecipeOverridesTransparencyAndDoesNotRequireAProbe(){
        val original=LumenSurfaceOptions(opacity=.2f,color=0x22000000)
        val result=LumenMaterialRecipe.resolve(original,LumenMaterialRecipeOptions(reduceTransparency=true),LumenPalette.neutral(false))
        assertEquals(1f,result.opacity,0f);assertEquals(1f,result.fallbackTintOpacity,0f);assertFalse(result.sampling.enabled)
        assertEquals(255,result.color!! ushr 24)
    }
    @Test fun unchangedRecipePreservesTheOriginalConfiguration(){val c=LumenSurfaceOptions();assertSame(c,LumenMaterialRecipe.resolve(c,LumenMaterialRecipeOptions(),LumenPalette.neutral(false)))}
    @Test fun hostTintOverrideSurvivesReadingIntent(){val c=LumenSurfaceOptions(tintOpacity=.6f);assertEquals(.6f,LumenMaterialRecipe.resolve(c,LumenMaterialRecipeOptions(intent=LumenMaterialIntent.READING),LumenPalette.neutral(false)).tintOpacity,0f)}
    @Test fun intentRoleDefaultsCanBeDisabledAndAccessibilityStillWins(){
        val c=LumenSurfaceOptions(role=com.lumen.coacervation.engine.model.SurfaceRole.MODAL)
        val recipe=LumenMaterialRecipeOptions(intent=LumenMaterialIntent.READING)
        assertEquals(.38f,LumenMaterialRecipe.resolve(c,recipe,LumenPalette.neutral(false)).tintOpacity,0f)
        assertSame(c,LumenMaterialRecipe.resolve(c,recipe.copy(useIntentDefaults=false),LumenPalette.neutral(false)))
        assertFalse(LumenMaterialRecipe.resolve(c,recipe.copy(useIntentDefaults=false,reduceTransparency=true),LumenPalette.neutral(false)).sampling.enabled)
    }
    @Test fun qualityUsesHysteresisAndMinimumResidence(){
        val p=LumenSurfaceQualityPolicy();val c=LumenSurfaceQualityOptions(enabled=true,adaptive=true,mode=LumenDetailMode.HIGH,minimumDwellMs=1000)
        assertEquals(LumenDetailMode.HIGH,p.update(1f,0L,c));assertEquals(LumenDetailMode.HIGH,p.update(1f,999_000_000L,c))
        assertEquals(LumenDetailMode.BALANCED,p.update(1f,1_000_000_000L,c));assertEquals(LumenDetailMode.BALANCED,p.update(1f,1_500_000_000L,c))
        assertEquals(LumenDetailMode.LOW,p.update(1f,2_000_000_000L,c));assertEquals(LumenDetailMode.LOW,p.update(.72f,3_000_000_000L,c))
        assertEquals(LumenDetailMode.BALANCED,p.update(.5f,3_000_000_000L,c))
    }
    @Test fun enablingOrChangingAdaptiveQualityStartsAtTheRequestedMode(){
        val p=LumenSurfaceQualityPolicy()
        assertEquals(LumenDetailMode.HIGH,p.update(0f,0L,LumenSurfaceQualityOptions()))
        val low=LumenSurfaceQualityOptions(enabled=true,adaptive=true,mode=LumenDetailMode.LOW)
        assertEquals(LumenDetailMode.LOW,p.update(0f,100L,low))
        assertEquals(LumenDetailMode.HIGH,p.update(1f,200L,low.copy(mode=LumenDetailMode.HIGH)))
    }
    @Test fun allNewNonFiniteAndUnboundedInputsAreRejected(){
        listOf<()->Any>({LumenSurfaceCorners(Float.NaN)},{LumenSurfaceGeometryOptions(fusionRadiusDp=Float.POSITIVE_INFINITY)},
            {LumenProgressiveBlurOptions(strongRadiusDp=1f,weakRadiusDp=2f)},{LumenLocalPressOptions(maxRipples=5)},
            {LumenSurfaceLightOptions(altitude=0f)},{LumenMaterialRecipeOptions(saturation=3f)},
            {LumenSurfaceQualityOptions(maxBitmapPixels=Int.MAX_VALUE)},{LumenSurfaceDebugOptions(boundsOpacity=0f)}).forEach {assertThrows(IllegalArgumentException::class.java){it()}}
    }
}
