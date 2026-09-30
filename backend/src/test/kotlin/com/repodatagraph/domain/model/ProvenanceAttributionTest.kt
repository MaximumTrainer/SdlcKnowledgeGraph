package com.repodatagraph.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** A manual fact says who stated it (#114, FR-3). */
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
    fun `the anonymous principal is recorded as anonymous, not left blank`() {
        val provenance = Provenance.manual(now, Principal.ANONYMOUS)

        assertThat(provenance.writtenBy).isEqualTo("anonymous")
        assertThat(provenance.principalType).isEqualTo("user")
    }

    @Test
    fun `a fact from a connector names no writer yet, until machine principals exist`() {
        val provenance = Provenance(sourceSystem = "github", ingestedAt = now, validFrom = now)

        assertThat(provenance.writtenBy).isNull()
        assertThat(provenance.principalType).isNull()
    }
}
