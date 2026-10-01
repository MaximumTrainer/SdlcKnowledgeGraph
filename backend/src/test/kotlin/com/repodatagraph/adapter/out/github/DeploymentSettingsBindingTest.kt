package com.repodatagraph.adapter.out.github

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

/**
 * `connectors.github.deployments` as Spring binds it (#90), which is not always what the Kotlin
 * defaults say: an annotated default is bound as written, so a list must be given as its elements.
 */
class DeploymentSettingsBindingTest {
    private fun bind(properties: Map<String, String>): GitHubProperties =
        Binder(MapConfigurationPropertySource(properties)).bindOrCreate("connectors.github", GitHubProperties::class.java)

    @Test
    fun `with nothing configured it reads every kind of package GitHub serves an image or library as`() {
        val settings = bind(mapOf("connectors.github.orgs" to "acme")).deployments

        assertThat(settings.packageTypes).containsExactly("container", "npm", "maven")
        assertThat(settings.lookback).isEqualTo(Duration.ofDays(7))
        assertThat(settings.maxRunDuration).isEqualTo(Duration.ofHours(6))
        assertThat(settings.registryFor("container")).isEqualTo("ghcr.io")
    }

    @Test
    fun `configured kinds and registries replace the defaults`() {
        val settings =
            bind(
                mapOf(
                    "connectors.github.deployments.package-types" to "container",
                    "connectors.github.deployments.registries.container" to "ghcr.example.com",
                    "connectors.github.deployments.lookback" to "P1D",
                ),
            ).deployments

        assertThat(settings.packageTypes).containsExactly("container")
        assertThat(settings.registryFor("container")).isEqualTo("ghcr.example.com")
        assertThat(settings.registryFor("npm")).isEqualTo("npm.pkg.github.com")
        assertThat(settings.lookback).isEqualTo(Duration.ofDays(1))
    }
}
