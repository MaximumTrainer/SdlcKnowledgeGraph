package com.repodatagraph.adapter.out.github

import org.springframework.stereotype.Component

/**
 * What a CODEOWNERS file says, split into what it claims and which of those claims are teams.
 *
 * @param handles every owner, as the file spells them: `@person`, `@org/team` or an email address
 * @param teams the subset that are teams, normalised to `org/team` in lower case
 */
data class Codeowners(
    val handles: List<String> = emptyList(),
    val teams: List<String> = emptyList(),
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
 * Only the owners are read, not the patterns they apply to. "This team owns `/backend/`" is a
 * finer-grained claim than the graph makes anywhere, and flattening it to "this team owns this
 * repository" is the claim OWNED_BY is declared to carry.
 */
@Component
class CodeownersParser {
    fun parse(content: String): Codeowners {
        val handles = LinkedHashSet<String>()
        content.lineSequence().forEach { line ->
            val withoutComment = line.substringBefore(COMMENT).trim()
            if (withoutComment.isEmpty()) return@forEach
            // The first token is the path pattern; everything after it is an owner.
            withoutComment
                .split(WHITESPACE)
                .drop(1)
                .filter { it.isNotBlank() }
                .forEach { handles += it }
        }

        val teams =
            handles
                .filter { it.startsWith(AT) && it.contains(SLASH) }
                .map { it.removePrefix(AT).lowercase() }
                .distinct()

        return Codeowners(handles = handles.toList(), teams = teams)
    }

    private companion object {
        const val COMMENT = "#"
        const val AT = "@"
        const val SLASH = "/"
        val WHITESPACE = Regex("\\s+")
    }
}
