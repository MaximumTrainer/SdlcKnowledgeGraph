package com.repodatagraph.acceptance.steps

import com.repodatagraph.RepoDataGraphApplication
import io.cucumber.java.After
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/**
 * Starts a second, separate application the way the container does, and records whether it came up.
 *
 * Command-line arguments outrank the environment, so `--NEO4J_URI=` unsets it here even on a machine
 * that exports one. Nothing else is started for real: the web server is off and the database is never
 * reached, because the check under test runs before any bean is created.
 */
class StartupConfigSteps {
    private var context: ConfigurableApplicationContext? = null
    private var failure: Throwable? = null

    @After
    fun closeContext() {
        context?.close()
    }

    @When("the application starts with the {string} profile and NEO4J_URI unset")
    fun startsWithoutUri(profile: String) = start(profile, uri = "")

    @When("the application starts with the {string} profile and NEO4J_URI set")
    fun startsWithUri(profile: String) = start(profile, uri = "bolt://neo4j.invalid:7687")

    @Then("startup fails with a message containing {string}")
    fun startupFails(expected: String) {
        assertThat(failure).describedAs("startup should have failed").isNotNull()
        assertThat(causes(failure!!).mapNotNull { it.message }).anySatisfy { assertThat(it).contains(expected) }
    }

    @Then("startup does not fail on the database URI")
    fun startupDoesNotFailOnUri() {
        val messages = failure?.let { causes(it).mapNotNull { cause -> cause.message } }.orEmpty()
        assertThat(messages).noneSatisfy { assertThat(it).contains("NEO4J_URI") }
    }

    private fun start(
        profile: String,
        uri: String,
    ) {
        failure =
            runCatching {
                context =
                    SpringApplicationBuilder(RepoDataGraphApplication::class.java)
                        .profiles(profile)
                        .run(
                            "--NEO4J_URI=$uri",
                            "--spring.main.web-application-type=none",
                            "--spring.main.lazy-initialization=true",
                            "--spring.main.banner-mode=off",
                        )
            }.exceptionOrNull()
    }

    private fun causes(error: Throwable): List<Throwable> = generateSequence(error) { it.cause }.toList()
}
