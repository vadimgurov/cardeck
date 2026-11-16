package io.github.shaonialife.cardeck.audio

import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * аудио-тест RTP-пакета с обратной связью: формат зашифрованной линии должен быть восстановлен побайтно с
 * помощью «пути расшифровки принимающей стороны»
 * (Сравните DiPlay `AudioStream.kt`: AAD=head 4..12, зашифрованный текст=12..n-8, nonce=tail 8B с прямым порядком байтов).
 */
class AudioSenderTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }

    private fun sender() =
        AudioSender(
            host = "127.0.0.1",
            dataPort = 5000,
            streamKey = key,
            log = {},
        )

    @Test
    fun headerFieldsMatchCarPlayRtpConvention() {
        val pcm = ByteArray(AudioSender.PACKET_BYTES) { it.toByte() }
        val wire = sender().sealPacket(pcm, sequence = 0x1234, timestamp = 512, nonceCounter = 0)

        // версия=2; PT=100 (catplay напрямую использует тип потока в качестве PT)
        assertEquals(0x80, wire[0].toInt() and 0xFF)
        assertEquals(100, wire[1].toInt() and 0x7F)
        // последовательность БЭ
        assertEquals(0x12, wire[2].toInt() and 0xFF)
        assertEquals(0x34, wire[3].toInt() and 0xFF)
        // временная метка BE = 512
        assertEquals(0, wire[4].toInt() and 0xFF)
        assertEquals(0, wire[5].toInt() and 0xFF)
        assertEquals(2, wire[6].toInt() and 0xFF)
        assertEquals(0, wire[7].toInt() and 0xFF)
        // Общая длина = 12 заголовков + обычный текст + 16 тегов + 8 одноразовых номеров.
        assertEquals(12 + pcm.size + 16 + 8, wire.size)
    }

    @Test
    fun receiverDecryptPathRestoresPcm() {
        val pcm = ByteArray(AudioSender.PACKET_BYTES) { ((it * 13) and 0xFF).toByte() }
        val wire = sender().sealPacket(pcm, sequence = 7, timestamp = 1024, nonceCounter = 42)

        // —— Репликация расшифровки приемника (DiPlay AudioStream.runData + путь AndroidMediaSink LPCM) ——
        val aad = wire.copyOfRange(4, 12)
        val sealedEnd = wire.size - 8
        val sealed = wire.copyOfRange(12, sealedEnd)
        val shortNonce = wire.copyOfRange(sealedEnd, wire.size)
        val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }

        val decrypted = AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
        // Формат строки — **big endian s16** (принимающая сторона преобразует byteSwapS16 обратно в прямой порядок
        // байтов для подачи AudioTrack):

        // Результат расшифровки должен быть равен исходному PCM в обратном порядке и будет соответствовать исходному PCM после обмена.
        val receiverPcm = AudioSender.bigEndianS16(decrypted) // Приемник byteSwapS16 (подкачка — операция инволюции)
        assertArrayEquals(pcm, receiverPcm)
    }

    @Test
    fun wirePayloadIsByteSwappedRelativeToCapture() {
        val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56) // Образцы с прямым порядком байтов 0x1234, 0x5678
        val wire = sender().sealPacket(pcm, sequence = 0, timestamp = 0, nonceCounter = 0)
        val decrypted =
            AirPlayCrypto.chachaOpen(
                key,
                ByteArray(12).also { wire.copyOfRange(wire.size - 8, wire.size).copyInto(it, 4) },
                wire.copyOfRange(12, wire.size - 8),
                wire.copyOfRange(4, 12),
            )
        // Строка должна быть с прямым порядком байтов: 0x12 0x34 0x56 0x78
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), decrypted)
    }

    @Test
    fun distinctNoncesProduceDistinctCiphertext() {
        val pcm = ByteArray(AudioSender.PACKET_BYTES)
        val first = sender().sealPacket(pcm, 0, 0, nonceCounter = 0)
        val second = sender().sealPacket(pcm, 0, 0, nonceCounter = 1)
        val cipherA = first.copyOfRange(12, first.size - 8)
        val cipherB = second.copyOfRange(12, second.size - 8)
        // Открытый текст тот же, но nonce увеличивается: зашифрованный текст должен быть другим (чтобы предотвратить
        // повторное использование nonce).

        org.junit.Assert.assertFalse(cipherA.contentEquals(cipherB))
    }
}
