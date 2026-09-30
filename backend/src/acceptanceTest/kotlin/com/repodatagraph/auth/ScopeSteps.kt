package com.repodatagraph.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Steps for least privilege (#116): tokens that carry some of the graph scopes and not others, and
 * the walk over every route the application maps that proves none of them was left unguarded.
 *
 * The walk is black-box on purpose. It does not ask the application which requirement it declared
 * for a route; it calls the route with a token that holds no graph scopes, and only a refusal naming
 * the scope counts. A route added tomorrow without a requirement is found the same way.
 */
class ScopeSteps(
    private val world: AuthWorld,
    private val objectMapper: ObjectMapper,
    @Qualifier("requestMappingHandlerMapping") private val mappings: RequestMappingHandlerMapping,
) {
    private var routes: List<Route> = emptyList()

    @Given("{string} holds a token with scope {word} only")
    fun holdsATokenWithOnly(
        clientId: String,
        scope: String,
    ) {
        world.hold(clientId, ClientCredentials(DevRealmKeycloak.issuer, objectMapper).accessToken(clientId, scope))
    }

    @Given("a token with no graph scopes")
    fun aTokenWithNoGraphScopes() {
        // The development realm's visitor has an account and no graph role, so no graph scope.
        world.hold(VISITOR, PkceSignIn(DevRealmKeycloak.issuer, objectMapper).accessToken(VISITOR, VISITOR))
    }

    @When("GET {word} is called")
    fun getIsCalled(path: String) {
        world.send(HttpMethod.GET, path, world.actorToken())
    }

    @Then("^the response is (\\d+) with required (\\[.*\\]) and held (\\[.*\\])$")
    fun theResponseIsWithRequiredAndHeld(
        status: Int,
        required: String,
        held: String,
    ) {
        val last = world.last()
        assertEquals(status, last.statusCode.value(), "body: ${last.body}")
        val body = world.json(last)
        assertEquals(REFUSAL, body.path("error").asText(null), "body: ${last.body}")
        assertEquals(objectMapper.readTree(required), body.path("required"), "required, in body: ${last.body}")
        assertEquals(objectMapper.readTree(held), body.path("held"), "held, in body: ${last.body}")
    }

    @When("the route-guard test enumerates all mapped routes")
    fun enumerateRoutes() {
        routes =
            mappings.handlerMethods.keys
                .flatMap { info ->
                    val methods =
                        info.methodsCondition.methods
                            .map { it.name }
                            .ifEmpty { ANY_METHOD }
                    info.patternValues.flatMap { pattern -> methods.map { Route(it, pattern) } }
                }.plus(GRAPHQL)
                .distinct()
                .sortedWith(compareBy(Route::pattern, Route::method))
        assertTrue(routes.any { it.pattern.startsWith("/api/v1/") }, "no API routes were found: $routes")
        aTokenWithNoGraphScopes()
    }

    @Then("each route has a declared scope requirement or is on the explicit public allowlist:")
    fun eachRouteIsGuarded(allowlist: DataTable) {
        val public = allowlist.asMaps().map { it.getValue("method") to it.getValue("route") }.toSet()
        val isPublic = { route: Route -> (route.method to route.pattern) in public || ("*" to route.pattern) in public }
        val token = world.actorToken()

        val unguarded =
            routes.filterNot(isPublic).mapNotNull { route ->
                val method = HttpMethod.valueOf(route.method)
                val body = if (method == HttpMethod.POST || method == HttpMethod.PUT) route.body() else null
                val response = world.send(method, route.samplePath(), token, body)
                // Not every route answers JSON (the error page, the YAML description), so a body that is not JSON
                // is simply not a refusal.
                val refusal = runCatching { world.json(response) }.getOrNull()
                val refused =
                    response.statusCode.value() == 403 &&
                        refusal?.path("error")?.asText(null) == REFUSAL &&
                        refusal.path("required").size() > 0
                if (refused) null else "${route.method} ${route.pattern} answered ${response.statusCode.value()}: ${response.body}"
            }

        assertEquals(emptyList<String>(), unguarded, "routes with no scope requirement and not on the public allowlist")
    }

    /** One method and one mapped pattern, as the handler mapping declares it. */
    private data class Route(
        val method: String,
        val pattern: String,
    ) {
        /** The pattern with every variable filled in, so it can be requested. */
        fun samplePath(): String = pattern.replace(VARIABLE, "sample")

        /** Something to send where a body is expected, so a refusal is not a complaint about the body. */
        fun body(): Any = if (pattern == GRAPHQL.pattern) mapOf("query" to "{ repositories { id } }") else emptyMap<String, Any>()
    }

    private companion object {
        const val REFUSAL = "insufficient scope"
        const val VISITOR = "visitor"

        /** What a mapping that names no method answers; the walk tries each of them. */
        val ANY_METHOD = listOf("GET", "POST", "PUT", "DELETE")

        /** Served by a router function rather than an annotated handler, so it is added by hand. */
        val GRAPHQL = Route("POST", "/graphql")

        val VARIABLE = Regex("\\{\\*?[^}]+}")
    }
}
