package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidVisualTuningPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.lumen.coacervation.engine.contract.SourceContract

class LiquidVisualTuningPolicyTest {

    /**
     * 未设自定义图时 Liquid 的自动 underlay 必须与标准磨砂皮肤共用 [AmbientBackdropScene]：
     * 两种材质下用户看到的是同一个 Monet 氛围背景。折射采样副本保持无颗粒。
     */
    @Test
    fun `auto backdrop shares the ambient scene with the frosted skin`() {
        val liquid = source("liquid/LiquidBackdropSource")
        val frosted = source("material/FrostedMaterialRenderer")

        assertTrue(liquid.contains("AmbientBackdropScene.paint(canvas, palette"))
        assertTrue(liquid.contains("AmbientBackdropScene.addGrain(pixels)"))
        // 颗粒只写进可见根位图；折射采样副本用加噪前的像素构建。
        assertTrue(liquid.contains("opticalBitmap = optical"))
        assertTrue(frosted.contains("AmbientBackdropScene.paint(canvas, palette"))
        assertTrue(frosted.contains("AmbientBackdropScene.addGrain(pixels)"))
    }

    @Test
    fun `saturation stays near the source palette`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.saturation in .94f..1f)
        }
    }

    @Test
    fun `gpu glass is lighter than translucent fallback`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.cardGlassAlpha < tuning.cardFallbackAlpha)
            assertTrue(tuning.modalGlassAlpha < tuning.modalFallbackAlpha)
            assertTrue(tuning.motionGlassAlpha < tuning.motionFallbackAlpha)
            assertTrue(tuning.cardGlassAlpha in .24f..0.34f)
            assertTrue(tuning.modalGlassAlpha in .4f..0.9f)
            assertTrue(tuning.cardFallbackAlpha < 0.8f)
            assertTrue(tuning.modalFallbackAlpha < 0.95f)
        }
    }

    @Test
    fun `modal remains more opaque than card`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.modalGlassAlpha > tuning.cardGlassAlpha)
            assertTrue(tuning.modalFallbackAlpha > tuning.cardFallbackAlpha)
            assertTrue(tuning.motionGlassAlpha > tuning.cardGlassAlpha)
            assertTrue(tuning.motionGlassAlpha <= tuning.modalGlassAlpha)
            assertTrue(tuning.motionFallbackAlpha > tuning.cardFallbackAlpha)
            assertTrue(tuning.motionFallbackAlpha < tuning.modalFallbackAlpha)
        }
    }

    private fun source(name: String): String = SourceContract.read("src/main/java/com/lumen/coacervation/engine/$name.kt")
}
