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

    /**
     * A provider id means something only with its provider (#88). On the two hosts that are one
     * provider each the provider is the host's, so a caller holding only GitHub's id need not say
     * GitHub; anywhere else it has to be said.
     */
    @Test
    fun `a provider id on github or gitlab takes its provider from the host (#88)`() {
        val github = derived.expand("Repository", mapOf("url" to "acme/payments", "providerId" to "123456"))
        val gitlab = derived.expand("Repository", mapOf("url" to "https://gitlab.com/acme/payments", "providerId" to "77"))
        val elsewhere = derived.expand("Repository", mapOf("url" to "https://git.acme.test/acme/payments", "providerId" to "9"))

        assertEquals("github", github["provider"])
        assertEquals("gitlab", gitlab["provider"])
        assertEquals(null, elsewhere["provider"])
    }

    @Test
    fun `a provider the caller names is kept, in lower case, over the host's (#88)`() {
        val props = derived.expand("Repository", mapOf("url" to "acme/payments", "provider" to " Other ", "providerId" to "5"))

        assertEquals("other", props["provider"])
    }

    @Test
    fun `a provider id sent as a number is stored as the string it is, trimmed (#88)`() {
        assertEquals("123456", derived.expand("Repository", mapOf("url" to "acme/payments", "providerId" to 123456))["providerId"])
        assertEquals("123456", derived.expand("Repository", mapOf("url" to "acme/payments", "providerId" to " 123456 "))["providerId"])
    }

    @Test
    fun `no provider is invented for a repository without a provider id (#88)`() {
        assertEquals(false, derived.expand("Repository", mapOf("url" to "acme/payments")).containsKey("provider"))
    }

    /** `orgRepo` is derived now (#88, FR4), so it is named as such rather than as an unknown property. */
    @Test
    fun `orgRepo is derived from url and is not accepted from a caller (#88)`() {
        assertEquals(mapOf("orgRepo" to "url"), DerivedProperties.derivedOnly("Repository"))
        assertEquals(emptyMap<String, String>(), DerivedProperties.derivedOnly("Team"))
    }
}
