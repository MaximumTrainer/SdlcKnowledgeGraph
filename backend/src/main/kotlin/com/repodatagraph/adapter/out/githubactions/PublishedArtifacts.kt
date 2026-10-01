package com.repodatagraph.adapter.out.githubactions

import com.repodatagraph.domain.lifecycle.ArtifactFamily
import java.time.Instant

/**
 * A package version a workflow run published (#90, FR-1), as an Artifact will hold it.
 *
 * Keyed as the identity resolver keys any Artifact, and as the deployment ingest (#7) names the same
 * image: `<registry>/<name>@<digest>` when GitHub names a digest, which it does for a container image,
 * and `<name>:<version>` when it does not, which is every other kind. A key without a digest is the
 * weaker one - two builds can share a version, never a digest - so such an artifact is stated at
 * [confidence] 0.8, and the graph folds it into the digest's node once a writer reports one (#98).
 */
data class PublishedArtifact(
    val registry: String?,
    val name: String,
    val digest: String?,
    val version: String,
    val artifactType: String,
) {
    val confidence: Double get() = if (digest != null) FULL else WITHOUT_DIGEST

    /** What replaces it: the same image by registry and name, whatever the build. */
    val family: ArtifactFamily get() = ArtifactFamily(registry, name)

    /** The Artifact's properties, without the ones it has no value for, which the store would clear. */
    fun props(): Map<String, Any> =
        listOfNotNull(
            registry?.let { "registry" to it },
            "name" to name,
            digest?.let { "digest" to it },
            "version" to version,
            "artifactType" to artifactType,
        ).toMap()

    companion object {
        private const val FULL = 1.0
        private const val WITHOUT_DIGEST = 0.8
        private const val CONTAINER = "container"
        private const val DIGEST_PREFIX = "sha256:"
        private const val LATEST = "latest"

        /** GitHub's package kinds as the ontology's artifact types; anything else is `other`. */
        private val ARTIFACT_TYPES =
            mapOf(
                CONTAINER to "container-image",
                "docker" to "container-image",
                "npm" to "npm-package",
                "maven" to "jar",
            )

        /**
         * A container image is named `<owner>/<package>` in lower case, as ghcr.io addresses it, with
         * the version it was tagged as - the first tag but `latest`, which moves - or else its digest.
         * Any other kind is named as its ecosystem names it, at its version.
         */
        fun of(
            owner: String,
            pkg: GitHubPackage,
            version: PackageVersion,
            registries: Map<String, String>,
        ): PublishedArtifact {
            val container = pkg.packageType == CONTAINER || pkg.packageType == "docker"
            val digest = version.name.takeIf { container && it.startsWith(DIGEST_PREFIX) }
            val tag =
                version.metadata
                    ?.container
                    ?.tags
                    .orEmpty()
                    .firstOrNull { it.isNotBlank() && it != LATEST }
            return PublishedArtifact(
                registry = registries[pkg.packageType],
                name = if (container) "$owner/${pkg.name}".lowercase() else pkg.name,
                digest = digest,
                version = if (container) tag ?: version.name else version.name,
                artifactType = ARTIFACT_TYPES[pkg.packageType] ?: "other",
            )
        }
    }
}

/** A version GitHub Packages holds, and when it was published, before it is known whose it is. */
data class PackageRelease(
    val artifact: PublishedArtifact,
    val publishedAt: Instant,
)

/** Which run published each release, and the releases more than one run could have published. */
data class Attribution(
    val byRun: Map<Long, List<PublishedArtifact>>,
    val ambiguous: List<PackageRelease>,
)

/**
 * Which workflow run published a package version (#90, FR-1).
 *
 * GitHub records no link from a package version to the run that pushed it, so the connector works it
 * out from what it does record: a version of a package published from the run's repository, created
 * while the run was running. A version two runs of the repository could have published belongs to
 * neither, because guessing would record a commit the artifact may not have been built from. A run
 * still going covers what was published up to [now].
 */
object PackageAttribution {
    fun attribute(
        releases: List<PackageRelease>,
        runs: List<WorkflowRun>,
        now: Instant,
    ): Attribution {
        val byRun = mutableMapOf<Long, MutableList<PublishedArtifact>>()
        val ambiguous = mutableListOf<PackageRelease>()
        releases.forEach { release ->
            val covering = runs.distinctBy { it.id }.filter { covers(it, release.publishedAt, now) }
            when (covering.size) {
                0 -> Unit
                1 -> byRun.getOrPut(covering.single().id) { mutableListOf() } += release.artifact
                else -> ambiguous += release
            }
        }
        return Attribution(byRun.mapValues { (_, artifacts) -> artifacts.distinct() }, ambiguous)
    }

    private fun covers(
        run: WorkflowRun,
        at: Instant,
        now: Instant,
    ): Boolean {
        val ended = if (run.completed) run.updatedAt else now
        return !at.isBefore(run.startedAt) && !at.isAfter(ended)
    }
}
