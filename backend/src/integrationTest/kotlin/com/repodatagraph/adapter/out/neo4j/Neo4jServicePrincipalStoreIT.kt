package com.repodatagraph.adapter.out.neo4j

import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ServicePrincipalStore
import com.repodatagraph.support.Neo4jTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID

/**
 * The service principal registry in Neo4j (#115): a registration is a ServicePrincipal node like any
 * other, keyed by its name, whose provenance says who registered it and for how long it held.
 *
 * Each test registers names of its own, so what other tests wrote is never counted.
 */
@SpringBootTest
@Import(Neo4jTestcontainersConfig::class)
class Neo4jServicePrincipalStoreIT {
    @Autowired
    private lateinit var store: ServicePrincipalStore

    @Autowired
    private lateinit var graphStore: GraphStore

    private val suffix = UUID.randomUUID().toString().take(8)
    private val registeredAt = Instant.parse("2026-09-30T09:00:00Z")

    private fun principal(
        name: String,
        validTo: Instant? = null,
    ) = ServicePrincipal("$name-$suffix", "team-payments", "An agent", "dan", registeredAt, validTo)

    @Test
    fun `a registration reads back as it was saved`() {
        val saved = store.save(principal("triage-agent"))

        assertThat(store.find(saved.name)).isEqualTo(saved)
    }

    @Test
    fun `a name never registered is not found`() {
        assertThat(store.find("nobody-$suffix")).isNull()
    }

    @Test
    fun `deregistering is the same node with a validTo, not a second one`() {
        val saved = store.save(principal("github-connector"))
        store.save(saved.copy(validTo = registeredAt.plusSeconds(60)))

        assertThat(store.find(saved.name)?.validTo).isEqualTo(registeredAt.plusSeconds(60))
        assertThat(store.findAll().filter { it.name == saved.name }).hasSize(1)
    }

    @Test
    fun `registering again after deregistering clears the validTo`() {
        val saved = store.save(principal("returning-agent", validTo = registeredAt.plusSeconds(60)))
        store.save(saved.copy(validFrom = registeredAt.plusSeconds(120), validTo = null))

        val back = store.find(saved.name)
        assertThat(back?.validTo).isNull()
        assertThat(back?.validFrom).isEqualTo(registeredAt.plusSeconds(120))
    }

    @Test
    fun `the listing holds current and deregistered registrations, in name order`() {
        val b = store.save(principal("b-agent"))
        val a = store.save(principal("a-agent", validTo = registeredAt.plusSeconds(60)))

        val mine = store.findAll().filter { it.name.endsWith(suffix) }

        assertThat(mine).containsExactly(a, b)
    }

    @Test
    fun `the registration is a graph node with the registry's provenance`() {
        val saved = store.save(principal("graph-agent"))

        val node = graphStore.findNode(NodeKey("ServicePrincipal", saved.name))

        assertThat(node?.props?.get("ownedBy")).isEqualTo("team-payments")
        assertThat(node?.provenance?.writtenBy).isEqualTo("dan")
        assertThat(node?.provenance?.validFrom).isEqualTo(registeredAt)
    }
}
