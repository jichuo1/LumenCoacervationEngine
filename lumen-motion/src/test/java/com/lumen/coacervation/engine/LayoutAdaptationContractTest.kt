package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 尺寸与排布适配的接线（适配标准 §14）：纯几何由 LayoutAdaptationPolicyTest 覆盖，这里守住"真的用上了"。 */
class LayoutAdaptationContractTest {

    @Test fun elasticTravelIsBoundedByNeighborsCapturedOnce() {
        val controller = MotionSource.file("ElasticInteractionController")
        val capture = controller.after("private fun captureGroupGaps(group: View) {").before("private fun validGeometry()")
        assertTrue(capture.contains("ElasticMotionGroupPolicy.neighborGaps("))
        assertTrue(capture.contains("ElasticMotionGroupPolicy.travelBound("))
        // 兄弟位置取可见位置（含平移）：手风琴、文字链正在移动的兄弟按它们现在的样子算。
        assertTrue(capture.contains("sibling.left + sibling.translationX"))
        // 只在按下时算一次：拖动帧里不遍历兄弟。
        val drag = controller.after("private fun dragTo(").before("private fun animateFrame(")
        assertFalse(drag.contains("neighborGaps"))
        assertTrue(drag.contains("drag.x, drag.y, gapLeft, gapTop, gapRight, gapBottom, drag)"))
    }

    @Test fun morphsStartFromTheSourcesOwnCorner() {
        val presenter = MotionSource.file("LumenModalPresenter")
        assertTrue(presenter.contains("MorphCornerPolicy.collapsedRadius(anchorDeclaredRadius, collapsed.width, collapsed.height)"))
        assertTrue(presenter.contains("liveAnchor?.declaredCornerRadius() ?: anchorCornerRadiusPx"))
        val origin = MotionSource.file("ContainerMorphOrigin")
        assertTrue(origin.contains("entryCornerRadiusPx = entry.declaredCornerRadius()"))
        assertTrue(origin.contains("entryCornerRadiusPx = intent.getFloatExtra(EXTRA_ENTRY_CORNER_RADIUS, Float.NaN)"))
        val controller = MotionSource.file("ContainerMorphController")
        assertTrue(controller.contains("origin.entryCornerRadiusPx.takeIf { it.isFinite() } ?: (collapsedCornerRadiusDp * density)"))
        // 声明的直角与"没有声明"不同：背景、前景都报不出才是 NaN。
        val corners = MotionSource.file("MorphCorners")
        assertTrue(corners.contains("for (drawable in listOf(background, foreground))"))
    }

    @Test fun accordionFollowersDependOnTheArrangement() {
        val controller = MotionSource.file("SectionExpansionController")
        val capture = controller.after("private fun captureActors()").before("private fun clipLayer(")
        assertTrue(capture.contains("parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL ->"))
        assertTrue(capture.contains("isRowContainer(parent) ->"))
        assertTrue(capture.contains("ExpansionFollowPolicy.sharesColumn("))
        assertTrue(controller.contains("parent is android.widget.GridLayout || parent is android.widget.TableLayout"))
        // 竖向列表不经过任何换算：来源工程的行为逐字不变。
        assertTrue(controller.contains("for (i in 0 until minOf(levels, rowLevels.size))"))
    }

    @Test fun revealWaitsForTheHorizontalStepBeforeHighlighting() {
        val reveal = MotionSource.file("LumenReveal")
        val body = reveal.after("public fun reveal(").before("public fun cancel()")
        assertTrue(body.contains("val horizontal = startHorizontalReveal(scrollView, target)"))
        assertTrue(body.contains("whenSettled(horizontal) {"))
        assertTrue(body.contains("horizontal?.cancel()"))
        assertTrue(reveal.contains("node is HorizontalScrollView || node.canScrollHorizontally(1) || node.canScrollHorizontally(-1)"))
    }
}
