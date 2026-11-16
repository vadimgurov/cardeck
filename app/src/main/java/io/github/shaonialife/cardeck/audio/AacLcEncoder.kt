package io.github.shaonialife.cardeck.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * PCM → кодер AAC-LC (тип = 102 канал MainHighAudio).
 *
 * Зачем он нужен   (20 октября 2026 г., фактический тест Honda HFT / Alpine): Этот автомобиль находится в `/info`.
 * Файл `media`   `type=102` (MainHighAudio) объявляет только `0x400000` = **AAC-LC 44.1k стерео**,
 * не имеет битов PCM; в то время как `type=100 media` декларирует только PCM 44.1k. При нажатии туда PCM машина
 * принимает пакеты, но не потребляет их (`sampleTime` в `/feedback` всегда равен 0), а сессия разрывается примерно через 6,5 секунд.
 * Настоящий мультимедийный звук iPhone использует формат 102 + AAC-LC, поэтому вам придется программировать AAC самостоятельно.
 *
 * Формат строки   (сравните Catplay `aac_encoder.rs` + `rtp_recorder.rs`): Выход энкодера
 * **Прямой кадр AAC** — без заголовка ADTS, без заголовка RFC3640 AU, полезная нагрузка RTP — это сам кадр,
 * Одна рамка в упаковке. Кодировщик AAC Android `MediaCodec` также выводит голые кадры и может использоваться напрямую.
 *
 * Длина кадра   фиксирована и составляет 1024 выборки (спецификация AAC-LC), поэтому временная метка RTP увеличивается на 1024 на пакет.
 */
class AacLcEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitRate: Int = 192_000,
    /** Добавлять ли 7-байтовый заголовок ADTS к каждому кадру (см. [AudioTuning.ADTS]). */
    private val wrapAdts: Boolean = false,
    private val log: (String) -> Unit,
) {
    companion object {
        /** AAC-LC имеет фиксированный кадр из 1024 выборок (на канал). */
        const val FRAME_SAMPLES = 1024

        /** Количество байт в одном кадре PCM (16 бит). */
        fun pcmBytes(channels: Int): Int = FRAME_SAMPLES * channels * 2

        /** Длина заголовка ADTS (без CRC). */
        private const val ADTS_HEADER_SIZE = 7

        /** audioObjectType в ADTS: AAC-LC = 2 (сохраняется AOT-1). */
        private const val AAC_PROFILE_LC = 2

        private const val MIME = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10_000L

        /**
         * Группа  7-байтовый заголовок ADTS (без CRC): MPEG-4/AAC-LC/соответствующая частота дискретизации и количество каналов.
         *
         * Онлайн-сообщения           (полезная нагрузка RTP) представляют собой **чистые кадры AAC** в соответствии с
         * соглашением Apple, но цепочка декодирования отдельных транспортных средств
         * организован по ADTS (Android `audio/aac-adts`). Если будет подан голый кадр, он будет расценен как отсутствие кадра.
         * Таким образом,           оставляет переключаемый путь упаковки ([AudioTuning.ADTS]).
         */
        fun adtsHeader(
            payloadSize: Int,
            sampleRate: Int,
            channels: Int,
        ): ByteArray {
            val frameLength = payloadSize + ADTS_HEADER_SIZE
            val freqIndex = frequencyIndex(sampleRate)
            val header = ByteArray(ADTS_HEADER_SIZE)
            header[0] = 0xFF.toByte()
            // оставшиеся 4 бита синхворда + MPEG-4(0) + Layer(00) + Protection_absent(1)
            header[1] = 0xF1.toByte()
            // профиль=LC(2) сохраняет AOT-1; индекс частоты дискретизации <<2; старшие 2 бита конфигурации канала
            header[2] =
                (
                    ((AAC_PROFILE_LC - 1) shl 6) or
                        ((freqIndex and 0x0F) shl 2) or
                        ((channels shr 2) and 0x03)
                ).toByte()
            // Конфигурация канала, младшие 2 бита + длина кадра, старшие 2 бита
            header[3] = (((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
            header[4] = ((frameLength shr 3) and 0xFF).toByte()
            // Длина кадра, младшие 3 бита + buffer_fullness=0x7FF (соглашение VBR) + одиночный кадр (2 младших бита 0)
            header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
            header[6] = 0xFC.toByte()
            return header
        }

        /** sampleFrequencyIndex → sampleFrequencyIndex ADTS. */
        fun frequencyIndex(rate: Int): Int =
            when (rate) {
                96_000 -> 0
                88_200 -> 1
                64_000 -> 2
                48_000 -> 3
                44_100 -> 4
                32_000 -> 5
                24_000 -> 6
                22_050 -> 7
                16_000 -> 8
                12_000 -> 9
                11_025 -> 10
                8_000 -> 11
                7_350 -> 12
                else -> 4
            }

        /**
         * Самотестирование: используйте декодер платформы для декодирования кадра чистого AAC обратно в PCM и возврата
         * `(пик, Количество образцов)`; если не декодировано, верните ноль.
         *
         * Зачем нужен           (третий фактический тест 20 октября 2026 г.): Когда машина и машина «получили посылку,
         * но не издают ни звука», наиболее вероятная ошибка
         * Атрибуция - кодер выдает плохой поток/отключение звука, либо со стороны автомобиля не ведется трансляция. Эта
         * машина может расшифровать его один раз, и его можно подделать:
         * Пиковое значение           > 0 указывает на то, что битовый поток действителен и не отключен, и проблема
         * должна быть на стороне транспортного средства; Пиковое значение = 0 указывает на то, что кодер приглушает звук.
         *
         * Примечание. Здесь не следует выполнять какое-либо переупорядочение байтов кадра (старая реализация помещает
         * все нагрузки в переупорядочение s16 с прямым порядком байтов,
         * даже заменил битовый поток AAC — автомобильный декодер напрямую определяет плохой кадр, что является основной
         * причиной «пакетов, но нет звука»).
         */
        fun decodePeak(
            frame: ByteArray,
            sampleRate: Int,
            channels: Int,
        ): Pair<Int, Int>? {
            var created: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels)
                val codec = MediaCodec.createDecoderByType(MIME)
                created = codec
                codec.configure(format, null, null, 0)
                codec.start()
                val inputIndex = codec.dequeueInputBuffer(200_000L)
                if (inputIndex < 0) return null
                val input = codec.getInputBuffer(inputIndex) ?: return null
                input.clear()
                input.put(frame)
                codec.queueInputBuffer(inputIndex, 0, frame.size, 0L, 0)
                val info = MediaCodec.BufferInfo()
                val deadline = System.nanoTime() + 600_000_000L
                while (System.nanoTime() < deadline) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 60_000L)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER ||
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
                    ) {
                        continue
                    }
                    if (outputIndex < 0) continue
                    val output = codec.getOutputBuffer(outputIndex)
                    var peak = 0
                    var samples = 0
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        while (output.remaining() >= 2) {
                            val value = output.short.toInt()
                            val magnitude = if (value < 0) -value else value
                            if (magnitude > peak) peak = magnitude
                            samples++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (samples > 0) return peak to samples
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { created?.stop() }
                runCatching { created?.release() }
            }
        }
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    /** Удаление ADTS запрашивает только один раз, чтобы избежать пролистывания экрана. */
    private var adtsStripped = false

    /** Приглашение к упаковке ADTS вводится только один раз. */
    private var adtsWrapped = false

    /** Шестнадцатеричное число первого кадра вводится только один раз. */
    private var firstFrameLogged = false

    /** Количество входных байтов PCM на кадр. */
    val inputFrameBytes: Int = pcmBytes(channels)

    var started: Boolean = false
        private set

    fun start(): Boolean {
        if (started) return true
        return try {
            val format =
                MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputFrameBytes)
                }
            val created = MediaCodec.createEncoderByType(MIME)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            codec = created
            presentationUs = 0L
            started = true
            log(
                "АУДИО: готов кодер AAC-LC (${sampleRate}Hz ${channels}ch" +
                    "${bitRate / 1000}кбит/с, кадр=${FRAME_SAMPLES} выборка/${inputFrameBytes}B)",
            )
            true
        } catch (error: Exception) {
            log("АУДИО: не удалось запустить кодер AAC-LC — ${error.message} (это устройство заброшено)")
            started = false
            false
        }
    }

    /**
     * передает кадр PCM (байты [inputFrameBytes]) и возвращает все кадры AAC, скомпилированные на этот раз.
     *
     * В кодировщике       существует внутренняя задержка (первый кадр обычно занимает 1–2 кадра, чтобы передать
     * первый пакет), поэтому возвращаемое значение может быть пустым.
     * Вызывающая сторона может обработать его, «отправляя полученные кадры один за другим».
     */
    fun encode(
        pcm: ByteArray,
        size: Int,
    ): List<ByteArray> {
        val encoder = codec ?: return emptyList()
        val output = mutableListOf<ByteArray>()
        try {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = encoder.getInputBuffer(inputIndex)
                if (buffer == null) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, presentationUs, 0)
                } else {
                    buffer.clear()
                    buffer.put(pcm, 0, size.coerceAtMost(buffer.capacity()))
                    encoder.queueInputBuffer(inputIndex, 0, size, presentationUs, 0)
                    presentationUs += FRAME_SAMPLES * 1_000_000L / sampleRate
                }
            }
            drain(encoder, output)
        } catch (error: Exception) {
            log("АУДИО: исключение кодировки AAC — ${error.message}")
        }
        return output
    }

    private fun drain(
        encoder: MediaCodec,
        output: MutableList<ByteArray>,
    ) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index < 0 -> return
                else -> {
                    val buffer = encoder.getOutputBuffer(index)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && bufferInfo.size > 0 && !isConfig) {
                        val data = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(data)
                        output += finalizeFrame(data)
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /**
     * Удалите заголовок ADTS, если он присутствует.
     *
     * Требование онлайн-формата       — **голый кадр AAC** (catplay `rtp_recorder` напрямую использует выход кодера в качестве нагрузки).
     * Большинство реализаций MediaCodec выводят голые кадры, но некоторые аппаратные кодировщики поддерживают
     * заголовок ADTS размером 7/9 байт — просто внесите его.
     * недопустимая нагрузка, декодер транспортного средства отбросит весь поток. Здесь идентифицируйте и отделяйте
     * по слову синхронизации, что является защитным лечением.
     */
    private fun stripAdts(frame: ByteArray): ByteArray {
        if (frame.size < 7) return frame
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return frame
        // Бит Protection_absent ADTS — это младший бит второго байта: 1 = нет CRC (7-байтовый заголовок), 0 = 9 байтов.
        val headerSize = if ((b1 and 0x01) == 0x01) 7 else 9
        if (frame.size <= headerSize) return frame
        if (!adtsStripped) {
            adtsStripped = true
            log("АУДИО: выход кодировщика с заголовком ADTS (${headerSize}B) — очищен, преобразован в чистые кадры AAC.")
        }
        return frame.copyOfRange(headerSize, frame.size)
    }

    /**
     * определяет форму онлайн-загрузки в соответствии с текущим планом: пустой кадр (по умолчанию) или «ADTS-заголовок + пустой кадр».
     *
     * Сначала удалите ADTS, которые могут идти в комплекте с кодировщиком (во избежание дублирования заголовков), а
     * затем решите, стоит ли пересобирать по плану.
     */
    private fun finalizeFrame(frame: ByteArray): ByteArray {
        val raw = stripAdts(frame)
        if (!adtsWrapped && wrapAdts) {
            adtsWrapped = true
            log("АУДИО: изменено на инкапсуляцию ADTS (7-байтовый заголовок добавляется к каждому кадру, вариант 2)")
        }
        val out = if (wrapAdts) buildAdts(raw) else raw
        // Сохранение первого кадра: при возникновении проблемы вы можете напрямую проверить из журнала, является ли
        // загрузка допустимым AAC (вместо ожидания файла руководства пользователя).

        if (!firstFrameLogged) {
            firstFrameLogged = true
            val head = out.take(16).joinToString(" ") { "%02X".format(it) }
            log(
                "АУДИО: первый кадр ${out.size}B (голый кадр ${raw.size}B) head=$head" +
                    (if (wrapAdts) "（ADTS，sync=FFF）" else ""),
            )
        }
        return out
    }

    /** Набор 7-байтового заголовка ADTS (без CRC): MPEG-4/LC/соответствующие частота дискретизации и каналы. */
    private fun buildAdts(raw: ByteArray): ByteArray = adtsHeader(raw.size, sampleRate, channels) + raw

    fun stop() {
        val encoder = codec ?: return
        codec = null
        started = false
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
    }
}
