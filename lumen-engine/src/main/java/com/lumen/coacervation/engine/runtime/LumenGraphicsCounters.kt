package com.lumen.coacervation.engine.runtime

/** Per-owner counters; no content, View or paths are retained or emitted. */
internal class LumenGraphicsCounters {
    var enabled = true
    var timingEnabled = false
    private var retiredTo:LumenGraphicsCounters?=null
    var contentRecordings = 0L
        private set
    var proxyRecordings = 0L
        private set
    var effectBuilds = 0L
        private set
    var shaderCompilations = 0L
        private set
    var softwareRequests = 0L
        private set
    var softwareCompletions = 0L
        private set
    var staleCompletions = 0L
        private set
    var contentRecordingNanos = 0L
        private set
    var softwareProcessingNanos = 0L
        private set

    fun contentRecorded(nanos: Long = 0L) {
        if (!enabled) return
        val target=retiredTo?:this;target.contentRecordings++
        if (timingEnabled) target.contentRecordingNanos += nanos.coerceAtLeast(0L)
    }
    fun proxyRecorded() { if (enabled) (retiredTo?:this).proxyRecordings++ }
    fun effectBuilt() { if (enabled) (retiredTo?:this).effectBuilds++ }
    fun shaderCompiled() { if (enabled) (retiredTo?:this).shaderCompilations++ }
    fun softwareRequested() { if (enabled) (retiredTo?:this).softwareRequests++ }
    fun softwareCompleted(nanos: Long, stale: Boolean) {
        if (!enabled) return
        val target=retiredTo?:this;target.softwareCompletions++
        if (stale) target.staleCompletions++
        if (timingEnabled) target.softwareProcessingNanos += nanos.coerceAtLeast(0L)
    }
    fun recordingTimed(nanos: Long) { if(enabled&&timingEnabled)(retiredTo?:this).contentRecordingNanos+=nanos.coerceAtLeast(0L) }
    /** Transfer once. Late worker completions update the aggregate without retaining their source/View. */
    fun retireInto(destination:LumenGraphicsCounters){check(retiredTo==null&&destination!==this&&destination.retiredTo==null);destination.absorb(this);retiredTo=destination}
    fun absorb(other: LumenGraphicsCounters) {
        contentRecordings+=other.contentRecordings;proxyRecordings+=other.proxyRecordings;effectBuilds+=other.effectBuilds
        shaderCompilations+=other.shaderCompilations;softwareRequests+=other.softwareRequests;softwareCompletions+=other.softwareCompletions
        staleCompletions+=other.staleCompletions;contentRecordingNanos+=other.contentRecordingNanos;softwareProcessingNanos+=other.softwareProcessingNanos
    }
}
