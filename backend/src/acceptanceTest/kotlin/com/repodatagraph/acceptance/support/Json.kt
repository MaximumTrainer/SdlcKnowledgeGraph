package com.repodatagraph.acceptance.support

import com.fasterxml.jackson.databind.JsonNode

/**
 * Whether a field actually says something.
 *
 * Worth a named function because the obvious spelling is wrong in a way that never fails: `asText()`
 * on a JSON null returns the string `"null"`, which is not blank, so `path(x).asText().isNotBlank()`
 * reports every null field as present. An assertion written that way passes whether or not the thing
 * it is checking happened - which is how a tombstone test can go green against a node whose validity
 * was never closed.
 */
fun JsonNode.says(field: String): Boolean = hasNonNull(field) && path(field).asText().isNotBlank()
