package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Две формы шифрования кадров потока (три категории шуток `ScreenFrameCodec`).
 *
 * key return points:
 * 1. Корневой объект должен находиться в байте 8 - CarPlay 210.81 Старые автомобильные машины не считывают topObject прицепа.
 * берет корень непосредственно по смещению 8 (причина исправления кошачьих игр выше 9ad3a7e);
 * 2. Получение ключа/iv из AES-128-CTR должно быть согласовано с игрой `derive_aes_stream_keys` байт за байтом;
 * Поток ключей   **непрерывный между кадрами** (создание нового экземпляра в каждом кадре приведет к искажению
 * всех символов из второго кадра);
 * 3. Сеансовый ключ для непарного сеанса состоит из всех нулей (условное обозначение `et=NONE`, а не случайное значение).
 */
class StreamFrameCipherTest {
    @Test
    fun encodedPlistRootObjectStartsAtByteEight() {
        // Используйте многобайтовую таблицу смещений только в том случае, если количество объектов > 255 (реальное тело
        // SETUP автомобиля содержит сотни объектов)

        val values = linkedMapOf<String, Any?>()
        repeat(150) { values["field%03d".format(it)] = it.toLong() }
        val data = BinaryPlist.encode(values)

        assertEquals("bplist00", String(data, 0, 8, Charsets.US_ASCII))
        // The root is the dictionary (0xD0 | count); the first byte is the root tag
        assertEquals(0xD0, data[8].toInt() and 0xF0)

        val trailer = data.size - 32
        val width = data[trailer + 6].toInt() and 0xFF
        assertTrue("Несколько объектов должны использовать многобайтовые таблицы смещений.（width=$width）", width > 1)
        val count = readUint(data, trailer + 8, 8).toInt()
        val root = readUint(data, trailer + 16, 8).toInt()
        val table = readUint(data, trailer + 24, 8).toInt()
        val rootOffset = readUint(data, table + root * width, width).toInt()
        assertEquals("CarPlay 210.81 Чтение байтов напрямую 8", 8, rootOffset)

        // После перестановки он все еще должен быть доступен для чтения стандартным парсером (таблица смещений исправлена)
        val decoded = BinaryPlist.parse(data) as Map<*, *>
        assertEquals(150, decoded.size)
        assertEquals(149L, decoded["field149"])
    }

    @Test
    fun smallPlistKeepsRootAtByteEightToo() {
        val data = BinaryPlist.encode(linkedMapOf("a" to 1L, "b" to "x"))
        assertEquals(0xD0, data[8].toInt() and 0xF0)
        assertEquals(1L, (BinaryPlist.parse(data) as Map<*, *>)["a"])
    }

    @Test
    fun aesStreamKeysMatchCatplayDerivation() {
        val sessionKey = ByteArray(16) { (it + 1).toByte() }
        val connectionId = 0x0102_0304_0506_0708L
        val (key, iv) = AirPlayStreamKeys.aesStreamKeys(sessionKey, connectionId)

        fun expected(prefix: String): ByteArray =
            AirPlayCrypto
                .sha512(
                    prefix.toByteArray(Charsets.US_ASCII),
                    "72623859790382856".toByteArray(Charsets.US_ASCII),
                    sessionKey,
                ).copyOfRange(0, 16)

        assertArrayEquals(expected("AirPlayStreamKey"), key)
        assertArrayEquals(expected("AirPlayStreamIV"), iv)
        assertEquals(16, key.size)
        assertEquals(16, iv.size)
    }

    @Test
    fun unpairedSessionUsesZeroSessionKey() {
        assertArrayEquals(ByteArray(16), AirPlayStreamKeys.ZERO_SESSION_KEY)
        val (key, iv) = AirPlayStreamKeys.aesStreamKeys(AirPlayStreamKeys.ZERO_SESSION_KEY, 1L)
        assertEquals(16, key.size)
        assertEquals(16, iv.size)
        // Ключ потока, полученный из нулевого ключа, также не должен состоять из нулей (в противном случае шифрование и
        // дешифрование перейдут в открытый текст).

        assertNotEquals(ByteArray(16).toList(), key.toList())
    }

    @Test
    fun aesCtrKeystreamIsContinuousAcrossFrames() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val iv = ByteArray(16) { (it * 5).toByte() }
        val frameA = ByteArray(152) { it.toByte() }
        val frameB = ByteArray(152) { (it + 7).toByte() }

        // Отправитель: два последовательных кадра одного и того же экземпляра.
        val sender = AirPlayCrypto.Aes128CtrStream(key, iv)
        val wireA = frameA.copyOf().also(sender::applyKeystream)
        val wireB = frameB.copyOf().also(sender::applyKeystream)

        // Получающая сторона: непрерывное дешифрование одним и тем же экземпляром.
        val receiver = AirPlayCrypto.Aes128CtrStream(key, iv)
        val plainA = wireA.copyOf().also(receiver::applyKeystream)
        val plainB = wireB.copyOf().also(receiver::applyKeystream)

        assertArrayEquals(frameA, plainA)
        assertArrayEquals(frameB, plainB)
        // Если по ошибке каждый кадр создается новый экземпляр (сброс ключевого потока), WireB будет равен префиксу WireA.
        assertNotEquals(wireA.take(16), wireB.take(16))
    }

    @Test
    fun chachaStreamKeyMatchesHkdfDerivation() {
        val secret = ByteArray(32) { it.toByte() }
        val key = AirPlayStreamKeys.chachaStreamKey(secret, 42L)
        assertArrayEquals(
            AirPlayCrypto.hkdfSha512(
                secret,
                "DataStream-Salt42".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            ),
            key,
        )
        assertEquals(32, key.size)
    }

    private fun readUint(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xFF) }
        return value
    }
}
