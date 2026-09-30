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
 * [required]. [held] is every graph scope it does carry, so the refusal can say what to ask for.
 */
class SourceNotPermittedException(
    val sourceSystem: String,
    val required: List<String>,
    val held: List<String>,
) : RuntimeException("may not write as source system '$sourceSystem'")
