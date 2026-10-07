package com.lumen.coacervation.engine.runtime

/** Capture timestamps describe our request/recording, not the compositor's pixel production time. */
internal class LumenSourceTimeline {
    var epoch=1L
        private set
    var contentVersion=0L
        private set
    var capturedVersion=-1L
        private set
    var requestNanos=Long.MIN_VALUE
        private set
    var completionNanos=Long.MIN_VALUE
        private set
    fun contentChanged() { contentVersion++ }
    fun sourceChanged() { epoch++;contentVersion++;capturedVersion=-1;requestNanos=Long.MIN_VALUE;completionNanos=Long.MIN_VALUE }
    fun requested(now: Long) { requestNanos=now }
    fun completed(token: Long,version: Long,started: Long,finished: Long): Boolean {
        if(token!=epoch)return false
        capturedVersion=version;requestNanos=started;completionNanos=finished;return true
    }
    fun estimatedAgeNanos(now: Long): Long? =
        if(capturedVersion<0L)null else (now-requestNanos).coerceAtLeast(0L)
}
