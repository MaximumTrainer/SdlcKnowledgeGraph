package com.repodatagraph.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `sdlc.policy`: the authorisation policy the API enforces (#30, #95, ADR-0020).
 *
 * @param bundle SDLC_POLICY_BUNDLE. A bundle compiled with `opa build --target wasm` to enforce instead
 *   of the one the API was built with; unset or empty for that one.
 * @param rolesClaim the token claim that carries the caller's roles (#30 FR3). A token without it is
 *   judged by its scopes alone, as every token was before roles existed.
 * @param groupsClaim OIDC_GROUPS_CLAIM. The token claim that names the caller's groups, read as the
 *   keys of the teams they belong to (#30 FR7).
 */
@ConfigurationProperties(prefix = "sdlc.policy")
data class PolicyProperties(
    val bundle: String? = null,
    val rolesClaim: String = DEFAULT_ROLES_CLAIM,
    val groupsClaim: String = DEFAULT_GROUPS_CLAIM,
) {
    companion object {
        const val DEFAULT_ROLES_CLAIM = "sdlc_roles"
        const val DEFAULT_GROUPS_CLAIM = "groups"
    }
}
