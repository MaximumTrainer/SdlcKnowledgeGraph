package com.repodatagraph.adapter.out.github

import com.repodatagraph.adapter.out.github.iac.IacIndexer
import com.repodatagraph.adapter.out.github.manifest.DeclaredDependency
import com.repodatagraph.adapter.out.github.manifest.ManifestParser
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * A dependency that looks like something this organisation publishes, held back until it is known
 * which repository publishes it.
 *
 * Repositories are read one at a time, so at the moment `payments` says it depends on
 * `@acme/billing`, the repository that publishes `@acme/billing` may not have been read yet.
 */
data class PendingInternalDependency(
    val from: NodeKey,
    val packageName: String,
    val ecosystem: String,
    val manifest: String,
    val version: String?,
    val scope: String,
    val observedAt: Instant?,
)

/** What reading the files inside one repository produced. */
data class RepositoryContents(
    val delta: GraphDelta = GraphDelta(),
    /** Names this repository publishes, for resolving other repositories' dependencies onto it. */
    val publishes: List<String> = emptyList(),
    val pending: List<PendingInternalDependency> = emptyList(),
)

/**
 * Turns the files inside a repository into dependencies and infrastructure evidence.
 *
 * Two kinds of claim come out of here and they are not the same kind. A library read from a manifest
 * is reported: the file says it, at full confidence, and the edge names the file so anyone can go and
 * check. A dependency on another repository in this organisation is inferred: it rests on a package
 * name matching a naming convention, and it is recorded as a guess so that a reviewer can tell it
 * from a fact - and so that a convention changing does not quietly rewrite history as truth.
 */
@Component
class RepositoryContentsMapper(
    private val parsers: List<ManifestParser>,
    private val iacIndexer: IacIndexer,
    private val identityResolver: IdentityResolver,
    private val properties: GitHubProperties,
) {
    /** Whether this file is worth spending a request on. */
    fun isInteresting(path: String): Boolean = parserFor(path) != null || iacFormatOf(path) != null

    /**
     * @param files the interesting files' contents, by path
     * @throws com.repodatagraph.adapter.out.github.manifest.UnreadableManifestException if a manifest
     *   is there but malformed - a fact about that repository, which the run records as partial
     */
    fun map(
        repositoryKey: NodeKey,
        files: Map<String, String>,
        observedAt: Instant?,
    ): RepositoryContents {
        val manifests = files.mapNotNull { (path, content) -> parserFor(path)?.parse(path, content) }
        val iacFiles = files.mapNotNull { (path, content) -> iacFile(repositoryKey, path, content, observedAt) }

        val declared = manifests.flatMap { manifest -> manifest.dependencies.map { manifest.path to it } }
        val internal = declared.filter { (_, dependency) -> isInternal(dependency.name) }
        val external = declared - internal.toSet()

        return RepositoryContents(
            delta =
                GraphDelta(
                    nodes = external.map { (_, dependency) -> libraryNode(dependency, observedAt) } + iacFiles.map { it.node },
                    edges =
                        external.map { (path, dependency) -> libraryEdge(repositoryKey, path, dependency, observedAt) } +
                            iacFiles.map { it.edge },
                ),
            publishes = manifests.flatMap { it.publishes },
            pending =
                internal.map { (path, dependency) ->
                    PendingInternalDependency(
                        from = repositoryKey,
                        packageName = dependency.name,
                        ecosystem = dependency.ecosystem,
                        manifest = path,
                        version = dependency.version,
                        scope = dependency.scope.declared(),
                        observedAt = observedAt,
                    )
                },
        )
    }

    fun libraryNode(
        dependency: DeclaredDependency,
        observedAt: Instant?,
    ): NodeUpsert =
        NodeUpsert(
            type = LIBRARY,
            props = mapOf("ecosystem" to dependency.ecosystem, "name" to dependency.name),
            observedAt = observedAt,
        )

    fun libraryEdge(
        from: NodeKey,
        manifestPath: String,
        dependency: DeclaredDependency,
        observedAt: Instant?,
    ): EdgeUpsert =
        EdgeUpsert(
            type = DEPENDS_ON,
            from = from,
            to = identityResolver.keyFor(LIBRARY, mapOf("ecosystem" to dependency.ecosystem, "name" to dependency.name)),
            props =
                buildMap {
                    put("kind", "library")
                    put("manifest", manifestPath)
                    put("scope", dependency.scope.declared())
                    dependency.version?.let { put("version", it) }
                },
            observedAt = observedAt,
        )

    private fun isInternal(packageName: String): Boolean =
        properties.manifests.internalPackagePrefixes.any { prefix ->
            prefix.isNotBlank() && packageName.startsWith(prefix, ignoreCase = true)
        }

    private fun parserFor(path: String): ManifestParser? {
        if (!properties.manifests.enabled) return null
        return parsers.firstOrNull { it.handles(path) && (properties.manifests.includeLockfiles || !it.readsLockfile) }
    }

    private fun iacFormatOf(path: String) = if (properties.iac.enabled) iacIndexer.formatOf(path) else null

    private fun iacFile(
        repositoryKey: NodeKey,
        path: String,
        content: String,
        observedAt: Instant?,
    ): IacNodeAndEdge? {
        val format = iacFormatOf(path) ?: return null
        val props =
            mapOf(
                "repoKey" to repositoryKey.key,
                "path" to path,
                "format" to format.declared(),
                "resourceRefs" to iacIndexer.referencesIn(format, content),
            )
        return IacNodeAndEdge(
            node = NodeUpsert(type = IAC_FILE, props = props, observedAt = observedAt),
            edge =
                EdgeUpsert(
                    type = CONTAINS_IAC,
                    from = repositoryKey,
                    to = identityResolver.keyFor(IAC_FILE, props),
                    observedAt = observedAt,
                ),
        )
    }

    private data class IacNodeAndEdge(
        val node: NodeUpsert,
        val edge: EdgeUpsert,
    )

    private companion object {
        const val LIBRARY = "Library"
        const val IAC_FILE = "IacFile"
        const val DEPENDS_ON = "DEPENDS_ON"
        const val CONTAINS_IAC = "CONTAINS_IAC"
    }
}
