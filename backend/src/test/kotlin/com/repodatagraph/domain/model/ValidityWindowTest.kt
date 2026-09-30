package com.repodatagraph.domain.model

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Which facts an as-of read sees (#93, FR-4), and the validity a write restating a fact gives it.
 *
 * A fact holds over the half-open interval `[validFrom, validTo)`: from the instant it began, up to
 * but not including the instant it ended, so two facts where one ended as the next began are never
 * both current at once.
 */
class ValidityWindowTest {
    private val from = Instant.parse("2026-09-30T01:00:00Z")
    private val to = Instant.parse("2026-09-30T01:58:00Z")
    private val closed = Provenance(sourceSystem = "manual", ingestedAt = from, validFrom = from, validTo = to)
    private val open = closed.copy(validTo = null)

    @Test
    fun `a closed fact holds from its validFrom, inclusive, to its validTo, exclusive`() {
        assertThat(ValidityWindow.contains(closed, from)).isTrue()
        assertThat(ValidityWindow.contains(closed, Instant.parse("2026-09-30T01:30:00Z"))).isTrue()
        assertThat(ValidityWindow.contains(closed, to)).isFalse()
        assertThat(ValidityWindow.contains(closed, Instant.parse("2026-09-30T00:59:59Z"))).isFalse()
    }

    @Test
    fun `an open fact holds from its validFrom on`() {
        assertThat(ValidityWindow.contains(open, Instant.parse("2099-01-01T00:00:00Z"))).isTrue()
        assertThat(ValidityWindow.contains(open, Instant.parse("2026-09-30T00:00:00Z"))).isFalse()
    }

    @Test
    fun `an asOf parameter is an ISO-8601 instant, and absent means now`() {
        assertThat(asOfParameter(null)).isNull()
        assertThat(asOfParameter("2026-09-30T01:30:00Z")).isEqualTo(Instant.parse("2026-09-30T01:30:00Z"))
        assertThat(asOfParameter("2026-09-30T02:30:00+01:00")).isEqualTo(Instant.parse("2026-09-30T01:30:00Z"))
    }

    @Test
    fun `anything else is refused naming asOf`() {
        listOf("yesterday", "2026-09-30", "01:30", "", " ").forEach { value ->
            assertThatThrownBy { asOfParameter(value) }
                .describedAs(value)
                .isInstanceOf(InvalidQueryParameterException::class.java)
                .hasFieldOrPropertyWithValue("field", "asOf")
        }
    }

    @Test
    fun `restating a current fact keeps the validFrom it began with`() {
        val written = Instant.parse("2026-09-30T09:00:00Z")

        assertThat(Provenance.validFromFor(open, written, closes = false)).isEqualTo(from)
        assertThat(Provenance.validFromFor(open, written, closes = true)).isEqualTo(from)
    }

    @Test
    fun `restating a closed fact as current begins it again, and keeping it closed keeps its start`() {
        val written = Instant.parse("2026-09-30T09:00:00Z")

        assertThat(Provenance.validFromFor(closed, written, closes = false)).isEqualTo(written)
        assertThat(Provenance.validFromFor(closed, written, closes = true)).isEqualTo(from)
    }

    @Test
    fun `a fact stated for the first time begins when it is written`() {
        val written = Instant.parse("2026-09-30T09:00:00Z")

        assertThat(Provenance.validFromFor(null, written, closes = false)).isEqualTo(written)
    }
}
