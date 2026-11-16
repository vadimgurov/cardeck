package io.github.shaonialife.cardeck.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import io.github.shaonialife.cardeck.airplay.TimingServer
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Основной отправитель звука CarPlay (мобильный телефон → автомобильное устройство, RTP через UDP).
 *
 * Формат строки   (сравните путь расшифровки DiPlay `AudioStream.kt` и кошачью игру `rtp_cipher_chacha.rs`):
 * ```
 * [RTP голова 12B][ChaCha20-Poly1305 герметичная нагрузка PCM + тег 16B][8B LE nonce]
 * ```
 * — Шифрование: ChaCha20-Poly1305; AAD = заголовок 4..12 байт (BE-представление отметки времени + ssrc,
 * «Современный AAD» от   Catplay; Расшифровка DiPlay занимает тот же диапазон)
 * — nonce (12B) = 4 байта нуля + конечные 8 байтов (счетчик с прямым порядком байтов, увеличивается с 0 на пакет) → [AirPlayCrypto.nonce64]
 * — поле RTP: версия=2, PT=тип потока (100=канал PCM / 102=канал AAC-LC;
 * по сравнению с кошачьими играми `payload_type = stream_type`),
 * SSRC=0 (та же модель, что и catplay), временная метка = совокупное количество кадров выборки, seq увеличивает пакет за пакетом
 * - **Первый аудиопакет установлен в бит маркера** (Спецификация: первый пакет после RECORD/FLUSH должен быть
 * помечен, и принимающая сторона соответственно
 * разделяет границу воспроизведения; catplay всегда ложь, но наш сеанс «добавить аудиопоток после ЗАПИСИ»,
 * Первая отметка пакета   может помочь принимающей стороне перестроиться)
 *
 * байпас:
 * — **Пакет синхронизации**: [AudioSyncPacket] отправляется в `controlPort` этого потока каждую секунду для
 * установления точки привязки RTP↔NTP.
 * (Без него принимающая сторона может только «угадывать по времени прибытия». Для строгой реализации это просто
 * не запланировано — фактическое тестирование 20 октября 2026 г.)
 *
 * Коллекция  : `AudioPlaybackCapture` (Android 10+) записывает мультимедийный/навигационный звук,
 * воспроизводимый другими приложениями на мобильном телефоне.
 * (требуется MediaProjection — та же лицензия, что и для зеркального режима). [SAMPLES_PER_PACKET] выборка кадров на пакет
 * (стерео 48000 Гц, 16 бит = нагрузка 2048 байт, общий пакет ~ 2084 байт, не превышающий буфер приема пакетов
 * 4096 байт на принимающей стороне).
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /** Отключить громкость мультимедиа мобильного телефона (звук исходит только из автомобильного устройства). null = Не делать (для тестирования). */
    private val context: android.content.Context? = null,
    /**
     * Частота выборки сбора данных  (Гц): должна соответствовать формату, согласованному с помощью SETUP (основной
     * выбор — 48k, автомобильный компьютер поддерживает только
     * 44.1k согласовывается и выдается AirPlayClient). По умолчанию 48к.
     */
    private val sampleRate: Int = 48_000,
    /** Кодирование нагрузки: PCM (тип=100 полос) или AAC-LC (тип=102 полосы). */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** Тип потока RTP, используется только для журналов (PT соответствует типу SETUP). */
    private val streamType: Long = 100L,
    /** Дополняется ли кадр AAC заголовком ADTS (см. [AudioTuning.ADTS], используемый для устранения неполадок в цепочке декодирования машина-машина). */
    private val adtsFrames: Boolean = false,
    /**
     * Порт управления этим аудиопотоком (доставленным автомобилем в ответе SETUP). >0 каждые [SYNC_INTERVAL_MILLIS]
     * отправляет [AudioSyncPacket] для установления точки привязки RTP↔NTP; 0 = машина не указана (старая машина), передается только звук.
     */
    private val controlPort: Int = 0,
) {
    companion object {
        const val CHANNELS = 2

        /**
         * Образцы кадров  в упаковке = стандарт AirPlay SPF 352 (catplay `MAX_FRAMES_PER_PACKET_PCM`,
         * проверка реального автомобиля). 48000/352 ≈ 136 пакетов/секунду (7,33 мс/пакет).
         * 17 октября 2026 г. Фактические измерения: ранее при использовании 512 кадров (10,67 мс/пакет) на принимающей
         * стороне Audi не было звука ——
         * Если длина пакета           не соответствует длине пакета реального iPhone, принимающая сторона может
         * отбросить его в соответствии с фиксированным буфером SPF.
         */
        const val SAMPLES_PER_PACKET = 352

        /** Количество байтов в пакете полезной нагрузки (16-битное стерео). */
        const val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        /** Интервал отправки пакетов синхронизации (уточнение: один в секунду). */
        const val SYNC_INTERVAL_MILLIS = 1_000L

        private const val RTP_HEADER_LEN = 12
        private const val TAG_SIZE = 16
        private const val NONCE_SIZE = 8

        private const val READ_TIMEOUT_QUIET_MILLIS = 500L

        /** Ключ сохранения тома носителя перед отключением звука (цель восстановления не будет потеряна, даже если процесс будет завершен). */
        private const val KEY_SAVED_VOLUME = "audio_saved_media_volume"

        /** s16 с прямым порядком байтов → s16 с прямым порядком байтов (обратный порядок каждые два байта). Для проводного формата CarPlay требуются образцы с прямым порядком байтов. */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)

    /** stop() — это идемпотентный вентиль (stop будет вызываться для нескольких конечных путей). */
    private val stopped = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var syncSocket: DatagramSocket? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var syncThread: Thread? = null

    /** Кодер AAC-LC (создан только для [AudioCodec.AAC_LC]). */
    private var aacEncoder: AacLcEncoder? = null

    /**
     * Курсор RTP (первоначально это была локальная переменная в цикле сбора данных). Поле упоминается, поскольку
     * **поток пакета синхронизации** должен прочитать
     * «Временная метка текущего/следующего пакета», иначе временная метка в точке привязки никогда не будет
     * соответствовать фактическому отправленному пакету.
     */
    @Volatile private var sequence = 0

    @Volatile private var timestamp = 0L

    @Volatile private var nonceCounter = 0L

    /** Отметка первого аудиопакета (удаляется после ввода); Самотестирование AAC выполняется только один раз. */
    private var markerPending = true
    private var selfChecked = false

    /** Количество байт PCM на пакет/кадр (дорожка PCM = полезная нагрузка одного пакета; полоса AAC = ввод одного кадра). */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(CHANNELS) else PACKET_BYTES

    /** Количество выборок, отправленных на пакет (1024 на кадр для AAC, 352 на пакет для PCM). */
    private val samplesPerPacket: Long
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.FRAME_SAMPLES.toLong() else SAMPLES_PER_PACKET.toLong()

    /**
     * RTP SSRC: фиксированный 0 (catplay `prepare_header` использует 0 на реальных автомобилях; принимающая сторона DiPlay не проверяет).
     * 17 октября 2026 г. Фактическое измерение: до использования случайных значений на принимающей стороне Audi не
     * было звука — некоторые автомобили были связаны с НАСТРОЙКОЙ в соответствии с SSRC.
     * Объявленный поток       (отличное от 0 считается отброшенным неизвестным потоком).
     */
    private val ssrc = 0

    /** Сбор + отправка статистики (для ограничения тока журнала). */
    private var packetsSent = 0L
    private var bytesSent = 0L
    private var lastLogAt = 0L
    private var readFailures = 0

    val isRunning: Boolean get() = running.get()

    /** Индекс громкости мультимедиа телефона до отключения звука (-1 = звук не отключен). */
    private var mutedOriginalVolume = -1

    /**
     * Бесшумный вывод мультимедиа на мобильный телефон: после активации аудиоканала звук исходит только из
     * автомобильного устройства (настоящее поведение CarPlay).
     * Основа (фактическое измерение 10 октября 2026 г.): захват воспроизведения берется из REMOTE_SUBMIX (до точки приложения громкости),
     * Отключение звука потоковой передачи на мобильном телефоне **не влияет на захваченные данные** — мобильный
     * телефон не звонит, а машина шумит, как обычно.
     *
     * Сохранение значения восстановления (SharedPreferences): процесс был завершен системой/было слишком поздно
     * восстанавливаться при установке обновлений.
     * Если следующее значение отключения звука равно 0, оно будет считаться «исходной громкостью», что приведет к
     * постоянному отключению звука после отключения (действовано 10 октября 2026 г.).
     */
    private fun mutePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            if (mutedOriginalVolume >= 0) return
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
            val current = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            // Целевое значение восстановления: приоритет отдается сохраненному значению, которое не удалось восстановить в
            // последний раз; в противном случае текущее значение (>0 является достоверным)

            val restoreTarget =
                when {
                    saved > 0 -> saved
                    current > 0 -> current
                    else -> -1
                }
            if (restoreTarget > 0) {
                prefs.edit().putInt(KEY_SAVED_VOLUME, restoreTarget).apply()
            }
            mutedOriginalVolume = restoreTarget
            manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            // Проверка обратного чтения: политика звука ПЗУ может немедленно отклонить/восстановить звук (при фактическом
            // тестировании громкость по-прежнему остается исходным значением).

            // В это время телефон продолжает выпускаться - лог должен быть виден напрямую
            val after = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            log(
                "АУДИО: телефон без звука (прежняя громкость ${if (restoreTarget > 0) restoreTarget else "неизвестно"}，" +
                    "Восстановление после отключения) - объем чтения = $after" +
                    if (after != 0) "(⚠ Отключение звука не действует, телефон все равно будет воспроизводиться)" else "",
            )
        } catch (error: Exception) {
            log("АУДИО: не удалось отключить звук мобильного мультимедиа — ${error.message}")
        }
    }

    private fun restorePhoneMedia() {
        val ctx = context ?: return
        try {
            val manager =
                ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
            val prefs = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val target =
                when {
                    mutedOriginalVolume > 0 -> mutedOriginalVolume
                    else -> prefs.getInt(KEY_SAVED_VOLUME, -1)
                }
            if (target > 0) {
                manager.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    target,
                    0,
                )
                log("АУДИО: громкость мультимедиа на мобильном телефоне восстановлена ($target).")
            } else {
                log(
                    "АУДИО: нет действительной исходной записи громкости, на носителе мобильного телефона сохраняется " +
                        "текущее значение (для настройки нажмите кнопку громкости)",
                )
            }
            prefs.edit().remove(KEY_SAVED_VOLUME).apply()
        } catch (_: Exception) {
        }
        mutedOriginalVolume = -1
    }

    /**
     * начинает передачу звука. Требуется MediaProjection (авторизованный источник AudioPlaybackCapture).
     * возвращает информацию о том, успешно ли введен цикл сбора.
     */
    fun start(projection: MediaProjection): Boolean {
        if (running.get()) return true
        stopped.set(false)
        return try {
            val client = DatagramSocket()
            client.connect(InetAddress.getByName(host), dataPort)
            socket = client

            // Для канала AAC сначала настройте кодировщик: если его невозможно закодировать, собирать его не нужно (это
            // также позволяет избежать иллюзии «пакетов, но нет звука»)

            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                aacEncoder = encoder
            }

            val captureConfig = buildCaptureConfig(projection)
            val audioFormat =
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            val recorder =
                AudioRecord
                    .Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(framePcmBytes * 8)
                    .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                log("АУДИО: инициализация AudioRecord не удалась (состояние = ${recorder.state})")
                runCatching { recorder.release() }
                aacEncoder?.stop()
                aacEncoder = null
                client.close()
                socket = null
                return false
            }
            record = recorder
            running.set(true)
            mutePhoneMedia()
            log(
                "АУДИО: включено (захват воспроизведения ${sampleRate}Hz стерео → $host:$dataPort," +
                    "Канал=t$streamType/${codec.name}," +
                    if (codec == AudioCodec.AAC_LC) {
                        "1 кадр AAC на пакет (выборки ${AacLcEncoder.FRAME_SAMPLES}" +
                            (if (adtsFrames) ", пакет ADTS))" else ", голый кадр))")
                    } else {
                        "${SAMPLES_PER_PACKET} кадров на пакет PCM)"
                    },
            )

            // Поток пакетов синхронизации запускается первым: чем раньше будет установлена точка привязки, тем скорее
            // принимающая сторона сможет запланировать широковещательную рассылку.

            startSyncLoop()

            Thread(
                {
                    try {
                        recorder.startRecording()
                    } catch (error: Exception) {
                        log("АУДИО: Не удалось начать сбор данных — ${error.message}.")
                        stop()
                        return@Thread
                    }
                    runCaptureLoop()
                },
                "audio-capture",
            ).apply {
                isDaemon = true
                start()
                thread = this
            }
            true
        } catch (error: Exception) {
            log("АУДИО: Ошибка запуска — ${error.message}")
            stop()
            false
        }
    }

    /**
     * останавливает звук.
     *
     * имеет более одного конечного пути (потоок сбора, наконец, обратный вызов завершения сеанса, ручное отключение)
     * и будет вызываться повторно;
     * должен быть идемпотентным — в противном случае «Том мобильного носителя восстановлен» будет введен дважды и
     * изменен пользователем вручную во время сеанса.
     * Когда том      , второе рекавери перезапишет его старым значением.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        aacEncoder?.stop()
        aacEncoder = null
        runCatching { socket?.close() }
        socket = null
        runCatching { syncSocket?.close() }
        syncSocket = null
        syncThread = null
        thread = null
        restorePhoneMedia()
        log("АУДИО: остановлено (всего пакетов $packetsSent / ${bytesSent / 1024}KB)")
    }

    /** Конфигурация захвата: общие цели воспроизведения, такие как мультимедиа/игры/навигация (добавляйте элементы один за другим и пропускайте, если система отклоняет отдельные виды использования). */
    private fun buildCaptureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        ).forEach { usage ->
            runCatching { builder.addMatchingUsage(usage) }
        }
        return builder.build()
    }

    // ---- Пакет синхронизации звука (временная метка RTP ↔ NTP, один раз в секунду) ----

    private fun startSyncLoop() {
        if (controlPort <= 0) {
            log(
                "АУДИО: ControlPort не выдается автомобилем — пакет синхронизации не отправляется (можно полагаться " +
                    "только на временную метку, включенную в аудиопакет)",
            )
            return
        }
        val client =
            try {
                DatagramSocket().apply { connect(InetAddress.getByName(host), controlPort) }
            } catch (error: Exception) {
                log("АУДИО: не удалось создать сокет пакета синхронизации — ${error.message}")
                return
            }
        syncSocket = client
        log(
            "АУДИО: пакет синхронизации включен → $host:$controlPort (один раз в ${SYNC_INTERVAL_MILLIS}ms, " +
                "устанавливается привязка RTP↔NTP)",
        )
        val worker =
            Thread(
                {
                    var syncSequence = 0
                    var first = true
                    try {
                        while (running.get() && !client.isClosed) {
                            val next = timestamp
                            val current = (next - samplesPerPacket).coerceAtLeast(0L)
                            val ntp = TimingServer.ntpFromNanos(System.nanoTime())
                            val packet = AudioSyncPacket.build(syncSequence, current, next, ntp, first)
                            client.send(DatagramPacket(packet, packet.size))
                            syncSequence = (syncSequence + 1) and 0xFFFF
                            if (first) {
                                first = false
                                log(
                                    "АУДИО: отправлен первый пакет синхронизации (бит X=1) ts=$current→$next——" +
                                        "Если двигатель все еще молчит, это означает, что он не ждет этой точки привязки.",
                                )
                            }
                            Thread.sleep(SYNC_INTERVAL_MILLIS)
                        }
                    } catch (error: Exception) {
                        if (running.get()) log("АУДИО: передача пакетов синхронизации остановлена — ${error.message}")
                    }
                },
                "audio-sync",
            )
        worker.isDaemon = true
        worker.start()
        syncThread = worker
    }

    // ---- Цикл сбора → Отправка пакета ----

    private fun runCaptureLoop() {
        val recorder = record ?: return
        val frameBytes = framePcmBytes
        val pcm = ByteArray(frameBytes)
        // Частота диагностической выборки: PCM около 125 пакетов/с (раз в 20 с); AAC около 47 кадров/с
        val diagnosticStride = if (codec == AudioCodec.AAC_LC) 600 else 1875
        try {
            while (running.get()) {
                val read =
                    try {
                        recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    } catch (error: Exception) {
                        log("АУДИО: Исключение чтения при сборе данных — ${error.message}")
                        break
                    }
                if (read < 0) {
                    readFailures++
                    if (readFailures == 1 || readFailures % 100 == 0) {
                        log("АУДИО: код ошибки сбора $read ($readFailures раз подряд)")
                    }
                    if (readFailures > 500) break
                    Thread.sleep(READ_TIMEOUT_QUIET_MILLIS)
                    continue
                }
                if (read < frameBytes) continue // Остаточный пакет (хвост буфера): отбросить и дождаться следующего полного пакета.
                readFailures = 0

                // Диагностика контента (17 октября 2026 г.): захват воспроизведения может иметь все нули (приложение отключает
                // захват/маршрутизацию звука).

                // Bluetooth/нет воспроизведения звука). Когда все нули установлены, в машине отключается звук, но телефон
                // продолжает звонить. Пользователь сообщит: «Звук включен».

                // "на мобильном телефоне" - надо уметь отличать "не взял трубку" и "взял, но не играл в машине".
                if (sequence < 3 || sequence % diagnosticStride == 0) {
                    var nonZero = 0
                    var peak = 0
                    var index = 0
                    while (index + 1 < read) {
                        val value = ((pcm[index + 1].toInt() and 0xFF) shl 8) or (pcm[index].toInt() and 0xFF)
                        val magnitude = if (value > 32767) 65536 - value else value
                        if (magnitude > peak) peak = magnitude
                        if (magnitude > 0) nonZero++
                        index += 2
                    }
                    val percent = nonZero * 100 / (read / 2).coerceAtLeast(1)
                    log(
                        "АУДИО: Сбор выборки seq=$sequence ненулевое $percent% пиковое значение=$peak" +
                            if (peak == 0) {
                                "(⚠ Все нули: звук не записан — возможно, приложение отключило " +
                                    "воспроизведение записи или звук перешел на Bluetooth)"
                            } else {
                                ""
                            },
                    )
                }

                // Канал PCM: взять один сегмент = отправить один пакет; Канал AAC: подайте один кадр PCM, кодер может выдать 0 ~ N кадров.
                val payloads: List<ByteArray> =
                    if (codec == AudioCodec.AAC_LC) {
                        val encoder = aacEncoder ?: break
                        encoder.encode(pcm, frameBytes)
                    } else {
                        listOf(pcm)
                    }
                if (payloads.isEmpty()) continue

                // Одноразовая самопроверка: самостоятельно декодируйте результат кодирования, чтобы убедиться, что битовый поток
                // действителен и не молчалив.

                // Это самое необходимое доказательство «бесшумной машины-машины» — если пиковое значение самодекодирования равно
                // 0, проблема кроется в кодере.

                // Если звук при саморасшифровке есть, но машина молчит, проблема на стороне машины (сессия/громкость/маршрутизация).
                if (!selfChecked) {
                    selfChecked = true
                    verifyEncodedFrame(payloads.first())
                }

                var sendFailed = false
                for (payload in payloads) {
                    if (!sendPayload(payload)) {
                        sendFailed = true
                        break
                    }
                }
                if (sendFailed) break
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastLogAt >= 30_000L) {
                    lastLogAt = now
                    log(
                        "АУДИО: Нажатие (накопленные пакеты $packetsSent, ${bytesSent / 1024}KB," +
                            "seq=$sequence ts=$timestamp）",
                    )
                }
            }
        } catch (error: Throwable) {
            // Неперехваченные исключения в фоновых потоках убивают весь процесс — звуковые исключения никогда не всплывают.
            log("АУДИО: поток сбора данных завершился ненормально — ${error.message}")
        } finally {
            stop()
        }
    }

    /** Пакет + Отправить пакет (синхронно обновить курсор seq/ts/nonce). Возврат false указывает на то, что ссылка не работает. */
    private fun sendPayload(payload: ByteArray): Boolean {
        val wire = sealPacket(payload, sequence, timestamp, nonceCounter, markerPending)
        markerPending = false
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++
        val client = socket ?: return false
        return try {
            client.send(DatagramPacket(wire, wire.size))
            packetsSent++
            bytesSent += wire.size
            true
        } catch (error: IOException) {
            log("АУДИО: Ошибка отправки UDP — ${error.message}")
            false
        }
    }

    /** Используйте декодер платформы, чтобы декодировать первый кадр обратно в PCM и записать пиковое значение (фоновый поток, не блокируя поток push-уведомлений). */
    private fun verifyEncodedFrame(frame: ByteArray) {
        val payload = frame.copyOf()
        Thread(
            {
                try {
                    val result = AacLcEncoder.decodePeak(payload, sampleRate, CHANNELS)
                    if (result == null) {
                        log(
                            "АУДИО: Самотестирование = Локальный декодер не декодировал образец (первый кадр " +
                                "${payload.size}B) — фальсификация невозможна, продолжайте отправлять поток",
                        )
                        return@Thread
                    }
                    val (peak, samples) = result
                    log(
                        "АУДИО: Самотестирование = первый кадр ${payload.size}B, образцы $samples с собственным " +
                            "декодированием, пиковое значение $peak" +
                            if (peak > 0) {
                                "(✓ Битовый поток действителен и не бесшумен: если машина молчит, проблема не в кодировании/шифровании)"
                            } else {
                                "(⚠ Декодируется до всех нулей: кодер AAC выводит тихие данные)"
                            },
                    )
                } catch (error: Exception) {
                    log("АУДИО: Исключение декодирования самопроверки — ${error.message}")
                }
            },
            "audio-selfcheck",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Соберите пакет RTP: открытый текст PCM → печать ChaCha20 → [заголовок][шифрованный текст+тег][nonce8]. (внутренний: прямой тест) */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        // AAD = временная метка+ssrc раздел заголовка (байты 4..12) — игра «современный AAD» / DiPlay расшифровывает тот же абзац
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        // Ключ: LPCM для проводного формата CarPlay — это образцы **big endian** s16 — ресивер это делает.
        // byteSwapS16 Преобразование обратно в прямой порядок байтов и передача AudioTrack (DiPlay AndroidMediaSink.kt:1687).
        // Коллекция Android имеет прямой порядок байтов, поэтому здесь необходимо изменить порядок; если вы его
        // пропустите, вы услышите «шуршащий звук» (фактическое измерение 10 октября 2026 г.).

        //
        // Канал AAC не выполняет переупорядочение: битовый поток AAC представляет собой поток байтов, а не 16-битную
        // последовательность выборок, и переупорядочение будет осуществляться напрямую.

        // Битовый поток перепутан (декодирование машины не работает → пакеты есть, а звука нет).
        val wirePayload = if (codec == AudioCodec.AAC_LC) pcm else bigEndianS16(pcm)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePayload, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // Завершающий 8-байтовый одноразовый номер с прямым порядком байтов (согласуется с младшими 8 байтами AirPlayCrypto.nonce64)
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }

    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // версия=2
        // PT следовать за типом потока (catplay `payload_type = stream_type`): 100 = канал PCM, 102 = канал AAC
        header[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7F)).toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
