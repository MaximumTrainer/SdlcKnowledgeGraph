package com.repodatagraph.adapter.`in`.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.adapter.out.policy.WasmPolicyDecisionPoint
import com.repodatagraph.config.AuthMode
import com.repodatagraph.config.AuthProperties
import com.repodatagraph.config.PolicyProperties
import com.repodatagraph.config.ReadsOverPost
import com.repodatagraph.domain.port.`in`.ResourceOwners
import com.repodatagraph.domain.port.`in`.ServicePrincipalUseCase
import com.repodatagraph.domain.port.out.PolicyDecisionPoint
import com.repodatagraph.observability.PolicyMetrics
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

/**
 * The API as an OAuth 2 resource server (#114, ADR-0005): every request under `/api` and `/graphql`
 * needs a bearer JWT signed by the configured issuer, and anything else is refused with a 401 the web
 * interface answers by restarting the login.
 *
 * What a principal may do is decided by the authorisation policy (#30, #95, ADR-0020), which
 * [ScopeGate] asks about every request: by default the graph scopes on its token (#116), `graph:read`
 * to read and `graph:write` to change the graph, declared once per route family in [ScopePolicy]. Who the caller is, which is what provenance records ([SecurityContextPrincipal]), is a
 * user, or a connector or agent holding a client-credentials token, which [ServicePrincipalGate] lets
 * in only once its client is a registered service principal (#115).
 *
 * A few paths stay public, each for a reason that does not depend on who is asking. They are
 * [ScopePolicy.PUBLIC], the same list the scope check exempts:
 * - the probes, `/actuator/info` and `/actuator/prometheus`: the platform and the scraper inside the
 *   deployment hold no user token (docs/DEPLOYMENT.md D1, D2, D9);
 * - the ingest endpoints, which keep their own bearer token (D6) - their token is never decoded as a
 *   JWT. Connectors and pipelines can be principals of their own since #115, but the ingest callers
 *   (a CI pipeline, the seed job) are not yet moved onto client credentials;
 * - the webhook receivers, which verify the sender's signature instead (ADR-0005);
 * - the ontology document and the API documentation, which describe the model, not the data in it.
 *
 * With no issuer configured the instance runs the anonymous read-only mode instead (#118, [AuthMode]):
 * there is nobody to issue a token, so reads are answered for anyone and no token is decoded at all.
 * [AuthGuard][com.repodatagraph.config.AuthGuard] lets that start only on a read-only instance, so
 * [ReadOnlyGuard][com.repodatagraph.config.ReadOnlyGuard], which runs before this chain, has already
 * refused every write but the ingest endpoints' own; the chain refuses any other write again, so a
 * write is never let in by the absence of a login alone.
 *
 * Stateless and without CSRF protection: the token travels in a header, never in a cookie, so there is
 * no ambient credential for a cross-site request to ride on.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties::class, PolicyProperties::class)
class SecurityConfig(
    private val auth: AuthProperties,
    private val objectMapper: ObjectMapper,
    private val servicePrincipals: ServicePrincipalUseCase,
    private val policyProperties: PolicyProperties,
    private val policy: ObjectProvider<PolicyDecisionPoint>,
    private val owners: ObjectProvider<ResourceOwners>,
    private val meters: ObjectProvider<MeterRegistry>,
) {
    /**
     * The policy gate (#30, #95). Where the application has no policy bean - a test slice that builds
     * only this configuration - it gets the bundle the API was built with, the same one.
     */
    private fun policyGate(): ScopeGate =
        ScopeGate(
            objectMapper = objectMapper,
            policy = policy.getIfAvailable { WasmPolicyDecisionPoint.classpathDefault() },
            properties = policyProperties,
            owners = owners.getIfAvailable(),
            metrics = meters.getIfAvailable()?.let(::PolicyMetrics),
            mode = auth.mode,
        )

    // The spread copies a handful of patterns once, when the chain is built.
    @Suppress("SpreadOperator")
    @Bean
    fun apiSecurity(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }

        if (auth.mode == AuthMode.ANONYMOUS_READ_ONLY) return anonymousReadOnly(http)

        val refusal = unauthenticated()
        http
            .authorizeHttpRequests {
                // Answered for anyone, each for the reason ScopePolicy gives: the probes, the API's
                // description, the error page, the ontology, and the endpoints with a credential of
                // their own (the ingest token, a webhook's signature).
                ScopePolicy.PUBLIC.forEach { family ->
                    val patterns = family.patterns.toTypedArray()
                    if (family.methods.isEmpty()) {
                        it.requestMatchers(*patterns).permitAll()
                    } else {
                        family.methods.forEach { method -> it.requestMatchers(HttpMethod.valueOf(method), *patterns).permitAll() }
                    }
                }
                it.anyRequest().authenticated()
            }.oauth2ResourceServer {
                it
                    .jwt(Customizer.withDefaults())
                    .bearerTokenResolver(bearerTokenResolver())
                    .authenticationEntryPoint(refusal)
            }.exceptionHandling { it.authenticationEntryPoint(refusal) }
            // A client's token is good only once its client is a registered service principal (#115).
            .addFilterAfter(ServicePrincipalGate(servicePrincipals, objectMapper), BearerTokenAuthenticationFilter::class.java)
            // And then only for what the policy allows (#116, #30, #95). After the registry gate, so an
            // unregistered client is told that rather than which scope it lacks.
            .addFilterAfter(policyGate(), ServicePrincipalGate::class.java)
        return http.build()
    }

    /**
     * The anonymous read-only mode's chain: reads, GraphQL queries (ReadOnlyGuard refuses a mutation
     * before it gets here), the queries sent as a POST ([ReadsOverPost]) and the public families answer
     * for anyone; anything else is refused. The spread copies a handful of patterns once, when the
     * chain is built.
     */
    @Suppress("SpreadOperator")
    private fun anonymousReadOnly(http: HttpSecurity): SecurityFilterChain {
        val refusal = unauthenticated()
        http
            .authorizeHttpRequests {
                ScopePolicy.PUBLIC.forEach { family ->
                    val patterns = family.patterns.toTypedArray()
                    if (family.methods.isEmpty()) {
                        it.requestMatchers(*patterns).permitAll()
                    } else {
                        family.methods.forEach { method -> it.requestMatchers(HttpMethod.valueOf(method), *patterns).permitAll() }
                    }
                }
                ANONYMOUS_READS.forEach { method -> it.requestMatchers(HttpMethod.valueOf(method)).permitAll() }
                it.requestMatchers(HttpMethod.POST, GRAPHQL_PATH).permitAll()
                // Queries sent as a POST (#87), which ReadOnlyGuard has already let through as reads.
                it.requestMatchers(HttpMethod.POST, *ReadsOverPost.PATHS.toTypedArray()).permitAll()
                it.anyRequest().denyAll()
            }.exceptionHandling { it.authenticationEntryPoint(refusal) }
            // The anonymous reader is put to the policy too, which answers its reads (#30, #95).
            .addFilterAfter(policyGate(), AnonymousAuthenticationFilter::class.java)
        return http.build()
    }

    /**
     * Trusts tokens the configured issuer signed, and only while they name that issuer and are in date.
     * With a key set URI the keys are fetched on first use rather than at startup, so the API can start
     * before its identity provider does; from the issuer alone, its discovery document is read at
     * startup. Only with an issuer: in the anonymous read-only mode there are no keys to
     * trust, and no decoder to trust them with.
     */
    @Bean
    @Conditional(IssuerConfigured::class)
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
        private const val GRAPHQL_PATH = "/graphql"
        private val ANONYMOUS_READS = listOf("GET", "HEAD", "OPTIONS")
    }

    /** Matches when an issuer is configured: the [AuthMode.OIDC] mode. */
    class IssuerConfigured : Condition {
        override fun matches(
            context: ConditionContext,
            metadata: AnnotatedTypeMetadata,
        ): Boolean = AuthMode.of(context.environment.getProperty("sdlc.auth.issuer-uri")) == AuthMode.OIDC
    }
}
