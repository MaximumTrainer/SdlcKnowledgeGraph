package com.repodatagraph.auth

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.time.Duration

/**
 * A Keycloak loaded with the development realm the compose stack's `auth` profile uses
 * (`keycloak/sdlc-realm.json`), so the acceptance suite signs in against the same clients and users
 * a developer does.
 *
 * A plain [GenericContainer] rather than a Keycloak-specific module: the suite needs an issuer and a
 * login form, nothing from the admin API. Started once, on first use, and left to Testcontainers'
 * reaper, like the Neo4j container.
 */
object DevRealmKeycloak {
    /** The same image compose.yaml pins, so the suite and the stack cannot drift apart. */
    const val IMAGE = "quay.io/keycloak/keycloak:26.0"
    const val REALM = "sdlc"
    const val UI_CLIENT = "sdlc-ui"

    /** A redirect the UI client allows. Nothing listens there: the flow stops at the redirect. */
    const val UI_REDIRECT = "http://localhost:5173/auth/callback"

    private const val PORT = 8080

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(IMAGE))
            .withCommand("start-dev", "--import-realm")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("keycloak/sdlc-realm.json"),
                "/opt/keycloak/data/import/sdlc-realm.json",
            ).withExposedPorts(PORT)
            .waitingFor(
                Wait
                    .forHttp("/realms/$REALM/.well-known/openid-configuration")
                    .forPort(PORT)
                    .withStartupTimeout(Duration.ofMinutes(3)),
            ).also { it.start() }
    }

    /** The issuer every token this realm signs names, as the test process reaches it. */
    val issuer: String
        get() = "http://${container.host}:${container.getMappedPort(PORT)}/realms/$REALM"
}
