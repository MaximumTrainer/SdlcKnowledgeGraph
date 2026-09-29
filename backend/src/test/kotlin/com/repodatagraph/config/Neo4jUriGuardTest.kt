package com.repodatagraph.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

class Neo4jUriGuardTest {
    private val guard = Neo4jUriGuard()
    private val application = SpringApplication()

    private fun environment(
        vararg profiles: String,
        uri: String? = null,
    ) = MockEnvironment().apply {
        setActiveProfiles(*profiles)
        if (uri != null) setProperty("NEO4J_URI", uri)
    }

    @Test
    fun `refuses the docker profile without NEO4J_URI`() {
        val error = assertThrows<IllegalStateException> { guard.postProcessEnvironment(environment("docker"), application) }

        assertTrue(error.message!!.contains("NEO4J_URI must be set"), error.message)
    }

    @Test
    fun `refuses the docker profile with a blank NEO4J_URI`() {
        assertThrows<IllegalStateException> { guard.postProcessEnvironment(environment("docker", uri = "  "), application) }
    }

    @Test
    fun `accepts the docker profile with NEO4J_URI set`() {
        assertDoesNotThrow { guard.postProcessEnvironment(environment("docker", uri = "bolt://neo4j:7687"), application) }
    }

    @Test
    fun `leaves other profiles alone, which have their own connection settings`() {
        assertDoesNotThrow { guard.postProcessEnvironment(environment("test"), application) }
        assertDoesNotThrow { guard.postProcessEnvironment(environment(), application) }
    }
}
