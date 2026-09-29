package com.repodatagraph.acceptance.steps

import com.repodatagraph.acceptance.support.ApiWorld
import com.repodatagraph.acceptance.support.GraphStoreFaults
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.assertj.core.api.Assertions.assertThat

/**
 * Reads /actuator/prometheus the way a Prometheus server would. Counters only ever go up and other
 * scenarios share the application, so a count is asserted as the difference from a scrape taken when
 * the scenario started.
 */
class MetricsSteps(
    private val world: ApiWorld,
) {
    private var baseline: List<Sample> = emptyList()
    private var scraped: List<Sample> = emptyList()

    @Before("@metrics")
    fun takeBaseline() {
        baseline = scrape()
    }

    @After("@metrics")
    fun clearFaults() {
        GraphStoreFaults.next = null
    }

    @Given("the graph store fails on its next {string}")
    fun theStoreFailsOnItsNext(operation: String) {
        GraphStoreFaults.next = operation
    }

    @When("I scrape the metrics")
    fun iScrape() {
        scraped = scrape()
    }

    @Then("the metric {string} has the value {int} with label {string} set to {string}")
    fun theMetricHasTheValue(
        name: String,
        value: Int,
        label: String,
        labelValue: String,
    ) {
        val series = scraped.filter { it.name == name }
        assertThat(series).describedAs("$name in the scrape").isNotEmpty()
        assertThat(series.single().labels[label]).isEqualTo(labelValue)
        assertThat(series.single().value).isEqualTo(value.toDouble())
    }

    @Then("the scrape has a {string} series with label {string} set to {string}")
    fun theScrapeHasASeries(
        name: String,
        label: String,
        labelValue: String,
    ) {
        assertThat(scraped.filter { it.name == name }.map { it.labels[label] }).contains(labelValue)
    }

    @Then("{string} with type {string} and outcome {string} went up by {int}")
    fun wentUpByTypeAndOutcome(
        name: String,
        type: String,
        outcome: String,
        by: Int,
    ) {
        assertIncrease(name, mapOf("type" to type, "outcome" to outcome), by)
    }

    @Then("{string} with operation {string} went up by {int}")
    fun wentUpByOperation(
        name: String,
        operation: String,
        by: Int,
    ) {
        assertIncrease(name, mapOf("operation" to operation), by)
    }

    /**
     * A series written as Prometheus writes it, `name{label="value",...}`, naming only the labels
     * that matter: every series that carries them counts, so a claim about `connector="fake"` holds
     * whatever the other labels say.
     */
    @Then("the series {string} went up by {int}")
    fun theSeriesWentUpBy(
        series: String,
        by: Int,
    ) {
        val (name, labels) = selector(series)
        assertIncrease(name, labels, by)
    }

    /** For a gauge, which says what is true now rather than counting, so it is read as it stands. */
    @Then("the series {string} is {int}")
    fun theSeriesIs(
        series: String,
        value: Int,
    ) {
        assertThat(current(series)).describedAs(series).isEqualTo(value.toDouble())
    }

    @Then("the series {string} is less than {int}")
    fun theSeriesIsLessThan(
        series: String,
        bound: Int,
    ) {
        // NaN, a connector that never succeeded, is not less than anything, so it fails here too.
        assertThat(current(series)).describedAs(series).isLessThan(bound.toDouble())
    }

    private fun current(series: String): Double {
        val (name, labels) = selector(series)
        val matching = scrape().filter { it.name == name && labels.all { (k, v) -> it.labels[k] == v } }
        assertThat(matching).describedAs("$series in the scrape").hasSize(1)
        return matching.single().value
    }

    private fun selector(series: String): Pair<String, Map<String, String>> {
        val sample = Sample.parse("$series 0")
        assertThat(sample).describedAs("'$series' is not written as a Prometheus series").isNotNull
        return checkNotNull(sample).name to sample.labels
    }

    private fun assertIncrease(
        name: String,
        labels: Map<String, String>,
        by: Int,
    ) {
        val now = scrape()

        fun total(samples: List<Sample>) =
            samples.filter { it.name == name && labels.all { (k, v) -> it.labels[k] == v } }.sumOf { it.value }
        assertThat(total(now) - total(baseline)).describedAs("$name$labels").isEqualTo(by.toDouble())
    }

    private fun scrape(): List<Sample> {
        val response = world.get("/actuator/prometheus")
        assertThat(response.statusCode.value()).describedAs("GET /actuator/prometheus").isEqualTo(200)
        return response.body
            .orEmpty()
            .lineSequence()
            .mapNotNull(Sample::parse)
            .toList()
    }

    /** One line of the Prometheus text format: a name, its labels and a value. */
    private data class Sample(
        val name: String,
        val labels: Map<String, String>,
        val value: Double,
    ) {
        companion object {
            private val LINE = Regex("""^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})? (\S+)""")
            private val LABEL = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"""")

            fun parse(line: String): Sample? =
                LINE.find(line)?.takeUnless { line.startsWith("#") }?.destructured?.let { (name, labels, value) ->
                    Sample(name, LABEL.findAll(labels).associate { it.groupValues[1] to it.groupValues[2] }, value.toDouble())
                }
        }
    }
}
