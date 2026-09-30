package app.slate.tablet.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketTest {
    // Same bytes as the golden vector in protocol/PROTOCOL.md and the Windows ProtocolTests.
    private val golden = intArrayOf(
        0x02, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x13, 0x00, 0x02, 0x00, 0x00, 0x00, 0x3F,
        0x00, 0x00, 0x80, 0x3E, 0xE2, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xE8, 0x03, 0x00, 0x00,
    ).map { it.toByte() }.toByteArray()

    private val goldenPacket = Packet(
        PacketType.PEN, seq = 1, timestamp = 1000, tool = PenTool.PEN,
        flags = PenFlags.CONTACT or PenFlags.IN_RANGE or PenFlags.HAS_TILT,
        pressure = 512, x = 0.5f, y = 0.25f, tiltX = -30, tiltY = 15,
    )

    @Test fun encodesGoldenVector() = assertArrayEquals(golden, goldenPacket.encode())

    @Test fun decodesGoldenVector() = assertEquals(goldenPacket, Packet.decode(golden))

    @Test fun roundTripsEveryType() {
        val packets = listOf(
            Packet(PacketType.HELLO, seq = 7, timestamp = 99, surfaceWidth = 2560, surfaceHeight = 1600, pairCode = 42, name = "SM-X920"),
            Packet(PacketType.HELLO, seq = -1, timestamp = -5, pairCode = Protocol.NO_PAIR_CODE),
            Packet(PacketType.PEN, tool = PenTool.ERASER, flags = PenFlags.IN_RANGE or PenFlags.BARREL, pressure = 1024,
                x = 1f, y = 0f, tiltX = 90, tiltY = -90, rotation = 359),
            Packet(PacketType.LEAVE, seq = 3),
            Packet(PacketType.CONFIG, aspect = 16f / 9f, name = "LAPTOP"),
            Packet(PacketType.PING, lastRttMs = 12),
            Packet(PacketType.PONG, echoTimestamp = 0xDEADBEEF.toInt()),
            Packet(PacketType.DISCOVER, udpPort = 47811, name = "DESKTOP-ABCDEFG"),
            Packet(PacketType.BYE, reason = ByeReason.BAD_CODE),
        )
        for (p in packets) assertEquals(p, Packet.decode(p.encode()))
    }

    @Test fun decodesAtOffset() {
        val buf = ByteArray(40)
        golden.copyInto(buf, 8)
        assertEquals(goldenPacket, Packet.decode(buf, 8, 32))
    }

    @Test fun truncatesNamesOnCharacterBoundary() {
        // 13 ASCII bytes + a 2-byte character doesn't fit in 14 bytes: the character is dropped whole.
        val p = Packet(PacketType.HELLO, name = "ABCDEFGHIJKLMé")
        assertEquals("ABCDEFGHIJKLM", Packet.decode(p.encode())!!.name)
        val emoji = Packet(PacketType.HELLO, name = "Tab 🖊️ S11")
        assertEquals("Tab 🖊️ S1", Packet.decode(emoji.encode())!!.name)
    }

    @Test fun rejectsShortOrUnknown() {
        assertNull(Packet.decode(ByteArray(31)))
        assertNull(Packet.decode(ByteArray(32).also { it[0] = 99 }))
        assertNull(Packet.decode(ByteArray(32)))
    }
}
