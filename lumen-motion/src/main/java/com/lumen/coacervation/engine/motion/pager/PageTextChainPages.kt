package com.lumen.coacervation.engine.motion.pager

import java.util.IdentityHashMap

/** Page identity survives index shifts; removed pages release once without a per-frame snapshot. */
internal class PageTextChainPages<Page : Any, State : Any> {
    private class Entry<Page, State>(val page: Page, val state: State)
    private val entries = ArrayList<Entry<Page, State>>()
    private val states = IdentityHashMap<Page, State>()

    operator fun get(page: Page): State? = states[page]

    fun put(page: Page, state: State) {
        check(get(page) == null)
        states[page] = state
        entries.add(Entry(page, state))
    }

    fun remove(page: Page): State? {
        var index = 0
        while (index < entries.size) {
            if (entries[index].page === page) {
                states.remove(page)
                return entries.removeAt(index).state
            }
            index++
        }
        return null
    }

    fun releaseRemoved(isCurrent: (Page) -> Boolean, release: (State) -> Unit) {
        var index = entries.size - 1
        while (index >= 0) {
            val entry = entries[index]
            if (!isCurrent(entry.page)) {
                entries.removeAt(index)
                states.remove(entry.page)
                release(entry.state)
            }
            index--
        }
    }

    fun clear(release: (State) -> Unit) {
        while (entries.isNotEmpty()) {
            val entry = entries.removeAt(entries.lastIndex)
            states.remove(entry.page)
            release(entry.state)
        }
    }
}
