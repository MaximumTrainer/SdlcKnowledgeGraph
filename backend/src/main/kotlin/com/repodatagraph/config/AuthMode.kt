package com.repodatagraph.config

/**
 * How an instance knows who is calling (#118). One setting decides it, the issuer: with one, every
 * request under `/api` and `/graphql` needs a bearer token it signed; with none, the instance runs the
 * anonymous read-only mode, answering reads for anyone and refusing every write ([AuthGuard] lets it
 * start only read-only). There is no mode that accepts writes from nobody.
 *
 * @param wireName how `/actuator/info` reports it (`deployment.authentication`)
 */
enum class AuthMode(
    val wireName: String,
) {
    OIDC("oidc"),
    ANONYMOUS_READ_ONLY("anonymous-read-only"),
    ;

    companion object {
        fun of(issuerUri: String?): AuthMode = if (issuerUri.isNullOrBlank()) ANONYMOUS_READ_ONLY else OIDC
    }
}
