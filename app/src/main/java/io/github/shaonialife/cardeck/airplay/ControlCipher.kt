package io.github.shaonialife.cardeck.airplay

/**
 * Кадр шифрования канала управления (побайтовая совместимость с эталонным приемником ControlCipher):
 * на кадр = [2-байтовая длина зашифрованного текста с прямым порядком байтов (для AAD)][зашифрованный текст][16-байтовый тег],
 * nonce = 4-байтовый ноль + 8-байтовый счетчик с прямым порядком байтов. Ключи и счетчики в направлениях
 * отправки и получения увеличиваются независимо.
 *
 * Верхний предел однокадрового открытого текста   составляет **1422** (1440 MTU — 2 байта AAD — тег 16 байт). контролировать кошачью игру
 * `AirPlayCipherCodec`: фрагментирован по принципу «Ethernet 1500 — заголовок IPv6 40 — заголовок TCP 20 = 1440»,
 * , и если сторона декодирования обнаружит len > 1422, это будет напрямую расценено как нарушение протокола**.
 * Старые версии настоящего iPhone используют 1024-байтовую нарезку.
 * Оба   находятся в пределах диапазона приема принимающей стороны; кадры, превышающие 1440, могут быть строго
 * отклонены машиной (старая реализация использует 0x4000,
 * Как только запрос превышает MTU, он будет разделен на недопустимые кадры - 19.10.2026, выровненные по кошачьей игре).
 * Направление чтения   остается расслабленным (длина фрагментов, отправляемых самим транспортным средством,
 * определяется им, и кадры не должны теряться из-за превышения нашего MTU).
 */
class ControlCipher(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
) {
    class Decrypted(
        val data: ByteArray,
        val rest: ByteArray,
    )

    private var readCounter = 0L
    private var writeCounter = 0L

    fun decrypt(buffer: ByteArray): Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            val aad = buffer.copyOfRange(offset, offset + HEADER_SIZE)
            val ciphertextAndTag = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
            output += AirPlayCrypto.chachaOpen(readKey, AirPlayCrypto.nonce64(readCounter), ciphertextAndTag, aad)
            readCounter++
            offset = frameEnd
        }
        val joined = if (output.isEmpty()) ByteArray(0) else output.reduce { acc, bytes -> acc + bytes }
        return Decrypted(joined, buffer.copyOfRange(offset, buffer.size))
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag =
                AirPlayCrypto.chachaSeal(
                    writeKey,
                    AirPlayCrypto.nonce64(writeCounter),
                    chunk,
                    header,
                )
            output += header
            output += ciphertextAndTag
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return output.reduce { acc, bytes -> acc + bytes }
    }

    private fun readU16Le(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16

        /** 1440 MTU — 2B AAD — метка 16B (того же калибра, что и в Catplay AIRPLAY_CIPHER_MTU_MAX). */
        const val MAX_PAYLOAD = 1422
    }
}
