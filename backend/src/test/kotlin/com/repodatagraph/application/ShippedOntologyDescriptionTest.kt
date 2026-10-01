package com.repodatagraph.application

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.identity.DerivedProperties
import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.ontology.PropertyDef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader

/**
 * The shipped registry's examples, held to the validator the API writes through (#81).
 *
 * The build-time lint checks them too, with its own reading of the rules, because it runs before the
 * application exists. This is what keeps the two readings honest: an example the lint accepts and the
 * API would refuse is a lie told to every agent that reads it.
 */
class ShippedOntologyDescriptionTest {
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    private val validator = PropertyValidator()
    private val derived = DerivedProperties(GitRemoteParser())

    @Test
    fun `every example of every property is a value the API would accept for it`() {
        val refused =
            owners().flatMap { (owner, properties) ->
                properties.flatMap { property ->
                    property.examples.flatMap { example ->
                        // Alone and optional, so only the value itself is judged.
                        validator
                            .validate(listOf(property.copy(required = false)), mapOf(property.name to example))
                            .map { "$owner.${property.name} = $example: ${it.message}" }
                    }
                }
            }

        assertThat(refused).isEmpty()
    }

    @Test
    fun `every node type's example node is one the API would accept, whole`() {
        val refused =
            registry.allNodeTypes().flatMap { type ->
                type.examples.flatMap { example ->
                    validator.validate(type, derived.expand(type.name, example)).map { "${type.name}: ${it.field} ${it.message}" }
                }
            }

        assertThat(refused).isEmpty()
    }

    @Test
    fun `every property is described and has an example`() {
        val incomplete =
            owners().flatMap { (owner, properties) ->
                properties
                    .filter { it.description.isNullOrBlank() || it.examples.isEmpty() }
                    .map { "$owner.${it.name}" }
            }

        assertThat(incomplete).isEmpty()
    }

    @Test
    fun `the status-like properties name their values`() {
        fun enumOf(
            type: String,
            property: String,
        ) = registry.nodeType(type)?.property(property)?.enum

        assertThat(
            enumOf("Deployment", "status"),
        ).containsExactly("PENDING", "IN_PROGRESS", "SUCCESS", "FAILED", "ROLLED_BACK", "CANCELLED")
        assertThat(enumOf("SyncRun", "status")).containsExactly("RUNNING", "SUCCESS", "PARTIAL", "FAILED")
        assertThat(enumOf("Pipeline", "lastRunStatus")).containsExactly("success", "failure", "cancelled", "in_progress", "unknown")
        assertThat(enumOf("Environment", "type")).containsExactly("development", "test", "staging", "production", "ephemeral", "other")
        assertThat(enumOf("Artifact", "artifactType")).contains("container-image", "other")
        assertThat(
            registry
                .edgeType("OWNS_RESOURCE")
                ?.properties
                ?.firstOrNull { it.name == "rule" }
                ?.enum,
        ).containsExactly("manual", "tag", "deployment", "iac", "naming")
    }

    @Test
    fun `no identity property is deprecated, and every deprecation names what replaces it`() {
        registry.allNodeTypes().forEach { type ->
            type.properties.filter { it.deprecated != null }.forEach { property ->
                assertThat(property.name).describedAs("${type.name}.${property.name}").isNotIn(type.identity)
                val replacedBy = property.deprecated?.replacedBy
                assertThat(type.property(replacedBy.orEmpty()) != null || registry.edgeType(replacedBy.orEmpty()) != null)
                    .describedAs("${type.name}.${property.name} is replaced by $replacedBy")
                    .isTrue()
            }
        }
    }

    private fun owners(): List<Pair<String, List<PropertyDef>>> =
        registry.allNodeTypes().map { it.name to it.properties } + registry.allEdgeTypes().map { it.name to it.properties }
}
