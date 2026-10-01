package com.repodatagraph.application.ingest

import com.repodatagraph.domain.ontology.OntologyRegistry

/**
 * What kind of environment a canonical environment name is (#81): itself, where the registry's
 * `Environment.type` enum names it, and `other` where it does not, such as `dogfood`, rather than a
 * value the API would refuse from any other writer. One rule for every writer of deployments - the
 * ingest (#7) and the github-actions connector (#90) - so the two type one environment alike.
 */
class EnvironmentTypes(
    registry: OntologyRegistry,
) {
    private val types: Set<String> =
        registry
            .nodeType(ENVIRONMENT)
            ?.property("type")
            ?.enum
            .orEmpty()
            .toSet()

    fun typeOf(canonicalName: String): String = if (canonicalName in types) canonicalName else OTHER

    private companion object {
        const val ENVIRONMENT = "Environment"
        const val OTHER = "other"
    }
}
