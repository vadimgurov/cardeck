package io.github.shaonialife.cardeck.airplay

import android.content.Context
import android.os.SystemClock
import io.github.shaonialife.cardeck.audio.AudioCodec
import io.github.shaonialife.cardeck.audio.AudioProfile
import io.github.shaonialife.cardeck.audio.AudioSender
import io.github.shaonialife.cardeck.audio.AudioTuning
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.net.EndpointHosts
import io.github.shaonialife.cardeck.net.TcpLiveness
import io.github.shaonialife.cardeck.util.DeviceIdentity
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Клиент AirPlay (фаза 2):
 * - получение списка возможностей /info и анализ bplist
 * — /pair-setup + /pair-verify полное сопряжение (постоянное соединение RTSP при последовательном POST TLV8)
 * Долгосрочный идентификатор контроллера   (ed25519 + идентификатор сопряжения) сохраняется в SharedPreferences после его первой генерации.
 * впоследствии наложит сюда сеанс потоковой передачи SETUP/RECORD.
 */
class AirPlayClient(
    initialEndpoint: Iap2SenderSession.CarPlayEndpoint,
    private val log: (String) -> Unit,
    private val context: Context? = null,
    /**
     * Генерация связи между автомобилем  (для определения изображения 0x1D01 на стороне Bluetooth см. [ReceiverProfile.linkGenerationOf]).
     * 23 октября 2026 г. Используется только для журналов после второго определения — стратегия согласования
     * маркеров унифицирована для всех поколений (согласование ссылок не завершено).
     * Для расцепления машины       требуется ~10 секунд; переключение экрана зависит от режима, измененного после завершения согласования).
     */
    private val linkGeneration: ReceiverProfile.LinkGeneration? = null,
    /**
     * Обновление конечной точки в окне сопряжения (вызывается перед каждым раундом повторных попыток, должно быть
     * неблокирующим): возвращает новые результаты после включения mDNS.
     * Конечная точка      , ноль = нет новой информации. `_airplay._tcp` Audi MIB3 часто появляется в сети после первого анализа.
     * Ранняя реализация       проверялась только один раз перед сопряжением. После сбоя он мог только простаивать
     * адрес шлюза до тех пор, пока не будет исчерпано окно 120 с.
     */
    private val endpointRefresher: ((Iap2SenderSession.CarPlayEndpoint) -> Iap2SenderSession.CarPlayEndpoint?)? = null,
    /**
     * Низкочастотный перехват во время повторных попыток сопряжения (раз в ~10 с, должен быть неблокирующим):
     * используется ведущим для повторного объявления ctrl mDNS.
     * и сообщает о состоянии обнаружения mDNS.
     *
     * Зачем это нужно: 19.10.2026 Audi 0.1.7 В журнале машина** никогда не определяет ctrl и не транслирует.
     * `_airplay._tcp`**, и мы простаивали на шлюзе `:7000` только в течение 54 секунд после того, как истекло время
     * ожидания 4-секундного окна приглашения.
     * (`ECONNREFUSED`), в течение этого периода не будет никаких объявлений или сообщений о том, что «mDNS не найден».
     * Bonjour, возможно, пропустил первое объявление; периодически переигрывать это правда практика iPhone
     * (кошачья игра Force_announce каждые 1 с).
     */
    private val retryTick: ((attempt: Int) -> Unit)? = null,
) {
    /**
     * Принимающая конечная точка, используемая в данный момент. 0x4301 обновляется мастером в горячем режиме, когда
     * он подключается к сети после опоздания/mDNS;
     * Цикл жеребьёвки перечитывается в каждом раунде повторных попыток, и изменения вступают в силу в следующем раунде.
     */
    @Volatile
    private var endpoint: Iap2SenderSession.CarPlayEndpoint = initialEndpoint

    /** Алгебра конечных точек: обеспечение Connected использует его для обнаружения «конечная точка изменилась во время соединения», чтобы избежать отправки запросов на новую конечную точку на старый адрес. */
    @Volatile
    private var endpointGeneration = 0

    private var socket: Socket? = null
    private var cSeq = 0

    /**
     * Адрес, использованный при последнем успешном подключении приемника (в строковой форме, локальный адрес IPv6-связи с `%<iface>`).
     *
     * Автомобили       (особенно Audi) `_airplay._tcp` / 0x4301 часто дают только **IPv6-локальный канал**;
     * Старая реализация       распознает только IPv4 (`contains('.')`). Такие конечные точки будут рассматриваться
     * как «без адреса» и возвращаться.
     * Шлюз:7000 Не волнуйтесь: шлюз/порт автомобиля прошивки 4xxx может быть не .1:7000, поэтому он никогда не будет подключен.
     * теперь напрямую использует адрес, предоставленный Bonjour/0x4301, в соответствии с методом кошачьей игры, с
     * приоритетом IPv4 и покрытием IPv6.
     * и укажите **действительно подключенный адрес** каналу видео/аудио/событий (они должны идти по тому же адресу, что и пара).
     */
    @Volatile
    var activeHost: String? = null
        private set

    /** Результат разрешения подключенного адреса (видео/аудиопорт повторно использует один и тот же InetAddress, чтобы избежать вторичного сбоя разрешения). */
    @Volatile
    private var activeAddress: InetAddress? = null

    /**
     * Служба синхронизации (начальный объявленный порт SETUP; автомобильный NTP пингует его для синхронизации часов мультимедиа).
     * Отложенный запуск, постоянный при повторных попытках (функция close() не останавливает его — сокет UDP не имеет состояния сеанса).
     */
    private val timingServer = TimingServer(log)

    /** Включено после проверки пары: все последующие сообщения RTSP шифруются и инкапсулируются как единое целое. */
    private var cipher: ControlCipher? = null

    /** /информация кэшируется во время фазы установления связи (при согласовании звука используются: audioType/format/delay, выбранные в декларации транспортного средства). */
    @Volatile
    private var negotiatedInfo: Map<*, *>? = null

    // ---- Внешний процесс ----

    /** Отклоняется ли установка пары 403 (реальная функция автомобиля); На основании этого мастер решает, использовать ли пенсионный хак BT. */
    @Volatile
    var pairSetupRejected: Boolean = false
        private set

    /**
     * 403 возникает на **любом этапе** во время сопряжения (раздел настройки пары/проверки пары/аутентификации).
     *
     * реальная машина для захвата пакетов Dingji (21 октября 2026 г., системная диагностика Audi MMI 3315_5 ГГц):
     * впервые для настоящего iPhone
     * Соединение       было отклонено хост-компьютером с кодом 403 после прохождения проверки пары (раздел
     * сертификации MFi), и хост-компьютер самостоятельно отправит сообщение повторно через **18–20 с.
     * ctrl-приглашение, при второй активации Bluetooth продолжит работу на протяжении всего процесса. Таким образом,
     * любая 403 означает, что «iAP2 доверен».
     * Автомобильный автомат, созданный      , ожидает готовности своего собственного конечного автомата» — в это
     * время отключение BT или понижение уровня сеанса открытого текста являются обратными операциями.
     * Правильная позиция — оставить исходный процесс в окне и повторить попытку (основной элемент управления
     * нажимает на эту отметку, чтобы избежать двух путей).
     */
    @Volatile
    var pairingForbidden: Boolean = false
        private set

    /** Действительно ли в этом раунде началось сопряжение (TCP подключился к принимающей стороне). Условия для включения взлома ссылок DiPlay. */
    @Volatile
    var attemptedPairing: Boolean = false
        private set

    /**
     * Приемник  **не поддерживает** соединение с HomeKit вообще (/pair-setup возвращает 404/405/501 или 455).
     *
     * — это старая автомобильная машина до HomeKit (эра CarPlay 210.81, приемник Catplay, категория 1):
     * Они принимают только сеансы открытого текста `et=NONE` + потоки экрана AES-CTR. Продолжайте повторять попытку в окне сопряжения.
     * 120 секунд бессмысленны - фактические пользователи видят «невозможно подключиться» (Audi 4xxx 2026-10-19).
     */
    @Volatile
    var pairingUnsupported: Boolean = false
        private set

    /** Определяет, зашифрован ли канал (проверка пары завершена). false = текстовый сеанс (старая машина/непарная). */
    val controlEncrypted: Boolean get() = cipher != null

    /**
     * пропускает сопряжение с HomeKit и переходит непосредственно к сеансу открытого текста `et=NONE` (старая
     * автомобильная машина до HomeKit).
     *
     * основан на `_airplay._tcp`. TXT имеет необъявленный бит 38 HK_PAIRING_AND_ENCRYPT.
     * (категория приемника Catplay 1: «pre-HomeKit: не имеет /pair-setup и нет
     * HK_PAIRING_AND_ENCRYPT Флаг Bonjour (эра v210 +/-)").
     * Если вы не попытаетесь установить пару, вы можете сохранить запрос, который обречен на провал; если решение
     * TXT неверно, сопряжение все равно будет выполнено.
     * Откат вызывающей стороны при отклонении       SETUP (не хуже старого поведения).
     */
    fun skipPairing(reason: String) {
        pairingUnsupported = true
        log("AIRPLAY: пропустить сопряжение с HomeKit ($reason) — используйте сеанс открытого текста et=NONE + поток экрана AES-CTR.")
    }

    /** Восстановите канал управления (после сбоя/пропуска сопряжения перед НАСТРОЙКОЙ должно быть новое соединение). */
    fun reconnect(timeoutMillis: Int = 5_000): Boolean = ensureConnected(timeoutMillis)

    /**
     * Повторная попытка «обновления шифрования» после отклонения открытого текста SETUP (синоним `handle_setup_result`):
     * Сначала нажмите телефон до HomeKit, чтобы перейти к `et=NONE`. Если установка SETUP отклонена и вы еще не
     * пробовали выполнить сопряжение,
     * Просто выполните настройку/проверку пары, а затем НАСТРОЙКУ. Функции TXT не будут работать напрямую, если решение неверно.
     */
    fun retryWithPairing(windowMillis: Long = 30_000): PairVerifyClient.ControlKeys? {
        pairingUnsupported = false
        close()
        log("AIRPLAY: не удалось выполнить настройку обычного текста — попробуйте зашифрованное обновление (создайте пару с HomeKit)")
        return runPairing(windowMillis)
    }

    /**
     * Сопряжение : попробуйте сначала настроить пару (зарегистрировать долгосрочный открытый ключ) → проверить пару
     * (получить контрольный ключ);
     * Если установка пары отклонена кодом **403** (фактическое испытание Audi MIB3 на реальном автомобиле: этап iAP2 завершил доверие MFi,
     * не принимает окно анонимного сопряжения SRP), затем используйте «быстрый путь сопряженного устройства» для
     * непосредственной проверки сопряжения.
     * использует новое TCP-соединение каждый раз при повторной попытке (некоторые получатели поддерживают конечные
     * автоматы посредством соединения).
     *
     * не прогревается /info: реальный автомобильный тест (11 октября 2026 г.) машина-машина отвечает 455 на /info при непарном соединении
     * , а затем настройка пары в том же соединении — 403; Клиент Apple и Catplay напрямую связаны.
     * Заголовки запросов на сопряжение       дословно выравниваются с помощью Catplay PairingHelperTx (X-Apple-HKP / X-Apple-PD).
     */

    /** Флаг отмены (устанавливается при завершении сеанса): цикл повторной попытки сопряжения завершается немедленно и больше не заполняет окно ожидания. */
    @Volatile
    var cancelled: Boolean = false

    /** Продолжается ли цикл повторных попыток сопряжения (на основании этого мастер определяет, следует ли «конечную точку позднего прибытия» обновлять в горячем режиме или игнорировать). */
    @Volatile
    var pairingInProgress: Boolean = false
        private set

    /** Текущая конечная точка (используется в качестве резервной, когда мастер поздно объединяет конечную точку, чтобы избежать стирания доступного адреса, когда у 0x4301 нет IP-адреса). */
    val currentEndpoint: Iap2SenderSession.CarPlayEndpoint get() = endpoint

    /**
     * Конечная точка приемника горячего обновления (0x4301 позднее прибытие/новый результат mDNS во время сопряжения).
     * Закройте текущий TCP при изменении адреса или порта, позволяя циклу сопряжения повторно подключиться в
     * соответствии с новым адресом в следующем раунде.
     *
     * @return true = конечная точка действительно изменилась (переключилась); false = То же, что и текущий, ничего не было сделано.
     */
    fun updateEndpoint(
        newEndpoint: Iap2SenderSession.CarPlayEndpoint,
        reason: String,
    ): Boolean {
        val old = endpoint
        val unchanged =
            old.ipAddresses == newEndpoint.ipAddresses &&
                old.airPlayPort == newEndpoint.airPlayPort &&
                old.publicKey == newEndpoint.publicKey &&
                old.deviceIdentifier == newEndpoint.deviceIdentifier
        if (unchanged) return false
        endpoint = newEndpoint
        endpointGeneration++
        log(
            "AIRPLAY: обновление конечной точки ($reason)" +
                "${old.ipAddresses.firstOrNull()}:${old.airPlayPort} → " +
                "${newEndpoint.ipAddresses.firstOrNull()}:${newEndpoint.airPlayPort}" +
                "(На следующем этапе цикла сопряжения используется новый адрес)",
        )
        close()
        return true
    }

    fun runPairing(windowMillis: Long = PAIRING_WINDOW_MILLIS): PairVerifyClient.ControlKeys? {
        pairingInProgress = true
        try {
            return runPairingLoop(windowMillis)
        } finally {
            pairingInProgress = false
        }
    }

    private fun runPairingLoop(windowMillis: Long): PairVerifyClient.ControlKeys? {
        val identity = loadOrCreateIdentity()
        log("AIRPLAY: идентификатор пары идентификаторов контроллера = ${identity.pairingId}")
        // Окно AirPlay «автомобиль-машина» «открывается после того, как пользователь нажмет на значок мобильного
        // телефона на автомобиле/получит приглашение ctrl»

        // (Фактическое измерение Audi: порт меняется с «Соединение отказывается открываться»). Итак, предел при
        // повторной попытке усыновления:

        // По умолчанию попытки каждые 2 секунды в течение 120 секунд — ожидание нажатия пользователем на автомобиль не
        // будет прервано заранее.

        val deadline = System.currentTimeMillis() + windowMillis
        var attempt = 0
        while (System.currentTimeMillis() < deadline && !cancelled) {
            attempt++
            // Поглощайте mDNS один раз перед каждым раундом повторных попыток. Новый результат: когда адрес шлюза не может
            // быть подключен, автомобиль

            // _airplay._tcp, возможно, обнаружил в фоновом режиме, что своевременное переключение лучше, чем простоя в течение 120 секунд.
            endpointRefresher?.invoke(endpoint)?.let { refreshed ->
                updateEndpoint(refreshed, "Обновление mDNS во время сопряжения")
            }
            // Низкочастотный перехват: мастер повторно объявляет ctrl mDNS соответственно (автомобиль Bonjour может
            // пропустить первое объявление)

            if (attempt == 1 || attempt % RETRY_TICK_ATTEMPTS == 0) {
                runCatching { retryTick?.invoke(attempt) }
            }
            close()
            if (!ensureConnected(2_000)) {
                if (attempt == 1 || attempt % 5 == 0) {
                    log(
                        "AIRPLAY: порт AirPlay в автомобиле не открыт (ожидает, пока вы нажмете на значок мобильного " +
                            "телефона на автомобиле / автомобиль отправит приглашение ctrl)... Повторите попытку в течение времени $attempt.",
                    )
                }
                Thread.sleep(2_000)
                continue
            }
            attemptedPairing = true
            val transport = TlvTransport { path, body -> postPairing(path, body) }
            try {
                val setupStatus =
                    try {
                        PairSetupClient(identity, transport, log).run()
                        null
                    } catch (error: IOException) {
                        val message = error.message.orEmpty()
                        val forbidden = message.contains("403")
                        if (forbidden) {
                            pairSetupRejected = true
                            pairingForbidden = true
                        }
                        val unsupported = PAIRING_UNSUPPORTED_STATUS.any { message.contains(it) }
                        log(
                            "AIRPLAY: pair-setup " +
                                when {
                                    forbidden ->
                                        "Отклонено 403 (автомобиль не принимает анонимное сопряжение - iAP2 " +
                                            "завершил доверие) → переход на сопряженный быстрый путь"
                                    unsupported -> "Не поддерживается ($message) — старый приёмник; переход на нешифрованный сеанс"
                                    else -> "Сбой ($message)"
                                },
                        )
                        if (unsupported) {
                            pairingUnsupported = true
                            close()
                            return null
                        }
                        if (forbidden) "403" else "failed"
                    }
                // После 403 конечный автомат старого соединения недоступен: перейдите на новое соединение и выберите быстрый путь.
                if (setupStatus == "403") {
                    close()
                    if (!ensureConnected(2_000)) continue
                    val fastPathTransport = TlvTransport { path, body -> postPairing(path, body) }
                    val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                    val keys = PairVerifyClient(identity, fastPathTransport, log).run(receiverStaticKey)
                    cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                    log("AIRPLAY: шифрование канала управления включено (спаренный быстрый путь)")
                    return keys
                }
                val receiverStaticKey = endpoint.publicKey?.let(::hexToBytes)
                val keys = PairVerifyClient(identity, transport, log).run(receiverStaticKey)
                // Шифрование включается с обеих сторон сразу после проверки пары (принимающая сторона включается после ответа M4)
                cipher = ControlCipher(keys.phoneReadKey, keys.phoneWriteKey)
                log("AIRPLAY: шифрование канала управления включено.")
                return keys
            } catch (error: IOException) {
                val message = error.message.orEmpty()
                // 403 в разделе проверки/аутентификации имеет то же происхождение, что и настройка пары 403 (установлено доверие
                // между транспортным средством и машиной),

                // См. сопряжениеЗапрещено. Примечание. После маркировки мастер больше не будет прерывать BT/не будет понижать
                // версию обычного текста.

                if (message.contains("403")) pairingForbidden = true
                log("AIRPLAY: попытка сопряжения $attempt не удалась — $message")
                if (PAIRING_UNSUPPORTED_STATUS.any { message.contains(it) }) {
                    pairingUnsupported = true
                    log(
                        "AIRPLAY: принимающая сторона не предоставляет канал сопряжения с HomeKit — прекратите повторные " +
                            "попытки и преобразуйте сеанс в открытый текстовый формат (et=NONE)",
                    )
                    close()
                    return null
                }
                // 403 Backoff (перехват пакетов на реальной машине: машине требуется 18-20 секунд, чтобы войти в приемлемое
                // состояние после отклонения первого соединения.

                // В течение этого периода частые повторные попытки могут только увеличить нагрузку на транспортное средство и
                // могут продолжать сбрасывать его конечный автомат готовности);

                // На случай других сбоев оставьте быстрый опрос и другие порты открытыми.
                Thread.sleep(pairingRetryBackoffMs(message))
            }
        }
        log("AIRPLAY: сопряжение в конечном итоге не удалось (приемник RTSP не отвечает)")
        return null
    }

    /** Извлеките и проанализируйте /info (примечание: принимающая сторона может не отвечать во время активного сеанса). */
    fun fetchInfo(timeoutMillis: Int = 5_000): Map<*, *>? {
        if (!ensureConnected()) return null
        return try {
            val (statusLine, body) = requestInfo(timeoutMillis)
            log("AIRPLAY: ✓ /info [$statusLine] body=${body.size} байт")
            if (body.isEmpty()) return null
            val root = BinaryPlist.parse(body)
            if (root is Map<*, *>) {
                logInfoSummary(root)
                root
            } else {
                null
            }
        } catch (error: IOException) {
            log("AIRPLAY: /info не удалось — ${error.message}")
            null
        }
    }

    /**
     * Контрольный сигнал KeepAlive UDP (сравните с KeepAliveClient): отправьте 4 байта в порт KeepAlivePort автомобиля.
     * Случайный UDP; протокол, нет ответа. **При отсутствии сторожевой таймер 10s отключит весь сеанс**.
     *
     * Интервал       составляет **5 секунд** вместо 10 секунд кошачьей игры: 10 секунд соответствуют длине
     * автомобильного сторожевого таймера `Thread.sleep`.
     * Джиттер на миллисекундном уровне       приведет к тому, что каждый пульс будет приходить на 1 мс позже
     * установленного срока (фактическое измерение Audi 3315 с двумя колесами 2026-10-19:
     * пинг № 2 отстает ровно на 10,001 с от № 1, а затем весь раунд сбрасывается в течение 213 ~ 247 мс, 10,2 с после ЗАПИСИ).
     * Десятки кошачьей игры       применимы только к собственному приемнику; для реальных машин необходимо оставить
     * хотя бы половину запаса.
     */
    fun startKeepAlive(
        host: String,
        port: Int,
    ) {
        if (port <= 0) return
        Thread({
            var udp: java.net.DatagramSocket? = null
            try {
                udp = java.net.DatagramSocket()
                val address = java.net.InetAddress.getByName(host)
                var sent = 0
                var lastSentAt = 0L
                while (isSessionAlive() && udp?.isClosed == false) {
                    val now = SystemClock.uptimeMillis()
                    val gap = if (lastSentAt == 0L) 0L else now - lastSentAt
                    lastSentAt = now
                    val payload = ByteArray(4).also(java.security.SecureRandom()::nextBytes)
                    udp.send(java.net.DatagramPacket(payload, payload.size, address, port))
                    sent++
                    if (sent <= 3 || sent % 60 == 0) {
                        log("AIRPLAY: KeepAlive ping #$sent → $host:$port (интервал ${gap}ms)")
                    }
                    Thread.sleep(KEEPALIVE_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
            } catch (error: IOException) {
                log("AIRPLAY: KeepAlive завершается — ${error.message}")
            } finally {
                udp?.close()
            }
        }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * RTSP / сигнал обратной связи (сравните catplay FEEDBACK_INTERVAL = 1000 мс): пустое тело POST каждую секунду
     * /feedback — активный сторожевой таймер на принимающей стороне. Транзакции RTSP сериализуются [rtspLock].
     */
    fun startFeedbackLoop() {
        Thread({
            var sent = 0
            while (isSessionAlive()) {
                try {
                    val (status, body) = request("POST", "/feedback", ByteArray(0))
                    sent++
                    if (sent <= 6 || sent % 30 == 0) {
                        log("AIRPLAY: feedback #$sent [$status]${if (body.isNotEmpty()) " body=${body.size}B" else ""}")
                    }
                    // Обратная связь машина-машина содержит статус каждого потока (получен или нет, недогружен): это самое прямое,
                    // когда звук молчит.

                    // Критерий - возможность отличить «транспортное средство не получило посылку» от «посылка была получена, но не
                    // передана» (17.10.2026).

                    // Первые 8 полных обращений (приблизительно охватывающие 8 секунд после начала потока, что соответствует
                    // 6,5-секундному сторожевому окну удаления цепочки):

                    // Будет ли значение sampleTime аудиопотока двигаться вперед от 0, является убедительным свидетельством того, был
                    // ли использован пакет.

                    if (body.isNotEmpty() && (sent <= 8 || sent % 30 == 0)) {
                        logFeedbackBody(body)
                    }
                } catch (_: IOException) {
                    break // Соединение потеряно (сессия завершена)
                }
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "airplay-feedback").apply {
            isDaemon = true
            start()
        }
    }

    /** Анализируйте и распечатывайте ответы / отзывы (bplist). Полевое сравнение кошачьей игры `StreamFeedback`:
     * sr/type/streamConnectionID/sampleTime/timestamp/timestampRawNs
     */
    private fun logFeedbackBody(body: ByteArray) {
        val root =
            runCatching { BinaryPlist.parse(body) as? Map<*, *> }.getOrNull()
        if (root == null) {
            log(
                "AIRPLAY: не удалось проанализировать тело обратной связи (${body.size}B) " +
                    "hex=${body.joinToString("") { "%02x".format(it) }}",
            )
            return
        }
        val streams = root["streams"] as? List<*>
        if (streams == null) {
            log("AIRPLAY: обратная связь без ключа потоков (keys=${root.keys}) полное поле=$root")
            return
        }
        streams.forEachIndexed { index, entry ->
            val stream = entry as? Map<*, *> ?: return@forEachIndexed
            log(
                "AIRPLAY: feedback.stream[$index] type=${stream["type"]} sr=${stream["sr"]} " +
                    "connId=${stream["streamConnectionID"]} sampleTime=${stream["sampleTime"]} " +
                    "ts=${stream["timestamp"]} tsRawNs=${stream["timestampRawNs"]}Полное поле=$stream",
            )
        }
    }

    /** Критерий выживания потока пульса: сокет RTSP все еще подключен. */
    private fun isSessionAlive(): Boolean = socket?.isClosed == false

    fun close() {
        runCatching { socket?.close() }
        socket = null
        activeHost = null
        activeAddress = null
        // Новые соединения всегда начинаются с **открытого текста** (шифрование переключается после успешной проверки
        // пары): шифр старого соединения должен быть

        // Очистите его, иначе поток чтения после повторного подключения будет интерпретировать ответ в виде открытого
        // текста как кадр зашифрованного текста, и время ожидания всех последующих запросов истечет.

        cipher = null
        synchronized(rtspWriteLock) {
            readerSocket = null
            readerThread = null
            readerGeneration++
        }
        failAllPending("Канал управления закрыт")
        runCatching { iapTunnel?.socket?.close() }
        iapTunnel = null
        iapLink = null
        // Примечание: controlIapTx здесь не означает выключение — функция close() будет повторена по тому же пути экземпляра.
        // (retryWithPairing и т. д.) повторное использование, уничтожение исполнителя приведет к уничтожению исходящего
        // MIB3 после повторного подключения;

        // Стоимость резидентных потоков демона незначительна (по одному на экземпляр клиента).
    }

    /**
     * объявляет режим ввода HID (hidSetInputMode, направление FromDevice — сенсорная команда включения мобильного телефона → автомобиль).
     * сравнить кошачью игру CommandHidSetInputMode {hidInputMode u8, строка uuid};
     * uuid — это шестнадцатеричная строка /info hidDevices[].uuid (тот же uid.toString(16) из DiPlay sendHidReport).
     * Функции дисплея Audi = 0x08 (высокоточное сенсорное управление) + hidУстройства готовы, самое большое
     * подозрение заключается в том, что при прикосновении нет сообщения
     * У       этого оружия нет.
     */
    fun sendHidSetInputMode(
        deviceUuidHex: String,
        mode: Int = 0,
    ): Boolean {
        val sent =
            postEventCommand(
                linkedMapOf(
                    "type" to "hidSetInputMode",
                    "params" to
                        linkedMapOf<String, Any?>(
                            "hidInputMode" to mode.toLong(),
                            "uuid" to deviceUuidHex,
                        ),
                ),
            )
        log("AIRPLAY: hidSetInputMode (канал событий) uuid=$deviceUuidHex mode=$mode ok=$sent")
        return sent
    }

    /**
     * объявляет владение ресурсом (modesChanged): мобильный телефон удерживает главный экран + основной звук
     * (сравните утвержденные_режимы catplay).
     * Последовательность проверки реального автомобиля: Изменены режимы → НАСТРОЙКА экрана. Пропустите это
     * утверждение при сопряжении Audi MIB3.
     * поток экрана SETUP Direct 400 (сбой арбитража ресурсов). appStates: телефон/навигация возвращает None (0),
     * Голос Нет (-1); ресурсы: главный экран (1)/основной звук (2),entity=permanent=Controller(1).
     */

    // ----Отправка команды канала события (сравните с DiPlay sendCommandLocked) ----
    // Команды транспортного средства и машины проходят через команду POST / в сокете канала событий (а не через
    // соединение управления RTSP!):

    // iAPSendMessage (байты виртуальной ссылки), hidSetInputMode, modeChanged и т. д.
    // Асинхронный принцип «выстрелил и забыл»: неравный ответ (тот же метод записи, что и у DiPlay), независимый подсчет CSeq.

    @Volatile
    private var eventCommandSocket: Socket? = null

    /** null = текстовый канал событий (et=NONE сеанс старой машины, кошачья игра `EventsServer::unencrypted`). */
    @Volatile
    private var eventCommandCipher: ControlCipher? = null

    private val eventCommandLock = Any()
    private var eventCseq = 0

    /** Совокупное количество неизвестных запросов (законных запросов, кроме /command): тишина после первых 10 трассировок. */
    @Volatile
    private var unknownEventRequests = 0

    /** Совокупное количество списков в /command, которые невозможно решить: первые 8 полос имеют исходные шестнадцатеричные трассировки (проверьте путь команды машина-машина). */
    @Volatile
    private var badEventCommands = 0

    /**
     * Неразрешимый «отпечаток размера» (длина + первые 8 байт) команды /command.
     *
     * обычно каждую секунду повторно отправляет один и тот же пакет команд. После удаления дубликатов по отпечатку
     * пальца будет собран только полный текст **самой новой** формы тела.
     * не обновляет экран и не пропускает ни одну категорию (ранее обрезалось только по количеству позиций, но если
     * вы измените модель автомобиля, вы увидите только первые 8 позиций).
     */
    private val badEventSignatures = LinkedHashSet<String>()

    /** После того как канал событий установлен, он вызывается методом ConnectEventChannel. cipher=null указывает на канал открытого текста. */
    private fun registerEventCommandChannel(
        socket: Socket,
        cipher: ControlCipher?,
    ) {
        eventCommandSocket = socket
        eventCommandCipher = cipher
    }

    /** Отправьте команду POST/команду на канал событий (зашифрованную, когда канал зашифрован). Возвращает false, если канал не готов. */
    fun postEventCommand(command: Map<String, Any?>): Boolean {
        val socket = eventCommandSocket ?: return false
        val cipher = eventCommandCipher
        return try {
            synchronized(eventCommandLock) {
                eventCseq++
                val body = BinaryPlist.encode(command)
                val head =
                    "POST /command RTSP/1.0\r\n" +
                        "Content-Type: application/x-apple-binary-plist\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "CSeq: $eventCseq\r\n\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII) + body
                val bytes = cipher?.encrypt(plaintext) ?: plaintext
                socket.getOutputStream().apply {
                    write(bytes)
                    flush()
                }
            }
            true
        } catch (error: IOException) {
            log("СОБЫТИЕ: не удалось отправить команду — ${error.message}")
            false
        }
    }

    /**
     * Ответ на запрос канала событий : отвечайте только на **запрос транспортного средства** RTSP/1.0 200 OK и повторяйте его CSeq
     * (То же, что и DiPlay buildResponse, сравните RtspMessage.kt:76). Отчет о ловушках (11 октября 2026 г.):
     * ранее отвечал 200 на все входящие сообщения — ответ «ответ» отправлялся через DiPlay: DiPlay помещал наши
     * Избыточный ответ анализируется в запрос («RTSP/1.0», при удалении метода протокол получает третий токен),
     * возвращает неверную строку «ОК 200 ОК»; мы вернемся → бесконечное эхо, лавина журналов и пустой зашифрованный канал.
     * Запись канала       и postEventCommand совместно используют блокировку, чтобы избежать пересечения зашифрованных кадров двух потоков.
     */
    private fun respondEventRequest(cseq: String?) {
        val socket = eventCommandSocket ?: return
        val cipher = eventCommandCipher
        try {
            synchronized(eventCommandLock) {
                val head =
                    "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n" +
                        (cseq?.let { "CSeq: $it\r\n" } ?: "") + "\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII)
                socket.getOutputStream().apply {
                    write(cipher?.encrypt(plaintext) ?: plaintext)
                    flush()
                }
            }
        } catch (_: IOException) {
        }
    }

    fun assertModes(): Boolean {
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "type" to "modesChanged",
                    "params" to
                        linkedMapOf(
                            "appStates" to
                                listOf(
                                    linkedMapOf<String, Any?>("appStateID" to 2L, "entity" to 0L),
                                    linkedMapOf<String, Any?>("appStateID" to 3L, "entity" to 0L),
                                    linkedMapOf<String, Any?>("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L),
                                ),
                            "resources" to
                                listOf(
                                    linkedMapOf<String, Any?>("resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L),
                                    linkedMapOf<String, Any?>("resourceID" to 2L, "entity" to 1L, "permanentEntity" to 1L),
                                ),
                        ),
                ),
            )
        return try {
            val (statusLine, _) =
                request(
                    "POST",
                    "/command",
                    body,
                    contentType = "application/x-apple-binary-plist",
                )
            log("AIRPLAY: режимы изменены (объявляет удержание главного экрана/основного аудиоресурса) [$statusLine]")
            statusLine.contains("200")
        } catch (error: IOException) {
            log("AIRPLAY: не удалось изменить режимы — ${error.message}")
            false
        }
    }

    /**
     * НАСТРОЙКА №1: отправить информацию о сеансе, получить eventPort/timingPort/enabledFeatures.
     *
     * Функции       должны отправлять «Полный список возможностей iPhone»: Receiver Computing
     * включенОсобенности = поддержка автомобиля ∩ предложение по мобильному телефону, **возможности, не включенные в
     * предложение, не будут включены**.
     * 11.10.2026 Фактический тестовый урок Audi: предлагайте ["videoPlayback"] только после возвращения автомобиля
     * EnableFeatures=[] (видеоконвейер не включен), НАСТРОЙКА последующего потока экрана равна 400.
     * игра для сравнения списков (отправитель проверки реального автомобиля, включая необходимые элементы видео,
     * такие как viewAreas/uiContext).
     */
    fun runSetup(): SetupInfo? {
        // Порт синхронизации: реализация реального автомобиля catplay всегда привязана к реальному серверу синхронизации
        // UDP и уведомляется об этом на машине автомобиля.

        // (Источник синхронизации медиа-часов); TimingPort=0 раньше был нашим подозреваемым номер один в отношении Screen Stream 400.
        if (!timingServer.start()) {
            log("AIRPLAY: служба синхронизации не запущена (продолжается, но часы мультимедиа не могут быть синхронизированы)")
        }
        val deviceId = setupDeviceId()
        val body = buildSessionSetupBody(deviceId, timingServer.port, encrypted = cipher != null)
        log(
            "AIRPLAY: Начальная настройка body=${body.size}B TimingPort=${timingServer.port}" +
                "deviceID=$deviceId шифрование=${if (cipher != null) "Да" else "Нет（et=NONE）"}",
        )
        val (statusLine, responseBody) =
            try {
                // URI запроса использует относительный путь `/setup` (так же, как в AirPlayRtspClient::initial_setup):
                // Некоторые старые прошивки (эпохи 210.81) распознают только относительные URI, и отправка абсолютного
                // `rtsp://host:port/` приведет к 404/400.

                request(
                    "SETUP",
                    "/setup",
                    body,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: Ошибка настройки — ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log("AIRPLAY: ответ НАСТРОЙКИ [$statusLine] (ID устройства, сообщаемый пользователем = $deviceId)")
            if (statusLine.contains("403")) {
                // Два типа причин ошибки 403 упорядочены по вероятности: фильтрация устройств (WinPlay и т. д. «Принимать только указанные
                // iPhone") является наиболее распространенным, за ним следует статус сопряжения/доверительности, не принятый
                // принимающей стороной. поставь эту машину

                // Адрес Bluetooth вводится напрямую, и пользователь может проверить его, сравнив настройки ресивера.
                log(
                    "AIRPLAY: устранение неполадок 403 – если на принимающей стороне включена фильтрация устройств, " +
                        "выберите локальный адрес Bluetooth на принимающей стороне." +
                        "$deviceId" +
                        if (deviceId == DeviceIdentity.PLACEHOLDER_MAC) {
                            "(В настоящее время значение-заполнитель: невозможно определить реальный MAC-адрес Bluetooth, Шизуку готова?)"
                        } else {
                            ""
                        },
                )
            }
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody)
            } catch (error: IllegalArgumentException) {
                log("AIRPLAY: не удалось проанализировать список ответов SETUP (байты ${responseBody.size})")
                null
            } as? Map<*, *>
        if (root == null) {
            log("AIRPLAY: ответ SETUP не является словарем")
            return null
        }
        // Вытяните основные параметры дисплея (следуйте направлению экрана принимающей стороны; автомобильная машина с
        // горизонтальным экраном должна толкать экран горизонтального экрана, и координаты касания также должны быть
        // выровнены соответствующим образом)

        // Этот путь **не может выйти из строя автоматически**:/info Зависание означает геометрию дисплея, HID-устройство
        // (сенсорное управление) и возможности звука.

        // Все возвращаются к жестко запрограммированным значениям. 20.10.2026 BMW iD9 здесь молча провалился, оставив только
        // `hidDevices uuids=[]` / `displayUuid=null` два результата, причины не видно.
        var infoRoot: Map<*, *>? = null
        val infoMap = loadInfoWithDiagnostics()
        infoRoot = infoMap
        val display = (infoMap?.get("displays") as? List<*>)?.firstOrNull() as? Map<*, *>
        if (display == null) {
            log(
                "AIRPLAY: /info не отображает → Параметры дисплея возвращаются к разрешению телефона." +
                    "(Со стороны автомобиля может быть небольшой экран/черная рамка); HID-устройство пусто → машина не " +
                    "отправит hidSendReport (тач недоступен)",
            )
        }
        // /info полный дамп: найдите UUID частного потока данных Audi (кандидат clientTypeUUID типа 130),
        // Описание устройства HID (сенсорное управление) и любые декларации возможностей, которые мы не реализовали.
        // uuid сохраняется как есть (Audi отправляет строку «0»/«1», DiPlay использует целое число — hidSetInputMode
        // возвращает его как есть)

        val parsedHidUuids = ArrayList<String>()
        // uuid → HID дескриптор (для получения информации об использовании необходимо нажать записи медиа-ключей,
        // сенсорные записи не нужны, но сохраняются вместе)

        val parsedHidDescriptors = HashMap<String, ByteArray>()
        infoRoot?.let { root ->
            val skipped = setOf("displays")
            val summary =
                root.entries.joinToString("; ") { (key, value) ->
                    if (key in skipped) "$key=[${(value as? List<*>)?.size ?: "?"}предмет]" else "$key=${value.toString().take(200)}"
                }
            log("AIRPLAY: /информационное поле: $summary")
            // отображения были напечатаны одно за другим в logInfoSummary (включая сведения о значениях ключей) и не будут
            // повторяться здесь.

            // Возможности аудио выводятся по одному (вся строка /info обрезается командой take(200), а audioFormats
            // разрывается ровно посередине):

            // Единственный способ определить audioType - здесь - Audi type=100 заявляет о совместимости или носителе?
            (root["audioFormats"] as? List<*>)?.forEachIndexed { index, entry ->
                val format = entry as? Map<*, *> ?: return@forEachIndexed
                val output = (format["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                val input = (format["audioInputFormats"] as? Number)?.toLong() ?: 0L
                log(
                    "AIRPLAY: audioFormat[$index] type=${format["type"]} audioType=${format["audioType"]} " +
                        "output=0x${output.toString(16)} input=0x${input.toString(16)}" +
                        "（PCM44.1k/48k stereo=" +
                        "${if ((output and (1L shl 11)) != 0L) "✓" else "✗"}" +
                        "${if ((output and (1L shl 15)) != 0L) "✓" else "✗"}，" +
                        "AAC-LC 44.1k=${if ((output and (1L shl 22)) != 0L) "✓" else "✗"}）",
                )
            }
            (root["audioLatencies"] as? List<*>)?.forEachIndexed { index, entry ->
                val latency = entry as? Map<*, *> ?: return@forEachIndexed
                log(
                    "AIRPLAY: audioLatency[$index] type=${latency["type"]} audioType=${latency["audioType"]} " +
                        "in=${latency["inputLatencyMicros"]} out=${latency["outputLatencyMicros"]}",
                )
            }
            for (hidKey in listOf("hidDevices", "hids", "hid")) {
                (root[hidKey] as? List<*>)?.forEachIndexed { index, h ->
                    log("AIRPLAY: /info $hidKey[$index]=$h")
                    if (h is Map<*, *>) {
                        // Фактическое измерение Audi: uuid — это строка «0»/«1» (диагностика 13:38 uuidClass=String);
                        // DiPlay — целое число. Преобразуйте строки единообразно, hidSetInputMode/hidSendReport возвращает как есть
                        val uuid =
                            when (val raw = h["uuid"]) {
                                is Number -> raw.toString()
                                is String -> raw
                                else -> null
                            }
                        if (uuid != null) parsedHidUuids += uuid
                        (h["hidDescriptor"] as? ByteArray)?.let { descriptor ->
                            log(
                                "AIRPLAY: hidDescriptor[$index] ${descriptor.size}B hex=" +
                                    descriptor.take(96).joinToString(" ") { "%02x".format(it) },
                            )
                            // Порядок использования медиа-ключей в массиве (Consumer Control) можно получить только из дескриптора:
                            // Array сообщает о байтах как о «индексах», а не об идентификаторах использования (см. примечание
                            // ConsumerKeys).

                            if (uuid != null) parsedHidDescriptors[uuid] = descriptor
                        }
                    }
                }
            }
        }
        log("AIRPLAY: результат анализа hidDevices uuids=$parsedHidUuids")
        val info =
            SetupInfo(
                eventPort = portOf(root["eventPort"]),
                timingPort = portOf(root["timingPort"]),
                keepAlivePort = root["keepAlivePort"]?.let(::portOf)?.takeIf { it != 0 },
                enabledFeatures = (root["enabledFeatures"] as? List<*>)?.map { it.toString() }.orEmpty(),
                displayWidth = ((display?.get("widthPixels") as? Long) ?: 1080L).toInt(),
                displayHeight = ((display?.get("heightPixels") as? Long) ?: 2320L).toInt(),
                // UUID дисплея /info отображает[0] (настоящий iPhone заполнит его в потоке экрана SETUP;
                // Имя ключа основано на строчной букве «uuid», правильное значение — «UUID» в верхнем регистре)
                displayUuid =
                    uuidText(
                        display?.get("uuid")
                            ?: display?.get("UUID")
                            ?: display?.get("displayUUID"),
                    ),
                maxFps = ((display?.get("maxFPS") as? Long) ?: 30L).toInt(),
                hidDeviceUuids = parsedHidUuids,
                hidDescriptors = parsedHidDescriptors,
                // Audi MMI 3315 заявляет правду: поток экрана KeepAlive с кодом операции = 5 с телом статистики bplist
                keepAliveSendStatsAsBody = (infoRoot?.get("keepAliveSendStatsAsBody") as? Boolean) == true,
            )
        log(
            "AIRPLAY: ✓ НАСТРОЙКА завершена eventPort=${info.eventPort} TimingPort=${info.timingPort}" +
                "keepAlive=${info.keepAlivePort ?: "Нет"} features=${info.enabledFeatures} " +
                "display=${info.displayWidth}x${info.displayHeight}@${info.maxFps} " +
                "displayUuid=${info.displayUuid}",
        )
        return info
    }

    /**
     * извлекает `/info` и выполняет полную экспертизу (строка состояния/длина тела/исключение синтаксического
     * анализа/исходный шестнадцатеричный код).
     *
     * ранее записывался как `catch (_: Exception) { null }` - машина замолчала полностью, как только отказалась (20.10.2026
     * BMW iD9: строка журнала `/info Поле` вообще не отображается, только `uuids=[]` и `displayUuid=null`).
     * Повторное восстановление при отклонении в течение сеанса. **Обнаружение независимого короткого соединения**:
     * служба AirPlay на принимающей стороне обычно разрешает отсоединение
     * `/info` сеанса       (запрос возможностей), тогда как `/info` в активном сеансе часто отклоняется 455/403.
     */
    private fun loadInfoWithDiagnostics(): Map<*, *>? {
        var statusLine = "Не запрошено"
        var body = ByteArray(0)
        try {
            val first = requestInfo()
            statusLine = first.first
            body = first.second
            if (!statusLine.contains("200") || body.isEmpty()) {
                log("AIRPLAY: /info first time [$statusLine] body=${body.size}B → повторите попытку через 800 мс.")
                Thread.sleep(800)
                ensureConnected(2_000)
                val retry = requestInfo(5_000)
                log("AIRPLAY: /info Повторить попытку [${retry.first}] body=${retry.second.size}B")
                if (retry.first.contains("200") && retry.second.isNotEmpty()) {
                    statusLine = retry.first
                    body = retry.second
                }
            }
        } catch (error: Exception) {
            log("AIRPLAY: исключение запроса /info — ${error.javaClass.simpleName}: ${error.message}")
        }
        parseInfoBody(statusLine, body)?.let { return it }
        scheduleSideProbe()
        return null
    }

    /**
     * задерживает [delayMillis], а затем занимает побочный канал `/info`.
     *
     * Почему бы не исследовать место происшествия: первые 20 секунд после установления сеанса являются наиболее
     * важным периодом сбора доказательств (подписка на транспортное средство и машину, `/command` в секунду,
     * Туннель CSM). Если вторичное соединение позволит машине прервать основной сеанс, мы потеряем весь пакет
     * доказательств. Даже если что-то пойдет не так с отложенным обнаружением,
     * также может вернуться с полными журналами, которые можно найти.
     *
     * будет перевыдан немедленно, если будет обнаружено HID-устройство. `hidSetInputMode` - касание может сработать
     * на месте (определена геометрия дисплея,
     * изменить нельзя, но можно добавить входной путь без перестроения сеанса).
     */
    private fun scheduleSideProbe(delayMillis: Long = 25_000) {
        Thread(
            {
                runCatching { Thread.sleep(delayMillis) }
                val info = probeInfoOnSideConnection() ?: return@Thread
                val uuids =
                    (info["hidDevices"] as? List<*>).orEmpty().mapNotNull { entry ->
                        val hid = entry as? Map<*, *> ?: return@mapNotNull null
                        when (val raw = hid["uuid"]) {
                            is Number -> raw.toString()
                            is String -> raw
                            else -> null
                        }
                    }
                if (uuids.isEmpty()) {
                    log(
                        "AIRPLAY: боковой канал/информация прошла успешно, но автомобиль не объявляет HID-устройство → " +
                            "доступ к HID-каналу AirPlay недоступен касанием",
                    )
                    return@Thread
                }
                log(
                    "AIRPLAY: боковой канал получает HID-устройство $uuids → перезапустите hidSetInputMode (касание " +
                        "может вступить в силу немедленно)",
                )
                uuids.forEach { uuid -> runCatching { sendHidSetInputMode(uuid, mode = 0) } }
            },
            "info-side-probe",
        ).apply { isDaemon = true }.start()
    }

    /** `/info` сообщение → словарь. В случае неудачи запишите причину в исходном шестнадцатеричном формате и кэшируйте ее в negoitedInfo. */
    private fun parseInfoBody(
        statusLine: String,
        body: ByteArray,
    ): Map<*, *>? {
        log("AIRPLAY: /info ответ [$statusLine] body=${body.size}B")
        if (body.isEmpty()) return null
        val info =
            try {
                val result = BinaryPlist.parseRecovered(body)
                result.note?.let { log("AIRPLAY: /info $it") }
                result.value as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                log("AIRPLAY: ошибка анализа /info plist — ${error.message}")
                log("AIRPLAY: /info исходное тело (${body.size}B) hex=" + hexDump(body, 320))
                null
            }
        if (info == null) return null
        logInfoSummary(info)
        negotiatedInfo = info
        return info
    }

    /**
     * Независимое обнаружение короткого соединения `/info` (без шифрования, без сеанса): гарантия в случае отклонения в сеансе.
     *
     * лишь создает новый TCP, отправляет `GET /info` и закрывает его после прочтения сообщения — не трогая конечный
     * автомат канала управления.
     * Если машина       его не распознает (455/403/таймаут), то он просто будет работать впустую и не повлияет на
     * уже запущенный видеопоток.
     */
    private fun probeInfoOnSideConnection(timeoutMillis: Int = 4_000): Map<*, *>? {
        val target = endpoint
        if (target.airPlayPort <= 0) return null
        val host =
            activeHost ?: EndpointHosts.candidates(target.ipAddresses).firstOrNull() ?: return null
        var probe: Socket? = null
        return try {
            log(
                "AIRPLAY: /info обнаружение бокового канала → $host:${target.airPlayPort} (независимое короткое " +
                    "соединение, не затрагивает сеанс)",
            )
            probe =
                Socket().apply {
                    connect(InetSocketAddress(resolveAddress(host), target.airPlayPort), timeoutMillis)
                    soTimeout = timeoutMillis
                }
            val payload = BinaryPlist.encode(linkedMapOf<String, Any?>())
            val head =
                "GET /info RTSP/1.0\r\n" +
                    "CSeq: 1\r\n" +
                    "User-Agent: AirPlay/$SENDER_SOURCE_VERSION\r\n" +
                    "Content-Type: application/x-apple-binary-plist\r\n" +
                    "Content-Length: ${payload.size}\r\n\r\n"
            probe.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII) + payload)
                flush()
            }
            val input = probe.getInputStream()
            val collected = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var expected = -1
            while (true) {
                val count =
                    try {
                        input.read(chunk)
                    } catch (_: java.net.SocketTimeoutException) {
                        -1
                    }
                if (count < 0) break
                collected.write(chunk, 0, count)
                val bytes = collected.toByteArray()
                if (expected < 0) {
                    val separator = indexOf(bytes, HEAD_SEPARATOR)
                    if (separator >= 0) {
                        val (_, contentLength) =
                            parseRtspHead(String(bytes, 0, separator, Charsets.ISO_8859_1))
                        expected = separator + HEAD_SEPARATOR.size + contentLength
                    }
                }
                if (expected >= 0 && bytes.size >= expected) break
            }
            val bytes = collected.toByteArray()
            val separator = indexOf(bytes, HEAD_SEPARATOR)
            if (separator < 0) {
                log(
                    "AIRPLAY: боковой канал /info не имеет действительного ответа (${bytes.size}B, транспортное средство " +
                        "может обслуживать только связанные сеансы)",
                )
                return null
            }
            val headText = String(bytes, 0, separator, Charsets.ISO_8859_1)
            val (line, contentLength) = parseRtspHead(headText)
            val bodyStart = separator + HEAD_SEPARATOR.size
            val infoBody = bytes.copyOfRange(bodyStart, minOf(bodyStart + contentLength, bytes.size))
            return parseInfoBody("Боковой канал $line", infoBody)
        } catch (error: Exception) {
            log("AIRPLAY: сбой бокового канала /info - ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            runCatching { probe?.close() }
        }
    }

    class SetupInfo(
        val eventPort: Int,
        val timingPort: Int,
        val keepAlivePort: Int?,
        val enabledFeatures: List<String>,
        /** Параметр основного дисплея (/info display[0], следующий за текущим направлением экрана принимающей стороны). */
        val displayWidth: Int,
        val displayHeight: Int,
        val displayUuid: String?,
        val maxFps: Int,
        /** Список /info hidDevices[].uuid (0=сенсорный экран, 1=ручка; тот же номер модели DiPlay). */
        val hidDeviceUuids: List<String> = emptyList(),
        /**
         * uuid → HID-дескриптор. Байт отчета о массиве медиа-ключа (Consumer Control) представляет собой индекс массива использования,
         * должен проанализировать дескриптор, чтобы получить правильное сопоставление (фактическое измерение Audi:
         * нажмите «Далее», чтобы получить 0x05=usages[5]=0xB5).
         */
        val hidDescriptors: Map<String, ByteArray> = emptyMap(),
        /**
         * `/info.keepAliveSendStatsAsBody`: содержит ли кадр KeepAlive потока экрана тело статистики.
         * (истина → код операции=5 + bplist; ложь → код операции=2 пустой кадр). Правда для Ауди 3315.
         */
        val keepAliveSendStatsAsBody: Boolean = false,
    )

    /** Результаты согласования потока экрана. */
    class ScreenStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        /** Шифрование кадра: ChaCha20 (парный сеанс) или AES-128-CTR (неспарный сеанс). */
        val cipher: StreamFrameCipher,
    )

    /**
     * iAP Tunnel (DataStream типа 130, см. DiPlay IapTunnel): iAP2 для Hyundai CarPlay
     * Канал данных EA — **Автомобиль → сообщение iAP2 мобильного телефона (включая сенсорный HID) проходит через этот зашифрованный TCP**,
     * Мобильный телефон       → автомобильный компьютер использует команду iAPSendMessage канала событий ([sendIap]).
     * Формат строки      : кадр ChaCha (длина 2B LE как AAD + зашифрованный текст + тег 16B, nonce=счетчик 8B LE,
     * Ключ       = HKDF(общий, «DataStream-Salt$seed», «DataStream-Output-Encryption-Key»))
     * ⊃ APTransportPackage (общая длина 4 байта BE, заголовок 32 байта, смещение 16 type="comm") → тело = сообщение iAP2.
     */
    inner class IapTunnelClient(
        val socket: Socket,
        private val readKey: ByteArray,
        private val logPrefix: String = "TUNNEL",
        private val writeKey: ByteArray = readKey,
    ) {
        @Volatile
        var messageCount = 0
            private set

        /** Автомобильная машина → обратный вызов сообщения iAP2 мобильного телефона (байт кадра CSM). */
        var onIapMessage: ((Iap2Csm.Frame) -> Unit)? = null

        private val parser =
            Iap2Csm
                .Parser()

        private val writeLock = Any()
        private var writeCounter = 0L
        private var writeFailureLogged = false

        /**
         * Mobile → Запись в направлении приемника (туннель): механизм туннельной связи DiPlay **чтение только из TCP-сокета**
         * (входящий поток AirPlayIapTunnelStream = IapTunnel/TCP, исходящий = канал событий),
         * Следовательно, отправитель должен преобразовать байты канала iAP2 в кадр ChaCha (длина 2 байта LE как AAD +
         * зашифрованный текст + тег 16 байт).
         * ⊃ APTransportPackage (общая длина 4B BE, смещение 16 позиций type="comm")" записывается в сокет.
         * Ключ           имеет то же происхождение, что и направление чтения (тег ВЫХОД = мобильный телефон →
         * направление приема, 2026-08, проверьте исходный код для подтверждения).
         * возвращает значение записи (сокет закрыт/исключение возвращает false, вызывающая сторона записывает только один раз).
         */
        fun writeIapBytes(bytes: ByteArray): Boolean {
            if (socket.isClosed || !socket.isConnected || bytes.isEmpty()) return false
            return try {
                val size = TUNNEL_PACKAGE_HEADER_BYTES + bytes.size
                val plain = ByteArray(size)
                plain[0] = (size ushr 24).toByte()
                plain[1] = (size ushr 16).toByte()
                plain[2] = (size ushr 8).toByte()
                plain[3] = size.toByte()
                plain[16] = 'c'.code.toByte()
                plain[17] = 'o'.code.toByte()
                plain[18] = 'm'.code.toByte()
                plain[19] = 'm'.code.toByte()
                bytes.copyInto(plain, TUNNEL_PACKAGE_HEADER_BYTES)
                val aad =
                    byteArrayOf(
                        (size and 0xFF).toByte(),
                        ((size ushr 8) and 0xFF).toByte(),
                    )
                // Приращение счетчика должно быть заблокировано сокетом записи: ChaCha nonce не в порядке = расшифровка узла не удалась.
                synchronized(writeLock) {
                    val sealed =
                        AirPlayCrypto.chachaSeal(
                            writeKey,
                            AirPlayCrypto.nonce64(writeCounter++),
                            plain,
                            aad,
                        )
                    val output = socket.getOutputStream()
                    output.write(aad)
                    output.write(sealed)
                    output.flush()
                }
                writeFailureLogged = false
                true
            } catch (error: IOException) {
                if (!writeFailureLogged) {
                    writeFailureLogged = true
                    log("$logPrefix: Ошибка записи — ${error.message}")
                }
                false
            }
        }

        fun startReading() {
            Thread({
                val input = socket.getInputStream()
                var pending = ByteArray(0)
                var plaintext = ByteArray(0)
                val buffer = ByteArray(16 * 1024)
                try {
                    while (socket.isClosed == false) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        pending += buffer.copyOf(count)
                        val decrypted = decryptFrames(pending)
                        pending = decrypted.second
                        plaintext += decrypted.first
                        plaintext = parsePackages(plaintext)
                    }
                    log("$logPrefix: канал закрыт (EOF), всего сообщений $messageCount iAP2.")
                } catch (error: IOException) {
                    log("$logPrefix: Конец чтения - ${error.message}")
                }
            }, "iap-tunnel-reader").apply {
                isDaemon = true
                start()
            }
        }

        /** Расшифровка кадра ChaCha (тот же формат кадра, что и у ControlCipher, независимый счетчик/ключ). */
        private fun decryptFrames(buffer: ByteArray): Pair<ByteArray, ByteArray> {
            val output = ArrayList<ByteArray>()
            var offset = 0
            while (buffer.size - offset >= 2) {
                val length = (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
                val frameLength = 2 + length + 16
                if (buffer.size - offset < frameLength) break
                val aad = buffer.copyOfRange(offset, offset + 2)
                val sealed = buffer.copyOfRange(offset + 2, offset + frameLength)
                val plain =
                    AirPlayCrypto.chachaOpen(
                        readKey,
                        AirPlayCrypto.nonce64(tunnelReadCounter++),
                        sealed,
                        aad,
                    )
                output += plain
                offset += frameLength
            }
            val rest = buffer.copyOfRange(offset, buffer.size)
            return if (output.isEmpty()) ByteArray(0) to rest else output.reduce { a, b -> a + b } to rest
        }

        /** Анализ APTransportPackage: общая длина 4B BE, заголовок 32B, смещение 16 type="comm" → body=iAP2. */
        private fun parsePackages(buffer: ByteArray): ByteArray {
            var offset = 0
            while (buffer.size - offset >= 32) {
                val size =
                    ((buffer[offset].toInt() and 0xFF) shl 24) or
                        ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                        ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                        (buffer[offset + 3].toInt() and 0xFF)
                if (size < 32 || buffer.size - offset < size) break
                val messageType = String(buffer, offset + 16, 4, Charsets.US_ASCII)
                if (messageType == "comm") {
                    val body = buffer.copyOfRange(offset + 32, offset + size)
                    onIapBytes(body)
                }
                offset += size
            }
            return buffer.copyOfRange(offset, buffer.size)
        }

        private fun onIapBytes(bytes: ByteArray) {
            messageCount++
            val frames = parser.offer(bytes)
            for (frame in frames) {
                // У шестнадцатеричного форматирования есть своя цена: делайте это только в той ветке, которая действительно
                // нуждается в регистрации (до того, как каждое сообщение будет учтено первым).

                if (messageCount <= 20) {
                    val hex = bytes.joinToString(" ") { "%02x".format(it) }
                    log(
                        "$logPrefix: iAP2 msg #$messageCount id=0x${frame.messageId.toString(16)} " +
                            "(${Iap2MessageIds.nameOf(frame.messageId)}) body=${frame.body.size}B " +
                            "hex=${hex.take(160)}",
                    )
                } else if (messageCount % 100 == 0) {
                    log("$logPrefix: iAP2 msg #$messageCount id=0x${frame.messageId.toString(16)} body=${frame.body.size}B")
                }
                onIapMessage?.invoke(frame)
            }
        }

        private var tunnelReadCounter = 0L
    }

    /**
     * НАСТРОЙКА туннеля iAP типа 130 и подключение к порту данных автомобиля. Возвращает ноль в случае сбоя (сеанс
     * продолжается, сенсорный ввод недоступен).
     * Мобильный телефон → направление движения автомобиля использует [sendIap] (канал событий iAPSendMessage).
     */
    fun runIapTunnel(sessionKey: ByteArray?): IapTunnelClient? {
        // Туннельный сокет зашифрован ключом DataStream, непарный сеанс не имеет общего ключа для получения → пропускается.
        if (sessionKey == null) {
            log("ТУННЕЛЬ: непарный сеанс не имеет общего секретного кода – туннель iAP пропущен (сенсорный доступ недоступен)")
            return null
        }
        val host = receiverHost() ?: return null
        val seed = (System.currentTimeMillis() % 0x7FFF_FFFFL) + 1L
        val connectionId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>(
                                "type" to 130L,
                                "clientTypeUUID" to IAP_DATASTREAM_UUID,
                                "seed" to seed,
                                "streamConnectionID" to connectionId,
                            ),
                        ),
                ),
            )
        val (statusLine, responseBody) =
            try {
                request("SETUP", "/", body, contentType = "application/x-apple-binary-plist")
            } catch (error: IOException) {
                log("ТУННЕЛЬ: УСТАНОВКА не удалась — ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log("ТУННЕЛЬ: ответ НАСТРОЙКИ [$statusLine] (заголовок ответа: ${lastResponseHeadOfRequest()})")
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (_: IllegalArgumentException) {
                null
            } ?: run {
                log("ТУННЕЛЬ: не удалось проанализировать ответ SETUP.")
                return null
            }
        val streams = root["streams"] as? List<*>
        val stream = streams?.firstOrNull() as? Map<*, *>
        val dataPort = ((stream?.get("dataPort") as? Long) ?: 0L).toInt() and 0xFFFF
        if (dataPort == 0) {
            log("ТУННЕЛЬ: ответ НАСТРОЙКИ: нет порта данных (streams=$streams)")
            return null
        }
        val key =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        val tcp =
            try {
                Socket()
            } catch (error: IOException) {
                return null
            }
        return try {
            tcp.tcpNoDelay = true
            tcp.connect(java.net.InetSocketAddress(host, dataPort), 5_000)
            log("ТУННЕЛЬ: ✓ Установлен iAP-туннель $host:$dataPort (seed=$seed)")
            val client = IapTunnelClient(tcp, key)
            client.startReading()
            iapTunnel = client
            client
        } catch (error: IOException) {
            log("ТУННЕЛЬ: TCP-соединение не установлено — ${error.message}")
            runCatching { tcp.close() }
            null
        }
    }

    /**
     * Ссылка на туннельный клиент  (форма DiPlay): **исходящее направление** виртуального канала канала событий ([VirtualIap2Link])
     * должен быть записан в TCP типа 130 (механизм туннельных связей DiPlay считывает только из TCP; исходящие
     * данные проходят через канал событий,
     * соответствует нашему пассивному приему). Если туннеля нет (форма канала событий Audi), продолжайте
     * использовать канал событий [sendIap] при исходящем сообщении.
     */
    @Volatile
    private var iapTunnel: IapTunnelClient? = null

    private var sendIapCount = 0

    /**
     * мобильный телефон → автомобильное сообщение iAP2: команда канала событий iAPSendMessage (сравните DiPlay sendIapMessage,
     * Конверт параметров       + байты данных, то же самое, что и CommandIapSendMessage).
     */
    fun sendIap(data: ByteArray): Boolean {
        val sent =
            postEventCommand(
                linkedMapOf(
                    "type" to "iAPSendMessage",
                    "params" to linkedMapOf<String, Any?>("data" to data),
                ),
            )
        sendIapCount++
        if (sendIapCount <= 10) {
            log(
                "IAPLINK: tx (канал событий) ${data.size}B ok=$sent hex=" +
                    data.joinToString(" ") { "%02x".format(it) }.take(120),
            )
        }
        return sent
    }

    // ---- Виртуальная ссылка iAP2 на канале событий ----
    //
    // Фактические испытания Audi (11 октября 2026 г.): Автомобиль отправил маркер канального уровня iAP2 через iAPSendMessage.
    // (ff 55 02 00 ee 10) — открывает виртуальную ссылку iAP2 на канале событий (тип 130 TCP).
    // Отклонено машиной: 200, но нет порта данных). Данные EA (Touch HID) берут по этой ссылке.
    // Multiplex [io.github.shaonialife.cardeck.iap2.Iap2LinkEngine] для завершения установления связи, а также отправки и получения кадров.

    @Volatile
    private var iapLink: VirtualIap2Link? = null

    /**
     * Обратный вызов мультимедийной клавиши автомобиля (предыдущая песня и следующая песня на рулевом колесе/панели).
     *
     * Канал      : Канал событий → iAPSendMessage → Сеанс управления iAP2 `0x6802 AccessoryHIDReport`
     * → [ConsumerKeys] сопоставлен с Android KeyEvent → этот обратный вызов. Введено SessionController
     * `dispatchMediaKey` (объединен с маршрутом прямых инвестиций HID).
     */
    var onMediaKey: ((Int) -> Unit)? = null

    /** Клавиши навигации (ручка iDrive/джойстик с четырьмя кнопками): требуется виртуальный фокус экрана, вводимый в DPAD с сеансового уровня. */
    var onNavKey: ((Int) -> Unit)? = null

    /**
     * Завершение сеанса управления туннелем MIB3 (аутентификация + пакетная отправка) Обратный вызов: хост планирует
     * режимыИзменено соответствующим образом
     * Пакет переопределения ресурсов       (время AADisplay r17 +1,5/4/12/30 с). 082446 Динцзи: не повторяется
     * Автомобильный телефон исправен на протяжении всего процесса, но никогда не переключается на экран CarPlay.
     */
    var onTunnelControlSessionComplete: (() -> Unit)? = null

    /** Является ли виртуальный канал MIB3 активным (armMib3 прошел успешно) - Использование удаления канала: поставлено на охрану, но удалено до завершения сеанса управления = сторожевой таймер канала, независимо от аудиооборудования. */
    var mib3LinkArmed: Boolean = false
        private set

    /** MIB3 контролирует, завершен ли сеанс (отправлена аутентификация+пакетный пакет). */
    var mib3ControlSessionDone: Boolean = false
        private set

    /** Единственный экземпляр виртуального канала (исходящая маршрутизация/трехморфологическая семантика, см. [VirtualIap2Link]). */
    private fun obtainIapLink(): VirtualIap2Link =
        iapLink ?: synchronized(this) {
            iapLink
                ?: VirtualIap2Link(
                    log = { line -> log(line) },
                    sendEventIap = ::sendIap,
                    sendControlIap = ::postControlIap,
                    tunnelWriter = { iapTunnel },
                    alive = { socket?.isClosed == false },
                ).also {
                    it.onMediaKey = { keyCode -> onMediaKey?.invoke(keyCode) }
                    it.onNavKey = { keyCode -> onNavKey?.invoke(keyCode) }
                    it.onControlSessionComplete = {
                        mib3ControlSessionDone = true
                        onTunnelControlSessionComplete?.invoke()
                    }
                    it.begin()
                    iapLink = it
                }
        }

    /** Байтовая запись iAPSendMessage транспортного средства (вызывается обработчиком команды события SessionController). */
    fun onIapEventBytes(data: ByteArray) {
        obtainIapLink().onCarBytes(data)
    }

    /**
     * MIB3 на охрану: вызывается после успешной настройки потока экрана - активная отправка через **канал
     * управления** POST/команду один раз
     * DETECT, затем дождитесь SYN автомобиля (реальное время машины Frida, подробности см. в [VirtualIap2Link.armMib3]).
     * Сеанс открытого текста       (старый автомобильный компьютер до HomeKit) не имеет семантики туннеля Wi-Fi и пропускается.
     */
    private fun armMib3VirtualLink() {
        if (cipher == null) return
        if (iapTunnel != null) return // Туннельный режим DiPlay: постановка на охрану не происходит (и не должна происходить).
        val generation = linkGeneration ?: ReceiverProfile.LinkGeneration.EVENT_LINK
        obtainIapLink().setGeneration(generation)
        obtainIapLink().armMib3()
        mib3LinkArmed = true
    }

    /**
     * Канал управления Исходящий iAPSendMessage (MIB3): кадры канала связи iAP2 на стороне мобильного телефона должны контролироваться RTSP
     * POST/команда при соединении       - MIB3 вообще не читается при записи в канал событий (толерантность к DiPlay, Audi
     * непереносимость      ; AADisplay вывод Фриды). Serial Executor: не блокирует поток битов связи, при этом
     * /feedback heartbeat использует блокировку CSeq/write для request() (поточно-ориентированный дизайн см. в AirPlayClient).
     */
    private val controlIapTx =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "mib3-iap-tx").apply { isDaemon = true }
        }
    private var controlIapCount = 0

    fun postControlIap(frame: ByteArray) {
        controlIapTx.execute {
            try {
                val body =
                    BinaryPlist.encode(
                        linkedMapOf(
                            "type" to "iAPSendMessage",
                            "params" to linkedMapOf<String, Any?>("data" to frame),
                        ),
                    )
                val (statusLine, reply) =
                    request(
                        "POST",
                        "/command",
                        body,
                        contentType = "application/x-apple-binary-plist",
                    )
                controlIapCount++
                val head =
                    "IAPLINK: tx (канал управления) #$controlIapCount ${frame.size}B [$statusLine]" +
                        (if (reply.isEmpty()) "" else " resp=${reply.size}B hex=" + hexPrefix(reply))
                if (controlIapCount <= 10 || controlIapCount % 50 == 0 || reply.isNotEmpty()) {
                    log(head)
                }
                // Автомобильная машина может вставлять байты ссылки непосредственно в тело ответа (вместо отправки другого
                // канала событий): передайте его, если вы его узнали.

                // Ссылка двигателя, не позволяйте ему лгать в ответ.
                if (reply.isNotEmpty() && looksLikeIapLink(reply)) {
                    log("IAPLINK: ответ канала управления содержит байты связи ${reply.size}B → передаваемые в механизм связи.")
                    iapLink?.onCarBytes(reply)
                }
            } catch (error: IOException) {
                log("IAPLINK: сбой POST канала управления — ${error.message}")
            }
        }
    }

    /** Шестнадцатеричный префикс тела ответа (в диагностических целях усекается до 24 байт). */
    private fun hexPrefix(
        bytes: ByteArray,
        limit: Int = 24,
    ): String = bytes.take(limit).joinToString(" ") { "%02x".format(it) } + if (bytes.size > limit) "…" else ""

    /**
     * Полное шестнадцатеричное значение сообщения (для диагностики неисправностей): по умолчанию — до 480 байт, а каждая строка — 48 байт.
     * При диагностике автомобиля и машины plist задается только 24-байтовый префикс и объект ошибки не виден. Полный
     * текст необходимо предоставить.
     */
    private fun hexDump(
        bytes: ByteArray,
        limit: Int = 480,
        perLine: Int = 48,
    ): String {
        if (bytes.isEmpty()) return "(пусто)"
        val shown = minOf(bytes.size, limit)
        val builder = StringBuilder(shown * 3)
        var index = 0
        while (index < shown) {
            if (index > 0) builder.append("\n    ")
            val end = minOf(index + perLine, shown)
            for (position in index until end) {
                builder.append("%02x".format(bytes[position]))
                if (position != end - 1) builder.append(' ')
            }
            index = end
        }
        if (bytes.size > shown) builder.append(" …(всего ${bytes.size}B, показан только первый $shown)")
        return builder.toString()
    }

    /** Является ли тело ответа кадром канала iAP2 (маркер 0xFF55/пакет синхронизации 0xFF5A). */
    private fun looksLikeIapLink(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 0xFF.toByte() &&
            (bytes[1] == 0x5A.toByte() || bytes[1] == 0x55.toByte())

    /** Результат согласования аудиопотока (RTP через UDP; ключ тот же, что и правило видеопотока, соль — идентификатор аудиосоединения). */
    class AudioStreamInfo(
        val streamConnectionId: Long,
        val dataPort: Int,
        val controlPort: Int,
        val streamKey: ByteArray,
        /** Согласованная частота дискретизации (Гц): выберите в соответствии с декларацией машины, AudioSender должен использовать то же значение для сбора данных. */
        val sampleRate: Int = 48_000,
    )

    /** Порт plist — это u16, но он закодирован как 2-байтовое целое число со знаком (>32767 читается как отрицательное), побитовое маскирование истинного значения. */
    private fun portOf(value: Any?): Int = ((value as? Long) ?: 0L).toInt() and 0xFFFF

    private companion object {
        /** Тип видеопотока главного экрана (сравните приемник STREAM_TYPE_MAIN_SCREEN; 100 — аудио, 130 — туннель данных/iAP). */
        const val SCREEN_STREAM_TYPE = 110L

        /** Тип основного аудиопотока (DiPlay STREAM_TYPE_MAIN_AUDIO; проход PCM). */
        const val AUDIO_STREAM_TYPE = 100L

        /**
         * Тип основного аудиопотока высокого качества (кошачья игра `StreamType::MainHighAudio`).
         *
         * **Реальный медиаканал** некоторых автомобилей           (фактические испытания Honda HFT / Alpine 8A250-30A
         * 2026 г.) находится здесь:
         * `type=102 media` объявляет только AAC-LC, а `type=100 media` объявляет только PCM — установите PCM на 100.
         * Машина синхронизации           SETUP возвращает 200 в порт данных, но не использует его (время выборки
         * `/feedback` всегда равно 0).
         * разорвет всю сессию примерно через 6,5 секунд.
         */
        const val AUDIO_STREAM_TYPE_MAIN_HIGH = 102L

        /** Биты аудиоформата: PCM 48000 Гц, 16 бит стерео (определение Catplay AudioFormat 1<<15). */
        const val AUDIO_FORMAT_PCM_48K_STEREO = 1L shl 15

        /** Биты аудиоформата: PCM 44100 Гц, 16 бит стерео (1<<11; резервный вариант, когда 48k недоступен). */
        const val AUDIO_FORMAT_PCM_44K_STEREO = 1L shl 11

        /** Стерео AAC-LC 44100 Гц (1<<22; кошачья игра `AAC_LC_44100_STEREO`). */
        const val AUDIO_FORMAT_AAC_LC_44K_STEREO = 1L shl 22

        /** Стерео AAC-LC 48000 Гц (1<<23; кошачья игра `AAC_LC_48000_STEREO`). */
        const val AUDIO_FORMAT_AAC_48K_STEREO = 1L shl 23

        /**
         * audioТип медиа-аудиоканала (настоящий медиа-аудио iPhone работает таким образом).
         * Какой из них выбрать, определяется аудиоформатами автомобиля [negotiateAudio] пресс/инфо - вот только
         * Критерий           «сначала медиафайл» и его базовое значение протокола при отсутствии /info.
         */
        const val AUDIO_TYPE_MEDIA = "media"

        /**
         * Нижнее значение задержки звука (мс): используется, когда выходLatencyMicros не указан в audioLatency автомобиля/информации.
         * catplay Примечание. Для стабильной потоковой передачи AirPlay требуется не менее 250 мс (зажим 32..2000) — 250
         * для нижнего уровня.
         */
        const val AUDIO_LATENCY_FALLBACK_MS = 250L

        /** Уровень громкости автомобиля ≤ этого значения (дБ) считается уровнем отключения звука (-144 в AirPlay примерно соответствует полному отключению звука). */
        private const val MUTE_THRESHOLD_DB = -100f

        /** Громкость увеличивается при обнаружении положения отключения звука (дБ, 0 = максимум; -25 — от среднего до низкого, не громко). */
        private const val SAFE_VOLUME_DB = -25.0f

        /** Анализируйте текстовые параметры, такие как `volume: -12.345678`. */
        private val VOLUME_REGEX = Regex("volume:\\s*(-?[0-9.]+)")

        /**
         * Список возможностей iPhone в  SETUP (принимающая сторона вычисляет включенные функции в соответствии с
         * «Поддержка автомобиля ∩ Предложение»,
         * Отсутствует возможность, вся функция отключена —— 11 октября 2026 г. Фактический тест Audi: воспроизведение
         * видео предлагается отдельно.
         * Видеоконвейер           не включен, поток экрана 400). Список сравнивается с настоящим автомобильным отправителем Catplay.
         */
        val CONTROLLER_FEATURES =
            listOf(
                "uiContext",
                "viewAreas",
                "cornerMasks",
                "focusTransfer",
                "h.264Level5.1",
                "mainBuffered",
                "altScreen",
                "enhancedSiri",
                "hevc",
                "sessionManagement",
                "logTransfer",
                "iAPChannel",
                "vehicleStateProtocol",
                "videoPlayback",
            )

        /** Общее время, потраченное на ожидание открытия окна AirPlay на машине (окно ожидания, когда пользователь щелкает значок телефона на машине). */
        private const val PAIRING_WINDOW_MILLIS = 120_000L

        /** Интервал (раз) вызова [retryTick] при повторе жеребьёвки: 2 секунды за раунд, 10 раз ≈ повторный анонс каждые 20 секунд. */
        private const val RETRY_TICK_ATTEMPTS = 10

        /**
         * Получающая сторона явно возвращает статус HTTP «Нет такого интерфейса». Если он попадет, это будет определено
         * как старый автомобиль до HomeKit.
         * немедленно преобразует разговор в обычный текст и больше не проходит зря через окно сопряжения 120 секунд.
         */
        private val PAIRING_UNSUPPORTED_STATUS = listOf("404", "405", "455", "501")

        /** Версия AirPlay, сообщенная отправителем самостоятельно (значение проверки реального автомобиля Catplay, эпоха iOS 15.0.2; не используйте автомобильную версию). */
        private const val SENDER_SOURCE_VERSION = "566.25.21"

        /**
         * UDP интервал KeepAlive. в кошачьей игре используются десятки, но это калибр самой принимающей стороны; настоящие автомобили (Ауди
         * MMI 3315) Оба сторожевых таймера имеют длину 10 и проиграют, если они равны по длине (дрожание сна приводит к
         * тому, что пульс приходит на 1 мс позже).
         */
        private const val KEEPALIVE_INTERVAL_MS = 5_000L

        /** Строка состояния Sentinel доставляется официанту, когда поток чтения завершается/соединение закрывается (не настоящий ответ RTSP). */
        private const val RTSP_CLOSED_MARK = "__closed__"

        /** Задержка потока экрана (измеренное значение catplay c2a car_manager). */
        private const val SCREEN_LATENCY_MS = 25L

        /** clientTypeUUID iAP DataStream (DiPlay IAP_DATASTREAM_UUID, та же модель, что и настоящий iPhone). */
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"

        /** Длина заголовка Tunnel APTransportPackage (общая длина 4B BE + 12B зарезервировано + 4B type="comm" + 12B зарезервировано). */
        private const val TUNNEL_PACKAGE_HEADER_BYTES = 32

        /** Конечный разделитель и поле заголовка RTSP-заголовка являются обычными (запрос/ответ являются общими для обоих путей повторной сборки и не должны быть встроенными). */
        private val HEAD_SEPARATOR = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

        private val CONTENT_LENGTH_REGEX = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)

        private val CSEQ_REGEX = Regex("CSeq:\\s*(\\d+)", RegexOption.IGNORE_CASE)

        /** Разберите заголовок сообщения RTSP: первая строка + Content-Length (общий для обоих путей повторной сборки). */
        fun parseRtspHead(headText: String): Pair<String, Int> {
            val contentLength =
                CONTENT_LENGTH_REGEX
                    .find(headText)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: 0
            val firstLine = headText.lineSequence().firstOrNull() ?: ""
            return firstLine to contentLength
        }

        fun indexOf(
            source: ByteArray,
            target: ByteArray,
        ): Int {
            outer@ for (index in 0..source.size - target.size) {
                for (offset in target.indices) {
                    if (source[index + offset] != target[offset]) continue@outer
                }
                return index
            }
            return -1
        }
    }

    /**
     * НАСТРОЙКА : Согласование основного аудиопотока (тип 100, RTP через UDP).
     *
     * Формат строки       такой же, как у видео, но передача отличается: принимающая сторона открывает **UDP**
     * dataPort/controlPort в ответе,
     * Мобильный телефон       отправляет кадры PCM в формате [AudioSender]. Ключ = HKDF(общий,
     * «DataStream-Salt<audio connId>», «DataStream-Output-Encryption-Key»).
     *
     * Поле       выравнивается в соответствии с отправителем catplay (`StreamDescriptionAudio::new`):
     * - `audioLatencyMs` Требуется (принимающая сторона будет читать; отсутствие передачи будет строго реализовывать разделение сеанса)
     * - `audioType` **Переговоры о пресс-автомобиле/информационном заявлении** (см. [negotiateAudio]): приоритет медиа-аудио
     * канал       `media`; после программирования `compatibility` машина принимает НАСТРОЙКУ, предоставляет порты и принимает пакеты.
     * но не подключен к каналу воспроизведения (тихо) - это одна из основных причин 17.10.2026
     * — `streamConnectionID` использует случайный положительный i64 (та же модель, что и Catplay `rand::random::<u64>()`): временная метка
     * Оставшееся число будет увеличено при повторном подключении через короткий промежуток времени.
     * — нет дополнительных GET /info (фактически измерено, это занимает ~2,5 с; /info извлекается во время фазы
     * установления связи и кэшируется для повторного использования)
     *
     * Формат      : установите приоритет стерео PCM 48k (бит 15), автомобильная машина объявляет только 44,1 k,
     * затем вернитесь к биту 11; частота дискретизации отправляется в
     * [AudioSender] остается неизменным.
     */
    fun runAudioStream(
        sharedSecret: ByteArray,
        profile: AudioProfile,
        tuning: AudioTuning = AudioTuning.RAW_SPF,
    ): AudioStreamInfo? {
        // Положение передачи выбирается вызывающим абонентом путем нажатия «кандидаты, объявленные транспортным
        // средством/информация + историческая память этого транспортного средства», а затем передается:

        // Наличие разных передач AudioType на одном автомобиле совершенно разное (см. комментарий AudioProfile),
        // Если одна передача выходит из строя, переключитесь на следующую передачу, вместо того, чтобы слепо пытаться
        // использовать одну и ту же передачу каждый раз при подключении.

        val negotiation = profile
        log(
            "AIRPLAY: согласование звука → audioType=${profile.audioType}" +
                "format=0x${profile.formatBit.toString(16)} ${profile.sampleRate}Hz " +
                "задержка = ${profile.latencyMs}ms (шестерня = ${profile.label})," +
                "Пакет=${tuning.detail})",
        )
        // Случайное положительное значение i64: BinaryPlist кодирует целое число со знаком, маскируя знаковый бит, чтобы
        // избежать отрицательных чисел.

        val connectionId = (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1L)
        // значение spf: AAC 1024 выборки на кадр; PCM соответствует 352 кадрам отправителя в пакете.
        val spfValue =
            tuning.spf?.let {
                if (profile.codec == AudioCodec.AAC_LC) it.toLong() else AudioSender.SAMPLES_PER_PACKET.toLong()
            }
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>().apply {
                                put("type", profile.streamType)
                                put("streamConnectionID", connectionId)
                                put("audioFormat", negotiation.formatBit)
                                put("audioType", negotiation.audioType)
                                put("audioLatencyMs", negotiation.latencyMs)
                                // Поле за полем выровнено по отправителю catplay (StreamDescriptionAudio::new):
                                // input=false (только нисходящий канал, без микрофона),
                                // supportRTPPacketRedundancy=false (мы не упаковываем резервные кадры).
                                // Принимающая сторона, у которой отсутствует строгая реализация этих двух логических частей времени, будет
                                // рассматриваться как «необъявленная = не поддерживается».

                                // Поток отбрасывается на этапе инициализации аудиотракта.
                                put("input", false)
                                put("supportsRTPPacketRedundancy", false)
                                put("supportsHighAccuracyTimestamps", tuning.highAccuracyTimestamps)
                                // spf = количество кадров на пакет: 1024 для AAC, 352 для PCM.
                                // Фактические измерения Honda от 20 октября 2026 г.: когда SPF не объявлен, автомобиль все равно получает
                                // пакет
                                // и нажимает 44,1 тыс.

                                // Потребление в реальном времени, но динамик молчит - это значение необходимо для реализации "планирования
                                // по
                                // фиксированному SPF".

                                spfValue?.let { put("spf", it) }
                            },
                        ),
                ),
            )
        val host = endpoint.ipAddresses.firstOrNull() ?: "receiver"
        val startedAt = SystemClock.uptimeMillis()
        val (statusLine, responseBody) =
            try {
                request(
                    "SETUP",
                    "rtsp://$host:${endpoint.airPlayPort}/",
                    body,
                    // 8 с: фактическое измерение настройки аудиосистемы Audi может занять около 2,7 с (арбитраж ресурсов на стороне
                    // автомобиля); поток асинхронного чтения
                    // Гарантируется, что контрольный сигнал обратной связи будет отправлен как обычно в течение периода ожидания, и
                    // длительное ожидание больше не будет запускать сторожевой таймер автомобиля-машины.
                    timeoutMillis = 8_000,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: не удалось выполнить согласование аудиопотока — ${error.message}")
                return null
            }
        log("AIRPLAY: НАСТРОЙКА звука туда и обратно ${SystemClock.uptimeMillis() - startedAt}ms [$statusLine]")
        if (!statusLine.contains("200")) {
            log("AIRPLAY: ответ согласования аудиопотока [$statusLine]")
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                null
            } ?: run {
                log("AIRPLAY: не удалось проанализировать ответ согласования аудиопотока.")
                return null
            }
        val streams =
            root["streams"] as? List<*> ?: run {
                log("AIRPLAY: ответ на запрос согласования аудиопотока, отсутствуют потоки")
                return null
            }
        val audio =
            streams.firstOrNull { entry ->
                ((entry as? Map<*, *>)?.get("type") as? Long) == profile.streamType
            } as? Map<*, *> ?: run {
                log("AIRPLAY: аудиопоток отклонен (streams=$streams)")
                return null
            }
        val dataPort = portOf(audio["dataPort"])
        if (dataPort == 0) {
            log("AIRPLAY: аудиопоток не возвращается. Порт данных ($audio)")
            return null
        }
        val streamKey =
            AirPlayCrypto.hkdfSha512(
                sharedSecret,
                "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
                "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
            )
        log(
            "AIRPLAY: ✓ Согласование аудиопотока прошло успешно (type=${profile.streamType} ${negotiation.audioType}" +
                "format=0x${negotiation.formatBit.toString(16)} ${negotiation.sampleRate}Hz " +
                "latency=${negotiation.latencyMs}ms）" +
                "dataPort=$dataPort controlPort=${portOf(audio["controlPort"])} connId=$connectionId" +
                " Все поля=$audio",
        )
        // Аудиоканал установился, но есть еще очень распространенная причина, почему «машина принимает данные, а динамик молчит»:
        // Громкость AirPlay на принимающей стороне отключена (-144 дБ) — настоящий iPhone будет активно SET_PARAMETER
        // объем. Сначала проверьте здесь (кстати, запишите значение в журнал). Если вы нашли положение отключения звука,
        // поднимите его до -25 дБ.

        probeAndFixVolume(host)
        return AudioStreamInfo(
            streamConnectionId = connectionId,
            dataPort = dataPort,
            controlPort = portOf(audio["controlPort"]),
            streamKey = streamKey,
            sampleRate = negotiation.sampleRate,
        )
    }

    /**
     * запрашивает и корректирует громкость ресивера (`GET_PARAMETER`/`SET_PARAMETER` + `volume` для AirPlay).
     *
     * Зачем нужен       (фактическое измерение Honda 20 октября 2026 г.): После построения канала AAC-LC машина **
     * потребляет образцы в реальном времени **
     * (`sampleTime` из `/feedback` — 44,1k), но все динамики молчат. Настоящий iPhone в сеансе
     * будет активно отправлять `SET_PARAMETER volume`; если усиление AirPlay на принимающей стороне остается в положении отключения звука
     * (-144дБ), производительность именно такая "данные идут, а звука нет".
     *
     * здесь делает только две вещи: ① Записывает в журнал текущий объём автомобиля (это прямое свидетельство для
     * определения "положения молчания");
     * ② Если он действительно находится в положении отключения звука (≤[MUTE_THRESHOLD_DB]), переместите его в [SAFE_VOLUME_DB].
     * Ошибка запроса/автомобиль не поддерживает это не повлияет на основную ссылку.
     */
    private fun probeAndFixVolume(host: String) {
        val url = "rtsp://$host:${endpoint.airPlayPort}/"
        val volume: Float? =
            try {
                val (status, body) =
                    request(
                        "GET_PARAMETER",
                        url,
                        "volume".toByteArray(Charsets.US_ASCII),
                        timeoutMillis = 4_000,
                        contentType = "text/parameters",
                    )
                val text = body.toString(Charsets.UTF_8).trim()
                log("AIRPLAY: запрос объема автомобиля $status body=${text.take(160)}")
                VOLUME_REGEX
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull()
            } catch (error: Exception) {
                log("AIRPLAY: не удалось выполнить запрос громкости автомобиля — ${error.message} (игнорируется, не влияет на аудиопоток)")
                null
            }
        // Можно найти и не в положении отключения звука: соблюдайте текущую громкость автомобиля и не перезаписывайте ее.
        if (volume != null && volume > MUTE_THRESHOLD_DB) return
        // Если его невозможно найти (фактический тест Honda возвращает 501 Not Implemented), его также необходимо выдать один раз:
        // Исходная реализация возвращает напрямую, что означает, что громкость ресивера ни разу не устанавливалась в
        // течение всего сеанса - если автомобильный компьютер AirPlay

        // Усиление останавливается в положении отключения звука, и симптом "данные идут, но динамик молчит", и мы даже
        // логи получить не можем.

        try {
            val payload = String.format(java.util.Locale.US, "volume: %.6f", SAFE_VOLUME_DB)
            val (status, body) =
                request(
                    "SET_PARAMETER",
                    url,
                    payload.toByteArray(Charsets.US_ASCII),
                    timeoutMillis = 4_000,
                    contentType = "text/parameters",
                )
            log(
                if (volume == null) {
                    "AIRPLAY: Автомобиль не поддерживает запрос громкости → еще уменьшите громкость звука " +
                        "$SAFE_VOLUME_DB дБ ($status ${body.size}B)"
                } else {
                    "AIRPLAY: Громкость автомобиля отключена ($volume дБ) → установлена на $SAFE_VOLUME_DB дБ ($status ${body.size}B)"
                },
            )
        } catch (error: Exception) {
            log("AIRPLAY: не удалось установить громкость автомобиля — ${error.message}")
        }
    }

    /**
     * нажимает audioFormats/audioLatency `/info` для генерации **кандидатов на аудиоустройства** (по порядку).
     *
     * Разница между       и старой реализацией (возвращает только один «результат переговоров») (фактическое
     * измерение Honda HFT 19 октября 2026 г.):
     * Автомобиль       часто «принимает НАСТРОЙКУ определенного оборудования, предоставляет dataPort и получает
     * пакеты UDP, но внутренний аудиоканал не может быть открыт».
     * Весь разговор прервался через несколько секунд. Следовательно, вызывающий абонент должен иметь возможность
     * **переключить передачу и повторить попытку**, при этом все допустимые передачи будут возвращены.
     *
     * Правила      :
     * 1. Получите только `type=100` (основной звук);
     * 2. Каждая шестерня `audioType` соответствует формату PCM в соответствии со своим собственным растровым
     * изображением `audioOutputFormats`.
     * (приоритет имеет 48-битный стереобит 15, за которым следует 44,1-битный стереобит 11);
     * 3. Сортировка: приоритет файла `media` по умолчанию (настоящий медиа-аудиоканал iPhone, единственный файл,
     * который может воспроизводить звук в соответствии с фактическим тестом Audi);
     * Если [preferCompat]=true, на первом месте будут немедийные материалы (совместимость и т. д.)——
     * Медиа-файлы некоторых автомобилей заявляют только 44,1 КБ, а их аудиоканалы потребляют только 48 КБ, поэтому
     * можно использовать только совместимые файлы;
     * 4. Задержка принимает значение (тип, audioType) из выводаLatencyMicros из audioLatency,
     * нельзя использовать для получения [AUDIO_LATENCY_FALLBACK_MS].
     * возвращается к базовой линии протокола (media + PCM48k + 250 мс), когда не может получить /info, что
     * аналогично тому, как было до изменения.
     */
    fun audioCandidates(preferCompat: Boolean = false): List<AudioProfile> {
        val baseline =
            listOf(
                AudioProfile(AUDIO_TYPE_MEDIA, AUDIO_FORMAT_PCM_48K_STEREO, 48_000, AUDIO_LATENCY_FALLBACK_MS),
            )
        val info = negotiatedInfo ?: return baseline
        val declared =
            (info["audioFormats"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                .orEmpty()
        if (declared.isEmpty()) {
            log("AIRPLAY: автомобильная машина/информация не объявляет аудиоформаты → попробуйте в соответствии с базовой линией протокола")
            return baseline
        }

        // ---- 1) Канал AAC-LC: приоритет имеет MainHighAudio(102), за которым следует type=100 ----
        // Если в автомобиле заявлено AAC-LC на 102/media, это означает, что у него есть независимый высококачественный медиа-аудиотракт.
        // (Настоящий мультимедийный звук iPhone работает именно так). Производительность перевода PCM в 100 такова:
        // SETUP возвращает 200 в dataPort,

        // Однако sampleTime в controlPort=0 и /feedback всегда равен 0, и весь сеанс прерывается примерно через 6,5 секунд.
        val aac = mutableListOf<AudioProfile>()
        val aacOptions =
            listOf(
                Triple(AUDIO_STREAM_TYPE_MAIN_HIGH, AUDIO_FORMAT_AAC_LC_44K_STEREO, 44_100),
                Triple(AUDIO_STREAM_TYPE_MAIN_HIGH, AUDIO_FORMAT_AAC_48K_STEREO, 48_000),
                Triple(AUDIO_STREAM_TYPE, AUDIO_FORMAT_AAC_LC_44K_STEREO, 44_100),
                Triple(AUDIO_STREAM_TYPE, AUDIO_FORMAT_AAC_48K_STEREO, 48_000),
            )
        aacOptions.forEach { (streamType, bit, rate) ->
            declared
                .filter { ((it["type"] as? Number)?.toLong()) == streamType }
                .forEach { channel ->
                    val output = (channel["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                    if ((output and bit) == 0L) return@forEach
                    val audioType = (channel["audioType"] as? String)?.lowercase() ?: return@forEach
                    // Медиа-аудио проходит только через медиа-файл; совместимость/по умолчанию — звуковая подсказка/сцена совместимости.
                    if (audioType != AUDIO_TYPE_MEDIA) return@forEach
                    aac +=
                        AudioProfile(
                            audioType = audioType,
                            formatBit = bit,
                            sampleRate = rate,
                            latencyMs = latencyFor(info, audioType, streamType),
                            streamType = streamType,
                            codec = AudioCodec.AAC_LC,
                        )
                }
        }

        // ---- 2) Канал PCM (существует только при типе=100) ----
        val formats = declared.filter { ((it["type"] as? Number)?.toLong()) == AUDIO_STREAM_TYPE }
        val pcm = mutableListOf<AudioProfile>()
        // Считаются только «медиа» файлы: машина также объявит файлы телефонии/речиRecognition/alert и другие файлы.
        // У них также есть PCM48k в растровом изображении, но направление медиапотока в канал голоса/тона приводит
        // только к странным звукам или тишине.

        // медиа/совместимость/по умолчанию Эти три уровня используются в качестве медиаканалов как на отправляющей, так
        // и на принимающей стороне.

        val mediaTypes =
            formats.filter { channel ->
                val type = (channel["audioType"] as? String)?.lowercase()
                type == AUDIO_TYPE_MEDIA || type == "compatibility" || type == "default"
            }
        val pool = mediaTypes.ifEmpty { formats }
        // Внешний слой имеет формат (48k → 44,1k), а внутренний слой — в порядке, заявленном автомобилем: 48k,
        // естественно, занимает первое место на одной и той же передаче.

        listOf(AUDIO_FORMAT_PCM_48K_STEREO to 48_000, AUDIO_FORMAT_PCM_44K_STEREO to 44_100)
            .forEach { (bit, rate) ->
                pool.forEach { channel ->
                    val output = (channel["audioOutputFormats"] as? Number)?.toLong() ?: 0L
                    if ((output and bit) == 0L) return@forEach
                    val type = (channel["audioType"] as? String)?.lowercase() ?: return@forEach
                    pcm += AudioProfile(type, bit, rate, latencyFor(info, type, AUDIO_STREAM_TYPE))
                }
            }
        // Медиа-файлы имеют наивысший приоритет; остальные отсортированы по знакомству с совместимостью → по умолчанию
        // (48к хранится первым в группе)

        val priority = { type: String ->
            when {
                type == AUDIO_TYPE_MEDIA && !preferCompat -> 0
                type == "compatibility" && !preferCompat -> 1
                type == "compatibility" && preferCompat -> 0
                type == AUDIO_TYPE_MEDIA && preferCompat -> 2
                else -> 3
            }
        }
        // «Файл совместимости» = принудительно использовать старый путь PCM (пропуская AAC): на некоторых автомобилях
        // AAC-LC будет устанавливаться напрямую.

        // Отклоните его и оставьте вход вручную, чтобы автоматические кандидаты каждый раз не ударялись о стену первыми.
        val ordered =
            (if (preferCompat) emptyList() else aac.distinctBy { it.key }) +
                pcm.sortedBy { priority(it.audioType) }
        if (ordered.isEmpty()) {
            log(
                "AIRPLAY: Автомобиль не декларирует доступные стереоформаты AAC-LC/PCM." +
                    "(Всего файлов ${declared.size}) → Все равно попробуйте в соответствии с базовым уровнем",
            )
            return baseline
        }
        log("AIRPLAY: аудиокандидат (по предпочтениям) =${ordered.map { it.label }}")
        return ordered.distinctBy { it.key }
    }

    /** Выходная задержка (мс), объявленная этим механизмом (type, audioType); если допустимого значения нет, используйте значение по умолчанию. */
    private fun latencyFor(
        info: Map<*, *>,
        audioType: String,
        streamType: Long = AUDIO_STREAM_TYPE,
    ): Long {
        val declaredMs =
            (info["audioLatencies"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull {
                    ((it["type"] as? Number)?.toLong()) == streamType &&
                        (it["audioType"] as? String)?.lowercase() == audioType
                }?.let { (it["outputLatencyMicros"] as? Number)?.toLong() }
                ?.takeIf { it > 0 }
                ?.let { it / 1000 }
        // Нижний предел 250 мс: значение OutputLatencyMicros, заявленное транспортным средством, представляет собой
        // только его собственную выходную задержку (фактически измеренная 35 мс).

        // Использование его в качестве сквозного буфера напрямую превратит дрожание Wi-Fi в недогрузку. Catplay также отражает AirPlay
        // `max(заявленная стоимость, 250ms)`.
        return declaredMs?.coerceAtLeast(AUDIO_LATENCY_FALLBACK_MS) ?: AUDIO_LATENCY_FALLBACK_MS
    }

    /**
     * идентификационный текст транспортного средства (ключевой источник памяти аудиооборудования): используйте
     * только стабильную модель + производителя в /info,
     * Имя Bluetooth/строка прошивки изменится при повторном подключении и не может быть введена.
     */
    fun receiverIdentity(): String {
        val info = negotiatedInfo ?: return "unknown"
        val manufacturer = (info["manufacturer"] as? String).orEmpty().trim()
        val model = (info["model"] as? String).orEmpty().trim()
        return "$manufacturer $model".trim().ifBlank { "unknown" }
    }

    /**
     * НАСТРОЙКА №2: Согласование видеопотока на главном экране (тип 110). streamConnectionID указывается клиентом,
     * Оба конца получают ключ потока в соответствии с методом шифрования сеанса; принимающая сторона открывает порт
     * данных TCP и возвращает его в ответ.
     *
     * [sessionKey]: производный общий секрет парной проверки; null = непарный сеанс (`et=NONE`),
     * нажмите Catplay, чтобы получить сеансовый ключ AES-128-CTR + нулевой.
     */
    fun runScreenStream(
        sessionKey: ByteArray?,
        displayUuid: String? = null,
    ): ScreenStreamInfo? {
        // Контрольная игра (проверка реальной машины):streamConnectionID = случайный u64 (+1 гарантированно ненулевой)
        val connectionId = java.security.SecureRandom().nextLong() and Long.MAX_VALUE or 1L
        val streamUuid =
            displayUuid ?: java.util.UUID
                .randomUUID()
                .toString()
        log(
            "AIRPLAY: порядок действий на главном экране НАСТРОЙКА uuid=$streamUuid" +
                if (displayUuid == null) {
                    "(⚠️ /info не предоставляет displayUuid и может использовать только случайные значения — машина " +
                        "может получать поток, но не быть привязанной к какому-либо экрану)"
                } else {
                    "(из /info отображает[].uuid)"
                },
        )
        val body =
            BinaryPlist.encode(
                linkedMapOf(
                    "streams" to
                        listOf(
                            linkedMapOf<String, Any?>(
                                "type" to SCREEN_STREAM_TYPE, // 110 = Главный экран (100 — звук, DiPlay откроет порт UDP)
                                "streamConnectionID" to connectionId,
                                // Настоящий отправитель iPhone/catplay: требуются uuid и latencyMs.
                                // uuid должен быть отображаемым UUID, объявленным /info display[] на принимающей стороне (реальные
                                // автомобили
                                // проверят его;
                                // Случайные значения заставили Audi разделить сеанс сразу на 400). latencyMs=25 — фактическое измеренное
                                // значение кошачьей игры.
                                "uuid" to streamUuid,
                                "latencyMs" to SCREEN_LATENCY_MS,
                            ),
                        ),
                ),
            )
        // Путь запроса точно такой же, как и в catplay: поток SETUP переходит в «/» (первоначальный SETUP идет в «/setup»).
        // Некоторые ресиверы (прошивка Audi) чувствительны к путям, и если пути не совпадают, то весь пакет будет 400.
        val (statusLine, responseBody) =
            try {
                request(
                    "SETUP",
                    "/",
                    body,
                    contentType = "application/x-apple-binary-plist",
                )
            } catch (error: IOException) {
                log("AIRPLAY: не удалось согласование потока — ${error.message}")
                return null
            }
        if (!statusLine.contains("200")) {
            log(
                "AIRPLAY: ответ согласования потока [$statusLine] (заголовок ответа: ${lastResponseHeadOfRequest()}) \n" +
                    "  ↳ Тело запроса(${body.size}B)=" + body.joinToString("") { "%02x".format(it) },
            )
            return null
        }
        val root =
            try {
                BinaryPlist.parse(responseBody) as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                null
            } ?: run {
                log("AIRPLAY: не удалось проанализировать ответ согласования потока.")
                return null
            }
        val streams =
            root["streams"] as? List<*> ?: run {
                log("AIRPLAY: ответ на запрос согласования потоков отсутствует")
                return null
            }
        val screen = streams.firstOrNull() as? Map<*, *>
        val dataPort = portOf(screen?.get("dataPort"))
        if (screen == null || dataPort == 0) {
            log("AIRPLAY: трансляция на главном экране отклонена (streams=$streams)")
            return null
        }
        val cipher =
            if (sessionKey != null) {
                StreamFrameCipher.ChaCha(AirPlayStreamKeys.chachaStreamKey(sessionKey, connectionId))
            } else {
                val (key, iv) = AirPlayStreamKeys.aesStreamKeys(AirPlayStreamKeys.ZERO_SESSION_KEY, connectionId)
                StreamFrameCipher.AesCtr(key, iv)
            }
        log(
            "AIRPLAY: ✓ Согласование потока на главном экране успешно выполнено dataPort=$dataPort connId=$connectionId" +
                "Шифрование=${if (cipher is StreamFrameCipher.ChaCha) "ChaCha20" else "AES-128-CTR（Непарный сеанс）"}",
        )
        // MIB3: Настройка экрана 200 После получения порта данных реальная машина отправляет обнаружение (Frida
        // Время: экран НАСТРОЙКА → обнаружение телефона на управлении → HU SYN на управлении). См. ArmMib3VirtualLink.
        armMib3VirtualLink()
        return ScreenStreamInfo(connectionId, dataPort, cipher)
    }

    /** ЗАПИСЬ: Активация сеанса (завершение приема onSessionActive). Путь указывается относительно `/` (то же самое, что и запись catplay()). */
    fun record(): Boolean =
        try {
            val (statusLine, _) =
                request(
                    "RECORD",
                    "/",
                    BinaryPlist.encode(linkedMapOf<String, Any?>()),
                    contentType = "application/x-apple-binary-plist",
                )
            val ok = statusLine.contains("200")
            log(if (ok) "AIRPLAY: ✓ ЗАПИСЬ завершена – сессия активирована" else "AIRPLAY: ЗАПИСАТЬ ответ [$statusLine]")
            ok
        } catch (error: IOException) {
            log("AIRPLAY: Ошибка записи — ${error.message}")
            false
        }

    /**
     * подключает канал событий (приемник ждет нас на eventPort ответа SETUP).
     *
     * [sessionKey] шифруется с помощью ключа событий, если он не равен нулю (обратите внимание, что метка
     * направления противоречит здравому смыслу: принимающая сторона использует
     * Events-Read читает наши данные); **null = непарный сеанс, полный текст канала** (catplay
     * Та же модель, что и       `EventsServer::unencrypted`, старые автомобили до HomeKit распознают только этот тип).
     * Поток чтения       реорганизует простой текст в пакеты RTSP, анализирует команды plist (такие как
     * hidSendReport touch) и выполняет обратный вызов [onCommand].
     */
    fun connectEventChannel(
        sessionKey: ByteArray?,
        setup: SetupInfo,
        onCommand: (Map<*, *>) -> Unit = {},
    ) {
        if (setup.eventPort == 0) {
            log("СОБЫТИЕ: порт события SETUP не указан, пропустить")
            return
        }
        val host = receiverHost() ?: return
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(resolveAddress(host), setup.eventPort), 5_000)
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            // Канал событий также является долгоживущим соединением: вы должны иметь возможность узнать, когда пир исчезает
            // (см. примечание о TcpLiveness).

            TcpLiveness.configure(socket, "канал событий", log)
            log(
                "СОБЫТИЕ: ✓ Подключенный канал событий $host:${setup.eventPort} " +
                    "(${if (sessionKey != null) "Шифрование" else "простой текст"}）",
            )
            val cipher =
                sessionKey?.let { secret ->
                    val phoneWriteKey =
                        AirPlayCrypto.hkdfSha512(
                            secret,
                            "Events-Salt".toByteArray(),
                            "Events-Read-Encryption-Key".toByteArray(),
                        )
                    val phoneReadKey =
                        AirPlayCrypto.hkdfSha512(
                            secret,
                            "Events-Salt".toByteArray(),
                            "Events-Write-Encryption-Key".toByteArray(),
                        )
                    ControlCipher(phoneReadKey, phoneWriteKey)
                }
            registerEventCommandChannel(socket, cipher)
            Thread({
                val input = socket.getInputStream()
                val accumulator = RtspAccumulator()
                var pending = ByteArray(0)
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count =
                            try {
                                input.read(buffer)
                            } catch (_: java.net.SocketTimeoutException) {
                                // 30 секунд простоя — это нормальное состояние (низкая частота канала событий): только сигнал пробуждения,
                                // никогда не выход——

                                // Выход потока чтения будет расценен принимающей стороной как событие отключения соединения и вся сессия
                                // (включая видео/Bluetooth) будет демонтирована.

                                continue
                            }
                        if (count < 0) {
                            log("СОБЫТИЕ: Канал закрыт (EOF)")
                            break
                        }
                        pending += buffer.copyOf(count)
                        val plain =
                            if (cipher == null) {
                                val all = pending
                                pending = ByteArray(0)
                                all
                            } else {
                                val decrypted = cipher.decrypt(pending)
                                pending = decrypted.rest
                                decrypted.data
                            }
                        var eventOtherLogged = 0
                        for (message in accumulator.offer(plain)) {
                            val firstLine = message.firstLine
                            // (a) Строка состояния («RTSP/1.0 200 OK» и т. д.) = реакция автомобиля на **нашу команду**:
                            //     Пропускать без звука — нет ответа, нет записи (ответ вызовет эхо пинг-понга, см. ответEventRequest)
                            if (firstLine.startsWith("RTSP/") || firstLine.startsWith("HTTP/")) continue
                            // (b) Определение запроса: токен метода + начальный путь "/". Неверно сформированные строки (например, «ОК 200
                            // ОК»:

                            //     Второй токен — число) оценивается как отсутствие запроса → молча отбрасывается, нет ответа, нет памяти
                            val tokens = firstLine.split(" ")
                            val isRequest = tokens.size >= 2 && tokens[1].startsWith("/")
                            if (!isRequest) continue
                            // Запрос автомобиля и машины: немедленно вернуть 200 (эхо-запрос CSeq); /команда распространяется, а остальная
                            // часть регулирования оставляет следы

                            val cseq = CSEQ_REGEX.find(message.head)?.groupValues?.get(1)
                            respondEventRequest(cseq)
                            if (!firstLine.contains("/command")) {
                                unknownEventRequests++
                                if (unknownEventRequests <= 10) {
                                    log("СОБЫТИЕ: Неизвестный запрос [$firstLine] body=${message.body.size}B")
                                }
                                continue
                            }
                            var commandError: String? = null
                            val parsed =
                                try {
                                    val result = BinaryPlist.parseRecovered(message.body)
                                    result.note?.let { note ->
                                        log("СОБЫТИЕ: /command Разрешение отказоустойчивости успешно — — $note")
                                    }
                                    result.value
                                } catch (error: Throwable) {
                                    // Невозможно просто перехватить IllegalArgumentException: исключение выхода за пределы/длины, если оно
                                    // экранировано

                                    // Это приведет к непосредственному уничтожению потока чтения событий, и весь сеанс будет прерван.
                                    commandError = "${error.javaClass.simpleName}: ${error.message}"
                                    null
                                }
                            val command = parsed as? Map<*, *>
                            if (command == null) {
                                // Автомобиль каждую секунду будет отправлять неразборчивую команду / — нажмите «Body Fingerprint».
                                // Чтобы устранить дублирование, сохраняйте полный текст (причина + заголовок сообщения + полный
                                // шестнадцатеричный код) один раз для каждого типа и учитывайте только остальные.

                                badEventCommands++
                                val signature =
                                    message.body.size.toString() + ":" + hexPrefix(message.body, 8)
                                if (badEventSignatures.add(signature) && badEventSignatures.size <= 4) {
                                    log(
                                        "СОБЫТИЕ: команда не распознана [$firstLine] body=${message.body.size}B" +
                                            "Причина =" + (commandError ?: "Результат анализа=${parsed?.javaClass?.simpleName}"),
                                    )
                                    log(
                                        "СОБЫТИЕ: заголовок командного сообщения =" +
                                            message.head.replace("\r\n", " | ").take(300),
                                    )
                                    log("СОБЫТИЕ: Тело командного сообщения (${message.body.size}B) hex=" + hexDump(message.body))
                                }
                                if (badEventCommands % 120 == 0) {
                                    log("СОБЫТИЕ: Накоплены ошибки анализа $badEventCommands/команд (тихо, только подсчет)")
                                }
                                continue
                            }
                            try {
                                onCommand(command)
                            } catch (error: Exception) {
                                log("СОБЫТИЕ: Исключение обработки команды — ${error.message}")
                            }
                        }
                    }
                } catch (error: IOException) {
                    log("СОБЫТИЕ: Конец чтения - ${error.message}")
                }
            }, "event-reader").apply {
                isDaemon = true
                start()
            }
        } catch (error: IOException) {
            log("СОБЫТИЕ: Ошибка подключения — ${error.message}")
        }
    }

    /** Повторный сборщик потоковых сообщений RTSP: подайте любой фрагментированный простой текст и выведите полное сообщение (первая строка + тело). */
    private class RtspAccumulator {
        /** Полное сообщение: первая строка (для классификации), полный текст заголовка (для извлечения CSeq), тело. */
        class Message(
            val firstLine: String,
            val head: String,
            val body: ByteArray,
        )

        private var buffer = ByteArray(0)

        fun offer(bytes: ByteArray): List<Message> {
            if (bytes.isEmpty()) return emptyList()
            buffer += bytes
            val messages = ArrayList<Message>()
            while (true) {
                val index = indexOf(buffer, HEAD_SEPARATOR)
                if (index < 0) break
                val headText = String(buffer, 0, index, Charsets.ISO_8859_1)
                val (firstLine, contentLength) = parseRtspHead(headText)
                val bodyStart = index + HEAD_SEPARATOR.size
                if (buffer.size - bodyStart < contentLength) break
                messages +=
                    Message(
                        firstLine,
                        headText,
                        buffer.copyOfRange(bodyStart, bodyStart + contentLength),
                    )
                buffer = buffer.copyOfRange(bodyStart + contentLength, buffer.size)
            }
            return messages
        }
    }

    /**
     * `GET /info` (та же модель, что и Catplay `AirPlayRtspClient::info`):
     * Тело       представляет собой **пустой словарь bplist** (не нулевой длины), Content-Type должен быть
     * `application/x-apple-binary-plist`. Старая прошивка (эпоха 210.81) Верно Нет тела/Неверно
     * /info       Content-Type вернет 400/455, и последующая установка завершится неудачно.
     */
    private fun requestInfo(timeoutMillis: Int = 10_000): Pair<String, ByteArray> =
        request(
            "GET",
            "/info",
            BinaryPlist.encode(linkedMapOf<String, Any?>()),
            timeoutMillis,
            contentType = "application/x-apple-binary-plist",
        )

    // ---- RTSP-запрос ----

    private fun ensureConnected(timeoutMillis: Int = 5_000): Boolean {
        if (socket?.isConnected == true && socket?.isClosed == false) return true
        // Конечная точка снимка и алгебра: Мастер может изменить конечную точку во время соединения (0x4301 опаздывает),
        // Если вы используете старый адрес для установления соединения, а затем запросите его, вы будете подключены не к
        // той машине, пока не истечет время текущего раунда.

        val target = endpoint
        val generation = endpointGeneration
        val candidates = EndpointHosts.candidates(target.ipAddresses)
        if (candidates.isEmpty()) {
            log("AIRPLAY: 0x4301/mDNS не предоставляет доступный адрес и не может подключиться.")
            return false
        }
        if (target.airPlayPort <= 0) {
            log("AIRPLAY: Неизвестный порт (${target.airPlayPort}), невозможно подключиться.")
            return false
        }
        val failures = ArrayList<String>(candidates.size)
        for (host in candidates) {
            val address =
                try {
                    resolveAddress(host)
                } catch (error: Exception) {
                    failures += "$host (ошибка анализа: ${error.message})"
                    continue
                }
            try {
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(address, target.airPlayPort), timeoutMillis)
                if (generation != endpointGeneration) {
                    // Конечная точка была обновлена во время соединения: отмените старое соединение и повторно подключитесь по
                    // новому адресу в следующем раунде.

                    runCatching { newSocket.close() }
                    return false
                }
                // Чтение обрабатывается независимым потоком чтения (блокирование и ожидание данных), а время ожидания запроса
                // контролируется опросом таблицы, на которую необходимо ответить;

                // Если здесь оставить soTimeout, поток чтения будет периодически получать SocketTimeoutException и завершаться.
                newSocket.soTimeout = 0
                // Проверка работоспособности однорангового узла: служба поддержки активности ядра по умолчанию ожидает 2 часа,
                // прежде чем начать обнаружение, и автомобиль выключается/

                // При выходе из хотспота ссылка часто не отправляет RST, и поток чтения всегда зависает (экран зависает, а
                // сторожевой таймер не срабатывает).

                TcpLiveness.configure(newSocket, "канал управления", log)
                socket = newSocket
                activeAddress = address
                activeHost = addressLiteral(address)
                log(
                    "AIRPLAY: TCP-подключение $activeHost:${target.airPlayPort} (локальный компьютер " +
                        "${newSocket.localAddress.hostAddress})",
                )
                return true
            } catch (error: IOException) {
                failures += "$host（${error.message}）"
            }
        }
        log(
            "AIRPLAY: соединение не удалось — ${failures.joinToString("；")}\n" +
                "↳ Если ошибка повторяется: убедитесь, что телефон в настоящее время подключен к точке доступа " +
                "принимающей стороны \"${target.ssid}\"," +
                "Вместо домашнего/корпоративного Wi-Fi (при неправильной привязке к сети автомобильно-машинный сегмент сети недоступен)",
        )
        return false
    }

    /**
     * разрешает адрес подключения. Локальный канал IPv6 должен иметь область действия (`%wlan0`), которая будет использоваться Java:
     * mDNS обычно возвращает адрес с областью действия, а `fe80::…` в 0x4301 — нет — добавьте сюда
     * текущий интерфейс Wi-Fi, в противном случае `connect` сообщит, что сеть недоступна.
     */
    private fun resolveAddress(host: String): InetAddress {
        val address = InetAddress.getByName(host)
        if (address is Inet6Address && address.isLinkLocalAddress && address.scopeId == 0) {
            val zone = host.substringAfter('%', "").takeIf { it.isNotEmpty() }
            val iface = zone?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() } ?: wifiInterface()
            if (iface != null) {
                return Inet6Address.getByAddress(null, address.address, iface)
            }
        }
        return address
    }

    /** Найдите интерфейс, по которому передается трафик точки доступа «автомобиль-машина» (для локальной области канала IPv6). */
    private fun wifiInterface(): NetworkInterface? {
        val candidates =
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            }.getOrDefault(emptyList())
        return candidates.firstOrNull { nic ->
            nic.isUp &&
                !nic.isLoopback &&
                (nic.name.startsWith("wlan") || nic.name.startsWith("ap") || nic.name.startsWith("eth"))
        } ?: candidates.firstOrNull { nic -> nic.isUp && !nic.isLoopback }
    }

    /**
     * Текст адреса : ссылка IPv6 должна иметь локальную область действия, чтобы ее можно было использовать
     * последующим `InetAddress.getByName`.
     * единообразно выводит **числовой идентификатор области** (`fe80::1%3`) — формат имени интерфейса имеет
     * непоследовательную поддержку синтаксического анализа на разных ПЗУ.
     */
    private fun addressLiteral(address: InetAddress): String {
        val text = address.hostAddress ?: return address.toString()
        if (address is Inet6Address && address.scopeId != 0) {
            return "${text.substringBefore('%')}%${address.scopeId}"
        }
        return text
    }

    private fun post(
        path: String,
        body: ByteArray,
    ): ByteArray {
        val (statusLine, responseBody) = request("POST", path, body)
        if (!statusLine.contains("200")) {
            throw IOException("Ответ POST $path [$statusLine]")
        }
        return responseBody
    }

    /**
     * Запрос на сопряжение  (настройка пары/проверка пары): клиент Apple имеет тот же заголовок, что и Catplay PairingHelperTx.
     * реальный тест автомобиля carplay на реальном автомобиле (направление Catplay TX работает на реальном автомобиле):
     * — Тип контента: приложение/сопряжение+tlv8
     * — X-Apple-HKP: 2 (сопряжение с HomeKit v2, идентификационный заголовок клиента Apple)
     * — X-Apple-PD: 1 (извлечение пары, только проверка пары)
     * 11 октября 2026 г.: в этой версии по умолчанию используется application/octet-stream и нет этих двух заголовков. У Audi есть
     * Pair-Setup/Pair-Verify all возвращает 403 (уровень HTTP отклонен, разрешение TLV не введено).
     */
    private fun postPairing(
        path: String,
        body: ByteArray,
    ): ByteArray {
        val extra =
            buildList {
                add("X-Apple-HKP" to "2")
                if (path.endsWith("pair-verify")) add("X-Apple-PD" to "1")
            }
        val (statusLine, responseBody) =
            request(
                "POST",
                path,
                body,
                contentType = "application/pairing+tlv8",
                extraHeaders = extra,
            )
        // Диагностика (устранение неполадок 403 на реальном автомобиле): длина запроса + строка состояния ответа/длина/байты заголовка
        val headHex = responseBody.take(16).joinToString(" ") { "%02x".format(it) }
        log(
            "AIRPLAY: $path req=${body.size}B -> [$statusLine] resp=${responseBody.size}B" +
                if (responseBody.isNotEmpty()) " head=[$headHex]" else "",
        )
        if (!statusLine.contains("200")) {
            throw IOException("Ответ POST $path [$statusLine]")
        }
        return responseBody
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray,
        timeoutMillis: Int = 10_000,
        contentType: String = "application/octet-stream",
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): Pair<String, ByteArray> {
        val client = socket ?: throw IOException("Не подключено")
        val startedAt = SystemClock.uptimeMillis()
        startReader(client)
        val queue = java.util.concurrent.LinkedBlockingQueue<Pair<String, ByteArray>>(1)
        val cseq: Int
        synchronized(rtspWriteLock) {
            cseq = ++cSeq
            // Сначала зарегистрируйтесь, а затем напишите: ответ может прийти до возврата записи.
            pendingResponses[cseq] = queue
            try {
                val head =
                    "$method $path RTSP/1.0\r\n" +
                        "CSeq: $cseq\r\n" +
                        "User-Agent: AirPlay/$SENDER_SOURCE_VERSION\r\n" +
                        "Content-Type: $contentType\r\n" +
                        extraHeaders.joinToString("") { "${it.first}: ${it.second}\r\n" } +
                        "Content-Length: ${body.size}\r\n" +
                        "\r\n"
                val plaintext = head.toByteArray(Charsets.US_ASCII) + body
                val encryption = cipher
                val wire =
                    if (encryption != null) {
                        synchronized(encryption) { encryption.encrypt(plaintext) }
                    } else {
                        plaintext
                    }
                val output = client.getOutputStream()
                output.write(wire)
                output.flush()
            } catch (error: Throwable) {
                pendingResponses.remove(cseq)
                throw error
            }
        }
        val response =
            try {
                queue.poll(timeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: throw IOException("Тайм-аут RTSP (${timeoutMillis}ms, CSeq=$cseq $method $path)")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("RTSP прерван (CSeq=$cseq $method $path)")
            } finally {
                pendingResponses.remove(cseq)
            }
        // Следы медленного ответа: > 1,5 с, что указывает на то, что автомобиль находится на внутреннем арбитраже
        // (свидетельства из первых рук по устранению подобных проблем)

        val elapsed = SystemClock.uptimeMillis() - startedAt
        if (elapsed > 1_500) {
            log("AIRPLAY: Медленный ответ ${elapsed}ms ($method $path, CSeq=$cseq) — пульс не прерывается в течение периода ожидания")
        }
        if (response.first == RTSP_CLOSED_MARK) {
            throw IOException("Канал управления закрыт (CSeq=$cseq $method $path)")
        }
        lastRequestCSeq.set(cseq)
        return response
    }

    /**
     * Поток чтения канала управления : равномерно принимает пакеты, расшифровывает и отправляет ответы на
     * соответствующие запросы в соответствии с CSeq.
     *
     * Почему       должен быть асинхронным (фактическая основная причина, измеренная 17 октября 2026 г.): ответ Audi
     * SETUP занимает около 2,7 с, ранее
     * «Одно соединение, одна блокировка + синхронный ответ на чтение» приведет к полной остановке контрольного
     * сигнала обратной связи в течение этого периода; пара машина-машина
     * Обратная связь       имеет сторожевой таймер ~3 с — если подача звука прерывается во время НАСТРОЙКИ, канал
     * событий будет сброшен и весь сеанс будет прерван.
     * (характеристики журнала: обратная связь №1 и №2 разделены интервалом 2,7 с, за которым следует `EVENT: Конец
     * чтения - Connection reset`).
     * После того, как       будет изменен на асинхронный, любой длинный запрос больше не будет блокировать контрольное сообщение.
     */

    /**
     * Поток чтения разделяет буфер в зависимости от того, «включено ли шифрование»:
     * В тот момент, когда шифр       изменится с нулевого на ненулевой (проверка пары завершена), предыдущий остаток
     * открытого текста должен быть отброшен — —
     * Если хвост открытого текста вставлен в кадр зашифрованного текста для синтаксического анализа, граница кадра
     * ChaCha будет смещена, и все последующие ответы будут не поддаются расшифровке.
     */
    private fun readLoop(
        client: Socket,
        generation: Int,
    ) {
        var cipherPending = ByteArray(0)
        var buffer = ByteArray(0)
        var encryptedMode = false
        try {
            val input = client.getInputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                val encryption = cipher
                if (encryption != null && !encryptedMode) {
                    // Точка переключения: Очистите остаток на стороне открытого текста, а затем постоянно используйте расшифровку кадра.
                    encryptedMode = true
                    cipherPending = ByteArray(0)
                }
                val data =
                    if (encryption == null) {
                        chunk.copyOf(count)
                    } else {
                        cipherPending += chunk.copyOf(count)
                        val decrypted =
                            try {
                                synchronized(encryption) { encryption.decrypt(cipherPending) }
                            } catch (error: Exception) {
                                log("AIRPLAY: не удалось расшифровать канал управления — ${error.message}")
                                break
                            }
                        cipherPending = decrypted.rest
                        decrypted.data
                    }
                if (data.isEmpty()) continue
                buffer += data
                while (true) {
                    val parsed = parseRtspMessage(buffer) ?: break
                    buffer = parsed.rest
                    dispatchResponse(parsed.statusLine, parsed.body, parsed.head)
                }
            }
        } catch (_: IOException) {
        } finally {
            // Только «текущий» поток чтения очищает учетную запись: повторное подключение изменит сокет и новый поток
            // чтения. Старый поток не должен случайно повредить новый сеанс.

            if (generation == readerGeneration) {
                failAllPending("Заканчивается поток чтения канала управления")
            }
        }
    }

    /** Сообщения канала управления (для отправки CSeq; глава для диагностики и классификации запросов/ответов). */
    private class RtspMessage(
        val statusLine: String,
        val body: ByteArray,
        val head: String,
    ) {
        val cseq: Int? get() =
            CSEQ_REGEX
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        /** Запрос, активно отправленный транспортным средством (первая строка — `METHOD /path RTSP/1.0`); первая строка ответа — `RTSP/1.0 200 OK`. */
        val isRequest: Boolean
            get() {
                val tokens = statusLine.split(" ")
                return tokens.size >= 2 && tokens[1].startsWith("/")
            }
    }

    private fun dispatchResponse(
        statusLine: String,
        body: ByteArray,
        head: String,
    ) {
        val message = RtspMessage(statusLine, body, head)
        message.cseq?.let { responseHeads[it] = head }
        val queue = message.cseq?.let { pendingResponses[it] }
        if (queue != null) {
            queue.offer(message.statusLine to message.body)
            return
        }
        // Нет соответствия: транспортное средство может отправить собственный запрос по каналу управления (200 должно
        // быть возвращено, иначе канал будет удален).

        // Только регулирование оставляет след, а не молчаливое отбрасывание — предыдущая реализация синхронизации
        // ошибочно приняла бы его за ответ на предыдущий запрос.

        unmatchedMessages++
        if (unmatchedMessages <= 5) {
            log(
                "AIRPLAY: нет соответствующего сообщения в канале управления (#$unmatchedMessages)." +
                    "[$statusLine] cseq=${message.cseq} request=${message.isRequest} body=${body.size}B",
            )
        }
        if (message.isRequest) {
            // Мультиплексный запрос CSeq возвращает пустой ответ (то же соглашение, что и для канала событий)
            runCatching {
                val payload =
                    "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n" +
                        (message.cseq?.let { "CSeq: $it\r\n" } ?: "") + "\r\n"
                val encryption = cipher
                synchronized(rtspWriteLock) {
                    val out = socket?.getOutputStream() ?: return@runCatching
                    out.write(
                        if (encryption != null) {
                            synchronized(encryption) { encryption.encrypt(payload.toByteArray(Charsets.US_ASCII)) }
                        } else {
                            payload.toByteArray(Charsets.US_ASCII)
                        },
                    )
                    out.flush()
                }
            }
        }
    }

    /** Количество несовпадающих пакетов (для журналов регулирования). */
    private var unmatchedMessages = 0

    private fun failAllPending(reason: String) {
        val waiting = pendingResponses.values.toList()
        pendingResponses.clear()
        if (waiting.isEmpty()) return
        log("AIRPLAY: $reason —— Пробуждение ожидающих запросов ${waiting.size}.")
        waiting.forEach { it.offer(RTSP_CLOSED_MARK to ByteArray(0)) }
    }

    private fun startReader(client: Socket) {
        val previous: Socket?
        synchronized(rtspWriteLock) {
            if (readerSocket === client && readerThread?.isAlive == true) return
            previous = readerSocket
            readerSocket = client
            val generation = ++readerGeneration
            readerThread =
                Thread({ readLoop(client, generation) }, "airplay-rtsp-reader").apply {
                    isDaemon = true
                    start()
                }
        }
        // Входной поток старого сокета удерживается этим потоком: он должен быть закрыт (будет создано исключение
        // SocketException, чтобы позволить старому потоку чтения выйти),

        // В противном случае два потока чтения могут быть заблокированы в одном и том же соединении и перехватить пакеты
        // — это приведет к тому, что ответ будет неправильным после асинхронной отправки.

        if (previous != null && previous !== client) {
            runCatching { previous.close() }
        }
    }

    /** CSeq, на который будет дан ответ → очередь ответов (асинхронная отправка; обратная связь больше не ограничивается длинными запросами). */
    private val pendingResponses =
        java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.LinkedBlockingQueue<Pair<String, ByteArray>>>()

    /** Последовательная сторона записи: выделение CSeq + счетчик записей шифра + запись сокета должны быть атомарными (сторона чтения в отдельном потоке). */
    private val rtspWriteLock = Any()

    private var readerThread: Thread? = null
    private var readerSocket: Socket? = null

    /** Генерация потока чтения: после повторного подключения старый поток не должен очищать таблицу ожидающих ответов нового сеанса. */
    private var readerGeneration = 0

    /** Последний заголовок ответа RTSP (хранится CSeq: поток чтения и поток запроса работают одновременно, одно поле не будет соответствовать другим запросам). */
    private val responseHeads = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** CSeq самого последнего запроса() этого потока (используется для диагностического получения заголовков ответов; ThreadLocal позволяет избежать несоответствия параллелизма). */
    private val lastRequestCSeq = ThreadLocal<Int>()

    /** Получите заголовок ответа последнего запроса этого потока (для диагностики; если его невозможно прочитать, будет возвращена пустая строка). */
    private fun lastResponseHeadOfRequest(): String =
        lastRequestCSeq
            .get()
            ?.let { responseHeads[it] }
            ?.replace("\r\n", " | ")
            .orEmpty()

    /** Результат синтаксического анализа (сообщение + оставшиеся неизрасходованные байты). */
    private class ParsedRtsp(
        val statusLine: String,
        val body: ByteArray,
        val head: String,
        val rest: ByteArray,
    )

    /**
     * Вырезать полное сообщение (заголовок + тело Content-Length) из буфера.
     * вызывается только в потоке чтения канала управления (буфер является локальным состоянием потока и не
     * используется совместно несколькими потоками).
     */
    private fun parseRtspMessage(buffer: ByteArray): ParsedRtsp? {
        val separatorIndex = indexOf(buffer, HEAD_SEPARATOR)
        if (separatorIndex < 0) return null
        val headText = String(buffer, 0, separatorIndex, Charsets.ISO_8859_1)
        val (statusLine, contentLength) = parseRtspHead(headText)
        val bodyStart = separatorIndex + HEAD_SEPARATOR.size
        if (buffer.size - bodyStart < contentLength) return null
        val responseBody = buffer.copyOfRange(bodyStart, bodyStart + contentLength)
        val rest =
            if (buffer.size > bodyStart + contentLength) {
                buffer.copyOfRange(bodyStart + contentLength, buffer.size)
            } else {
                ByteArray(0)
            }
        return ParsedRtsp(statusLine, responseBody, headText, rest)
    }

    // ---- Сохранение идентичности ----

    private fun loadOrCreateIdentity(): ControllerIdentity {
        val preferences = context?.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
        val storedId = preferences?.getString("pairingId", null)
        val storedPrivate = preferences?.getString("privateKey", null)
        if (storedId != null && storedPrivate != null) {
            val privateKey = Base64.getDecoder().decode(storedPrivate)
            val publicKey = AirPlayCrypto.ed25519PublicKey(privateKey)
            return ControllerIdentity(storedId, privateKey, publicKey)
        }
        val keyPair = AirPlayCrypto.ed25519Generate()
        val pairingId = UUID.randomUUID().toString()
        preferences
            ?.edit()
            ?.putString("pairingId", pairingId)
            ?.putString("privateKey", Base64.getEncoder().encodeToString(keyPair.privateKey))
            ?.apply()
        return ControllerIdentity(pairingId, keyPair.privateKey, keyPair.publicKey)
    }

    // ---- Инструменты ----

    /**
     * Адрес получателя (общий для канала событий/туннеля iAP/видео/аудио).
     *
     * отдает приоритет адресу, который фактически подключен во время сопряжения: IPv4 может быть недоступен в
     * многоадресных конечных точках (точка доступа Audi
     * обычно предоставляет ссылку только локальный IPv6). Если другие каналы выберут IPv4 соответственно, "канал
     * управления сможет пройти, но видео не сможет пройти".
     */
    private fun receiverHost(): String? = activeHost ?: EndpointHosts.candidates(endpoint.ipAddresses).firstOrNull()

    /**
     * Идентификатор устройства, сообщаемый программой SETUP на уровне сеанса (используется принимающей стороной для
     * определения того, «какой это iPhone»).
     *
     * должен быть настоящим MAC-адресом Bluetooth этого устройства: WinPlay 19 октября 2026 г. Фактические измерения
     * в соответствии с Bluetooth Bluetooth, выбранным пользователем.
     * Фильтрация адресов      , значения заполнителя/ложь всегда равны 403; Audi MIB3 и DiPlay не проверяют, поэтому
     * старые значения жестко запрограммированы.
     * никогда не вызывал никаких проблем. Стадия BT должна быть проанализирована и кэширована (0x4300/0x4E0E также полагается на нее),
     * Здесь приоритет отдается чтению кэша, чтобы избежать перезапуска оболочки; если кэш отсутствует, парсинг будет заблокирован один раз.
     */
    private fun setupDeviceId(): String {
        val applicationContext = context ?: return DeviceIdentity.PLACEHOLDER_MAC
        // Возвращается попадание в кэш (анализируется на этапе BT); допускается только отсутствующий запрос.
        return DeviceIdentity.cachedBluetoothMac()
            ?: DeviceIdentity.bluetoothMac(applicationContext)
    }

    /**
     * создает тело сообщения SETUP на уровне сеанса (удаляется для одиночного тестирования: как только идентификатор
     * устройства возвращается к записанному ложному значению,
     * Приемники, отфильтрованные такими устройствами, как       WinPlay, напрямую ответят 403, и для отключения
     * этого явления потребуется 45 секунд).
     */
    internal fun buildSessionSetupBody(
        deviceId: String,
        timingPort: Int,
        /** false = непарный/незашифрованный сеанс: должен явно указывать `et=NONE` (то же самое, что и catplay do_initial_setup). */
        encrypted: Boolean = true,
    ): ByteArray {
        val fields =
            linkedMapOf<String, Any?>(
                "deviceID" to deviceId,
                "macAddress" to "aa:bb:cc:dd:ee:ff",
                "name" to "iPhone",
                "model" to "iPhone14,2",
                "osName" to "iPhone OS",
                "osBuildVersion" to "19A404",
                "osVersion" to "15.0.2",
                // собственная версия AirPlay для iPhone (эмпирическая ценность кошачьих игр); 320.17.7, который ранее
                // неправильно использовался в автомобиле
                "sourceVersion" to SENDER_SOURCE_VERSION,
                "timingPort" to timingPort.toLong(),
                "sessionUUID" to
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .uppercase(),
                "sessionCorrelationUUID" to
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .uppercase(),
                "statsCollectionEnabled" to false,
                "updateSessionRequest" to false,
                "keepAliveLowPower" to true,
                "features" to CONTROLLER_FEATURES,
            )
        if (!encrypted) {
            // AirPlayEncryptionType::NONE = 1<<0 (пониженное значение, если catplay не запускается /pair-setup).
            // При отсутствии этого ключа строгий получатель может отклонить сеанс как «зашифрованный, но без ключа».
            fields["et"] = 1L
        }
        return BinaryPlist.encode(fields)
    }

    private fun typeName(value: Any?): String =
        when (value) {
            null -> "null"
            is ByteArray -> "data(${value.size}B)"
            is List<*> -> "list(${value.size})"
            is Map<*, *> -> "dict(${value.size})"
            is String -> "str(${value.length})"
            else -> value.javaClass.simpleName
        }

    /**
     * `/info displays[].uuid` унифицированная строковая структура.
     *
     * видел три кодировки: строка (Audi "0"/"1", некоторые прошивки дают 36-битный UUID),
     * **16-байтовый тип UUID** (bplist 0xB, `toString()` станет мусором, как `[B@1f2e3d4`),
     * UID CFKeyedArchiver (целое число). `uuid` в потоке экрана SETUP должен быть таким же, как тот, который объявлен принимающей стороной.
     * **Точно то же самое**, в противном случае автомобиль может быть подключен, но не подключен ни к одному экрану
     * (отображается как «подключен, но нет экрана»).
     */
    private fun uuidText(value: Any?): String? =
        when (value) {
            null -> null
            is String -> value.takeIf { it.isNotBlank() }
            is ByteArray ->
                if (value.size == 16) {
                    val hex = value.joinToString("") { "%02x".format(it) }
                    "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                        "${hex.substring(16, 20)}-${hex.substring(20)}"
                } else {
                    value.joinToString("") { "%02x".format(it) }
                }
            is Number -> value.toString()
            else -> value.toString().takeIf { it.isNotBlank() }
        }

    private fun logInfoSummary(root: Map<*, *>) {
        // Полный список полей верхнего уровня (ключ:тип) - с первого взгляда понятно, какой ключ скрывает двигатель
        // автомобиля, чтобы не просто распознать

        // Официальные ключи Apple (на ключах, которые мы не анализировали, может быть заявление HID BMW iD9)
        log(
            "AIRPLAY: /info поле верхнего уровня (${root.size})=" +
                root.entries.joinToString(" ") { (key, value) -> "$key:${typeName(value)}" },
        )
        // Любой ключ, в названии которого есть hid, печатается полностью (список устройств + дескриптор являются
        // обязательным условием для сенсорного доступа)

        root.entries
            .filter { (key, _) -> key.toString().contains("hid", ignoreCase = true) }
            .forEach { (key, value) -> log("AIRPLAY: /info $key=$value") }
        val features = root["features"] as? Long
        log(
            "AIRPLAY: /info summary name=${root["name"]} model=${root["model"]} " +
                "deviceID=${root["deviceID"]}" +
                if (features != null) " features=0x${features.toString(16)}" else "",
        )
        (root["displays"] as? List<*>)?.forEachIndexed { index, display ->
            log("AIRPLAY: /info displays[$index]=$display")
            if (display is Map<*, *>) {
                log(
                    "AIRPLAY: /info отображает ключ [$index]=" +
                        display.entries.joinToString(" ") { (key, value) ->
                            "$key=${value.toString().take(80)}"
                        },
                )
            }
        }
        // Возможности аудио печатаются по одному (вся строка журнала /info будет обрезана, а audioFormats попадает точно в точку усечения):
        // Шестерни и растровые изображения audioType, объявленные для type=100 на каждом транспортном средстве, сильно
        // различаются, и согласование зависит от этих деталей.

        (root["audioFormats"] as? List<*>)?.forEachIndexed { index, entry ->
            val format = entry as? Map<*, *> ?: return@forEachIndexed
            val output = (format["audioOutputFormats"] as? Number)?.toLong() ?: 0L
            val input = (format["audioInputFormats"] as? Number)?.toLong() ?: 0L
            log(
                "AIRPLAY: audioFormat[$index] type=${format["type"]} audioType=${format["audioType"]} " +
                    "output=0x${output.toString(16)} input=0x${input.toString(16)}" +
                    "(PCM48k стерео бит15=${if ((output and (1L shl 15)) != 0L) "✓" else "✗"}）",
            )
        }
        (root["audioLatencies"] as? List<*>)?.forEachIndexed { index, entry ->
            val latency = entry as? Map<*, *> ?: return@forEachIndexed
            log(
                "AIRPLAY: audioLatency[$index] type=${latency["type"]} audioType=${latency["audioType"]} " +
                    "in=${latency["inputLatencyMicros"]} out=${latency["outputLatencyMicros"]}",
            )
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val cleaned = hex.trim()
        if (cleaned.length % 2 != 0) return null
        return try {
            ByteArray(cleaned.length / 2) { index ->
                cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}

/**
 * Повторная попытка сбоя сопряжения (чистая функция, подтверждена тестом воспроизведения): 403 = Реальный
 * автомобиль «Доверие установлено, автомобиль и машина не готовы»
 * Статус   (системная диагностика реальной машины: окно готовности составляет около 18–20 с, в течение которых
 * высокочастотные повторные попытки могут продолжать сбрасывать его конечный автомат);
 * Другие сбои (порт не открыт и т. д.) продолжают быстрый опрос и другие приглашения.
 */
internal fun pairingRetryBackoffMs(failureMessage: String): Long = if (failureMessage.contains("403")) 5_000L else 500L
