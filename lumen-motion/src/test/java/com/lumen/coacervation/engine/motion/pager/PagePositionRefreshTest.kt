package com.lumen.coacervation.engine.motion.pager

import com.lumen.coacervation.engine.contract.SourceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页器"平移与可见性都应用之后才通知"（适配标准 §4 的宿主义务，lumen-motion 自己的翻页器替宿主守住）。
 * 来源工程同名测试里其余四个用例断言的是引擎本体的位置刷新，已在 lumen-engine 的 PositionRefreshTest 里。
 */
class PagePositionRefreshTest {
    private fun function(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing $signature" }
        val body = source.indexOf('{', start)
        var depth = 1
        var end = body + 1
        while (depth > 0 && end < source.length) {
            when (source[end++]) { '{' -> depth++; '}' -> depth-- }
        }
        check(depth == 0) { "Unclosed $signature" }
        return source.substring(body, end)
    }

    @Test fun pagerNotifiesAfterApplyingBothTransformsAndVisibilityOnlyWhenSomethingChanged() {
        val pager = SourceContract.read("motion/pager/LumenPagePager.kt")
        val apply = function(pager, "private fun applyPosition()")
        assertTrue(apply.contains("if (child.translationX != translation)"))
        assertTrue(apply.contains("if (child.visibility != visibility)"))
        assertEquals(2, Regex("positionChanged = true").findAll(apply).count())
        val notify = apply.indexOf("if (positionChanged) onPositionChanged()")
        assertTrue(notify > apply.indexOf("child.translationX = translation"))
        assertTrue(notify > apply.indexOf("child.visibility = visibility"))
        assertFalse(apply.contains("requestLayout("))
        assertFalse(apply.contains("postInvalidateOnAnimation("))
    }
}
