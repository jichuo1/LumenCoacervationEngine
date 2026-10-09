package com.lumen.coacervation.engine.motion.pager

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageScrollPositionContractTest {
    @Test fun actualOffsetsNotifyAfterSuperWithoutChangingOriginalConstructor() {
        val scroll = MotionSource.file("LumenPageScrollView")
        val constructor = scroll.after("public class LumenPageScrollView(").before("NestedScrollView(context)")
        assertTrue(constructor.contains("private val onUserScroll: () -> Unit"))
        assertTrue(constructor.contains("private val onContentTouch: () -> Unit"))
        assertFalse(constructor.contains("onScrollPositionChanged"))
        assertTrue(scroll.contains("public var onScrollPositionChanged: ((View) -> Unit)? = null"))
        val changed = MotionSource.functions(scroll, "onScrollChanged").single()
        assertTrue(changed.indexOf("super.onScrollChanged(l, t, oldl, oldt)") <
            changed.indexOf("onScrollPositionChanged?.invoke(this)"))
        assertTrue(changed.contains("if (l != oldl || t != oldt)"))
        assertFalse(changed.contains("onUserScroll()"))
        assertFalse(changed.contains("post"))
    }
}
