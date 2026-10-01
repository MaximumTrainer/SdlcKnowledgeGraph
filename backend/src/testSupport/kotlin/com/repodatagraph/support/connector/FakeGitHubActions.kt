package com.repodatagraph.support.connector

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import java.time.Instant

/**
 * What GitHub Actions, the Deployments API and GitHub Packages say about a repository (#90): its
 * workflow runs, the deployments they made with each one's statuses, and the package versions the
 * owner has published.
 *
 * Separate from [FakeGitHub] for the reason [FakeGitHubFiles] is: it is a different conversation with
 * GitHub, and a connector that reads one should not pass a test by having stubbed the other. Each
 * listing answers with everything declared, whatever filter the request names, so a connector that
 * relied on GitHub to filter for it would be caught taking someone else's runs.
 */
class FakeGitHubActions(
    private val server: WireMockServer,
) {
    private val runs = mutableMapOf<String, MutableList<FakeRun>>()
    private val deployments = mutableMapOf<String, MutableList<FakeDeployment>>()
    private val packages = mutableMapOf<String, MutableList<FakePackage>>()

    /** A workflow run, served on its own and in the repository's listing of runs. */
    fun hasRun(
        org: String,
        repo: String,
        run: FakeRun,
    ) {
        val all = runs.getOrPut("$org/$repo") { mutableListOf() }.apply { add(run) }
        server.stubFor(
            get(urlPathEqualTo("/repos/$org/$repo/actions/runs/${run.id}"))
                .willReturn(json(run.json(org, repo))),
        )
        // Newest first, as GitHub lists them.
        val listing = all.sortedByDescending { it.createdAt }.joinToString(",") { it.json(org, repo) }
        server.stubFor(
            get(urlPathEqualTo("/repos/$org/$repo/actions/runs"))
                .willReturn(json("""{"total_count":${all.size},"workflow_runs":[$listing]}""")),
        )
    }

    /** A deployment, served on its own, in the repository's listing, and with its statuses. */
    fun hasDeployment(
        org: String,
        repo: String,
        deployment: FakeDeployment,
    ) {
        val all = deployments.getOrPut("$org/$repo") { mutableListOf() }.apply { add(deployment) }
        server.stubFor(
            get(urlPathEqualTo("/repos/$org/$repo/deployments/${deployment.id}"))
                .willReturn(json(deployment.json(org, repo))),
        )
        server.stubFor(
            get(urlPathEqualTo("/repos/$org/$repo/deployments/${deployment.id}/statuses"))
                .willReturn(json(deployment.statusesJson(org, repo))),
        )
        val listing = all.sortedByDescending { it.createdAt }.joinToString(",", "[", "]") { it.json(org, repo) }
        server.stubFor(get(urlPathEqualTo("/repos/$org/$repo/deployments")).willReturn(json(listing)))
    }

    /**
     * A package the owner publishes from [FakePackage.repository], with its versions. Listed under
     * `/orgs/{org}/packages` for its type only; every other type answers an empty list, as GitHub does
     * for an organisation that publishes nothing of that kind.
     */
    fun hasPackage(
        org: String,
        pkg: FakePackage,
    ) {
        // A second call for a package already declared adds its versions, as publishing again does.
        val all = packages.getOrPut(org) { mutableListOf() }
        val existing = all.firstOrNull { it.name == pkg.name && it.type == pkg.type }
        val merged = existing?.copy(versions = existing.versions + pkg.versions) ?: pkg
        all.remove(existing)
        all.add(merged)
        server.stubFor(
            get(urlPathEqualTo("/orgs/$org/packages"))
                .atPriority(LOW)
                .willReturn(json("[]")),
        )
        all.groupBy { it.type }.forEach { (type, ofType) ->
            server.stubFor(
                get(urlPathEqualTo("/orgs/$org/packages"))
                    .withQueryParam("package_type", EqualToPattern(type))
                    .atPriority(HIGH)
                    .willReturn(json(ofType.joinToString(",", "[", "]") { it.json(org) })),
            )
        }
        server.stubFor(
            get(urlPathEqualTo("/orgs/$org/packages/${pkg.type}/${pkg.name}/versions"))
                .willReturn(json(merged.versions.sortedByDescending { it.createdAt }.joinToString(",", "[", "]") { it.json(pkg.type) })),
        )
    }

    internal fun forget() {
        runs.clear()
        deployments.clear()
        packages.clear()
    }

    private fun json(body: String) =
        aResponse()
            .withStatus(OK)
            .withHeader("Content-Type", "application/json")
            .withHeader(FakeGitHub.REMAINING_HEADER, "5000")
            .withHeader(FakeGitHub.RESET_HEADER, "0")
            .withBody(body)

    private companion object {
        const val OK = 200
        const val HIGH = 1
        const val LOW = 10
    }
}

/** A workflow run as GitHub describes it: which commit, which workflow, when it ran and how it ended. */
data class FakeRun(
    val id: Long,
    val headSha: String,
    val path: String = ".github/workflows/release.yml",
    val createdAt: Instant,
    val completedAt: Instant,
    val status: String = "completed",
    val conclusion: String? = "success",
) {
    fun json(
        org: String,
        repo: String,
    ): String =
        """
        {
          "id": $id,
          "name": "${path.substringAfterLast('/')}",
          "path": "$path",
          "head_sha": "$headSha",
          "head_branch": "main",
          "status": "$status",
          "conclusion": ${conclusion?.let { "\"$it\"" } ?: "null"},
          "created_at": "$createdAt",
          "run_started_at": "$createdAt",
          "updated_at": "$completedAt",
          "html_url": "https://github.com/$org/$repo/actions/runs/$id",
          "repository": { "name": "$repo", "full_name": "$org/$repo", "html_url": "https://github.com/$org/$repo" }
        }
        """.trimIndent()
}

/**
 * A deployment and its statuses, newest status last in [statuses] and listed newest first, as GitHub
 * lists them. A status made by a workflow job names its run in `log_url` and `target_url`.
 */
data class FakeDeployment(
    val id: Long,
    val sha: String,
    val environment: String,
    val createdAt: Instant,
    val statuses: List<FakeDeploymentStatus>,
    val creator: String = "github-actions[bot]",
) {
    fun json(
        org: String,
        repo: String,
    ): String =
        """
        {
          "id": $id,
          "sha": "$sha",
          "ref": "main",
          "task": "deploy",
          "environment": "$environment",
          "original_environment": "$environment",
          "payload": {},
          "creator": { "login": "$creator" },
          "created_at": "$createdAt",
          "updated_at": "${statuses.maxOfOrNull { it.createdAt } ?: createdAt}",
          "url": "https://api.github.com/repos/$org/$repo/deployments/$id"
        }
        """.trimIndent()

    fun statusesJson(
        org: String,
        repo: String,
    ): String =
        statuses.withIndex().reversed().joinToString(",", "[", "]") { (index, status) -> status.json(org, repo, id * STATUS_IDS + index) }

    private companion object {
        const val STATUS_IDS = 100L
    }
}

data class FakeDeploymentStatus(
    val state: String,
    val createdAt: Instant,
    /** The workflow run that reported it, or null for one reported by something else. */
    val runId: Long? = null,
) {
    fun json(
        org: String,
        repo: String,
        id: Long,
    ): String {
        val log = runId?.let { "\"https://github.com/$org/$repo/actions/runs/$it/job/${it * JOB_IDS}\"" } ?: "\"\""
        return """
            {
              "id": $id,
              "state": "$state",
              "created_at": "$createdAt",
              "updated_at": "$createdAt",
              "log_url": $log,
              "target_url": $log,
              "environment_url": ""
            }
            """.trimIndent()
    }

    private companion object {
        /** A job id derived from its run's, so a test can tell which run a status names. */
        const val JOB_IDS = 10L
    }
}

/**
 * A package in GitHub Packages, published from [repository] in the same owner. A container image's
 * version is named by its digest and carries its tags; any other kind's is named by its version.
 */
data class FakePackage(
    val name: String,
    val type: String,
    val repository: String,
    val versions: List<FakePackageVersion>,
) {
    fun json(org: String): String =
        """
        {
          "id": ${name.hashCode()},
          "name": "$name",
          "package_type": "$type",
          "visibility": "private",
          "repository": { "name": "$repository", "full_name": "$org/$repository" }
        }
        """.trimIndent()
}

data class FakePackageVersion(
    val name: String,
    val createdAt: Instant,
    val tags: List<String> = emptyList(),
) {
    fun json(type: String): String {
        val metadata =
            if (type == "container") {
                """{"package_type":"container","container":{"tags":${tags.joinToString(",", "[", "]") { "\"$it\"" }}}}"""
            } else {
                """{"package_type":"$type"}"""
            }
        return """{"id":${name.hashCode()},"name":"$name","created_at":"$createdAt","updated_at":"$createdAt","metadata":$metadata}"""
    }
}
