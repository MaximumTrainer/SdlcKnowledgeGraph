package com.repodatagraph.application.lifecycle

import com.repodatagraph.domain.lifecycle.CompiledStatement
import com.repodatagraph.domain.lifecycle.InvalidMigrationException
import com.repodatagraph.domain.lifecycle.MigrationFile
import com.repodatagraph.domain.ontology.EdgeTypeDef
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Each YAML migration operation, and the Cypher it becomes (#33, FR7). Every statement is idempotent
 * - running it a second time finds nothing left to change - and names only labels, relationship
 * types and properties the compiler has checked, since none of those can be a Cypher parameter.
 */
class YamlMigrationCompilerTest {
    private fun property(name: String) = PropertyDef(name = name, type = PropertyType.STRING)

    private val registry =
        OntologyRegistry(
            version = "1.4.0",
            nodeTypes =
                listOf(
                    NodeTypeDef("ConfigurationItem", "A CI", listOf("sysId"), listOf(property("sysId"), property("ciName"))),
                    NodeTypeDef("Team", "A team", listOf("name"), listOf(property("name"), property("tier"))),
                    NodeTypeDef("Squad", "Several people", listOf("name"), listOf(property("name"))),
                ),
            edgeTypes = listOf(EdgeTypeDef("OWNED_BY", "Owned", listOf("Team"), listOf("Team"), "OWNS")),
        )

    private val compiler = YamlMigrationCompiler(registry)

    private fun compile(yaml: String): List<CompiledStatement> = compiler.compile(MigrationFile.parse("V1_4_0__test.yaml", yaml))

    @Test
    fun `renameProperty moves the value into a declared property, sparing a node that has both`() {
        val statements = compile("operations:\n  - renameProperty: { type: ConfigurationItem, from: name, to: ciName }\n")

        assertThat(statements).containsExactly(
            CompiledStatement(
                "MATCH (n:ConfigurationItem) WHERE n.name IS NOT NULL AND n.ciName IS NULL SET n.ciName = n.name REMOVE n.name",
            ),
        )
    }

    @Test
    fun `addProperty sets a default only where the property is missing`() {
        val statements = compile("operations:\n  - addProperty: { type: Team, property: tier, default: bronze }\n")

        assertThat(statements).containsExactly(
            CompiledStatement("MATCH (n:Team) WHERE n.tier IS NULL SET n.tier = \$default", mapOf("default" to "bronze")),
        )
    }

    @Test
    fun `dropProperty removes a property the registry no longer declares`() {
        val statements = compile("operations:\n  - dropProperty: { type: Team, property: slug }\n")

        assertThat(statements).containsExactly(CompiledStatement("MATCH (n:Team) WHERE n.slug IS NOT NULL REMOVE n.slug"))
    }

    @Test
    fun `dropProperty refuses to drop a property the registry still declares`() {
        val error = assertThrows<InvalidMigrationException> { compile("operations:\n  - dropProperty: { type: Team, property: tier }\n") }

        assertThat(error.message).contains("V1_4_0__test").contains("Team.tier")
    }

    @Test
    fun `renameType relabels every node of the old type and restates its id`() {
        val statements = compile("operations:\n  - renameType: { from: Group, to: Squad }\n")

        assertThat(statements).containsExactly(
            CompiledStatement("MATCH (n:Group) SET n:Squad REMOVE n:Group SET n.id = 'Squad:' + n.key"),
        )
    }

    @Test
    fun `mergeTypes relabels each old type into the one that remains`() {
        val statements = compile("operations:\n  - mergeTypes: { from: [Group, Guild], into: Squad }\n")

        assertThat(statements).containsExactly(
            CompiledStatement("MATCH (n:Group) SET n:Squad REMOVE n:Group SET n.id = 'Squad:' + n.key"),
            CompiledStatement("MATCH (n:Guild) SET n:Squad REMOVE n:Guild SET n.id = 'Squad:' + n.key"),
        )
    }

    @Test
    fun `renameEdge recreates each relationship under its new type with its properties`() {
        val statements = compile("operations:\n  - renameEdge: { from: BELONGS_TO, to: OWNED_BY }\n")

        assertThat(statements).containsExactly(
            CompiledStatement("MATCH (a)-[r:BELONGS_TO]->(b) CREATE (a)-[s:OWNED_BY]->(b) SET s = properties(r) DELETE r"),
        )
    }

    @Test
    fun `operations compile in the order the file lists them`() {
        val statements =
            compile(
                """
                description: Two steps
                operations:
                  - renameType: { from: Group, to: Squad }
                  - dropProperty: { type: Squad, property: legacy }
                """.trimIndent(),
            )

        assertThat(statements.map { it.cypher.substringBefore(" SET").substringBefore(" WHERE") })
            .containsExactly("MATCH (n:Group)", "MATCH (n:Squad)")
        assertThat(compiler.description(MigrationFile.parse("V1_4_0__test.yaml", "description: Two steps\noperations: []\n")))
            .isEqualTo("Two steps")
    }

    @Test
    fun `a target the registry does not declare is refused, naming the migration and the target`() {
        val undeclaredType =
            assertThrows<InvalidMigrationException> { compile("operations:\n  - renameType: { from: Group, to: Tribe }\n") }
        val undeclaredProperty =
            assertThrows<InvalidMigrationException> {
                compile("operations:\n  - renameProperty: { type: Team, from: nom, to: title }\n")
            }
        val undeclaredEdge = assertThrows<InvalidMigrationException> { compile("operations:\n  - renameEdge: { from: A, to: B_OF }\n") }

        assertThat(undeclaredType.message).contains("V1_4_0__test").contains("Tribe")
        assertThat(undeclaredProperty.message).contains("Team.title")
        assertThat(undeclaredEdge.message).contains("B_OF")
    }

    @Test
    fun `a name that could smuggle Cypher is refused`() {
        val error =
            assertThrows<InvalidMigrationException> {
                compile("operations:\n  - dropProperty: { type: Team, property: \"x REMOVE n.name\" }\n")
            }

        assertThat(error.message).contains("x REMOVE n.name")
    }

    @Test
    fun `an operation nobody declared is refused, naming it`() {
        val error = assertThrows<InvalidMigrationException> { compile("operations:\n  - truncate: { type: Team }\n") }

        assertThat(error.message).contains("truncate")
    }

    @Test
    fun `a Cypher migration is its statements, one per semicolon at the end of a line`() {
        val statements =
            compiler.compile(
                MigrationFile.parse(
                    "V1_4_0__raw.cypher",
                    "// Normalise the tiers\nMATCH (t:Team) WHERE t.tier = 'Gold'\nSET t.tier = 'gold';\n\n" +
                        "MATCH (t:Team { tier: 'x' }) DELETE t;\n",
                ),
            )

        assertThat(statements).containsExactly(
            CompiledStatement("MATCH (t:Team) WHERE t.tier = 'Gold'\nSET t.tier = 'gold'"),
            CompiledStatement("MATCH (t:Team { tier: 'x' }) DELETE t"),
        )
    }
}
