package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

/** PMA110/API 37: extended-range ColorMatrix RGB survived until composition and distorted a linear fade. */
class FrostedSdrToneContractTest {
    @Test fun frostedNodeUsesTheClampedShaderEvenWithRefractionDisabled() {
        val glass = SourceContract.read("host/SurfaceCaptureApi31.kt").after("class Glass(")
        val lens = glass.after("private val lensStrength").before("val renderer =")
        assertTrue(lens.contains("if (sampling.refractionEnabled) sampling.refractionStrength else 0f"))
        assertTrue(lens.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU"))
        assertTrue(lens.contains("options.material == LumenSurfaceMaterial.FROSTED || lensStrength > 0f"))
        assertTrue(lens.contains("cache.lens(options.material, liquidParameters, density, lensStrength, counters)"))
        assertTrue(glass.contains("FrostedChromeLensApi33.effect(lens, effect, width, height, padding, lensStrength)"))
    }

    @Test fun premultipliedSdrClampPrecedesTheFadeStage() {
        val shader = SourceContract.read("material/FrostedChromeGlassApi31.kt")
        assertTrue(shader.contains("half3(0.0), half3(color.a)"))
        val glass = SourceContract.read("host/SurfaceCaptureApi31.kt").after("val renderer =")
        assertTrue(glass.indexOf("FrostedChromeLensApi33.effect") < glass.indexOf("if (sampling.fadeEnabled)"))
    }

    @Test fun directShaderAcceptsTheCurveAndKeepsTheHoldBoundary() {
        val direct = SourceContract.read("host/DirectSurfaceProgramApi33.kt")
        assertTrue(direct.contains("c.sampling.fadeCurve==LumenSurfaceFadeCurve.LINEAR"))
        assertTrue(direct.contains("uniform float fadeLinear;"))
        assertTrue(direct.contains("if(q<=fade.y)dissolve=1.0;"))
        assertTrue(direct.contains("fadeLinear>0.5?t:t*t*(3.0-2.0*t)"))
    }
}
