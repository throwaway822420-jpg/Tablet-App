package app.slate.tablet.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class H264Test {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // SPS headers from ffmpeg/libx264 output at these sizes.
    @Test fun readsFrameSizeIncludingCropping() {
        assertEquals(1920 to 1080, H264.spsSize(hex("6742c028da01e0089f970110000003001000000303c8f1832a")))
        assertEquals(2880 to 1800, H264.spsSize(hex("6742c033da00b4038fe5c044000003000400000300f23c60ca80")))
        assertEquals(1366 to 768, H264.spsSize(hex("6742c020da01581879bc0440000003004000000f23c60ca8")))
    }

    @Test fun findsNalUnitsWithBothStartCodeLengths() {
        val au = byteArrayOf(0, 0, 0, 1, 9, 0x10, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 4, 4)
        val nals = H264.nalUnits(au)
        assertEquals(listOf(9, 7, 8, 5), nals.map { H264.type(au, it.first) })
        assertEquals(listOf(2, 3, 2, 3), nals.map { it.second })
        assertEquals(listOf<Byte>(0, 0, 0, 1, 0x67, 1, 2), H264.withStartCode(au, nals[1].first, nals[1].second).toList())
    }
}
