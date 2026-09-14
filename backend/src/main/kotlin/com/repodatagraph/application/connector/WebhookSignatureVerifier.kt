package com.repodatagraph.application.connector

import org.springframework.stereotype.Component
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 signature checking, shared by every connector that accepts webhooks.
 *
 * Here rather than in each connector for one reason: comparing the two hex strings with `==` leaks
 * how many leading characters matched, and an attacker who can measure that can find a valid
 * signature a byte at a time. [MessageDigest.isEqual] takes the same time whatever the input, and
 * getting that wrong in six connectors is six chances to get it wrong.
 *
 * An empty secret refuses everything. A connector configured without one has not opted into webhooks
 * at all, and treating "no secret" as "any signature will do" is the worst possible reading.
 */
@Component
class WebhookSignatureVerifier {
    fun verify(
        body: ByteArray,
        secret: String,
        offered: String?,
    ): Boolean {
        if (secret.isEmpty() || offered.isNullOrBlank()) return false
        val expected = sign(body, secret)
        return MessageDigest.isEqual(expected.toByteArray(), offered.trim().toByteArray())
    }

    /** The hex HMAC of [body] under [secret], the form nearly every provider sends. */
    fun sign(
        body: ByteArray,
        secret: String,
    ): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(), ALGORITHM))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
