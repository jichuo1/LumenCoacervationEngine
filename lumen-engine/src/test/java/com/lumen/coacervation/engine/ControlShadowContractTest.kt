package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
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

    @Test fun drawHasNoClipPathOrVectorRing() {
        val source = SourceContract.read("material/ControlShadowDrawable.kt")
        // 连续剖面取代了"裁剪 + 硬描边"：内侧淡出由纹理承担，绘制路径不得再切轮廓或画环，
        // 否则边缘会重新出现 2026-10-17 实测的 33.7/255 亮度阶跃。
        assertTrue(!source.contains("clipOutPath"))
        assertTrue(!source.contains("drawContactRing"))
        assertTrue(!source.contains("drawRoundRect"))
    }

    @Test fun drawKeepsTheShadowUnderTheContent() {
        val source = SourceContract.read("material/ControlShadowDrawable.kt")
        val draw = source.after("override fun draw(canvas: Canvas)")
        // 内侧不允许再压暗控件：阴影必须先于最后一次内容绘制，且内部不出现内缘裁剪。
        val contentDraw = draw.lastIndexOf("content?.draw(canvas)")
        val firstObtain = draw.indexOf("ControlShadowTextureCache.obtain")
        assertTrue("shadow must be drawn under the content", contentDraw > firstObtain)
    }

    @Test fun drawPathAllocatesNothingAndReusesCachedMetrics() {
        val source = SourceContract.read("material/ControlShadowDrawable.kt")
        // 两层度量都在构造时算一次：每帧新建 TextureMetrics 会让所有表面产生持续分配（ENGINEERING_RULES.md §5）。
        assertTrue(source.contains("private val castMetrics = ControlShadowPolicy.textureMetrics"))
        assertTrue(source.contains("private val contactMetrics = ControlShadowPolicy.textureMetrics"))
        val draw = source.after("override fun draw(canvas: Canvas)")
        assertTrue("draw must not rebuild metrics per frame",
            !draw.contains("ControlShadowPolicy.textureMetrics("))
        val slice = source.after("private fun drawNineSlice").before("// ---------------- 内容转发")
        assertTrue("drawNineSlice must not allocate stop arrays", !slice.contains("floatArrayOf("))
        assertTrue("drawNineSlice must not allocate stop arrays", !slice.contains("intArrayOf("))
    }

    @Test fun cacheBakesThePolicyProfileWithoutBlur() {
        val source = SourceContract.read("material/ControlShadowTextureCache.kt")
        // hub 以 WeakHashMap 保存监听器：内联 lambda 会在第一次 GC 后消失，而 registered 仍为 true，
        // 此后 releaseGraphics() 永远清不到缓存，"内存告急释放"的文档承诺失效。
        assertTrue(source.contains("private val memoryListener = LumenMemoryPressureListener"))
        assertTrue(source.after("private fun register()").contains("addListener(memoryListener)"))
        // 建图失败（含内存不足）只关闭投射层，不得冒泡到宿主 draw。
        assertTrue(source.after("fun obtain(metrics:")
            .contains("runCatching { render(metrics) }"))
        // 剖面由策略层按有符号距离烘焙；不得退回模糊位图。
        assertTrue(source.contains("ControlShadowPolicy.buildAlphaLut"))
        assertTrue(!source.contains("ModernBackdropBlur"))
        // 记账必须发生在插入之前：removeEldestEntry 在 put 内部调用，否则预算检查漏掉新条目。
        assertTrue(source.after("totalBytes += bitmap.byteCount")
            .trimStart().startsWith("entries[id] = bitmap"))
    }
}
