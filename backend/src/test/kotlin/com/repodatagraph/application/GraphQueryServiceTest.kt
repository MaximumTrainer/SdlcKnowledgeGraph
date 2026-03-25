package com.repodatagraph.application

import com.repodatagraph.domain.model.*
import com.repodatagraph.domain.port.out.FactStorePort
import com.repodatagraph.domain.port.out.RepositoryGraphPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.kotlin.*

class GraphQueryServiceTest {

    private val graphPort = mock<RepositoryGraphPort>()
    private val factStorePort = mock<FactStorePort>()
    private val service = GraphQueryService(graphPort, factStorePort)

    @Test
    fun `getCloudResourcesForRepo delegates to graph port`() {
        val resources = listOf(CloudResource(id = "c1", provider = "AWS", resourceType = "Lambda", name = "fn-xyz"))
        whenever(graphPort.findCloudResourcesForRepo("repoId")).thenReturn(resources)

        val result = service.getCloudResourcesForRepo("repoId")

        assertEquals(resources, result)
    }

    @Test
    fun `getDependencies returns upstream repos`() {
        val deps = listOf(Repository(id = "dep1", orgRepo = "org/dep1"))
        whenever(graphPort.findDependencies("repoId")).thenReturn(deps)

        val result = service.getDependencies("repoId")

        assertEquals(deps, result)
    }

    @Test
    fun `getDependents returns downstream repos`() {
        val dependents = listOf(Repository(id = "d1", orgRepo = "org/d1"))
        whenever(graphPort.findDependents("repoId")).thenReturn(dependents)

        val result = service.getDependents("repoId")

        assertEquals(dependents, result)
    }

    @Test
    fun `getImpactAnalysis returns combined results`() {
        val dependents = listOf(Repository(id = "d1", orgRepo = "org/d1"))
        val resources = listOf(CloudResource(id = "c1", provider = "AWS", resourceType = "ECS", name = "service"))
        val deployments = listOf(Deployment(id = "dep1", artifactId = "a1", environmentId = "env1"))
        whenever(graphPort.findDependents("repoId")).thenReturn(dependents)
        whenever(graphPort.findCloudResourcesForRepo("repoId")).thenReturn(resources)
        whenever(graphPort.findDeploymentsForRepo("repoId")).thenReturn(deployments)

        val result = service.getImpactAnalysis("repoId")

        assertEquals(dependents, result["dependents"])
        assertEquals(resources, result["cloudResources"])
        assertEquals(deployments, result["deployments"])
    }

    @Test
    fun `getAuditEventsForRepo delegates to factstore`() {
        val events = listOf(AuditEvent(id = "e1", eventType = "DEPLOY"))
        whenever(factStorePort.queryEvents("repoId")).thenReturn(events)

        val result = service.getAuditEventsForRepo("repoId")

        assertEquals(events, result)
    }
}
