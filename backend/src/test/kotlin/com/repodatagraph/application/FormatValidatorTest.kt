package com.repodatagraph.application

import com.repodatagraph.domain.ontology.PropertyFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

/**
 * The shapes a string property may be declared to have (#81). Each accepts every form the graph's
 * writers produce today and refuses what is plainly something else; none tries to be a full parser,
 * because a format hint is there to catch a value in the wrong field, not to certify one.
 */
class FormatValidatorTest {
    @ParameterizedTest(name = "{0} accepts {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "INSTANT|2026-09-30T12:00:00Z",
            "INSTANT|2026-09-30T12:00:00.123456Z",
            "URL|https://github.com/acme/payments",
            "URL|http://localhost:8080/api",
            "URL|https://github.com/acme/payments/pull/42",
            "URL|ssh://git@github.com/acme/payments.git",
            "URL|git://example.com/acme/payments",
            "URL|git@github.com:acme/payments.git",
            "URL|chorus://task/01J9ZQ",
            "EMAIL|platform@acme.example",
            "EMAIL|first.last+tag@sub.acme.io",
            "IANA_TZ|Europe/London",
            "IANA_TZ|UTC",
            "IANA_TZ|America/Argentina/Buenos_Aires",
            "RRULE|FREQ=WEEKLY;BYDAY=TU",
            "RRULE|RRULE:FREQ=DAILY;INTERVAL=2;COUNT=10",
            "SEMVER|1.3.0",
            "SEMVER|2.0.0-rc.1+build.5",
            "SHA256|sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730",
            "ARN|arn:aws:s3:::acme-logs",
            "ARN|arn:aws:rds:eu-west-1:123456789012:db:payments",
            "ARN|arn:aws-us-gov:lambda:us-gov-west-1:123456789012:function:ingest",
        ],
    )
    fun `a value of the declared shape is accepted`(
        format: PropertyFormat,
        value: String,
    ) {
        assertThat(FormatValidator.matches(format, value)).isTrue()
    }

    @ParameterizedTest(name = "{0} refuses {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "INSTANT|30/09/2026",
            "INSTANT|2026-09-30",
            "URL|not a url",
            "URL|github.com/acme/payments",
            "URL|https://",
            "EMAIL|not an email",
            "EMAIL|platform@",
            "EMAIL|@acme.example",
            "IANA_TZ|Mars/Olympus_Mons",
            "IANA_TZ|london",
            "RRULE|every tuesday",
            "RRULE|FREQ=FORTNIGHTLY",
            "SEMVER|1.3",
            "SEMVER|v1.3.0",
            "SHA256|sha256:abc",
            "SHA256|7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730",
            "SHA256|SHA256:7D865E959B2466918C9863AFCA942D0FB89D7C9AC0C99BAFC3749504DED97730",
            "ARN|acme-logs",
            "ARN|arn:aws:s3",
        ],
    )
    fun `a value of another shape is refused`(
        format: PropertyFormat,
        value: String,
    ) {
        assertThat(FormatValidator.matches(format, value)).isFalse()
    }

    @ParameterizedTest
    @EnumSource(PropertyFormat::class)
    fun `an absent value is not checked`(format: PropertyFormat) {
        assertThat(FormatValidator.matches(format, null)).isTrue()
    }

    @Test
    fun `every format is published under the name the registry spells it`() {
        assertThat(PropertyFormat.entries.map { it.wireName })
            .containsExactly("instant", "url", "email", "iana-tz", "rrule", "semver", "sha256", "arn")
        assertThat(PropertyFormat.fromWireName("iana-tz")).isEqualTo(PropertyFormat.IANA_TZ)
    }
}
