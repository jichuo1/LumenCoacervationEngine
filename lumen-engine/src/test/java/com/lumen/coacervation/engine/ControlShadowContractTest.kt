package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阴影包裹层对 View 必须等价于裸背景：回调、状态、热点、轮廓都要转发，否则柔光采样注册
 * （ModernSurfaceDrawable 读 `callback as? View`）与长按高光裁剪（读 outline 圆角）会静默失效。
 */
class ControlShadowContractTest {

    @Test fun wrapperForwardsCallbackBoundsStateHotspotAndOutlineToContent() {
        val source = SourceContract.read("material/ControlShadowDrawable.kt")
        // setCallback 是 final 不能 override：回调必须在 draw 首帧惰性转发给内容。
        val draw = source.after("override fun draw(canvas: Canvas)")
        assertTrue(draw.contains("content.callback = callback"))
        val bounds = source.after("override fun onBoundsChange(bounds: Rect)")
        assertTrue(bounds.contains("content?.bounds = bounds"))
        val outline = source.after("override fun getOutline(outline: Outline)")
        assertTrue(outline.contains("content.getOutline(outline)"))
        val state = source.after("override fun onStateChange(state: IntArray)")
        assertTrue(state.contains("content.setState(state)"))
        val hotspot = source.after("override fun setHotspot(x: Float, y: Float)")
        assertTrue(hotspot.contains("content?.setHotspot(x, y)"))
    }

    @Test fun delegateSurfaceWrapsOnlyThroughThePublicEntry() {
        val source = SourceContract.read("host/LumenActivityDelegate.kt")
        val surface = source.after("public fun surface(color: Int, radiusDp: Float, role: SurfaceRole): Drawable {")
        assertTrue(surface.contains("LumenControlShadow.wrap(base, radiusDp, density, controlShadow, isDark)"))
    }

    @Test fun shadowDrawKeepsContactRingOutsideTheContent() {
        val source = SourceContract.read("material/ControlShadowDrawable.kt")
        // 接触环比内容先画且永不内侵：内侵的环会透过半透明表面读成脏边。
        val ring = source.after("private fun drawContactRing(canvas: Canvas, paint: Paint, innerOffset: Float)")
        assertTrue(ring.contains(".coerceAtLeast(paint.strokeWidth * .5f)"))
    }
}
