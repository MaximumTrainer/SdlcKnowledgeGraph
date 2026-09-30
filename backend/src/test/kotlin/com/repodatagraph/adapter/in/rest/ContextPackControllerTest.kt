package com.repodatagraph.adapter.`in`.rest

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.ContextPack
import com.repodatagraph.domain.model.ContextPackQuery
import com.repodatagraph.domain.model.EnvironmentTier
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.PackEdge
import com.repodatagraph.domain.model.PackNode
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ProvenanceSummary
import com.repodatagraph.domain.port.`in`.ContextPackUseCase
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * The HTTP contract of `POST /api/v1/context-pack` (#96): the request body, the bounds a bad one is
 * refused with (`400 {error, field}`), and the shape of the pack - stable node and edge ids, each
 * fact with its one-line provenance summary, each edge with the evidence it rests on - that #79's
 * renderers will cite. The use case is mocked; what a template reaches is the service's business.
 */
@WebMvcTest(ContextPackController::class)
class ContextPackControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var useCase: ContextPackUseCase

    private val at = Instant.parse("2026-09-01T10:00:00Z")
    private val observed = Instant.parse("2026-08-31T12:00:00Z")
    private val stated = Provenance(sourceSystem = "github", ingestedAt = at, observedAt = observed, validFrom = at, confidence = 0.9)

    private fun summary(stale: Boolean = false) = ProvenanceSummary("github", observed, 0.9, inferred = false, stale = stale)

    private val settlement =
        GraphNode(NodeKey.parse("Repository:github.com/acme/settlement-api"), mapOf("name" to "settlement-api"), stated)
    private val web = GraphNode(NodeKey.parse("Repository:github.com/acme/web"), mapOf("name" to "web", "defaultBranch" to "main"), stated)
    private val dependsOn =
        GraphEdge(
            type = "DEPENDS_ON",
            from = web.key,
            to = settlement.key,
            props = mapOf("kind" to "library", "manifest" to "package.json"),
            provenance = stated,
        )

    private val pack =
        ContextPack(
            template = "change-impact",
            start =
                PackNode(
                    node = settlement,
                    label = "settlement-api",
                    distance = 0,
                    confidence = 1.0,
                    inferred = false,
                    score = 1.0,
                    tier = EnvironmentTier.OTHER,
                    via = emptyList(),
                    provenance = summary(),
                ),
            budget = 10,
            asOf = null,
            reached = 35,
            truncated = true,
            cut = 34,
            nodes =
                listOf(
                    PackNode(
                        node = web,
                        label = "web",
                        distance = 1,
                        confidence = 0.9,
                        inferred = false,
                        score = 0.25,
                        tier = EnvironmentTier.OTHER,
                        via = listOf(PathStep("DEPENDED_ON_BY", settlement.id, web.id, 0.9, false)),
                        provenance = summary(stale = true),
                    ),
                ),
            edges = listOf(PackEdge(dependsOn, inverse = "DEPENDED_ON_BY", provenance = summary())),
        )

    private fun contextPack(body: String): ResultActions =
        mockMvc.perform(post("/api/v1/context-pack").contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `answers the pack with stable ids, provenance summaries and the evidence on each edge`() {
        whenever(useCase.contextPack(any())).thenReturn(pack)

        contextPack("""{"startId":"Repository:github.com/acme/settlement-api","template":"change-impact","budget":10}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.template").value("change-impact"))
            .andExpect(jsonPath("$.budget").value(10))
            .andExpect(jsonPath("$.asOf").doesNotExist())
            .andExpect(jsonPath("$.scoring.version").value("1"))
            .andExpect(jsonPath("$.reached").value(35))
            .andExpect(jsonPath("$.truncated").value(true))
            .andExpect(jsonPath("$.cut").value(34))
            .andExpect(jsonPath("$.start.id").value(settlement.id))
            .andExpect(jsonPath("$.start.distance").value(0))
            .andExpect(jsonPath("$.nodes[0].id").value(web.id))
            .andExpect(jsonPath("$.nodes[0].type").value("Repository"))
            .andExpect(jsonPath("$.nodes[0].key").value("github.com/acme/web"))
            .andExpect(jsonPath("$.nodes[0].label").value("web"))
            .andExpect(jsonPath("$.nodes[0].distance").value(1))
            .andExpect(jsonPath("$.nodes[0].confidence").value(0.9))
            .andExpect(jsonPath("$.nodes[0].inferred").value(false))
            .andExpect(jsonPath("$.nodes[0].score").value(0.25))
            .andExpect(jsonPath("$.nodes[0].tier").value("other"))
            .andExpect(jsonPath("$.nodes[0].props.defaultBranch").value("main"))
            .andExpect(jsonPath("$.nodes[0].via[0].edge").value("DEPENDED_ON_BY"))
            .andExpect(jsonPath("$.nodes[0].via[0].to").value(web.id))
            .andExpect(jsonPath("$.nodes[0].provenance.source").value("github"))
            .andExpect(jsonPath("$.nodes[0].provenance.observedAt").value(observed.toString()))
            .andExpect(jsonPath("$.nodes[0].provenance.confidence").value(0.9))
            .andExpect(jsonPath("$.nodes[0].provenance.inferred").value(false))
            .andExpect(jsonPath("$.nodes[0].provenance.stale").value(true))
            // A summary, not the envelope: what an agent reads in one line.
            .andExpect(jsonPath("$.nodes[0].provenance.validFrom").doesNotExist())
            .andExpect(jsonPath("$.edges[0].id").value("DEPENDS_ON:${web.id}>${settlement.id}"))
            .andExpect(jsonPath("$.edges[0].type").value("DEPENDS_ON"))
            .andExpect(jsonPath("$.edges[0].inverse").value("DEPENDED_ON_BY"))
            .andExpect(jsonPath("$.edges[0].from").value(web.id))
            .andExpect(jsonPath("$.edges[0].to").value(settlement.id))
            .andExpect(jsonPath("$.edges[0].manifest").value("package.json"))
            .andExpect(jsonPath("$.edges[0].rule").doesNotExist())
            .andExpect(jsonPath("$.edges[0].commitSha").doesNotExist())
            .andExpect(jsonPath("$.edges[0].props.kind").value("library"))
            .andExpect(jsonPath("$.edges[0].provenance.source").value("github"))
            .andExpect(jsonPath("$.edges[0].provenance.stale").value(false))
    }

    @Test
    fun `the body becomes the query, asOf read as an instant`() {
        whenever(useCase.contextPack(any())).thenReturn(pack)

        contextPack("""{"startId":"Repository:github.com/acme/settlement-api","template":"change-impact","budget":20}""")
            .andExpect(status().isOk)
        contextPack(
            """{"startId":"CloudResource:aws:arn:aws:s3:::l","template":"data-consumers","budget":500,"asOf":"2026-09-10T00:00:00Z"}""",
        ).andExpect(status().isOk)

        val queries = argumentCaptor<ContextPackQuery>()
        verify(useCase, times(2)).contextPack(queries.capture())
        assertEquals(ContextPackQuery(settlement.key, "change-impact", 20), queries.firstValue)
        assertEquals(
            ContextPackQuery(
                NodeKey("CloudResource", "aws:arn:aws:s3:::ledger"),
                "data-consumers",
                500,
                Instant.parse("2026-09-10T00:00:00Z"),
            ),
            queries.secondValue,
        )
    }

    @Test
    fun `an answer as of an instant says which instant`() {
        whenever(useCase.contextPack(any())).thenReturn(pack.copy(asOf = Instant.parse("2026-09-10T00:00:00Z")))

        contextPack("""{"startId":"${settlement.id}","template":"change-impact","budget":10,"asOf":"2026-09-10T00:00:00Z"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.asOf").value("2026-09-10T00:00:00Z"))
    }

    @ParameterizedTest(name = "{1} is refused naming {0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "startId | {\"template\":\"change-impact\",\"budget\":10}",
            "startId | {\"startId\":\"settlement-api\",\"template\":\"change-impact\",\"budget\":10}",
            "template | {\"startId\":\"Repository:github.com/acme/settlement-api\",\"budget\":10}",
            "template | {\"startId\":\"Repository:github.com/acme/settlement-api\",\"template\":\" \",\"budget\":10}",
            "budget | {\"startId\":\"Repository:github.com/acme/settlement-api\",\"template\":\"change-impact\"}",
            "budget | {\"startId\":\"Repository:github.com/acme/settlement-api\",\"template\":\"change-impact\",\"budget\":0}",
            "budget | {\"startId\":\"Repository:github.com/acme/settlement-api\",\"template\":\"change-impact\",\"budget\":501}",
            "asOf | {\"startId\":\"Team:platform\",\"template\":\"change-impact\",\"budget\":5,\"asOf\":\"2026-09-10\"}",
        ],
    )
    fun `a missing or malformed field is 400 naming it, and nothing is walked`(
        field: String,
        body: String,
    ) {
        contextPack(body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value(field))

        verify(useCase, never()).contextPack(any())
    }

    @Test
    fun `a budget out of bounds names the bound`() {
        contextPack("""{"startId":"${settlement.id}","template":"change-impact","budget":501}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("1 and 500")))
    }

    @Test
    fun `an unknown template is 400 naming the field, as the use case refuses it`() {
        whenever(useCase.contextPack(any()))
            .thenThrow(InvalidQueryParameterException("template", "unknown template 'everything'; known: change-impact"))

        contextPack("""{"startId":"${settlement.id}","template":"everything","budget":10}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.field").value("template"))
            .andExpect(jsonPath("$.error").value(containsString("change-impact")))
    }

    @Test
    fun `a start node the graph does not hold is 404`() {
        whenever(useCase.contextPack(any())).thenThrow(NodeNotFoundException(listOf(settlement.key)))

        contextPack("""{"startId":"${settlement.id}","template":"change-impact","budget":10}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.missing[0]").value(settlement.id))
    }

    @Test
    fun `the same pack is the same bytes, whatever order the properties came in`() {
        val reordered =
            pack.copy(
                nodes = pack.nodes.map { it.copy(node = web.copy(props = linkedMapOf("defaultBranch" to "main", "name" to "web"))) },
                edges =
                    pack.edges.map {
                        it.copy(
                            edge = dependsOn.copy(props = linkedMapOf("manifest" to "package.json", "kind" to "library")),
                        )
                    },
            )
        whenever(useCase.contextPack(any())).thenReturn(pack, reordered)

        val body = """{"startId":"${settlement.id}","template":"change-impact","budget":10}"""
        val first = contextPack(body).andReturn().response.contentAsString
        val second = contextPack(body).andReturn().response.contentAsString

        assertEquals(first, second)
    }
}
