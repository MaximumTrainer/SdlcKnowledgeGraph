package com.repodatagraph.acceptance

import com.repodatagraph.support.Neo4jTestcontainersConfig
import io.cucumber.spring.CucumberContextConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Boots the real application on a random port, backed by a Testcontainers Neo4j, once per
 * acceptance-test run. Cucumber shares this Spring context across all scenarios.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Neo4jTestcontainersConfig::class)
@ActiveProfiles("test")
class CucumberSpringConfig
