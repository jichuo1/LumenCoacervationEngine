package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.liquid.LiquidEffectProfile
import com.lumen.coacervation.engine.liquid.LiquidGlassFootprintPolicy
import com.lumen.coacervation.engine.liquid.LiquidTokenResolver
import com.lumen.coacervation.engine.liquid.LiquidVisualTuningPolicy
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenEffectTuningPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 玻璃采样区域的纯策略护栏；像素级证明在设备测试 `LiquidGlassFootprintInstrumentedTest`。 */
class LiquidGlassFootprintPolicyTest {
    @Test fun interiorLensPeakBoundsRTimesOneMinusRSquared() {
        var peak = 0.0
        for (i in 0..100_000) { val r = i / 100_000.0; peak = maxOf(peak, r * (1 - r * r)) }
        assertTrue("peak $peak", LiquidGlassFootprintPolicy.INTERIOR_LENS_PEAK >= peak)
        assertTrue(LiquidGlassFootprintPolicy.INTERIOR_LENS_PEAK - peak < 0.001)
    }

    /** 上限必须与后端对 opticalIntensity 的夹取一致：后端放宽而这里不改，区域就会漏掉位移。 */
    @Test fun opticalIntensityCapMatchesTheBackendClamp() {
        val backend = SourceContract.read("liquid/LiquidRefractionBackendApi33.kt")
        assertTrue(backend.contains("setFloatUniform(\"opticalIntensity\", opticalIntensity.coerceIn(1f, 1.85f))"))
        assertEquals(1.85f, LiquidGlassFootprintPolicy.MAX_OPTICAL_INTENSITY)
    }

    @Test fun maxOffsetCoversEveryShaderDisplacementTerm() {
        for (profile in LiquidEffectProfile.entries) {
            val p = LumenEffectTuningPolicy.applyTo(
                LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark = false), profile, dark = false),
                LumenEffectTuning.DEFAULT)
            val density = 3f
            val expected = 1.85f * density * (p.refractionAmountDp + 0.385f * p.interiorDistortionDp +
                maxOf(p.chromaticShiftDp, p.scatteringRadiusDp))
            assertEquals(expected, LiquidGlassFootprintPolicy.maxOffsetPx(p, density), 1e-4f)
            assertTrue(LiquidGlassFootprintPolicy.maxOffsetPx(p, density) >= 1.85f * density * p.refractionAmountDp)
        }
    }

    @Test fun bitmapRectAddsFilterTexelsAndClampsToTheBitmap() {
        val out = IntArray(8)
        LiquidGlassFootprintPolicy.toBitmapRect(100.2f, 50.7f, 300.1f, 120.0f, 0.5f, 0.5f, 500, 500, out, 0)
        assertArrayEquals(intArrayOf(48, 23, 153, 62), out.copyOf(4))
        LiquidGlassFootprintPolicy.toBitmapRect(-80f, -80f, 2000f, 3000f, 0.62f, 0.62f, 671, 1491, out, 4)
        assertArrayEquals(intArrayOf(0, 0, 671, 1491), out.copyOfRange(4, 8))
    }
}
