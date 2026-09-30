package com.repodatagraph.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `sdlc.auth`: who the API trusts to say who a caller is (#114, ADR-0005).
 *
 * @param disabled AUTH_DISABLED. The development bypass: every request is let through and recorded
 *   as anonymous. Refused under the prod profile ([AuthGuard]) and announced on every start.
 * @param issuerUri AUTH_ISSUER_URI. The issuer every accepted token must name, and where its keys are
 *   discovered unless [jwkSetUri] says otherwise. Required unless [disabled].
 * @param jwkSetUri AUTH_JWK_SET_URI. Where to fetch the issuer's signing keys when the API reaches the
 *   issuer at a different address from the one it signs as - inside compose, Keycloak is
 *   `keycloak:8080` to the API but `localhost:8081` to the browser that signed in.
 */
@ConfigurationProperties(prefix = "sdlc.auth")
data class AuthProperties(
    val disabled: Boolean = false,
    val issuerUri: String? = null,
    val jwkSetUri: String? = null,
)
