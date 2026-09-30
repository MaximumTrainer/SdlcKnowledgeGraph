package com.repodatagraph.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Refuses to start an instance that would accept writes from callers it cannot name (#118).
 *
 * Two refusals. The development bypass AUTH_DISABLED no longer exists (#118, FR-3), and a removed
 * switch left in an environment is refused by name rather than ignored: an operator who set it meant
 * something by it, and silently doing something else is how an instance ends up open. And no
 * identity provider on a writable instance: with no issuer the API cannot tell one caller from
 * another, so it may only run the anonymous read-only mode ([AuthMode]), serving reads to anyone and
 * refusing every write. An unauthenticated writable instance is not a configuration anyone should be
 * able to reach by omission (#48, FR6; docs/DEPLOYMENT.md, D4 and D13).
 *
 * An [EnvironmentPostProcessor], like [Neo4jUriGuard], so the refusal comes before any bean exists and
 * is the only thing in the log. It reads the `sdlc.auth` properties, so it runs after the application
 * configuration that maps them from the environment has loaded.
 */
class AuthGuard : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        check(REMOVED.none(environment::containsProperty)) {
            "AUTH_DISABLED is not a setting: the development bypass was removed (#118). Unset it. To sign in " +
                "locally, run the Keycloak the default compose stack starts and set AUTH_ISSUER_URI; to serve " +
                "reads without an identity provider, set SDLC_READ_ONLY=true and leave AUTH_ISSUER_URI unset."
        }
        val issuer = environment.getProperty(ISSUER)
        val readOnly = environment.getProperty(READ_ONLY, Boolean::class.java, false)
        check(AuthMode.of(issuer) == AuthMode.OIDC || readOnly) {
            "AUTH_ISSUER_URI is not set and SDLC_READ_ONLY is not true: an instance with no identity provider " +
                "cannot tell who is writing, so it may only run read-only. Set AUTH_ISSUER_URI to the issuer to " +
                "trust (for example http://localhost:8081/realms/sdlc), or set SDLC_READ_ONLY=true to serve reads " +
                "to anyone and refuse every write."
        }
    }

    private companion object {
        const val ISSUER = "sdlc.auth.issuer-uri"
        const val READ_ONLY = "sdlc.read-only"

        /** The bypass's environment variable and the property it mapped to (#114), both gone. */
        val REMOVED = listOf("AUTH_DISABLED", "sdlc.auth.disabled")
    }
}
