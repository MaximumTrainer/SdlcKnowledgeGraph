package com.repodatagraph.domain.model

/**
 * A git repository.
 *
 * Identified by its remote rather than by a free-text `org/repo`: the same repository is named
 * differently by everything that mentions it, and only the remote is something they all agree on
 * (#8). `url` is accepted in any written form and stored canonicalised; `host`, `org` and `name` are
 * derived from it and are what the node is keyed on.
 *
 * The key itself is deliberately not a property here. OntologyDriftValidator requires every property
 * of a model class to be declared in the registry, and a derived address is not something the
 * ontology describes - the same reason `id` is excluded. Callers that need it build it from the
 * three parts, as RepositoryResponse does.
 */
data class Repository(
    val id: String,
    val url: String,
    val host: String,
    val org: String,
    val name: String,
    val defaultBranch: String = "main",
    val topics: List<String> = emptyList(),
    val codeowners: List<String> = emptyList(),
    val serviceId: String? = null,
    val language: String? = null,
    val description: String? = null,
)
