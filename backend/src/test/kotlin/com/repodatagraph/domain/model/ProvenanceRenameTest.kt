package com.repodatagraph.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What a rename records (#88): the keys a node was known by before, so a caller still holding one
 * finds it. The write that renames states its own provenance; only the history is carried over.
 */
class ProvenanceRenameTest {
    private val earlier = Instant.parse("2026-01-01T00:00:00Z")
    private val now = Instant.parse("2026-09-30T00:00:00Z")

    @Test
    fun `a rename records the key the node is leaving, after the ones it left before`() {
        val before = Provenance.manual(earlier).copy(previousKeys = listOf("github.com/acme/billing"))

        val after = Provenance.manual(now).afterRename(before, from = "github.com/acme/payments", to = "github.com/acme-platform/payments")

        assertEquals(listOf("github.com/acme/billing", "github.com/acme/payments"), after.previousKeys)
        assertEquals(now, after.ingestedAt)
    }

    @Test
    fun `renaming back to a previous key drops it from the history, since it is current again`() {
        val before = Provenance.manual(earlier).copy(previousKeys = listOf("github.com/acme/payments"))

        val after = Provenance.manual(now).afterRename(before, from = "github.com/acme-platform/payments", to = "github.com/acme/payments")

        assertEquals(listOf("github.com/acme-platform/payments"), after.previousKeys)
    }

    @Test
    fun `a key is recorded once however often the node returns to it`() {
        val before = Provenance.manual(earlier).copy(previousKeys = listOf("a/b/c", "d/e/f"))

        assertEquals(listOf("a/b/c", "d/e/f"), Provenance.manual(now).afterRename(before, from = "a/b/c", to = "x/y/z").previousKeys)
    }

    @Test
    fun `a fact that was never renamed has no previous keys`() {
        assertEquals(emptyList<String>(), Provenance.manual(now).previousKeys)
    }
}
