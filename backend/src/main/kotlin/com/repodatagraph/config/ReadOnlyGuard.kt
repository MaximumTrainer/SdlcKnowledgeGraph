package com.repodatagraph.config

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.language.OperationDefinition
import graphql.parser.InvalidSyntaxException
import graphql.parser.Parser
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader

/** A write that stays allowed on a read-only instance: an exact method and an exact path. */
data class AllowedWrite(
    val method: String,
    val path: String,
)

/**
 * `sdlc.read-only`: the posture of an instance reachable by people who must not change it (#48, D5).
 *
 * The API has no authentication yet (#3), so without this any reachable instance is an open database.
 * When on, every request that could write is refused with 403 before it reaches a controller.
 *
 * Deny-by-default in two senses. Every method other than GET, HEAD and OPTIONS is refused on every
 * path, not only under `/api/v1`, so a write endpoint added later - or reached by a path spelled
 * differently from the one a pattern expected - is refused until someone lists it in [ALLOWLIST].
 * And a GraphQL request is let through only when its document can be parsed and shown to contain no
 * mutation; anything that cannot be shown safe is refused.
 *
 * This is a posture, not an authorisation model. An allowlisted endpoint is not made safe by being
 * listed; it has to guard itself, and the list only narrows what has to.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReadOnlyGuard internal constructor(
    private val readOnly: Boolean,
    private val allowlist: Set<AllowedWrite>,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    @Autowired
    constructor(
        @Value("\${sdlc.read-only:false}") readOnly: Boolean,
        objectMapper: ObjectMapper,
    ) : this(readOnly, ALLOWLIST, objectMapper)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (!readOnly) {
            chain.doFilter(request, response)
            return
        }
        val path = request.requestURI.removePrefix(request.contextPath)
        when {
            path == GRAPHIQL_PATH || path.startsWith("$GRAPHIQL_PATH/") -> response.sendError(HttpStatus.NOT_FOUND.value())
            isWebSocketUpgrade(request) -> refuse(response)
            request.method in READ_METHODS -> passReadUnlessMutation(request, response, chain, path)
            AllowedWrite(request.method, path) in allowlist -> chain.doFilter(request, response)
            request.method == "POST" && path == GRAPHQL_PATH -> passGraphQlUnlessMutation(request, response, chain)
            else -> refuse(response)
        }
    }

    /**
     * A GraphQL mutation can travel over a WebSocket, where no HTTP method says it is a write. Other
     * upgrades are left alone: HTTP clients routinely offer `h2c`, and refusing that would refuse reads.
     */
    private fun isWebSocketUpgrade(request: HttpServletRequest): Boolean =
        request.getHeaders("Upgrade").toList().any { it.contains("websocket", ignoreCase = true) }

    /** GraphQL over GET is not served today; this keeps a mutation out if it ever is. */
    private fun passReadUnlessMutation(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
        path: String,
    ) {
        val document = request.getParameter("query")
        if (path == GRAPHQL_PATH && document != null && !isFreeOfMutations(document)) {
            refuse(response)
        } else {
            chain.doFilter(request, response)
        }
    }

    private fun passGraphQlUnlessMutation(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val body = request.inputStream.readNBytes(MAX_INSPECTED_BODY_BYTES + 1)
        val document =
            body
                .takeIf { it.size <= MAX_INSPECTED_BODY_BYTES }
                ?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
                ?.takeIf { it.isObject }
                ?.path("query")
                ?.takeIf { it.isTextual }
                ?.asText()
        if (document != null && isFreeOfMutations(document)) {
            chain.doFilter(ReplayedBodyRequest(request, body), response)
        } else {
            refuse(response)
        }
    }

    /** Every operation in the document is checked, not only the one `operationName` selects. */
    private fun isFreeOfMutations(document: String): Boolean =
        try {
            Parser
                .parse(document)
                .getDefinitionsOfType(OperationDefinition::class.java)
                .none { it.operation == OperationDefinition.Operation.MUTATION }
        } catch (_: InvalidSyntaxException) {
            false
        }

    private fun refuse(response: HttpServletResponse) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, mapOf("error" to REFUSAL))
    }

    /** The body had to be read to be inspected, so the application is handed the same bytes again. */
    private class ReplayedBodyRequest(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(ByteArrayInputStream(body))

        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()))

        override fun getContentLength(): Int = body.size

        override fun getContentLengthLong(): Long = body.size.toLong()
    }

    private class ByteArrayServletInputStream(
        private val source: ByteArrayInputStream,
    ) : ServletInputStream() {
        override fun read(): Int = source.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = source.read(b, off, len)

        override fun isFinished(): Boolean = source.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(listener: ReadListener?): Unit = throw IOException("asynchronous reads are not supported")
    }

    companion object {
        /**
         * The writes a read-only instance still accepts. Empty until the deployment ingest endpoint (#7)
         * exists; it is the one entry #48 plans, and it keeps its own bearer token.
         */
        val ALLOWLIST: Set<AllowedWrite> = emptySet()

        /** A GraphQL request larger than this is refused rather than read into memory to be inspected. */
        const val MAX_INSPECTED_BODY_BYTES = 256 * 1024

        const val REFUSAL = "this instance is read-only"
        private const val GRAPHQL_PATH = "/graphql"
        private const val GRAPHIQL_PATH = "/graphiql"
        private val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
