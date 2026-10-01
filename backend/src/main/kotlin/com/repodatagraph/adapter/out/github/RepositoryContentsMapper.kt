package com.repodatagraph.adapter.out.github

import com.repodatagraph.adapter.out.github.iac.IacIndexer
import com.repodatagraph.adapter.out.github.manifest.ManifestParser
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * A dependency a manifest declares, held back until it is known whether a repository publishes it.
 *
 * Repositories are read one at a time, so at the moment `payments` says it depends on
 * `ledger-client`, the repository that publishes `ledger-client` may not have been read yet - and
 * whether the dependency is a library or a repository in this organisation turns on exactly that
 * (#86, FR-4).
 *
 * @param source the repository as GitHub names it, `org/name`, for the evidence the edge names
 * @param internal whether the name matches a prefix this organisation publishes under: a hint for a
 *   webhook, which has read one repository and so holds such a dependency back rather than record it
 *   as a library, never a condition for resolving one
 */
data class PendingDependency(
    val from: NodeKey,
    val source: String,
    val packageName: String,
    val ecosystem: String,
    val manifest: String,
    val version: String?,
    val scope: String,
    val observedAt: Instant?,
    val internal: Boolean = false,
)

/** What reading the files inside one repository produced. */
data class RepositoryContents(
    /** The infrastructure-as-code evidence, which needs nothing else to be written. */
    val delta: GraphDelta = GraphDelta(),
    /** Names this repository publishes, for resolving other repositories' dependencies onto it. */
    val publishes: List<String> = emptyList(),
    /** Every dependency its manifests declare, for [DependencyResolver] once the picture is whole. */
    val dependencies: List<PendingDependency> = emptyList(),
)

/**
 * Turns the files inside a repository into dependencies and infrastructure evidence.
 *
 * A dependency comes out of here as a declaration and nothing more: whether it is a library or a
 * repository in this organisation is [DependencyResolver]'s question, answered once every repository
 * has said what it publishes. What is written straight away is the infrastructure-as-code evidence,
 * which depends on nothing else.
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
     * @param source the repository as GitHub names it, `org/name`, for the evidence each fact names;
     *   the repository's key where no name is to hand
     */
    fun map(
        repositoryKey: NodeKey,
        files: Map<String, String>,
        observedAt: Instant?,
        source: String = repositoryKey.key,
    ): RepositoryContents {
        val manifests = files.mapNotNull { (path, content) -> parserFor(path)?.parse(path, content) }
        val iacFiles = files.mapNotNull { (path, content) -> iacFile(repositoryKey, source, path, content, observedAt) }

        return RepositoryContents(
            delta = GraphDelta(nodes = iacFiles.map { it.node }, edges = iacFiles.map { it.edge }),
            publishes = manifests.flatMap { it.publishes },
            dependencies =
                manifests.flatMap { manifest ->
                    manifest.dependencies.map { dependency ->
                        PendingDependency(
                            from = repositoryKey,
                            source = source,
                            packageName = dependency.name,
                            ecosystem = dependency.ecosystem,
                            manifest = manifest.path,
                            version = dependency.version,
                            scope = dependency.scope.declared(),
                            observedAt = observedAt,
                            internal = isInternal(dependency.name),
                        )
                    }
                },
        )
    }

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
        source: String,
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
            node = NodeUpsert(type = IAC_FILE, props = props, observedAt = observedAt, sourceId = "$source:$path"),
            edge =
                EdgeUpsert(
                    type = CONTAINS_IAC,
                    from = repositoryKey,
                    to = identityResolver.keyFor(IAC_FILE, props),
                    observedAt = observedAt,
                    sourceId = "$source:$path",
                ),
        )
    }

    private data class IacNodeAndEdge(
        val node: NodeUpsert,
        val edge: EdgeUpsert,
    )

    private companion object {
        const val IAC_FILE = "IacFile"
        const val CONTAINS_IAC = "CONTAINS_IAC"
    }
}
