package com.repodatagraph.application

import com.repodatagraph.domain.exception.UnknownSourceSystemException
import com.repodatagraph.domain.model.Principal
import com.repodatagraph.domain.model.PrincipalType
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.SourceSystemDef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The provenance of a fact stated through the API (#117): the source it names, checked first against
 * the registry and then against what the principal may speak for, and who stated it.
 */
class StatedProvenanceTest {
    private val registry =
        OntologyRegistry("1.0.0", emptyList(), emptyList(), sources = listOf(SourceSystemDef("manual"), SourceSystemDef("github")))
    private val asked = mutableListOf<String>()
    private val stated =
        StatedProvenance(registry, { asked += it }) { Principal("github-connector", PrincipalType.SERVICE, "team-platform") }

    @Test
    fun `a known source is authorised, then stated with who stated it`() {
        val now = Instant.parse("2026-09-30T12:00:00Z")

        val provenance = stated.forWrite("github", now)

        assertThat(asked).containsExactly("github")
        assertThat(provenance.sourceSystem).isEqualTo("github")
        assertThat(provenance.ingestedAt).isEqualTo(now)
        assertThat(provenance.validFrom).isEqualTo(now)
        assertThat(provenance.confidence).isEqualTo(1.0)
        assertThat(provenance.inferred).isFalse()
        assertThat(provenance.writtenBy).isEqualTo("github-connector")
        assertThat(provenance.principalType).isEqualTo("service")
        assertThat(provenance.onBehalfOfTeam).isEqualTo("team-platform")
    }

    @Test
    fun `an unknown source is refused before anyone's scopes are looked at`() {
        // A scope for a source nobody declared means nothing, so the refusal is the 400, not a 403.
        assertThatThrownBy { stated.forWrite("jira") }
            .isInstanceOf(UnknownSourceSystemException::class.java)
            .hasFieldOrPropertyWithValue("known", listOf("manual", "github"))

        assertThat(asked).isEmpty()
    }
}
