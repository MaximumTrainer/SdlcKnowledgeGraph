package com.repodatagraph.domain.port.`in`

/**
 * The deploy pipeline reporting what it just deployed (#7).
 *
 * Takes the Authorization header and the body as they arrived. The token is checked before the body
 * is read, so a caller without it learns nothing about what a valid report looks like.
 */
interface DeploymentIngestUseCase {
    fun ingest(
        authorization: String?,
        body: ByteArray,
    ): DeploymentIngestOutcome
}

/** How a report ended, one case per status the endpoint answers with. */
sealed interface DeploymentIngestOutcome {
    /** No token is configured, so nobody may report. */
    data object Disabled : DeploymentIngestOutcome

    /** The caller did not present the configured token. */
    data object Unauthorized : DeploymentIngestOutcome

    /** The report was refused; [errors] maps each field to what is wrong with it. */
    data class Invalid(
        val errors: Map<String, String>,
    ) : DeploymentIngestOutcome

    /**
     * The report was applied.
     *
     * @param deploymentIds one per artifact, as `Deployment:<key>`
     * @param created false when this exact report had already been applied, which changes nothing
     * @param nodes how many nodes the report asserts, whether or not they already existed
     * @param edges how many edges the report asserts
     */
    data class Accepted(
        val deploymentIds: List<String>,
        val created: Boolean,
        val nodes: Int,
        val edges: Int,
    ) : DeploymentIngestOutcome
}
