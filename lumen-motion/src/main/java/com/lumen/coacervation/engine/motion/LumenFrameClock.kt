package com.lumen.coacervation.engine.motion

/** Time is local to effects; deterministic playback never reads MotionEvent timestamps as nanoTime. */
public fun interface LumenFrameClock { public fun nowNanos(): Long }

public class LumenFixedFrameClock @JvmOverloads constructor(initialNanos: Long=0L) : LumenFrameClock {
    private var now=initialNanos
    init {require(initialNanos>=0L)}
    override fun nowNanos(): Long = now
    public fun seekNanos(value: Long) {require(value>=0L);now=value}
    public fun advanceMillis(value: Long) {require(value in 0L..60_000L&&now<=Long.MAX_VALUE-value*1_000_000L);now+=value*1_000_000L}
}
