package com.repodatagraph.auth

import com.repodatagraph.support.Neo4jTestcontainersConfig
import io.cucumber.spring.CucumberContextConfiguration
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Boots the real application on a random port with authentication on, trusting the Keycloak in
 * [DevRealmKeycloak] as its issuer, backed by a Testcontainers Neo4j.
 *
 * The issuer is handed over as a system property before the context starts ([AuthSuiteHooks]) rather
 * than through `@DynamicPropertySource`, because the check that refuses to start without an issuer
 * (AuthGuard) runs before dynamic properties are added.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Neo4jTestcontainersConfig::class)
@ActiveProfiles("test")
// /actuator/prometheus is one of the endpoints that must stay public, and tests turn it off unless asked.
@AutoConfigureObservability
// Cucumber instantiates this class to build the context, so it cannot become an object.
@Suppress("UtilityClassWithPublicConstructor")
class AuthSpringConfig
