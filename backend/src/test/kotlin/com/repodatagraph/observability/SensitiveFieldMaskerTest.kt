package com.repodatagraph.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** A field whose name looks like a secret is masked at any depth before it reaches a log (#44, FR6). */
class SensitiveFieldMaskerTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "password", "PASSWORD", "dbPassword", "token", "accessToken", "secret", "clientSecret", "authorization",
            "apiKey", "API_KEY", "apikey", "cookie", "Set-Cookie", "credential", "credentials",
        ],
    )
    fun `masks a field named like a secret`(name: String) {
        assertThat(SensitiveFieldMasker.mask(mapOf(name to "s3cr3t"))).isEqualTo(mapOf(name to "***"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["name", "type", "key", "properties", "author", "fields"])
    fun `leaves other fields alone`(name: String) {
        assertThat(SensitiveFieldMasker.mask(mapOf(name to "value"))).isEqualTo(mapOf(name to "value"))
    }

    @Test
    fun `masks at any depth, inside maps and lists`() {
        val masked =
            SensitiveFieldMasker.mask(
                mapOf(
                    "outer" to mapOf("inner" to mapOf("password" to "p")),
                    "list" to listOf(mapOf("token" to "t"), "plain"),
                ),
            )

        assertThat(masked).isEqualTo(
            mapOf(
                "outer" to mapOf("inner" to mapOf("password" to "***")),
                "list" to listOf(mapOf("token" to "***"), "plain"),
            ),
        )
    }

    @Test
    fun `masks a secret-named field even when its value is not a string`() {
        assertThat(SensitiveFieldMasker.mask(mapOf("credentials" to mapOf("user" to "u", "pass" to "p")))).isEqualTo(
            mapOf(
                "credentials" to "***",
            ),
        )
    }
}
