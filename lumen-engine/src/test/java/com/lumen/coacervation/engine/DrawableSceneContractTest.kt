package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawableSceneContractTest {
    @Test fun commonEntryPointKeepsApi29TypesBehindTheVersionGuard() {
        val common = SourceContract.read("host/LumenDrawableScene.kt")
        val hardware = SourceContract.read("host/DrawableSceneApi29.kt")
        assertFalse(common.contains("import android.graphics.RenderNode"))
        assertFalse(common.contains("drawRenderNode("))
        assertTrue(common.contains("hardware && Build.VERSION.SDK_INT >= 29"))
        assertTrue(hardware.contains("@RequiresApi(29)"))
    }
}
