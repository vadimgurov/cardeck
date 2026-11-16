package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одиночный тест головки ADTS и переключения аудиорешений.
 *
 * Предыстория: Когда автомобильная машина (Honda HFT/Alpine 8A250-30A) использует тип = 102 + AAC-LC, она может подключаться, потреблять,
 * не имеет звука». Остающееся отличие, которое невозможно различить по журналам, — это инкапсуляция полезной
 * нагрузки (голый кадр или ADTS). Вот заголовок ADTS
 * Каждый бит   исправлен, чтобы не перепутать «ошибку записи пакета» с «автомобиль и машина не распознают ее» во
 * время реальных испытаний автомобиля.
 */
class AacLcEncoderTest {
    /** Проанализируйте длину 13-битного кадра из заголовка кадра. */
    private fun frameLength(header: ByteArray): Int =
        ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xFF) shr 5)

    /** Анализ конфигурации канала из заголовка кадра. */
    private fun channelConfig(header: ByteArray): Int = ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() and 0xFF) shr 6)

    @Test
    fun adtsHeaderFor44100Stereo() {
        val payload = 592
        val header = AacLcEncoder.adtsHeader(payload, 44_100, 2)

        assertEquals(7, header.size)
        // Слово синхронизации FFF + MPEG-4 + уровень 0 + Protection_absent=1
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xF1, header[1].toInt() and 0xFF)
        // профиль = AAC-LC (хранилище AOT-1 = 1)
        assertEquals(1, (header[2].toInt() and 0xFF) shr 6)
        // Индекс частоты дискретизации: 44100 → 4
        assertEquals(4, ((header[2].toInt() and 0xFF) shr 2) and 0x0F)
        // Конфигурация каналов: Стерео → 2
        assertEquals(2, channelConfig(header))
        // Длина кадра (включая 7-байтовый заголовок)
        assertEquals(payload + 7, frameLength(header))
        // buffer_fullness = 0x7FF (11 бит: младшие 5 бит h5 + старшие 6 бит h6), num_raw_data_blocks = 0
        assertEquals(
            0x7FF,
            ((header[5].toInt() and 0x1F) shl 6) or ((header[6].toInt() and 0xFC) shr 2),
        )
        assertEquals(0, header[6].toInt() and 0x03)
    }

    @Test
    fun adtsHeaderTracksSampleRateAndChannels() {
        // 48к → индекс 3; моно → 1
        val header = AacLcEncoder.adtsHeader(100, 48_000, 1)
        assertEquals(3, ((header[2].toInt() and 0xFF) shr 2) and 0x0F)
        assertEquals(1, channelConfig(header))
        assertEquals(107, frameLength(header))
    }

    @Test
    fun frequencyIndexTable() {
        assertEquals(4, AacLcEncoder.frequencyIndex(44_100))
        assertEquals(3, AacLcEncoder.frequencyIndex(48_000))
        assertEquals(8, AacLcEncoder.frequencyIndex(16_000))
        // Не включенная частота дискретизации возвращается к 44100, чтобы избежать записи недопустимых индексов.
        assertEquals(4, AacLcEncoder.frequencyIndex(37_000))
    }

    @Test
    fun tuningIsFixedToRawFrames() {
        // Доработано: пустой кадр AAC + spf=1024 + временная метка прибытия, только эта группа, переключение не предусмотрено.
        assertEquals(1, AudioTuning.entries.size)
        assertTrue(!AudioTuning.RAW_SPF.adts)
        assertEquals(1024, AudioTuning.RAW_SPF.spf)
        assertTrue(!AudioTuning.RAW_SPF.highAccuracyTimestamps)
    }
}
