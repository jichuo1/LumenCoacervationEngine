package com.lumen.coacervation.engine.motion.pager

import org.junit.Assert.*
import org.junit.Test

class PageTextChainPagesTest {
    private data class Page(val label: String)

    @Test fun removingTheCachedTailReturnsItsStateBeforeTraversingRemainingPages() {
        val pages = PageTextChainPages<Page, String>()
        val first = Page("first")
        val tail = Page("tail")
        pages.put(first, "first matrix/clip")
        pages.put(tail, "tail matrix/clip")
        val released = ArrayList<String>()
        pages.releaseRemoved({ it === first }, released::add)
        assertEquals(listOf("tail matrix/clip"), released)
        assertNull(pages[tail])
        assertEquals("first matrix/clip", pages[first])
        pages.releaseRemoved({ it === first }, released::add)
        assertEquals(1, released.size)
    }

    @Test fun shiftingAnIndexKeepsTheSurvivingPageStateAndReplacementUsesItsOwnIdentity() {
        val pages = PageTextChainPages<Page, String>()
        val removed = Page("same")
        val survivor = Page("survivor")
        val replacement = Page("same") // Value equality must never reuse removed View state.
        pages.put(removed, "old")
        pages.put(survivor, "surviving")
        assertNull(pages[replacement])
        val released = ArrayList<String>()
        pages.releaseRemoved({ it === survivor || it === replacement }, released::add)
        pages.put(replacement, "new")
        assertEquals(listOf("old"), released)
        assertEquals("surviving", pages[survivor])
        assertEquals("new", pages[replacement])
        pages.clear(released::add)
        pages.clear(released::add)
        assertEquals(listOf("old", "new", "surviving"), released)
    }

    @Test fun releasingThenReusingTheSamePageDoesNotCarryTheOldBorrowedState() {
        val pages = PageTextChainPages<Page, String>()
        val page = Page("reused")
        pages.put(page, "first loan")
        assertEquals("first loan", pages.remove(page))
        assertNull(pages.remove(page))
        pages.put(page, "second loan")
        assertEquals("second loan", pages[page])
    }
}
