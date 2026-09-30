package com.repodatagraph.adapter.`in`.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.config.AuthProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain

/**
 * The API as an OAuth 2 resource server (#114, ADR-0005): every request under `/api` and `/graphql`
 * needs a bearer JWT signed by the configured issuer, and anything else is refused with a 401 the web
 * interface answers by restarting the login.
 *
 * There are no scopes yet. Any valid token may do what any caller could before; deciding *what* a
 * principal may do is AUTH-3. What this establishes is *who* the caller is, which is what provenance
 * records ([SecurityContextPrincipal]).
 *
 * A few paths stay public, each for a reason that does not depend on who is asking:
 * - the probes, `/actuator/info` and `/actuator/prometheus`: the platform and the scraper inside the
 *   deployment hold no user token (docs/DEPLOYMENT.md D1, D2, D9);
 * - the ingest endpoints, which keep their own bearer token (D6) until connectors and pipelines are
 *   principals of their own (AUTH-2) - their token is never decoded as a JWT;
 * - the webhook receivers, which verify the sender's signature instead (ADR-0005);
 * - the ontology document and the API documentation, which describe the model, not the data in it.
 *
 * Stateless and without CSRF protection: the token travels in a header, never in a cookie, so there is
 * no ambient credential for a cross-site request to ride on.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties::class)
class SecurityConfig(
    private val auth: AuthProperties,
    private val objectMapper: ObjectMapper,
) {
    @Bean
    fun apiSecurity(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }

        if (auth.disabled) {
            // The development bypass: nothing is checked, and every write is recorded as anonymous.
            http.authorizeHttpRequests { it.anyRequest().permitAll() }
            return http.build()
        }

        val refusal = unauthenticated()
        http
            .authorizeHttpRequests {
                // Answered for anyone, whatever the method. /error is where the servlet container
                // forwards a failure of a request that was already let in.
                it
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus",
                        "/api-docs",
                        "/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/error",
                    ).permitAll()
                    // The model, not the data in it (ADR-0005).
                    .requestMatchers(HttpMethod.GET, "/api/v1/ontology", "/api/v1/ontology/**")
                    .permitAll()
                    // Guarded by a credential of their own rather than a user's token.
                    .requestMatchers(HttpMethod.POST, "$INGEST_PREFIX**", "/api/v1/webhooks/*")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer {
                it
                    .jwt(Customizer.withDefaults())
                    .bearerTokenResolver(bearerTokenResolver())
                    .authenticationEntryPoint(refusal)
            }.exceptionHandling { it.authenticationEntryPoint(refusal) }
        return http.build()
    }

    /**
     * Trusts tokens the configured issuer signed, and only while they name that issuer and are in date.
     * The keys are fetched on first use rather than at startup, so the API can start before its
     * identity provider does.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sdlc.auth", name = ["disabled"], havingValue = "false", matchIfMissing = true)
    fun jwtDecoder(): JwtDecoder {
        val issuer = checkNotNull(auth.issuerUri?.takeIf { it.isNotBlank() }) { "AUTH_ISSUER_URI must be set" }
        val decoder =
            auth.jwkSetUri
                ?.takeIf { it.isNotBlank() }
                ?.let { NimbusJwtDecoder.withJwkSetUri(it).build() }
                ?: NimbusJwtDecoder.withIssuerLocation(issuer).build()
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer))
        return decoder
    }

    /**
     * Reads the bearer token everywhere except where a bearer token means something else: the ingest
     * endpoints' own shared token would otherwise be decoded as a JWT and refused.
     */
    private fun bearerTokenResolver(): BearerTokenResolver {
        val standard = DefaultBearerTokenResolver()
        return BearerTokenResolver { request: HttpServletRequest ->
            val path = request.requestURI.removePrefix(request.contextPath)
            if (path.startsWith(INGEST_PREFIX)) null else standard.resolve(request)
        }
    }

    /**
     * The 401: the `error` field every refusal in this API has, and the `WWW-Authenticate` challenge
     * RFC 6750 asks for. Why a token failed stays in the server; telling a caller whether its forged
     * signature or its expiry was the problem only helps it forge the next one.
     */
    private fun unauthenticated(): AuthenticationEntryPoint =
        AuthenticationEntryPoint { _, response, _ ->
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.setHeader("WWW-Authenticate", "Bearer")
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            objectMapper.writeValue(response.outputStream, mapOf("error" to REFUSAL))
        }

    companion object {
        const val REFUSAL = "authentication required"

        private const val INGEST_PREFIX = "/api/v1/ingest/"
    }
}
