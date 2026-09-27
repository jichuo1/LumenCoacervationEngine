package com.lumen.coacervation.engine.motion.morph

import com.lumen.coacervation.engine.motion.MotionRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTransitionMotionTest {
    private val entryBounds = MotionRect(72f, 360f, 1008f, 540f)
    private val windowBounds = MotionRect(0f, 0f, 1080f, 2160f)
    private val entryTitle = MotionRect(108f, 390f, 620f, 435f)
    private val toolbarTitle = MotionRect(174f, 18f, 900f, 66f)

    @Test
    fun diagnosticsEntryExpandsToWindowWithoutChangingTheSourceEndpoint() {
        val collapsed = frame(0f)
        val expanded = frame(1f)

        assertEquals(entryBounds, collapsed.bounds)
        assertEquals(entryTitle.left, collapsed.titleX, 0f)
        assertEquals(entryTitle.top, collapsed.titleY, 0f)
        assertEquals(0f, collapsed.contentAlpha, 0f)
        assertEquals(windowBounds, expanded.bounds)
        assertEquals(toolbarTitle.left, expanded.titleX, 0f)
        assertEquals(toolbarTitle.top, expanded.titleY, 0f)
        assertEquals(1f, expanded.contentAlpha, 0f)
    }

    @Test
    fun predictiveReturnKeepsContentVisibleLongerThanTimedClose() {
        val timed = frame(0.8f, ContainerMorphContentTiming.TIMED)
        val predictive = frame(0.8f, ContainerMorphContentTiming.PREDICTIVE)

        assertEquals(0f, timed.contentAlpha, 0f)
        assertEquals(1f, predictive.contentAlpha, 0f)
        assertTrue(predictive.contentTranslationYPx < timed.contentTranslationYPx)
    }

    @Test
    fun diagnosticSurfaceHandsOffOnlyAfterReachingTheSourceEndpoint() {
        assertEquals(
            0f,
            ContainerMorphSpec.transitionSurfaceAlpha(
                expansion = 0f,
                handoffExpansion = ContainerMorphEntrySpec.SURFACE_HANDOFF_EXPANSION
            ),
            0f
        )
        assertEquals(
            1f,
            ContainerMorphSpec.transitionSurfaceAlpha(
                expansion = ContainerMorphEntrySpec.SURFACE_HANDOFF_EXPANSION,
                handoffExpansion = ContainerMorphEntrySpec.SURFACE_HANDOFF_EXPANSION
            ),
            0f
        )
        assertEquals(
            0.5f,
            ContainerMorphSpec.transitionSurfaceAlpha(
                expansion = ContainerMorphEntrySpec.SURFACE_HANDOFF_EXPANSION / 2f,
                handoffExpansion = ContainerMorphEntrySpec.SURFACE_HANDOFF_EXPANSION
            ),
            0.0001f
        )
        assertEquals(1f, ContainerMorphSpec.collapsedChromeFraction(0f), 0f)
        assertEquals(0f, ContainerMorphSpec.collapsedChromeFraction(0.28f), 0f)
        assertEquals(12f, ContainerMorphEntrySpec.CORNER_RADIUS_DP, 0f)
        assertEquals(2f, ContainerMorphEntrySpec.STROKE_WIDTH_DP, 0f)
        assertTrue(ContainerMorphEntrySpec.scrimAlpha(darkTheme = false) > 0x38)
        assertTrue(ContainerMorphEntrySpec.scrimAlpha(darkTheme = true) > 0x38)
    }

    @Test
    fun sourceWindowCoordinatesAvoidCrossActivitySystemBarOffset() {
        val origin = transitionOrigin(
            localEntry = entryBounds,
            localTitle = entryTitle,
            sourceWindowTopOnScreen = 72f
        )

        val mapped = requireNotNull(
            ContainerMorphCoordinateMapper.map(
                origin = origin,
                destinationWindowWidth = 1080,
                destinationWindowHeight = 2160,
                destinationWindowLeftOnScreen = 0f,
                destinationWindowTopOnScreen = 24f,
                tolerancePx = 4f
            )
        )

        assertTrue(mapped.usedSourceWindowCoordinates)
        assertEquals(entryBounds, mapped.entryBounds)
        assertEquals(entryTitle, mapped.titleBounds)
    }

    @Test
    fun invalidSourceWindowCoordinatesUseValidatedScreenFallback() {
        val origin = transitionOrigin(
            localEntry = MotionRect(-400f, -300f, -200f, -100f),
            localTitle = MotionRect(-380f, -280f, -240f, -220f),
            sourceWindowTopOnScreen = 24f
        )

        val mapped = requireNotNull(
            ContainerMorphCoordinateMapper.map(
                origin = origin,
                destinationWindowWidth = 1080,
                destinationWindowHeight = 2160,
                destinationWindowLeftOnScreen = 0f,
                destinationWindowTopOnScreen = 24f,
                tolerancePx = 4f
            )
        )

        assertTrue(!mapped.usedSourceWindowCoordinates)
        assertEquals(entryBounds, mapped.entryBounds)
        assertEquals(entryTitle, mapped.titleBounds)
    }

    private fun frame(
        expansion: Float,
        timing: ContainerMorphContentTiming = ContainerMorphContentTiming.TIMED
    ): ContainerMorphFrame = ContainerMorphSpec.frame(
        expansion = expansion,
        collapsedBounds = entryBounds,
        expandedBounds = windowBounds,
        collapsedTitleBounds = entryTitle,
        expandedTitleBounds = toolbarTitle,
        collapsedTitleTextSizePx = 45f,
        expandedTitleTextSizePx = 51f,
        collapsedCornerRadiusPx = 45f,
        contentTravelPx = 36f,
        contentTiming = timing
    )

    private fun transitionOrigin(
        localEntry: MotionRect,
        localTitle: MotionRect,
        sourceWindowTopOnScreen: Float
    ): ContainerMorphOrigin = ContainerMorphOrigin(
        entryBoundsOnScreen = entryBounds.offsetBy(dy = sourceWindowTopOnScreen),
        titleBoundsOnScreen = entryTitle.offsetBy(dy = sourceWindowTopOnScreen),
        entryBoundsInSourceWindow = localEntry,
        titleBoundsInSourceWindow = localTitle,
        sourceWindowBoundsOnScreen = MotionRect(
            0f,
            sourceWindowTopOnScreen,
            1080f,
            sourceWindowTopOnScreen + 2160f
        ),
        titleTextSizePx = 45f,
        titleLineCount = 1,
        titleLayoutDirection = android.view.View.LAYOUT_DIRECTION_LTR,
        sourceWindowWidth = 1080,
        sourceWindowHeight = 2160,
        displayId = 0,
        displayRotation = 0
    )

    private fun MotionRect.offsetBy(
        dx: Float = 0f,
        dy: Float = 0f
    ) = MotionRect(left + dx, top + dy, right + dx, bottom + dy)

    /** 2026-09-24：入口与形变表面底色随主题——浅色用表面色（柔光浅色下原来是一块中灰）。 */
    @Test
    fun entrySurfaceUsesTheSurfaceColorInLightTheme() {
        val gray = 0xFF323B42.toInt()
        val surface = 0xFFF4F4F6.toInt()
        val light = ContainerMorphEntrySpec.surfaceColor(false, gray, surface)
        val dark = ContainerMorphEntrySpec.surfaceColor(true, gray, surface)
        assertEquals(surface and 0x00FFFFFF, light and 0x00FFFFFF)
        assertEquals(gray and 0x00FFFFFF, dark and 0x00FFFFFF)
        assertEquals(ContainerMorphEntrySpec.scrimAlpha(false), light ushr 24)
        assertEquals(ContainerMorphEntrySpec.scrimAlpha(true), dark ushr 24)
    }
}
