package com.repodatagraph.adapter.out.github.manifest

import org.springframework.stereotype.Component

/**
 * `go.mod`, which is both a declaration and a lockfile.
 *
 * Go writes the whole transitive closure into the file and marks the parts that are not this
 * module's own choice `// indirect`. Those are skipped: a transitive requirement is a fact about
 * somebody else's module, and recording it here would make "what does this repository depend on"
 * unanswerable for every Go repository in the estate.
 */
@Component
class GoModParser : ManifestParser {
    override val ecosystem = GO

    override fun handles(path: String): Boolean = path.substringAfterLast('/') == "go.mod"

    override fun parse(
        path: String,
        content: String,
    ): Manifest =
        Manifest(
            path = path,
            ecosystem = GO,
            dependencies =
                content
                    .lineSequence()
                    .map { it.trim() }
                    .filterNot { it.contains(INDIRECT) }
                    .mapNotNull { requirement(it) }
                    .distinct()
                    .toList(),
            publishes =
                MODULE
                    .find(content)
                    ?.groupValues
                    ?.get(1)
                    ?.let { listOf(it) }
                    .orEmpty(),
        )

    /** Both shapes: a `require (` block's entries, and a single-line `require x v1`. */
    private fun requirement(line: String): DeclaredDependency? {
        val withoutKeyword = line.removePrefix("require").trim()
        // `require (` opens a block; the entries are the lines after it.
        if (withoutKeyword.startsWith("(")) return null
        return REQUIREMENT
            .matchEntire(withoutKeyword.substringBefore("//").trim())
            ?.let { DeclaredDependency(GO, it.groupValues[1], it.groupValues[2]) }
    }

    private companion object {
        const val INDIRECT = "// indirect"
        val MODULE = Regex("""^module\s+(\S+)""", RegexOption.MULTILINE)
        val REQUIREMENT = Regex("""([a-zA-Z0-9./_~-]+\.[a-zA-Z0-9./_~-]+)\s+(v\S+)""")
    }
}

internal const val GO = "go"
