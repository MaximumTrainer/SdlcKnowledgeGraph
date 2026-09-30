package com.repodatagraph.adapter.out.archive

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.repodatagraph.domain.lifecycle.ArchiveRecord
import com.repodatagraph.domain.port.out.ArchiveSink
import com.repodatagraph.domain.port.out.ArchiveWriter
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Writes an archive run to `archive-<yyyyMMdd'T'HHmmss'Z'>.jsonl` under [directory] (#33, FR6): one
 * JSON object per line, `{"kind": "node" | "edge", "type": ..., "data": {...}}`, so a year of retired facts can be
 * read back, or loaded elsewhere, a line at a time. Named for when the run began, so one run never
 * overwrites another.
 */
class FilesystemArchiveWriter(
    private val directory: String,
) : ArchiveWriter {
    private val json =
        ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    override fun open(at: Instant): ArchiveSink {
        val root = Path.of(directory).toAbsolutePath()
        Files.createDirectories(root)
        val stamp = STAMP.format(at.atZone(ZoneOffset.UTC))
        var file = root.resolve("archive-$stamp.jsonl")
        var suffix = 1
        while (Files.exists(file)) file = root.resolve("archive-$stamp-${suffix++}.jsonl")
        return FileSink(file, Files.newBufferedWriter(file))
    }

    private inner class FileSink(
        file: Path,
        private val out: BufferedWriter,
    ) : ArchiveSink {
        override val location: String = file.toString()

        override fun write(record: ArchiveRecord) {
            out.write(json.writeValueAsString(mapOf("kind" to record.kind, "type" to record.type, "data" to record.data)))
            out.newLine()
        }

        override fun close() = out.close()
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}
