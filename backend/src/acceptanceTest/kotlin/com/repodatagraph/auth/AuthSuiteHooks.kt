package com.repodatagraph.auth

import io.cucumber.java.BeforeAll

/**
 * Starts Keycloak and names it as the issuer before Cucumber builds the Spring context, which it does
 * on the first scenario. A system property rather than a dynamic property, because the startup check
 * that insists on an issuer reads the environment before dynamic properties exist. The other suites
 * run with the development bypass, where an issuer is ignored.
 *
 * A top-level function, because Cucumber wants a static method and a companion object's is not one.
 */
@BeforeAll
fun nameTheIssuer() {
    System.setProperty("sdlc.auth.issuer-uri", DevRealmKeycloak.issuer)
}
