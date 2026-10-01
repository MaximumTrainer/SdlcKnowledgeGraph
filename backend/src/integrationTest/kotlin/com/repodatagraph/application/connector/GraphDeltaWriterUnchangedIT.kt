package com.repodatagraph.application.connector

import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.out.connector.Capability
import com.repodatagraph.domain.port.out.connector.ConnectorDescriptor
import com.repodatagraph.domain.port.out.connector.EdgeUpsert
import com.repodatagraph.domain.port.out.connector.GraphDelta
import com.repodatagraph.domain.port.out.connector.NodeUpsert
import com.repodatagraph.domain.port.out.connector.SyncMode
import com.repodatagraph.support.Neo4jTestcontainersConfig
import com.repodatagraph.support.connector.FakeConnector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * "Unchanged" has to survive a round trip through Neo4j (#86, FR-6): integers come back as Long,
 * instants as zoned date-times, lists as whatever the driver builds. A unit test with the values the
 * writer sent would prove the comparison and not what the store actually returns.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class GraphDeltaWriterUnchangedIT {
    @Autowired
    private lateinit var writer: GraphDeltaWriter

    @Autowired
    private lateinit var recorder: SyncRunRecorder

    private val github =
        ConnectorDescriptor("github", "github", setOf("Repository", "Team"), setOf("OWNED_BY"), setOf(Capability.FULL))

    @Test
    fun `the same page written twice is written once and unchanged the second time`() {
        val name = "unchanged-" + UUID.randomUUID().toString().take(8)
        val repository = NodeKey("Repository", "github.com/acme/$name")
        val team = NodeKey("Team", "github.com/acme/$name-team")
        val page =
            GraphDelta(
                nodes =
                    listOf(
                        NodeUpsert(
                            type = "Repository",
                            props =
                                mapOf(
                                    "url" to "https://github.com/acme/$name",
                                    "defaultBranch" to "main",
                                    "topics" to listOf("java", "payments"),
                                    "codeowners" to listOf("@acme/$name-team"),
                                    "packageNames" to listOf("@acme/$name-client"),
                                    "language" to "Kotlin",
                                ),
                            observedAt = Instant.parse("2026-09-01T10:00:00Z"),
                            sourceId = "R_$name",
                        ),
                        NodeUpsert(type = "Team", props = mapOf("name" to team.key), sourceId = "acme/$name-team"),
                    ),
                edges =
                    listOf(
                        EdgeUpsert(
                            type = "OWNED_BY",
                            from = repository,
                            to = team,
                            props = mapOf("pathPatterns" to listOf("*")),
                            sourceId = "acme/$name:CODEOWNERS",
                        ),
                    ),
            )

        val first = writer.apply(page, github, run())
        val second = writer.apply(page, github, run())

        assertThat(first.written).isEqualTo(3)
        assertThat(first.unchanged).isZero()
        assertThat(second.written).isZero()
        assertThat(second.unchanged).isEqualTo(3)
    }

    /** A recorded run, since what a run writes is linked to it. */
    private fun run(): String =
        UUID.randomUUID().toString().also { id ->
            recorder.recordRun(
                id,
                RegisteredConnector(FakeConnector(name = "unchanged-it"), enabled = true),
                SyncMode.FULL,
                RunStatus.RUNNING,
                DeltaResult(),
                null,
                null,
            )
        }
}
