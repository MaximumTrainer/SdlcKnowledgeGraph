package com.repodatagraph.adapter.out.github.manifest

import org.springframework.stereotype.Component
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Gradle build files, read as text rather than evaluated.
 *
 * Evaluating a build file means running somebody else's code, which is not something an ingestion
 * job should do on every repository in an estate. So this reads the declarations it can see and
 * accepts that it cannot see the ones a script computes - a dependency added in a loop is invisible
 * here, and that is the price of not executing the file.
 */
@Component
class GradleParser : ManifestParser {
    override val ecosystem = MAVEN

    override fun handles(path: String): Boolean = path.substringAfterLast('/') in GRADLE_FILES

    override fun parse(
        path: String,
        content: String,
    ): Manifest =
        Manifest(
            path = path,
            ecosystem = MAVEN,
            dependencies =
                COORDINATES
                    .findAll(content)
                    .filter { isDependencyConfiguration(it.groupValues[1]) }
                    .mapNotNull { match -> dependencyFrom(match.groupValues[1], match.groupValues[2]) }
                    .distinct()
                    .toList(),
        )

    /**
     * Whether this is a way of declaring a dependency, as opposed to anything else that takes a
     * quoted string.
     *
     * Checked against a list rather than matched in the pattern, so that `id("...")` - a plugin,
     * which is part of the build rather than of the software - does not come back as a dependency.
     */
    private fun isDependencyConfiguration(name: String): Boolean {
        val lower = name.lowercase()
        return CONFIGURATIONS.any { lower == it || lower.endsWith(it) }
    }

    private fun dependencyFrom(
        configuration: String,
        coordinates: String,
    ): DeclaredDependency? {
        val parts = coordinates.split(":")
        // group:artifact, optionally :version. Anything else is a file path or a project reference.
        if (parts.size !in GROUP_AND_ARTIFACT..GROUP_ARTIFACT_AND_VERSION || parts.any { it.isBlank() }) return null
        return DeclaredDependency(
            ecosystem = MAVEN,
            name = "${parts[0]}:${parts[1]}",
            // A version managed by a BOM or a version catalogue is still a dependency; dropping it
            // would hide exactly the ones a platform team manages centrally.
            version = parts.getOrNull(2),
            scope = if (configuration.startsWith("test")) DependencyScope.DEV else DependencyScope.RUNTIME,
        )
    }

    private companion object {
        val GRADLE_FILES = setOf("build.gradle", "build.gradle.kts")
        const val GROUP_AND_ARTIFACT = 2
        const val GROUP_ARTIFACT_AND_VERSION = 3

        /** The base configurations, and anything prefixed onto them such as `testImplementation`. */
        val CONFIGURATIONS =
            listOf("implementation", "api", "compileonly", "runtimeonly", "annotationprocessor", "kapt", "ksp")

        /** Any name followed by a quoted string; which names count is decided separately. */
        val COORDINATES = Regex("""\b([A-Za-z]\w*)\s*[( ]\s*["']([^"']+)["']""")
    }
}

/** Maven's own declaration. XML, and read as XML: a pom is generated as often as it is written. */
@Component
class PomParser : ManifestParser {
    override val ecosystem = MAVEN

    override fun handles(path: String): Boolean = path.substringAfterLast('/') == "pom.xml"

    override fun parse(
        path: String,
        content: String,
    ): Manifest {
        val document =
            try {
                safeDocumentBuilder().parse(ByteArrayInputStream(content.toByteArray()))
            } catch (malformed: SAXException) {
                throw UnreadableManifestException(path, malformed)
            }

        val project = document.documentElement
        val dependencies =
            document
                .getElementsByTagName("dependency")
                .let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
                .filterIsInstance<Element>()
                .mapNotNull { dependencyFrom(it) }

        return Manifest(
            path = path,
            ecosystem = MAVEN,
            dependencies = dependencies,
            publishes = publishedCoordinates(project),
        )
    }

    private fun dependencyFrom(element: Element): DeclaredDependency? {
        val group = element.childText("groupId")
        val artifact = element.childText("artifactId")
        // Half a coordinate names nothing. A dependency inheriting its group from a parent pom is
        // the case this loses, and resolving that means resolving the parent from a remote repository.
        if (group == null || artifact == null) return null
        val scope = element.childText("scope")
        return DeclaredDependency(
            ecosystem = MAVEN,
            name = "$group:$artifact",
            version = element.childText("version"),
            // `test` and `provided` are both "not shipped": provided means the container supplies it.
            scope = if (scope in setOf("test", "provided")) DependencyScope.DEV else DependencyScope.RUNTIME,
        )
    }

    /** The project's own coordinates, inheriting the group from a parent when it does not say. */
    private fun publishedCoordinates(project: Element): List<String> {
        val artifact = project.childText("artifactId") ?: return emptyList()
        val group = project.childText("groupId") ?: project.directChild("parent")?.childText("groupId")
        return listOf(if (group == null) artifact else "$group:$artifact")
    }

    /**
     * Only direct children, and no entities or external references.
     *
     * A pom is fetched from somebody else's repository. Resolving a DOCTYPE or an external entity in
     * one would let that repository read files from this server, which is the classic XXE.
     */
    private fun safeDocumentBuilder() =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }.newDocumentBuilder()

    private fun Element.directChild(name: String): Element? =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { it.tagName == name }

    /**
     * A direct child's text.
     *
     * Direct rather than descendant, so a `<dependencies>` block inside `<dependencyManagement>` does
     * not lend its `groupId` to the project itself.
     */
    private fun Element.childText(name: String): String? = directChild(name)?.textOf()

    private fun Node.textOf(): String? = textContent?.trim()?.takeIf { it.isNotEmpty() }
}

internal const val MAVEN = "maven"
