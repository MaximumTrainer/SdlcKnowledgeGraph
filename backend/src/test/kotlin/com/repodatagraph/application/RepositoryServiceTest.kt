package com.repodatagraph.application

import com.repodatagraph.domain.model.AuditEvent
import com.repodatagraph.domain.model.Repository
import com.repodatagraph.domain.port.out.FactStorePort
import com.repodatagraph.domain.port.out.RepositoryGraphPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RepositoryServiceTest {
    private val graphPort = mock<RepositoryGraphPort>()
    private val factStorePort = mock<FactStorePort>()
    private val service = RepositoryService(graphPort, factStorePort)

    @Test
    fun `registerRepository saves to graph and records audit event`() {
        val repo = Repository(id = "1", orgRepo = "org/repo")
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
        val repo = Repository(id = "1", orgRepo = "org/repo")
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
                Repository(id = "1", orgRepo = "org/repo1"),
                Repository(id = "2", orgRepo = "org/repo2"),
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
}
