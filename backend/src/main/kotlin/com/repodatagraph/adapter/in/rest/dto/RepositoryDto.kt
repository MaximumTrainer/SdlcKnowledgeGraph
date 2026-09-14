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
            )

        private fun strings(value: Any?): List<String> = (value as? Collection<*>)?.map { it.toString() } ?: emptyList()
    }
}

/** Generic request body for linking a repository to another entity (team, cloud resource, pipeline, CI item). */
data class LinkRequest(
    val targetId: String,
)
