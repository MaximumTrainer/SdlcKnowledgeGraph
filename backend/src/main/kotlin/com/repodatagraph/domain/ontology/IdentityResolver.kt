package com.repodatagraph.domain.ontology

import com.repodatagraph.domain.identity.GitRemoteParser
import com.repodatagraph.domain.model.NodeKey
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Derives a node's key from its own properties.
 *
 * This is the mechanism that keeps the graph coherent when several connectors describe the same
 * thing. GitHub, a cloud tagging API and a CMDB all refer to the same repository in different
 * notations; unless they all reduce to one key, the graph grows duplicates that no traversal can
 * reconcile. Keys are never random, so re-ingesting the same fact is idempotent.
 */
@Component
class IdentityResolver(
    private val gitRemoteParser: GitRemoteParser = GitRemoteParser(),
) {
    fun keyFor(
        type: String,
        props: Map<String, Any?>,
    ): NodeKey =
        when (type) {
            "Repository" -> NodeKey(type, repositoryKey(props))
            "CloudResource" -> NodeKey(type, "${required(props, "provider", type).lowercase()}:${required(props, "resourceId", type)}")
            "ConfigurationItem" -> NodeKey(type, configurationItemKey(props))
            "Pipeline" -> NodeKey(type, pipelineKey(props))
            "Artifact" -> NodeKey(type, artifactKey(props))
            "Environment" -> NodeKey(type, environmentKey(required(props, "name", type)))
            "Deployment" -> NodeKey(type, deploymentKey(props))
            "Team", "Service" -> NodeKey(type, required(props, "name", type).lowercase().trim())
            "Library" -> NodeKey(type, libraryKey(props))
            "IacFile" -> NodeKey(type, iacFileKey(props))
            "Ontology" -> NodeKey(type, required(props, "version", type))
            "SyncRun" -> NodeKey(type, required(props, "id", type))
            else -> throw IdentityResolutionException("no identity rule for node type '$type'")
        }

    /**
     * Normalises any remote form to `host/org/name`, lowercased.
     *
     * Delegates to [GitRemoteParser] rather than matching remotes here. There used to be a second
     * set of patterns in this file, which meant the key a node was stored under and the key a lookup
     * derived could drift apart without anything failing (#8).
     */
    fun repositoryKey(props: Map<String, Any?>): String {
        val url = props["url"]?.toString()?.trim()
        if (!url.isNullOrEmpty()) return gitRemoteParser.parse(url).key

        val host = props["host"]?.toString()?.trim()
        val org = props["org"]?.toString()?.trim()
        val name = props["name"]?.toString()?.trim()
        if (!host.isNullOrEmpty() && !org.isNullOrEmpty() && !name.isNullOrEmpty()) {
            return "$host/$org/$name".lowercase()
        }
        throw IdentityResolutionException("Repository needs either 'url' or all of 'host', 'org' and 'name'")
    }

    /**
     * `<ecosystem>:<name>`, with the name left as its ecosystem spells it.
     *
     * The ecosystem is lowercased because "npm" and "NPM" are one registry; the name is not, because
     * Maven coordinates are case-sensitive and folding them would merge distinct artefacts.
     */
    private fun libraryKey(props: Map<String, Any?>): String =
        required(props, "ecosystem", "Library").lowercase().trim() + ":" + required(props, "name", "Library").trim()

    /** `<repoKey>:<path>`. A path is only meaningful inside the repository that holds it. */
    private fun iacFileKey(props: Map<String, Any?>): String =
        required(props, "repoKey", "IacFile").trim() + ":" + required(props, "path", "IacFile").trim().removePrefix("/")

    private fun configurationItemKey(props: Map<String, Any?>): String {
        val system = (props["sourceSystem"]?.toString() ?: "servicenow").lowercase()
        val instance = required(props, "instance", "ConfigurationItem")
        val sysId = required(props, "sysId", "ConfigurationItem")
        return "$system:$instance:$sysId"
    }

    private fun pipelineKey(props: Map<String, Any?>): String {
        val provider = required(props, "provider", "Pipeline").lowercase()
        val repoKey = required(props, "repoKey", "Pipeline").lowercase()
        val workflowPath = required(props, "workflowPath", "Pipeline")
        return "$provider:$repoKey:$workflowPath"
    }

    /** Prefers an immutable digest; falls back to name and version when the registry gives no digest. */
    private fun artifactKey(props: Map<String, Any?>): String {
        val name = required(props, "name", "Artifact")
        val digest = props["digest"]?.toString()?.takeIf { it.isNotBlank() }
        if (digest != null) {
            val registry = props["registry"]?.toString()?.takeIf { it.isNotBlank() }
            return if (registry != null) "$registry/$name@$digest" else "$name@$digest"
        }
        val version =
            props["version"]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw IdentityResolutionException("Artifact needs either 'digest' or 'version'")
        return "$name:$version"
    }

    private fun environmentKey(name: String): String {
        val normalised = name.lowercase().trim()
        return ENVIRONMENT_ALIASES[normalised] ?: normalised
    }

    private fun deploymentKey(props: Map<String, Any?>): String {
        val artifactKey = required(props, "artifactKey", "Deployment")
        val environmentKey = environmentKey(required(props, "environmentKey", "Deployment"))
        val deployedAt =
            when (val value = props["deployedAt"]) {
                is Instant -> value
                is String -> Instant.parse(value)
                else -> throw IdentityResolutionException("Deployment needs 'deployedAt'")
            }
        return "$artifactKey#$environmentKey#${deployedAt.epochSecond}"
    }

    private fun required(
        props: Map<String, Any?>,
        name: String,
        type: String,
    ): String =
        props[name]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IdentityResolutionException("$type needs '$name' to derive its identity")

    private companion object {
        val ENVIRONMENT_ALIASES =
            mapOf(
                "prod" to "production",
                "prd" to "production",
                "live" to "production",
                "stg" to "staging",
                "stage" to "staging",
                "dev" to "development",
                "test" to "testing",
            )
    }
}
