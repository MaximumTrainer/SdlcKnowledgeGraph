package com.repodatagraph.domain.model

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.exception.InvalidQueryParameterException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.DefaultResourceLoader

/**
 * What a change-impact question may ask (#87, FR1): depth 1 to 4, default 2; limit 1 to 500,
 * default 50. A value out of bounds is refused naming the field and the bound, before anything is
 * walked, so no caller can ask for an unbounded answer.
 */
class ChangeImpactQueryTest {
    @Test
    fun `the defaults are depth 2 and limit 50, with no paths and no sha`() {
        val query = ChangeImpactQuery.of("github.com/acme/payments", null, null, null, null)

        assertEquals(ChangeImpactQuery("github.com/acme/payments"), query)
        assertEquals(2, query.depth)
        assertEquals(50, query.limit)
        assertEquals(emptyList<String>(), query.paths)
        assertNull(query.sha)
        assertEquals(NodeKey("Repository", "github.com/acme/payments"), query.repository)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 5, 9, -1])
    fun `a depth outside 1 to 4 is refused naming the field and the bound`(depth: Int) {
        val refused = assertThrows<InvalidQueryParameterException> { ChangeImpactQuery("github.com/acme/payments", depth = depth) }

        assertEquals("depth", refused.field)
        assertEquals("depth must be between 1 and 4, was $depth", refused.message)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 501, -3])
    fun `a limit outside 1 to 500 is refused naming the field and the bound`(limit: Int) {
        val refused = assertThrows<InvalidQueryParameterException> { ChangeImpactQuery("github.com/acme/payments", limit = limit) }

        assertEquals("limit", refused.field)
        assertEquals("limit must be between 1 and 500, was $limit", refused.message)
    }

    @Test
    fun `the bounds themselves are allowed`() {
        ChangeImpactQuery("github.com/acme/payments", depth = 1, limit = 1)
        ChangeImpactQuery("github.com/acme/payments", depth = 4, limit = 500)
    }

    @Test
    fun `a missing or blank repositoryKey is refused naming it`() {
        assertEquals(
            "repositoryKey",
            assertThrows<InvalidQueryParameterException> { ChangeImpactQuery.of(null, null, null, null, null) }.field,
        )
        assertEquals("repositoryKey", assertThrows<InvalidQueryParameterException> { ChangeImpactQuery(" ") }.field)
    }

    @Test
    fun `paths are bounded in number and length, and none may be blank`() {
        val tooMany = List(ChangeImpactQuery.MAX_PATHS + 1) { "src/file$it.kt" }
        val tooLong = "a/".repeat(ChangeImpactQuery.MAX_PATH_LENGTH)

        assertEquals("paths", assertThrows<InvalidQueryParameterException> { ChangeImpactQuery("r", tooMany) }.field)
        assertEquals("paths", assertThrows<InvalidQueryParameterException> { ChangeImpactQuery("r", listOf(tooLong)) }.field)
        assertEquals("paths", assertThrows<InvalidQueryParameterException> { ChangeImpactQuery("r", listOf(" ")) }.field)
        assertEquals(
            "paths",
            assertThrows<InvalidQueryParameterException> { ChangeImpactQuery.of("r", listOf(null), null, null, null) }.field,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "xyz", "abc", "4f1c2d9; MATCH (n) DETACH DELETE n"])
    fun `a sha that is not a commit hash is refused naming it`(sha: String) {
        assertEquals("sha", assertThrows<InvalidQueryParameterException> { ChangeImpactQuery("r", sha = sha) }.field)
    }

    @Test
    fun `an abbreviated or a full sha is a sha`() {
        ChangeImpactQuery("r", sha = "4f1c2d9")
        ChangeImpactQuery("r", sha = "a".repeat(40))
        ChangeImpactQuery("r", sha = "b".repeat(64))
    }

    @Test
    fun `an environment's tier is read as declared, and anything else - missing included - is other`() {
        assertEquals(EnvironmentTier.PRODUCTION, EnvironmentTier.of("production"))
        assertEquals(EnvironmentTier.PRE_PRODUCTION, EnvironmentTier.of("pre_production"))
        assertEquals(EnvironmentTier.DEVELOPMENT, EnvironmentTier.of("development"))
        assertEquals(EnvironmentTier.OTHER, EnvironmentTier.of("other"))
        assertEquals(EnvironmentTier.OTHER, EnvironmentTier.of(null))
        assertEquals(EnvironmentTier.OTHER, EnvironmentTier.of("gold"))
    }

    @Test
    fun `the tiers are the ones the registry declares on Environment, so the two cannot drift`() {
        val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
        val tier = registry.nodeType("Environment")?.properties?.single { it.name == "tier" }

        assertEquals(EnvironmentTier.entries.map { it.wire }.toSet(), tier?.enum?.toSet())
        assertTrue(tier?.required == false, "tier is read as other where it is missing, so a graph written before it still reads")
    }

    /** A GitHub App knows a repository by its id, not its remote (#88, FR5): either names it. */
    @Test
    fun `a provider id names the repository in place of its key, github unless another is named (#88)`() {
        val byId = ChangeImpactQuery.of(null, null, null, null, null, provider = null, providerId = " 123456 ")
        val onGitLab = ChangeImpactQuery.of(null, null, null, null, null, provider = "GitLab", providerId = "77")

        assertEquals(mapOf("provider" to "github", "providerId" to "123456"), byId.alias)
        assertEquals(mapOf("provider" to "gitlab", "providerId" to "77"), onGitLab.alias)
        assertNull(ChangeImpactQuery("github.com/acme/payments").alias)
    }

    @Test
    fun `without a repository key or a provider id the query is refused naming the repository key (#88)`() {
        val refused = assertThrows<InvalidQueryParameterException> { ChangeImpactQuery.of(" ", null, null, null, null, "github", " ") }

        assertEquals("repositoryKey", refused.field)
        assertEquals("repositoryKey or providerId is required", refused.message)
    }
}
