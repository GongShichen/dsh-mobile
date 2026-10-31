package io.dsh.mobile.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

object HLFrameCodec {
    const val MAX_FRAME_BYTES = 1024 * 1024
    const val MAX_LOGICAL_MESSAGE_BYTES = 16 * 1024 * 1024
    const val RAW_CHUNK_BYTES = 64 * 1024

    fun encode(value: JsonElement): ByteArray {
        val payload = WireJson.encodeToString(JsonElement.serializer(), value).encodeToByteArray()
        requireLength(payload.size)
        val result = ByteArray(payload.size + 4)
        for (index in 0..3) result[index] = (payload.size ushr (24 - index * 8)).toByte()
        payload.copyInto(result, 4)
        return result
    }

    fun decode(frame: ByteArray): JsonElement {
        if (frame.size < 4) throw HLProtocolException("Truncated frame header")
        val size = payloadLength(frame.copyOfRange(0, 4))
        if (frame.size != size + 4) throw HLProtocolException("Frame length mismatch")
        return decodePayload(frame.copyOfRange(4, frame.size))
    }

    fun payloadLength(header: ByteArray): Int {
        if (header.size != 4) throw HLProtocolException("Truncated frame header")
        val length = header.fold(0L) { n, byte -> (n shl 8) or (byte.toLong() and 255L) }
        if (length !in 1..MAX_FRAME_BYTES.toLong()) throw HLProtocolException("Frame exceeds size limit")
        return length.toInt()
    }

    fun decodePayload(bytes: ByteArray): JsonElement {
        requireLength(bytes.size)
        return parseUtf8Json(bytes)
    }

    private fun parseUtf8Json(bytes: ByteArray): JsonElement =
        try { WireJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) }
        catch (_: Exception) { throw HLProtocolException("Invalid UTF-8 JSON frame") }

    /** Host-to-phone reader. Phone encode() deliberately never emits large request envelopes. */
    suspend fun read(readExact: suspend (Int) -> ByteArray): JsonElement {
        val length = payloadLength(readExact(4))
        val payload = readExact(length)
        if (payload.size != length) throw HLProtocolException("EOF during frame")
        val value = decodePayload(payload)
        val header = value as? JsonObject ?: return value
        if ((header["type"] as? JsonPrimitive)?.contentOrNull != "json-chunks") return value
        val version = header["v"] as? JsonPrimitive
        if (version == null || version.isString || version.longOrNull != PROTOCOL_VERSION.toLong()) throw HLProtocolException("Unsupported large JSON envelope version")
        val declared = header["length"] as? JsonPrimitive
        val logicalLength = declared?.takeUnless { it.isString }?.longOrNull ?: throw HLProtocolException("Invalid large JSON length")
        if (logicalLength > MAX_LOGICAL_MESSAGE_BYTES) throw HLLogicalMessageTooLargeException()
        if (logicalLength <= MAX_FRAME_BYTES) throw HLProtocolException("Large JSON envelope requires more than 1 MiB")
        val message = ByteArray(logicalLength.toInt())
        var offset = 0
        while (offset < message.size) {
            val count = minOf(RAW_CHUNK_BYTES, message.size - offset)
            val chunk = readExact(count)
            if (chunk.size != count) throw HLProtocolException("EOF during large JSON message; a new snapshot is required")
            chunk.copyInto(message, offset)
            offset += count
        }
        return parseUtf8Json(message)
    }

    private fun requireLength(length: Int) {
        if (length !in 1..MAX_FRAME_BYTES) throw HLProtocolException("Frame exceeds size limit")
    }
}
