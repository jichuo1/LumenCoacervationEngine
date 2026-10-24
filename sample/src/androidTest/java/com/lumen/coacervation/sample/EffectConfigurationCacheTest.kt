package com.lumen.coacervation.sample

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.effects.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EffectConfigurationCacheTest {
    @Test fun cachesFollowAllFilmFieldsAndOnlyTheNormalizedGrainSeed() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var options = LumenEffectOptions(seed = 7)
                val layer = LumenEffectLayer(View(activity), options)
                try {
                    assertEquals(LumenEffectWorkDiagnostics(1, 1, 1), layer.workDiagnostics())
                    fun update(value: LumenEffectOptions) { options = value; layer.update(value) }
                    update(options.copy(procedural = options.procedural.copy(opacity = .9f, radiusDp = 30f,
                        kind = LumenProceduralKind.PAPER, paper = LumenPaperOptions(contrast = .8f)),
                        particles = LumenParticleOptions(enabled = true), framesPerSecond = 60))
                    assertEquals(LumenEffectWorkDiagnostics(2, 1, 1), layer.workDiagnostics())
                    val changes: List<(LumenFilmOptions) -> LumenFilmOptions> = listOf(
                        { it.copy(thicknessNm = 1000f) }, { it.copy(iridescence = .1f) },
                        { it.copy(roughness = .9f) }, { it.copy(angleDegrees = 20f) }, { it.copy(intensity = 1.5f) }
                    )
                    for (change in changes) {
                        val before = layer.workDiagnostics()
                        update(options.copy(procedural = options.procedural.copy(film = change(options.procedural.film))))
                        assertEquals(before.gradientBuilds + 1, layer.workDiagnostics().gradientBuilds)
                        assertEquals(before.grainRegenerations, layer.workDiagnostics().grainRegenerations)
                    }
                    val gradient = layer.workDiagnostics().gradientBuilds
                    update(options.copy(procedural = options.procedural.copy(color = 0xff123456.toInt())))
                    assertEquals(gradient + 1, layer.workDiagnostics().gradientBuilds)
                    update(options.copy(seed = 0))
                    val normalized = layer.workDiagnostics()
                    update(options.copy(seed = 1))
                    assertEquals(normalized.grainRegenerations, layer.workDiagnostics().grainRegenerations)
                    update(options.copy(seed = -1))
                    assertEquals(normalized.grainRegenerations + 1, layer.workDiagnostics().grainRegenerations)
                    assertEquals(gradient + 1, layer.workDiagnostics().gradientBuilds)
                } finally { layer.close() }
            }
        }
    }
}
