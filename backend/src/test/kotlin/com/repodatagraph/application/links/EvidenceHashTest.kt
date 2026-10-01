package com.repodatagraph.application.links

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a rejection is pinned to (#28, FR5): a rejected candidate stays rejected while the evidence
 * behind it is the same, and comes back for review once it changes. So the hash has to be the same
 * for the same evidence however it was assembled, and different for any difference in it.
 */
class EvidenceHashTest {
    @Test
    fun `the same rule and evidence hash the same, whatever order the evidence came in`() {
        val one = EvidenceHash.of("naming", linkedMapOf("name" to "billing-prod", "normalised" to "billing"))
        val other = EvidenceHash.of("naming", linkedMapOf("normalised" to "billing", "name" to "billing-prod"))

        assertEquals(one, other)
        assertTrue(Regex("[0-9a-f]{16}").matches(one), one)
    }

    @Test
    fun `a different value, key or rule hashes differently`() {
        val base = EvidenceHash.of("naming", mapOf("name" to "billing-prod"))

        assertNotEquals(base, EvidenceHash.of("naming", mapOf("name" to "billing-dev")))
        assertNotEquals(base, EvidenceHash.of("naming", mapOf("label" to "billing-prod")))
        assertNotEquals(base, EvidenceHash.of("iac", mapOf("name" to "billing-prod")))
    }

    @Test
    fun `entries cannot be run together to collide`() {
        assertNotEquals(EvidenceHash.of("iac", mapOf("a" to "b=c")), EvidenceHash.of("iac", mapOf("a=b" to "c")))
    }

    @Test
    fun `a candidate's id is stable for a resource and a repository, and differs for any other pair`() {
        val id = EvidenceHash.candidateId("aws:arn:aws:sqs:eu-west-1:1:billing-prod", "github.com/acme/billing")

        assertEquals(id, EvidenceHash.candidateId("aws:arn:aws:sqs:eu-west-1:1:billing-prod", "github.com/acme/billing"))
        assertNotEquals(id, EvidenceHash.candidateId("aws:arn:aws:sqs:eu-west-1:1:billing-prod", "github.com/acme/payments"))
        assertTrue(Regex("[0-9a-f]{16}").matches(id), id)
    }

    @Test
    fun `evidence is stored as sorted key=value entries and read back`() {
        val evidence = mapOf("path" to "infra/main.tf", "matched" to "name")

        val stored = EvidenceHash.encode(evidence)

        assertEquals(listOf("matched=name", "path=infra/main.tf"), stored)
        assertEquals(evidence, EvidenceHash.decode(stored))
        assertEquals(mapOf("reference" to "a=b"), EvidenceHash.decode(listOf("reference=a=b")))
    }
}
