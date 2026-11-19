package com.stump.recon.rf

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Zero-dependency MessagePack decoder for LXMF 1.3 delivery and propagation announces.
 * Handles fixint, uint/int 8..64, float32/float64 fractional stamp costs, nil, bool,
 * bin/str, fixarray/array16, and fixmap/map16 (PN_META_* integer keys).
 */
object MsgPackDecoder {

    private class Cursor(val data: ByteArray, var pos: Int = 0)

    fun decode(bytes: ByteArray): Any? {
        if (bytes.isEmpty()) return null
        val cur = Cursor(bytes)
        return try {
            readNext(cur)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readNext(c: Cursor): Any? {
        if (c.pos >= c.data.size) return null
        val b = c.data[c.pos++].toInt() and 0xFF
        return when {
            b <= 0x7F -> b
            b >= 0xE0 -> b - 256
            (b and 0xE0) == 0xA0 -> readString(c, b and 0x1F)
            (b and 0xF0) == 0x90 -> readArray(c, b and 0x0F)
            (b and 0xF0) == 0x80 -> readMap(c, b and 0x0F)
            b == 0xC0 -> null
            b == 0xC2 -> false
            b == 0xC3 -> true
            b == 0xC4 -> readBytes(c, readU8(c))
            b == 0xC5 -> readBytes(c, readU16(c))
            b == 0xC6 -> readBytes(c, readU32(c).toInt())
            b == 0xCA -> readFloat32(c)
            b == 0xCB -> readFloat64(c)
            b == 0xCC -> readU8(c)
            b == 0xCD -> readU16(c)
            b == 0xCE -> readU32(c)
            b == 0xCF -> readU64(c)
            b == 0xD0 -> readU8(c).toByte().toInt()
            b == 0xD1 -> readU16(c).toShort().toInt()
            b == 0xD2 -> readU32(c).toInt()
            b == 0xD3 -> readU64(c)
            b == 0xD9 -> readString(c, readU8(c))
            b == 0xDA -> readString(c, readU16(c))
            b == 0xDB -> readString(c, readU32(c).toInt())
            b == 0xDC -> readArray(c, readU16(c))
            b == 0xDD -> readArray(c, readU32(c).toInt())
            b == 0xDE -> readMap(c, readU16(c))
            b == 0xDF -> readMap(c, readU32(c).toInt())
            else -> null
        }
    }

    private fun readU8(c: Cursor): Int {
        if (c.pos >= c.data.size) throw IndexOutOfBoundsException()
        return c.data[c.pos++].toInt() and 0xFF
    }

    private fun readU16(c: Cursor): Int {
        if (c.pos + 2 > c.data.size) throw IndexOutOfBoundsException()
        val v = ((c.data[c.pos].toInt() and 0xFF) shl 8) or (c.data[c.pos + 1].toInt() and 0xFF)
        c.pos += 2
        return v
    }

    private fun readU32(c: Cursor): Long {
        if (c.pos + 4 > c.data.size) throw IndexOutOfBoundsException()
        val v = ((c.data[c.pos].toLong() and 0xFF) shl 24) or
            ((c.data[c.pos + 1].toLong() and 0xFF) shl 16) or
            ((c.data[c.pos + 2].toLong() and 0xFF) shl 8) or
            (c.data[c.pos + 3].toLong() and 0xFF)
        c.pos += 4
        return v
    }

    private fun readU64(c: Cursor): Long {
        if (c.pos + 8 > c.data.size) throw IndexOutOfBoundsException()
        val bb = ByteBuffer.wrap(c.data, c.pos, 8).order(ByteOrder.BIG_ENDIAN)
        c.pos += 8
        return bb.long
    }

    private fun readFloat32(c: Cursor): Double {
        if (c.pos + 4 > c.data.size) throw IndexOutOfBoundsException()
        val bb = ByteBuffer.wrap(c.data, c.pos, 4).order(ByteOrder.BIG_ENDIAN)
        c.pos += 4
        return bb.float.toDouble()
    }

    private fun readFloat64(c: Cursor): Double {
        if (c.pos + 8 > c.data.size) throw IndexOutOfBoundsException()
        val bb = ByteBuffer.wrap(c.data, c.pos, 8).order(ByteOrder.BIG_ENDIAN)
        c.pos += 8
        return bb.double
    }

    private fun readBytes(c: Cursor, len: Int): ByteArray {
        if (len < 0 || c.pos + len > c.data.size) throw IndexOutOfBoundsException()
        val out = c.data.copyOfRange(c.pos, c.pos + len)
        c.pos += len
        return out
    }

    private fun readString(c: Cursor, len: Int): String = String(readBytes(c, len), Charsets.UTF_8)

    private fun readArray(c: Cursor, len: Int): List<Any?> {
        if (len < 0 || len > 256) throw IndexOutOfBoundsException()
        return List(len) { readNext(c) }
    }

    private fun readMap(c: Cursor, len: Int): Map<Any, Any?> {
        if (len < 0 || len > 128) throw IndexOutOfBoundsException()
        val out = LinkedHashMap<Any, Any?>()
        repeat(len) {
            val k = readNext(c) ?: return@repeat
            val v = readNext(c)
            out[k] = v
        }
        return out
    }
}
