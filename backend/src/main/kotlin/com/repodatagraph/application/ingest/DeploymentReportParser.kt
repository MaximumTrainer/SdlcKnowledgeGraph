package com.repodatagraph.application.ingest

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.exception.InvalidGitRemoteException
import com.repodatagraph.domain.identity.GitRemoteParser
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Reads a deployment report, collecting every problem rather than stopping at the first.
 *
 * Read from a JSON tree rather than bound to a class: binding stops at the first missing field, and a
 * workflow step sending a malformed report is fixed in one round only if it is told everything at once.
 */
@Component
class DeploymentReportParser(
    private val objectMapper: ObjectMapper,
    private val gitRemoteParser: GitRemoteParser = GitRemoteParser(),
) {
    fun parse(body: ByteArray): ParsedReport =
        readObject(body)?.let(::read) ?: ParsedReport.Invalid(mapOf("body" to "body must be a JSON object"))

    private fun read(root: JsonNode): ParsedReport {
        val fields = Fields(root)

        val repository = fields.text("repository")?.let { fields.remote("repository", it) }
        val commitSha = fields.text("commitSha")
        val environment = fields.text("environment")
        val runUrl = fields.text("runUrl")
        val status = fields.text("status")?.let { fields.oneOf("status", it, STATUSES, "status must be SUCCESS or FAILED") }
        val deployedAt = fields.text("deployedAt")?.let { fields.instant("deployedAt", it) }
        val artifacts = fields.artifacts()
        val workflowPath = fields.text("pipeline.workflowPath")
        val provider =
            root
                .path("pipeline")
                .path("provider")
                .asText("")
                .ifBlank { DEFAULT_PROVIDER }

        if (fields.errors.isNotEmpty()) return ParsedReport.Invalid(fields.errors)
        return ParsedReport.Valid(
            DeploymentReport(
                repository = checkNotNull(repository),
                commitSha = checkNotNull(commitSha),
                artifacts = artifacts,
                environment = checkNotNull(environment),
                status = checkNotNull(status),
                deployedAt = checkNotNull(deployedAt),
                deployedBy = root.path("deployedBy").asText("").ifBlank { null },
                runUrl = checkNotNull(runUrl),
                pipeline = ReportedPipeline(provider, checkNotNull(workflowPath)),
            ),
        )
    }

    private fun readObject(body: ByteArray): JsonNode? =
        try {
            objectMapper.readTree(body)?.takeIf { it.isObject }
        } catch (_: JacksonException) {
            null
        }

    /** The report's fields, each read at most once, with what is wrong with them gathered in [errors]. */
    private inner class Fields(
        private val root: JsonNode,
    ) {
        val errors = linkedMapOf<String, String>()

        /** A non-blank string at a dotted [path], or null with the problem recorded. */
        fun text(path: String): String? {
            val node = path.split('.').fold(root) { node, field -> node.path(field) }
            return node.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
                ?: fail(path, "$path must not be blank")
        }

        fun remote(
            path: String,
            value: String,
        ): String? =
            try {
                gitRemoteParser.parse(value)
                value
            } catch (invalid: InvalidGitRemoteException) {
                fail(path, "$path is not a git remote: ${invalid.message}")
            }

        fun oneOf(
            path: String,
            value: String,
            allowed: Set<String>,
            message: String,
        ): String? = value.takeIf { it in allowed } ?: fail(path, message)

        fun instant(
            path: String,
            value: String,
        ): Instant? =
            try {
                Instant.parse(value)
            } catch (_: DateTimeParseException) {
                fail(path, "$path must be an ISO-8601 instant")
            }

        fun artifacts(): List<ReportedArtifact> {
            val node = root.path("artifacts")
            if (!node.isArray || node.isEmpty) return fail("artifacts", "artifacts must not be empty") ?: emptyList()
            return node.mapIndexedNotNull { index, artifact -> artifact(index, artifact) }
        }

        private fun artifact(
            index: Int,
            artifact: JsonNode,
        ): ReportedArtifact? {
            val name = artifact.path("name").asText("")
            val digest = artifact.path("digest").asText("").ifBlank { null }
            val tag = artifact.path("tag").asText("").ifBlank { null }
            return when {
                name.isBlank() -> fail("artifacts[$index].name", "artifacts[$index].name must not be blank")
                digest == null && tag == null -> fail("artifacts[$index]", "an artifact needs a digest or a tag")
                else -> ReportedArtifact(name, digest, tag)
            }
        }

        private fun <T> fail(
            path: String,
            message: String,
        ): T? {
            errors[path] = message
            return null
        }
    }

    private companion object {
        val STATUSES = setOf("SUCCESS", "FAILED")
        const val DEFAULT_PROVIDER = "github-actions"
    }
}
