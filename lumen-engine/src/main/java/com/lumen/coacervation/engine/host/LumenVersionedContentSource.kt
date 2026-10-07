package com.lumen.coacervation.engine.host

public enum class LumenSourceAvailability { READY, TEMPORARILY_UNAVAILABLE, PROHIBITED, INDEPENDENT_SURFACE }

/** Optional source contract. Getters are pure main-thread snapshots; identities/dependencies remain stable until epoch changes. */
public interface LumenVersionedContentSource : LumenContentSource {
    public val sourceEpoch: Long
    public val contentVersion: Long
    public val availability: LumenSourceAvailability
    public val dependencies: List<LumenContentSource> get() = emptyList()
}
