package com.repodatagraph.domain.port.`in`

/**
 * The dogfood seed writing what it read about this repository (#47, FR10).
 *
 * Takes the Authorization header and the body as they arrived, like [DeploymentIngestUseCase], and for
 * the same reason: the token is checked before the body is read.
 */
interface SeedIngestUseCase {
    fun seed(
        authorization: String?,
        body: ByteArray,
    ): SeedIngestOutcome
}

/** How a seed batch ended, one case per status the endpoint answers with. */
sealed interface SeedIngestOutcome {
    /** No token is configured, so nobody may seed. */
    data object Disabled : SeedIngestOutcome

    /** The caller did not present the configured token. */
    data object Unauthorized : SeedIngestOutcome

    /** The batch was refused; [errors] maps each field to what is wrong with it. */
    data class Invalid(
        val errors: Map<String, String>,
    ) : SeedIngestOutcome

    /**
     * The batch was applied.
     *
     * @param created false when this exact batch had already been applied, which changes nothing
     * @param nodes how many nodes the batch asserts, whether or not they already existed
     * @param edges how many edges the batch asserts
     */
    data class Accepted(
        val created: Boolean,
        val nodes: Int,
        val edges: Int,
    ) : SeedIngestOutcome
}
