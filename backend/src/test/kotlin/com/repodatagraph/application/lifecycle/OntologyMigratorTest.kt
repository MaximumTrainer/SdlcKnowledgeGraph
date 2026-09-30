package com.repodatagraph.application.lifecycle

import com.repodatagraph.domain.lifecycle.AppliedMigration
import com.repodatagraph.domain.lifecycle.CompiledStatement
import com.repodatagraph.domain.lifecycle.MigrationChecksumException
import com.repodatagraph.domain.lifecycle.MigrationFailedException
import com.repodatagraph.domain.lifecycle.MigrationFile
import com.repodatagraph.domain.lifecycle.MigrationMode
import com.repodatagraph.domain.lifecycle.OntologyAheadException
import com.repodatagraph.domain.ontology.NodeTypeDef
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.PropertyDef
import com.repodatagraph.domain.ontology.PropertyType
import com.repodatagraph.domain.port.out.MigrationSource
import com.repodatagraph.domain.port.out.OntologyStateStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * What the application does with the shipped migrations as it starts, and when an admin asks (#33,
 * FR7-FR8). The store here is in memory; that each migration is one transaction, and a failing one
 * leaves the graph as it was, is proved against Neo4j in OntologyMigratorIT.
 */
class OntologyMigratorTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val registry =
        OntologyRegistry(
            version = "1.5.0",
            nodeTypes =
                listOf(
                    NodeTypeDef(
                        "Team",
                        "A team",
                        listOf("name"),
                        listOf(PropertyDef("name", PropertyType.STRING), PropertyDef("tier", PropertyType.STRING)),
                    ),
                ),
            edgeTypes = emptyList(),
        )
    private val v140 =
        MigrationFile.parse(
            "V1_4_0__add_tier.yaml",
            "operations:\n  - addProperty: { type: Team, property: tier, default: bronze }\n",
        )
    private val v150 = MigrationFile.parse("V1_5_0__raw.cypher", "MATCH (t:Team) SET t.tier = 'silver';\n")

    /** The graph's recorded state, and every statement run against it, in order. */
    private class FakeStore(
        var versions: MutableList<String> = mutableListOf("1.3.0"),
        val failOn: String? = null,
    ) : OntologyStateStore {
        val recorded = mutableListOf<AppliedMigration>()
        val statements = mutableListOf<String>()

        override fun storedVersions() = versions.toList()

        override fun applied() = recorded.toList()

        override fun apply(
            migration: MigrationFile,
            statements: List<CompiledStatement>,
            appliedAt: Instant,
        ): AppliedMigration {
            check(migration.id != failOn) { "Neo.ClientError.Statement.SyntaxError" }
            this.statements += statements.map { it.cypher }
            val applied = AppliedMigration(migration.version.toString(), migration.name, migration.checksum, appliedAt, 0, false)
            recorded += applied
            versions = mutableListOf(migration.version.toString())
            return applied
        }

        override fun recordBaseline(
            migration: MigrationFile,
            at: Instant,
        ) {
            recorded += AppliedMigration(migration.version.toString(), migration.name, migration.checksum, at, 0, true)
        }

        override fun recordVersion(
            version: String,
            at: Instant,
        ) {
            versions = mutableListOf(version)
        }
    }

    private fun migrator(
        store: FakeStore,
        mode: MigrationMode = MigrationMode.AUTO,
        files: List<MigrationFile> = listOf(v150, v140),
    ) = OntologyMigrator(MigrationSource { files }, store, registry, mode, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `at startup in auto mode every pending migration runs in order and the graph ends on the build's version`() {
        val store = FakeStore()

        migrator(store).migrate()

        assertThat(store.statements).containsExactly(
            "MATCH (n:Team) WHERE n.tier IS NULL SET n.tier = \$default",
            "MATCH (t:Team) SET t.tier = 'silver'",
        )
        assertThat(store.recorded.map { it.version }).containsExactly("1.4.0", "1.5.0")
        assertThat(store.versions).containsExactly("1.5.0")
    }

    @Test
    fun `in manual mode startup runs nothing and leaves the version where it was`() {
        val store = FakeStore()

        val migrator = migrator(store, MigrationMode.MANUAL)
        migrator.migrate()

        assertThat(store.statements).isEmpty()
        assertThat(store.versions).containsExactly("1.3.0")
        assertThat(migrator.status().pending.map { it.version }).containsExactly("1.4.0", "1.5.0")
        assertThat(migrator.status().upToDate).isFalse()
    }

    @Test
    fun `an admin applying them brings the graph up to date`() {
        val store = FakeStore()
        val migrator = migrator(store, MigrationMode.MANUAL)

        val result = migrator.applyPending()

        assertThat(result.applied.map { it.version }).containsExactly("1.4.0", "1.5.0")
        assertThat(result.dbVersion).isEqualTo("1.5.0")
        assertThat(migrator.status().upToDate).isTrue()
    }

    @Test
    fun `a new graph records the build's version and baselines every migration, running none`() {
        val store = FakeStore(versions = mutableListOf())

        migrator(store).migrate()

        assertThat(store.statements).isEmpty()
        assertThat(store.recorded.map { it.version to it.baseline }).containsExactly("1.4.0" to true, "1.5.0" to true)
        assertThat(store.versions).containsExactly("1.5.0")
    }

    @Test
    fun `a failing migration stops startup, naming it, and nothing after it runs`() {
        val store = FakeStore(failOn = "V1_4_0__add_tier")

        val error = assertThrows<MigrationFailedException> { migrator(store).migrate() }

        assertThat(error.migration).isEqualTo("V1_4_0__add_tier")
        assertThat(error.message).contains("V1_4_0__add_tier").contains("SyntaxError")
        assertThat(store.statements).isEmpty()
        assertThat(store.versions).containsExactly("1.3.0")
    }

    @Test
    fun `a graph written by a newer ontology is refused, as before`() {
        val store = FakeStore(versions = mutableListOf("2.0.0"))

        val error = assertThrows<OntologyAheadException> { migrator(store).migrate() }

        assertThat(error.message).contains("2.0.0").contains("1.5.0")
    }

    @Test
    fun `an applied migration whose file changed is refused before anything runs`() {
        val store = FakeStore()
        store.recorded += AppliedMigration("1.4.0", "add_tier", "0".repeat(64), now, 1, false)

        assertThrows<MigrationChecksumException> { migrator(store).migrate() }

        assertThat(store.statements).isEmpty()
    }

    @Test
    fun `with no migrations shipped a graph on an older version is simply moved to the build's`() {
        val store = FakeStore()

        migrator(store, files = emptyList()).migrate()

        assertThat(store.versions).containsExactly("1.5.0")
    }
}
