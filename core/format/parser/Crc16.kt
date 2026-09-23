package com.galaxyfit3.core.format.parser

/**
 * CRC-16/CCITT-FALSE — polynomial 0x1021, init 0xFFFF, no reflection, no final XOR.
 * Equivalent to Python's binascii.crc_hqx(data, 0xFFFF).
 */
object Crc16 {
    private val table = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var crc = i shl 8
            repeat(8) { bit ->
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
            table[i] = crc
        }
    }

    fun compute(data: ByteArray, seed: Int = 0xFFFF): Int =
        computeRange(data, 0, data.size, seed)

    /**
     * Compute CRC over the byte range [start, end). Used for the header CRC
     * (covers bytes 0x20..EOF) and for payload CRCs.
     */
    fun computeRange(data: ByteArray, start: Int, end: Int, seed: Int = 0xFFFF): Int {
        var crc = seed and 0xFFFF
        for (i in start until end) {
            val b = data[i].toInt() and 0xFF
            val old = crc
            crc = ((old shl 8) and 0xFFFF) xor (table[((old shr 8) xor b) and 0xFF] and 0xFFFF)
        }
        return crc
    }
}