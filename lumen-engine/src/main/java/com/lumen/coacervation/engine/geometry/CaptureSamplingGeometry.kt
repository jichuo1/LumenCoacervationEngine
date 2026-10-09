package com.lumen.coacervation.engine.geometry

/** A shared content node needs new target matrices only when its recording geometry changed. */
internal class CaptureSamplingGeometry {
    private var initialized = false
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var recordedWidth = 0
    private var recordedHeight = 0
    private var scaleX = 0f
    private var scaleY = 0f

    fun update(sourceWidth: Int, sourceHeight: Int, recordedWidth: Int, recordedHeight: Int,
        scaleX: Float, scaleY: Float): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || recordedWidth <= 0 || recordedHeight <= 0 ||
            !scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || scaleY <= 0f) return false
        val changed = !initialized || this.sourceWidth != sourceWidth || this.sourceHeight != sourceHeight ||
            this.recordedWidth != recordedWidth || this.recordedHeight != recordedHeight ||
            this.scaleX != scaleX || this.scaleY != scaleY
        this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight
        this.recordedWidth = recordedWidth; this.recordedHeight = recordedHeight
        this.scaleX = scaleX; this.scaleY = scaleY
        initialized = true
        return changed
    }

    fun clear() { initialized = false }
}
