package com.repodatagraph.auth

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.time.Instant
import java.util.Base64

/**
 * Signs a user in the way the web interface does: the authorization code flow with PKCE against
 * the public UI client, through Keycloak's own login form.
 *
 * Deliberately not the password grant. The realm does not enable it for the UI client, and a test
 * that took a shortcut the browser cannot would be proving a flow nobody uses.
 */
class PkceSignIn(
    private val issuer: String,
    private val objectMapper: ObjectMapper,
) {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val cookies = linkedMapOf<String, String>()

    /** Returns the access token Keycloak issued to [username]. */
    fun accessToken(
        username: String,
        password: String,
    ): String {
        val verifier = base64Url(ByteArray(VERIFIER_BYTES).also { SecureRandom().nextBytes(it) })
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val authorize =
            "$issuer/protocol/openid-connect/auth?" +
                form(
                    "client_id" to DevRealmKeycloak.UI_CLIENT,
                    "response_type" to "code",
                    "scope" to "openid",
                    "redirect_uri" to DevRealmKeycloak.UI_REDIRECT,
                    "state" to "acceptance",
                    "code_challenge" to challenge,
                    "code_challenge_method" to "S256",
                )
        val loginPage = send(HttpRequest.newBuilder(URI.create(authorize)).GET())
        val action =
            FORM_ACTION
                .find(loginPage.body())
                ?.groupValues
                ?.get(1)
                ?.replace("&amp;", "&")
                ?: error("no login form in Keycloak's response (${loginPage.statusCode()}): ${loginPage.body().take(PREVIEW)}")

        val submitted =
            send(
                HttpRequest
                    .newBuilder(URI.create(action))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form("username" to username, "password" to password))),
            )
        val location =
            submitted.headers().firstValue("Location").orElse(null)
                ?: error("sign-in did not redirect (${submitted.statusCode()}): ${submitted.body().take(PREVIEW)}")
        val code =
            query(URI.create(location))["code"]
                ?: error("the redirect after sign-in carried no code: $location")

        val token =
            send(
                HttpRequest
                    .newBuilder(URI.create("$issuer/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            form(
                                "grant_type" to "authorization_code",
                                "client_id" to DevRealmKeycloak.UI_CLIENT,
                                "code" to code,
                                "redirect_uri" to DevRealmKeycloak.UI_REDIRECT,
                                "code_verifier" to verifier,
                            ),
                        ),
                    ),
            )
        return objectMapper.readTree(token.body()).path("access_token").asText(null)
            ?: error("the token endpoint issued no access token (${token.statusCode()}): ${token.body()}")
    }

    /** Keycloak ties the login form to its session cookies, so they are carried between requests. */
    private fun send(request: HttpRequest.Builder): HttpResponse<String> {
        if (cookies.isNotEmpty()) request.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).also { response ->
            response.headers().allValues("Set-Cookie").forEach { header ->
                val (name, value) = header.substringBefore(';').split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                cookies[name] = value
            }
        }
    }

    companion object {
        private const val VERIFIER_BYTES = 32
        private const val PREVIEW = 500
        private val FORM_ACTION = Regex("""<form[^>]*\baction="([^"]+)"""")

        /**
         * A JWT with the issuer and subject a real one would have, signed by a key the issuer never
         * published. What an attacker who knows the claims but not the key could send.
         */
        fun forgedToken(
            issuer: String,
            subject: String,
        ): String {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_BITS) }.generateKeyPair()
            val now = Instant.now().epochSecond
            val header = base64Url("""{"alg":"RS256","typ":"JWT","kid":"forged"}""".toByteArray())
            val payload =
                base64Url(
                    """{"iss":"$issuer","sub":"$subject","iat":$now,"exp":${now + LIFETIME_SECONDS}}""".toByteArray(),
                )
            val signature =
                Signature.getInstance("SHA256withRSA").run {
                    initSign(keys.private)
                    update("$header.$payload".toByteArray())
                    base64Url(sign())
                }
            return "$header.$payload.$signature"
        }

        private const val RSA_BITS = 2048
        private const val LIFETIME_SECONDS = 300

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun form(vararg pairs: Pair<String, String>): String =
            pairs.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }

        private fun query(uri: URI): Map<String, String> =
            uri.rawQuery
                .orEmpty()
                .split('&')
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
    }
}
