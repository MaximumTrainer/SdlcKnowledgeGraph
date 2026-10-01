package com.repodatagraph.support

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.client.RestTemplateCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Instant
import java.util.Base64

/**
 * A signed-in caller for the suites that exercise the graph rather than the login (#118).
 *
 * The API always knows who is calling: with an issuer every request needs its token, and without one
 * nothing can be written. So a suite that writes needs a principal, and this gives it one without an
 * identity provider and without a switch in the application. The real security chain runs unchanged -
 * the gate, the registry check, the scope checks - and only the decoder is replaced, by one that
 * accepts exactly [TOKEN] as a user, [SUBJECT], holding every graph scope, and [READER_TOKEN] as a
 * user, [READER_SUBJECT], who may only read (#28: a refusal is only proved by someone refused). Any
 * other token is refused as a forged one would be.
 *
 * A `TestRestTemplate` in a context that imports this sends [TOKEN] on every request that does not
 * carry an Authorization header of its own, except to the ingest endpoints, whose bearer token is a
 * different credential that tests supply themselves. A suite with another client adds the header
 * itself ([AUTHORIZATION]).
 *
 * The context also needs an issuer, since an API with none starts only read-only: [ISSUER], with
 * [JWK_SET_URI] so the application's own decoder fetches nothing at startup. Neither is ever fetched,
 * because this decoder is the only one ever asked.
 *
 * That the real decoder trusts only the issuer's keys is the acceptance suite's auth features' job,
 * against a real Keycloak.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestPrincipalConfig {
    @Bean
    @Primary
    fun testPrincipalDecoder(): JwtDecoder =
        JwtDecoder { token ->
            val claims =
                when {
                    token == TOKEN -> mapOf("sub" to SUBJECT, "scope" to SCOPES)
                    token == READER_TOKEN -> mapOf("sub" to READER_SUBJECT, "scope" to READER_SCOPES)
                    token.startsWith(CLAIMS_PREFIX) -> claimsOf(token)
                    else -> throw BadJwtException("not the test principal's token")
                }
            val subject = claims["sub"].toString()
            Jwt
                .withTokenValue(token)
                .header("alg", "none")
                .claims { it.putAll(claims) }
                .subject(subject)
                .issuer(ISSUER)
                .claim("preferred_username", subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(TOKEN_LIFETIME_SECONDS))
                .build()
        }

    @Bean
    fun testPrincipalRestTemplate(): RestTemplateCustomizer =
        RestTemplateCustomizer { template ->
            template.interceptors.add(
                ClientHttpRequestInterceptor { request, body, execution ->
                    val ingest = request.uri.path.startsWith(INGEST_PREFIX)
                    if (!ingest && !request.headers.containsKey(HttpHeaders.AUTHORIZATION)) request.headers.setBearerAuth(TOKEN)
                    execution.execute(request, body)
                },
            )
        }

    companion object {
        /** The issuer a context that imports this names. Never fetched. */
        const val ISSUER = "https://issuer.test.invalid/realms/sdlc"

        /** Its key set, named so the application's decoder is built without reading [ISSUER]'s discovery. */
        const val JWK_SET_URI = "$ISSUER/protocol/openid-connect/certs"

        /** The one token the test decoder accepts. */
        const val TOKEN = "test-principal"

        /** Who every write in these suites is recorded as. */
        const val SUBJECT = "test-principal"

        /** The header value for a client that is not a `TestRestTemplate`. */
        const val AUTHORIZATION = "Bearer $TOKEN"

        /** A second user's token, holding graph:read alone. */
        const val READER_TOKEN = "test-reader"

        /** Who [READER_TOKEN] is. */
        const val READER_SUBJECT = "test-reader"

        /** The header value that reads as [READER_SUBJECT]. */
        const val READER_AUTHORIZATION = "Bearer $READER_TOKEN"

        private const val READER_SCOPES = "graph:read"

        private const val CLAIMS_PREFIX = "test-claims."
        private val MAPPER = ObjectMapper()

        /**
         * A token the test decoder accepts carrying exactly [claims] - `sub`, `scope`, and whatever
         * else a test needs, such as the roles and groups the authorisation policy reads (#30). The
         * claims travel in the token itself, so a test can mint one per scenario.
         */
        fun tokenWith(claims: Map<String, Any?>): String =
            CLAIMS_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(claims))

        /** The header value for [tokenWith]. */
        fun authorizationWith(claims: Map<String, Any?>): String = "Bearer ${tokenWith(claims)}"

        @Suppress("UNCHECKED_CAST")
        private fun claimsOf(token: String): Map<String, Any?> {
            val claims =
                runCatching { MAPPER.readValue(Base64.getUrlDecoder().decode(token.removePrefix(CLAIMS_PREFIX)), Map::class.java) }
                    .getOrNull() as? Map<String, Any?>
            return claims?.takeIf { it["sub"] != null } ?: throw BadJwtException("a test token's claims name no subject")
        }

        /**
         * Every graph scope - reading, writing and administering the graph's lifecycle (#33) - and the
         * scope of every source sources.yaml declares (#116, #117).
         */
        private const val SCOPES =
            "graph:read graph:write graph:admin graph:write:github graph:write:github-actions graph:write:servicenow " +
                "graph:write:aws graph:write:dogfood-seed graph:write:sdlc-knowledge-graph"

        private const val INGEST_PREFIX = "/api/v1/ingest/"
        private const val TOKEN_LIFETIME_SECONDS = 3600L
    }
}
