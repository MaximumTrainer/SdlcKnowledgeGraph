package com.repodatagraph.adapter.`in`.graphql

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/**
 * The GraphQL counterpart of the REST 400 and 404 (#21, FR7): a bad argument is a BAD_REQUEST naming
 * it in `extensions.field`, and a node that resolves to nothing is NOT_FOUND. Anything else falls
 * through to the default, which says nothing about the internals.
 */
@Component
class GraphQlExceptions : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(
        ex: Throwable,
        env: DataFetchingEnvironment,
    ): GraphQLError? =
        when (ex) {
            is InvalidQueryParameterException ->
                GraphqlErrorBuilder
                    .newError(env)
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(ex.message.orEmpty())
                    .extensions(mapOf("field" to ex.field))
                    .build()
            is NodeNotFoundException ->
                GraphqlErrorBuilder
                    .newError(env)
                    .errorType(ErrorType.NOT_FOUND)
                    .message("node not found: ${ex.missing.joinToString { it.id }}")
                    .build()
            else -> null
        }
}
