package com.repodatagraph.adapter.out.ontology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

/**
 * `meta: true` marks a node type that describes the graph itself rather than the software it models,
 * so a client can leave it out of anything meant for browsing the software (#6).
 */
class YamlOntologyLoaderMetaTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()

    @Test
    fun `the graph's own bookkeeping types are meta`() {
        val meta =
            registry
                .allNodeTypes()
                .filter { it.meta }
                .map { it.name }
                .toSet()

        assertEquals(setOf("Ontology", "SyncRun", "ConnectorState", "ServicePrincipal", "NodeVersion", "OntologyMigration"), meta)
    }

    @Test
    fun `a type that does not say is not meta`() {
        assertEquals(false, registry.nodeType("Repository")?.meta)
    }
}
