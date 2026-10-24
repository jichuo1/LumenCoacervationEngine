package com.lumen.coacervation.engine.interaction

import org.junit.Assert.*
import org.junit.Test

class ElasticPageCropTest {
    @Test fun `a tall source records only its preexisting bottom page crop`() {
        assertEquals(ElasticClipGeometry.CROP_BOTTOM, edges(80f, 60f, 1000f, 756f, 80f, 60f, 1000f, 700f))
        assertEquals(0, edges(80f, 60f, 1000f, 696f, 80f, 60f, 1000f, 696f))
        assertEquals(0, edges(80f, 60f, 1000f, 700f, 80f, 60f, 1000f, 700f))
    }

    @Test fun `an interior clip is not mistaken for the page edge`() {
        assertEquals(0, edges(80f, 60f, 1000f, 756f, 80f, 60f, 1000f, 640f))
        assertEquals(0, edges(-20f, -20f, 1100f, 720f, 10f, 10f, 1070f, 690f))
    }

    @Test fun `page crops are independent in all four directions before pixel rounding`() {
        assertEquals(ElasticClipGeometry.CROP_LEFT or ElasticClipGeometry.CROP_TOP or
            ElasticClipGeometry.CROP_RIGHT or ElasticClipGeometry.CROP_BOTTOM,
            edges(-20.25f, -30.5f, 1100.75f, 730.25f, 0f, 0f, 1080f, 700f))
        assertEquals(ElasticClipGeometry.CROP_TOP,
            edges(80f, -30f, 1000f, 660f, 80f, 0f, 1000f, 660f))
    }

    @Test fun `invalid or inconsistent rectangles cannot relax any edge`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0, edges(80f, 60f, 1000f, bad, 80f, 60f, 1000f, 700f))
        }
        assertEquals(0, edges(80f, 60f, 80f, 756f, 80f, 60f, 80f, 700f))
        assertEquals(0, edges(80f, 60f, 1000f, 756f, 70f, 60f, 1000f, 700f))
        assertEquals(0, edges(80f, 60f, 1000f, 756f, 80f, 60f, 1000f, 701f))
    }

    @Test fun `only redundant constraints yield while tighter outer clips survive`() {
        assertEquals(Float.POSITIVE_INFINITY, ElasticClipGeometry.retainedGap(0f, true), 0f)
        assertEquals(Float.POSITIVE_INFINITY, ElasticClipGeometry.retainedGap(24f, true), 0f)
        assertEquals(-12f, ElasticClipGeometry.retainedGap(-12f, true), 0f)
        assertEquals(0f, ElasticClipGeometry.retainedGap(0f, false), 0f)
        assertEquals(24f, ElasticClipGeometry.retainedGap(24f, false), 0f)
        assertEquals(-12f, minOf(-12f, ElasticClipGeometry.retainedGap(0f, true)), 0f)
    }

    @Test fun `an owned page crop restores positive travel without changing strict fitting`() {
        val out = ElasticVector()
        ElasticClipGeometry.fitAxis(14.56f, 1.003f, 640f, 348f, 60f,
            ElasticClipGeometry.retainedGap(0f, true), out)
        assertEquals(14.56f, out.x, 0f)
        assertEquals(1.003f, out.y, 0f)
        ElasticClipGeometry.fitAxis(14.56f, 1.003f, 640f, 348f, 60f,
            ElasticClipGeometry.retainedGap(0f, false), out)
        assertEquals(0f, out.x, 0f)
        assertEquals(1f, out.y, 0f)
    }

    private fun edges(left: Float, top: Float, right: Float, bottom: Float,
                      visibleLeft: Float, visibleTop: Float, visibleRight: Float, visibleBottom: Float): Int =
        ElasticClipGeometry.pageCropEdges(left, top, right, bottom,
            visibleLeft, visibleTop, visibleRight, visibleBottom, 1080f, 700f)
}
