package com.repodatagraph.config

import com.repodatagraph.domain.ontology.OntologyRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.info.BuildProperties
import org.springframework.mock.env.MockEnvironment
import java.util.Properties

class DeploymentInfoContributorTest {
    private val registry = OntologyRegistry(version = "1.4.0", nodeTypes = emptyList(), edgeTypes = emptyList())
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `reports the commit, version, ontology version, profile and posture`() {
        val deployment =
            contribute(
                readOnly = true,
                commit = sha,
                build = build(version = "0.3.0"),
                environment = MockEnvironment().apply { setActiveProfiles("docker") },
            )

        assertEquals(
            mapOf(
                "commit" to sha,
                "version" to "0.3.0",
                "ontologyVersion" to "1.4.0",
                "profile" to "docker",
                "readOnly" to true,
                "authentication" to "oidc",
            ),
            deployment,
        )
    }

    @Test
    fun `says unknown rather than blank when the image was built without a commit`() {
        assertEquals("unknown", contribute(commit = "  ")["commit"])
    }

    @Test
    fun `says unknown when the jar carries no build info`() {
        assertEquals("unknown", contribute(build = null)["version"])
    }

    @Test
    fun `names the default profile when none is active`() {
        assertEquals("default", contribute(environment = MockEnvironment())["profile"])
    }

    @Test
    fun `joins several active profiles in order`() {
        val environment = MockEnvironment().apply { setActiveProfiles("docker", "dogfood") }

        assertEquals("docker,dogfood", contribute(environment = environment)["profile"])
    }

    @Test
    fun `says how it knows who is calling`() {
        assertEquals("anonymous-read-only", contribute(auth = AuthProperties(issuerUri = ""))["authentication"])
    }

    private fun contribute(
        readOnly: Boolean = false,
        auth: AuthProperties = AuthProperties(issuerUri = "https://id.example.test/realms/sdlc"),
        commit: String = sha,
        build: BuildProperties? = build(version = "0.0.1"),
        environment: MockEnvironment = MockEnvironment(),
    ): Map<*, *> {
        val info = Info.Builder()
        DeploymentInfoContributor(readOnly, commit, build, environment, registry, auth).contribute(info)
        return info.build().details["deployment"] as Map<*, *>
    }

    private fun build(version: String) = BuildProperties(Properties().apply { setProperty("version", version) })
}
