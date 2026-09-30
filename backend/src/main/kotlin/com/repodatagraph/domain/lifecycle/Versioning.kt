package com.repodatagraph.domain.lifecycle

import com.repodatagraph.domain.ontology.OntologyRegistry

/** `lifecycle.versioning.*`: whether a changed node keeps what it replaced, how much, and for which types. */
data class VersioningSettings(
    val enabled: Boolean = true,
    val maxVersions: Int = DEFAULT_MAX_VERSIONS,
    val excludedTypes: Collection<String> = emptyList(),
) {
    init {
        require(maxVersions > 0) { "lifecycle.versioning.max-versions must be at least 1, was $maxVersions" }
    }

    companion object {
        const val DEFAULT_MAX_VERSIONS = 50
    }
}

/**
 * Which node types keep their earlier values as versions (#33, FR1). The graph describing itself -
 * sync runs, connector state, the ontology, the versions themselves - changes on every run and has no
 * history worth keeping, so a meta type never does, whatever configuration says.
 */
class VersioningPolicy(
    val settings: VersioningSettings,
    private val registry: OntologyRegistry,
) {
    val maxVersions: Int get() = settings.maxVersions

    fun isVersioned(type: String): Boolean {
        val nodeType = registry.nodeType(type) ?: return false
        return settings.enabled && !nodeType.meta && type !in settings.excludedTypes
    }

    /** Every type that keeps no versions though versioning is on: those configured, and every meta type. */
    fun excludedTypes(): List<String> =
        (settings.excludedTypes + registry.allNodeTypes().filter { it.meta }.map { it.name }).distinct().sorted()
}
