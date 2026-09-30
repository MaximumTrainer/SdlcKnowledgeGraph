package com.repodatagraph.adapter.out.github

import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A pipeline per workflow file (#86, FR-3), keyed the way the deployment reports and the dogfood seed
 * already key one, so the same workflow seen by all three is one node.
 */
class WorkflowPipelinesTest {
    private val pipelines = WorkflowPipelines(IdentityResolver())
    private val payments = NodeKey("Repository", "github.com/acme/payments")
    private val observed = Instant.parse("2026-09-01T10:00:00Z")

    @Test
    fun `only a file directly under the workflows directory is a workflow`() {
        assertThat(pipelines.isWorkflow(".github/workflows/ci.yml")).isTrue()
        assertThat(pipelines.isWorkflow(".github/workflows/release.yaml")).isTrue()
        // GitHub runs neither of these: one is in a subdirectory, the other is not YAML.
        assertThat(pipelines.isWorkflow(".github/workflows/templates/shared.yml")).isFalse()
        assertThat(pipelines.isWorkflow(".github/workflows/README.md")).isFalse()
        assertThat(pipelines.isWorkflow("docs/workflows/notes.yml")).isFalse()
        assertThat(pipelines.isWorkflow("services/api/.github/workflows/ci.yml")).isFalse()
    }

    @Test
    fun `a workflow becomes a Pipeline of its repository, reported by GitHub`() {
        val delta = pipelines.map(payments, "acme/payments", listOf(".github/workflows/ci.yml"), observed)

        val node = delta.nodes.single()
        assertThat(node.type).isEqualTo("Pipeline")
        assertThat(node.props)
            .containsEntry("provider", "github-actions")
            .containsEntry("repoKey", "github.com/acme/payments")
            .containsEntry("workflowPath", ".github/workflows/ci.yml")
            .containsEntry("name", "ci.yml")
            .containsEntry("repoId", "github.com/acme/payments")
        assertThat(node.sourceId).isEqualTo("acme/payments:.github/workflows/ci.yml")
        assertThat(node.observedAt).isEqualTo(observed)
        assertThat(node.inferred).isFalse()

        val edge = delta.edges.single()
        assertThat(edge.type).isEqualTo("HAS_PIPELINE")
        assertThat(edge.from).isEqualTo(payments)
        assertThat(edge.to).isEqualTo(NodeKey("Pipeline", "github-actions:github.com/acme/payments:.github/workflows/ci.yml"))
        assertThat(edge.sourceId).isEqualTo("acme/payments:.github/workflows/ci.yml")
    }

    @Test
    fun `a repository with no workflows has no pipelines`() {
        val delta = pipelines.map(payments, "acme/payments", emptyList(), observed)

        assertThat(delta.nodes).isEmpty()
        assertThat(delta.edges).isEmpty()
    }
}
