package com.repodatagraph.adapter.out.github.manifest

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * One parser per manifest format, each proving the same three things about its own syntax: which
 * dependencies are declared, at what version as written, and which of them are only needed to build.
 *
 * The runtime/dev distinction is the one that earns its place. A graph that cannot tell "this
 * service ships this library" from "somebody's test uses it" answers "what is affected by this CVE"
 * with a list twice as long as the truth, which is the same as answering nothing.
 */
class ManifestParserTest {
    @Nested
    inner class PackageJson {
        private val parser = PackageJsonParser()

        @Test
        fun `reads runtime and dev dependencies apart`() {
            val manifest =
                parser.parse(
                    "package.json",
                    """
                    {
                      "name": "@acme/payments",
                      "dependencies": { "express": "^4.18.0" },
                      "devDependencies": { "vitest": "3.2.4" }
                    }
                    """.trimIndent(),
                )

            assertThat(manifest.ecosystem).isEqualTo("npm")
            assertThat(manifest.dependencies).containsExactlyInAnyOrder(
                DeclaredDependency("npm", "express", "^4.18.0", DependencyScope.RUNTIME),
                DeclaredDependency("npm", "vitest", "3.2.4", DependencyScope.DEV),
            )
        }

        @Test
        fun `reports what the package publishes under its own name`() {
            val manifest = parser.parse("package.json", """{ "name": "@acme/payments" }""")

            // How a dependency on @acme/payments is later resolved to this repository rather than
            // recorded as a third-party library nobody here controls.
            assertThat(manifest.publishes).containsExactly("@acme/payments")
        }

        @Test
        fun `reads a manifest that declares nothing`() {
            assertThat(parser.parse("package.json", "{}").dependencies).isEmpty()
        }

        @Test
        fun `refuses a package json that is not json`() {
            assertThatThrownBy { parser.parse("package.json", "{ this is not json") }
                .isInstanceOf(UnreadableManifestException::class.java)
                .hasMessageContaining("package.json")
        }

        @Test
        fun `reads the file wherever it sits, but only by that name`() {
            assertThat(parser.handles("services/api/package.json")).isTrue()
            assertThat(parser.handles("package.json.bak")).isFalse()
            assertThat(parser.handles("package-lock.json")).isFalse()
        }
    }

    @Nested
    inner class PackageLock {
        private val parser = PackageLockParser()

        @Test
        fun `reads the versions a lockfile actually resolved`() {
            val manifest =
                parser.parse(
                    "package-lock.json",
                    """
                    {
                      "lockfileVersion": 3,
                      "packages": {
                        "": { "name": "@acme/payments" },
                        "node_modules/express": { "version": "4.18.2" },
                        "node_modules/vitest": { "version": "3.2.4", "dev": true }
                      }
                    }
                    """.trimIndent(),
                )

            assertThat(manifest.dependencies).containsExactlyInAnyOrder(
                DeclaredDependency("npm", "express", "4.18.2", DependencyScope.RUNTIME),
                DeclaredDependency("npm", "vitest", "3.2.4", DependencyScope.DEV),
            )
        }

        @Test
        fun `names a nested dependency by its package, not its path`() {
            val manifest =
                parser.parse(
                    "package-lock.json",
                    """
                    { "packages": { "node_modules/a/node_modules/debug": { "version": "4.3.4" } } }
                    """.trimIndent(),
                )

            // The path says where npm put it, which is an installation detail. Two copies of `debug`
            // at different depths are one library.
            assertThat(manifest.dependencies).containsExactly(DeclaredDependency("npm", "debug", "4.3.4"))
        }
    }

    @Nested
    inner class Gradle {
        private val parser = GradleParser()

        @Test
        fun `reads kotlin dsl coordinates`() {
            val manifest =
                parser.parse(
                    "build.gradle.kts",
                    """
                    dependencies {
                        implementation("org.springframework.boot:spring-boot-starter-web:3.5.16")
                        testImplementation("io.mockk:mockk:1.13.12")
                    }
                    """.trimIndent(),
                )

            assertThat(manifest.dependencies).containsExactlyInAnyOrder(
                DeclaredDependency("maven", "org.springframework.boot:spring-boot-starter-web", "3.5.16"),
                DeclaredDependency("maven", "io.mockk:mockk", "1.13.12", DependencyScope.DEV),
            )
        }

        @Test
        fun `reads groovy quotes too`() {
            val manifest = parser.parse("build.gradle", "    api 'com.google.guava:guava:33.0.0-jre'")

            assertThat(manifest.dependencies).containsExactly(DeclaredDependency("maven", "com.google.guava:guava", "33.0.0-jre"))
        }

        @Test
        fun `records a dependency whose version comes from somewhere else`() {
            val manifest = parser.parse("build.gradle.kts", """implementation("org.postgresql:postgresql")""")

            // A version managed by a BOM or a catalogue is still a dependency. Dropping it because the
            // file does not spell the version out would hide exactly the dependencies a platform team
            // manages centrally.
            assertThat(manifest.dependencies).containsExactly(DeclaredDependency("maven", "org.postgresql:postgresql", null))
        }

        @Test
        fun `ignores a plugin, which is a build tool rather than a dependency`() {
            val manifest = parser.parse("build.gradle.kts", """    id("org.jetbrains.kotlin.jvm") version "2.0.21"""")

            assertThat(manifest.dependencies).isEmpty()
        }
    }

    @Nested
    inner class Pom {
        private val parser = PomParser()

        @Test
        fun `reads coordinates and scope`() {
            val manifest =
                parser.parse(
                    "pom.xml",
                    """
                    <project>
                      <artifactId>payments</artifactId>
                      <groupId>com.acme</groupId>
                      <dependencies>
                        <dependency>
                          <groupId>org.springframework</groupId>
                          <artifactId>spring-core</artifactId>
                          <version>6.1.0</version>
                        </dependency>
                        <dependency>
                          <groupId>org.junit.jupiter</groupId>
                          <artifactId>junit-jupiter</artifactId>
                          <version>5.10.0</version>
                          <scope>test</scope>
                        </dependency>
                      </dependencies>
                    </project>
                    """.trimIndent(),
                )

            assertThat(manifest.dependencies).containsExactlyInAnyOrder(
                DeclaredDependency("maven", "org.springframework:spring-core", "6.1.0"),
                DeclaredDependency("maven", "org.junit.jupiter:junit-jupiter", "5.10.0", DependencyScope.DEV),
            )
            assertThat(manifest.publishes).containsExactly("com.acme:payments")
        }

        @Test
        fun `refuses a pom that is not xml`() {
            assertThatThrownBy { parser.parse("pom.xml", "<project><dependencies>") }
                .isInstanceOf(UnreadableManifestException::class.java)
        }
    }

    @Nested
    inner class RequirementsTxt {
        private val parser = RequirementsTxtParser()

        @Test
        fun `reads pinned and floating requirements`() {
            val manifest =
                parser.parse(
                    "requirements.txt",
                    """
                    # comment
                    requests==2.31.0
                    fastapi>=0.110
                    urllib3

                    -r other-requirements.txt
                    """.trimIndent(),
                )

            assertThat(manifest.dependencies).containsExactly(
                DeclaredDependency("pypi", "requests", "==2.31.0"),
                DeclaredDependency("pypi", "fastapi", ">=0.110"),
                DeclaredDependency("pypi", "urllib3", null),
            )
        }

        @Test
        fun `treats a dev requirements file as dev`() {
            val manifest = parser.parse("requirements-dev.txt", "pytest==8.0.0")

            assertThat(manifest.dependencies.single().scope).isEqualTo(DependencyScope.DEV)
        }
    }

    @Nested
    inner class PyProject {
        private val parser = PyProjectParser()

        @Test
        fun `reads PEP 621 dependencies`() {
            val manifest =
                parser.parse(
                    "pyproject.toml",
                    """
                    [project]
                    name = "payments"
                    dependencies = [
                        "requests>=2.31",
                        "fastapi==0.110.0",
                    ]

                    [project.optional-dependencies]
                    dev = ["pytest==8.0.0"]
                    """.trimIndent(),
                )

            assertThat(manifest.dependencies).containsExactlyInAnyOrder(
                DeclaredDependency("pypi", "requests", ">=2.31"),
                DeclaredDependency("pypi", "fastapi", "==0.110.0"),
                DeclaredDependency("pypi", "pytest", "==8.0.0", DependencyScope.DEV),
            )
            assertThat(manifest.publishes).containsExactly("payments")
        }

        @Test
        fun `reads poetry's table instead when that is what the file uses`() {
            val manifest =
                parser.parse(
                    "pyproject.toml",
                    """
                    [tool.poetry.dependencies]
                    python = "^3.12"
                    requests = "^2.31"
                    """.trimIndent(),
                )

            // `python` is the interpreter, not a package: recording it would put a Library node in the
            // graph that no registry has ever published.
            assertThat(manifest.dependencies).containsExactly(DeclaredDependency("pypi", "requests", "^2.31"))
        }
    }

    @Nested
    inner class GoMod {
        private val parser = GoModParser()

        @Test
        fun `reads a require block`() {
            val manifest =
                parser.parse(
                    "go.mod",
                    """
                    module github.com/acme/payments

                    go 1.22

                    require (
                        github.com/gin-gonic/gin v1.10.0
                        github.com/stretchr/testify v1.9.0 // indirect
                    )
                    """.trimIndent(),
                )

            // An indirect requirement is somebody else's dependency, recorded here only because Go
            // writes the whole closure into the file. It is not this repository's declaration.
            assertThat(manifest.dependencies).containsExactly(
                DeclaredDependency("go", "github.com/gin-gonic/gin", "v1.10.0"),
            )
            assertThat(manifest.publishes).containsExactly("github.com/acme/payments")
        }

        @Test
        fun `reads a single-line require`() {
            val manifest = parser.parse("go.mod", "require github.com/spf13/cobra v1.8.0")

            assertThat(manifest.dependencies).containsExactly(DeclaredDependency("go", "github.com/spf13/cobra", "v1.8.0"))
        }
    }
}
