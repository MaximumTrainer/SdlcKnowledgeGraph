package com.repodatagraph.auth

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * The authentication features (#114), run against a third application context that trusts a real
 * Keycloak.
 *
 * A separate suite for the reason the read-only one is: Cucumber shares one Spring context across a
 * glue package, and which issuer the API trusts is fixed when that context starts. The main suite
 * runs as a fixed test principal (TestPrincipalConfig), so its scenarios stay about what they test
 * rather than about signing in.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("auth")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.repodatagraph.auth")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, html:build/reports/cucumber/auth.html, json:build/reports/cucumber/auth.json",
)
class RunAuthCucumberTest
