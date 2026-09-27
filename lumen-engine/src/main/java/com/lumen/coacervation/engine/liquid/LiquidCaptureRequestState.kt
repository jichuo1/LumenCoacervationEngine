package com.lumen.coacervation.engine.liquid

/** Invalidation never releases a bitmap lease: PixelCopy must actually finish before another begins. */
internal class LiquidCaptureRequestState {
    class Ticket internal constructor(internal val epoch: Long)
    enum class Completion { CURRENT, STALE, FOREIGN }
    private var epoch = 0L
    private var pending: Ticket? = null

    fun begin(): Ticket? {
        if (pending != null) return null
        return Ticket(epoch).also { pending = it }
    }

    fun invalidate() { epoch++ }

    fun complete(ticket: Ticket): Completion {
        if (pending !== ticket) return Completion.FOREIGN
        pending = null
        return if (ticket.epoch == epoch) Completion.CURRENT else Completion.STALE
    }
}
