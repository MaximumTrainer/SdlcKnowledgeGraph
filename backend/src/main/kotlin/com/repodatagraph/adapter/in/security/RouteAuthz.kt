package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.policy.AuthzAction
import com.repodatagraph.domain.policy.AuthzResource
import com.repodatagraph.domain.policy.ResourceKind
import graphql.GraphQLException
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.parser.Parser

/**
 * What a request does and to what, as the policy is asked (#30 FR5, #95 FR-1): the action and the
 * resource of a route, read from the same route families [ScopePolicy] declares.
 *
 * The action follows the family's scopes, so the default policy - which asks of each action exactly
 * the scopes ScopePolicy names - answers every route as the scope check did (DefaultPolicyParityTest):
 * a read is `read`, a read sent as a POST `query`, a route that needs graph:admin `admin`, and any
 * other write `create`, `update` or `delete` by its method, `link` for a relationship and `sync` for a
 * connector run.
 *
 * The resource is a node type where the path names one, so a type above the caller's clearance is
 * refused before anything is read; and a read says whether what it returns is filtered by the policy
 * (PolicyResponseAdvice, AsOfResolver), or holds nothing labelled, or else must be refused to a caller
 * not cleared for everything.
 */
object RouteAuthz {
    private const val NODES = "/api/v1/nodes/"
    private const val EDGES = "/api/v1/edges"
    private const val LINKS = "/api/v1/links/"
    private const val CONNECTORS = "/api/v1/connectors/"
    private const val POLICY = "/api/v1/policy"
    private const val NEIGHBOURHOOD = "/api/v1/graph/neighbourhood"
    private const val BY_KEY = "by-key"
    private const val SYNC_SUFFIX = "/sync"

    /** The GraphQL query fields whose nodes the policy filters (AsOfResolver). */
    val FILTERED_GRAPHQL_FIELDS = setOf("node", "edges", "__typename")

    /**
     * Reads that return no node or property the registry labels: the state of the connectors and
     * their runs, how fresh each source is, the lifecycle's settings and migrations, the ontology and
     * the policy itself. With nothing to filter, they are answered to any reader the scopes and roles
     * allow, whatever their clearance.
     */
    private val UNLABELLED_READS =
        listOf("/api/v1/connectors", "/api/v1/sync-runs", "/api/v1/freshness", "/api/v1/ontology", "/api/v1/lifecycle/migrations")
    private const val LIFECYCLE_STATUS = "/api/v1/lifecycle"

    /** The actions a request to a route needs decided, one per kind of thing it does. */
    fun actions(
        method: String,
        path: String,
        requirement: RouteRequirement,
        graphQlDocument: String?,
    ): Set<AuthzAction> =
        when (requirement) {
            is RouteRequirement.Scopes -> setOf(actionOf(method.uppercase(), path, requirement.scopes))
            RouteRequirement.ByGraphQlOperation -> graphQlActions(graphQlDocument)
            is RouteRequirement.Public -> emptySet()
        }

    private fun actionOf(
        method: String,
        path: String,
        scopes: Set<GraphScope>,
    ): AuthzAction =
        when {
            GraphScope.ADMIN in scopes -> AuthzAction.ADMIN
            GraphScope.WRITE !in scopes -> if (method == "GET" || method == "HEAD") AuthzAction.READ else AuthzAction.QUERY
            path.startsWith(LINKS) || path == EDGES || path.startsWith("$EDGES/") -> AuthzAction.LINK
            path.startsWith(CONNECTORS) && path.endsWith(SYNC_SUFFIX) -> AuthzAction.SYNC
            method == "DELETE" -> AuthzAction.DELETE
            method == "PUT" || method == "PATCH" -> AuthzAction.UPDATE
            else -> AuthzAction.CREATE
        }

    /**
     * A query is `query`; each mutation field is the action it performs. A document that cannot be
     * read is treated as both a query and a write, as [GraphQlScopes] treats it.
     */
    private fun graphQlActions(document: String?): Set<AuthzAction> {
        val required = GraphQlScopes.required(document)
        val operations = document?.let(::operations)
        val actions = mutableSetOf<AuthzAction>()
        if (GraphScope.READ in required) actions += AuthzAction.QUERY
        if (GraphScope.WRITE in required) {
            val mutations = operations?.filter { it.operation == OperationDefinition.Operation.MUTATION }
            val fields = mutations?.flatMap { topLevelFields(it) }.orEmpty()
            actions += if (fields.isEmpty()) setOf(AuthzAction.UPDATE) else fields.map(::mutationAction)
        }
        return actions
    }

    private fun mutationAction(field: String): AuthzAction =
        when {
            field.startsWith("delete") -> AuthzAction.DELETE
            field.startsWith("register") || field.startsWith("create") -> AuthzAction.CREATE
            field.startsWith("link") || field.startsWith("add") -> AuthzAction.LINK
            else -> AuthzAction.UPDATE
        }

    /** What a request to [path] acts on. [parameter] reads a query parameter, for a key sent as one. */
    fun resource(
        method: String,
        path: String,
        parameter: (String) -> String?,
        graphQlDocument: String? = null,
    ): AuthzResource {
        val read = method.uppercase() in setOf("GET", "HEAD")
        return when {
            path.startsWith(NODES) -> nodeResource(path.removePrefix(NODES), read, parameter)
            path == ServicePrincipal.API_PATH || path.startsWith("${ServicePrincipal.API_PATH}/") ->
                AuthzResource(ResourceKind.NODE, type = ServicePrincipal.NODE_TYPE, filtered = true)
            path == EDGES -> if (read) AuthzResource(ResourceKind.ENDPOINT, filtered = true) else AuthzResource(ResourceKind.EDGE)
            path == NEIGHBOURHOOD -> AuthzResource(ResourceKind.ENDPOINT, filtered = true)
            path == POLICY || path.startsWith("$POLICY/") -> AuthzResource(ResourceKind.POLICY, filtered = true)
            path == GRAPHQL -> AuthzResource(ResourceKind.ENDPOINT, filtered = graphQlFiltered(graphQlDocument))
            read && unlabelled(path) -> AuthzResource(ResourceKind.ENDPOINT, filtered = true)
            else -> AuthzResource(ResourceKind.ENDPOINT, filtered = false)
        }
    }

    private fun nodeResource(
        rest: String,
        read: Boolean,
        parameter: (String) -> String?,
    ): AuthzResource {
        val type = rest.substringBefore('/')
        val remainder = rest.substringAfter('/', "")
        val key =
            when {
                remainder == BY_KEY || remainder.startsWith("$BY_KEY/") -> parameter("key")
                remainder.isEmpty() -> null
                else -> remainder.removeSuffix("/merge")
            }
        return AuthzResource(ResourceKind.NODE, type = type.ifBlank { null }, key = key?.ifBlank { null }, filtered = read)
    }

    private fun unlabelled(path: String): Boolean =
        path == LIFECYCLE_STATUS || UNLABELLED_READS.any { path == it || path.startsWith("$it/") }

    /** Whether every query field the document reads is one whose nodes the policy filters. */
    private fun graphQlFiltered(document: String?): Boolean {
        val operations = document?.let(::operations) ?: return false
        return operations
            .filter { it.operation != OperationDefinition.Operation.MUTATION }
            .flatMap { topLevelFields(it) }
            .all { it in FILTERED_GRAPHQL_FIELDS }
    }

    private fun operations(document: String): List<OperationDefinition>? =
        try {
            Parser.parse(document).getDefinitionsOfType(OperationDefinition::class.java)
        } catch (_: GraphQLException) {
            null
        }

    /** The fields an operation selects at its root; a fragment spread there counts as unknown. */
    private fun topLevelFields(operation: OperationDefinition): List<String> =
        operation.selectionSet.selections.map { (it as? Field)?.name ?: "..." }

    private const val GRAPHQL = "/graphql"
}
