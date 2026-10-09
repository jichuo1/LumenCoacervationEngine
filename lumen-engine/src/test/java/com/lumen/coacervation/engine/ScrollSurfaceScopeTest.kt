package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.geometry.ScrollSurfaceScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollSurfaceScopeTest {
    private data class Node(val name: String, var parent: Node? = null)

    private fun inScope(surface: Node, host: Node): Boolean =
        ScrollSurfaceScope.contains(surface, host) { it.parent }

    @Test fun includesNestedCardsButExcludesHostToolbarAndOtherPage() {
        val window = Node("window")
        val scroll = Node("scroll", window)
        val card = Node("card", Node("content", scroll))
        val nestedButton = Node("button", card)
        val otherScroll = Node("other scroll", window)
        val otherCard = Node("other card", otherScroll)
        val toolbar = Node("toolbar", window)
        assertTrue(inScope(card, scroll))
        assertTrue(inScope(nestedButton, scroll))
        assertFalse(inScope(scroll, scroll))
        assertFalse(inScope(window, scroll))
        assertFalse(inScope(toolbar, scroll))
        assertFalse(inScope(otherCard, scroll))
    }

    @Test fun removalAndReparentingImmediatelyChangeScopeWithoutCachedAncestry() {
        val scroll = Node("scroll")
        val other = Node("other")
        val content = Node("content", scroll)
        val card = Node("card", content)
        assertTrue(inScope(card, scroll))
        content.parent = other
        assertFalse(inScope(card, scroll))
        content.parent = scroll
        assertTrue(inScope(card, scroll))
        card.parent = null
        assertFalse(inScope(card, scroll))
    }

    @Test fun equalLookingHostsUseIdentityNotValueEquality() {
        val host = Node("scroll")
        val sameLookingHost = Node("scroll")
        assertTrue(host == sameLookingHost)
        val card = Node("card", sameLookingHost)
        assertFalse(inScope(card, host))
        assertTrue(inScope(card, sameLookingHost))
    }

    @Test fun legitimateDeepViewTreesHaveNoArbitraryScopeCutoff() {
        val host = Node("host")
        var branch = host
        repeat(512) { branch = Node("node $it", branch) }
        assertTrue(inScope(branch, host))
        assertTrue(inScope(Node("one more", branch), host))
        assertFalse(inScope(branch, Node("unrelated")))
    }
}
