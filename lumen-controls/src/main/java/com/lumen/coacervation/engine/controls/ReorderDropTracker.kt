package com.lumen.coacervation.engine.controls

import java.util.ArrayDeque
import java.util.IdentityHashMap

/** A recovery may finish while another holder is already being dragged. */
internal class ReorderDropTracker<T : Any> {
    private val unsettled = IdentityHashMap<T, Boolean>()
    private val completed = ArrayDeque<Int>()

    val isDragging: Boolean get() = unsettled.isNotEmpty()
    val hasCompletedDrops: Boolean get() = completed.isNotEmpty()

    fun begin(holder: T) {
        // Regrabbing the same recovering holder continues its original drop.
        unsettled[holder] = true
    }

    fun finish(holder: T, position: Int): Boolean {
        if (unsettled.remove(holder) == null) return false
        completed.addLast(position)
        return true
    }

    fun poll(): Int? = if (isDragging) null else completed.pollFirst()
}
