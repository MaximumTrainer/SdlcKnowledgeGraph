package com.repodatagraph.adapter.out.github

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * CODEOWNERS is the only statement of ownership GitHub actually holds, so it is the only honest
 * source for an OWNED_BY edge. Everything else - the org a repository sits in, who pushed last - is
 * a guess, and a guess recorded at full confidence is worse than no edge at all.
 *
 * The distinction that matters here is team from individual. `@acme/platform-team` is a group that
 * outlives whoever is in it; `@some-person` is a person, and a person is not a Team.
 */
class CodeownersParserTest {
    private val parser = CodeownersParser()

    @Test
    fun `reads the team a pattern assigns ownership to`() {
        val owners = parser.parse("* @acme/platform-team")

        assertThat(owners.teams.map { it.slug }).containsExactly("acme/platform-team")
        assertThat(owners.handles).containsExactly("@acme/platform-team")
    }

    @Test
    fun `ignores comments and blank lines`() {
        val owners =
            parser.parse(
                """
                # Everything in this repository

                * @acme/platform-team
                # /docs/ @acme/docs-team
                """.trimIndent(),
            )

        assertThat(owners.teams.map { it.slug }).containsExactly("acme/platform-team")
    }

    @Test
    fun `does not mistake an individual for a team`() {
        val owners = parser.parse("* @some-person @acme/platform-team")

        assertThat(owners.teams.map { it.slug }).containsExactly("acme/platform-team")
        // Still recorded as an owner of the repository: the fact is true, it just is not a Team.
        assertThat(owners.handles).containsExactly("@some-person", "@acme/platform-team")
    }

    @Test
    fun `does not mistake an email address for a team`() {
        val owners = parser.parse("*.md docs@example.com")

        assertThat(owners.teams).isEmpty()
        assertThat(owners.handles).containsExactly("docs@example.com")
    }

    @Test
    fun `reads every pattern, not just the first`() {
        val owners =
            parser.parse(
                """
                * @acme/platform-team
                /backend/ @acme/backend-team
                /docs/ @acme/platform-team
                """.trimIndent(),
            )

        // Once each, in the order they were declared: a team owning two paths is still one team, and
        // a repeated upsert of the same Team node would inflate what a run reports it wrote.
        assertThat(owners.teams.map { it.slug }).containsExactly("acme/platform-team", "acme/backend-team")
    }

    @Test
    fun `keeps the paths a team was named against`() {
        val owners =
            parser.parse(
                """
                * @acme/platform-team
                /backend/ @acme/backend-team
                /docs/ @acme/platform-team
                """.trimIndent(),
            )

        // The evidence for the edge. "Owns everything" and "owns /docs/" are both ownership, and only
        // the pattern says which was declared.
        assertThat(owners.teams.single { it.slug == "acme/platform-team" }.patterns).containsExactly("*", "/docs/")
    }

    @Test
    fun `treats a team slug as case-insensitive`() {
        val owners = parser.parse("* @Acme/Platform-Team")

        // GitHub resolves @Acme/Platform-Team and @acme/platform-team to the same team, so two
        // spellings must not become two nodes.
        assertThat(owners.teams.map { it.slug }).containsExactly("acme/platform-team")
    }

    @Test
    fun `finds nothing in a file that says nothing`() {
        assertThat(parser.parse("").teams).isEmpty()
        assertThat(parser.parse("# only a comment").handles).isEmpty()
    }

    @Test
    fun `ignores a pattern with no owner at all`() {
        assertThat(parser.parse("/vendor/").handles).isEmpty()
    }
}
