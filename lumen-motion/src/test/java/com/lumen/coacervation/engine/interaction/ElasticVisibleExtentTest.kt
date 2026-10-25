package com.lumen.coacervation.engine.interaction

import org.junit.Assert.*
import org.junit.Test

/** 展开后高过视口的首级手风琴：可见那一段必须与同尺寸的完整卡片形变一致。 */
class ElasticVisibleExtentTest {
    private val cap = 10f // STRETCH_CAP_DP × density 4
    private val limit = 72f // positionLimit 在 density 4 的上限
    private val fitted = ElasticVector()

    @Test fun `fully visible axes keep the exact capped scale`() {
        for (size in listOf(48, 300, 1824, 11568)) for (base in listOf(.975f, .984f, 1f, 1.0092f, 1.019f, 1.2f)) {
            val old = ElasticMotionGroupPolicy.cappedScale(base, size, cap)
            for (extent in listOf(size.toFloat(), size + 1f, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
                assertEquals(old, ElasticVisibleExtent.scale(base, size, extent, cap), 0f)
            }
        }
        assertEquals(0f, ElasticVisibleExtent.pivotShift(150f, 150f, .984f), 0f)
    }

    @Test fun `a visible span is capped like a standalone card of that span`() {
        for (base in listOf(.975f, .984f, 1.0059f, 1.0092f, 1.019f)) {
            assertEquals(ElasticMotionGroupPolicy.cappedScale(base, 1700, cap),
                ElasticVisibleExtent.scale(base, 11568, 1700f, cap), 0f)
        }
    }

    @Test fun `only edges no reachable pose can reveal switch to the visible span`() {
        // HeldRowClipBoundsTest：只被视口切掉十几像素的行保持原有"不再外溢"契约。
        assertFalse(ElasticVisibleExtent.unreachable(16f, 64f, 12.8f, 1f))
        assertFalse(ElasticVisibleExtent.unreachable(96f, 640f, limit, 1f))
        assertTrue(ElasticVisibleExtent.unreachable(9868f, 1700f, limit, 1f))
        assertFalse(ElasticVisibleExtent.unreachable(9868f, 1700f, limit, 40f))
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(ElasticVisibleExtent.unreachable(bad, 1700f, limit, 1f))
            assertFalse(ElasticVisibleExtent.unreachable(9868f, bad, limit, 1f))
        }
        val open = Float.POSITIVE_INFINITY
        assertEquals(-12f, ElasticVisibleExtent.retainedGap(-12f, open, false), 0f)
        assertEquals(4f, ElasticVisibleExtent.retainedGap(4f, open, true), 0f)
        assertEquals(open, ElasticVisibleExtent.retainedGap(-9868f, open, true), 0f)
        // 远端 320px 宽窗口：整宽行被窗口切掉右侧，行自己的视口（间隙 0）仍须约束右缘。
        assertEquals(0f, ElasticVisibleExtent.retainedGap(-60f, 0f, true), 0f)
    }

    @Test fun `travel toward an unreachable hidden edge ignores neighbours beyond the viewport`() {
        // Sample 实测：首级下邻居远在屏幕外，默认邻居策略仍把向下行程压到 12dp - 2.5dp。
        assertEquals(limit, ElasticVisibleExtent.travelBound(38f, true, limit), 0f)
        assertEquals(38f, ElasticVisibleExtent.travelBound(38f, false, limit), 0f)
        assertEquals(96f, ElasticVisibleExtent.travelBound(96f, true, limit), 0f)
        assertEquals(0f, ElasticVisibleExtent.travelBound(0f, true, Float.NaN), 0f)
    }

    @Test fun `the old whole group pose reverses a tall header against the finger`() {
        val size = 11568f
        val pressed = .984f
        val stretched = ElasticMotionGroupPolicy.cappedScale(1.0092f, size.toInt(), cap)
        val headerMotion = 52f + (144f - size / 2) * (stretched - pressed)
        assertTrue("old header motion $headerMotion", headerMotion < 0f)
    }

    /** 真机 PMA110 的六组嵌套夹具：首级 11568px、可见段 1700px，标题在可见段顶端。 */
    @Test fun `the visible span of a tall card matches a standalone card pixel for pixel`() {
        val outer = pose(size = 11568, visible = 1700f)
        val standalone = pose(size = 1700, visible = 1700f)
        for (index in outer.indices) assertEquals("index=$index", standalone[index], outer[index], .01f)
        assertTrue("pressed sink=${outer[0]}", outer[0] in 0f..16f)
        assertTrue("drag follows the finger=${outer[1]}", outer[1] > 30f)
        assertTrue("visible stretch=${outer[2]}", outer[2] > 6f)
    }

    @Test fun `effective pivot fitting keeps the visible edges inside retained gaps`() {
        val size = 11568
        val realPivot = size / 2f
        val visible = 1700f
        val pivot = visible / 2
        for (translation in listOf(-52f, -1f, 0f, 1f, 52f)) for (base in listOf(.975f, .984f, 1.0092f)) {
            for (gap in listOf(0f, 8f, 40f, Float.POSITIVE_INFINITY)) {
                val scale = ElasticVisibleExtent.scale(base, size, visible, cap)
                ElasticClipGeometry.fitAxis(translation, scale, visible, pivot, gap, gap, fitted)
                val real = fitted.x + ElasticVisibleExtent.pivotShift(pivot, realPivot, fitted.y)
                val top = real + realPivot * (1f - fitted.y)
                val bottom = top + visible * fitted.y
                assertTrue("t=$translation top=$top gap=$gap", top >= -gap - .01f)
                assertTrue("t=$translation bottom=$bottom gap=$gap", bottom <= visible + gap + .01f)
                assertTrue("the visible centre never opposes the finger: t=$translation", fitted.x * translation >= 0f)
            }
        }
    }

    /**
     * 页面截断的首级卡片（可见段从组顶开始，底边无约束）。
     * [0] 按压时标题位移，[1] 拖动相对按压的标题位移，[2] 可见段相对按压的伸长量。
     */
    private fun pose(size: Int, visible: Float): FloatArray {
        val partial = visible < size
        val pivot = if (partial) visible / 2 else size / 2f
        val realPivot = size / 2f
        val anchor = 144f
        fun at(base: Float, translation: Float): FloatArray {
            val scale = ElasticVisibleExtent.scale(base, size, if (partial) visible else size.toFloat(), cap)
            ElasticClipGeometry.fitAxis(translation, scale, visible, pivot,
                Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, fitted)
            val t = fitted.x + ElasticVisibleExtent.pivotShift(pivot, realPivot, fitted.y)
            return floatArrayOf(t + realPivot * (1f - fitted.y) + anchor * fitted.y, fitted.y * visible)
        }
        val rest = at(1f, 0f)
        val pressed = at(.984f, 0f)
        val dragged = at(1.0092f, 52f)
        return floatArrayOf(pressed[0] - rest[0], dragged[0] - pressed[0], dragged[1] - pressed[1])
    }
}
