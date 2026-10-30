package io.dsh.mobile.protocol

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class ProtocolTest {
    @Test fun frameRoundTripsUnicodeAndCountsBytes() {
        val original = buildJsonObject { put("text", "你好，📱") }
        val frame = FrameCodec.encode(original)
        assertEquals(frame.size - 4, FrameCodec.payloadLength(frame.copyOfRange(0, 4)))
        assertEquals(original, FrameCodec.decode(frame))
    }

    @Test fun hostileFrameLengthsAndTruncationAreRejected() {
        assertFailsWith<ProtocolException> { FrameCodec.payloadLength(byteArrayOf(-1, -1, -1, -1)) }
        assertFailsWith<ProtocolException> { FrameCodec.payloadLength(byteArrayOf(0, 0, 0, 0)) }
        assertFailsWith<ProtocolException> { FrameCodec.decode(byteArrayOf(0, 0, 0, 3, 123, 125)) }
        assertFailsWith<ProtocolException> { FrameCodec.decode(byteArrayOf(0, 0, 0, 2, -1, -1)) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun invitation(version: Int = 1, expiry: Long = 5000): String {
        val json = buildJsonObject {
            put("v", version); put("endpointId", "0123456789abcdef".repeat(4)); put("ticket", "endpoint-ticket")
            put("pairingId", "aabbccdd"); put("secret", Base64.UrlSafe.encode(ByteArray(32) { it.toByte() }))
            put("expiresAt", expiry); put("name", "My Mac")
        }
        return "dsh-mobile://pair?data=" + Base64.UrlSafe.encode(json.toString().encodeToByteArray())
    }

    @Test fun pairingRequiresCurrentProtocolAndUnexpiredInvite() {
        val parsed = PairingInvitation.parse(invitation(), 1000)
        assertEquals("My Mac", parsed.name)
        assertFalse(parsed.toString().contains(parsed.secret))
        assertFailsWith<ProtocolException> { PairingInvitation.parse(invitation(expiry = 1000), 1000) }
        assertFailsWith<ProtocolException> { PairingInvitation.parse(invitation(version = 2), 1000) }
        assertFailsWith<ProtocolException> { PairingInvitation.parse(invitation() + "&data=duplicate", 1000) }
        assertFailsWith<ProtocolException> { PairingInvitation.parse("https://example.com/pair?data=secret", 0) }
    }

    @Test fun upstreamNamespaceCannotBeCalledThroughMobileAllowlist() {
        MobileMethods.requireAllowed("sessions.prompt")
        assertFailsWith<ProtocolException> { MobileMethods.requireAllowed("shell/execute") }
    }

    @Test fun largeUnicodeMessageReassemblesBeforeReadingTheNextOrdinaryFrame() = runTest {
        // Seven UTF-8 bytes per repetition deliberately split characters across 64 KiB reads.
        val expected = buildJsonObject { put("v", 1); put("type", "event"); put("text", "汉📱".repeat(180_000)) }
        val body = expected.toString().encodeToByteArray()
        assertTrue(body.size > FrameCodec.MAX_FRAME_BYTES)
        val tail = buildJsonObject { put("v", 1); put("type", "notice"); put("name", "push-configuration-changed") }
        val bytes = largeHeader(body.size) + body + FrameCodec.encode(tail)
        var offset = 0
        val sizes = mutableListOf<Int>()
        val read: suspend (Int) -> ByteArray = { size ->
            sizes += size
            bytes.copyOfRange(offset, offset + size).also { offset += size }
        }
        assertEquals(expected, FrameCodec.read(read))
        assertEquals(tail, FrameCodec.read(read))
        assertEquals(bytes.size, offset)
        assertTrue(sizes.all { it <= FrameCodec.RAW_CHUNK_BYTES })
        assertFailsWith<ProtocolException> { FrameCodec.encode(expected) }
    }

    @Test fun oversizeLogicalHeaderIsRejectedBeforeReadingOrAllocatingItsBody() = runTest {
        val header = largeHeader(FrameCodec.MAX_LOGICAL_MESSAGE_BYTES + 1)
        var offset = 0
        var calls = 0
        assertFailsWith<LogicalMessageTooLargeException> {
            FrameCodec.read { size ->
                calls++
                header.copyOfRange(offset, offset + size).also { offset += size }
            }
        }
        assertEquals(2, calls)
        assertEquals(header.size, offset)
    }

    @Test fun exactlySixteenMiBIsAValidLogicalMessage() = runTest {
        val body = ByteArray(FrameCodec.MAX_LOGICAL_MESSAGE_BYTES) { 97 }
        body[0] = 34; body[body.lastIndex] = 34
        val header = largeHeader(body.size)
        var headerOffset = 0
        var bodyOffset = 0
        val value = FrameCodec.read { size ->
            if (headerOffset < header.size) header.copyOfRange(headerOffset, headerOffset + size).also { headerOffset += size }
            else body.copyOfRange(bodyOffset, bodyOffset + size).also { bodyOffset += size }
        }
        assertEquals(FrameCodec.MAX_LOGICAL_MESSAGE_BYTES - 2, value.jsonPrimitive.content.length)
        assertEquals(body.size, bodyOffset)
    }

    @Test fun truncatedLargeBodyNeverBecomesAPartialJsonMessage() = runTest {
        val bytes = largeHeader(FrameCodec.MAX_FRAME_BYTES + 1) + ByteArray(70_000) { 32 }
        var offset = 0
        assertFailsWith<ProtocolException> {
            FrameCodec.read { size ->
                bytes.copyOfRange(offset, minOf(bytes.size, offset + size)).also { offset += it.size }
            }
        }
    }

    @Test fun chunkEnvelopeRequiresNumericVersionAndAnIntegerLargeLength() = runTest {
        val invalidHeaders = listOf(
            buildJsonObject { put("v", 2); put("type", "json-chunks"); put("length", FrameCodec.MAX_FRAME_BYTES + 1) },
            buildJsonObject { put("v", "1"); put("type", "json-chunks"); put("length", FrameCodec.MAX_FRAME_BYTES + 1) },
            buildJsonObject { put("v", 1); put("type", "json-chunks"); put("length", "1048577") },
            buildJsonObject { put("v", 1); put("type", "json-chunks"); put("length", 1048577.5) },
            buildJsonObject { put("v", 1); put("type", "json-chunks"); put("length", -1) },
            buildJsonObject { put("v", 1); put("type", "json-chunks"); put("length", FrameCodec.MAX_FRAME_BYTES) },
        )
        for (invalid in invalidHeaders) {
            val bytes = FrameCodec.encode(invalid)
            var offset = 0
            assertFailsWith<ProtocolException> { FrameCodec.read { size -> bytes.copyOfRange(offset, offset + size).also { offset += size } } }
            assertEquals(bytes.size, offset)
        }
    }

    @Test fun invalidUtf8AndCancellationInALargeBodyAreNotSwallowed() = runTest {
        val invalidBody = ByteArray(FrameCodec.MAX_FRAME_BYTES + 1) { 32 }
        invalidBody[0] = 34; invalidBody[invalidBody.lastIndex] = 34
        invalidBody[invalidBody.lastIndex - 1] = -1
        val bytes = largeHeader(invalidBody.size) + invalidBody
        var offset = 0
        assertFailsWith<ProtocolException> { FrameCodec.read { size -> bytes.copyOfRange(offset, offset + size).also { offset += size } } }
        offset = 0
        assertFailsWith<CancellationException> {
            FrameCodec.read { size ->
                if (offset > 1024) throw CancellationException("cancelled")
                bytes.copyOfRange(offset, offset + size).also { offset += size }
            }
        }
    }

    private fun largeHeader(size: Int): ByteArray = FrameCodec.encode(buildJsonObject {
        put("v", 1); put("type", "json-chunks"); put("length", size)
    })
}
