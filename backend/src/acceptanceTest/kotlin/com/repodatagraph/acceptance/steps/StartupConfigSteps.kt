package com.repodatagraph.acceptance.steps

import com.repodatagraph.RepoDataGraphApplication
import io.cucumber.java.After
import io.cucumber.java.en.Given
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
    private val profile: String = "docker"
    private var arguments: List<String> = emptyList()

    @After
    fun closeContext() {
        context?.close()
    }

    @When("the application starts with the {string} profile and NEO4J_URI unset")
    fun startsWithoutUri(profile: String) = start(profile, uri = "")

    @When("the application starts with the {string} profile and NEO4J_URI set")
    fun startsWithUri(profile: String) = start(profile, uri = "bolt://neo4j.invalid:7687")

    @Given("AUTH_DISABLED=true")
    fun authDisabled() {
        arguments = listOf("--AUTH_DISABLED=true", "--AUTH_ISSUER_URI=", "--SDLC_READ_ONLY=true")
    }

    @Given("the property sdlc.auth.disabled=false")
    fun authDisabledProperty() {
        arguments = listOf("--sdlc.auth.disabled=false", "--AUTH_ISSUER_URI=https://id.example.test/realms/sdlc")
    }

    @Given("no identity provider and SDLC_READ_ONLY={word}")
    fun noIdentityProvider(readOnly: String) {
        arguments = listOf("--AUTH_ISSUER_URI=", "--SDLC_READ_ONLY=$readOnly")
    }

    @Given("the identity provider {string} and SDLC_READ_ONLY={word}")
    fun anIdentityProvider(
        issuer: String,
        readOnly: String,
    ) {
        arguments = listOf("--AUTH_ISSUER_URI=$issuer", "--SDLC_READ_ONLY=$readOnly")
    }

    @When("the backend starts")
    fun theBackendStarts() = start(profile, uri = "bolt://neo4j.invalid:7687", arguments)

    @Then("startup does not fail on authentication")
    fun startupDoesNotFailOnAuthentication() {
        val messages = failure?.let { causes(it).mapNotNull { cause -> cause.message } }.orEmpty()
        AUTHENTICATION_SETTINGS.forEach { setting ->
            assertThat(messages).noneSatisfy { assertThat(it).contains(setting) }
        }
    }

    @Then("startup fails with an unknown-setting error naming {string}")
    fun startupFailsWithAnUnknownSetting(setting: String) {
        assertThat(failure).describedAs("startup should have failed").isNotNull()
        assertThat(causes(failure!!).mapNotNull { it.message }).anySatisfy {
            assertThat(it).contains(setting).contains("not a setting")
        }
    }

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

    // A handful of arguments once per scenario: the copy the spread makes costs nothing here.
    @Suppress("SpreadOperator")
    private fun start(
        profile: String,
        uri: String,
        extra: List<String> = emptyList(),
    ) {
        val arguments =
            listOf(
                "--NEO4J_URI=$uri",
                "--spring.main.web-application-type=none",
                "--spring.main.lazy-initialization=true",
                "--spring.main.banner-mode=off",
            ) + extra
        failure =
            runCatching {
                context =
                    SpringApplicationBuilder(RepoDataGraphApplication::class.java)
                        .profiles(profile)
                        .build()
                        .run(*arguments.toTypedArray())
            }.exceptionOrNull()
    }

    private fun causes(error: Throwable): List<Throwable> = generateSequence(error) { it.cause }.toList()

    private companion object {
        val AUTHENTICATION_SETTINGS = listOf("AUTH_ISSUER_URI", "AUTH_DISABLED", "SDLC_READ_ONLY")
    }
}
