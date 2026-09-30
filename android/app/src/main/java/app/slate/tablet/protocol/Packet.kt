package app.slate.tablet.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Implements protocol/PROTOCOL.md. Keep the two in sync.

object Protocol {
    const val PACKET_SIZE = 32
    const val VERSION = 1

    const val DISCOVERY_PORT = 47810
    const val WIFI_PORT = 47811
    const val USB_PORT = 47812

    const val NO_PAIR_CODE = 0xFFFF
    const val NO_RTT = 0xFFFF
    const val MAX_PRESSURE = 1024

    const val HELLO_NAME_BYTES = 14
    const val CONFIG_NAME_BYTES = 14
    const val DISCOVER_NAME_BYTES = 16
}

object PacketType {
    const val HELLO = 1
    const val PEN = 2
    const val LEAVE = 3
    const val CONFIG = 4
    const val PING = 5
    const val PONG = 6
    const val DISCOVER = 7
    const val BYE = 8
}

object PenFlags {
    const val CONTACT = 1 shl 0
    const val IN_RANGE = 1 shl 1
    const val BARREL = 1 shl 2
    const val HAS_ROTATION = 1 shl 3
    const val HAS_TILT = 1 shl 4
}

object PenTool {
    const val PEN = 0
    const val ERASER = 1
}

object ByeReason {
    const val NORMAL = 0
    const val BAD_CODE = 1
    const val BUSY = 2
    const val VERSION_MISMATCH = 3
}

/**
 * One packet. Only the fields belonging to [type] are meaningful. Integers hold the unsigned wire
 * values (u16 as 0..65535, u32 in an Int's bits).
 */
data class Packet(
    val type: Int,
    val version: Int = Protocol.VERSION,
    val seq: Int = 0,
    val timestamp: Int = 0,
    // PEN
    val tool: Int = PenTool.PEN,
    val flags: Int = 0,
    val pressure: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val tiltX: Int = 0,
    val tiltY: Int = 0,
    val rotation: Int = 0,
    // HELLO
    val surfaceWidth: Int = 0,
    val surfaceHeight: Int = 0,
    val pairCode: Int = Protocol.NO_PAIR_CODE,
    // HELLO, CONFIG, DISCOVER
    val name: String = "",
    // CONFIG
    val aspect: Float = 0f,
    // PING
    val lastRttMs: Int = Protocol.NO_RTT,
    // PONG
    val echoTimestamp: Int = 0,
    // DISCOVER
    val udpPort: Int = 0,
    // BYE
    val reason: Int = ByeReason.NORMAL,
) {
    val contact: Boolean get() = flags and PenFlags.CONTACT != 0
    val inRange: Boolean get() = flags and (PenFlags.IN_RANGE or PenFlags.CONTACT) != 0

    fun encode(): ByteArray {
        val out = ByteArray(Protocol.PACKET_SIZE)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, type.toByte())
        b.put(1, version.toByte())
        b.putInt(4, seq)
        b.putInt(28, timestamp)
        when (type) {
            PacketType.HELLO -> {
                b.putShort(8, surfaceWidth.toShort())
                b.putShort(10, surfaceHeight.toShort())
                b.putShort(12, pairCode.toShort())
                putName(out, 14, Protocol.HELLO_NAME_BYTES, name)
            }
            PacketType.PEN -> {
                b.put(8, tool.toByte())
                b.put(9, flags.toByte())
                b.putShort(10, pressure.toShort())
                b.putFloat(12, x)
                b.putFloat(16, y)
                b.put(20, tiltX.toByte())
                b.put(21, tiltY.toByte())
                b.putShort(22, rotation.toShort())
            }
            PacketType.CONFIG -> {
                b.putFloat(8, aspect)
                putName(out, 14, Protocol.CONFIG_NAME_BYTES, name)
            }
            PacketType.PING -> b.putShort(8, lastRttMs.toShort())
            PacketType.PONG -> b.putInt(8, echoTimestamp)
            PacketType.DISCOVER -> {
                b.putShort(8, udpPort.toShort())
                putName(out, 12, Protocol.DISCOVER_NAME_BYTES, name)
            }
            PacketType.BYE -> b.put(8, reason.toByte())
        }
        return out
    }

    companion object {
        /** Decodes a packet, or returns null if it's short or of an unknown type. */
        fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Packet? {
            if (length < Protocol.PACKET_SIZE) return null
            val b = ByteBuffer.wrap(data, offset, Protocol.PACKET_SIZE).slice().order(ByteOrder.LITTLE_ENDIAN)
            val type = b.get(0).toInt() and 0xFF
            if (type !in PacketType.HELLO..PacketType.BYE) return null
            val base = Packet(
                type = type,
                version = b.get(1).toInt() and 0xFF,
                seq = b.getInt(4),
                timestamp = b.getInt(28),
            )
            fun u16(at: Int) = b.getShort(at).toInt() and 0xFFFF
            return when (type) {
                PacketType.HELLO -> base.copy(
                    surfaceWidth = u16(8),
                    surfaceHeight = u16(10),
                    pairCode = u16(12),
                    name = getName(data, offset + 14, Protocol.HELLO_NAME_BYTES),
                )
                PacketType.PEN -> base.copy(
                    tool = b.get(8).toInt() and 0xFF,
                    flags = b.get(9).toInt() and 0xFF,
                    pressure = u16(10),
                    x = b.getFloat(12),
                    y = b.getFloat(16),
                    tiltX = b.get(20).toInt(),
                    tiltY = b.get(21).toInt(),
                    rotation = u16(22),
                )
                PacketType.CONFIG -> base.copy(
                    aspect = b.getFloat(8),
                    name = getName(data, offset + 14, Protocol.CONFIG_NAME_BYTES),
                )
                PacketType.PING -> base.copy(lastRttMs = u16(8))
                PacketType.PONG -> base.copy(echoTimestamp = b.getInt(8))
                PacketType.DISCOVER -> base.copy(
                    udpPort = u16(8),
                    name = getName(data, offset + 12, Protocol.DISCOVER_NAME_BYTES),
                )
                PacketType.BYE -> base.copy(reason = b.get(8).toInt() and 0xFF)
                else -> base
            }
        }

        /** UTF-8, truncated on a character boundary, NUL-padded. */
        private fun putName(out: ByteArray, at: Int, max: Int, name: String) {
            var used = 0
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                if (used + bytes.size > max) break
                bytes.copyInto(out, at + used)
                used += bytes.size
                i += Character.charCount(cp)
            }
        }

        private fun getName(data: ByteArray, at: Int, max: Int): String {
            var end = 0
            while (end < max && data[at + end] != 0.toByte()) end++
            return String(data, at, end, Charsets.UTF_8)
        }
    }
}
