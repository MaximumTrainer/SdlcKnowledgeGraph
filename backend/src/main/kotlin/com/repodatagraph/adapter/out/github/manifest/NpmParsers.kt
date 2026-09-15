package com.repodatagraph.adapter.out.github.manifest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import org.springframework.stereotype.Component

/** npm's declaration: what the package is, and what it asks for. */
@Component
class PackageJsonParser : ManifestParser {
    override val ecosystem = NPM

    override fun handles(path: String): Boolean = path.substringAfterLast('/') == "package.json"

    override fun parse(
        path: String,
        content: String,
    ): Manifest {
        val root = readJson(path, content)
        return Manifest(
            path = path,
            ecosystem = NPM,
            dependencies =
                declared(root, "dependencies", DependencyScope.RUNTIME) +
                    declared(root, "devDependencies", DependencyScope.DEV) +
                    // Optional dependencies are needed at runtime when they are there at all, which is
                    // a stronger claim than "dev" and the honest one for a vulnerability question.
                    declared(root, "optionalDependencies", DependencyScope.RUNTIME),
            publishes = listOfNotNull(root.path("name").takeIf { it.isTextual }?.asText()),
        )
    }

    private fun declared(
        root: JsonNode,
        field: String,
        scope: DependencyScope,
    ): List<DeclaredDependency> =
        root
            .path(field)
            .properties()
            .map { (name, version) -> DeclaredDependency(NPM, name, version.asText(), scope) }
}

/**
 * npm's lockfile: what a build actually resolved, transitive closure and all.
 *
 * Off by default, and deliberately. A lockfile is thousands of packages for a repository that
 * declares twenty, and recording them all as first-class nodes makes "who depends on this" a
 * question about npm's install graph rather than about this organisation's software. It is worth
 * having for "which repositories ship this exact vulnerable version", which is why it exists at all.
 */
@Component
class PackageLockParser : ManifestParser {
    override val ecosystem = NPM

    override val readsLockfile = true

    override fun handles(path: String): Boolean = path.substringAfterLast('/') == "package-lock.json"

    override fun parse(
        path: String,
        content: String,
    ): Manifest {
        val root = readJson(path, content)
        return Manifest(
            path = path,
            ecosystem = NPM,
            dependencies =
                root
                    .path("packages")
                    .properties()
                    .asSequence()
                    // "" is the project itself; a package with no version is a link or a workspace.
                    .filter { (location, entry) -> location.isNotEmpty() && entry.path("version").isTextual }
                    .map { (location, entry) ->
                        DeclaredDependency(
                            ecosystem = NPM,
                            // The path says where npm put it, which is an installation detail: two
                            // copies of one library at different depths are still one library.
                            name = location.substringAfterLast("node_modules/"),
                            version = entry.path("version").asText(),
                            scope = if (entry.path("dev").asBoolean()) DependencyScope.DEV else DependencyScope.RUNTIME,
                        )
                    }.distinct()
                    .toList(),
            publishes =
                listOfNotNull(
                    root
                        .path("packages")
                        .path("")
                        .path("name")
                        .takeIf { it.isTextual }
                        ?.asText(),
                ),
        )
    }
}

internal const val NPM = "npm"

private val JSON = JsonMapper.builder().build()

internal fun readJson(
    path: String,
    content: String,
): JsonNode =
    try {
        JSON.readTree(content)
    } catch (malformed: com.fasterxml.jackson.core.JacksonException) {
        // A manifest that will not parse is a fact about that repository, not a reason to stop
        // reading the rest of the estate.
        throw UnreadableManifestException(path, malformed)
    }
