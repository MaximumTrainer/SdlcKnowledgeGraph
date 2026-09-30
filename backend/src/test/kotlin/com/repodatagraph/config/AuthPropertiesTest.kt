package com.repodatagraph.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** How an instance knows who is calling is decided by one setting, the issuer (#118). */
class AuthPropertiesTest {
    @Test
    fun `an issuer means tokens from it`() {
        assertEquals(AuthMode.OIDC, AuthProperties(issuerUri = "https://id.example.test/realms/sdlc").mode)
    }

    @Test
    fun `no issuer, or a blank one, means the anonymous read-only mode`() {
        assertEquals(AuthMode.ANONYMOUS_READ_ONLY, AuthProperties().mode)
        assertEquals(AuthMode.ANONYMOUS_READ_ONLY, AuthProperties(issuerUri = "  ").mode)
    }

    @Test
    fun `each mode has the name the deployment info reports`() {
        assertEquals("oidc", AuthMode.OIDC.wireName)
        assertEquals("anonymous-read-only", AuthMode.ANONYMOUS_READ_ONLY.wireName)
    }
}
