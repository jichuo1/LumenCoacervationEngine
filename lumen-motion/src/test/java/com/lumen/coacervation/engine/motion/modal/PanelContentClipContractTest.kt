package com.lumen.coacervation.engine.motion.modal

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelContentClipContractTest {
    @Test fun modalScrollsEnterTheSharedTypedStretchPathBeforePresentation() {
        val present = MotionSource.file("LumenModalPresenter")
            .after("styleContent(container)").before("val cornerRadiusPx")
        assertTrue(present.contains("LumenPanelScroll.prepare(container)"))
        assertTrue(present.contains("lumen.installStretch(target)"))
        val prepare = MotionSource.file("LumenPanelScroll")
        assertTrue(prepare.contains("root.parent is LiquidStretchViewport"))
        assertTrue(prepare.contains("root is NestedScrollingChild3"))
        val bounded = MotionSource.file("LumenMaxHeightScrollView")
        assertTrue(bounded.contains(": NestedScrollView(context)"))
        assertTrue(bounded.contains("isFillViewport = false"))
    }

    @Test fun allModalHostsPrepareEmbeddedClipsAtLayoutTime() {
        assertTrue(MotionSource.file("ModalCardRoot").contains("contentClip.applyToHierarchy(this)"))
        assertTrue(MotionSource.file("IconAnchoredMotionLayer").contains("embeddedClip.apply(it, surfaceRadiusPx)"))
        assertTrue(MotionSource.file("BubblePanelLayer").contains("embeddedClip.apply(content, cornerRadius)"))
        for (host in listOf("ModalCardRoot", "IconAnchoredMotionLayer", "BubblePanelLayer")) {
            val source = MotionSource.file(host)
            val draw = if (host == "IconAnchoredMotionLayer") source.after("override fun drawChild(").before("override fun onInterceptTouchEvent(")
                else source.after("override fun dispatchDraw(").before("super.dispatchDraw(canvas)")
            assertFalse(draw.contains("embeddedClip.apply") || draw.contains("contentClip.applyToHierarchy"))
        }
    }

    @Test fun clippingPreservesHostOutlinesAndNeverChangesTouchOrLayout() {
        val clip = MotionSource.file("PanelContentClip")
        assertTrue(clip.contains("if (view.clipToOutline || provider != null"))
        assertTrue(clip.contains("provider.followsPanel"))
        assertTrue(clip.contains("view.width, view.height"))
        assertFalse(clip.contains("setOnTouchListener") || clip.contains("layoutParams =") || clip.contains("saveLayer"))
    }
}
