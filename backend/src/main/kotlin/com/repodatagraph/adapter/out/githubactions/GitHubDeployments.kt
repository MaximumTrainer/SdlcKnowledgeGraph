package com.repodatagraph.adapter.out.githubactions

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.repodatagraph.adapter.out.github.DeploymentSettings
import com.repodatagraph.adapter.out.github.GitHubClient
import com.repodatagraph.adapter.out.github.GitHubProperties
import com.repodatagraph.adapter.out.github.GitHubRefusedException
import com.repodatagraph.adapter.out.github.GitHubRepo
import com.repodatagraph.adapter.out.github.GitHubRepositoryMapper
import com.repodatagraph.adapter.out.github.header
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.PartialReadException
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import com.repodatagraph.observability.LogEvents
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * What GitHub Actions did, read from GitHub (#90): the workflow runs of the GitHub connector's
 * organisations, the package versions each published, and the deployments each made.
 *
 * Read two ways that arrive at the same facts. A poll reads every repository from a cursor - the
 * runs that completed after it, and the deployments whose latest status is newer than it - so a
 * webhook that never arrived is caught on the next one. A webhook reads one run, or one deployment,
 * back from the API as soon as GitHub says it moved, which is what gets a deployment into the graph
 * within a minute (FR-5). Either way the payload is only a hint: what is recorded is what the API
 * says, as for the GitHub connector's own webhooks.
 *
 * Shares the GitHub connector's configuration, token and HTTP client rather than having its own, so
 * the two read the same owners with the same credential, and an owner that is a user account rather
 * than an organisation is read as one by both.
 */
@Component
class GitHubDeployments(
    private val properties: GitHubProperties,
    private val github: GitHubClient,
    private val actions: ActionsClient,
    private val mapper: CiFactsMapper,
    private val repositories: GitHubRepositoryMapper,
    private val clock: Clock,
) {
    private val settings: DeploymentSettings get() = properties.deployments

    fun isConfigured(): Boolean = properties.isConfigured()

    fun isReachable(): Boolean = github.isReachable()

    /**
     * One page per repository with anything new, then one saying where the run got to. A repository
     * GitHub refuses to show is counted as unreadable and the run goes on, as the GitHub connector's
     * does; the run is then partial, so its watermark does not move past what it could not read.
     *
     * @param since the cursor: null for a first or full read, which looks back [DeploymentSettings.lookback]
     * @param watermark where the next read starts, which is when this one began
     */
    fun sync(
        since: Instant?,
        watermark: Instant,
    ): Sequence<GraphDelta> =
        sequence {
            val from = since ?: watermark.minus(settings.lookback)
            val packages = PackageCache()
            val failures = mutableListOf<String>()
            properties.orgs.filter { it.isNotBlank() }.forEach { owner ->
                github.repositories(owner).filterNot { it.archived }.forEach { repo ->
                    val read =
                        try {
                            poll(owner, repo, from, packages)
                        } catch (refused: GitHubRefusedException) {
                            failures += "${repo.fullName}: ${refused.message}"
                            null
                        }
                    read?.let { yield(it.copy(watermark = watermark)) }
                }
            }
            yield(GraphDelta(watermark = watermark))
            if (failures.isNotEmpty()) throw PartialReadException(failures)
        }

    /** Null when the event says nothing this connector records, or names an owner nobody configured. */
    fun handle(event: WebhookEvent): GraphDelta? {
        val type = event.headers.header(EVENT_HEADER).orEmpty()
        val payload = parse(event.body) ?: return ignored(type, "not a JSON object")
        val repository = payload.path("repository")
        val owner =
            repository
                .path("owner")
                .path("login")
                .asText()
                .ifBlank { repository.path("full_name").asText().substringBefore('/') }
        val name = repository.path("name").asText()
        return when {
            owner.isBlank() || name.isBlank() -> ignored(type, "no repository")
            properties.orgs.none { it.equals(owner, ignoreCase = true) } -> ignored(type, "an owner this instance does not read")
            type == WORKFLOW_RUN && payload.path("action").asText() == COMPLETED ->
                workflowRun(owner, name, payload.path("workflow_run").path("id").asLong())
            type == DEPLOYMENT_STATUS && payload.path("deployment_status").path("state").asText() != INACTIVE ->
                deploymentStatus(owner, name, payload.path("deployment").path("id").asLong())
            else -> ignored(type, "nothing this connector records")
        }
    }

    /** The runs that completed after [from], and the deployments whose latest status is newer than it. */
    private fun poll(
        owner: String,
        repo: GitHubRepo,
        from: Instant,
        packages: PackageCache,
    ): GraphDelta? {
        val earliest = from.minus(settings.maxRunDuration)
        val completed = actions.runs(owner, repo.name, createdSince = earliest).filter { it.updatedAt.isAfter(from) }.toList()
        val moved =
            actions
                .deployments(owner, repo.name)
                .takeWhile { !it.createdAt.isBefore(earliest) }
                .map { it to actions.statuses(owner, repo.name, it.id) }
                .filter { (deployment, statuses) -> (latest(statuses)?.createdAt ?: deployment.createdAt).isAfter(from) }
                .toList()
        if (completed.isEmpty() && moved.isEmpty()) return null

        val deploying =
            moved
                .mapNotNull { (_, statuses) -> runIdOf(statuses) }
                .filter { id -> completed.none { it.id == id } }
                .distinct()
                .mapNotNull { actions.run(owner, repo.name, it) }
        val runs = published(owner, repo, completed + deploying, packages)
        return mapper.map(
            refOf(repo),
            runs.filter { read -> completed.any { it.id == read.run.id } },
            moved.map { (deployment, statuses) -> DeploymentRead(deployment, latest(statuses), deployedBy(deployment, statuses, runs)) },
        )
    }

    /** A run GitHub says completed, what it published, and the deployments of its commit it made. */
    private fun workflowRun(
        owner: String,
        name: String,
        id: Long,
    ): GraphDelta? {
        val repo = github.repository(owner, name)
        val run = repo?.let { actions.run(owner, name, id) }?.takeIf { it.completed }
        if (repo == null || run == null) return null
        val read = published(owner, repo, listOf(run), PackageCache())
        val deployments =
            actions
                .deployments(owner, name, sha = run.headSha)
                .map { it to actions.statuses(owner, name, it.id) }
                // A deployment of this commit another run made is that run's to report.
                .filter { (_, statuses) -> runIdOf(statuses).let { it == null || it == run.id } }
                .map { (deployment, statuses) -> DeploymentRead(deployment, latest(statuses), read.single()) }
                .toList()
        return mapper.map(refOf(repo), read, deployments)
    }

    /** A deployment GitHub says moved, its latest status, and what the run that made it published. */
    private fun deploymentStatus(
        owner: String,
        name: String,
        id: Long,
    ): GraphDelta? {
        val repo = github.repository(owner, name)
        val deployment = repo?.let { actions.deployment(owner, name, id) }
        val statuses = deployment?.let { actions.statuses(owner, name, id) }.orEmpty()
        val latest = latest(statuses)
        if (repo == null || deployment == null || latest == null) return null
        val candidates =
            runIdOf(statuses)?.let { listOfNotNull(actions.run(owner, name, it)) }
                ?: actions
                    .runs(owner, name, createdSince = deployment.createdAt.minus(settings.maxRunDuration))
                    .filter { it.headSha == deployment.sha }
                    .toList()
        val runs = published(owner, repo, candidates, PackageCache())
        return mapper.map(refOf(repo), emptyList(), listOf(DeploymentRead(deployment, latest, deployedBy(deployment, statuses, runs))))
    }

    /** Each run with what it published, by GitHub Packages' record of the repository's versions. */
    private fun published(
        owner: String,
        repo: GitHubRepo,
        runs: List<WorkflowRun>,
        packages: PackageCache,
    ): List<RunRead> {
        val distinct = runs.distinctBy { it.id }
        if (distinct.isEmpty()) return emptyList()
        val earliest = distinct.minOf { it.startedAt }
        val registries = DeploymentSettings.DEFAULT_REGISTRIES + settings.registries
        val releases =
            settings.packageTypes.flatMap { type ->
                val owned = packages.of(owner, type)
                owned.packages
                    .filter { it.repository?.fullName.equals(repo.fullName, ignoreCase = true) }
                    .flatMap { pkg ->
                        actions
                            .versions(owned, owner, pkg) { it.createdAt.isBefore(earliest) }
                            .map { PackageRelease(PublishedArtifact.of(owner, pkg, it, registries), it.createdAt) }
                    }
            }
        val attribution = PackageAttribution.attribute(releases, distinct, Instant.now(clock))
        attribution.ambiguous.forEach { LogEvents.actionsPackageAmbiguous(repo.fullName, it.artifact.name) }
        return distinct.map { RunRead(it, attribution.byRun[it.id].orEmpty()) }
    }

    /**
     * The run that made a deployment: the one its statuses name, or else the newest run of its commit
     * that published something, for a deployment something other than a workflow job created.
     */
    private fun deployedBy(
        deployment: GitHubDeployment,
        statuses: List<DeploymentStatus>,
        runs: List<RunRead>,
    ): RunRead? =
        runIdOf(statuses)?.let { id -> runs.firstOrNull { it.run.id == id } }
            ?: runs
                .filter { it.run.headSha == deployment.sha && it.artifacts.isNotEmpty() }
                .maxByOrNull { it.run.updatedAt }

    private fun refOf(repo: GitHubRepo) = RepositoryRef(repositories.keyOf(repo), repo.htmlUrl)

    /** An owner's packages of each kind, listed once per read however many repositories ask. */
    private inner class PackageCache {
        private val listed = mutableMapOf<Pair<String, String>, OwnedPackages>()

        fun of(
            owner: String,
            type: String,
        ): OwnedPackages = listed.getOrPut(owner.lowercase() to type) { actions.packages(owner, type) }
    }

    private companion object {
        const val EVENT_HEADER = "X-GitHub-Event"
        const val WORKFLOW_RUN = "workflow_run"
        const val DEPLOYMENT_STATUS = "deployment_status"
        const val COMPLETED = "completed"
        const val INACTIVE = "inactive"
    }
}

/** The newest status that says how the deployment went, rather than that a later one replaced it. */
private fun latest(statuses: List<DeploymentStatus>): DeploymentStatus? = statuses.firstOrNull { !it.isReplacement }

private fun runIdOf(statuses: List<DeploymentStatus>): Long? = statuses.firstNotNullOfOrNull { it.runId }

private fun ignored(
    type: String,
    reason: String,
): GraphDelta? {
    LogEvents.actionsWebhookIgnored(type, reason)
    return null
}

private fun parse(body: ByteArray): JsonNode? =
    try {
        JSON.readTree(body)?.takeIf { it.isObject }
    } catch (_: JacksonException) {
        null
    }

private val JSON: JsonMapper = JsonMapper.builder().build()
