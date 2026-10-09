package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.widget.NavigationBarMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 排布上的硬编码（适配标准 §15）：数量上限、窗口尺寸、容器类型、字形与方向。 */
class LayoutLimitContractTest {

    @Test fun pagerHasNoPageCountCap() {
        val pager = MotionSource.file("LumenPagePager")
        assertFalse(pager.contains("require(childCount"))
        val chain = MotionSource.file("PageTextChain")
        assertTrue(chain.contains("private val items = HashMap<Int, List<Item>>()"))
        assertTrue(chain.contains("for (index in 0 until pager.childCount) {"))
        assertFalse(MotionSource.all().contains("MAX_PAGES"))
    }

    @Test fun navigationControlsAcceptMoreItemsAndFailLoudlyBeyondTheLimit() {
        assertEquals(6, NavigationBarMotion.MAX_ITEMS)
        assertEquals(8, NavigationBarMotion.MAX_SEGMENTS)
        // 5 项、6 项底栏的命中与指示位置（含从右到左）。
        assertEquals(4, NavigationBarMotion.indexAt(390f, 400f, 4f, 5, false))
        assertEquals(0, NavigationBarMotion.indexAt(390f, 400f, 4f, 5, true))
        assertEquals(5, NavigationBarMotion.nearestPage(7f, 6))
        assertEquals(1.5f, NavigationBarMotion.physicalSlot(3.5f, 6, true), 1e-6f)
        assertEquals(7f, NavigationBarMotion.position(9f, 8), 0f)
        // 档位条原来把段数静默截到 4：第 5 段起直接丢失。现在超出上限明确报错。
        val scrub = MotionSource.file("LumenSegmentScrubBar")
        assertFalse(scrub.contains("coerceIn(0, NavigationBarMotion.MAX_ITEMS)"))
        assertTrue(scrub.contains("require(labels.size in 1..NavigationBarMotion.MAX_SEGMENTS)"))
        assertTrue(scrub.contains("renderedTint = IntArray(count) { -1 }"))
        // 底栏默认宽度随项数放宽。
        assertTrue(MotionSource.file("LumenNavigationBar").contains("dp(maxOf(320f, count * 72f))"))
    }

    @Test fun bubbleIsPlacedInTheActivityWindowNotTheWholeScreen() {
        val present = MotionSource.function("present")
        val placement = present.after("val bubblePlacement = if (").before("val originalPaddingTop")
        assertTrue(placement.contains("val decor = activity.window.decorView"))
        assertTrue(placement.contains("decor::getLocationOnScreen"))
        assertFalse(placement.contains("displayMetrics.widthPixels.toFloat()"))
    }

    @Test fun modalTitleAndWidthAreNotTiedToThePhoneLayout() {
        val presenter = MotionSource.file("LumenModalPresenter")
        assertTrue(presenter.contains("titleView ?: (container.getChildAt(0) as? TextView)"))
        assertTrue(presenter.contains("public fun createContainer(): LinearLayout = ModalContainer(activity, (style.maxWidthDp * density).toInt())"))
        val container = presenter.after("private class ModalContainer(").before("super.onMeasure(capped, heightMeasureSpec)")
        // 确定宽度（preferredWidth、覆盖式子面板、气泡）一律照办，只给 WRAP/无约束封顶。
        assertTrue(container.contains("mode != View.MeasureSpec.EXACTLY"))
    }

    @Test fun revealWorksInAnyVerticalScrollerAndUsesVisibleCoordinatesSideways() {
        val reveal = MotionSource.file("LumenReveal")
        assertTrue(reveal.contains("val absolute = scrollView is android.widget.ScrollView || scrollView is NestedScrollView"))
        assertTrue(reveal.contains("val remaining = rect.top - scrollView.scrollY - topOffsetPx"))
        assertTrue(reveal.contains("(remaining > 0 && !scrollView.canScrollVertically(1))"))
        assertTrue(reveal.contains("host.scrollBy(0, next - applied)"))
        // 横向：内容坐标换成可见坐标（HorizontalScrollView 已滚动时，上一轮的实现会滚错距离）。
        assertTrue(reveal.contains("rect.offset(-host.scrollX, 0)"))
    }

    /** 2026-09-28 真机：目标标题带 8dp 内边距，飞行标题按 View 边框落点，结束时横跳 32px。 */
    @Test fun morphTitleLandsOnTheTextNotOnTheViewBounds() {
        val origin = MotionSource.file("ContainerMorphOrigin")
        val snapshot = origin.after("fun snapshot(").before("private fun View.boundsOnScreen()")
        assertTrue(snapshot.contains("title.boundsWithin(sourceWindowGroup)?.toTextBounds(title)"))
        assertTrue(snapshot.contains("title.boundsOnScreen().toTextBounds(title)"))
        val toText = origin.after("fun MotionRect.toTextBounds(").before("fun View.boundsWithin(")
        assertTrue(toText.contains("left + title.totalPaddingLeft + lineLeft"))
        assertTrue(toText.contains("top + title.totalPaddingTop"))
        val controller = MotionSource.file("ContainerMorphController")
        assertTrue(controller.contains("title.boundsWithin(host)?.toTextBounds(title)"))
        val host = MotionSource.file("ContainerMorphHost")
        assertTrue(host.contains("transitionTitle.includeFontPadding = toolbarTitle.includeFontPadding"))
    }

    @Test fun morphTitleAndBadgeFollowTheHostsTypeAndDirection() {
        val host = MotionSource.file("ContainerMorphHost")
        val replace = host.after("fun replacePage(").before("fun prepareFirstFrameForEntry()")
        assertTrue(replace.contains("transitionTitle.typeface = toolbarTitle.typeface"))
        val micro = MotionSource.file("MicroMotion")
        assertTrue(micro.contains("val pivotAtLeft = rtl == growFromEnd"))
        assertTrue(micro.contains("badge.pivotX = if (pivotAtLeft) 0f else badge.width.toFloat()"))
    }
}
