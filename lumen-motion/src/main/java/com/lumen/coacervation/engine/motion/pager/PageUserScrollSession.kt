package com.lumen.coacervation.engine.motion.pager

/** Tracks accepted page scrolling even when NestedScrollView discards pageScroll's return value. */
public class PageUserScrollSession {
    private var navigationKey = false
    private var pageAccepted = false

    fun beginKey(isNavigationKeyDown: Boolean) {
        navigationKey = isNavigationKeyDown
        pageAccepted = false
    }

    fun pageResult(accepted: Boolean) {
        if (navigationKey && accepted) pageAccepted = true
    }

    fun finishKey(returnedHandled: Boolean): Boolean {
        val accepted = navigationKey && (returnedHandled || pageAccepted)
        navigationKey = false
        pageAccepted = false
        return accepted
    }
}
