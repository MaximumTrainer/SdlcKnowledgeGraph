package com.repodatagraph.adapter.out.github

import org.springframework.stereotype.Component

/**
 * A team that owns something, and which paths it was named against.
 *
 * The patterns are kept rather than discarded because they are the evidence for the edge: "owns
 * everything" and "owns /docs/" are both recorded as ownership, and only the patterns say which was
 * claimed.
 *
 * @param slug the team as GitHub resolves it, `org/team` in lower case
 */
data class TeamOwnership(
    val slug: String,
    val patterns: List<String> = emptyList(),
)

/**
 * What a CODEOWNERS file says, split into what it claims and which of those claims are teams.
 *
 * @param handles every owner, as the file spells them: `@person`, `@org/team` or an email address
 * @param teams the subset that are teams, each with the paths it was named against
 */
data class Codeowners(
    val handles: List<String> = emptyList(),
    val teams: List<TeamOwnership> = emptyList(),
) {
    companion object {
        /** No CODEOWNERS, or one that assigns nothing. Not the same as "nobody owns this". */
        val NONE = Codeowners()
    }
}

/**
 * Reads CODEOWNERS, which is the only place GitHub actually records who owns a repository.
 *
 * Everything else that looks like ownership - the org a repository sits in, who pushed to it last,
 * who has admin - is a proxy, and a proxy recorded as a fact at full confidence is worse than no
 * edge at all: it cannot be told apart from ownership somebody declared.
 *
 * The graph records ownership of a repository, not of a path inside it - that is a finer-grained
 * claim than anything else it holds. The patterns are still carried onto the edge, because they are
 * what the claim rests on: "owns everything" and "owns /docs/" are both ownership, and only the
 * pattern says which was declared.
 */
@Component
class CodeownersParser {
    fun parse(content: String): Codeowners {
        val handles = LinkedHashSet<String>()
        val patternsByTeam = LinkedHashMap<String, MutableList<String>>()

        content.lineSequence().forEach { line ->
            val tokens =
                line
                    .substringBefore(COMMENT)
                    .trim()
                    .split(WHITESPACE)
                    .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return@forEach
            // The first token is the path pattern; everything after it is an owner.
            val pattern = tokens.first()
            tokens.drop(1).forEach { owner -> record(owner, pattern, handles, patternsByTeam) }
        }

        return Codeowners(
            handles = handles.toList(),
            teams = patternsByTeam.map { (slug, patterns) -> TeamOwnership(slug, patterns.toList()) },
        )
    }

    private fun record(
        owner: String,
        pattern: String,
        handles: MutableSet<String>,
        patternsByTeam: MutableMap<String, MutableList<String>>,
    ) {
        handles += owner
        val slug = teamSlug(owner) ?: return
        val patterns = patternsByTeam.getOrPut(slug) { mutableListOf() }
        if (pattern !in patterns) patterns += pattern
    }

    /**
     * A team, or null for anything else.
     *
     * GitHub resolves `@Acme/Platform-Team` and `@acme/platform-team` to one team, so two spellings
     * must not become two nodes. An owner with no slash is a person, and an email address is neither.
     */
    private fun teamSlug(owner: String): String? =
        owner
            .takeIf { it.startsWith(AT) && it.contains(SLASH) }
            ?.removePrefix(AT)
            ?.lowercase()

    private companion object {
        const val COMMENT = "#"
        const val AT = "@"
        const val SLASH = "/"
        val WHITESPACE = Regex("\\s+")
    }
}
