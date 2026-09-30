package com.repodatagraph.domain.identity

import com.repodatagraph.domain.exception.InvalidGitRemoteException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What the server fills in before a write is validated. A Change and a PullRequest name their
 * repository by its key, and a caller holding a remote in another notation must land on the same
 * node the Repository is stored under (#85), so the key is stored as the repository's own.
 */
class DerivedPropertiesTest {
    private val derived = DerivedProperties(GitRemoteParser())

    @Test
    fun `a change names its repository by the repository's key, and its sha in lower case (#85)`() {
        val props =
            derived.expand(
                "Change",
                mapOf(
                    "repositoryKey" to "git@github.com:Acme/Payments.git",
                    "sha" to " A1B2C3 ",
                    "title" to "Fix",
                ),
            )

        assertEquals(mapOf("repositoryKey" to "github.com/acme/payments", "sha" to "a1b2c3", "title" to "Fix"), props)
    }

    @Test
    fun `a pull request names its repository by the repository's key (#85)`() {
        val props = derived.expand("PullRequest", mapOf("repositoryKey" to "https://github.com/acme/payments", "number" to 42))

        assertEquals("github.com/acme/payments", props["repositoryKey"])
        assertEquals(42, props["number"])
    }

    @Test
    fun `a missing repository key is left for validation to report (#85)`() {
        assertEquals(mapOf("sha" to "a1b2c3"), derived.expand("Change", mapOf("sha" to "a1b2c3")))
    }

    @Test
    fun `a repository key that is not a remote is refused as one (#85)`() {
        assertThrows<InvalidGitRemoteException> { derived.expand("Change", mapOf("repositoryKey" to "not a remote", "sha" to "a1")) }
    }

    @Test
    fun `a work item's uri is left exactly as written (#85)`() {
        val props = mapOf("uri" to "HTTPS://Acme.atlassian.net/browse/PAY-42", "system" to "jira")

        assertEquals(props, derived.expand("ExternalWorkItem", props))
    }
}
