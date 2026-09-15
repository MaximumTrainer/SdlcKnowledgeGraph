package com.repodatagraph.adapter.out.github.manifest

/** Whether a dependency is needed to run the thing, or only to build and test it. */
enum class DependencyScope {
    RUNTIME,
    DEV,
    ;

    /** The spelling the ontology declares. */
    fun declared(): String = name.lowercase()
}

/**
 * One dependency a manifest asks for.
 *
 * The version is as written, not as resolved: `^4.18.0` is what the repository asked for, and the
 * version it happens to get today is a fact about a build rather than about the repository.
 */
data class DeclaredDependency(
    val ecosystem: String,
    val name: String,
    val version: String? = null,
    val scope: DependencyScope = DependencyScope.RUNTIME,
)

/**
 * What one manifest said.
 *
 * [publishes] is what this repository puts into the ecosystem under its own name. It is how a
 * dependency on `@acme/billing` can be resolved to the repository that publishes it rather than
 * recorded as a third-party library nobody here controls.
 */
data class Manifest(
    val path: String,
    val ecosystem: String,
    val dependencies: List<DeclaredDependency> = emptyList(),
    val publishes: List<String> = emptyList(),
)

/** A manifest that is there but cannot be read. Fails the repository, not the run. */
class UnreadableManifestException(
    val path: String,
    cause: Throwable? = null,
) : RuntimeException("cannot read $path", cause)

/**
 * Reads one manifest format.
 *
 * One implementation per format, chosen by file name, so adding an ecosystem is adding a class
 * rather than a branch in a growing conditional - and so a format nobody has written a parser for
 * is simply not read, rather than read wrongly.
 *
 * Every parser reports what the manifest *declares*, not what a build resolves. A resolved tree is a
 * fact about one build on one day; a declaration is a fact about the repository, and only the second
 * is worth keeping in a graph that outlives the build.
 */
interface ManifestParser {
    /** The ecosystem this parser's dependencies belong to: npm, maven, pypi or go. */
    val ecosystem: String

    /** Whether this parser reads the file at [path]. Matched on the file name, not the directory. */
    fun handles(path: String): Boolean

    /**
     * Whether this parser reads a lockfile rather than a declaration.
     *
     * A lockfile is the transitive closure of a build, so it is read only when asked for: see
     * `connectors.github.manifests.include-lockfiles`.
     */
    val readsLockfile: Boolean get() = false

    /** @throws UnreadableManifestException if the file is this format but malformed. */
    fun parse(
        path: String,
        content: String,
    ): Manifest
}
