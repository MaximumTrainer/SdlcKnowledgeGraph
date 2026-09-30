package com.repodatagraph.domain.ontology

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The environment alias table (#98, FR-3): which spellings name which environment. It used to be a
 * map in IdentityResolver; it is environments.yaml now, validated when the registry loads, so a table
 * that could fold one spelling into two environments stops the application rather than splitting
 * deployments between them depending on who wrote first.
 */
class EnvironmentAliasesTest {
    private val table =
        EnvironmentAliases(
            listOf(
                EnvironmentDef("production", "Serves customers", listOf("prod", "prd", "live")),
                EnvironmentDef("staging", "The last stop before production", listOf("stg", "stage")),
            ),
        )

    @ParameterizedTest(name = "''{0}'' names ''{1}''")
    @CsvSource(
        "prod, production",
        "PRD, production",
        "' live ', production",
        "production, production",
        "stg, staging",
        "staging, staging",
        "qa, qa",
        "Dogfood, dogfood",
    )
    fun `a name is lower-cased and trimmed, and an alias folds into its canonical name`(
        given: String,
        expected: String,
    ) {
        assertThat(table.canonical(given)).isEqualTo(expected)
    }

    @Test
    fun `without a table every name is its own, lower-cased`() {
        assertThat(EnvironmentAliases.NONE.canonical("Prod")).isEqualTo("prod")
    }

    @Test
    fun `an alias that maps to two canonical names is refused, naming both`() {
        assertThatThrownBy {
            EnvironmentAliases(
                listOf(
                    EnvironmentDef("production", "Serves customers", listOf("live")),
                    EnvironmentDef("demo", "Shown to prospects", listOf("live")),
                ),
            )
        }.isInstanceOf(InvalidOntologyException::class.java)
            .hasMessageContaining("'live'")
            .hasMessageContaining("production")
            .hasMessageContaining("demo")
    }

    @Test
    fun `an alias that is another environment's canonical name is refused`() {
        assertThatThrownBy {
            EnvironmentAliases(
                listOf(
                    EnvironmentDef("production", "Serves customers", listOf("staging")),
                    EnvironmentDef("staging", "The last stop before production", emptyList()),
                ),
            )
        }.isInstanceOf(InvalidOntologyException::class.java).hasMessageContaining("'staging'")
    }

    @Test
    fun `a canonical name declared twice is refused`() {
        assertThatThrownBy {
            EnvironmentAliases(
                listOf(
                    EnvironmentDef("production", "Serves customers", listOf("prod")),
                    EnvironmentDef("production", "Serves customers again", listOf("prd")),
                ),
            )
        }.isInstanceOf(InvalidOntologyException::class.java).hasMessageContaining("'production'")
    }

    @Test
    fun `a name or alias that is not lower-case and trimmed is refused, since keys are`() {
        assertThatThrownBy { EnvironmentAliases(listOf(EnvironmentDef("Production", "Serves customers", emptyList()))) }
            .isInstanceOf(InvalidOntologyException::class.java)
            .hasMessageContaining("'Production'")
        assertThatThrownBy { EnvironmentAliases(listOf(EnvironmentDef("production", "Serves customers", listOf(" prod")))) }
            .isInstanceOf(InvalidOntologyException::class.java)
        assertThatThrownBy { EnvironmentAliases(listOf(EnvironmentDef("production", "Serves customers", listOf("")))) }
            .isInstanceOf(InvalidOntologyException::class.java)
    }

    @Test
    fun `the table can be read back as alias to canonical name`() {
        assertThat(table.asMap()).containsExactly(
            entry("prod", "production"),
            entry("prd", "production"),
            entry("live", "production"),
            entry("stg", "staging"),
            entry("stage", "staging"),
        )
    }
}
