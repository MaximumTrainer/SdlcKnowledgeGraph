package com.repodatagraph.readonly

import com.repodatagraph.support.Neo4jTestcontainersConfig
import io.cucumber.spring.CucumberContextConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Boots the real application on a random port, read-only and with no identity provider - the
 * anonymous read-only mode the dogfood instance runs (#118) - backed by a Testcontainers Neo4j.
 */
@CucumberContextConfiguration
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["sdlc.read-only=true", "sdlc.auth.issuer-uri="],
)
@Import(Neo4jTestcontainersConfig::class)
@ActiveProfiles("test")
// Cucumber instantiates this class to build the context, so it cannot become an object.
@Suppress("UtilityClassWithPublicConstructor")
class ReadOnlySpringConfig
