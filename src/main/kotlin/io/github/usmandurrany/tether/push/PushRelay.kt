package io.github.usmandurrany.tether.push

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.ClientExtension
import io.github.usmandurrany.tether.core.SessionContext
import io.github.usmandurrany.tether.core.StanzaSender
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.ElementBuilder
import io.github.usmandurrany.tether.xml.element
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * An XEP-0357 app server that runs as an ordinary XMPP account: it logs in
 * with a fixed resource, and devices register `<full JID of the relay>` as
 * their push service. Their server then sends each notification to that
 * resource, and the relay passes it on to Firebase Cloud Messaging.
 *
 * Because it is a client, it needs no domain, certificate or server-to-server
 * port of its own, only a connection out, so it runs anywhere that keeps a
 * process alive (Cloud Run with a minimum of one instance, a small VM).
 *
 * Registration is the p2 ad-hoc command (`register-push-fcm` with `token`),
 * so `PushAppServer.p2(...)` in the SDK works unchanged. The relay keeps no
 * state: it answers with a random node, the token, and an HMAC of the token
 * under [secretKey]. The device's server sends the token and HMAC back with
 * every notification (as publish options), so the relay only ever pushes to
 * tokens it handed a secret to, and only for servers in [allowedDomains].
 */
class PushRelay(
    private val client: StanzaSender,
    private val fcm: FcmSender,
    private val secretKey: ByteArray,
    /** Servers allowed to send notifications through the relay; empty allows any. */
    private val allowedDomains: Set<String> = emptySet(),
    /** Forward the sender and message text the server includes. Off: the app fetches them itself. */
    private val includeMessage: Boolean = false,
    private val log: (String) -> Unit = ::println,
) : ClientExtension {
    val forwarded = AtomicLong()
    val refused = AtomicLong()

    init {
        require(secretKey.size >= 16) { "The secret key needs at least 16 bytes" }
    }

    override suspend fun onSessionStarted(session: SessionContext) {
        // Negative priority: messages to the relay's bare address are not routed to it.
        session.send(element("presence", CLIENT) { element("priority") { text("-1") } })
        log("Online as ${session.jid}; devices register this full address as their push service")
    }

    override suspend fun onStanza(stanza: Element): Boolean {
        if (stanza.name != "iq") return false
        val type = stanza.attribute("type")
        if (type != "get" && type != "set") return false
        val reply = when {
            type == "get" && stanza.child("query", DISCO_INFO) != null -> discoInfo(stanza)
            type == "set" && stanza.child("command", COMMANDS) != null -> register(stanza)
            type == "set" && stanza.child("pubsub", PUBSUB)?.child("publish", PUBSUB) != null -> publish(stanza)
            else -> error(stanza, "cancel", "service-unavailable")
        }
        client.send(reply)
        return true
    }

    private fun discoInfo(iq: Element) = result(iq) {
        element("query", DISCO_INFO) {
            element("identity") {
                attribute("category", "pubsub")
                attribute("type", "push")
                attribute("name", "Tether push relay")
            }
            listOf(DISCO_INFO, COMMANDS, PUSH).forEach { feature -> element("feature") { attribute("var", feature) } }
        }
    }

    private fun register(iq: Element): Element {
        val command = iq.child("command", COMMANDS)!!
        if (command.attribute("node") != REGISTER_COMMAND) return error(iq, "cancel", "item-not-found")
        if (command.attribute("action") == "cancel") {
            return result(iq) {
                element("command", COMMANDS) {
                    attribute("node", REGISTER_COMMAND)
                    attribute("sessionid", command.attribute("sessionid"))
                    attribute("status", "canceled")
                }
            }
        }
        val fields = formFields(command)
        val token = fields["token"]?.trim()
        if (token.isNullOrEmpty()) {
            // Asked without the token: show the form, as XEP-0050 clients expect.
            return result(iq) {
                element("command", COMMANDS) {
                    attribute("node", REGISTER_COMMAND)
                    attribute("sessionid", command.attribute("sessionid") ?: randomId())
                    attribute("status", "executing")
                    element("x", DATA_FORMS) {
                        attribute("type", "form")
                        element("field") {
                            attribute("var", "token")
                            attribute("type", "text-single")
                            attribute("label", "FCM registration token")
                        }
                    }
                }
            }
        }
        val node = randomId()
        log("Registered a device for ${iq.attribute("from")?.let(::bareDomain) ?: "unknown"}")
        return result(iq) {
            element("command", COMMANDS) {
                attribute("node", REGISTER_COMMAND)
                attribute("sessionid", command.attribute("sessionid") ?: node)
                attribute("status", "completed")
                element("x", DATA_FORMS) {
                    attribute("type", "result")
                    field("node", node)
                    field("token", token)
                    field("secret", sign(token))
                }
            }
        }
    }

    private suspend fun publish(iq: Element): Element {
        val from = iq.attribute("from")
        val domain = from?.let(::bareDomain)
        if (allowedDomains.isNotEmpty() && domain !in allowedDomains) {
            refused.incrementAndGet()
            log("Refused a notification from $domain: not in the allowed domains")
            return error(iq, "auth", "forbidden")
        }
        val pubsub = iq.child("pubsub", PUBSUB)!!
        val publish = pubsub.child("publish", PUBSUB)!!
        val node = publish.attribute("node").orEmpty()
        val options = pubsub.child("publish-options", PUBSUB)?.let(::formFields).orEmpty()
        val token = options["token"]
        val secret = options["secret"]
        if (token.isNullOrEmpty() || secret.isNullOrEmpty() || !MessageDigest.isEqual(sign(token).toByteArray(), secret.toByteArray())) {
            refused.incrementAndGet()
            log("Refused a notification from $domain: missing or wrong secret")
            return error(iq, "auth", "not-authorized")
        }
        val summary = publish.child("item", PUBSUB)?.child("notification", PUSH)?.let(::formFields).orEmpty()
        val data = buildMap {
            put("type", "xmpp-push")
            put("node", node)
            summary["message-count"]?.let { put("count", it) }
            if (includeMessage) {
                summary["last-message-sender"]?.let { put("sender", it) }
                summary["last-message-body"]?.let { put("body", it) }
            }
        }
        return when (val outcome = fcm.send(token, data)) {
            FcmResult.Sent -> {
                forwarded.incrementAndGet()
                result(iq)
            }
            // Tells the device's server to stop: XEP-0357 §8 says it then disables this node.
            FcmResult.Unregistered -> {
                log("FCM no longer knows a token; told $domain to stop")
                error(iq, "cancel", "item-not-found")
            }
            is FcmResult.Failed -> {
                log("FCM failed: ${outcome.reason}")
                error(iq, "wait", "internal-server-error")
            }
        }
    }

    private fun sign(token: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(token.toByteArray()))
    }

    private fun result(iq: Element, block: ElementBuilder.() -> Unit = {}) = element("iq", CLIENT) {
        attribute("type", "result")
        attribute("id", iq.attribute("id"))
        attribute("to", iq.attribute("from"))
        block()
    }

    private fun error(iq: Element, type: String, condition: String) = element("iq", CLIENT) {
        attribute("type", "error")
        attribute("id", iq.attribute("id"))
        attribute("to", iq.attribute("from"))
        element("error") {
            attribute("type", type)
            element(condition, STANZAS)
        }
    }

    private fun ElementBuilder.field(name: String, value: String) {
        element("field") {
            attribute("var", name)
            element("value") { text(value) }
        }
    }

    companion object {
        const val REGISTER_COMMAND = "register-push-fcm"
        private const val CLIENT = "jabber:client"
        private const val DISCO_INFO = "http://jabber.org/protocol/disco#info"
        private const val COMMANDS = "http://jabber.org/protocol/commands"
        private const val PUBSUB = "http://jabber.org/protocol/pubsub"
        private const val PUSH = "urn:xmpp:push:0"
        private const val DATA_FORMS = "jabber:x:data"
        private const val STANZAS = "urn:ietf:params:xml:ns:xmpp-stanzas"
        private val random = SecureRandom()

        private fun randomId(): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun bareDomain(address: String): String? = runCatching { BareJid.parse(address).domain }.getOrNull()

        /** The fields of the first data form under [parent], by name. */
        private fun formFields(parent: Element): Map<String, String> =
            parent.child("x", DATA_FORMS)?.children("field", DATA_FORMS).orEmpty()
                .mapNotNull { field -> field.attribute("var")?.let { it to field.child("value", DATA_FORMS)?.text.orEmpty() } }
                .toMap()
    }
}
