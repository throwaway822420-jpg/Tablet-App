package app.slate.tablet.mirror

/** Just enough H.264 to set up a hardware decoder: NAL scanning and the SPS frame size. */
object H264 {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** (offset of NAL header byte, length) for each NAL unit in Annex-B data. */
    fun nalUnits(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Pair<Int, Int>> {
        val end = offset + length
        val starts = ArrayList<Int>()
        var i = offset
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { n, s ->
            var e = if (n + 1 < starts.size) starts[n + 1] - 3 else end
            if (n + 1 < starts.size && e > s && data[e - 1].toInt() == 0) e-- // 4-byte start code of the next NAL
            s to (e - s)
        }
    }

    fun type(data: ByteArray, nalOffset: Int) = data[nalOffset].toInt() and 0x1F

    /** The NAL unit with a start code prepended, as MediaCodec wants for csd-0 / csd-1. */
    fun withStartCode(data: ByteArray, nalOffset: Int, nalLength: Int): ByteArray =
        byteArrayOf(0, 0, 0, 1) + data.copyOfRange(nalOffset, nalOffset + nalLength)

    /** Picture size from an SPS NAL unit (header byte included), after cropping. */
    fun spsSize(sps: ByteArray): Pair<Int, Int> {
        val r = BitReader(unescape(sps, 1))
        val profile = r.bits(8)
        r.bits(16) // constraint flags, level
        r.ue() // seq_parameter_set_id
        var chroma = 1
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue()
            if (chroma == 3) r.bits(1)
            r.ue(); r.ue() // bit depths
            r.bits(1)
            if (r.bits(1) == 1) { // scaling matrices
                for (i in 0 until if (chroma != 3) 8 else 12) {
                    if (r.bits(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.bits(1); r.se(); r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue(); r.bits(1) // max refs, gaps
        val wMbs = r.ue() + 1
        val hMapUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1) // direct_8x8
        var width = wMbs * 16
        var height = (2 - frameMbsOnly) * hMapUnits * 16
        if (r.bits(1) == 1) { // cropping
            val l = r.ue(); val rr = r.ue(); val t = r.ue(); val b = r.ue()
            val cropX = if (chroma == 0 || chroma == 3) 1 else 2
            val cropY = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
            width -= cropX * (l + rr)
            height -= cropY * (t + b)
        }
        return width to height
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    /** Removes emulation-prevention bytes (00 00 03 → 00 00). */
    private fun unescape(nal: ByteArray, from: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (i in from until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(val d: ByteArray) {
        var pos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = if (pos / 8 < d.size) d[pos / 8].toInt() and 0xFF else 0
                v = (v shl 1) or ((byte shr (7 - pos % 8)) and 1)
                pos++
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0 && zeros < 32) zeros++
            return (1 shl zeros) - 1 + bits(zeros)
        }
        fun se(): Int {
            val k = ue()
            return if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}
