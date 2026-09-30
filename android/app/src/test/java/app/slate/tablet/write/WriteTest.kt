package app.slate.tablet.write

import app.slate.tablet.protocol.Packet
import app.slate.tablet.protocol.PacketType
import app.slate.tablet.protocol.Protocol
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteTest {
    @Test fun textPacketsRoundTrip() {
        val p = Packet(PacketType.TEXT, textId = 513, chunkIndex = 2, chunkCount = 3, chunk = "x²+ √2 ≤ π")
        assertEquals(p, Packet.decode(p.encode()))
        val ack = Packet(PacketType.TEXT_ACK, textId = 65535)
        assertEquals(ack, Packet.decode(ack.encode()))
    }

    @Test fun chunksNeverSplitCharactersAndRejoin() {
        val text = "The area is πr² and ∫₀¹ x² dx = ⅓ 😀 done"
        val chunks = Protocol.textChunks(text)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= Protocol.TEXT_CHUNK_BYTES })
        assertEquals(text, chunks.joinToString(""))
        assertEquals(chunks, chunks.map { Packet.decode(Packet(PacketType.TEXT, chunk = it).encode())!!.chunk })
        assertTrue(Protocol.textChunks("").isEmpty())
        assertEquals(Protocol.MAX_TEXT_CHUNKS, Protocol.textChunks("a".repeat(10_000)).size)
    }

    @Test fun inkVersionsChangeOnEveryEdit() {
        val ink = Ink()
        val versions = mutableListOf(ink.version)
        ink.begin(0f, 0f); ink.extend(10f, 0f); ink.end(); versions += ink.version
        ink.begin(100f, 100f); ink.end(); versions += ink.version
        assertTrue(ink.eraseNear(5f, 1f, 3f)); versions += ink.version
        assertFalse(ink.eraseNear(500f, 500f, 3f))
        ink.undo(); versions += ink.version
        assertTrue(ink.isEmpty)
        assertEquals(versions.distinct(), versions)
    }

    @Test fun snapshotIsIndependent() {
        val ink = Ink()
        ink.begin(1f, 2f); ink.end()
        val snap = ink.snapshot()
        ink.clear()
        assertEquals(1, snap.size)
        assertEquals(2f, snap[0].y(0), 0f)
    }

    @Test fun imageGeometry() {
        val s = Stroke().apply { add(100f, 200f); add(1100f, 400f) }
        assertArrayEquals(floatArrayOf(76f, 176f, 1124f, 424f), InkGeometry.bounds(listOf(s)), 0f)
        assertNull(InkGeometry.bounds(emptyList()))
        assertEquals(0.5f, InkGeometry.scale(2800f, 600f), 1e-4f) // big writing shrinks to 1400 px
        assertEquals(1f, InkGeometry.scale(1000f, 300f), 0f)
        assertEquals(4f, InkGeometry.scale(80f, 50f), 1e-4f)      // a tiny "x" is enlarged to 320 px
    }

    @Test fun requestUsesSonnet55WithThinkingOffFallbacksAndJsonOutput() {
        val params = Recognizer.request("iVBORw0KGgo=")
        // The SDK's own mapper (Kotlin-internal, so reached by reflection) serializes exactly what goes on the wire.
        val mapper = Class.forName("com.anthropic.core.ObjectMappers").getMethod("jsonMapper").invoke(null) as ObjectMapper
        val body: JsonNode = mapper.valueToTree(params._body())
        assertEquals("claude-sonnet-5-5", body["model"].asText())
        assertEquals("between_tools", body["thinking"]["type"].asText())
        assertEquals(1, body["thinking"].size()) // between_tools must not carry other fields
        assertEquals("default", body["fallbacks"].asText())
        assertEquals("json_schema", body["output_config"]["format"]["type"].asText())
        assertEquals("text", body["output_config"]["format"]["schema"]["required"][0].asText())
        val content = body["messages"][0]["content"]
        assertEquals("image", content[0]["type"].asText())
        assertEquals("image/png", content[0]["source"]["media_type"].asText())
        assertTrue(params._headers().values("anthropic-beta").any { it.contains("server-side-fallback-2026-07-01") })
    }
}
