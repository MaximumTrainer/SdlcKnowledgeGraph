package com.repodatagraph.domain.exception

/**
 * A write named a source system the registry does not declare (#117, FR-4). Refused with the known
 * ones, so the caller can see what it may name instead.
 */
class UnknownSourceSystemException(
    val sourceSystem: String,
    val known: List<String>,
) : RuntimeException("unknown source system '$sourceSystem'")

/**
 * The principal may not state facts as [sourceSystem] (#117, FR-2): its token lacks a scope in
 * [required]. [held] is every graph scope it does carry, so the refusal can say what to ask for, and
 * [policy] the rule that refused it (#95 FR-3): `provenance.confidence` for a source scope it lacks,
 * `scopes` for graph:write itself.
 */
class SourceNotPermittedException(
    val sourceSystem: String,
    val required: List<String>,
    val held: List<String>,
    val policy: String = PROVENANCE_POLICY,
) : RuntimeException("may not write as source system '$sourceSystem'") {
    companion object {
        const val PROVENANCE_POLICY = "provenance.confidence"
    }
}
