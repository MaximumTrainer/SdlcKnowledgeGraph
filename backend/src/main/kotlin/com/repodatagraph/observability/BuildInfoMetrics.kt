package com.repodatagraph.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder

/**
 * What an instance is running, as `sdlc_build_info{version,ontology_version,commit} 1` (#44, FR7): a
 * constant, so a query can join any other series to the build that produced it. A value that is not
 * known says `unknown` rather than leaving a label empty, which Prometheus would drop.
 */
class BuildInfoMetrics(
    private val version: String?,
    private val ontologyVersion: String,
    private val commit: String?,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder("sdlc.build.info") { 1.0 }
            .description("The build, ontology and commit this instance is running; always 1")
            .tag("version", known(version))
            .tag("ontology_version", known(ontologyVersion))
            .tag("commit", known(commit))
            .register(registry)
    }

    private fun known(value: String?) = value?.takeIf { it.isNotBlank() } ?: "unknown"
}
