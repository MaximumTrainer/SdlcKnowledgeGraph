package com.repodatagraph.domain.lifecycle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A migration file's name says which ontology version it brings the graph to and what it does (#33,
 * FR7): `V<major>_<minor>_<patch>__<name>.yaml`, or `.cypher` for a raw one. Its checksum is what
 * says, later, whether the file that was applied is the file still shipped.
 */
class MigrationFileTest {
    @Test
    fun `a YAML migration's name carries its version and its name`() {
        val file = MigrationFile.parse("V1_4_0__rename_ci_legacy_name.yaml", "operations: []\n")

        assertThat(file.version).isEqualTo(OntologyVersion.parse("1.4.0"))
        assertThat(file.name).isEqualTo("rename_ci_legacy_name")
        assertThat(file.format).isEqualTo(MigrationFormat.YAML)
        assertThat(file.id).isEqualTo("V1_4_0__rename_ci_legacy_name")
    }

    @Test
    fun `a Cypher migration is raw`() {
        val file = MigrationFile.parse("V2_0_10__drop_repo_id.cypher", "MATCH (n) RETURN n;\n")

        assertThat(file.version.toString()).isEqualTo("2.0.10")
        assertThat(file.format).isEqualTo(MigrationFormat.CYPHER)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "1_4_0__missing_prefix.yaml",
            "V1_4__two_parts.yaml",
            "V1_4_0_rename.yaml",
            "V1_4_0__Upper_Case.yaml",
            "V1_4_0__spaces here.yaml",
            "V1_4_0__rename.json",
            "V1_4_0__.yaml",
        ],
    )
    fun `a file whose name does not say what it is is refused, naming it`(name: String) {
        val error = assertThrows<InvalidMigrationException> { MigrationFile.parse(name, "") }

        assertThat(error.message).contains(name)
    }

    @Test
    fun `the checksum is a SHA-256 of the content, the same however the checkout ends its lines`() {
        val unix = MigrationFile.parse("V1_4_0__a.yaml", "operations:\n  - dropProperty: { type: Team, property: slug }\n")
        val windows = MigrationFile.parse("V1_4_0__a.yaml", "operations:\r\n  - dropProperty: { type: Team, property: slug }\r\n")
        val edited = MigrationFile.parse("V1_4_0__a.yaml", "operations:\n  - dropProperty: { type: Team, property: slugs }\n")

        assertThat(unix.checksum).matches("^[0-9a-f]{64}$")
        assertThat(windows.checksum).isEqualTo(unix.checksum)
        assertThat(edited.checksum).isNotEqualTo(unix.checksum)
    }

    @Test
    fun `ontology versions order by number, not by text`() {
        val versions = listOf("1.10.0", "1.4.0", "1.4.10", "2.0.0", "1.4.2").map(OntologyVersion::parse)

        assertThat(versions.sorted().map { it.toString() }).containsExactly("1.4.0", "1.4.2", "1.4.10", "1.10.0", "2.0.0")
        assertThat(OntologyVersion.parse("1.4.0").underscored).isEqualTo("1_4_0")
        assertThat(OntologyVersion.parse("1.4")).isEqualTo(OntologyVersion.parse("1.4.0"))
    }
}
