package com.repodatagraph.readonly

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * The read-only features, run against a second application context started with
 * `sdlc.read-only=true`.
 *
 * A separate suite rather than a tag on the main one: Cucumber shares one Spring context across every
 * scenario of a glue package, and the property that matters here is fixed when that context starts.
 * The glue is a sibling package of `com.repodatagraph.acceptance`, not a child of it, so neither
 * suite discovers the other's context configuration.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("read-only")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.repodatagraph.readonly")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, html:build/reports/cucumber/read-only.html, json:build/reports/cucumber/read-only.json",
)
class RunReadOnlyCucumberTest
