package com.repodatagraph.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

/**
 * The two ways an instance could come up with a gate that is not what its operator meant (#114, FR-5):
 * the development bypass in production, and authentication on with nobody to trust.
 */
class AuthGuardTest {
    private val guard = AuthGuard()
    private val application = SpringApplication()

    private fun environment(
        vararg profiles: String,
        disabled: String? = null,
        issuer: String? = null,
    ) = MockEnvironment().apply {
        setActiveProfiles(*profiles)
        disabled?.let { setProperty("sdlc.auth.disabled", it) }
        issuer?.let { setProperty("sdlc.auth.issuer-uri", it) }
    }

    @Test
    fun `refuses the bypass under the prod profile, naming the flag`() {
        val error =
            assertThrows<IllegalStateException> {
                guard.postProcessEnvironment(environment("prod", disabled = "true"), application)
            }

        assertTrue(error.message!!.contains("AUTH_DISABLED"), error.message)
    }

    @Test
    fun `refuses the bypass when prod is one of several profiles`() {
        assertThrows<IllegalStateException> {
            guard.postProcessEnvironment(environment("docker", "prod", disabled = "true"), application)
        }
    }

    @Test
    fun `allows the bypass outside prod`() {
        assertDoesNotThrow { guard.postProcessEnvironment(environment("docker", disabled = "true"), application) }
    }

    @Test
    fun `refuses authentication on with no issuer to trust, naming the setting`() {
        val error =
            assertThrows<IllegalStateException> {
                guard.postProcessEnvironment(environment("docker", disabled = "false", issuer = " "), application)
            }

        assertTrue(error.message!!.contains("AUTH_ISSUER_URI"), error.message)
    }

    @Test
    fun `treats an unset flag as authentication on`() {
        assertThrows<IllegalStateException> { guard.postProcessEnvironment(environment("docker"), application) }
    }

    @Test
    fun `accepts authentication on with an issuer, in prod too`() {
        assertDoesNotThrow {
            guard.postProcessEnvironment(
                environment("prod", disabled = "false", issuer = "https://id.example.test/realms/sdlc"),
                application,
            )
        }
    }
}
