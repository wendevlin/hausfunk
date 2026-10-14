package dev.wendelin.hausfunk.signaling

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Result of decoding one received message. */
sealed interface Decoded<out T> {
    data class Message<T>(val message: T) : Decoded<T>

    /** A well-formed message of a type this version does not know: ignore it. */
    data class Unknown(val type: String) : Decoded<Nothing>

    /** Not valid JSON, no `type`, or a known type with invalid content. */
    data object Malformed : Decoded<Nothing>
}

/** JSON encoding of [SignalingMessage] and [ControlMessage], with `type` as the discriminator. */
object MessageCodec {
    private val json = Json {
        classDiscriminator = "type"
        // Newer peers may add fields; extra fields are not an error.
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val signalingTypes = typesOf(SignalingMessage.serializer())
    private val controlTypes = typesOf(ControlMessage.serializer())

    fun encode(message: SignalingMessage): String = json.encodeToString(SignalingMessage.serializer(), message)

    fun encode(message: ControlMessage): String = json.encodeToString(ControlMessage.serializer(), message)

    fun decodeSignaling(text: String): Decoded<SignalingMessage> =
        decode(text, SignalingMessage.serializer(), signalingTypes)

    fun decodeControl(text: String): Decoded<ControlMessage> =
        decode(text, ControlMessage.serializer(), controlTypes)

    private fun <T : Any> decode(text: String, serializer: KSerializer<T>, known: Set<String>): Decoded<T> {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return Decoded.Malformed
        } catch (e: IllegalArgumentException) {
            return Decoded.Malformed
        }
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Decoded.Malformed
        if (type !in known) return Decoded.Unknown(type)
        return try {
            Decoded.Message(json.decodeFromJsonElement(serializer, obj))
        } catch (e: SerializationException) {
            Decoded.Malformed
        } catch (e: IllegalArgumentException) {
            Decoded.Malformed
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun typesOf(serializer: KSerializer<*>): Set<String> {
        // A sealed serializer's descriptor lists its subclasses' serial names as element 1.
        val subclasses = serializer.descriptor.getElementDescriptor(1)
        return (0 until subclasses.elementsCount).map { subclasses.getElementName(it) }.toSet()
    }
}
