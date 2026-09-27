package com.lumen.coacervation.engine.touch

import com.lumen.coacervation.engine.contract.SourceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触摸光晕渲染路径的结构护栏。
 *
 * 拦的是会**静默退化**的写法（编译照过、界面照动、只有掉帧或跳变）：① 每帧重建 shader；③ 纯几何层被
 * android.graphics 污染（JVM 单测随之失效）。
 *
 * 来源工程里还断言了三个宿主控件（底栏、档位条、弹性控制器）不自建 shader、不离屏模糊、触点先换算到当前坐标系；
 * 那些是**宿主**的义务，独立引擎把它们写进 docs/INTEGRATION_STANDARD.md §7「触摸光晕」。
 */
class AdaptiveGlowRenderGuardTest {

    @Test fun geometryLayerStaysFreeOfAndroidGraphics() {
        val policy = SourceContract.read("touch/AdaptiveGlowPolicy.kt")
        val imports = policy.lineSequence().filter { it.startsWith("import ") }.toList()
        assertTrue("纯几何层不得 import android.graphics", imports.none { it.contains("android.graphics") })
        assertTrue("纯几何层不得 import Canvas/Paint/Shader",
            imports.none { it.contains(".Canvas") || it.contains(".Paint") || it.contains(".Shader") })
    }

    @Test fun rendererBuildsTheShaderExactlyOnceAndOutsideDraw() {
        val renderer = SourceContract.read("touch/TouchGlowRenderer.kt")
        assertEquals("shader 只准建一次", 1, Regex("RadialGradient\\(").findAll(renderer).count())
        val buildIndex = renderer.indexOf("RadialGradient(")
        val drawIndex = renderer.indexOf("fun draw(")
        assertTrue("构造点必须早于 draw", buildIndex in 0 until drawIndex)
        assertTrue("draw 内不得重建 shader", !renderer.substring(drawIndex).contains("RadialGradient("))
        // 几何圆必须包住前移后的渐变支持域：否则形变方向会被几何硬切出一道非零 alpha 直边
        assertTrue("draw 必须按 coreOffsetX 扩大几何圆",
            renderer.substring(drawIndex).contains("coreOffsetX"))
        assertTrue("draw 不得再用基准半径作几何",
            !renderer.substring(drawIndex).contains("drawCircle(0f, 0f, radius, paint)"))
    }
}
