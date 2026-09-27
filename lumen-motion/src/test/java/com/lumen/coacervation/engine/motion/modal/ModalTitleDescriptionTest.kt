package com.lumen.coacervation.engine.motion.modal

import com.lumen.coacervation.engine.contract.MotionSource
import org.junit.Assert.*
import org.junit.Test

class ModalTitleDescriptionTest {
    @Test fun descriptionsFadeContinuouslyAndAreAlreadyVisibleBeforeFinalRelease() {
        assertEquals(1f, ModalTitleMotionSpec.descriptionWeight(0f), 0f)
        assertEquals(.5f, ModalTitleMotionSpec.descriptionWeight(.15f), .000001f)
        assertEquals(0f, ModalTitleMotionSpec.descriptionWeight(.3f), 0f)
        assertEquals(0f, ModalTitleMotionSpec.descriptionWeight(1f), 0f)
        var last = 1f
        for (step in 0..1000) {
            val current = ModalTitleMotionSpec.descriptionWeight(step / 1000f)
            assertTrue(current <= last && current in 0f..1f)
            assertTrue(last - current < .006f)
            last = current
        }
        assertTrue(ModalTitleMotionSpec.descriptionWeight(.001f) > .999f)
        assertEquals(1f, ModalTitleMotionSpec.descriptionWeight(Float.NaN), 0f)
    }

    @Test fun descriptionsStayAboveTheMorphAndMergedRowsNeverRedrawTheTitle() {
        val code = MotionSource.file("ModalTitleDescriptions")
        assertFalse(code.contains("source.overlay"))
        assertTrue(code.contains("Block(source, 1,"))
        val titleDraw = MotionSource.functions(MotionSource.file("ModalTitleMotion"), "onDraw").single()
        assertTrue(titleDraw.contains("descriptions.draw(canvas)"))
        val draw = MotionSource.functions(code, "draw").single()
        assertTrue(draw.contains("layout.getLineTop(block.firstLine)"))
        assertTrue(draw.contains("paint.color = borrowedColor"))
        assertTrue(draw.contains("finally"))
        assertEquals(1, Regex("layout\\.draw\\(this\\)").findAll(draw).count())
        for (name in listOf("Bitmap", "StaticLayout", "setText(", "requestLayout")) {
            assertFalse(name, draw.contains(name))
        }
    }

    @Test fun closeRestoresDescriptionsBeforeHandingBackTheNativeText() {
        val source = MotionSource.file("ModalTitleMotion")
        val closed = MotionSource.functions(source, "closed").single()
        assertTrue(closed.indexOf("descriptions.apply(0f)") < closed.indexOf("descriptions.dispose()"))
        assertTrue(closed.indexOf("descriptions.dispose()") < closed.indexOf("restoreSourceText()"))
        val expanded = MotionSource.functions(source, "expanded").single()
        assertFalse(expanded.contains("descriptions.dispose()"))
        assertFalse(expanded.contains("restoreSourceText()"))
    }

    @Test fun returningTitleWaitsForTheSourceWindowFrameBeforeDialogRemoval() {
        val source = MotionSource.file("ModalTitleMotion")
        val finish = MotionSource.functions(source, "finishAfterSourceDraw").single()
        assertTrue(finish.contains("registerFrameCommitCallback"))
        assertTrue(finish.contains("unregisterFrameCommitCallback"))
        assertTrue(finish.contains("handler.postDelayed(release, 80L)"))
        assertTrue(finish.indexOf("descriptions.restoreNative()") < finish.indexOf("source.invalidate()"))
        val controller = MotionSource.functions(MotionSource.file("IconAnchoredMotionController"), "finish").single()
        assertTrue(controller.contains("title.finishAfterSourceDraw(onClosed)"))
        val descriptions = MotionSource.file("ModalTitleDescriptions")
        assertTrue(descriptions.contains("windowX - rootLocation[0], windowY - rootLocation[1]"))
    }

    @Test fun sourceTextIsNotBorrowedBeforeTheDialogHasItsFirstFrame() {
        val controller = MotionSource.file("IconAnchoredMotionController")
        assertFalse(MotionSource.functions(controller, "prepareFirstFrame").single().contains("titleMotion?.prepare"))
        val entry = MotionSource.functions(controller, "startEntry").single()
        assertTrue(entry.contains("layer.postOnAnimation"))
        assertTrue(entry.indexOf("state != MotionState.ENTERING") < entry.indexOf("titleMotion?.prepare(0f)"))
        val title = MotionSource.file("ModalTitleMotion")
        val draw = MotionSource.functions(title, "onDraw").single()
        assertTrue(draw.contains("if (sourceBorrowPending)"))
        assertTrue(draw.contains("descriptions.hideNative()"))
    }

    @Test fun overlappingPanelsCannotRevealADescriptionStillHeldByAnotherPanel() {
        val owners = ModalTitleColorOwners<Any, Float>()
        val view = Any(); val old = Any(); val next = Any()
        owners.acquire(view, old, .72f)
        owners.acquire(view, next, 0f)
        owners.updateWeight(view, old, 0f)
        assertEquals(0f, owners.updateWeight(view, next, .5f), 0f)
        assertNull(owners.release(view, old))
        assertEquals(.5f, owners.weight(view), 0f)
        assertEquals(.72f, owners.release(view, next)!!, 0f)
        assertEquals(1f, owners.weight(view), 0f)
    }
}
