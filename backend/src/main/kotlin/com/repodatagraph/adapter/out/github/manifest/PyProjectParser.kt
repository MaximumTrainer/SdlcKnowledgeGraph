package com.repodatagraph.adapter.out.github.manifest

import org.springframework.stereotype.Component

/**
 * `pyproject.toml`, in the two shapes it actually appears in: PEP 621 and Poetry.
 *
 * Read table by table rather than with a TOML parser. The alternative is a new dependency for two
 * known tables, and a general TOML model would still need those same two shapes teaching to it - the
 * ambiguity is in the ecosystem's conventions, not in the syntax.
 */
@Component
class PyProjectParser : ManifestParser {
    override val ecosystem = PYPI

    override fun handles(path: String): Boolean = path.substringAfterLast('/') == "pyproject.toml"

    override fun parse(
        path: String,
        content: String,
    ): Manifest {
        val project = table(content, "project")
        val optional = table(content, "project.optional-dependencies")
        val poetry = table(content, "tool.poetry.dependencies")

        return Manifest(
            path = path,
            ecosystem = PYPI,
            dependencies =
                (
                    quoted(arrayNamed(project, "dependencies")).mapNotNull { requirement(it, DependencyScope.RUNTIME) } +
                        quoted(optional).mapNotNull { requirement(it, DependencyScope.DEV) } +
                        poetryEntries(poetry)
                ).distinct(),
            publishes =
                ENTRY
                    .findAll(project)
                    .firstOrNull { it.groupValues[1] == "name" }
                    ?.let { listOf(it.groupValues[2]) }
                    .orEmpty(),
        )
    }

    /**
     * The lines under `[header]`, up to the next table.
     *
     * Line-based on purpose: a pattern that stopped at the next `[` would stop inside
     * `dependencies = [...]`, which is where the dependencies are.
     */
    private fun table(
        content: String,
        header: String,
    ): String {
        val lines = content.lines()
        val start = lines.indexOfFirst { it.trim() == "[$header]" }
        if (start < 0) return ""
        val rest = lines.drop(start + 1)
        return rest.takeWhile { !it.trimStart().startsWith("[") || it.trimStart().startsWith("[[") }.joinToString("\n")
    }

    /** The contents of `<name> = [ ... ]`, across as many lines as it takes. */
    private fun arrayNamed(
        table: String,
        name: String,
    ): String =
        ARRAY
            .findAll(table)
            .firstOrNull { it.groupValues[1] == name }
            ?.groupValues
            ?.get(2)
            .orEmpty()

    private fun quoted(block: String): List<String> = QUOTED.findAll(block).map { it.groupValues[1] }.toList()

    /** `requests = "^2.31"`, skipping `python`, which is the interpreter rather than a package. */
    private fun poetryEntries(table: String): List<DeclaredDependency> =
        ENTRY
            .findAll(table)
            .filter { it.groupValues[1] != "python" }
            .map { DeclaredDependency(PYPI, it.groupValues[1], it.groupValues[2]) }
            .toList()

    private fun requirement(
        text: String,
        scope: DependencyScope,
    ): DeclaredDependency? {
        val match = REQUIREMENT.matchEntire(text.substringBefore(';').substringBefore('[').trim()) ?: return null
        return DeclaredDependency(PYPI, match.groupValues[1], match.groupValues[2].takeIf { it.isNotEmpty() }, scope)
    }

    private companion object {
        val ARRAY = Regex("""(\w[\w-]*)\s*=\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
        val QUOTED = Regex("""["']([^"']+)["']""")
        val ENTRY = Regex("""^\s*([A-Za-z0-9._-]+)\s*=\s*["']([^"']+)["']""", RegexOption.MULTILINE)
        val REQUIREMENT = Regex("""([A-Za-z0-9._-]+)\s*((?:[<>=!~]=?.*)?)""")
    }
}
