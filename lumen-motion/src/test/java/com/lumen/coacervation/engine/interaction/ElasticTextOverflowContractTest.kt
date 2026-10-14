package com.lumen.coacervation.engine.interaction

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device lesson (1.4.4 review, OPPO PMA110 / API 37, density 4): a clickable TextView whose single line (66px)
 * overflowed its 60px height reported canScrollVertically(1) and lost the elastic press and highlight.
 * Only touch-scrollable TextViews (movement method, text selection, EditText) may keep native drag; they are
 * rejected before the scroll check, so the scroll check must not misread overflowing text as a scroller.
 */
class ElasticTextOverflowContractTest {
    private val hitTarget = MotionSource.file("ElasticInteractionController")
        .after("private fun hitTarget(").before("private fun isSwitchLike")

    @Test fun touchScrollableTextIsRejectedBeforeTheScrollCheck() {
        val rejection = hitTarget.indexOf("(view is TextView && (view.isTextSelectable || view.movementMethod != null))")
        val editText = hitTarget.indexOf("view is EditText")
        val scrollCheck = hitTarget.indexOf("view.canScrollVertically(1)")
        assertTrue(rejection in 0 until scrollCheck)
        assertTrue(editText in 0 until scrollCheck)
    }

    @Test fun overflowingPlainTextStaysEligible() {
        val target = hitTarget.after("if (role == ElasticNodeRole.TARGET) {").before("return Hit(view, x, y)")
        assertTrue(target.contains("if (view !is TextView && (view.canScrollHorizontally(-1) || view.canScrollHorizontally(1) ||"))
    }
}
