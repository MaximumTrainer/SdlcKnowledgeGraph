package com.repodatagraph.domain.identity

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.repodatagraph.domain.exception.InvalidGitRemoteException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The parser that decides what a Repository is.
 *
 * Its table of forms is shared with the TypeScript parser the editing screen uses
 * (`frontend/src/test/fixtures/git-remotes.json`), read from that one file by both. Two
 * implementations of one rule drift the moment they are tested separately, and a screen that
 * previews a different key from the one the API derives is worse than showing no preview at all.
 *
 * Read from disk the same way OpenApiExportTest reads the committed OpenAPI document, so the two
 * suites cannot quietly diverge from the fixture either.
 */
class GitRemoteParserTest {
    private val parser = GitRemoteParser()

    @TestFactory
    fun `accepts every form a real remote is written in`() =
        table("accepted").map { case ->
            val input = case["input"]!!
            DynamicTest.dynamicTest("$input (${case["why"]})") {
                val remote = parser.parse(input)

                assertThat(remote.host).isEqualTo(case["host"])
                assertThat(remote.org).isEqualTo(case["org"])
                assertThat(remote.name).isEqualTo(case["name"])
                assertThat(remote.canonicalUrl).isEqualTo("https://${case["host"]}/${case["org"]}/${case["name"]}")
                assertThat(remote.key).isEqualTo("${case["host"]}/${case["org"]}/${case["name"]}")
            }
        }

    @TestFactory
    fun `refuses what is not a git remote`() =
        table("rejected").map { case ->
            val input = case["input"]!!
            DynamicTest.dynamicTest("${input.ifBlank { "<blank>" }} (${case["why"]})") {
                assertThatThrownBy { parser.parse(input) }
                    .isInstanceOf(InvalidGitRemoteException::class.java)
                    .hasMessageContaining(input.trim().ifBlank { "" })
            }
        }

    private fun table(group: String): List<Map<String, String>> {
        val fixture = File("../frontend/src/test/fixtures/git-remotes.json")
        require(fixture.exists()) { "the shared remote table is missing at ${fixture.absolutePath}" }
        val root = ObjectMapper().registerKotlinModule().readTree(fixture)
        return root.path(group).map { node ->
            node.properties().associate { (key, value) -> key to value.asText() }
        }
    }
}
