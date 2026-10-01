package com.repodatagraph.adapter.out.policy

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * An OPA bundle as `opa build --target wasm` writes it (#30, ADR-0020): a gzipped tar holding the
 * compiled `policy.wasm`, the merged `data.json` and the `.manifest` naming its revision.
 *
 * Read with a few lines of tar rather than a library: a bundle is a handful of regular files, and
 * every entry is read whole. Anything else in it - the Rego sources OPA copies in - is ignored.
 *
 * @param source where it was read from, for the log and the policy endpoint
 */
class PolicyBundle(
    val wasm: ByteArray,
    val data: String,
    val revision: String,
    val source: String,
) {
    companion object {
        /** The bundle the API is built with: policy/ at the root of the repository, compiled by scripts/opa.mjs. */
        const val CLASSPATH_LOCATION = "policy/bundle.tar.gz"

        private const val BLOCK = 512
        private const val NAME_LENGTH = 100
        private const val SIZE_OFFSET = 124
        private const val SIZE_LENGTH = 12
        private const val TYPE_OFFSET = 156
        private const val PREFIX_OFFSET = 345
        private const val PREFIX_LENGTH = 155
        private const val OCTAL = 8
        private val MAPPER = ObjectMapper()

        fun fromClasspath(location: String = CLASSPATH_LOCATION): PolicyBundle {
            val stream =
                checkNotNull(PolicyBundle::class.java.classLoader.getResourceAsStream(location)) {
                    "no policy bundle at classpath:$location; run node scripts/opa.mjs build"
                }
            return stream.use { read(it, "classpath:$location") }
        }

        fun fromFile(path: Path): PolicyBundle = Files.newInputStream(path).use { read(it, path.toString()) }

        fun read(
            gzipped: InputStream,
            source: String,
        ): PolicyBundle {
            val entries = untar(GZIPInputStream(gzipped).readBytes())
            val wasm = checkNotNull(entries["policy.wasm"]) { "$source holds no policy.wasm: build it with opa build --target wasm" }
            val data = entries["data.json"]?.toString(Charsets.UTF_8) ?: "{}"
            val revision =
                entries[".manifest"]
                    ?.let { MAPPER.readTree(it).path("revision").asText("") }
                    ?.takeIf { it.isNotBlank() }
                    ?: "unversioned"
            return PolicyBundle(wasm, data, revision, source)
        }

        /** Every regular file in a ustar archive, by its name without a leading slash. */
        private fun untar(archive: ByteArray): Map<String, ByteArray> {
            val files = mutableMapOf<String, ByteArray>()
            var offset = 0
            while (offset + BLOCK <= archive.size) {
                val header = archive.copyOfRange(offset, offset + BLOCK)
                if (header.all { it.toInt() == 0 }) break
                val name = text(header, 0, NAME_LENGTH)
                val prefix = text(header, PREFIX_OFFSET, PREFIX_LENGTH)
                val size =
                    text(header, SIZE_OFFSET, SIZE_LENGTH)
                        .trim()
                        .ifEmpty { "0" }
                        .toLong(OCTAL)
                        .toInt()
                val type = header[TYPE_OFFSET].toInt().toChar()
                val body = offset + BLOCK
                if (type == '0' || type == '\u0000') {
                    val path = (if (prefix.isEmpty()) name else "$prefix/$name").removePrefix("/")
                    files[path] = archive.copyOfRange(body, body + size)
                }
                offset = body + (size + BLOCK - 1) / BLOCK * BLOCK
            }
            return files
        }

        private fun text(
            header: ByteArray,
            from: Int,
            length: Int,
        ): String {
            val field = header.copyOfRange(from, from + length)
            val end = field.indexOf(0).let { if (it < 0) length else it }
            return String(field, 0, end, Charsets.US_ASCII)
        }

        /** A bundle from bytes, for tests that build one in memory. */
        fun read(
            gzipped: ByteArray,
            source: String,
        ): PolicyBundle = read(ByteArrayInputStream(gzipped), source)
    }
}
