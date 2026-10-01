package com.repodatagraph.adapter.out.githubactions

import com.repodatagraph.adapter.out.github.GitHubHttp
import com.repodatagraph.adapter.out.github.GitHubProperties
import com.repodatagraph.adapter.out.github.GitHubResponse
import com.repodatagraph.adapter.out.github.nextPage
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What the github-actions connector asks GitHub for (#90): a repository's workflow runs and
 * deployments, a deployment's statuses, and the packages an owner publishes with their versions.
 *
 * Through the GitHub connector's [GitHubHttp], so the token, the retries and the rate limit are
 * handled once for both connectors and the token is never logged by either. Every listing is followed
 * to its last page, and a 404 on a listing is an answer: a repository with Actions turned off has no
 * runs, and one nobody deploys has no deployments.
 */
@Component
class ActionsClient(
    private val http: GitHubHttp,
    private val properties: GitHubProperties,
) {
    /**
     * The repository's completed runs created at or after [createdSince], newest first, or every one
     * when it is null. GitHub filters by when a run was created, so the caller asks from early enough
     * to catch a long run that completed later.
     */
    fun runs(
        owner: String,
        repo: String,
        createdSince: Instant?,
    ): Sequence<WorkflowRun> {
        val created = createdSince?.let { "&created=" + encode(">=" + it.truncatedTo(ChronoUnit.SECONDS)) }.orEmpty()
        return pages(uri("/repos/$owner/$repo/actions/runs?status=completed&$PAGE$created"), RUN_PAGE)
            .flatMap { it.workflowRuns.asSequence() }
            .filter { it.completed }
    }

    /** One run, read back by id: what a webhook names, believed only once GitHub says it again. */
    fun run(
        owner: String,
        repo: String,
        id: Long,
    ): WorkflowRun? = http.getOrNull("/repos/$owner/$repo/actions/runs/$id", WorkflowRun::class.java)

    /** The repository's deployments, newest first, of [sha] alone when it is given. */
    fun deployments(
        owner: String,
        repo: String,
        sha: String? = null,
    ): Sequence<GitHubDeployment> {
        val ofSha = sha?.let { "&sha=" + encode(it) }.orEmpty()
        return pages(uri("/repos/$owner/$repo/deployments?$PAGE$ofSha"), DEPLOYMENTS)
            .flatMap { it.asSequence() }
            // GitHub filters by sha when asked; a reader that relied on it would take another commit's
            // deployments for this one's on an API that ignored the filter.
            .filter { sha == null || it.sha == sha }
    }

    fun deployment(
        owner: String,
        repo: String,
        id: Long,
    ): GitHubDeployment? = http.getOrNull("/repos/$owner/$repo/deployments/$id", GitHubDeployment::class.java)

    /** The deployment's statuses, newest first. A hundred is far more than one deployment ever has. */
    fun statuses(
        owner: String,
        repo: String,
        deploymentId: Long,
    ): List<DeploymentStatus> =
        http
            .getPageOrNull(uri("/repos/$owner/$repo/deployments/$deploymentId/statuses?$PAGE"), STATUSES)
            ?.body
            .orEmpty()
            .sortedByDescending { it.createdAt }

    /**
     * Every package of [type] the owner publishes. An owner GitHub does not know as an organisation is
     * read as a user account, as the GitHub connector reads its repositories.
     */
    fun packages(
        owner: String,
        type: String,
    ): OwnedPackages {
        val query = "?package_type=${encode(type)}&$PAGE"
        val asOrganisation = http.getPageOrNull(uri("/orgs/$owner/packages$query"), PACKAGES)
        val scope = if (asOrganisation == null) USERS else ORGS
        val first = asOrganisation ?: http.getPageOrNull(uri("/users/$owner/packages$query"), PACKAGES)
        return OwnedPackages(
            scope,
            first
                ?.let { follow(it, PACKAGES) }
                .orEmpty()
                .flatten()
                .toList(),
        )
    }

    /** A package's versions, newest first, read until [until] says to stop. */
    fun versions(
        packages: OwnedPackages,
        owner: String,
        pkg: GitHubPackage,
        until: (PackageVersion) -> Boolean,
    ): List<PackageVersion> {
        val path = "/${packages.scope}/$owner/packages/${encode(pkg.packageType)}/${encode(pkg.name)}/versions?$PAGE"
        return pages(uri(path), VERSIONS)
            .flatMap { it.asSequence() }
            .takeWhile { !until(it) }
            .toList()
    }

    private fun <T> pages(
        first: URI,
        type: ParameterizedTypeReference<T>,
    ): Sequence<T> = http.getPageOrNull(first, type)?.let { follow(it, type) } ?: emptySequence()

    private fun <T> follow(
        first: GitHubResponse<T>,
        type: ParameterizedTypeReference<T>,
    ): Sequence<T> =
        sequence {
            var response = first
            while (true) {
                response.body?.let { yield(it) }
                val next = nextPage(response.headers) ?: break
                response = http.get(next, type)
            }
        }

    private fun uri(path: String): URI = URI.create(properties.baseUrl + path)

    private companion object {
        const val PAGE = "per_page=100"
        const val ORGS = "orgs"
        const val USERS = "users"

        val RUN_PAGE = object : ParameterizedTypeReference<WorkflowRunPage>() {}
        val DEPLOYMENTS = object : ParameterizedTypeReference<List<GitHubDeployment>>() {}
        val STATUSES = object : ParameterizedTypeReference<List<DeploymentStatus>>() {}
        val PACKAGES = object : ParameterizedTypeReference<List<GitHubPackage>>() {}
        val VERSIONS = object : ParameterizedTypeReference<List<PackageVersion>>() {}
    }
}

/** An owner's packages of one kind, and whether GitHub lists them under `/orgs` or `/users`. */
data class OwnedPackages(
    val scope: String,
    val packages: List<GitHubPackage>,
)

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
