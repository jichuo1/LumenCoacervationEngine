package com.lumen.coacervation.sample

import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceTintContinuityTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()

    @Test fun tintUpdatesKeepReadyFramesWhileGeometryChangesStillRebuild() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            val modes = mutableListOf(LumenSurfaceMaterial.FROSTED to LumenSurfaceBackend.SOFTWARE)
            if (Build.VERSION.SDK_INT >= 31) {
                modes += LumenSurfaceMaterial.FROSTED to LumenSurfaceBackend.GPU
                modes += LumenSurfaceMaterial.LIQUID to LumenSurfaceBackend.GPU
            }
            for ((material, backend) in modes) {
                val options = LumenSurfaceOptions(material = material, color = Color.BLACK,
                    radiusDp = 18f, tintOpacity = .1f, fallbackTintOpacity = .8f,
                    sampling = LumenSurfaceSampling(backend = if (backend == LumenSurfaceBackend.GPU)
                        LumenSurfaceBackend.AUTO else backend, minIntervalMs = 0))
                scenario.onActivity { it.binding.update(options) }
                val deadline = SystemClock.uptimeMillis() + 7000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { ready = it.binding.diagnostics()?.backend == backend }
                    if (!ready) SystemClock.sleep(25)
                }
                assertTrue("$material/$backend never became ready", ready)
                scenario.onActivity { activity ->
                    val recordings = activity.session.diagnostics().contentRecordings
                    val background = activity.glass.background
                    for (step in 1..12) {
                        activity.binding.update(options.copy(tintOpacity = .1f + step / 100f,
                            fallbackTintOpacity = .8f + step / 100f))
                        assertEquals("Tint discarded $material/$backend", backend, activity.binding.diagnostics()?.backend)
                        assertSame(background, activity.glass.background)
                        assertEquals(recordings, activity.session.diagnostics().contentRecordings)
                    }
                    activity.binding.update(options.copy(radiusDp = 30f))
                    assertEquals(LumenSurfaceBackend.STATIC, activity.binding.diagnostics()?.backend)
                    assertEquals(LumenSurfaceFailure.FRAME_PENDING, activity.binding.diagnostics()?.failure)
                }
            }
        }
    }
}
