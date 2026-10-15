package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.fluent.MicaPolicy
import com.lumen.coacervation.engine.host.LumenMicaFallbackReason as Reason
import com.lumen.coacervation.engine.host.LumenMicaKind as Kind
import com.lumen.coacervation.engine.host.LumenMicaLayer as Layer
import com.lumen.coacervation.engine.host.LumenMicaOptions
import org.junit.Assert.*
import org.junit.Test

class MicaPolicyTest {
    @Test fun snapshotAndPreparationHaveHardBudgetsEvenForExtremeAspectRatios() {
        for (w in listOf(1, 2, 95, 96, 1080, 4096, Int.MAX_VALUE)) {
            for (h in listOf(1, 2, 96, 1920, 8192, Int.MAX_VALUE)) {
                val (width, height) = MicaPolicy.sampleSize(w, h)
                assertTrue(width in 1..96 && height in 1..96)
                assertTrue(width.toLong() * height * 4 <= MicaPolicy.MAX_BITMAP_BYTES)
            }
        }
        assertEquals(36 * 1024, MicaPolicy.MAX_BITMAP_BYTES)
        assertEquals(144 * 1024, MicaPolicy.MAX_PREPARATION_BYTES)
        assertEquals(54 to 96, MicaPolicy.sampleSize(1080, 1920))
        assertEquals(1 to 1, MicaPolicy.sampleSize(1, 1))
    }

    @Test fun invalidDimensionsAndNonFiniteOpacitiesAreRejected() {
        for (size in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { MicaPolicy.sampleSize(size, 20) }
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) { LumenMicaOptions(tintOpacity = value) }
            assertThrows(IllegalArgumentException::class.java) { LumenMicaOptions(luminosityOpacity = value) }
        }
    }

    @Test fun fallbackColoursMatchWinUiBaseAndBaseAltTokens() {
        assertEquals(0xFFF3F3F3.toInt(), recipe(false, Kind.BASE).fallback)
        assertEquals(0xFFDADADA.toInt(), recipe(false, Kind.BASE_ALT).fallback)
        assertEquals(0xFF202020.toInt(), recipe(true, Kind.BASE).fallback)
        assertEquals(0xFF0A0A0A.toInt(), recipe(true, Kind.BASE_ALT).fallback)
        assertEquals(0xFF123456.toInt(), MicaPolicy.resolve(LumenMicaOptions(fallbackColor = 0x00123456), false, 0).fallback)
        assertEquals(0xFFABCDEF.toInt(), MicaPolicy.resolve(LumenMicaOptions(highContrast = true,
            fallbackColor = 0xFF123456.toInt()), false, 0x00ABCDEF).fallback)
    }

    @Test fun layerTokensMatchTheWinUiThemeDictionaries() {
        for (kind in Kind.entries) {
            for (layer in listOf(Layer.CONTENT, Layer.CARD)) {
                assertEquals(0x80FFFFFF.toInt(), MicaPolicy.layerColor(layer, kind, false))
                assertEquals(0x4C3A3A3A, MicaPolicy.layerColor(layer, kind, true))
            }
        }
        assertEquals(0, MicaPolicy.layerColor(Layer.COMMAND, Kind.BASE, false))
        assertEquals(0xB3FFFFFF.toInt(), MicaPolicy.layerColor(Layer.COMMAND, Kind.BASE_ALT, false))
        assertEquals(0x733A3A3A, MicaPolicy.layerColor(Layer.COMMAND, Kind.BASE_ALT, true))
    }

    @Test fun altRetainsMoreWallpaperColourInBothThemes() {
        for (dark in listOf(false, true)) {
            val base = MicaPolicy.materialPixel(0xFFFF0000.toInt(), recipe(dark, Kind.BASE))
            val alt = MicaPolicy.materialPixel(0xFFFF0000.toInt(), recipe(dark, Kind.BASE_ALT))
            fun chroma(c: Int) = (c shr 16 and 255) - (c and 255)
            assertTrue(chroma(alt) > chroma(base))
        }
    }

    @Test fun fullTintAndZeroTintHavePredictableEndpoints() {
        val options = LumenMicaOptions(tintColor = 0x00123456, tintOpacity = 1f, luminosityOpacity = 0f)
        val full = MicaPolicy.resolve(options, false, 0)
        assertEquals(0xFF123456.toInt(), MicaPolicy.materialPixel(0xFFFF0000.toInt(), full))
        val none = MicaPolicy.resolve(options.copy(tintOpacity = 0f), false, 0)
        assertEquals(0xFF102030.toInt(), MicaPolicy.materialPixel(0x00102030, none))
    }

    @Test fun fallbackCustomizationDoesNotRecolourTheActiveMaterial() {
        for (dark in listOf(false, true)) for (kind in Kind.entries) {
            val options = LumenMicaOptions(kind = kind)
            val original = MicaPolicy.resolve(options, dark, 0)
            val changed = MicaPolicy.resolve(options.copy(fallbackColor = 0xFFFF0000.toInt()), dark, 0)
            assertNotEquals(original.fallback, changed.fallback)
            for (pixel in listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
                assertEquals(MicaPolicy.materialPixel(pixel, original), MicaPolicy.materialPixel(pixel, changed))
        }
    }

    @Test fun allOutputsAreOpaqueAndBounded() {
        for (dark in listOf(false, true)) for (kind in Kind.entries) {
            for (r in 0..255 step 17) for (g in 0..255 step 31) for (b in 0..255 step 47) {
                val result = MicaPolicy.materialPixel((r shl 16) or (g shl 8) or b, recipe(dark, kind))
                assertEquals(255, result ushr 24)
            }
        }
    }

    @Test fun fallbackPriorityHonoursAccessibilityBeforePowerAndFocus() {
        val defaults = LumenMicaOptions()
        fun reason(o: LumenMicaOptions, low: Boolean = true, power: Boolean = true, inactive: Boolean = true,
            image: Boolean = false, memory: Boolean = true) = MicaPolicy.fallback(o, low, power, inactive, image, memory)
        assertEquals(Reason.DISABLED, reason(defaults.copy(enabled = false, highContrast = true)))
        assertEquals(Reason.HIGH_CONTRAST, reason(defaults.copy(highContrast = true, reduceTransparency = true)))
        assertEquals(Reason.REDUCE_TRANSPARENCY, reason(defaults.copy(reduceTransparency = true)))
        assertEquals(Reason.LOW_RAM_DEVICE, reason(defaults))
        assertEquals(Reason.POWER_SAVE, reason(defaults, low = false))
        assertEquals(Reason.MEMORY_PRESSURE, reason(defaults, low = false, power = false))
        assertEquals(Reason.WINDOW_INACTIVE, reason(defaults, low = false, power = false, memory = false))
        assertEquals(Reason.NO_BACKGROUND, reason(defaults, low = false, power = false, inactive = false, memory = false))
        assertEquals(Reason.NONE, reason(defaults, low = false, power = false, inactive = false, image = true, memory = false))
        assertEquals(Reason.NONE, reason(defaults.copy(fallbackOnLowRamDevice = false, fallbackOnPowerSave = false,
            fallbackWhenWindowInactive = false), image = true, memory = false))
    }

    private fun recipe(dark: Boolean, kind: Kind) = MicaPolicy.resolve(LumenMicaOptions(kind = kind), dark, 0)
}
