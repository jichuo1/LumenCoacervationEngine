package com.lumen.coacervation.engine.host

/** Counts are application calls/owned resources, not physical GPU passes or driver compilation events. */
public data class LumenSurfacePerformance(
    val contentRecordings: Long,
    val proxyRecordings: Long,
    val effectChainBuilds: Long,
    val runtimeShaderBuilds: Long,
    val softwareRequests: Long,
    val softwareCompletions: Long,
    val staleCompletions: Long,
    val contentRecordingNanos: Long,
    val softwareProcessingNanos: Long,
    val activeSoftwareBytes: Long,
    val logicalGpuContentPixels: Long,
    val logicalGpuEffectPixels: Long,
    val timingEnabled: Boolean
)

public enum class LumenRegionSpace { SOURCE_LOCAL, TARGET_LOCAL, EXECUTION_PIXELS }
public data class LumenSurfaceRegion(val left: Float,val top: Float,val right: Float,val bottom: Float,val space: LumenRegionSpace)
/** Rectangles are conservative enclosing bounds; rotated sources need not fill every pixel of the enclosure. */
public data class LumenSurfaceSamplingRegions(
    val shapeBounds: LumenSurfaceRegion,
    val requiredSampleBounds: LumenSurfaceRegion,
    val recordedBounds: LumenSurfaceRegion,
    val materializedBounds: LumenSurfaceRegion,
    val outputClip: LumenSurfaceRegion,
    val sourceId: Long,
    val sourceEpoch: Long,
    val contentVersion: Long,
    val capturedVersion: Long,
    val estimatedSourceAgeNanos: Long?,
    val availability: LumenSourceAvailability
)
