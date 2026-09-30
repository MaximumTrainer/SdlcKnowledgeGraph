package com.repodatagraph.adapter.out.github

import com.repodatagraph.adapter.out.github.iac.IacIndexer
import com.repodatagraph.adapter.out.github.manifest.GoModParser
import com.repodatagraph.adapter.out.github.manifest.GradleParser
import com.repodatagraph.adapter.out.github.manifest.ManifestParser
import com.repodatagraph.adapter.out.github.manifest.PackageJsonParser
import com.repodatagraph.adapter.out.github.manifest.PackageLockParser
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.IdentityResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What configuration decides, as opposed to what a manifest says.
 *
 * Each of these switches exists because the honest default is not the same for every organisation:
 * a lockfile is thousands of nodes, infrastructure indexing is only useful to someone who runs the
 * link engine, and what counts as "ours" is a naming convention nobody outside can know.
 */
class RepositoryContentsMapperTest {
    private val parsers: List<ManifestParser> =
        listOf(PackageJsonParser(), PackageLockParser(), GradleParser(), GoModParser())

    private val payments = NodeKey("Repository", "github.com/acme/payments")

    private val packageJson = """{ "name": "@acme/payments", "dependencies": { "express": "^4.18.0" } }"""
    private val packageLock = """{ "packages": { "node_modules/express": { "version": "4.18.2" } } }"""
    private val terraform = """resource "aws_s3_bucket" "receipts" { bucket = "acme-payments-receipts" }"""

    @Test
    fun `reads a manifest into dependencies naming the file they came from`() {
        val contents = mapper().map(payments, mapOf("package.json" to packageJson), null, "acme/payments")

        // Every dependency waits for the whole picture (#86, FR-4): whether "express" is a library or
        // a repository in this organisation is only known once every repository has said what it
        // publishes, so nothing is written for it yet.
        assertThat(contents.delta.nodes).isEmpty()
        val dependency = contents.dependencies.single()
        assertThat(dependency.packageName).isEqualTo("express")
        assertThat(dependency.manifest).isEqualTo("package.json")
        assertThat(dependency.version).isEqualTo("^4.18.0")
        assertThat(dependency.source).isEqualTo("acme/payments")
        assertThat(dependency.internal).isFalse()
    }

    @Test
    fun `leaves a lockfile alone unless it is asked for`() {
        val files = mapOf("package-lock.json" to packageLock)

        // Thousands of transitive packages for a repository that declares twenty. Recording them by
        // default would turn "who depends on this" into a question about npm's install graph.
        assertThat(mapper().map(payments, files, null).dependencies).isEmpty()
        assertThat(mapper(manifests(includeLockfiles = true)).map(payments, files, null).dependencies).isNotEmpty()
    }

    @Test
    fun `reads nothing at all when manifests are turned off`() {
        val contents = mapper(manifests(enabled = false)).map(payments, mapOf("package.json" to packageJson), null)

        assertThat(contents.delta.nodes).isEmpty()
        assertThat(contents.dependencies).isEmpty()
    }

    @Test
    fun `marks a dependency named like something this organisation publishes`() {
        val internal = """{ "dependencies": { "@acme/billing": "1.2.3" } }"""

        val contents = mapper(manifests(prefixes = listOf("@acme/"))).map(payments, mapOf("package.json" to internal), null)

        // A webhook, which has read one repository, holds such a dependency back rather than record
        // it as a library; a run that has read them all does not need the hint.
        assertThat(contents.delta.nodes).isEmpty()
        assertThat(contents.dependencies.single().packageName).isEqualTo("@acme/billing")
        assertThat(contents.dependencies.single().internal).isTrue()
    }

    @Test
    fun `indexes an infrastructure file as evidence, with an edge from the repository`() {
        val contents = mapper().map(payments, mapOf("infra/main.tf" to terraform), null)

        val node = contents.delta.nodes.single()
        assertThat(node.type).isEqualTo("IacFile")
        assertThat(node.props).containsEntry("format", "terraform").containsEntry("path", "infra/main.tf")
        assertThat(node.sourceId).isEqualTo("github.com/acme/payments:infra/main.tf")
        assertThat(
            contents.delta.edges
                .single()
                .type,
        ).isEqualTo("CONTAINS_IAC")
    }

    @Test
    fun `indexes nothing when infrastructure indexing is turned off`() {
        val contents = mapper(iac = IacSettings(enabled = false)).map(payments, mapOf("infra/main.tf" to terraform), null)

        assertThat(contents.delta.nodes).isEmpty()
    }

    @Test
    fun `only spends a request on a file something can read`() {
        val mapper = mapper()

        assertThat(mapper.isInteresting("package.json")).isTrue()
        assertThat(mapper.isInteresting("infra/main.tf")).isTrue()
        // The listing is one request; fetching is one per file. Every file that is not read is a
        // request not made against a rate limit shared with everything else the token does.
        assertThat(mapper.isInteresting("src/main/kotlin/Payments.kt")).isFalse()
        assertThat(mapper.isInteresting("README.md")).isFalse()
    }

    @Test
    fun `reports what the repository publishes, for other repositories to be matched against`() {
        val contents = mapper().map(payments, mapOf("package.json" to packageJson), null)

        assertThat(contents.publishes).containsExactly("@acme/payments")
    }

    private fun manifests(
        enabled: Boolean = true,
        includeLockfiles: Boolean = false,
        prefixes: List<String> = emptyList(),
    ) = ManifestSettings(enabled = enabled, internalPackagePrefixes = prefixes, includeLockfiles = includeLockfiles)

    private fun mapper(
        manifests: ManifestSettings = manifests(),
        iac: IacSettings = IacSettings(),
    ) = RepositoryContentsMapper(
        parsers = parsers,
        iacIndexer = IacIndexer(),
        identityResolver = IdentityResolver(),
        properties = GitHubProperties(orgs = listOf("acme"), token = "t", manifests = manifests, iac = iac),
    )
}
