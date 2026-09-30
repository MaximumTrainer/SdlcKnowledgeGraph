package com.repodatagraph.config

import com.repodatagraph.domain.model.FreshnessPolicy
import com.repodatagraph.domain.ontology.OntologyRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The freshness policy (#93), from configuration and the registry's source systems. Built once at
 * startup, so a window for a source the registry does not declare stops the application starting
 * rather than being quietly ignored.
 */
@Configuration
@EnableConfigurationProperties(FreshnessProperties::class)
class FreshnessConfig {
    @Bean
    fun freshnessPolicy(
        properties: FreshnessProperties,
        registry: OntologyRegistry,
    ): FreshnessPolicy = FreshnessPolicy(properties.defaultWindow, properties.windows, registry.sources.map { it.name })
}
