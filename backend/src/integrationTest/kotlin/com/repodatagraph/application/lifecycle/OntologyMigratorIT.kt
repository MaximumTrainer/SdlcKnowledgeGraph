package com.repodatagraph.application.lifecycle

import com.repodatagraph.adapter.out.ontology.ClasspathMigrationSource
import com.repodatagraph.domain.lifecycle.MigrationFailedException
import com.repodatagraph.domain.lifecycle.MigrationMode
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.OntologyStateStore
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.data.neo4j.core.Neo4jClient
import java.time.Clock
import java.time.Instant

/**
 * Migrations run against a real Neo4j (#33, FR7-FR8): in version order, each in a transaction of its
 * own, so one that fails leaves the graph exactly as it found it and nothing after it runs.
 *
 * The graph is put back on an older version for each test and restored after, since the Ontology
 * node is the one every other test's application context shares.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class OntologyMigratorIT {
    @Autowired
    private lateinit var store: OntologyStateStore

    @Autowired
    private lateinit var registry: OntologyRegistry

    @Autowired
    private lateinit var graphStore: GraphStore

    @Autowired
    private lateinit var neo4jClient: Neo4jClient

    private val team = NodeKey("Team", "migrator-it-team")

    @BeforeEach
    fun onAnOlderVersion() {
        neo4jClient.query("MATCH (m:OntologyMigration) DETACH DELETE m").run()
        neo4jClient.query("MATCH (o:Ontology) SET o.version = '1.3.0'").run()
        graphStore.upsertNode(GraphNode(team, mapOf("name" to team.key), Provenance.manual(Instant.EPOCH)))
        neo4jClient.query("MATCH (t:Team { key: \$key }) REMOVE t.steps, t.touched").bindAll(mapOf("key" to team.key)).run()
    }

    @AfterEach
    fun restore() {
        neo4jClient.query("MATCH (m:OntologyMigration) DETACH DELETE m").run()
        neo4jClient.query("MATCH (o:Ontology) SET o.version = \$version").bindAll(mapOf("version" to registry.version)).run()
    }

    private fun migrator(location: String) =
        OntologyMigrator(
            ClasspathMigrationSource(location, DefaultResourceLoader()),
            store,
            registry,
            MigrationMode.AUTO,
            Clock.systemUTC(),
        )

    private fun teamProperty(name: String): Any? =
        neo4jClient
            .query("MATCH (t:Team { key: \$key }) RETURN t[\$name] AS value")
            .bindAll(mapOf("key" to team.key, "name" to name))
            .fetch()
            .one()
            .orElseThrow()["value"]

    @Test
    fun `pending migrations run in order, each recorded, and the graph ends on the build's version`() {
        migrator("classpath:ontology/migrations-it/ordered/").migrate()

        assertThat(teamProperty("steps")).isEqualTo("ab")
        assertThat(store.applied().map { it.version to it.baseline }).containsExactly("1.3.1" to false, "1.3.2" to false)
        assertThat(store.applied().map { it.checksum }).allMatch { it.matches(Regex("^[0-9a-f]{64}$")) }
        assertThat(store.storedVersions()).containsExactly(registry.version)
    }

    @Test
    fun `running them again changes nothing`() {
        migrator("classpath:ontology/migrations-it/ordered/").migrate()
        migrator("classpath:ontology/migrations-it/ordered/").migrate()

        assertThat(teamProperty("steps")).isEqualTo("ab")
    }

    @Test
    fun `a migration that fails leaves the data and the version as they were`() {
        val error = assertThrows<MigrationFailedException> { migrator("classpath:ontology/migrations-it/failing/").migrate() }

        assertThat(error.message).contains("V1_3_1__half")
        assertThat(teamProperty("touched")).describedAs("its first statement was rolled back").isNull()
        assertThat(store.applied()).isEmpty()
        assertThat(store.storedVersions()).containsExactly("1.3.0")
    }
}
