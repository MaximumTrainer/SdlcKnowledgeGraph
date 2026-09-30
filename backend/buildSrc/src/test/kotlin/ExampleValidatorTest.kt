import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether an example is a value its property would accept (#81): of its type, inside its enum, of its
 * format, and of a conditional format only where the condition holds. This is the lint's reading of
 * the API's rules; ShippedOntologyDescriptionTest holds the shipped examples to the API's own.
 */
class ExampleValidatorTest {
    @Test
    fun `a value of each type is accepted for that type`() {
        mapOf(
            "string" to "payments",
            "int" to 42,
            "float" to 0.95,
            "boolean" to true,
            "instant" to "2026-09-30T12:00:00Z",
            "string[]" to listOf("billing", "payments"),
        ).forEach { (type, value) ->
            assertEquals(emptyList<String>(), ExampleValidator.problems(GenProperty("p", type, false, null), value), type)
        }
    }

    @Test
    fun `a value of another type is refused, naming the type`() {
        assertEquals(listOf("is not of type int"), ExampleValidator.problems(GenProperty("p", "int", false, null), "42"))
        assertEquals(listOf("is not of type int"), ExampleValidator.problems(GenProperty("p", "int", false, null), true))
        assertEquals(listOf("is not of type instant"), ExampleValidator.problems(GenProperty("p", "instant", false, null), "yesterday"))
        assertEquals(listOf("is not of type string[]"), ExampleValidator.problems(GenProperty("p", "string[]", false, null), listOf("a", 1)))
    }

    @Test
    fun `a value outside the enum is refused, naming the allowed values`() {
        val status = GenProperty("status", "string", true, null, enum = listOf("succeeded", "failed"))

        assertEquals(listOf("is not one of succeeded, failed"), ExampleValidator.problems(status, "done"))
        assertEquals(emptyList<String>(), ExampleValidator.problems(status, "failed"))
    }

    @Test
    fun `a value of another format is refused, naming the format`() {
        val url = GenProperty("url", "string", true, null, format = "url")

        assertEquals(listOf("is not of format url"), ExampleValidator.problems(url, "not a url"))
        assertEquals(emptyList<String>(), ExampleValidator.problems(url, "git@github.com:acme/payments.git"))
    }

    @Test
    fun `a conditional format applies only where its condition holds`() {
        val resourceId = GenProperty("resourceId", "string", true, null, format = "arn", formatWhen = mapOf("provider" to "aws"))

        assertEquals(emptyList<String>(), ExampleValidator.problems(resourceId, "/subscriptions/1/logs"))
        assertEquals(emptyList<String>(), ExampleValidator.problems(resourceId, "/subscriptions/1/logs", mapOf("provider" to "azure")))
        assertEquals(listOf("is not of format arn"), ExampleValidator.problems(resourceId, "/subscriptions/1/logs", mapOf("provider" to "aws")))
    }

    @Test
    fun `every format the registry may name has a rule`() {
        assertEquals(setOf("instant", "url", "email", "iana-tz", "rrule", "semver", "sha256", "arn"), FormatRules.KNOWN)
        mapOf(
            "instant" to "2026-09-30T12:00:00Z",
            "url" to "https://github.com/acme/payments",
            "email" to "platform@acme.example",
            "iana-tz" to "Europe/London",
            "rrule" to "FREQ=WEEKLY;BYDAY=TU",
            "semver" to "1.3.0",
            "sha256" to "sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730",
            "arn" to "arn:aws:s3:::acme-logs",
        ).forEach { (format, value) -> assertEquals(true, FormatRules.matches(format, value), format) }
    }
}
