package io.github.shaonialife.cardeck.audio

/**
 * AirPlay Audio **Пакет синхронизации** (RTCP PT=84 / Байты 0xD4, отправленный на `controlPort` для этого аудиопотока).
 *
 * Почему это необходимо (20 октября 2026 г. Honda HFT / Alpine 8A250-30A третье актуальное позиционирование):
 * аудио RTP — это всего лишь «образец потока», и принимающая сторона также должна знать соответствие между
 * **временной меткой RTP ↔ настенными часами (NTP)**,
 * может рассчитать, «когда N-я выборка должна быть отправлена в ЦАП». Эта информация находится не в аудиопакете, а в пакете синхронизации.
 * выпускается отдельно (сравните «Неофициальную спецификацию AirPlay» audio/rtp_streams:
 * «Пакеты синхронизации отправляются раз в секунду на порт управления. Они используются для
 * сопоставляет временные метки RTP, используемые в данный момент в аудиопотоке, со временем NTP»).
 *
 * Типичные симптомы отсутствия   — это именно та версия, с которой мы столкнулись: порт SETUP автомобиля, UDP, `/feedback`.
 * `sampleTime` Нажмите 44.1k, чтобы продвинуться до конца (часы идут), но динамик не производит ни одного сэмпла
 * - точка привязки планирования не установлена.
 * Видео   не затрагивается, поскольку каждый видеокадр имеет собственную временную метку рендеринга и не требует
 * привязки к перекрестной тактовой частоте.
 *
 * Формат строки   (20 байт, **без поля SSRC**):
 * ```
 * 0 1 2..3 4..7 8..15 16..19
 * 0x80 0xD4 seq(BE) RTP ts(BE, текущий) NTP(BE) RTP ts(BE, следующий пакет)
 * ```
 * – Байт 0: V=2 (0x80); **Первый пакет (после RECORD/FLUSH) устанавливает бит X = 0x90**
 * — Байт 1: M=1 + PT=84 → 0xD4
 * — байты 8..15: текущее время NTP (секунды<<32 | 2^32 доли), то же, что и [io.github.shaonialife.cardeck.airplay.TimingServer].
 * реагирует на одну и ту же временную базу (монотонные часы) для автомобиля и компьютера, в противном случае
 * точка привязки, рассчитанная автомобилем и компьютером, сместится в целом.
 */
object AudioSyncPacket {
    /** Фиксированная длина пакета синхронизации (спецификация: 8 байт заголовка + 8 байт NTP + 4 байта временной метки следующего пакета). */
    const val SIZE = 20

    /** Тип полезной нагрузки RTP: 84 = синхронизация времени (тип частного RTCP AirPlay). */
    private const val PT_SYNC = 0xD4

    /** Версия 2 + Маркер=1. */
    private const val BASE_FIRST_BYTE = 0x80

    /** Бит расширения (X): должен быть установлен первый пакет синхронизации после RECORD/FLUSH. */
    private const val EXTENSION_BIT = 0x10

    /**
     * Групповой пакет .
     *
     * @param последовательность Порядковый номер самого пакета синхронизации (он не имеет ничего общего с порядковым
     * номером аудио RTP и увеличивается независимо)
     * @param currentRtpTimestamp Временная метка только что отправленного аудиопакета (момент, соответствующий [ntp])
     * @param nextRtpTimestamp Временная метка RTP следующего аудиопакета (= текущий + количество выборок в пакете)
     * @param ntp 8-байтовое время NTP с прямым порядком байтов
     * @param первый Это первый пакет синхронизации после RECORD (установите бит расширения)
     */
    fun build(
        sequence: Int,
        currentRtpTimestamp: Long,
        nextRtpTimestamp: Long,
        ntp: ByteArray,
        first: Boolean,
    ): ByteArray {
        require(ntp.size == 8) { "Время NTP должно быть 8 байт, фактическое значение ${ntp.size}." }
        val packet = ByteArray(SIZE)
        packet[0] = (BASE_FIRST_BYTE or (if (first) EXTENSION_BIT else 0)).toByte()
        packet[1] = PT_SYNC.toByte()
        packet[2] = ((sequence ushr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        writeBe32(packet, 4, currentRtpTimestamp)
        ntp.copyInto(packet, 8)
        writeBe32(packet, 16, nextRtpTimestamp)
        return packet
    }

    private fun writeBe32(
        target: ByteArray,
        offset: Int,
        value: Long,
    ) {
        val truncated = value.toInt()
        target[offset] = ((truncated ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((truncated ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((truncated ushr 8) and 0xFF).toByte()
        target[offset + 3] = (truncated and 0xFF).toByte()
    }
}
