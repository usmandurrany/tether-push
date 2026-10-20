package io.github.usmandurrany.tether.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface FcmResult {
    data object Sent : FcmResult

    /** The token is no longer valid (app uninstalled, token rotated). */
    data object Unregistered : FcmResult

    data class Failed(val reason: String) : FcmResult
}

fun interface FcmSender {
    suspend fun send(token: String, data: Map<String, String>): FcmResult
}

/** Short-lived OAuth access tokens for the FCM HTTP v1 API. */
fun interface AccessTokens {
    /** A token and how many seconds it stays valid. */
    suspend fun fetch(): Pair<String, Long>
}

/**
 * Sends data-only, high-priority messages through the FCM HTTP v1 API, so the
 * app wakes even in Doze and decides itself what to show.
 */
class FcmHttpV1(
    private val projectId: String,
    private val tokens: AccessTokens,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : FcmSender {
    private val lock = Mutex()
    private var cached: String? = null
    private var expiresAt = 0L

    override suspend fun send(token: String, data: Map<String, String>): FcmResult {
        val body = buildJsonObject {
            putJsonObject("message") {
                put("token", token)
                putJsonObject("data") { data.forEach { (k, v) -> put(k, v) } }
                putJsonObject("android") {
                    put("priority", "HIGH")
                    put("ttl", "300s")
                }
            }
        }.toString()
        val response = try {
            post(body, accessToken())
        } catch (e: Exception) {
            return FcmResult.Failed(e.message ?: e.toString())
        }
        return when {
            response.statusCode() == 200 -> FcmResult.Sent
            response.statusCode() == 404 || "UNREGISTERED" in response.body() -> FcmResult.Unregistered
            response.statusCode() == 401 -> {
                lock.withLock { cached = null } // fetch a new access token next time
                FcmResult.Failed("401 from FCM")
            }
            else -> FcmResult.Failed("${response.statusCode()} from FCM: ${response.body().take(300)}")
        }
    }

    private suspend fun post(body: String, accessToken: String): HttpResponse<String> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private suspend fun accessToken(): String = lock.withLock {
        val now = clock()
        cached?.takeIf { now < expiresAt - 60 }?.let { return it }
        val (token, lifetime) = tokens.fetch()
        cached = token
        expiresAt = now + lifetime
        token
    }
}

/**
 * Access tokens from the Google metadata server: on Cloud Run or Compute
 * Engine, the service account the relay runs as, with no key file at all.
 */
class MetadataServerTokens(
    private val http: HttpClient = HttpClient.newHttpClient(),
) : AccessTokens {
    override suspend fun fetch(): Pair<String, Long> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"))
            .header("Metadata-Flavor", "Google")
            .timeout(Duration.ofSeconds(10))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Metadata server answered ${response.statusCode()}" }
        tokenFrom(response.body())
    }

    companion object {
        /** The project the relay runs in, for when no project id is configured. */
        fun projectId(http: HttpClient = HttpClient.newHttpClient()): String? = runCatching {
            val request = HttpRequest.newBuilder(URI("http://metadata.google.internal/computeMetadata/v1/project/project-id"))
                .header("Metadata-Flavor", "Google")
                .timeout(Duration.ofSeconds(5))
                .build()
            http.send(request, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body()?.trim()
        }.getOrNull()
    }
}

/**
 * Access tokens from a service-account key file (the JSON Firebase's console
 * downloads under Project settings > Service accounts), signed locally.
 */
class ServiceAccountTokens(
    keyJson: String,
    private val http: HttpClient = HttpClient.newHttpClient(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : AccessTokens {
    private val key: JsonObject = Json.parseToJsonElement(keyJson).jsonObject
    val projectId: String = key.string("project_id")
    private val email = key.string("client_email")
    private val tokenUri = key["token_uri"]?.jsonPrimitive?.content ?: "https://oauth2.googleapis.com/token"
    private val privateKey: PrivateKey = parsePrivateKey(key.string("private_key"))

    /** The signed JWT that is exchanged for an access token (RFC 7523). */
    fun assertion(): String {
        val now = clock()
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = buildJsonObject {
            put("iss", email)
            put("scope", SCOPE)
            put("aud", tokenUri)
            put("iat", now)
            put("exp", now + 3600)
        }.toString()
        val unsigned = b64(header.toByteArray()) + "." + b64(claims.toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(unsigned.toByteArray())
            sign()
        }
        return unsigned + "." + b64(signature)
    }

    override suspend fun fetch(): Pair<String, Long> = withContext(Dispatchers.IO) {
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) +
            "&assertion=" + assertion()
        val request = HttpRequest.newBuilder(URI(tokenUri))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Token endpoint answered ${response.statusCode()}" }
        tokenFrom(response.body())
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

        private fun JsonObject.string(name: String): String =
            this[name]?.jsonPrimitive?.content ?: error("The service-account key has no $name")

        private fun parsePrivateKey(pem: String): PrivateKey {
            val der = Base64.getMimeDecoder().decode(
                pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""),
            )
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        }

        private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

private fun tokenFrom(body: String): Pair<String, Long> {
    val json = Json.parseToJsonElement(body).jsonObject
    val token = json["access_token"]?.jsonPrimitive?.content ?: error("No access_token in the answer")
    val lifetime = json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
    return token to lifetime
}
