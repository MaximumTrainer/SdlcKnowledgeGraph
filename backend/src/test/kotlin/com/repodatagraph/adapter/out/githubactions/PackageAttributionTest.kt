package com.repodatagraph.adapter.out.githubactions

import com.repodatagraph.adapter.out.github.GitHubRepoRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What a workflow run published (#90, FR-1): a package version GitHub Packages holds for the run's
 * repository, published while that run - and no other run of the repository - was running. And how
 * each is keyed: by registry, name and digest where GitHub names a digest, and by name and version,
 * less surely, where it does not.
 */
class PackageAttributionTest {
    private val started = Instant.parse("2026-09-30T10:00:00Z")
    private val run = run(4711, started, started.plusSeconds(600))

    private fun run(
        id: Long,
        from: Instant,
        to: Instant,
        status: String = "completed",
    ) = WorkflowRun(id = id, path = ".github/workflows/release.yml", headSha = "3c9a1f2", status = status, createdAt = from, updatedAt = to)

    private fun image(
        digest: String = "sha256:abc",
        tags: List<String> = listOf("1.4.2"),
    ) = PublishedArtifact.of(
        owner = "Acme",
        pkg = GitHubPackage("payments", "container", GitHubRepoRef("acme/payments")),
        version = PackageVersion(digest, started.plusSeconds(300), PackageMetadata(container = ContainerMetadata(tags))),
        registries = REGISTRIES,
    )

    @Test
    fun `a container image is named by its registry, its owner and its digest`() {
        val artifact = image()

        assertThat(artifact.registry).isEqualTo("ghcr.io")
        assertThat(artifact.name).isEqualTo("acme/payments")
        assertThat(artifact.digest).isEqualTo("sha256:abc")
        assertThat(artifact.version).isEqualTo("1.4.2")
        assertThat(artifact.artifactType).isEqualTo("container-image")
        assertThat(artifact.props()).containsEntry("digest", "sha256:abc")
    }

    @Test
    fun `an image's version is its first tag that is not latest, or else its digest`() {
        assertThat(image(tags = listOf("latest", "1.4.2", "sha-3c9a1f2")).version).isEqualTo("1.4.2")
        assertThat(image(tags = listOf("latest")).version).isEqualTo("sha256:abc")
        assertThat(image(tags = emptyList()).version).isEqualTo("sha256:abc")
    }

    @Test
    fun `a package with no digest is named by its version and is less sure of its key`() {
        val artifact =
            PublishedArtifact.of(
                owner = "acme",
                pkg = GitHubPackage("payments", "npm", GitHubRepoRef("acme/payments")),
                version = PackageVersion("1.4.2", started.plusSeconds(300)),
                registries = REGISTRIES,
            )

        assertThat(artifact.digest).isNull()
        assertThat(artifact.name).isEqualTo("payments")
        assertThat(artifact.version).isEqualTo("1.4.2")
        assertThat(artifact.registry).isEqualTo("npm.pkg.github.com")
        assertThat(artifact.artifactType).isEqualTo("npm-package")
        assertThat(artifact.confidence).isEqualTo(DIGESTLESS)
        assertThat(artifact.props()).doesNotContainKey("digest")
    }

    @Test
    fun `a jar is a jar and a kind the ontology does not name is other`() {
        val jar = PublishedArtifact.of("acme", GitHubPackage("com.acme.payments", "maven"), PackageVersion("1.0.0", started), REGISTRIES)
        val gem = PublishedArtifact.of("acme", GitHubPackage("payments", "rubygems"), PackageVersion("1.0.0", started), REGISTRIES)

        assertThat(jar.artifactType).isEqualTo("jar")
        assertThat(gem.artifactType).isEqualTo("other")
        assertThat(gem.registry).isNull()
    }

    @Test
    fun `a version published while the run ran is the run's`() {
        val release = PackageRelease(image(), started.plusSeconds(300))

        val attributed = PackageAttribution.attribute(listOf(release), listOf(run), now = started.plusSeconds(3600))

        assertThat(attributed.byRun[4711L]).containsExactly(release.artifact)
        assertThat(attributed.ambiguous).isEmpty()
    }

    @Test
    fun `a version published before or after the run is not the run's`() {
        val before = PackageRelease(image("sha256:early"), started.minusSeconds(1))
        val after = PackageRelease(image("sha256:late"), started.plusSeconds(601))

        val attributed = PackageAttribution.attribute(listOf(before, after), listOf(run), now = started.plusSeconds(3600))

        assertThat(attributed.byRun).isEmpty()
    }

    @Test
    fun `a version two runs could have published belongs to neither`() {
        val overlapping = run(4712, started.plusSeconds(200), started.plusSeconds(900))
        val release = PackageRelease(image(), started.plusSeconds(300))

        val attributed = PackageAttribution.attribute(listOf(release), listOf(run, overlapping), now = started.plusSeconds(3600))

        assertThat(attributed.byRun).isEmpty()
        assertThat(attributed.ambiguous).containsExactly(release)
    }

    @Test
    fun `a run still going covers what was published up to now`() {
        val going = run(4713, started, started.plusSeconds(60), status = "in_progress")
        val release = PackageRelease(image(), started.plusSeconds(300))

        val attributed = PackageAttribution.attribute(listOf(release), listOf(going), now = started.plusSeconds(400))

        assertThat(attributed.byRun[4713L]).containsExactly(release.artifact)
    }

    private companion object {
        const val DIGESTLESS = 0.8
        val REGISTRIES = mapOf("container" to "ghcr.io", "npm" to "npm.pkg.github.com", "maven" to "maven.pkg.github.com")
    }
}
