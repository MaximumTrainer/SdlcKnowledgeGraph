package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.Repository

data class CreateRepositoryRequest(
    /** The git remote, in any written form. Parsed and canonicalised before anything is stored (#8). */
    val url: String,
    val defaultBranch: String = "main",
    val topics: List<String> = emptyList(),
    val codeowners: List<String> = emptyList(),
    val serviceId: String? = null,
    val language: String? = null,
    val description: String? = null,
)

data class RepositoryResponse(
    val id: String,
    /** The canonical remote, `https://host/org/name`. */
    val url: String,
    /** The identity key the node is stored under, `host/org/name`. */
    val key: String,
    val defaultBranch: String,
    val topics: List<String>,
    val codeowners: List<String>,
    val serviceId: String?,
    val language: String?,
    val description: String?,
    /** Who assigns [providerId]: github, gitlab or other (#88). */
    val provider: String?,
    /** The provider's stable id for the repository, which survives a rename (#88). */
    val providerId: String?,
    /** Keys the repository had before it was renamed, oldest first (#88). */
    val previousKeys: List<String>,
    /**
     * `org/name`. Derived from the remote, never accepted: emitted so a caller that still reads it
     * keeps working until the typed class is migrated off it (#8, #88).
     */
    val orgRepo: String,
) {
    companion object {
        fun from(repo: Repository) =
            RepositoryResponse(
                id = repo.id,
                url = repo.url,
                key = "${repo.host}/${repo.org}/${repo.name}",
                defaultBranch = repo.defaultBranch,
                topics = repo.topics,
                codeowners = repo.codeowners,
                serviceId = repo.serviceId,
                language = repo.language,
                description = repo.description,
                provider = repo.provider,
                providerId = repo.providerId,
                previousKeys = emptyList(),
                orgRepo = repo.orgRepo,
            )

        /**
         * The same response, rendered from a stored node.
         *
         * The deprecated create endpoint writes through the generic node use case, so what comes
         * back is a [GraphNode]; rendering it here keeps one write path rather than two.
         */
        fun from(node: GraphNode) =
            RepositoryResponse(
                id = node.id,
                url = node.props["url"]?.toString() ?: "https://" + node.key.key,
                key = node.key.key,
                defaultBranch = node.props["defaultBranch"]?.toString() ?: "main",
                topics = strings(node.props["topics"]),
                codeowners = strings(node.props["codeowners"]),
                serviceId = node.props["serviceId"]?.toString(),
                language = node.props["language"]?.toString(),
                description = node.props["description"]?.toString(),
                provider = node.props["provider"]?.toString(),
                providerId = node.props["providerId"]?.toString(),
                previousKeys = node.provenance.previousKeys,
                orgRepo = orgRepoOf(node),
            )

        /** From the stored parts when there are any, else from the key, whose last two parts they are. */
        private fun orgRepoOf(node: GraphNode): String {
            val org = node.props["org"]?.toString()
            val name = node.props["name"]?.toString()
            return if (org != null && name != null) "$org/$name" else node.key.key.substringAfter('/')
        }

        private fun strings(value: Any?): List<String> = (value as? Collection<*>)?.map { it.toString() } ?: emptyList()
    }
}

/** Generic request body for linking a repository to another entity (team, cloud resource, pipeline, CI item). */
data class LinkRequest(
    val targetId: String,
)
