package com.repodatagraph.adapter.out.archive

import com.fasterxml.jackson.databind.ObjectMapper
import com.repodatagraph.domain.lifecycle.ArchiveRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZonedDateTime

/**
 * The archive's file (#33, FR6): one JSON object per line, so a year of retired facts can be read
 * back a line at a time, named for when it was written so one run never overwrites another.
 */
class FilesystemArchiveWriterTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `writes one JSON object per record into a file named for the run`() {
        val at = Instant.parse("2026-09-30T04:00:00Z")
        val writer = FilesystemArchiveWriter(directory.resolve("nested").toString())

        val location =
            writer.open(at).use { sink ->
                sink.write(ArchiveRecord("node", mapOf("id" to "CloudResource:a", "labels" to listOf("CloudResource"))))
                sink.write(ArchiveRecord("edge", mapOf("type" to "OWNED_BY", "validTo" to ZonedDateTime.parse("2025-01-01T00:00:00Z"))))
                sink.location
            }

        val file = Path.of(location)
        assertThat(file.fileName.toString()).isEqualTo("archive-20260930T040000Z.jsonl")
        val lines = Files.readAllLines(file)
        assertThat(lines).hasSize(2)
        val first = ObjectMapper().readTree(lines[0])
        assertThat(first.path("kind").asText()).isEqualTo("node")
        assertThat(first.path("data").path("id").asText()).isEqualTo("CloudResource:a")
        assertThat(
            ObjectMapper()
                .readTree(lines[1])
                .path("data")
                .path("validTo")
                .asText(),
        ).isEqualTo("2025-01-01T00:00:00Z")
    }
}
