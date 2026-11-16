package io.github.shaonialife.cardeck.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import io.github.shaonialife.cardeck.airplay.BinaryPlist
import io.github.shaonialife.cardeck.airplay.StreamFrameCipher
import io.github.shaonialife.cardeck.net.TcpLiveness
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Передатчик видео с главного экрана CarPlay (Фаза 2-C2).
 *
 * Формат строки   (сравните DiPlay ScreenStream.kt):
 * — каждое сообщение = 128 байт заголовок + тело; заголовок = [0..3] длина тела u32LE, [4] код операции,
 * [8..15] NTP 32.32 LE время отправки; весь заголовок зашифрован AAD, а остальные байты равны нулю.
 * — код операции 1 = VideoConfig: запись avcC в виде открытого текста (решение выглядит как AvcC)
 * - код операции 0 = VideoFrame: ChaCha20-Poly1305 запечатанная «4-байтовая последовательность NAL с префиксом длины BE»,
 * nonce = nonce64 (количество кадров), счетчик увеличивается с 0 в порядке следования кадров.
 *
 * Источник видео  : ввод MediaCodec Surface (жестко закодированный H.264), контент отображается источником изображения.
 * (поверхность ввода DualDisplayComposer в режиме невидимого экрана).
 */
class VideoSender(
    private val host: String,
    private val dataPort: Int,
    /**
     * Метод шифрования кадра  (три формы кошачьей игры `ScreenFrameCodec`):
     * — ChaCha: парный (ключ производного потока с проверкой пары)
     * — AesCtr: непарный сеанс (`et=NONE`, AES-128-CTR + нулевой ключ сеанса)
     */
    private val frameCipher: StreamFrameCipher,
    /** H.264 4:2:0 требует четных размеров — нечетные высоты приведут к тому, что кодер перейдет в состояние ошибки при вводе первого кадра (масштабирование 125 % является ошибкой при реальных измерениях). */
    width: Int,
    height: Int,
    /** Целевая частота кадров (30/60): метаданные кодировщика + частота ускорения 60 кадров в секунду; фактический темп, управляемый синтезатором. */
    private val targetFps: Int = 30,
    private val log: (String) -> Unit,
    /** Обратный вызов, когда push-поток завершается естественным образом (одноранговый узел отключен и т. д.) — сигнал о том, что сеанс CarPlay действительно завершается. */
    private val onEnded: (() -> Unit)? = null,
    /**
     * Содержит ли кадр KeepAlive в потоке экрана тело статистики (`keepAliveSendStatsAsBody` автомобиля/информации).
     * Audi MMI 3315 объявляет true → код операции = 5 + тело статистики bplist; в противном случае код операции = 2 пустой кадр.
     * True iPhone/catplay по-прежнему отправляет раз в секунду, когда видео продолжает передаваться - это не резервная копия видеотрафика,
     * — это независимый сигнал выживания на принимающем экране (фактическое испытание двухколесного Audi 3315 19 октября 2026 г.: без него
     * Весь раунд был сброшен через 10,2 секунды после       RECORD, и все каналы видео/обратной связи/событий работают нормально).
     */
    private val keepAliveWithStats: Boolean = false,
) {
    private val width: Int = (width / 2) * 2
    private val height: Int = (height / 2) * 2
    private var socket: Socket? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)
    private var frameCounter = 0L
    private var configSent = false

    /** Пример потока ключей AES-CTR: состояние должно быть непрерывным во всех кадрах (новое создание каждого кадра приведет к декодированию искаженных символов одноранговым узлом). */
    private val aesStream: AirPlayCrypto.Aes128CtrStream? =
        (frameCipher as? StreamFrameCipher.AesCtr)?.let { AirPlayCrypto.Aes128CtrStream(it.key, it.iv) }

    /** Последний раз, когда поток экрана оставался активным (uptimeMillis; 0 = еще не отправлен). */
    private var lastKeepAliveAt = 0L

    /** Количество кадров/байтов между двумя KeepAlives (для статистики это синоним «кошачьей игры» ScreenSenderStats). */
    private var framesSinceKeepAlive = 0
    private var bytesSinceKeepAlive = 0L
    private var keepAliveCount = 0L

    @Volatile
    private var framesSent = 0L

    /** Активен ли push-поток (определяется функцией тихого шумоподавления: сохраняет значение false при сбое запуска). */
    val isRunning: Boolean get() = running.get()

    /** Общее количество отправленных кадров (частота кадров в реальном времени на карте героя = опрос в течение 1 секунды для определения разницы). */
    val framesSentTotal: Long get() = framesSent

    /**
     * Предварительно устанавливает TCP-соединение порта данных экрана (идемпотентное).
     *
     * Автомобильный сторожевой таймер       (проверен Audi 17 октября 2026 г.): порт данных должен быть подключен в
     * течение примерно 2 секунд после НАСТРОЙКИ экрана.
     * В противном случае напрямую сбросить канал управления и снести всю сессию. Сама НАСТРОЙКА звука занимает ~2,1
     * с. Если это произойдет после этого,
     * Соединения порта       неизбежно задерживаются, поэтому настройте этот метод перед согласованием звука. То же
     * самое касается кошачьих игр (сразу после НАСТРОЙКИ видео).
     * Finish_tx, затем согласуйте аудио).
     */
    fun openDataPort(): Boolean {
        val existing = socket
        if (existing != null && !existing.isClosed) return true
        return try {
            val client = Socket()
            client.connect(InetSocketAddress(host, dataPort), 5_000)
            client.tcpNoDelay = true
            // Push-стриминг — это долгое подключение: при выключении автомобиля или выходе из хотспота ссылка часто не
            // отправляет RST, и запись блокируется на полном окне.

            // Затем поток кодировщика застревает (это отображается как «изображение останавливается на последнем кадре»).
            TcpLiveness.configure(client, "Порт видеоданных", log)
            socket = client
            log("ВИДЕО: Подключен порт данных $host:$dataPort (запустите кодер после ожидания ЗАПИСИ)")
            true
        } catch (error: Exception) {
            log("ВИДЕО: Не удалось подключиться к порту данных — ${error.message}")
            false
        }
    }

    /**
     * начинает кодирование и потоковую передачу. Входная поверхность кодировщика передается вызывающему объекту для
     * рендеринга через [onSurfaceReady].
     * (заполнен составным холстом DualDisplayComposer в режиме невидимого экрана).
     */
    fun start(onSurfaceReady: (Surface) -> Unit): Boolean {
        if (running.get()) return true
        return try {
            if (!openDataPort()) {
                stop()
                return false
            }
            val bitrate =
                (
                    ((width * height * 6L).toInt()).coerceIn(2_000_000, 10_000_000) *
                        if (targetFps >= 60) 1.5f else 1f
                ).toInt().coerceAtMost(15_000_000)
            log("ВИДЕО: Запускаем энкодер ${width}x$height@$targetFps ${bitrate / 1_000_000}Mbps (порт подключен)")

            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    // Исправлен уровень H.264 4.0 (SPS level_idc=40, тот же формат потока, что и на реальном iPhone).
                    // Если он не фиксирован, кодер автоматически выбирается в соответствии с «разрешением + частотой кадров»: по
                    // умолчанию — 1412x654 при 30 кадрах в секунду.

                    // Юридически попадает на уровень 3.2 - декодер Audi DELPHI молча не отображает этот уровень
                    // (TCP все еще получен, сеанс активен, экран автомобиля черный, журнал реальной машины Audi 0628 от 22 октября 2026 г.
                    // Дин Цзе: Все успехи на уровне = 40 на одной машине, черный экран впервые на уровне = 32). Закодировано со
                    // скоростью 60 кадров в секунду

                    // Изначально на устройстве установлена версия 4.0, и эта настройка не подлежит изменению.
                    runCatching {
                        setInteger(
                            MediaFormat.KEY_LEVEL,
                            MediaCodecInfo.CodecProfileLevel.AVCLevel4,
                        )
                    }
                }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // Некоторые кодировщики не принимают указание уровня (конфигурация выдает исключение = видео полностью
            // уничтожено), понизьте версию до не указывающей и повторите попытку.

            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (levelRejected: Exception) {
                log(
                    "ВИДЕО: Указанный уровень 4.0 отвергается энкодером (${levelRejected.message}) - возврат к " +
                        "автоматическому выбору (на некоторых машинах может быть черный экран)",
                )
                val fallback =
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                codec.configure(fallback, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            inputSurface = surface
            running.set(true)
            lastKeepAliveAt = android.os.SystemClock.uptimeMillis()

            onSurfaceReady(surface)

            Thread({ drainEncoder() }, "video-drain").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("ВИДЕО: Ошибка запуска — ${error.message}")
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { socket?.close() }
        socket = null
    }

    /**
     * немедленно кодирует ключевой кадр (запрос `forceKeyFrame` для машины `/command`).
     *
     * Когда автомобиль не может разрешить изображение, он неоднократно запрашивает I-кадры; у нас есть один кадр в
     * секунду, но на запрос кадра нужно отвечать немедленно——
     * В противном случае автомобиль может зависнуть в состоянии «ожидание следующего I-кадра», а производительность
     * «подключена, но нет изображения».
     * (фактическое тестирование BMW iD9 20 октября 2026 г.: ForceKeyFrame был получен один раз, когда сеанс только начался).
     */
    fun requestKeyFrame(): Boolean {
        val codec = encoder ?: return false
        return try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
            true
        } catch (error: Exception) {
            log("ВИДЕО: не удалось запросить ключевой кадр — ${error.message}")
            false
        }
    }

    // ---- Кодирование вывода → Инкапсуляция и отправка протокола ----

    private fun drainEncoder() {
        val codec = encoder ?: return
        val output = socket?.getOutputStream() ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index =
                    try {
                        codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    } catch (error: Exception) {
                        // Кодер входит в состояние ошибки (например, первый кадр недопустимого размера): должен остаться след — разрешен
                        // тихий перерыв

                        // «Отправка нулевого кадра отключается через 4 секунды» невозможно проверить (принимающая сторона видит только
                        // EOF однорангового узла)

                        log("ВИДЕО: Кодеру не удалось получить кадры и остановить потоковую передачу - ${error.message}")
                        break
                    }
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        maybeSendKeepAlive(output)
                        continue
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        val flags = bufferInfo.flags
                        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            sendVideoConfig(buffer, bufferInfo.size, output)
                        } else if (bufferInfo.size > 0) {
                            sendVideoFrame(buffer, bufferInfo.size, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, output)
                        }
                        codec.releaseOutputBuffer(index, false)
                        maybeSendKeepAlive(output)
                    }
                }
            }
        } catch (error: Exception) {
            // Порт видеоданных отключен (например, конечный экран приема закрыт и сеанс закрыт): завершается только поток
            // push-уведомлений и никогда не всплывает ——

            // Неперехваченные исключения в фоновом потоке уничтожат весь процесс (из-за чего приложение однажды исчезло
            // после блокировки экрана).

            log(
                "ВИДЕО: потоковая передача прервана — ${error.message} (ожидание восстановления принимающей стороны и " +
                    "повторное подключение)",
            )
        } finally {
            stop()
            // Истинный сигнал окончания сеанса (отключение тихого шумоподавления/окончание статуса)
            runCatching { onEnded?.invoke() }
        }
    }

    /**
     * экранный поток KeepAlive кадр (кошачья игра `ScreenTransmitSession::create_keep_alive`, интервал 1 с).
     *
     * и обратная связь UDP KeepAlive/RTSP — это три независимых сигнала выживания; приемная сторона (Ауди 3315)
     * Только просмотра видеокадров недостаточно - при его отсутствии вся сессия будет сброшена примерно через 10
     * секунд после ЗАПИСИ (два раунда 19.10.2026).
     * фактическое измерение). Кадр представляет собой **открытый текст** (catplay шифрует только код операции =
     * VideoFrame), поэтому ChaCha не используется.
     * количество кадров; со статистическим телом, код операции = 5, и запишите длину тела в последние 4 байта параметров заголовка [14]
     * (f32 LE), побайтно идентичен кошачьей игре.
     */
    private fun maybeSendKeepAlive(output: java.io.OutputStream) {
        if (!running.get()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastKeepAliveAt < KEEPALIVE_INTERVAL_MS) return
        val elapsedNanos = (now - lastKeepAliveAt).coerceAtLeast(1L) * 1_000_000.0
        val frames = framesSinceKeepAlive
        val bytes = bytesSinceKeepAlive
        val framesPerSecond = (frames * 1_000_000_000.0 / elapsedNanos).toInt()
        val bytesPerSecond = bytes * 1_000_000_000.0 / elapsedNanos
        framesSinceKeepAlive = 0
        bytesSinceKeepAlive = 0
        lastKeepAliveAt = now
        keepAliveCount++
        val frame = buildKeepAliveFrame(keepAliveWithStats, frames, bytesPerSecond, framesPerSecond)
        output.write(frame)
        output.flush()
        if (keepAliveCount <= 3 || keepAliveCount % 30 == 0L) {
            log(
                "VIDEO: keepAlive #$keepAliveCount " +
                    "opcode=${if (keepAliveWithStats) OP_KEEP_ALIVE_WITH_BODY else OP_KEEP_ALIVE} " +
                    "body=${frame.size - HEADER_LEN}B fps=$framesPerSecond",
            )
        }
    }

    private fun sendVideoConfig(
        buffer: java.nio.ByteBuffer,
        size: Int,
        output: java.io.OutputStream,
    ) {
        val annexB = ByteArray(size)
        buffer.get(annexB)
        val nalUnits = splitAnnexB(annexB)
        val rawSps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (rawSps == null || pps == null) {
            log("ВИДЕО: в csd отсутствует SPS/PPS (NAL ${nalUnits.size}), пропуск конфигурации")
            return
        }
        // level_idc исправлен до версии 4.0 (встроенный ключевой кадр SPS также будет исправлен, см. sendVideoFrame)
        val sps = patchSpsLevel(rawSps)
        val avcc = ByteArrayOutputStream(8 + sps.size + pps.size)
        avcc.write(1)
        avcc.write(sps[1].toInt()) // профиль
        avcc.write(sps[2].toInt()) // совместимость
        avcc.write(sps[3].toInt()) // уровень
        avcc.write(0xFF) // 6 бит зарезервировано + 2 бита, размер длины - 1 (4 байта)
        avcc.write(0xE1) // 3-значный зарезервированный + 5-значный номер SPS
        avcc.write((sps.size ushr 8) and 0xFF)
        avcc.write(sps.size and 0xFF)
        avcc.write(sps)
        avcc.write(1)
        avcc.write((pps.size ushr 8) and 0xFF)
        avcc.write(pps.size and 0xFF)
        avcc.write(pps)
        val body = avcc.toByteArray()
        // Головка должна соответствовать кошачьей игре `ScreenFrame::config`, в противном случае некоторые автомобили
        // (фактические измерения Audi/DELPHI:

        // Если соединение и касание в норме, а экран автомобиля полностью черный), последующие зашифрованные кадры будут
        // анализироваться как открытый текст NAL → декодирование не удастся:

        //  - small_param[1] |= ScreenFlag::Encrypted(0x04): объявляет «Видеокадры зашифрованы»
        //  - params[1] = (ширина, высота) два f32 LE: автомобильная машина соответствующим образом строит холст декодера
        //  - Params[0] обнулены (конфиг catplay не содержит PTS; только VideoFrame пишет params[0])
        // Старая реализация не записывала три элемента — DiPlay/WinPlay только читали тело и не проверяли заголовок,
        // поэтому тестовый стенд всегда был нормальным.

        // Настоящая машина (Audi 4851) имеет черный экран (журнал пользователя 19 октября 2026 г.).
        val header = buildVideoConfigHeader(body.size, width, height)
        output.write(header)
        output.write(body)
        output.flush()
        configSent = true
        log(
            "ВИДЕО: ✓ VideoConfig отправил avcC=${body.size}B (SPS ${sps.size}B/PPS ${pps.size}B," +
                "profile=${sps[1].toInt() and 0xFF} level=${sps[3].toInt() and 0xFF} " +
                "flags=0x%02x ${width}x$height）".format(header[6].toInt() and 0xFF) +
                "\n ↳ 24 байта перед заголовком =" + header.take(24).joinToString(" ") { "%02x".format(it) },
        )
    }

    private fun sendVideoFrame(
        buffer: java.nio.ByteBuffer,
        size: Int,
        keyFrame: Boolean,
        output: java.io.OutputStream,
    ) {
        if (!configSent) {
            log("ВИДЕО: Пропал кадр (конфигурация еще не отправлена)")
            return
        }
        val annexB = ByteArray(size)
        buffer.get(annexB)
        val nalUnits = splitAnnexB(annexB)
        if (nalUnits.isEmpty()) return

        // Синхронное исправление уровня SPS, встроенное в ключевые кадры: автомобиль будет использовать внутрикадровый
        // SPS для обновления параметров декодера.

        // Если вы измените только csd без изменения внутрикадрового SPS, он вернется на низкий уровень после получения
        // первого ключевого кадра.

        val packed = ByteArrayOutputStream(size)
        for (nal in nalUnits) {
            val out =
                if (nal.isNotEmpty() && (nal[0].toInt() and 0x1F) == 7) patchSpsLevel(nal) else nal
            packed.write((out.size ushr 24) and 0xFF)
            packed.write((out.size ushr 16) and 0xFF)
            packed.write((out.size ushr 8) and 0xFF)
            packed.write(out.size and 0xFF)
            packed.write(out)
        }
        val plain = packed.toByteArray()

        // Метод шифрования выбирается et сеанса (catplay ScreenFrameCodec):
        //  - ChaCha: заголовок используется как AAD, а длина тела включает тег 16B;
        //  - AES-128-CTR: нет тегов, нет изменения длины, поток ключей непрерывен во всех кадрах.
        // Кадры KeepAlive/config не шифруются ни в одном из режимов. Головы можно построить только один раз — AAD ChaCha
        // Это должны быть 128 байт, наконец отправленные (повторяющаяся конструкция приведет к появлению разных
        // временных меток, и расшифровка узла неизбежно завершится неудачей).

        val header =
            buildHeader(
                OP_FRAME,
                if (frameCipher is StreamFrameCipher.ChaCha) plain.size + TAG_SIZE else plain.size,
            )
        val sealed =
            when (val cipher = frameCipher) {
                is StreamFrameCipher.ChaCha ->
                    AirPlayCrypto.chachaSeal(cipher.key, AirPlayCrypto.nonce64(frameCounter), plain, header)
                is StreamFrameCipher.AesCtr -> {
                    val encrypted = plain.copyOf()
                    aesStream?.applyKeystream(encrypted)
                    encrypted
                }
            }
        frameCounter++
        output.write(header)
        output.write(sealed)
        output.flush()
        framesSent++
        framesSinceKeepAlive++
        bytesSinceKeepAlive += plain.size.toLong()
        if (keyFrame || framesSent <= 3 || framesSent % 150 == 0L) {
            log("ВИДЕО: Кадр #$framesSent ${plain.size}B${if (keyFrame) " (key)" else ""} Отправлено")
        }
    }

    /** 128-байтовый заголовок сообщения: длина тела u32LE, код операции, временная метка NTP 32.32 LE, остальные равны нулю. */
    private fun buildHeader(
        opcode: Int,
        bodyLength: Int,
    ): ByteArray {
        val header = ByteArray(HEADER_LEN)
        header[0] = (bodyLength and 0xFF).toByte()
        header[1] = ((bodyLength ushr 8) and 0xFF).toByte()
        header[2] = ((bodyLength ushr 16) and 0xFF).toByte()
        header[3] = ((bodyLength ushr 24) and 0xFF).toByte()
        header[4] = opcode.toByte()
        val nanos = System.nanoTime()
        val seconds = nanos / 1_000_000_000L
        val fraction = ((nanos % 1_000_000_000L) shl 32) / 1_000_000_000L
        val ntp = (seconds shl 32) or fraction
        for (index in 0 until 8) {
            header[8 + index] = ((ntp ushr (8 * index)) and 0xFF).toByte()
        }
        return header
    }

    private fun writeMessage(
        opcode: Int,
        body: ByteArray,
        declaredLength: Int,
        output: java.io.OutputStream,
    ) {
        output.write(buildHeader(opcode, declaredLength))
        output.write(body)
        output.flush()
    }

    /** Разделите поток кода Приложения B на список NAL в соответствии со стартовым кодом 00 00 00 01/00 00 01. */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) {
                    starts += index
                    index += 3
                    continue
                }
                if (index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) {
                    starts += index
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) {
            if (bytes.isNotEmpty()) units += bytes
            return units
        }
        for (unit in 0 until starts.size) {
            val start = starts[unit]
            val prefix = if (bytes[start + 2] == 1.toByte()) 3 else 4
            val bodyStart = start + prefix
            val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
            if (bodyEnd > bodyStart) units += bytes.copyOfRange(bodyStart, bodyEnd)
        }
        return units
    }

    companion object {
        private const val HEADER_LEN = 128
        private const val OP_FRAME = 0
        private const val OP_CONFIG = 1
        private const val OP_KEEP_ALIVE = 2
        private const val OP_KEEP_ALIVE_WITH_BODY = 5
        private const val TAG_SIZE = 16

        /**
         * Флаг части головы  (кошачья игра `ScreenFlag`): запись в `small_param[1]` (смещение головы 6).
         * `Encrypted = 1<<2`: Видеокадр запечатан ChaCha; его необходимо объявить в VideoConfig,
         * Иначе машина будет парсить по простому тексту NAL → декодирование не получится и экран будет черным (сессия и тач пока в норме).
         */
        private const val SCREEN_FLAG_ENCRYPTED = 0x04

        /** Смещение заголовка VideoConfig `params[1]`: 8 + 1×8, два f32 LE = ширина, высота. */
        private const val CONFIG_WIDTH_HEIGHT_OFFSET = 16

        /** Интервал KeepAlive потока экрана (кошачья игра `DEFAULT_KEEP_ALIVE` = 1 с). */
        private const val KEEPALIVE_INTERVAL_MS = 1_000L

        /**
         * создает кадр KeepAlive потока экрана (обычный текстовый заголовок + необязательное тело статистики bplist).
         *
         * разделен на чистые функции для одиночного тестирования: если формат кадра написан неправильно (код
         * операции/поле длины тела/имя статистического ключа тела),
         * Принимающая сторона молча проигнорирует его, не сообщая об ошибке — явление все равно отключается через 10
         * секунд, а затраты на устранение неполадок чрезвычайно высоки.
         */
        internal fun buildKeepAliveFrame(
            withStats: Boolean,
            frames: Int,
            bytesPerSecond: Double,
            framesPerSecond: Int,
        ): ByteArray {
            val body =
                if (withStats) {
                    // Сравнение имен полей Catplay ScreenSenderStats (serde CamelCase)
                    BinaryPlist.encode(
                        linkedMapOf(
                            "txUsageAvg" to bytesPerSecond,
                            "encoderCurrentFPS" to framesPerSecond.toLong(),
                            "sentFramesAvg" to frames.toLong(),
                            "queuedFramesAvg" to 0L,
                            "lossAvg" to 0.0,
                        ),
                    )
                } else {
                    ByteArray(0)
                }
            val header = ByteArray(HEADER_LEN)
            // Макет заголовка: длина тела u32LE, код операции u8, небольшие параметры по 3 байта, параметры по 15 × 8 байт.
            header[0] = (body.size and 0xFF).toByte()
            header[1] = ((body.size ushr 8) and 0xFF).toByte()
            header[2] = ((body.size ushr 16) and 0xFF).toByte()
            header[3] = ((body.size ushr 24) and 0xFF).toByte()
            header[4] = (if (body.isEmpty()) OP_KEEP_ALIVE else OP_KEEP_ALIVE_WITH_BODY).toByte()
            if (body.isNotEmpty()) {
                // params[14] (смещение 8+14*8=120): первые 4 байта 0.0f, последние 4 байта длина тела f32 LE
                writeLittleEndianFloat(header, 120, 0f)
                writeLittleEndianFloat(header, 124, body.size.toFloat())
            }
            return if (body.isEmpty()) header else header + body
        }

        /**
         * Заголовок кадра  VideoConfig (выровнен побайтно по Catplay `ScreenFrame::config`):
         * — `small_param[1]` (смещение 6) Set Encrypted(0x04) — объявляет, что последующие видеокадры запечатаны.
         * - `params[1]` (смещение 16) = ширина и высота f32 LE - машина автомобиля строит холст декодера соответственно
         * - `params[0]` (смещение 8..16) остается 0 - конфиг без PTS
         *
         * разбит на чистые функции для единичного тестирования: когда в реальной машине (Audi 4851) этих двух пунктов не
         * хватает, разговор продолжается, тач нормальный, а экран полностью черный.
         * В журнале           не видно ошибок, что чрезвычайно затрудняет устранение неполадок.
         */
        internal fun buildVideoConfigHeader(
            bodySize: Int,
            width: Int,
            height: Int,
        ): ByteArray {
            val header = ByteArray(HEADER_LEN)
            header[0] = (bodySize and 0xFF).toByte()
            header[1] = ((bodySize ushr 8) and 0xFF).toByte()
            header[2] = ((bodySize ushr 16) and 0xFF).toByte()
            header[3] = ((bodySize ushr 24) and 0xFF).toByte()
            header[4] = OP_CONFIG.toByte()
            header[6] = SCREEN_FLAG_ENCRYPTED.toByte()
            for (index in 0 until 4) {
                val widthBits = width.toFloat().toRawBits()
                header[CONFIG_WIDTH_HEIGHT_OFFSET + index] = ((widthBits ushr (8 * index)) and 0xFF).toByte()
                val heightBits = height.toFloat().toRawBits()
                header[CONFIG_WIDTH_HEIGHT_OFFSET + 4 + index] = ((heightBits ushr (8 * index)) and 0xFF).toByte()
            }
            return header
        }

        private fun writeLittleEndianFloat(
            target: ByteArray,
            offset: Int,
            value: Float,
        ) {
            val bits = value.toRawBits()
            for (index in 0 until 4) {
                target[offset + index] = ((bits ushr (8 * index)) and 0xFF).toByte()
            }
        }
    }
}

/** Целевое значение H.264 level_idc: 4,0 (0x28). Та же форма потока, что и у настоящего iPhone. */
private const val TARGET_LEVEL_IDC = 40

/**
 * Исправьте level_idc SPS до 4.0 (3.1→4.0 — для ослабления ограничений и безопасности).
 *
 * Почему бы вместо этого не использовать MediaFormat.KEY_LEVEL: протестировано (MagicOS/Android 17) кодировщик **Тихий игнор**
 * Этот ключ не генерирует исключение и по-прежнему имеет версию 3.1 в SPS. Возможна только постобработка.
 *
 * Почему   безопасен: ① level_idc имеет фиксированное смещение в SPS (nal[3]), и изменение значения одного байта не меняет длину;
 * ② 3.1→4.0 ослабляет только верхний предел maxMBPS/maxFS/maxBR, а декодер кодирует 3.1 в соответствии с ресурсами, выделенными 4.0.
 * Поток   намного больше; ③ 0x28 не равен нулю и не вводит последовательность предотвращения эмуляции (00 00 0x).
 * Если исходное значение SPS составляет ≥4,0 (например, 4,2 автоматически выбирается при частоте 60 кадров в
 * секунду), исходное значение будет сохранено без понижения.
 *
 * Мотивация  : декодер Audi DELPHI молча не отображает потоки уровня 3.1/3.2 (TCP все еще получает, сеанс нормальный,
 * машина машина черный экран; 22 октября 2026 г. Журнал реальной машины Audi 0628: тот же уровень машины = 40,
 * все успешно, уровень = 32, первый черный экран).
 */
internal fun patchSpsLevel(sps: ByteArray): ByteArray {
    if (sps.size < 4) return sps
    val current = sps[3].toInt() and 0xFF
    if (current >= TARGET_LEVEL_IDC) return sps
    val patched = sps.copyOf()
    patched[3] = TARGET_LEVEL_IDC.toByte()
    return patched
}
