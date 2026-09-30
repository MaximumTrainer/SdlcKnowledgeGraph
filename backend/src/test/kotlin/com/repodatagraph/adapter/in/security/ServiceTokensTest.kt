package com.repodatagraph.adapter.`in`.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which tokens a machine holds, and which client it is (#115).
 *
 * A token is a service's when no user took part in issuing it. Keycloak marks that by naming the
 * client's service account `service-account-<client id>`, a prefix it reserves; an issuer following
 * RFC 9068 marks it by making the subject the client id. Either way the client is `azp`, or
 * `client_id` where there is no `azp`.
 */
class ServiceTokensTest {
    @Test
    fun `a Keycloak client-credentials token is the client named in azp`() {
        val claims =
            mapOf(
                "sub" to "0b6f3c1e-6a9d-4d1c-9d8e-3f2b1a0c9e8d",
                "azp" to "triage-agent",
                "client_id" to "triage-agent",
                "preferred_username" to "service-account-triage-agent",
            )

        assertEquals("triage-agent", ServiceTokens.clientIdOf(claims))
    }

    @Test
    fun `without azp the client is client_id`() {
        val claims =
            mapOf(
                "sub" to "0b6f3c1e",
                "client_id" to "github-connector",
                "preferred_username" to "service-account-github-connector",
            )

        assertEquals("github-connector", ServiceTokens.clientIdOf(claims))
    }

    @Test
    fun `with neither, the client is what the service account is named after`() {
        val claims = mapOf("sub" to "0b6f3c1e", "preferred_username" to "service-account-github-connector")

        assertEquals("github-connector", ServiceTokens.clientIdOf(claims))
    }

    @Test
    fun `an RFC 9068 token whose subject is the client is a service's`() {
        val claims = mapOf("sub" to "github-connector", "client_id" to "github-connector")

        assertEquals("github-connector", ServiceTokens.clientIdOf(claims))
    }

    @Test
    fun `a user signed in through the web interface is not a service, though azp names its client`() {
        val claims = mapOf("sub" to "dan", "azp" to "sdlc-ui", "preferred_username" to "dan")

        assertNull(ServiceTokens.clientIdOf(claims))
    }

    @Test
    fun `a user token carrying client_id is still a user's`() {
        // RFC 9068 puts client_id in every access token, a user's included.
        val claims = mapOf("sub" to "dan", "client_id" to "sdlc-ui", "preferred_username" to "dan")

        assertNull(ServiceTokens.clientIdOf(claims))
    }

    @Test
    fun `a token with only a subject is a user's`() {
        assertNull(ServiceTokens.clientIdOf(mapOf("sub" to "dan")))
    }

    @Test
    fun `a blank client claim is passed over for the next`() {
        val claims =
            mapOf("sub" to "x", "azp" to " ", "client_id" to "triage-agent", "preferred_username" to "service-account-triage-agent")

        assertEquals("triage-agent", ServiceTokens.clientIdOf(claims))
    }
}
