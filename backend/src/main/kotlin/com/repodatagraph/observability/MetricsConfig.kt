package com.repodatagraph.observability

import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.GraphStore
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/** The application's own meters (#44, FR7; docs/OBSERVABILITY.md). */
@Configuration(proxyBeanMethods = false)
class MetricsConfig {
    /** What the application is given wherever it asks for the store: the Neo4j store, instrumented. */
    @Bean
    @Primary
    fun instrumentedGraphStore(
        @Qualifier("neo4jGraphStore") store: GraphStore,
        meters: MeterRegistry,
    ): GraphStore = InstrumentedGraphStore(store, meters)

    @Bean
    fun buildInfoMetrics(
        build: ObjectProvider<BuildProperties>,
        registry: OntologyRegistry,
        @Value("\${sdlc.deployment.commit:}") commit: String,
    ) = BuildInfoMetrics(build.ifAvailable?.version, registry.version, commit)
}
