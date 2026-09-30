package com.repodatagraph.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.repodatagraph.domain.exception.ServicePrincipalExistsException
import com.repodatagraph.domain.exception.ServicePrincipalNotFoundException
import com.repodatagraph.domain.exception.ServicePrincipalValidationException
import com.repodatagraph.domain.exception.UnknownOwningTeamException
import com.repodatagraph.domain.exception.UserPrincipalRequiredException
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Principal
import com.repodatagraph.domain.model.PrincipalType
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ServicePrincipal
import com.repodatagraph.domain.model.ServicePrincipalRegistration
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ServicePrincipalStore
import com.repodatagraph.observability.EventLog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The service principal registry's rules (#115): only a user registers or deregisters, a principal is
 * owned by a team the graph knows, a name is registered once at a time, deregistering keeps the
 * record, and only a current registration lets a client in.
 */
class ServicePrincipalServiceTest {
    private val now = Instant.parse("2026-09-30T12:00:00Z")
    private val store: ServicePrincipalStore = mock()
    private val graphStore: GraphStore = mock()
    private var principal = Principal("dan", PrincipalType.USER)
    private val service =
        ServicePrincipalService(store, graphStore, { principal }, Clock.fixed(now, ZoneOffset.UTC))

    private val payments = NodeKey("Team", "team-payments")
    private val triage = ServicePrincipalRegistration("triage-agent", "team-payments", "Triages incidents")

    private fun teamExists(key: NodeKey = payments) {
        whenever(graphStore.findNode(key)).thenReturn(GraphNode(key, mapOf("name" to key.key), Provenance.manual()))
    }

    init {
        whenever(store.save(any())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `a user registers a principal owned by a known team, recorded as registered by them from now`() {
        teamExists()

        val registered = service.register(triage)

        assertThat(registered)
            .isEqualTo(ServicePrincipal("triage-agent", "team-payments", "Triages incidents", "dan", now, validTo = null))
        verify(store).save(registered)
    }

    @Test
    fun `the owner is a Team key, looked up exactly as given`() {
        service.runCatching { register(triage) }

        verify(graphStore).findNode(payments)
    }

    @Test
    fun `an owner the graph has no team for is refused, and nothing is saved`() {
        assertThatThrownBy { service.register(triage.copy(ownedBy = "team-nobody")) }
            .isInstanceOf(UnknownOwningTeamException::class.java)
            .extracting("ownedBy")
            .isEqualTo("team-nobody")
        verify(store, never()).save(any())
    }

    @Test
    fun `a service may not register a principal, even a registered one`() {
        teamExists()
        principal = Principal("triage-agent", PrincipalType.SERVICE, onBehalfOfTeam = "team-payments")

        assertThatThrownBy { service.register(triage.copy(name = "github-connector")) }
            .isInstanceOf(UserPrincipalRequiredException::class.java)
        verify(store, never()).save(any())
    }

    @Test
    fun `a name with a current registration is not registered twice`() {
        teamExists()
        whenever(store.find("triage-agent"))
            .thenReturn(ServicePrincipal("triage-agent", "team-payments", null, "dan", now.minusSeconds(60)))

        assertThatThrownBy { service.register(triage) }
            .isInstanceOf(ServicePrincipalExistsException::class.java)
            .extracting("name")
            .isEqualTo("triage-agent")
    }

    @Test
    fun `a deregistered name may be registered again, current from now`() {
        teamExists()
        whenever(store.find("triage-agent"))
            .thenReturn(ServicePrincipal("triage-agent", "team-old", null, "eve", now.minusSeconds(600), now.minusSeconds(60)))

        val registered = service.register(triage)

        assertThat(registered.validTo).isNull()
        assertThat(registered.validFrom).isEqualTo(now)
        assertThat(registered.ownedBy).isEqualTo("team-payments")
        assertThat(registered.registeredBy).isEqualTo("dan")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "has space", "a/b", "../x", "-leading-dash", "semi;colon"])
    fun `a name that cannot be a client id is refused, naming the field`(name: String) {
        teamExists()

        assertThatThrownBy { service.register(triage.copy(name = name)) }
            .isInstanceOf(ServicePrincipalValidationException::class.java)
            .extracting("field")
            .isEqualTo("name")
    }

    @Test
    fun `a name longer than a client id can be is refused`() {
        teamExists()

        assertThatThrownBy { service.register(triage.copy(name = "a".repeat(256))) }
            .isInstanceOf(ServicePrincipalValidationException::class.java)
    }

    @Test
    fun `a blank owner is refused, naming the field`() {
        assertThatThrownBy { service.register(triage.copy(ownedBy = " ")) }
            .isInstanceOf(ServicePrincipalValidationException::class.java)
            .extracting("field")
            .isEqualTo("ownedBy")
    }

    @Test
    fun `deregistering sets validTo to now and keeps the record`() {
        whenever(store.find("triage-agent"))
            .thenReturn(ServicePrincipal("triage-agent", "team-payments", null, "dan", now.minusSeconds(60)))

        val deregistered = service.deregister("triage-agent")

        assertThat(deregistered.validTo).isEqualTo(now)
        val saved = argumentCaptor<ServicePrincipal>()
        verify(store).save(saved.capture())
        assertThat(saved.firstValue.validTo).isEqualTo(now)
        assertThat(saved.firstValue.registeredBy).isEqualTo("dan")
    }

    @Test
    fun `deregistering twice keeps the first end`() {
        val ended = ServicePrincipal("triage-agent", "team-payments", null, "dan", now.minusSeconds(600), now.minusSeconds(60))
        whenever(store.find("triage-agent")).thenReturn(ended)

        assertThat(service.deregister("triage-agent")).isEqualTo(ended)
        verify(store, never()).save(any())
    }

    @Test
    fun `deregistering a name never registered is not found`() {
        assertThatThrownBy { service.deregister("nobody") }
            .isInstanceOf(ServicePrincipalNotFoundException::class.java)
    }

    @Test
    fun `a service may not deregister, not even itself`() {
        principal = Principal("triage-agent", PrincipalType.SERVICE, onBehalfOfTeam = "team-payments")

        assertThatThrownBy { service.deregister("triage-agent") }
            .isInstanceOf(UserPrincipalRequiredException::class.java)
    }

    @Test
    fun `a current registration resolves its client`() {
        val current = ServicePrincipal("triage-agent", "team-payments", null, "dan", now.minusSeconds(60))
        whenever(store.find("triage-agent")).thenReturn(current)

        assertThat(service.resolve("triage-agent")).isEqualTo(current)
    }

    @Test
    fun `a deregistered client resolves to nothing, like one never registered`() {
        whenever(store.find("triage-agent"))
            .thenReturn(ServicePrincipal("triage-agent", "team-payments", null, "dan", now.minusSeconds(600), now.minusSeconds(60)))

        assertThat(service.resolve("triage-agent")).isNull()
        assertThat(service.resolve("rogue-agent")).isNull()
    }

    @Test
    fun `the listing is every registration the store has, deregistered ones included`() {
        val all =
            listOf(
                ServicePrincipal("github-connector", "team-platform", null, "dan", now),
                ServicePrincipal("old-agent", "team-platform", null, "dan", now.minusSeconds(600), now.minusSeconds(60)),
            )
        whenever(store.findAll()).thenReturn(all)

        assertThat(service.list()).isEqualTo(all)
    }

    @Test
    fun `registering and deregistering are security events naming who did it`() {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val loggers =
            listOf("principal.registered", "principal.deregistered")
                .map { LoggerFactory.getLogger("${EventLog.LOGGER_PREFIX}.$it") as Logger }
        loggers.forEach { it.addAppender(events) }
        try {
            teamExists()
            val registered = service.register(triage)
            whenever(store.find("triage-agent")).thenReturn(registered)
            service.deregister("triage-agent")
        } finally {
            loggers.forEach { it.detachAppender(events) }
        }

        assertThat(events.list.map { it.loggerName.removePrefix("${EventLog.LOGGER_PREFIX}.") })
            .containsExactly("principal.registered", "principal.deregistered")
    }
}
