package com.repodatagraph.adapter.out.github.manifest

import org.springframework.stereotype.Component

/**
 * A pip requirements file.
 *
 * The file name carries information the contents do not: `requirements-dev.txt` and
 * `requirements/test.txt` are conventions, not standards, but they are the only signal a repository
 * gives about whether these packages ship.
 */
@Component
class RequirementsTxtParser : ManifestParser {
    override val ecosystem = PYPI

    override fun handles(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.startsWith("requirements") && name.endsWith(".txt")
    }

    override fun parse(
        path: String,
        content: String,
    ): Manifest {
        val scope = if (DEV_NAMES.any { it in path.lowercase() }) DependencyScope.DEV else DependencyScope.RUNTIME
        return Manifest(
            path = path,
            ecosystem = PYPI,
            dependencies =
                content
                    .lineSequence()
                    .map { it.substringBefore('#').trim() }
                    // `-r` includes another file, `-e` is an editable checkout, and an option is not a
                    // package: each is read on its own or not at all.
                    .filter { it.isNotEmpty() && !it.startsWith("-") }
                    .mapNotNull { requirement(it, scope) }
                    .toList(),
        )
    }

    private fun requirement(
        line: String,
        scope: DependencyScope,
    ): DeclaredDependency? {
        // Environment markers and extras describe when and how, not what.
        val withoutMarker = line.substringBefore(';').substringBefore('[').trim()
        val match = REQUIREMENT.matchEntire(withoutMarker) ?: return null
        return DeclaredDependency(
            ecosystem = PYPI,
            name = match.groupValues[1].trim(),
            version = match.groupValues[2].trim().takeIf { it.isNotEmpty() },
            scope = scope,
        )
    }

    private companion object {
        val DEV_NAMES = listOf("dev", "test")
        val REQUIREMENT = Regex("""([A-Za-z0-9._-]+)\s*((?:[<>=!~]=?.*)?)""")
    }
}

internal const val PYPI = "pypi"
