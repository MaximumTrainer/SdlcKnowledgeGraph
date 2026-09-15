package com.repodatagraph.acceptance

import com.repodatagraph.support.Neo4jTestcontainersConfig
import com.repodatagraph.support.connector.FakeConnectorConfig
import com.repodatagraph.support.connector.FakeGitHubConfig
import com.repodatagraph.support.connector.FakeServiceNowConfig
import io.cucumber.spring.CucumberContextConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Boots the real application on a random port, backed by a Testcontainers Neo4j, once per
 * acceptance-test run. Cucumber shares this Spring context across all scenarios.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(
    Neo4jTestcontainersConfig::class,
    FakeConnectorConfig::class,
    FakeGitHubConfig::class,
    FakeServiceNowConfig::class,
)
@ActiveProfiles("test")
// Cucumber instantiates this class to build the context, so it cannot become an object however few
// members it has - and `@DynamicPropertySource` is only discovered on the context configuration class.
@Suppress("UtilityClassWithPublicConstructor")
class CucumberSpringConfig {
    companion object {
        /** A fake's port is only known once it has started, so each connector is told here. */
        @JvmStatic
        @DynamicPropertySource
        fun fakes(registry: DynamicPropertyRegistry) {
            registry.add("connectors.github.base-url") { FakeGitHubConfig.baseUrl }
            registry.add("connectors.servicenow.instance-url") { FakeServiceNowConfig.baseUrl }
        }
    }
}
