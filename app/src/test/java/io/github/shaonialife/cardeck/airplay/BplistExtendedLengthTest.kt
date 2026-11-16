package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Расширенная длина  (0x…F + объект int) должна интерпретироваться как беззнаковая.
 *
 * Фактический источник измерений   (20 октября 2026 г.): DiPlay поставляет 0x5000 с конвертом iAPSendMessage, длина данных 182 байт.
 * (0xB6) — старая реализация readSigned читает -74, copyOfRange выдает `IllegalArgumentException:
 * 51 > -23`, вся команда отбрасывается; включены все длины ≥128.
 */
class BplistExtendedLengthTest {
    @Test
    fun extendedDataLengthAbove127Parses() {
        val data = ByteArray(200) { (it and 0xFF).toByte() }
        // Ручное кодирование: {"data": <200B>} (стандартная раскладка: расширенная длина 0x4F 0x10 0xC8)
        val objects = ArrayList<ByteArray>()
        objects.add(byteArrayOf(0xD1.toByte(), 1, 2)) // rootdict(1)
        objects.add(byteArrayOf(0x54.toByte()) + "data".toByteArray()) // 1
        objects.add(byteArrayOf(0x4F.toByte(), 0x10, 200.toByte()) + data) // 2
        var body = "bplist00".toByteArray()
        val offsets = ArrayList<Int>()
        for (o in objects) {
            offsets.add(body.size)
            body += o
        }
        val table = body.size
        for (off in offsets) body += byteArrayOf(off.toByte())
        val trailer = ByteArray(32)
        trailer[6] = 1
        trailer[7] = 1

        fun put(
            at: Int,
            v: Long,
        ) = repeat(8) { trailer[at + it] = ((v shr ((7 - it) * 8)) and 0xFF).toByte() }
        put(8, objects.size.toLong())
        put(16, 0L)
        put(24, table.toLong())
        body += trailer

        val parsed = BinaryPlist.parse(body) as Map<*, *>
        val blob = parsed["data"] as ByteArray
        assertEquals(200, blob.size)
        assertEquals(0.toByte(), blob[0])
        assertEquals(199.toByte(), blob[199])
    }

    @Test
    fun extendedAsciiStringLengthAbove127Parses() {
        val text = "x".repeat(150)
        val objects = ArrayList<ByteArray>()
        objects.add(byteArrayOf(0x5F.toByte(), 0x10, 150.toByte()) + text.toByteArray()) // 0 корень
        var body = "bplist00".toByteArray()
        body += objects[0]
        val table = body.size
        body += byteArrayOf(8) // Таблица смещения: 1 объект
        val trailer = ByteArray(32)
        trailer[6] = 1
        trailer[7] = 1

        fun put(
            at: Int,
            v: Long,
        ) = repeat(8) { trailer[at + it] = ((v shr ((7 - it) * 8)) and 0xFF).toByte() }
        put(8, 1L)
        put(16, 0L)
        put(24, table.toLong())
        body += trailer

        assertEquals(text, BinaryPlist.parse(body))
    }
}
