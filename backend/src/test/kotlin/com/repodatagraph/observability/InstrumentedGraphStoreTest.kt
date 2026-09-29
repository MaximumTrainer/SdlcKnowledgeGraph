package com.repodatagraph.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.port.out.GraphStore
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory

/**
 * The store as the application sees it, with its failures made visible (#44, FR7): an operation that
 * fails is counted by name and logged as graph.store.failed, then fails exactly as it would have. A
 * refusal the store is designed to give, such as a missing end of an edge, is an answer rather than
 * a failure and is neither.
 */
class InstrumentedGraphStoreTest {
    private val delegate: GraphStore = mock()
    private val meters = SimpleMeterRegistry()
    private val store = InstrumentedGraphStore(delegate, meters)
    private val platform = NodeKey("Team", "platform")

    private val appender = ListAppender<ILoggingEvent>()
    private val eventLogger = LoggerFactory.getLogger("${EventLog.LOGGER_PREFIX}.graph.store.failed") as Logger

    @BeforeEach
    fun capture() {
        appender.start()
        eventLogger.addAppender(appender)
    }

    @AfterEach
    fun release() {
        eventLogger.detachAppender(appender)
    }

    private fun errors(operation: String) =
        meters
            .find("sdlc.graph.store.errors")
            .tag("operation", operation)
            .counter()
            ?.count()

    @Test
    fun `passes calls through untouched`() {
        val node = GraphNode(platform, mapOf("name" to "platform"), Provenance.manual())
        whenever(delegate.findNode(platform)).thenReturn(node)

        assertThat(store.findNode(platform)).isSameAs(node)
        assertThat(errors("findNode")).isEqualTo(0.0)
    }

    @Test
    fun `a failing operation is counted, logged, and rethrown as it was`() {
        val failure = IllegalStateException("connection refused")
        whenever(delegate.upsertNode(any())).thenThrow(failure)

        assertThatThrownBy { store.upsertNode(GraphNode(platform, emptyMap(), Provenance.manual())) }.isSameAs(failure)

        assertThat(errors("upsertNode")).isEqualTo(1.0)
        val logged = appender.list.single()
        assertThat(logged.keyValuePairs.associate { it.key to it.value })
            .containsEntry("event", "graph.store.failed")
            .containsEntry("operation", "upsertNode")
        assertThat(logged.throwableProxy.message).isEqualTo("connection refused")
    }

    @Test
    fun `a refusal from the domain is not a store failure`() {
        whenever(delegate.countEdges(platform)).thenThrow(NodeNotFoundException(listOf(platform)))

        assertThatThrownBy { store.countEdges(platform) }.isInstanceOf(NodeNotFoundException::class.java)

        assertThat(errors("countEdges")).isEqualTo(0.0)
        assertThat(appender.list).isEmpty()
    }

    @Test
    fun `every operation reports zero errors before any has happened`() {
        val operations =
            GraphStore::class.java.declaredMethods
                .map { it.name }
                .toSet()

        assertThat(operations).allSatisfy { assertThat(errors(it)).describedAs(it).isEqualTo(0.0) }
    }
}
