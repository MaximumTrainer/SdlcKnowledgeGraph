package com.repodatagraph.auth

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Gets a token the way a connector or an agent does (#115): the OAuth 2 client-credentials grant, a
 * confidential client authenticating with its own secret and no user anywhere in the exchange.
 *
 * Unless told otherwise it asks for every graph scope the client may hold (#116), so a scenario about
 * who a service is gets a token that may do what the scenario needs; a scenario about scopes names
 * the ones it wants.
 */
class ClientCredentials(
    private val issuer: String,
    private val objectMapper: ObjectMapper,
) {
    private val http = HttpClient.newHttpClient()

    fun accessToken(
        clientId: String,
        scope: String? = OPTIONAL_SCOPES[clientId],
    ): String {
        val secret = SECRETS[clientId] ?: error("the development realm has no confidential client '$clientId'")
        val body =
            listOf("grant_type" to "client_credentials", "client_id" to clientId, "client_secret" to secret)
                .plus(listOfNotNull(scope?.let { "scope" to it }))
                .joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val response =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("$issuer/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return objectMapper.readTree(response.body()).path("access_token").asText(null)
            ?: error("the token endpoint issued $clientId no access token (${response.statusCode()}): ${response.body()}")
    }

    companion object {
        /**
         * The secrets the committed development realm gives its confidential clients. Development-only
         * values that exist nowhere but in that realm, which no deployment imports.
         */
        private val SECRETS =
            mapOf(
                "github-connector" to "github-connector-dev-only",
                "triage-agent" to "triage-agent-dev-only",
                "rogue-agent" to "rogue-agent-dev-only",
            )

        /**
         * The graph scopes a client is issued only when it asks for them. Keycloak refuses a request
         * for a scope the client was never given, so this names only the ones it has.
         */
        private val OPTIONAL_SCOPES = mapOf("triage-agent" to "graph:write")
    }
}
