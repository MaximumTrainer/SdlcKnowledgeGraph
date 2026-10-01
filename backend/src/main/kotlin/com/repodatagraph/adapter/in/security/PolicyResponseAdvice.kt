package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.adapter.`in`.rest.dto.EdgeListResponse
import com.repodatagraph.adapter.`in`.rest.dto.NeighbourhoodResponse
import com.repodatagraph.adapter.`in`.rest.dto.NodePageResponse
import com.repodatagraph.adapter.`in`.rest.dto.NodeResponse
import com.repodatagraph.adapter.out.policy.WasmPolicyDecisionPoint
import com.repodatagraph.config.PolicyProperties
import com.repodatagraph.domain.policy.Decision
import com.repodatagraph.domain.policy.FilterItem
import com.repodatagraph.domain.port.out.PolicyDecisionPoint
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * Filters what the REST reads of nodes return by the authorisation policy (#30 FR4, FR5), as the last
 * thing before the body is written: a node of a type above the caller's clearance is left out, and a
 * property above it is taken out of the nodes that remain and named in their `redacted`.
 *
 * - a node, `GET /api/v1/nodes/...`: refused `403 policy denied` when its type is hidden, which the
 *   gate has usually already done from the path;
 * - a page of nodes: the hidden ones left out of the page;
 * - a node's relationships, `GET /api/v1/edges`: those to a hidden node left out, and none at all
 *   when the node asked about is hidden;
 * - a neighbourhood: hidden nodes and every edge that reaches one left out, counted in
 *   `truncatedByPolicy`.
 *
 * These are the routes the gate lets a caller not cleared for everything read
 * ([RouteAuthz.resource] marks them filtered). The shipped policy clears a token without a roles
 * claim for everything, so for every caller there was before roles, nothing changes.
 *
 * The policy defaults to the bundle the API was built with, for a test slice that has no other.
 */
@RestControllerAdvice
class PolicyResponseAdvice(
    policy: PolicyDecisionPoint = WasmPolicyDecisionPoint.classpathDefault(),
    properties: PolicyProperties = PolicyProperties(),
) : ResponseBodyAdvice<Any> {
    private val reads = PolicyReads(policy, properties)

    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? =
        when (body) {
            is NodeResponse -> node(body, response)
            is NodePageResponse -> page(body)
            is EdgeListResponse -> edges(body, request)
            is NeighbourhoodResponse -> neighbourhood(body)
            else -> body
        }

    private fun node(
        node: NodeResponse,
        response: ServerHttpResponse,
    ): Any {
        val result = reads.filter(listOf(FilterItem(node.id, node.type)))
        if (!result.visible(node.id)) {
            response.setStatusCode(HttpStatus.FORBIDDEN)
            return PolicyRefusal.body(hidden(node.type))
        }
        return redacted(node, result.redactions(node.id))
    }

    private fun page(page: NodePageResponse): NodePageResponse {
        val result = reads.filter(page.items.map { FilterItem(it.id, it.type) })
        if (result.changesNothing) return page
        return page.copy(items = page.items.filter { result.visible(it.id) }.map { redacted(it, result.redactions(it.id)) })
    }

    private fun edges(
        edges: EdgeListResponse,
        request: ServerHttpRequest,
    ): EdgeListResponse {
        val root = (request as? ServletServerHttpRequest)?.servletRequest?.getParameter(NODE_ID)?.takeIf { ':' in it }
        val items =
            edges.items.map { FilterItem(it.other.id, it.other.type) } +
                listOfNotNull(root?.let { FilterItem(it, it.substringBefore(':')) })
        val result = reads.filter(items)
        return when {
            result.changesNothing -> edges
            root != null && !result.visible(root) -> EdgeListResponse(emptyList())
            else ->
                EdgeListResponse(
                    edges.items
                        .filter { result.visible(it.other.id) }
                        .map { edge ->
                            edge.copy(other = edge.other.copy(props = edge.other.props?.let { reads.redact(result, edge.other.id, it) }))
                        },
                )
        }
    }

    private fun neighbourhood(view: NeighbourhoodResponse): NeighbourhoodResponse {
        val result = reads.filter(view.nodes.map { FilterItem(it.id, it.type) })
        if (result.changesNothing) return view
        // Hiding the root hides everything reached from it, however visible each node is.
        val rootHidden = view.nodes.any { it.id == view.root } && !result.visible(view.root)
        val nodes = if (rootHidden) emptyList() else view.nodes.filter { result.visible(it.id) }
        val kept = nodes.mapTo(hashSetOf()) { it.id }
        return view.copy(
            nodes = nodes.map { it.copy(props = reads.redact(result, it.id, it.props)) },
            edges = view.edges.filter { it.from in kept && it.to in kept },
            truncatedByPolicy = view.nodes.size - nodes.size,
        )
    }

    private fun redacted(
        node: NodeResponse,
        redactions: List<String>,
    ): NodeResponse {
        val removed = redactions.filter { it in node.props }
        return if (removed.isEmpty()) node else node.copy(props = node.props - removed.toSet(), redacted = removed.sorted())
    }

    private fun hidden(type: String) =
        Decision(allow = false, policy = SENSITIVITY, reason = "$type is above what the subject is cleared for")

    private companion object {
        const val NODE_ID = "nodeId"
        const val SENSITIVITY = "sensitivity"
    }
}
