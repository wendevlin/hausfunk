package dev.wendelin.hausfunk.signaling

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageCodecTest {
    @Test
    fun signalingMessagesRoundTrip() {
        val messages = listOf(
            Hello(PROTOCOL_VERSION, "ab".repeat(32), "Kinderzimmer", "0.2.0", watching = true),
            Hello(PROTOCOL_VERSION, "cd".repeat(32), "Baby", "0.2.0"),
            Offer("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n"),
            Answer("v=0\r\n"),
            Ice("candidate:1 1 udp 2122260223 192.168.1.2 50000 typ host", "0", 0),
            Ice("candidate:2", null, 1),
            Busy,
            Bye,
            Unpair,
            PairCommit("Y29tbWl0"),
            PairReveal("bm9uY2U=", "ef".repeat(32), "Papa"),
            PairConfirm,
            PairAbort,
        )
        for (message in messages) {
            assertEquals(Decoded.Message(message), MessageCodec.decodeSignaling(MessageCodec.encode(message)))
        }
    }

    @Test
    fun controlMessagesRoundTrip() {
        val messages = listOf(Watching(false), Status(80, true, 2, false), Level(-42.5f), Ping, Pong)
        for (message in messages) {
            assertEquals(Decoded.Message(message), MessageCodec.decodeControl(MessageCodec.encode(message)))
        }
    }

    @Test
    fun usesTypeAsDiscriminator() {
        assertEquals("""{"type":"offer","sdp":"x"}""", MessageCodec.encode(Offer("x")))
        assertEquals("""{"type":"bye"}""", MessageCodec.encode(Bye))
    }

    @Test
    fun unknownTypesAreReportedNotFailed() {
        assertEquals(Decoded.Unknown("future"), MessageCodec.decodeSignaling("""{"type":"future","x":1}"""))
        // A control type is unknown on the signaling connection and vice versa.
        assertEquals(Decoded.Unknown("ping"), MessageCodec.decodeSignaling("""{"type":"ping"}"""))
        assertEquals(Decoded.Unknown("offer"), MessageCodec.decodeControl("""{"type":"offer","sdp":"x"}"""))
    }

    @Test
    fun extraFieldsAreIgnored() {
        assertEquals(Decoded.Message(Offer("x")), MessageCodec.decodeSignaling("""{"type":"offer","sdp":"x","new":true}"""))
    }

    @Test
    fun malformedInputIsRejected() {
        for (text in listOf("", "not json", "[]", "42", """{"sdp":"x"}""", """{"type":7}""",
            """{"type":"offer"}""", """{"type":"ice","candidate":"c","sdpMLineIndex":"zero"}""")) {
            assertEquals(Decoded.Malformed, MessageCodec.decodeSignaling(text), text)
        }
    }
}
