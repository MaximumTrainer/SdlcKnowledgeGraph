package com.repodatagraph.domain.lifecycle

import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which node types keep their earlier values as versions (#33, FR1). The graph describing itself -
 * sync runs, connector state, the ontology - changes on every run and has no history worth keeping.
 */
class VersioningPolicyTest {
    private fun property(name: String) = PropertyDef(name = name, type = PropertyType.STRING)

    private val registry =
        OntologyRegistry(
            version = "1.4.0",
            nodeTypes =
                listOf(
                    NodeTypeDef("Team", "A team", listOf("name"), listOf(property("name"))),
                    NodeTypeDef("Pipeline", "A pipeline", listOf("name"), listOf(property("name"))),
                    NodeTypeDef("SyncRun", "A run", listOf("id"), listOf(property("id")), meta = true),
                ),
            edgeTypes = emptyList(),
        )

    @Test
    fun `a domain type is versioned, a meta type never`() {
        val policy = VersioningPolicy(VersioningSettings(enabled = true, maxVersions = 50, excludedTypes = emptySet()), registry)

        assertThat(policy.isVersioned("Team")).isTrue()
        assertThat(policy.isVersioned("SyncRun")).isFalse()
        assertThat(policy.isVersioned("Nonsense")).isFalse()
    }

    @Test
    fun `configuration can leave a type out, or turn versioning off`() {
        val excluding = VersioningPolicy(VersioningSettings(true, 50, setOf("Pipeline")), registry)
        val off = VersioningPolicy(VersioningSettings(false, 50, emptySet()), registry)

        assertThat(excluding.isVersioned("Pipeline")).isFalse()
        assertThat(excluding.isVersioned("Team")).isTrue()
        assertThat(off.isVersioned("Team")).isFalse()
    }

    @Test
    fun `the types it leaves out include every meta type, for the status page`() {
        val policy = VersioningPolicy(VersioningSettings(true, 50, setOf("Pipeline")), registry)

        assertThat(policy.excludedTypes()).containsExactly("Pipeline", "SyncRun")
    }
}
