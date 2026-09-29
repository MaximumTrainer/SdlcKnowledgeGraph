package com.repodatagraph.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What an instance is running, as a meter (#44, FR7): a constant 1 labelled with the build, the
 * ontology and the commit, so a dashboard or an alert can join any other series to it.
 */
class BuildInfoMetricsTest {
    private val meters = SimpleMeterRegistry()

    private fun gauge() = meters.find("sdlc.build.info").gauge()

    @Test
    fun `is a constant 1 labelled with the version, the ontology version and the commit`() {
        BuildInfoMetrics(version = "0.0.1", ontologyVersion = "1.0.0", commit = "0123abc").bindTo(meters)

        val info = checkNotNull(gauge())
        assertThat(info.value()).isEqualTo(1.0)
        assertThat(info.id.getTag("version")).isEqualTo("0.0.1")
        assertThat(info.id.getTag("ontology_version")).isEqualTo("1.0.0")
        assertThat(info.id.getTag("commit")).isEqualTo("0123abc")
    }

    @Test
    fun `says unknown rather than leaving a label empty`() {
        BuildInfoMetrics(version = null, ontologyVersion = "1.0.0", commit = "").bindTo(meters)

        assertThat(checkNotNull(gauge()).id.getTag("version")).isEqualTo("unknown")
        assertThat(checkNotNull(gauge()).id.getTag("commit")).isEqualTo("unknown")
    }
}
