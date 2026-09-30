package com.repodatagraph.application.lifecycle

import com.repodatagraph.domain.lifecycle.AppliedMigration
import com.repodatagraph.domain.lifecycle.InvalidMigrationException
import com.repodatagraph.domain.lifecycle.MigrationChecksumException
import com.repodatagraph.domain.lifecycle.MigrationFile
import com.repodatagraph.domain.lifecycle.OntologyVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Which shipped migrations a graph still needs (#33, FR7). A migration runs once, in version order,
 * and only on a graph older than it: a graph that began on a newer ontology never held the shape the
 * migration repairs, so it is recorded as a baseline rather than run.
 */
class MigrationPlannerTest {
    private val registry = OntologyVersion.parse("1.6.0")
    private val v140 = MigrationFile.parse("V1_4_0__first.yaml", "operations: []\n")
    private val v150 = MigrationFile.parse("V1_5_0__second.yaml", "operations: []\n")
    private val v160 = MigrationFile.parse("V1_6_0__third.cypher", "MATCH (n:Team) SET n.x = 1;\n")

    private fun applied(
        file: MigrationFile,
        checksum: String = file.checksum,
    ) = AppliedMigration(file.version.toString(), file.name, checksum, Instant.EPOCH, 1, baseline = false)

    @Test
    fun `a graph on an older version needs every newer migration, oldest first`() {
        val plan = MigrationPlanner.plan(listOf(v160, v140, v150), registry, OntologyVersion.parse("1.4.0"), emptyList())

        assertThat(plan.pending).containsExactly(v150, v160)
        assertThat(plan.baseline).containsExactly(v140)
    }

    @Test
    fun `what was applied already is not pending again`() {
        val plan = MigrationPlanner.plan(listOf(v140, v150), registry, OntologyVersion.parse("1.3.0"), listOf(applied(v140)))

        assertThat(plan.pending).containsExactly(v150)
        assertThat(plan.baseline).isEmpty()
    }

    @Test
    fun `a new graph has nothing to migrate, so every migration is a baseline`() {
        val plan = MigrationPlanner.plan(listOf(v140, v150), registry, null, emptyList())

        assertThat(plan.pending).isEmpty()
        assertThat(plan.baseline).containsExactly(v140, v150)
    }

    @Test
    fun `a migration whose file changed after it was applied is refused, naming it and both checksums`() {
        val error =
            assertThrows<MigrationChecksumException> {
                MigrationPlanner.plan(listOf(v140), registry, OntologyVersion.parse("1.4.0"), listOf(applied(v140, "0".repeat(64))))
            }

        assertThat(error.migration).isEqualTo("V1_4_0__first")
        assertThat(error.recorded).isEqualTo("0".repeat(64))
        assertThat(error.actual).isEqualTo(v140.checksum)
        assertThat(error.message).contains("V1_4_0__first").contains("checksum")
    }

    @Test
    fun `two migrations to one version are refused`() {
        val twin = MigrationFile.parse("V1_4_0__twin.yaml", "operations: []\n")

        val error = assertThrows<InvalidMigrationException> { MigrationPlanner.plan(listOf(v140, twin), registry, null, emptyList()) }

        assertThat(error.message).contains("1.4.0").contains("V1_4_0__first").contains("V1_4_0__twin")
    }

    @Test
    fun `a migration to a version newer than the build is refused`() {
        val future = MigrationFile.parse("V2_0_0__future.yaml", "operations: []\n")

        val error = assertThrows<InvalidMigrationException> { MigrationPlanner.plan(listOf(future), registry, null, emptyList()) }

        assertThat(error.message).contains("V2_0_0__future").contains("1.6.0")
    }
}
