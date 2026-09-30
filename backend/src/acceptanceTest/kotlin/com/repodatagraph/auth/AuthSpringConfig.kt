package com.repodatagraph.auth

import com.repodatagraph.support.Neo4jTestcontainersConfig
import io.cucumber.spring.CucumberContextConfiguration
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Boots the real application on a random port with authentication on, trusting the Keycloak in
 * [DevRealmKeycloak] as its issuer, backed by a Testcontainers Neo4j.
 */
@CucumberContextConfiguration
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["sdlc.auth.disabled=false"],
)
@Import(Neo4jTestcontainersConfig::class)
@ActiveProfiles("test")
// /actuator/prometheus is one of the endpoints that must stay public, and tests turn it off unless asked.
@AutoConfigureObservability
// Cucumber instantiates this class to build the context, so it cannot become an object.
@Suppress("UtilityClassWithPublicConstructor")
class AuthSpringConfig {
    companion object {
        /** The issuer's port is only known once Keycloak has started. */
        @JvmStatic
        @DynamicPropertySource
        fun issuer(registry: DynamicPropertyRegistry) {
            registry.add("sdlc.auth.issuer-uri") { DevRealmKeycloak.issuer }
        }
    }
}
