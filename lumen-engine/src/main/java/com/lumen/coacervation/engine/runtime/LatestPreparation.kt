package com.lumen.coacervation.engine.runtime

import androidx.annotation.WorkerThread
import java.util.concurrent.RejectedExecutionException

/** One active operation, one replaceable request and one replaceable, asynchronously delivered result. */
internal class LatestPreparation<T : Any>(
    private val execute: (Runnable) -> Unit,
    private val post: (Runnable) -> Boolean,
    private val unavailable: () -> T,
    private val discard: (T) -> Unit
) : AutoCloseable {
    internal fun interface Work<T> { @WorkerThread fun run(): T }
    private class Request<T>(val generation: Long, val prepare: Work<T>)
    private class Delivery<T>(val value: T, val deliver: (T) -> Unit)
    private val lock = Any()
    private var generation = 0L
    private var pending: Request<T>? = null
    private var recipient: ((T) -> Unit)? = null
    private var delivery: Delivery<T>? = null
    private var scheduled = false
    private var deliveryScheduled = false
    private var closed = false
    val isClosed: Boolean get() = synchronized(lock) { closed }

    private val notify = Runnable {
        val ready = synchronized(lock) {
            delivery.also { delivery = null; deliveryScheduled = false }
        }
        // Public callers and delivery share the main thread; cancellation cannot race this callback.
        if (ready != null) ready.deliver(ready.value)
    }
    private val dispatch = Runnable {
        val request = synchronized(lock) { pending.also { pending = null } }
        try {
            if (request != null) publish(request, request.prepare.run())
        } finally {
            val again = synchronized(lock) {
                if (pending == null || closed) { scheduled = false; false } else true
            }
            if (again) schedule()
        }
    }

    fun submit(prepare: Work<T>, deliver: (T) -> Unit) {
        var previous: Delivery<T>?
        val start = synchronized(lock) {
            if (closed) return
            generation++
            pending = Request(generation, prepare)
            recipient = deliver
            previous = delivery; delivery = null
            if (scheduled) false else { scheduled = true; true }
        }
        previous?.let { discard(it.value) }
        if (start) schedule()
    }

    /** In-flight input remains owned by its operation until it returns; its result loses eligibility. */
    fun cancel() {
        val previous = synchronized(lock) {
            generation++; pending = null; recipient = null
            delivery.also { delivery = null }
        }
        previous?.let { discard(it.value) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        cancel()
    }

    private fun schedule() {
        try { execute(dispatch) } catch (_: RejectedExecutionException) {
            val request = synchronized(lock) {
                scheduled = false
                pending.also { pending = null }
            }
            if (request != null) publish(request, unavailable())
        }
    }

    private fun publish(request: Request<T>, value: T) {
        var previous: Delivery<T>? = null
        var accepted = false
        val wake = synchronized(lock) {
            val current = recipient
            if (closed || request.generation != generation || current == null) false else {
                accepted = true
                previous = delivery
                delivery = Delivery(value, current)
                // An active, superseded acquisition must not retain its old host callback.
                recipient = null
                if (deliveryScheduled) false else { deliveryScheduled = true; true }
            }
        }
        previous?.let { discard(it.value) }
        if (!accepted) { discard(value); return }
        if (wake && !post(notify)) {
            val lost = synchronized(lock) {
                deliveryScheduled = false
                delivery.also { delivery = null }
            }
            lost?.let { discard(it.value) }
        }
    }
}
