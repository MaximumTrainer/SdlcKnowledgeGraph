package com.repodatagraph.domain.exception

/**
 * A query parameter out of its bounds or not in its form (#21, FR7), naming the parameter so a
 * caller can tell which one to fix. Answered 400 with `{error, field}`.
 */
class InvalidQueryParameterException(
    val field: String,
    message: String,
) : RuntimeException(message)
