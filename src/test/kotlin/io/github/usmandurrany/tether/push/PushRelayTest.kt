package io.github.usmandurrany.tether.push

import io.github.usmandurrany.tether.core.StanzaSender
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.ElementBuilder
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushRelayTest {
    private class Sent : StanzaSender {
        val stanzas = ArrayList<Element>()
        override suspend fun send(stanza: Element) {
            stanzas += stanza
        }
        override suspend fun request(iq: Element, timeoutMillis: Long): Element = error("not used")
    }

    private class Fcm(var result: FcmResult = FcmResult.Sent) : FcmSender {
        val calls = ArrayList<Pair<String, Map<String, String>>>()
        override suspend fun send(token: String, data: Map<String, String>): FcmResult {
            calls += token to data
            return result
        }
    }

    private val key = ByteArray(32) { it.toByte() }

    private fun iq(type: String, from: String, block: ElementBuilder.() -> Unit) = element("iq", CLIENT) {
        attribute("type", type)
        attribute("id", "q1")
        attribute("from", from)
        attribute("to", "relay@example.org/push")
        block()
    }

    private fun ElementBuilder.form(type: String, vararg fields: Pair<String, String>) {
        element("x", DATA_FORMS) {
            attribute("type", type)
            fields.forEach { (name, value) ->
                element("field") {
                    attribute("var", name)
                    element("value") { text(value) }
                }
            }
        }
    }

    private fun Element.formValues(): Map<String, String> =
        child("x", DATA_FORMS)!!.children("field", DATA_FORMS).associate { it.attribute("var")!! to it.child("value", DATA_FORMS)!!.text }

    private suspend fun registerToken(relay: PushRelay, sent: Sent, token: String): Map<String, String> {
        relay.onStanza(
            iq("set", "alice@sure.im/phone") {
                element("command", COMMANDS) {
                    attribute("node", "register-push-fcm")
                    attribute("action", "execute")
                    form("submit", "token" to token, "android-id" to "x")
                }
            },
        )
        val answer = sent.stanzas.removeLast()
        assertEquals("result", answer.attribute("type"))
        assertEquals("alice@sure.im/phone", answer.attribute("to"))
        val command = answer.child("command", COMMANDS)!!
        assertEquals("completed", command.attribute("status"))
        return command.formValues()
    }

    private fun publish(from: String, node: String, options: Map<String, String>) = iq("set", from) {
        element("pubsub", PUBSUB) {
            element("publish") {
                attribute("node", node)
                element("item") {
                    element("notification", PUSH) {
                        form("form", "FORM_TYPE" to "urn:xmpp:push:summary", "message-count" to "2", "last-message-sender" to "bob@sure.im", "last-message-body" to "hi")
                    }
                }
            }
            element("publish-options") { form("submit", *options.toList().toTypedArray()) }
        }
    }

    @Test
    fun aRegisteredDeviceGetsItsNotificationsForwarded() = runTest {
        val sent = Sent()
        val fcm = Fcm()
        val relay = PushRelay(sent, fcm, key, allowedDomains = setOf("sure.im"), log = {})
        val registration = registerToken(relay, sent, "fcm-token-1")
        assertEquals("fcm-token-1", registration["token"])
        val node = assertNotNull(registration["node"])

        relay.onStanza(publish("alice@sure.im", node, registration - "node"))
        assertEquals("result", sent.stanzas.single().attribute("type"))
        val (token, data) = fcm.calls.single()
        assertEquals("fcm-token-1", token)
        assertEquals(mapOf("type" to "xmpp-push", "node" to node, "count" to "2"), data, "no sender or text unless asked for")
    }

    @Test
    fun theMessageIsForwardedOnlyWhenAskedFor() = runTest {
        val sent = Sent()
        val fcm = Fcm()
        val relay = PushRelay(sent, fcm, key, includeMessage = true, log = {})
        val registration = registerToken(relay, sent, "t")
        relay.onStanza(publish("alice@sure.im", registration["node"]!!, registration - "node"))
        assertEquals("bob@sure.im", fcm.calls.single().second["sender"])
        assertEquals("hi", fcm.calls.single().second["body"])
    }

    @Test
    fun aWrongSecretOrAnotherServerIsRefused() = runTest {
        val sent = Sent()
        val fcm = Fcm()
        val relay = PushRelay(sent, fcm, key, allowedDomains = setOf("sure.im"), log = {})
        val registration = registerToken(relay, sent, "t")

        relay.onStanza(publish("alice@sure.im", "n", mapOf("token" to "someone-else", "secret" to registration["secret"]!!)))
        assertEquals("not-authorized", sent.stanzas.removeLast().child("error", CLIENT)!!.elements.single().name)

        relay.onStanza(publish("mallory@evil.example", "n", registration - "node"))
        assertEquals("forbidden", sent.stanzas.removeLast().child("error", CLIENT)!!.elements.single().name)
        assertTrue(fcm.calls.isEmpty())
    }

    @Test
    fun aTokenFcmNoLongerKnowsTellsTheServerToStop() = runTest {
        val sent = Sent()
        val relay = PushRelay(sent, Fcm(FcmResult.Unregistered), key, log = {})
        val registration = registerToken(relay, sent, "t")
        relay.onStanza(publish("alice@sure.im", "n", registration - "node"))
        assertEquals("item-not-found", sent.stanzas.single().child("error", CLIENT)!!.elements.single().name)
    }

    @Test
    fun theCommandWithoutATokenAnswersWithItsForm() = runTest {
        val sent = Sent()
        val relay = PushRelay(sent, Fcm(), key, log = {})
        relay.onStanza(
            iq("set", "alice@sure.im/phone") {
                element("command", COMMANDS) {
                    attribute("node", "register-push-fcm")
                    attribute("action", "execute")
                }
            },
        )
        val command = sent.stanzas.single().child("command", COMMANDS)!!
        assertEquals("executing", command.attribute("status"))
        assertNotNull(command.attribute("sessionid"))
    }

    @Test
    fun otherRequestsGetAnErrorAndMessagesAreLeftAlone() = runTest {
        val sent = Sent()
        val relay = PushRelay(sent, Fcm(), key, log = {})
        assertTrue(relay.onStanza(iq("get", "sure.im") { element("query", "jabber:iq:version") }))
        assertEquals("service-unavailable", sent.stanzas.single().child("error", CLIENT)!!.elements.single().name)
        assertEquals(false, relay.onStanza(element("message", CLIENT) { element("body") { text("hi") } }))

        relay.onStanza(iq("get", "sure.im") { element("query", "http://jabber.org/protocol/disco#info") })
        val identity = sent.stanzas.last().child("query", "http://jabber.org/protocol/disco#info")!!.child("identity")!!
        assertEquals("push", identity.attribute("type"))
    }

    @Test
    fun serviceAccountAssertionsAreSignedJwts() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val keyJson = """{"type":"service_account","project_id":"demo-project","client_email":"relay@demo-project.iam.gserviceaccount.com","private_key":${kotlinx.serialization.json.JsonPrimitive(pem)}}"""
        val tokens = ServiceAccountTokens(keyJson, clock = { 1_000 })
        assertEquals("demo-project", tokens.projectId)
        val (header, claims, signature) = tokens.assertion().split('.')
        val decoded = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(claims))).jsonObject
        assertEquals("relay@demo-project.iam.gserviceaccount.com", decoded["iss"]!!.jsonPrimitive.content)
        assertEquals("https://oauth2.googleapis.com/token", decoded["aud"]!!.jsonPrimitive.content)
        assertEquals("4600", decoded["exp"]!!.jsonPrimitive.content)
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(pair.public)
            update("$header.$claims".toByteArray())
            verify(Base64.getUrlDecoder().decode(signature))
        }
        assertTrue(ok)
        assertNull(decoded["sub"])
    }

    private companion object {
        const val CLIENT = "jabber:client"
        const val COMMANDS = "http://jabber.org/protocol/commands"
        const val PUBSUB = "http://jabber.org/protocol/pubsub"
        const val PUSH = "urn:xmpp:push:0"
        const val DATA_FORMS = "jabber:x:data"
    }
}

