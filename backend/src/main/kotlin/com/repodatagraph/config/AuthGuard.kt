package com.repodatagraph.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Profiles

/**
 * Refuses to start an API whose gate is not what its operator could have meant (#114, FR-5).
 *
 * Two cases. The development bypass under the `prod` profile: AUTH_DISABLED exists so a developer can
 * work without Keycloak, and a production instance that lets everyone in because a flag leaked into
 * its environment must fail its deploy rather than serve. And authentication on with no issuer: an
 * API that cannot validate any token would refuse every request, and saying why at startup beats
 * saying 401 to everyone.
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
        val disabled = environment.getProperty(DISABLED, Boolean::class.java, false)
        if (disabled) {
            check(!environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
                "AUTH_DISABLED=true is refused under the $PRODUCTION_PROFILE profile: a production instance " +
                    "must authenticate its callers. Unset AUTH_DISABLED and set AUTH_ISSUER_URI."
            }
            return
        }
        check(!environment.getProperty(ISSUER).isNullOrBlank()) {
            "AUTH_ISSUER_URI must be set: authentication is on, and the API needs an issuer to trust " +
                "(for example http://localhost:8081/realms/sdlc). For local work without an identity " +
                "provider, set AUTH_DISABLED=true."
        }
    }

    private companion object {
        const val DISABLED = "sdlc.auth.disabled"
        const val ISSUER = "sdlc.auth.issuer-uri"
        const val PRODUCTION_PROFILE = "prod"
    }
}
