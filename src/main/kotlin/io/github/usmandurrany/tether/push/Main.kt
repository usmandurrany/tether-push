package io.github.usmandurrany.tether.push

import com.sun.net.httpserver.HttpServer
import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.ConnectionState
import io.github.usmandurrany.tether.core.TetherJvm
import io.github.usmandurrany.tether.core.XmppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Runs the push relay. Everything is configured with environment variables,
 * so no secret is ever in the image or the repository:
 *
 * - `RELAY_JID`, `RELAY_PASSWORD`: the XMPP account the relay logs in as (its own account, not a user's).
 * - `RELAY_RESOURCE`: fixed resource, `push` by default. Devices register `RELAY_JID/RELAY_RESOURCE`.
 * - `RELAY_HOST`, `RELAY_PORT`: optional, to skip DNS SRV.
 * - `RELAY_SECRET`: at least 16 random bytes, base64 (`openssl rand -base64 32`). Changing it invalidates every registration.
 * - `RELAY_ALLOWED_DOMAINS`: comma-separated servers allowed to push through the relay, such as `sure.im`. Empty allows any.
 * - `RELAY_INCLUDE_MESSAGE`: `true` forwards the sender and text the server includes; off by default.
 * - `FCM_PROJECT_ID`: the Firebase project; read from the metadata server on Google Cloud when unset.
 * - `GOOGLE_APPLICATION_CREDENTIALS`: a service-account key file. Unset on Cloud Run or Compute Engine,
 *   where the relay uses the service account it runs as.
 * - `PORT`: where the status page listens (Cloud Run sets it), 8080 by default.
 */
fun main() {
    fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
    fun required(name: String): String = env(name) ?: run {
        System.err.println("$name is not set. See the README.")
        exitProcess(2)
    }

    val jid = BareJid.parse(required("RELAY_JID"))
    val resource = env("RELAY_RESOURCE") ?: "push"
    val secret = runCatching { Base64.getDecoder().decode(required("RELAY_SECRET")) }.getOrElse {
        System.err.println("RELAY_SECRET must be base64, for example the output of `openssl rand -base64 32`.")
        exitProcess(2)
    }
    val tokens: AccessTokens
    val projectId: String
    val keyFile = env("GOOGLE_APPLICATION_CREDENTIALS")
    if (keyFile != null) {
        val accountTokens = ServiceAccountTokens(File(keyFile).readText())
        tokens = accountTokens
        projectId = env("FCM_PROJECT_ID") ?: accountTokens.projectId
    } else {
        tokens = MetadataServerTokens()
        projectId = env("FCM_PROJECT_ID") ?: MetadataServerTokens.projectId() ?: run {
            System.err.println("Set FCM_PROJECT_ID, or GOOGLE_APPLICATION_CREDENTIALS outside Google Cloud.")
            exitProcess(2)
        }
    }

    val config = XmppConfig(
        jid = jid,
        password = required("RELAY_PASSWORD"),
        resource = resource,
        host = env("RELAY_HOST"),
        port = env("RELAY_PORT")?.toInt(),
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = TetherJvm.client(config, scope, rememberLogin = false)
    val relay = PushRelay(
        client = client,
        fcm = FcmHttpV1(projectId, tokens),
        secretKey = secret,
        allowedDomains = env("RELAY_ALLOWED_DOMAINS")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
        includeMessage = env("RELAY_INCLUDE_MESSAGE").toBoolean(),
    )
    client.install(relay)

    startStatusPage(env("PORT")?.toInt() ?: 8080) {
        val state = client.state.value
        "${if (state is ConnectionState.Online) "online as ${state.jid}" else state.toString()}\n" +
            "FCM project: $projectId\nforwarded: ${relay.forwarded.get()}\nrefused: ${relay.refused.get()}\n"
    }

    println("Push relay for FCM project $projectId, logging in as $jid/$resource")
    runBlocking {
        while (true) {
            client.connect()
            // The SDK reconnects on its own; it stops only on errors it cannot fix
            // (bad password, the resource taken by a second copy). Try again later.
            val stopped = client.state.first { it is ConnectionState.Terminal } as ConnectionState.Terminal
            System.err.println("Stopped: ${stopped.error.message}. Retrying in a minute.")
            delay(60_000)
        }
    }
}

private fun startStatusPage(port: Int, status: () -> String) {
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/") { exchange ->
        val body = status().toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.start()
}
