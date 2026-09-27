package com.lumen.coacervation.engine

import org.junit.Assert.*
import org.junit.Test
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before

class ModernMaterialIntegrationTest {
    private fun source(path: String): String = SourceContract.read("src/main/java/com/lumen/coacervation/engine/$path")

    /** 授权前可用的窗口背景只用配色：不创建会话、不读材质偏好、不做位图工作。配色本身由宿主提供，引擎不取色。 */
    @Test fun neutralWindowBackgroundNeverTouchesTheSessionOrPreferences() {
        val delegate = source("host/LumenActivityDelegate.kt")
        val neutral = delegate.after("public fun neutralWindowBackground()").before("// ---------------- 控件")
        assertTrue(neutral.contains("ModernMaterialDrawables.neutralWindow(palette)"))
        assertFalse(neutral.contains("prepare"))
        assertFalse(neutral.contains("SkinPrefs"))
        val palette = source("model/LumenPalette.kt")
        assertFalse("引擎不取色：配色类型不得依赖壁纸或取色库", palette.contains("WallpaperManager") || palette.contains("m3color"))
    }

    @Test fun materialUsesOneSharedPreblurredUnderlayWithoutTakingLiquidOwnership() {
        val renderer = source("material/FrostedMaterialRenderer.kt")
        assertTrue(renderer.contains("ModernBackdropBlur.blur(pixels"))
        assertTrue(renderer.contains("BitmapShader(result.blurred"))
        assertTrue(renderer.contains("worker.submit"))
        assertTrue(renderer.contains("if (!lifecycle.accepts(token)) return"))
        assertTrue(renderer.contains("failedWidth == newWidth && failedHeight == newHeight"))
        assertFalse(renderer.contains("PixelCopy"))
        assertFalse(renderer.contains("claimLiquidRenderSession"))
        assertFalse(renderer.contains(".recycle()"))
        val session = source("runtime/ActivitySkinSession.kt")
        assertTrue(session.contains("if (requestedSkin != SkinId.LIQUID) return materialRenderer.bindRoot(root)"))
        assertTrue(session.contains("val owner = if (requestedSkin == SkinId.LIQUID)"))
        assertTrue(session.contains("materialRenderer.close()"))
    }

    @Test fun chromeRolesRemainDistinctAndGeometryRemainsCallerOwned() {
        val delegate = source("host/LumenActivityDelegate.kt")
        assertTrue(delegate.contains("surface(color, radiusDp, SurfaceRole.FLOATING)"))
        assertTrue(delegate.contains("surface(color, radiusDp, SurfaceRole.TOP_BAR)"))
        assertTrue(delegate.contains("surface(color, radiusDp, SurfaceRole.SELECTED_ITEM)"))
        val renderer = source("material/FrostedMaterialRenderer.kt")
        assertTrue(renderer.contains("radiusDp * density"))
        assertTrue(renderer.contains("observer.addOnScrollChangedListener(scroll)"))
        assertTrue(renderer.contains("observer.removeOnScrollChangedListener(state.scroll)"))
        assertTrue(renderer.contains("observer.addOnPreDrawListener(preDraw)"))
        assertTrue(renderer.contains("observer.removeOnPreDrawListener(state.preDraw)"))
        assertTrue(renderer.contains("observer.addOnDrawListener(draw)"))
        assertTrue(renderer.contains("observer.removeOnDrawListener(state.draw)"))
        // 登记与批量比较须用同一矩阵乘法次序，避免浮点结合差异造成静止表面反复失效。
        val registration = renderer.after("internal fun register(").before("private fun registerRefreshWindow")
        val refresh = renderer.after("private fun flushPositionChanges").before("fun releaseMemory")
        assertTrue(registration.contains("samplingMatrices.withAncestorMemo"))
        assertTrue(refresh.contains("samplingMatrices.withAncestorMemo"))
        assertTrue(renderer.contains("ValueAnimator.areAnimatorsEnabled()"))
    }
}
