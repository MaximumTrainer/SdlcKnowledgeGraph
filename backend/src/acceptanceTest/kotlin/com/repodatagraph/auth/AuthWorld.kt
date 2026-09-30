package com.repodatagraph.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.spring.ScenarioScope
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

/**
 * Per-scenario state the authentication suite's step classes share: the tokens each principal holds,
 * which of them the scenario is speaking as, and the last response, so one class can make a request
 * and another can assert on it without redefining the same step.
 */
@Component
@ScenarioScope
class AuthWorld(
    private val restTemplate: TestRestTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val tokens = linkedMapOf<String, String>()

    /**
     * Whoever the last "holds a token" or "has signed in" step was about: the "it" of a scenario.
     * Settable from outside because Spring opens this class to proxy it for the scenario scope, and
     * an open property cannot have a private setter.
     */
    var actor: String? = null

    var response: ResponseEntity<String>? = null

    fun hold(
        principal: String,
        token: String,
    ) {
        tokens[principal] = token
        actor = principal
    }

    fun tokenOf(principal: String): String? = tokens[principal]

    fun actorToken(): String = checkNotNull(actor?.let(tokens::get)) { "No one holds a token yet" }

    fun send(
        method: HttpMethod,
        path: String,
        bearer: String?,
        body: Any? = null,
    ): ResponseEntity<String> {
        val headers = HttpHeaders()
        if (body != null) headers.contentType = MediaType.APPLICATION_JSON
        bearer?.let { headers.setBearerAuth(it) }
        return restTemplate.exchange(path, method, HttpEntity(body, headers), String::class.java).also { response = it }
    }

    fun last(): ResponseEntity<String> = checkNotNull(response) { "No request has been made yet" }

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body ?: "null")
}
