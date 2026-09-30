package com.repodatagraph.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

/**
 * An instance either knows who is calling or accepts no writes (#118). The development bypass is gone
 * and is refused by name wherever it is still set, and an instance with no identity provider starts
 * only read-only - the anonymous read-only mode - so an unauthenticated writable instance is not
 * something an operator can reach by omission (#48, FR6).
 */
class AuthGuardTest {
    private val guard = AuthGuard()
    private val application = SpringApplication()

    private fun environment(
        vararg profiles: String,
        issuer: String? = null,
        readOnly: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) = MockEnvironment().apply {
        setActiveProfiles(*profiles)
        issuer?.let { setProperty("sdlc.auth.issuer-uri", it) }
        readOnly?.let { setProperty("sdlc.read-only", it) }
        extra.forEach { (name, value) -> setProperty(name, value) }
    }

    private fun refusal(environment: MockEnvironment): String =
        assertThrows<IllegalStateException> { guard.postProcessEnvironment(environment, application) }.message!!

    @Test
    fun `refuses AUTH_DISABLED as a setting that no longer exists`() {
        val message =
            refusal(environment("docker", issuer = "https://id.example.test/realms/sdlc", extra = mapOf("AUTH_DISABLED" to "true")))

        assertTrue(message.contains("AUTH_DISABLED") && message.contains("not a setting"), message)
    }

    @Test
    fun `refuses it whatever its value, since it switches nothing any more`() {
        val message =
            refusal(environment("docker", issuer = "https://id.example.test/realms/sdlc", extra = mapOf("AUTH_DISABLED" to "false")))

        assertTrue(message.contains("AUTH_DISABLED"), message)
    }

    @Test
    fun `refuses the property it used to set, naming the environment variable`() {
        val message =
            refusal(environment("docker", issuer = "https://id.example.test/realms/sdlc", extra = mapOf("sdlc.auth.disabled" to "true")))

        assertTrue(message.contains("AUTH_DISABLED") && message.contains("not a setting"), message)
    }

    @Test
    fun `refuses no identity provider on a writable instance, naming both settings`() {
        val message = refusal(environment("docker", issuer = " ", readOnly = "false"))

        assertTrue(message.contains("AUTH_ISSUER_URI"), message)
        assertTrue(message.contains("SDLC_READ_ONLY"), message)
    }

    @Test
    fun `treats unset settings as no identity provider on a writable instance`() {
        val message = refusal(environment("docker"))

        assertTrue(message.contains("AUTH_ISSUER_URI") && message.contains("SDLC_READ_ONLY"), message)
    }

    @Test
    fun `accepts no identity provider on a read-only instance`() {
        assertDoesNotThrow { guard.postProcessEnvironment(environment("docker", issuer = "", readOnly = "true"), application) }
    }

    @Test
    fun `accepts an identity provider on a writable instance, in prod too`() {
        assertDoesNotThrow {
            guard.postProcessEnvironment(
                environment("prod", issuer = "https://id.example.test/realms/sdlc", readOnly = "false"),
                application,
            )
        }
    }
}
