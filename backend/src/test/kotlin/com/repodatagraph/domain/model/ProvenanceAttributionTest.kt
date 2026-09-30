package com.repodatagraph.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** A manual fact says who stated it (#114, FR-3), and a service says for which team (#115, FR-3). */
class ProvenanceAttributionTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `a manual fact carries the principal's subject and kind`() {
        val provenance = Provenance.manual(now, Principal("dan", PrincipalType.USER))

        assertThat(provenance.writtenBy).isEqualTo("dan")
        assertThat(provenance.principalType).isEqualTo("user")
        assertThat(provenance.sourceSystem).isEqualTo(Provenance.MANUAL)
    }

    @Test
    fun `a service principal's fact names the service and the team it acts for`() {
        val provenance = Provenance.manual(now, Principal("triage-agent", PrincipalType.SERVICE, onBehalfOfTeam = "team-payments"))

        assertThat(provenance.writtenBy).isEqualTo("triage-agent")
        assertThat(provenance.principalType).isEqualTo("service")
        assertThat(provenance.onBehalfOfTeam).isEqualTo("team-payments")
    }

    @Test
    fun `a user acts for no team`() {
        assertThat(Provenance.manual(now, Principal("dan", PrincipalType.USER)).onBehalfOfTeam).isNull()
    }

    @Test
    fun `a fact from a connector names no writer yet, until machine principals exist`() {
        val provenance = Provenance(sourceSystem = "github", ingestedAt = now, validFrom = now)

        assertThat(provenance.writtenBy).isNull()
        assertThat(provenance.principalType).isNull()
    }
}
