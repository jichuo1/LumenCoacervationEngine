package com.lumen.coacervation.engine.interaction

import com.lumen.coacervation.engine.contract.MotionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElasticGlowTuningTest {
    @Test fun unitMultipliersReturnTheOriginalValuesBitForBit() {
        for (alpha in 0..255) assertEquals(alpha, ElasticGlowTuning.baseAlpha(alpha, 1f))
        for (radius in listOf(0f, 33.6f, 252f, 1e6f)) assertEquals(radius, ElasticGlowTuning.radius(radius, 1f), 0f)
    }

    @Test fun intensityScalesTheBaseAlphaWithinAByte() {
        assertEquals(0, ElasticGlowTuning.baseAlpha(32, 0f))
        assertEquals(16, ElasticGlowTuning.baseAlpha(32, .5f))
        assertEquals(128, ElasticGlowTuning.baseAlpha(32, 4f))
        assertEquals(255, ElasticGlowTuning.baseAlpha(200, 4f))
        assertEquals(84, ElasticGlowTuning.baseAlpha(56, 1.5f))
    }

    @Test fun radiusScalesLinearly() {
        assertEquals(50f, ElasticGlowTuning.radius(100f, .5f), 0f)
        assertEquals(200f, ElasticGlowTuning.radius(100f, 2f), 0f)
    }

    /**
     * 倍率每次长按开始时读取（设置页滑块可即时预览），并真正进入光晕的半径与基准 alpha；
     * 两个窗口入口（Activity、弹窗）都把同一个来源传给控制器。空白折叠后比对。
     */
    @Test fun tuningIsReadPerPressAndReachesTheGlow() {
        fun flat(text: String) = text.replace(Regex("\\s+"), " ")
        val controller = flat(MotionSource.file("ElasticInteractionController"))
        assertTrue(controller.contains("highlight = effectTuning().let { tuning ->"))
        assertTrue(controller.contains("TouchHighlight(view, density, highlightColor, tuning.dragGlowIntensity, tuning.dragGlowRadius)"))
        assertTrue(controller.contains("ElasticGlowTuning.radius(maxOf(width, height) * .7f, glowRadiusScale)"))
        assertTrue(controller.contains("ElasticGlowTuning.baseAlpha(HIGHLIGHT_BASE_ALPHA, glowIntensity)"))
        assertTrue(controller.contains("state.update(frame, dt, radius, baseAlpha, config)"))
        val interaction = flat(MotionSource.file("LumenElasticInteraction"))
        assertTrue(interaction.contains("private val effectTuning: () -> LumenEffectTuning = { lumen.effectTuning }"))
        assertEquals(2, Regex("effectTuning = effectTuning").findAll(interaction).count())
    }
}
