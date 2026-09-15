package com.repodatagraph.adapter.out.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import com.repodatagraph.domain.port.out.connector.WebhookEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * What GitHub pushes, and what of it the graph records.
 *
 * A webhook is a hint, not a source of truth. It says "something changed here"; what changed is then
 * read back from the API exactly as a scheduled run would read it, through [RepositoryReader]. The
 * alternative - trusting the payload - means a graph whose contents depend on which fields GitHub
 * happened to include in which event, and a second mapping to keep in step with the first.
 *
 * Only events that change something the graph holds do anything at all. A star, a comment, a push
 * that touches nothing this connector reads: accepted and ignored, because the event was genuine and
 * refusing it would have GitHub retrying something that will never mean anything.
 */
@Component
class GitHubWebhookHandler(
    private val client: GitHubClient,
    private val reader: RepositoryReader,
    private val verifier: GitHubWebhookVerifier,
    private val contentsMapper: RepositoryContentsMapper,
    private val properties: GitHubProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Null when the event says nothing this graph records. */
    fun handle(event: WebhookEvent): GraphDelta? =
        parse(event.body)
            ?.let { payload ->
                payload to
                    payload
                        .path("organization")
                        .path("login")
                        .asText()
                        .ifBlank { orgOf(payload) }
            }?.takeIf { (_, org) -> org.isNotBlank() }
            ?.let { (payload, org) -> interpret(org, payload, verifier.eventType(event)) }

    private fun interpret(
        org: String,
        payload: JsonNode,
        type: String?,
    ): GraphDelta? =
        when (type) {
            "repository" -> reread(org, payload)
            "push" -> if (touchesSomethingRead(payload)) reread(org, payload) else null
            "team" -> team(org, payload)
            else -> {
                log.debug("ignoring GitHub {} event", type)
                null
            }
        }

    /**
     * Reads the repository back from the API rather than believing the payload.
     *
     * An event's `repository` object is a snapshot of some of the fields, and which ones depends on
     * the event. Reading it back means one mapping rather than two, and means a webhook and a
     * scheduled run cannot disagree about what a repository is.
     */
    private fun reread(
        org: String,
        payload: JsonNode,
    ): GraphDelta? =
        payload
            .path("repository")
            .path("name")
            .asText()
            .takeIf { it.isNotBlank() }
            ?.let { client.repository(org, it) }
            ?.let { repo ->
                val read = reader.read(org, repo)
                // A dependency on one of our own packages needs every repository read to resolve,
                // which a webhook has not done. It is left to the next scheduled run.
                if (read.pending.isNotEmpty()) log.debug("{} internal dependencies deferred", read.pending.size)
                read.delta
            }

    /**
     * Whether a push changed a file the graph reads.
     *
     * Most pushes do not. Re-reading a repository for every commit would spend the rate limit on
     * source files nothing here looks at, and the branch matters too: a change on a feature branch is
     * not yet a fact about the repository.
     */
    private fun touchesSomethingRead(payload: JsonNode): Boolean {
        val branch = payload.path("ref").asText().removePrefix(BRANCH_REF)
        val default = payload.path("repository").path("default_branch").asText()
        if (default.isNotBlank() && branch != default) return false

        return payload
            .path("commits")
            .asSequence()
            .flatMap { commit -> CHANGE_FIELDS.asSequence().flatMap { commit.path(it).asSequence() } }
            .map { it.asText() }
            .any { path -> contentsMapper.isInteresting(path) || path.substringAfterLast('/') == CODEOWNERS }
    }

    /**
     * A team renamed. Only the name changes here; who owns what is read from CODEOWNERS, not from
     * team membership, so a membership change is not an ownership change.
     */
    private fun team(
        org: String,
        payload: JsonNode,
    ): GraphDelta? {
        val slug =
            payload
                .path("team")
                .path("slug")
                .asText()
                .ifBlank { return null }
        val host =
            properties.baseUrl
                .substringAfter("//")
                .substringBefore("/")
                .removePrefix("api.")
        return GraphDelta(nodes = listOf(NodeUpsert(type = "Team", props = mapOf("name" to "$host/$org/$slug"))))
    }

    private fun orgOf(payload: JsonNode): String =
        payload
            .path("repository")
            .path("full_name")
            .asText()
            .substringBefore('/')

    private fun parse(body: ByteArray): JsonNode? =
        try {
            JSON.readTree(body).takeIf { it.isObject }
        } catch (malformed: com.fasterxml.jackson.core.JacksonException) {
            // A verified signature over unparseable JSON is GitHub sending something new, not an
            // attack. Nothing to record, and nothing to retry.
            log.warn("a verified GitHub webhook body was not JSON: {}", malformed.message)
            null
        }

    private companion object {
        const val BRANCH_REF = "refs/heads/"
        const val CODEOWNERS = "CODEOWNERS"
        val CHANGE_FIELDS = listOf("added", "modified", "removed")
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
