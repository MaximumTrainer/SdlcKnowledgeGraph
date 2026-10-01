package com.repodatagraph.adapter.out.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * Reading the policy bundle `opa build --target wasm` writes (#30): the compiled policy, its data and
 * the revision its manifest names.
 */
class PolicyBundleTest {
    @Test
    fun `the bundle the API ships holds the compiled policy, its data and its revision`() {
        val bundle = PolicyBundle.fromClasspath()

        // Every WebAssembly module starts \0asm.
        assertEquals(listOf<Byte>(0, 0x61, 0x73, 0x6d), bundle.wasm.take(4))
        assertTrue(bundle.data.contains("\"action_scopes\""), "data: ${bundle.data.take(200)}")
        assertTrue(bundle.data.contains("\"ontology\""), "the ontology's sensitivity labels are in the data")
        assertEquals("sdlc-authz-1.0.0", bundle.revision)
        assertEquals("classpath:policy/bundle.tar.gz", bundle.source)
    }

    @Test
    fun `a bundle on disk is read the same way, and names where it came from`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("bundle.tar.gz")
        javaClass.classLoader.getResourceAsStream("policy/bundle.tar.gz")!!.use { Files.copy(it, file) }

        val bundle = PolicyBundle.fromFile(file)

        assertEquals("sdlc-authz-1.0.0", bundle.revision)
        assertEquals(file.toString(), bundle.source)
    }

    @Test
    fun `a bundle without a compiled policy is refused, saying how to build one`() {
        val failure = assertThrows<IllegalStateException> { PolicyBundle.read(ByteArrayInputStream(gzippedEmptyTar()), "test") }

        assertTrue(failure.message!!.contains("opa build --target wasm"), failure.message)
    }

    /** Two zero blocks: the end of an archive holding nothing. */
    private fun gzippedEmptyTar(): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(ByteArray(EMPTY_TAR_BYTES)) }
        return out.toByteArray()
    }

    private companion object {
        const val EMPTY_TAR_BYTES = 1024
    }
}
