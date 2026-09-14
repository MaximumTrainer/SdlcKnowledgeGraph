package com.repodatagraph.support.connector

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Puts a [FakeGitHub] in the context and points the GitHub connector at it.
 *
 * Started in a companion object rather than in the bean method, because the connector's base URL is
 * a configuration property and configuration is bound before any bean exists. Whoever imports this
 * has to hand [baseUrl] to `connectors.github.base-url` - see `CucumberSpringConfig`.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeGitHubConfig {
    @Bean
    fun fakeGitHub(): FakeGitHub = INSTANCE

    companion object {
        /**
         * One fake for the whole run, on a port the operating system picks.
         *
         * A per-scenario server would mean a per-scenario base URL, and so a new Spring context for
         * every scenario - minutes of startup to avoid a `reset()` call.
         */
        val INSTANCE: FakeGitHub = FakeGitHub().apply { start() }

        val baseUrl: String get() = INSTANCE.baseUrl
    }
}
