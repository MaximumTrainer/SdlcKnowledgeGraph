package com.repodatagraph.ops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Alerts and runbooks cannot drift apart (#44, FR10). Every alert under ops/alerts names a runbook
 * that exists under docs/runbooks, and every runbook there except the README is named by at least one
 * alert. An alert nobody can act on and a runbook for an alert that no longer exists both fail here.
 */
class AlertRunbookTest {
    private val repository = File("..").canonicalFile

    @Test
    fun `every alert names a runbook that exists, and every runbook is named by an alert`() {
        val check = AlertRunbooks(repository.resolve("ops/alerts"), repository.resolve("docs/runbooks"))

        assertThat(check.alerts()).describedAs("alerts under ops/alerts").isNotEmpty()
        assertThat(check.problems()).isEmpty()
    }

    @Test
    fun `an alert whose runbook does not exist is named`(
        @TempDir dir: File,
    ) {
        val (alerts, runbooks) = fixture(dir, runbooks = listOf("instance-down.md"))
        alerts.resolve("app.rules.yml").writeText(rules("InstanceDown" to "instance-down.md", "Mystery" to "nothing.md"))

        assertThat(AlertRunbooks(alerts, runbooks).problems())
            .containsExactly("Mystery (ops/alerts/app.rules.yml) names docs/runbooks/nothing.md, which does not exist")
    }

    @Test
    fun `an alert with no runbook at all is named`(
        @TempDir dir: File,
    ) {
        val (alerts, runbooks) = fixture(dir, runbooks = emptyList())
        alerts.resolve("app.rules.yml").writeText("groups:\n  - name: app\n    rules:\n      - alert: Silent\n        expr: vector(1)\n")

        assertThat(AlertRunbooks(alerts, runbooks).problems())
            .containsExactly("Silent (ops/alerts/app.rules.yml) has no runbook_url annotation")
    }

    @Test
    fun `a runbook no alert names is named, and the README is not`(
        @TempDir dir: File,
    ) {
        val (alerts, runbooks) = fixture(dir, runbooks = listOf("README.md", "instance-down.md", "orphan.md"))
        alerts.resolve("generated").mkdirs()
        alerts.resolve("generated/slo.rules.yml").writeText(rules("InstanceDown" to "instance-down.md"))

        assertThat(AlertRunbooks(alerts, runbooks).problems())
            .containsExactly("docs/runbooks/orphan.md is not the runbook of any alert")
    }

    @Test
    fun `promtool test files are not rule files`(
        @TempDir dir: File,
    ) {
        val (alerts, runbooks) = fixture(dir, runbooks = listOf("instance-down.md"))
        alerts.resolve("app.rules.yml").writeText(rules("InstanceDown" to "instance-down.md"))
        alerts.resolve("tests").mkdirs()
        alerts.resolve("tests/app.test.yml").writeText("rule_files: [../app.rules.yml]\ntests: []\n")

        assertThat(AlertRunbooks(alerts, runbooks).problems()).isEmpty()
    }

    private fun fixture(
        dir: File,
        runbooks: List<String>,
    ): Pair<File, File> {
        val alerts = dir.resolve("ops/alerts").apply { mkdirs() }
        val books = dir.resolve("docs/runbooks").apply { mkdirs() }
        runbooks.forEach { books.resolve(it).writeText("# $it\n") }
        return alerts to books
    }

    private fun rules(vararg alerts: Pair<String, String>) =
        buildString {
            append("groups:\n  - name: test\n    rules:\n")
            alerts.forEach { (name, runbook) ->
                append("      - alert: $name\n        expr: vector(1)\n        annotations:\n")
                append("          runbook_url: $RUNBOOKS/$runbook\n")
            }
        }

    companion object {
        const val RUNBOOKS = "https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/docs/runbooks"
    }
}

/** Reads the alert rules and the runbooks, and says where they disagree. */
class AlertRunbooks(
    private val alertsDir: File,
    private val runbooksDir: File,
) {
    private val yaml = ObjectMapper(YAMLFactory())

    data class Alert(
        val name: String,
        val file: String,
        val runbookUrl: String?,
    )

    fun alerts(): List<Alert> =
        alertsDir
            .walkTopDown()
            .onEnter { it.name != "tests" }
            .filter { it.isFile && it.extension == "yml" }
            .sortedBy { it.path }
            .flatMap { file ->
                yaml
                    .readTree(file)
                    .path("groups")
                    .flatMap { it.path("rules") }
                    .filter { it.has("alert") }
                    .map { Alert(it.path("alert").asText(), relative(file), it.runbookUrl()) }
            }.toList()

    fun problems(): List<String> {
        val alerts = alerts()
        val named = alerts.mapNotNull { it.runbookUrl?.substringAfter(RUNBOOK_PREFIX, "") }.toSet()
        val missing = alerts.mapNotNull(::problemWith)
        val orphans =
            runbooksDir
                .listFiles { file -> file.isFile && file.extension == "md" && file.name != "README.md" }
                .orEmpty()
                .map { it.name }
                .sorted()
                .filterNot { it in named }
                .map { "docs/runbooks/$it is not the runbook of any alert" }
        return missing + orphans
    }

    private fun problemWith(alert: Alert): String? {
        val url = alert.runbookUrl ?: return "${alert.name} (${alert.file}) has no runbook_url annotation"
        val runbook = url.substringAfter(RUNBOOK_PREFIX, "")
        val where = "${alert.name} (${alert.file}) names"
        return when {
            runbook.isEmpty() -> "$where $url, which is not under docs/runbooks"
            !runbooksDir.resolve(runbook).isFile -> "$where docs/runbooks/$runbook, which does not exist"
            else -> null
        }
    }

    private fun JsonNode.runbookUrl(): String? = path("annotations").path("runbook_url").takeIf { it.isTextual }?.asText()

    private fun relative(file: File) = "ops/alerts/" + file.relativeTo(alertsDir).invariantSeparatorsPath

    private companion object {
        const val RUNBOOK_PREFIX = "/docs/runbooks/"
    }
}
