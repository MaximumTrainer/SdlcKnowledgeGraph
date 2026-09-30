package com.repodatagraph.auth

import io.cucumber.java.AfterAll
import io.cucumber.java.BeforeAll

/**
 * Starts Keycloak and names it as the issuer before Cucumber builds the Spring context, which it does
 * on the first scenario. A system property rather than a dynamic property, because the startup check
 * that insists on an issuer reads the environment before dynamic properties exist. The other suites
 * run with the development bypass, where an issuer is ignored.
 *
 * Top-level functions, because Cucumber wants a static method and a companion object's is not one.
 */
@BeforeAll
fun nameTheIssuer() {
    System.setProperty(ISSUER_PROPERTY, DevRealmKeycloak.issuer)
}

/**
 * Takes the issuer back once the suite is done. The suites share a JVM, and a system property outranks
 * the AUTH_ISSUER_URI a later suite's startup scenario unsets, so a leftover issuer would let the
 * application start where that scenario expects it to refuse - as it did whenever this suite ran
 * first.
 */
@AfterAll
fun forgetTheIssuer() {
    System.clearProperty(ISSUER_PROPERTY)
}

private const val ISSUER_PROPERTY = "sdlc.auth.issuer-uri"
