package com.repodatagraph.application

import com.repodatagraph.adapter.out.ontology.YamlOntologyLoader
import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.port.out.FactStorePort
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.RepositoryGraphPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.DefaultResourceLoader

class RepositoryServiceTest {
    private val graphPort = mock<RepositoryGraphPort>()
    private val factStorePort = mock<FactStorePort>()
    private val graphStore = mock<GraphStore>()
    private val registry = YamlOntologyLoader(DefaultResourceLoader()).load()
    private val service = RepositoryService(graphPort, factStorePort, graphStore, registry)

    @Test
    fun `registerRepository saves to graph and records audit event`() {
        val repo = Repository(id = "1", url = "https://github.com/org/repo", host = "github.com", org = "org", name = "repo")
        whenever(graphPort.save(repo)).thenReturn(repo)

        val result = service.registerRepository(repo)

        assertEquals(repo, result)
        verify(graphPort).save(repo)
        verify(factStorePort).recordEvent(
            argThat { event: AuditEvent ->
                event.eventType == "REPOSITORY_REGISTERED" && event.repoId == "1"
            },
        )
    }

    @Test
    fun `getRepository delegates to graph port`() {
        val repo = Repository(id = "1", url = "https://github.com/org/repo", host = "github.com", org = "org", name = "repo")
        whenever(graphPort.findById("1")).thenReturn(repo)

        val result = service.getRepository("1")

        assertEquals(repo, result)
    }

    @Test
    fun `getRepository returns null when not found`() {
        whenever(graphPort.findById("999")).thenReturn(null)

        val result = service.getRepository("999")

        assertNull(result)
    }

    @Test
    fun `listRepositories returns all from graph port`() {
        val repos =
            listOf(
                Repository(id = "1", url = "https://github.com/org/repo1", host = "github.com", org = "org", name = "repo1"),
                Repository(id = "2", url = "https://github.com/org/repo2", host = "github.com", org = "org", name = "repo2"),
            )
        whenever(graphPort.findAll()).thenReturn(repos)

        val result = service.listRepositories()

        assertEquals(2, result.size)
    }

    @Test
    fun `deleteRepository removes from graph and records audit event`() {
        service.deleteRepository("1")

        verify(graphPort).delete("1")
        verify(factStorePort).recordEvent(
            argThat { event: AuditEvent ->
                event.eventType == "REPOSITORY_DELETED" && event.repoId == "1"
            },
        )
    }

    @Test
    fun `linkToTeam delegates to graph port`() {
        service.linkToTeam("repoId", "teamId")
        verify(graphPort).linkToTeam("repoId", "teamId")
    }

    @Test
    fun `addDependency delegates to graph port`() {
        service.addDependency("repoA", "repoB")
        verify(graphPort).addDependency("repoA", "repoB")
    }

    private val renamed =
        GraphNode(
            NodeKey("Repository", "github.com/acme-platform/payments-service"),
            mapOf("provider" to "github", "providerId" to "123456"),
            Provenance.manual().copy(previousKeys = listOf("github.com/acme/payments")),
        )

    /** A repository found by the id its provider gives it, which survives a rename (#88). */
    @Test
    fun `findByProviderId finds the repository holding that provider id (#88)`() {
        whenever(graphStore.findNodeByAlias("Repository", mapOf("provider" to "github", "providerId" to "123456"))).thenReturn(renamed)

        assertEquals(renamed, service.findByProviderId("GitHub", " 123456 "))
    }

    @Test
    fun `findByProviderId refuses a provider the ontology does not declare, naming the ones it does (#88)`() {
        val refused = assertThrows<InvalidQueryParameterException> { service.findByProviderId("bitbucket", "1") }

        assertEquals("provider", refused.field)
        assertEquals("provider must be one of github, gitlab, other", refused.message)
        verify(graphStore, never()).findNodeByAlias(any(), any())
    }

    @Test
    fun `findByPreviousKey finds the repository that was known by that key before a rename (#88)`() {
        whenever(graphStore.findNodeByPreviousKey(NodeKey("Repository", "github.com/acme/payments"))).thenReturn(renamed)

        assertEquals(renamed, service.findByPreviousKey("github.com/acme/payments"))
    }
}
