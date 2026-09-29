package com.repodatagraph.config

import com.repodatagraph.domain.ontology.OntologyRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.boot.info.BuildProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * The `deployment` block of `/actuator/info`: what this instance is running and how (#48, D2).
 *
 * The pipeline that deployed an image asks this whether the commit it meant to deploy is the one
 * serving (D3), and a client can see that an instance is read-only before it tries to write. So
 * every field is always present, and a value the build did not supply reads `unknown` rather than
 * being left out: a missing field and an unstamped image should not look the same.
 *
 * `commit` comes from `SDLC_COMMIT`, which the image build sets from the commit it was built from.
 */
@Component
class DeploymentInfoContributor(
    @Value("\${sdlc.read-only:false}") private val readOnly: Boolean,
    @Value("\${sdlc.deployment.commit:}") private val commit: String,
    private val build: BuildProperties?,
    private val environment: Environment,
    private val registry: OntologyRegistry,
) : InfoContributor {
    override fun contribute(builder: Info.Builder) {
        builder.withDetail(
            "deployment",
            mapOf(
                "commit" to commit.trim().ifEmpty { UNKNOWN },
                "version" to (build?.version ?: UNKNOWN),
                "ontologyVersion" to registry.version,
                "profile" to environment.activeProfiles.joinToString(",").ifEmpty { "default" },
                "readOnly" to readOnly,
            ),
        )
    }

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
