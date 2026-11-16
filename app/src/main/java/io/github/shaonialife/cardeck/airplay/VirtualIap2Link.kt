package io.github.shaonialife.cardeck.airplay

import android.content.Context
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import io.github.shaonialife.cardeck.iap2.MfiLengthMemory
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.input.ConsumerKeys
import io.github.shaonialife.cardeck.input.HidNavigation
import java.security.SecureRandom
import java.util.TimeZone
import java.util.UUID

/**
 * Виртуальный канал iAP2 на канале событий  («туннель Wi-Fi» в сеансе AirPlay) реализует три формы обслуживания:
 *
 * - **DiPlay (туннель типа 130)**: исходящий туннель записи TCP; согласование ссылок использует семантику «сначала эхо»
 * (жесткие ожидания от однорангового движка, см. [Iap2LinkEngine.echoFirst]);
 * - **Audi MIB3 (канал событий + канал управления POST)**: реальный машинный тайминг (AADisplay висит с Frida
 * Захват пакета медиасервера iPhone с джейлбрейком для подтверждения) — **Поток экрана SETUP автоматически
 * отправляется телефоном один раз после успешной настройки.
 * DETECT (iAPSendMessage через канал управления POST/команда) → автомобиль возвращает SYN (канал событий)
 * → Мобильный телефон возвращает SYN|ACK (канал управления)**, после чего весь сеанс управления повторяется: 0x1D00 → 0x1D01 →
 * 0x1D02+0xAA00 → (0xAA06 → 0xAA00) → 0xAA02 Challenge 20B → 0xAA03 → 0xAA05 →
 * последние новости мобильного телефона → ответ на подписку на автомобильный телефон. **Если возвращается только
 * пустой ACK, MIB3 потребуется около 10–12 секунд для демонтажа всей цепочки**
 * (журнал реального двухколесного транспортного средства этого склада от 19 октября 2026 г. снова появился через
 * 10,2 с, что ранее было ошибочно расценено как отсутствие пульса);
 * — **Старая форма события/разговор в виде обычного текста**: маркер будет отображать один вопрос и один ответ
 * (с сохранением исторического поведения).
 *
 * Байты связи машина-машина → мобильный телефон поступают равномерно из канала событий iAPSendMessage ([onCarBytes]);
 * Мобильный телефон   → машина автомобиля разделена по форме (туннель TCP/канал управления POST <-- по
 * умолчанию). **Направления не могут быть изменены**:
 * Исходящая запись   не считывается в канале событий (заключение Фриды; воспроизведение фактического теста Honda HFT 20 октября 2026 г. ——
 * После того, как исходящий   был изменен на канал событий, транспортное средство не ответило ни на один пакет
 * синхронизации FF5A в течение 20 минут, а лишь повторно передало маркер с частотой 1 Гц).
 */
class VirtualIap2Link(
    private val log: (String) -> Unit,
    /** Исходящий канал событий iAPSendMessage (старая форма покрытия). */
    private val sendEventIap: (ByteArray) -> Boolean,
    /** Исходящий канал управления iAPSendMessage (единственный правильный канал для побочных кадров MIB3 для мобильных устройств). */
    private val sendControlIap: (ByteArray) -> Unit,
    /** Туннельный порт записи DiPlay type-130 (динамический доступ каждый раз при исходящем сообщении, может быть нулевым). */
    private val tunnelWriter: () -> AirPlayClient.IapTunnelClient?,
    /** Выполните условия выживания потока (соединение управления хостом не закрыто). */
    private val alive: () -> Boolean,
    /** Контекст персистентности для памяти длины MFi; null = внутренняя память (одиночный тест). */
    private val mfiContext: Context? = null,
    /** Часы (можно ввести один тест). */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()
    private val engine = Iap2LinkEngine()
    private val parser = Iap2Csm.Parser()
    private var frameCount = 0
    private var eaCount = 0
    private var started = false

    // ---- Состояние туннельного пути DiPlay ----
    private var tunnelCsmStarted = false
    private var tunnelIdentified = false

    /** Необходимо доставить синхронное эхо (шлюз туннеля: 0x1D00 разрешено отправлять только после того, как эхо фактически записано в TCP туннеля). */
    private var pendingEchoFlush = false
    private var tunnelEchoDelivered = false

    // ---- Статус пути MIB3 ----
    private var mib3Armed = false

    /**
     * Генерация связи между транспортными средствами  (перечисление и оценка заканчиваются на [ReceiverProfile],
     * основа оценки зависит от PROFILE/IAPLINK)
     * Вывод журнала      ).
     *
     * **23 октября 2026 г. Второе решение: согласование маркера больше не назначается поколением** — все
     * транспортные средства и машины должны завершить ссылку
     * Согласование       (маркер машина-машина = ждёт нашу таблицу сессий, бесконечная повторная передача на частоте
     * 1Гц; если нет ответа, ссылка никогда не придёт
     * НОРМАЛЬНО, цепь была снята машиной ~через 10 с после ЗАПИСИ: 0.1.9 измерял 10,17 с, 0.1.17 измерял 10,18 с).
     * ранее игнорировал маркер для CLASSIC_MIB3 в 0.1.16/0.1.17 на основе «0.1.9 игнорирования маркера».
     * Audi заходит как обычно при      " - этот вывод подтвержден собственным логом 0.1.9 (отключенным в 10.17с) и
     * логом автовладельца 0.1.17
     * перевернут. Настоящая причина «переговоров о приостановке исходного интерфейса карты пользовательского
     * интерфейса автомобиля» заключалась в том, что после завершения переговоров режимы ModeChanged отсутствовали.
     * (см. [onControlSessionComplete]).
     *
     * Генерация       теперь используется только для журналов и будущей отправки, а стратегия маркеров
     * унифицирована: согласование обновления по пороговому значению (Honda HFT’s
     * В носителе медиа-ключей       в 0x6802 после установления связи обязательным условием вырезания экрана Audi
     * является ссылка НОРМАЛЬНАЯ + переобъявление ресурса).
     */
    private var generation: ReceiverProfile.LinkGeneration = ReceiverProfile.LinkGeneration.EVENT_LINK
    private var mib3SynAckQueued = false
    private var mib3CsmStarted = false
    private var mib3Identified = false
    private var mib3AuthStage = 0
    private var mib3MarkerIgnores = 0
    private var mib3DeferredLogs = 0
    private var mib3WirelessSessionId: String? = null
    private var mib3ControlSessionNotified = false

    /**
     * Режим связи с каналом событий  (недавно протестирован на Honda HFT 20 октября 2026 г.).
     *
     * Транспортное средство неоднократно отправляет маркер SYN (`FF 55 02 00 EE 10`,
     * (раз в секунду, сотни раз подряд), ждем, пока мы вернем на него маркер, прежде чем продолжить отправку пакетов синхронизации.
     * Обнаружение       и [armMib3] использует **канал управления** — транспортное средство его не видит, поэтому
     * оно всегда отправляет сообщение повторно.
     *
     * Начиная с последовательного маркера [EVENT_LINK_MARKER_THRESHOLD], определяется, действительно ли он ожидает ответа.
     * переключается в этот режим: канал событий используется как для входящего, так и для исходящего трафика, а
     * соединение может быть установлено посредством ответа маркера + согласования пакета синхронизации.
     * Только после установки ссылки       машина отправит 0x5000 (подписка NowPlaying), 0x6800 (StartHID),
     * 0x6802 (HID-отчет = медиа-клавиши на руле/панели) - Здесь находится держатель кнопки переключения песен.
     */
    private var eventLinkArmed = false
    private var eventLinkMarkers = 0
    private var eventCsmStarted = false

    /**
     * раунд переговоров по каналу управления (добавлен после завершения разработки Honda HFT 20 октября 2026 г.).
     *
     * Маркер автомобиля       поступает из **канала событий**, но его стек iAP2 поступает только из **канала управления** (RTSP
     * POST / iAPSendMessage команды) читает отправленные нами байты - исходное заключение проекта Frida читается
     * "При записи в канал событий машина его вообще не читает." r5/r6 Переместите станцию отправления на канал
     * событий и подключите поезд и машину к одному.
     * FF5A не отвечает на пакеты синхронизации и повторно отправляет маркеры с частотой 1 Гц до рассвета. Это именно то, что было упущено.
     *
     * теперь возвращается на исходящий канал управления: [armMib3] / [restartControlNegotiation] отправляется один раз за раунд.
     * «маркер + таблица сеансов этой таблицы», внутренний движок выполняет повторную отправку со скоростью 500 мс/1
     * с; [CONTROL_SYN_ROUND_MILLIS]
     * переходит в следующий раунд без прогресса, вплоть до раундов [MAX_CONTROL_SYN_ROUNDS], а затем останавливается на ПЕРЕГОВОРАХ.
     * молча ждет (больше никакого управления, чтобы не запутать машинный стек).
     */
    private var controlSynRounds = 0
    private var controlSynRoundStartedAtMs = 0L
    private var controlSynGaveUp = false

    /** Подсчет необработанных сообщений сеанса управления (точка наблюдения для устранения неполадок медиа-ключа, см. [maybeRespondControlCsmLocked]). */
    private var unknownCsmIds = 0

    /** Последний записанный статус связи (в журнале пары отображается только один переход состояний). */
    private var lastLinkState = Iap2LinkEngine.State.IDLE

    // ---- Статус автомобильного HID медиа-ключа (0x6802) ----
    /** Коллекция «Использование», нажатая в последнем сообщении (используется для определения «новой печати», автоматически очищается после отпускания). */
    private var lastHidUsages: Set<Int> = emptySet()

    /** Время начала текущей серии нажатий клавиш (непрерывное нажатие разрешено только в том случае, если одно и то же нажатие клавиши превышает [HID_HOLD_REPEAT_MILLIS]). */
    private var hidHoldStartedAtMs = 0L

    /** Количество напечатанных кадров с подробными сведениями о параметрах (печатаются только первые несколько кадров, а затем при необходимости печатаются действия). */
    private var hidReportFrames = 0

    // ---- MIB3 Tunnel MFi Challenge (постоянная память для каждого автомобиля; **независимая память** с
    // подключением Bluetooth — один и тот же автомобиль)

    // Серверы аутентификации двух ссылок могут быть разными. Измеренное распознавание Bluetooth MMI 3315 составляет
    // 32B, а туннель Frida - 20B) ----

    private var mfiIdentity: String? = null
    private var mfiMemory = MfiLengthMemory.Entry()
    private var mfiOrder: List<Int> = emptyList()
    private var mfiPosition = -1
    private var mfiCurrentLength = 0
    private var mfiWindowMillis = 0L
    private var mfiChallengeSentAtMs = 0L
    private var mfiFinished = false

    init {
        engine.onEaData = { bytes -> handleEaData(bytes) }
        engine.onSynchronizationEchoSent = { pendingEchoFlush = true }
        Thread(
            {
                while (alive()) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    synchronized(lock) {
                        engine.advanceTime(System.nanoTime() / 1_000_000L)
                        drainLocked()
                    }
                }
            },
            "iap-link-pacer",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Запуск формы события: Сервер находится в режиме ожидания (принимает маркер и возвращает маркер, ожидая SYN автомобиля). */
    fun begin() {
        synchronized(lock) {
            if (started) return
            started = true
            engine.enterNegotiating()
        }
    }

    /**
     * MIB3 на охрану (вызывается после успешной настройки экрана SETUP и только один раз): активная отправка DETECT
     * на **канал управления**.
     * должен дождаться завершения SETUP перед отправкой - ранняя отправка будет сброшена во время обмена SETUP (вывод Frida); после этого
     * Все маркеры автомобиля       обнаруживаются и отправляются повторно. Реальная машина (MIB3) возвращает SYN
     * один раз и не будет отправлять повторно.
     *
     * Здесь обратите внимание: **отправляются только пустые маркеры**: время MIB3 таково: «он возвращает SYN → мы возвращаем SYN|ACK»,
     * Предварительное фотографирование таблицы сеансов нарушит работу ее конечного автомата (см. [Iap2LinkEngine.startResponder]
     * Фактические записи измерений      ). Если машина автомобиля неоднократно отправляет маркеры, как Honda HFT, она
     * [startControlRoundLocked] Обновите систему до «маркер + таблица сеансов этой таблицы» и повторите попытку.
     */
    fun armMib3() {
        synchronized(lock) {
            begin()
            if (mib3Armed) return
            mib3Armed = true
            sendControlIap(Iap2LinkEngine.MARKER)
            log("IAPLINK: отправлено обнаружение MIB3 (канал управления iAPSendMessage, поколение=$generation) - ожидание автомобиля")
        }
    }

    /**
     * явно объявляет генерацию связи машина-машина (может выполняться до и после постановки на охрану,
     * идемпотентно). Межпоколенческая модернизация используется только для регистрации и будущей отправки.
     * Стратегия согласования маркера       унифицирована для всех поколений (вторичное решение 23 октября 2026 г.,
     * см. документ [generation]).
     */
    fun setGeneration(generation: ReceiverProfile.LinkGeneration) {
        synchronized(lock) {
            if (this.generation == generation) return
            this.generation = generation
            log("IAPLINK: генерация ссылок → $generation")
        }
    }

    /**
     * Для отладки/специального режима: принудительно откройте раунд согласования канала управления (обычный путь
     * запускается маркером транспортное средство-машина).
     * После вызова       последующие маркеры будут обрабатываться в состоянии согласования.
     */
    fun forceControlNegotiation(reason: String) {
        synchronized(lock) {
            if (tunnelWriter() != null) return
            eventLinkArmed = true
            startControlRoundLocked(reason)
        }
    }

    /**
     * Обратный вызов завершения сеанса управления MIB3 (запускается один раз при выдаче аутентификации + пакетного
     * сигнала): хост должен нажать
     * AADisplay r17 подтвердил синхронизацию, переустановил режимы, Изменил декларацию ресурсов (+1,5 с первого выпуска,
     * +4/+12/+30 с (повторяется). **082446 Динцзи**: Переговоры/MFi/подписка прошли успешно, но только во время сеанса.
     * создал ресурс, который был объявлен один раз на ранней стадии → машина исправна, но никогда не переключается
     * на экран CarPlay — второй элемент управления
     * Сеанс       сбрасывает арбитраж ресурсов автомобиля и машины. Старый оператор недействителен и ожидает нового.
     * Будьте осторожны и не делайте заявлений во время выполнения 0xAA00/0xAA05 (кража канала управления с помощью iAP POST убьет Audi,
     * AADisplay (исходный комментарий), вам необходимо дождаться завершения пакета.
     */
    var onControlSessionComplete: (() -> Unit)? = null

    /**
     * запускает раунд согласования канала управления: маркер + пакет локальной синхронизации (сеанс 11.10.12),
     * механизм обновления повторно отправляет такт.
     */
    private fun startControlRoundLocked(reason: String) {
        val now = nowMillis()
        controlSynRounds++
        controlSynRoundStartedAtMs = now
        engine.initiate(now)
        log(
            "IAPLINK: управляющий SYN (раунд $controlSynRounds/$MAX_CONTROL_SYN_ROUNDS, $reason)" +
                "——Пакет синхронизации ожидающего устройства (FF5A)",
        )
        drainLocked()
    }

    /** Запись байта связи транспортное средство-машина (данные канала событий iAPSendMessage). */
    fun onCarBytes(bytes: ByteArray) {
        synchronized(lock) {
            // Переключиться на семантику согласования «сначала эхо», когда туннель существует (жесткое ожидание
            // однорангового механизма diPlay)

            engine.echoFirst = tunnelWriter() != null
            val isMarker =
                bytes.size == 6 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0x55.toByte()
            if (isMarker && tunnelWriter() == null) {
                if (mib3Armed) {
                    mib3MarkerIgnores++
                    val now = nowMillis()
                    when (generation) {
                        // 23 октября 2026 г. Решение: **Все поколения должны завершить согласование ссылок**. Нажал «Ауди Игнор»
                        // "маркер" отправлен (0.1.16/0.1.17), фальсифицирован журналом автовладельца 0.1.17 - маркер машины машины
                        // При **бесконечной повторной передаче** с частотой 1 Гц (значительно превышающей «2–5 пауз после» в старом
                        // комментарии) ссылка никогда не придет.

                        // НОРМАЛЬНО, через 10,18с после ЗАПИСИ машина-машина удалила всю сессию (0,1,9 та же модель 10,17с).
                        // Завершение самих переговоров не имеет побочных эффектов (082446 полное здоровье), чего не хватает, так это
                        // modeChanged повторяется (см. onControlSessionComplete).
                        ReceiverProfile.LinkGeneration.CLASSIC_MIB3,
                        ReceiverProfile.LinkGeneration.EVENT_LINK,
                        -> {
                            if (!eventLinkArmed && mib3MarkerIgnores >= EVENT_LINK_MARKER_THRESHOLD) {
                                eventLinkArmed = true
                                log(
                                    "IAPLINK: транспортное средство и машина постоянно отправляют маркер SYN ×$mib3MarkerIgnores." +
                                        "(Ожидается наша таблица сеансов) → Раунд переговоров о перевыпуске канала управления",
                                )
                                startControlRoundLocked("Перевыпуск маркеров автомобилей и машин")
                            } else if (eventLinkArmed) {
                                eventLinkMarkers++
                                if (engine.state != Iap2LinkEngine.State.NORMAL && !controlSynGaveUp) {
                                    val stale = now - controlSynRoundStartedAtMs >= CONTROL_SYN_ROUND_MILLIS
                                    when {
                                        !stale -> Unit
                                        controlSynRounds >= MAX_CONTROL_SYN_ROUNDS -> {
                                            controlSynGaveUp = true
                                            engine.stopNegotiationRetries()
                                            log(
                                                "IAPLINK: Круговое согласование канала управления " +
                                                    "$MAX_CONTROL_SYN_ROUNDS нет ответа – остановить автоматическую повторную передачу," +
                                                    "Ссылка останавливается на ${engine.state} (автомобиль автоматически " +
                                                    "продолжит движение, если отправит 0x1D01)",
                                            )
                                        }

                                        else -> startControlRoundLocked("Нет ответа в последнем раунде ${CONTROL_SYN_ROUND_MILLIS}ms.")
                                    }
                                }
                                // Поток движка также будет отправлен повторно, здесь отправляемые байты выбрасываются немедленно.
                                drainLocked()
                                if (eventLinkMarkers <= 3 || eventLinkMarkers % 20 == 0) {
                                    log(
                                        "IAPLINK: статус связи маркера автомобиля-машины (#$eventLinkMarkers) = ${engine.state}." +
                                            "(Канал управления вокруг $controlSynRounds)",
                                    )
                                }
                                return
                            } else if (mib3MarkerIgnores <= 3) {
                                log(
                                    "IAPLINK: Маркер автомобиля и машины (#$mib3MarkerIgnores) — согласуйте перевыпуск " +
                                        "после сбора $EVENT_LINK_MARKER_THRESHOLD.",
                                )
                            }
                        }
                    }
                } else {
                    // Перед постановкой на охрану (экран НАСТРОЙКА не завершен): Нет ответа – реальная машина не обнаружится до НАСТРОЙКИ.
                    // Эхо по каналу событий не имеет смысла для MIB3 (он распознает только канал управления); старый приемник
                    // открытого текста

                    // Не переходить по этой семантике ссылки
                    if (mib3DeferredLogs < 2) {
                        mib3DeferredLogs++
                        log(
                            "IAPLINK: Маркер автомобиля перед НАСТРОЙКОЙ временно не отвечает (активно обнаруживает " +
                                "после постановки на охрану)",
                        )
                    }
                    return
                }
            }
            engine.feed(bytes, System.nanoTime() / 1_000_000L)
            drainLocked()
        }
    }

    /**
     * Исходящие байты ссылки: Туннель TCP (DiPlay) → Канал управления POST/команда (MIB3/Honda HFT) →
     * Канал событий       (старая текстовая форма).
     *
     * **Разделение труда фиксировано**: байты связи машина-машина передаются из канала событий `iAPSendMessage` **Входящие**, наши
     * Исходящие байты       могут быть записаны только в канал управления - транспортное средство не может прочитать
     * их при записи в канал событий (заключение Фриды, 20 октября 2026 г.)
     * Honda HFT снова подверглась испытаниям: после выезда со станции и перехода на канал событий машина не вернула
     * ни одного FF5A в течение 20 минут).
     */
    private fun sendLinkBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> tunnel.writeIapBytes(bytes)
            eventLinkArmed -> sendControlIap(bytes)
            mib3Armed -> sendControlIap(bytes)
            else -> sendEventIap(bytes)
        }
    }

    /**
     * Исходящий байтовый диск механизма : SYN|ACK разрешено запускать только после того, как он фактически доставлен
     * в соответствующий канал——
     * Если одноранговый механизм получит голый ACK до того, как увидит SYN|ACK, он сразу же отключится (фактический тест DiPlay).
     */
    private fun flushOutputLocked() {
        val out = engine.takeOutput()
        if (out.isEmpty()) return
        val tunnel = tunnelWriter()
        when {
            tunnel != null -> {
                val ok = tunnel.writeIapBytes(out)
                if (ok && pendingEchoFlush) {
                    pendingEchoFlush = false
                    if (!tunnelEchoDelivered) {
                        tunnelEchoDelivered = true
                        log("IAPLINK: ✓ В туннель записано синхронное эхо (согласование канала завершено, конечный CSM готов к отправке)")
                    }
                }
            }

            eventLinkArmed -> {
                // Входящий канал событий/исходящий канал управления: ответы, пакеты синхронизации и CSM записываются в канал управления.
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    log("IAPLINK: ✓ SYN|ACK доставлен в канал управления – согласование канала завершено, сеанс управления запущен.")
                }
            }

            mib3Armed -> {
                sendControlIap(out)
                if (pendingEchoFlush) {
                    pendingEchoFlush = false
                    mib3SynAckQueued = true
                    log("IAPLINK: ✓ MIB3 SYN|ACK доставляет канал управления — дождитесь подтверждения соединения перед запуском CSM.")
                }
            }

            else -> {
                sendEventIap(out)
                pendingEchoFlush = false
            }
        }
    }

    private fun drainLocked() {
        while (true) {
            when (val event = engine.pollEvent()) {
                null -> break
                is Iap2LinkEngine.Event.Control ->
                    handleFrameLocked(event.bytes)

                is Iap2LinkEngine.Event.Dead -> {
                    log("IAPLINK: Виртуальное соединение прекращено – ${event.reason ?: "EOF"}")
                    return
                }
            }
        }
        noteLinkStateLocked()
        // Сначала очистите (сначала эхо должно быть отправлено партнеру), а затем запустите CSM; переключение тайм-аута
        // может привести к появлению новых кадров в очереди и их окончательному сбросу.

        flushOutputLocked()
        maybeStartControlCsmLocked()
        checkMfiTimeoutLocked()
        flushOutputLocked()
    }

    /** Миграция статуса канала появляется в журнале только один раз (IDLE→NEGOTIATING→NORMAL — это водораздел, который определяет, открыт ли ключевой путь). */
    private fun noteLinkStateLocked() {
        val state = engine.state
        if (state == lastLinkState) return
        lastLinkState = state
        log("IAPLINK: Статус соединения → $state (только NORMAL может получить медиа-ключ 0x6800/0x6802 автомобиля)")
    }

    /** MFi вызовет тихий таймаут: запишите его в память автомобиля и повторите попытку, переключившись на следующую передачу (бить поток приводов каждые 200мс). */
    private fun checkMfiTimeoutLocked() {
        val identity = mfiIdentity ?: return
        if (mfiFinished || mfiCurrentLength == 0) return
        if (nowMillis() - mfiChallengeSentAtMs < mfiWindowMillis) return
        val failed = mfiCurrentLength
        MfiLengthMemory.recordSilent(mfiContext, identity, failed)
        log(
            "IAPLINK: Tunnel MFi ${failed}B Silent ${mfiWindowMillis}ms — сохраните в памяти, переключитесь на следующую " +
                "передачу и повторите попытку.",
        )
        mfiCurrentLength = 0
        startNextMfiChallengeLocked()
    }

    /**
     * Задача MFi следующего уровня (порядок = перестановка в соответствии с памятью автомобиля, базовый приоритет
     * холодного запуска 20B - реальная стоимость машины Frida).
     * имеет ту же семантику, что и путь Bluetooth при отсутствии реакции на всех передачах: пропускаем проверку и
     * сразу 0xAA05, что и будет определяться последующим поведением автомобиля.
     */
    private fun startNextMfiChallengeLocked() {
        val identity = mfiIdentity ?: return
        if (mfiOrder.isEmpty()) {
            mfiMemory = MfiLengthMemory.load(mfiContext, identity)
            mfiOrder = MfiLengthMemory.orderAttempts(TUNNEL_MFI_BASELINE, mfiMemory)
            if (!mfiMemory.isEmpty) {
                log(
                    "IAPLINK: Память автомобильного пресса Tunnel MFi: ${MfiLengthMemory.describe(mfiMemory)}" +
                        " → Порядок этого раунда ${mfiOrder.joinToString("/")}B",
                )
            }
        }
        mfiPosition += 1
        if (mfiPosition >= mfiOrder.size) {
            log(
                "IAPLINK: На все запросы туннеля MFi нет ответа — пропустите проверку и перейдите прямо к 0xaa05 " +
                    "(определяется по последующему поведению автомобиля)",
            )
            mfiFinished = true
            mib3AuthStage = 4
            sendCsmLocked(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
            sendMib3BurstLocked()
            return
        }
        mfiCurrentLength = mfiOrder[mfiPosition]
        val base = TUNNEL_MFI_WINDOWS[mfiCurrentLength] ?: TUNNEL_MFI_SILENT_WINDOW_MS
        mfiWindowMillis =
            if (mfiCurrentLength in mfiMemory.silent && mfiCurrentLength != mfiMemory.succeeded) {
                minOf(base, TUNNEL_MFI_SILENT_WINDOW_MS)
            } else {
                base
            }
        mfiChallengeSentAtMs = nowMillis()
        val challenge = ByteArray(mfiCurrentLength).also { SecureRandom().nextBytes(it) }
        sendCsmLocked(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
        mib3AuthStage = 3
        log("IAPLINK: туннель tx=0xaa02 Challenge=${mfiCurrentLength}B (время ${mfiPosition + 1}, окно ${mfiWindowMillis}ms)")
    }

    /**
     * Запуск сеанса управления  (две формы туннеля/MIB3): после того, как канал станет НОРМАЛЬНЫМ и будет доставлен SYN|ACK,
     * 0x1D00 Начальная идентификация. Если этот шаг пропущен, вся ссылка будет удалена по истечении 5-минутного окна
     * контроллера противоположного конца.
     * (DiPlay 11 октября 2026 г. измерил основную причину; MIB3 имеет гораздо более короткое окно демонтажа, ~ 10 с).
     */
    private fun maybeStartControlCsmLocked() {
        if (engine.state != Iap2LinkEngine.State.NORMAL) return
        val onTunnel = tunnelWriter() != null
        if (onTunnel) {
            if (tunnelCsmStarted || !tunnelEchoDelivered) return
            tunnelCsmStarted = true
        } else if (eventLinkArmed) {
            // Режим инициатора «транспортное средство-машина»: используйте канал управления при исходящем сообщении, если
            // принята таблица одноранговых сеансов, он может начаться.

            if (eventCsmStarted || engine.peerSynchronization == null) return
            eventCsmStarted = true
        } else {
            if (!mib3Armed || mib3CsmStarted || !mib3SynAckQueued) return
            mib3CsmStarted = true
        }
        sendCsmLocked(Iap2MessageIds.START_IDENTIFICATION)
    }

    /** Трансмиссия CSM на звене (входит в задел двигателя, и промывается двигателем согласно окну коробки передач). */
    private fun sendCsmLocked(
        messageId: Int,
        body: ByteArray = ByteArray(0),
    ) {
        val frame = Iap2Csm.encode(messageId, body)
        val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L)
        log(
            "IAPLINK: туннельная передача 0x${messageId.toString(16)}" +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B ok=$ok",
        )
    }

    /** Непосредственная доставка полного кадра CSM (побайтовые константы, записанные Frida, без перекодирования). */
    private fun sendRawCsmLocked(frame: ByteArray) {
        val ok = engine.sendControl(frame, System.nanoTime() / 1_000_000L)
        // Структура кадра CSM: FF 5A [len:2] [msgId:2] …——Номер сообщения находится в формате [4..5], [2..3] — длина полезной нагрузки.
        // Я по ошибке взял [2..3], а в логе 0xFFFA было написано как 0x32, 0xFFF0 как 0x0E, а 0x4158 как 0x64.
        // (значение длины), во время расследования легко оказаться предвзятым (фактически один раз ввели в заблуждение 23 октября 2026 г.).
        val messageId = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        log(
            "IAPLINK: туннельная передача 0x${messageId.toString(16)}" +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B raw ok=$ok",
        )
    }

    /**
     * ответ сеанса управления (туннель и идентификация общего ресурса MIB3/скелет MFi, MIB3 также имеет пакетные ответы и ответы подписки):
     * 0x1D01 → 0x1D02 + 0xAA00; 0xAA06 → 0xAA00;
     * 0xAA01 → 0xAA02 вызов (MIB3 20B / DiPlay 32B); 0xAA03 → 0xAA05 (случайный пакет MIB3).
     * Подписка       (только MIB3): 0x4157→0x4158, 0x4154→0x4155, 0x4170→0x4171, 0x5000→0x5001 пуста.
     * 0xEA02 (requestAppLaunch)/0xA101 (VehicleStatus)/0xFFFB (выборка позиционирования PASCD ~1 Гц)
     * связывает только ACK (автоматический двигатель) - **0xFFFB пропускает ACK, и машина автомобиля также закрывает
     * канал управления через ~10 с** (вывод Фриды).
     */
    private fun maybeRespondControlCsmLocked(frame: Iap2Csm.Frame) {
        val onTunnel = tunnelWriter() != null
        // В режиме инициатора «транспортное средство-машина» повторно используется идентификатор MIB3/скелет MFi
        // (исходящий также использует канал управления)

        val mib3 = (mib3Armed || eventLinkArmed) && !onTunnel
        if (!onTunnel && !mib3) return
        val ids = Iap2MessageIds
        when (frame.messageId) {
            ids.IDENTIFICATION_INFORMATION -> {
                val identified = if (onTunnel) tunnelIdentified else mib3Identified
                if (identified) return
                if (onTunnel) tunnelIdentified = true else mib3Identified = true
                val params =
                    Iap2Csm
                        .parseParams(frame.body)
                        .associateBy { it.id }
                log(
                    "IAPLINK: идентификатор получателя туннеля rx 0x1d01" +
                        "name=${params[0]?.value?.decodeToString()?.trimEnd('\u0000')} " +
                        "model=${params[1]?.value?.decodeToString()?.trimEnd('\u0000')}",
                )
                // Ссылка 6/7 = Список сообщений, которые, как заявляет автомобиль, «он может отправлять/могут получать».
                // Устранение неполадок медиа-ключа основано на этих двух строках:

                // Если 0x6800/0x6802 нет в списке отправки, это означает, что этот автомобиль вообще не использует iAP2 HID для
                // отправки кнопок.

                // Не нужно тратить время на этот путь (вместо этого проверьте сторону Bluetooth/AVRCP).
                params[6]?.value?.let { raw ->
                    val sends = u16List(raw)
                    log(
                        "IAPLINK: Список поставки транспортных средств и машин (${sends.size})=" +
                            sends.joinToString(" ") { "0x%04x".format(it) } +
                            " Содержит HID0x6800=${sends.contains(Iap2MessageIds.START_HID)}" +
                            " Содержит 0x6802=${sends.contains(Iap2MessageIds.ACCESSORY_HID_REPORT)}",
                    )
                }
                params[7]?.value?.let { raw ->
                    val receives = u16List(raw)
                    log(
                        "IAPLINK: Список дебиторской задолженности по транспортным средствам и машинам (${receives.size})=" +
                            receives.joinToString(" ") { "0x%04x".format(it) },
                    )
                }
                if (mib3) {
                    // Ключ памяти = идентификатор автомобиля + «туннель»: не загрязняет память канала Bluetooth.
                    mfiIdentity = mfiIdentityOf(params) + " tunnel"
                }
                sendCsmLocked(ids.IDENTIFICATION_ACCEPTED)
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 1
            }

            MFI_AUTH_SERIAL -> {
                if (!mib3 || mib3AuthStage != 1) return
                sendCsmLocked(ids.MFI_REQUEST_CERTIFICATE)
                mib3AuthStage = 2
                log("IAPLINK: MIB3 0xaa06 (серийный номер аутентификации) → повторно отправить 0xaa00")
            }

            ids.MFI_AUTHENTICATION_FAILED -> {
                if (!mib3 || mfiFinished) return
                mfiIdentity?.let { MfiLengthMemory.recordRejected(mfiContext, it, mfiCurrentLength) }
                log("IAPLINK: Tunnel MFi ${mfiCurrentLength}B отклонен (0xaa04) – сохранить в памяти, перейти на следующую передачу")
                mfiCurrentLength = 0
                startNextMfiChallengeLocked()
            }

            ids.MFI_CERTIFICATE -> {
                if (mib3) {
                    // Длина определяется памятью машины (предпочтителен холодный запуск 20 Б, реальное значение машины Frida;
                    // подробности см. в startNextMfiChallengeLocked)

                    startNextMfiChallengeLocked()
                } else {
                    // Идентификатор DiPlay CARLINKIT принимает только точные 32 Б (если длина не совпадает, ссылка будет разорвана
                    // напрямую).

                    val challenge =
                        ByteArray(32).also { SecureRandom().nextBytes(it) }
                    sendCsmLocked(ids.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
                    mib3AuthStage = 3
                }
            }

            ids.MFI_RESPONSE -> {
                if (mib3) {
                    mfiIdentity?.let { MfiLengthMemory.recordSuccess(mfiContext, it, mfiCurrentLength) }
                    log("IAPLINK: туннель rx=0xaa03, подпись ${frame.body.size}B (вызов ${mfiCurrentLength}B успешен, запомнен)")
                    mfiCurrentLength = 0
                    mfiFinished = true
                }
                sendCsmLocked(ids.MFI_AUTHENTICATION_SUCCEEDED)
                if (mib3) {
                    mib3AuthStage = 4
                    sendMib3BurstLocked()
                } else {
                    log("IAPLINK: туннель MFi завершен — ожидание подписки узла (затем пометьте туннель как готовый)")
                }
            }

            START_COMMUNICATIONS_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(MIB3_COMMUNICATIONS_UPDATE_IDLE)
                log("IAPLINK: MIB3 0x4157 → 0x4158 (пустое состояние)")
            }

            START_CALL_STATE_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    CALL_STATE_UPDATE,
                    Iap2Csm.param(0x0002, byteArrayOf(0)),
                )
                log("IAPLINK: MIB3 0x4154 → 0x4155 (без вызова)")
            }

            START_LIST_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    LIST_UPDATE,
                    Iap2Csm.param(0x0000, byteArrayOf(0)) +
                        Iap2Csm.param(0x0002, byteArrayOf(0, 0)) +
                        Iap2Csm.param(0x0005, byteArrayOf(0)) +
                        Iap2Csm.param(0x0007, byteArrayOf(0, 0)),
                )
                log("IAPLINK: MIB3 0x4170 → 0x4171 (пустой список)")
            }

            Iap2MessageIds.START_NOW_PLAYING_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(MIB3_NOW_PLAYING_UPDATE_IDLE)
                // Тексты песен на приборной панели/путь к данным медиа-карты (0x5001 + крышка для передачи файлов) будут подключены позже
                // MediaSessionsState — ответ о пустом состоянии является необходимым условием выживания MIB3, поэтому эта версия
                // будет удалена первой.

                log("IAPLINK: MIB3 0x5000 → 0x5001 (пусто) — медиаданные приборной панели, которые необходимо получить")
            }

            ids.START_HID -> {
                // Транспортное средство заявляет: «Я хочу начать отправку отчетов HID», обычно с дескриптором (определяет
                // семантику байтов отчета).

                val params = Iap2Csm.parseParams(frame.body)
                val descriptor = params.map { it.value }.firstOrNull { it.size > 8 }
                if (descriptor != null) {
                    hidDescriptor = descriptor
                    log(
                        "IAPLINK: Автомобильная машина 0x6800 Дескриптор StartHID ${descriptor.size}B hex=" +
                            descriptor.joinToString(" ") { "%02x".format(it) }.take(120),
                    )
                } else {
                    log("IAPLINK: автомобильная машина 0x6800 StartHID (без дескриптора) параметр = ${params.map { it.id }}")
                }
            }

            ids.ACCESSORY_HID_REPORT -> {
                // Мультимедийные клавиши на руле/панели.
                //
                // 20.10.2026 Honda HFT, размеры рамы (11B):
                //   00 06 00 00 00 03 | 00 05 00 01 04
                //   param id=0 значение 00 03 (тип/описание отчета, константа) ← ранее рассматривалось как значение отчета
                //   param id=1 значение 04 (реальное HID-сообщение)
                // Старый код принимает «первые 1–8 байтов параметра» → всегда получает 00 03, и в растровом изображении не
                // устанавливается ни один бит.

                // Таким образом, журнал продолжал показывать `usage=Нет keyCode=0`, но при нажатии кнопки никаких действий
                // предпринято не было.

                val params = Iap2Csm.parseParams(frame.body)
                val report = hidReportFromParams(params)
                // Навигационные устройства (ручка iDrive/джойстик с четырьмя направлениями): колесо/шляпа на странице «Общий рабочий стол»,
                // Используя внедрение DPAD (требуется фокусировка виртуального экрана), его нельзя распространять глобально в
                // качестве медиа-ключа.

                if (report != null && HidNavigation.isNavigationDevice(hidDescriptor)) {
                    val navKeys = HidNavigation.handle("iap2-hid", hidDescriptor, report)
                    if (navKeys.isNotEmpty()) {
                        log("IAPLINK: ручка/клавиша навигации" + navKeys.joinToString("+") { HidNavigation.nameOf(it) })
                        navKeys.forEach { onNavKey?.invoke(it) }
                    }
                } else {
                    val keyMap = hidDescriptor?.let(ConsumerKeys::parseDescriptor)
                    val usages =
                        when {
                            report == null -> emptyList()
                            keyMap != null -> keyMap.usagesFor(report)
                            // Нет дескриптора: сокращен до «однобайтового идентификатора использования».
                            else -> listOf(report[0].toInt() and 0xFF).filter { it != 0 }
                        }
                    if (hidReportFrames < HID_REPORT_VERBOSE_FRAMES) {
                        hidReportFrames++
                        log(
                            "IAPLINK: Автомобильная машина 0x6802 params=" +
                                params.joinToString(",") { "${it.id}=" + it.value.joinToString("") { b -> "%02x".format(b) } } +
                                " отчет =" + (report?.joinToString(" ") { "%02x".format(it) } ?: "пустой") +
                                " растровое изображение=" + (keyMap?.isArray?.let { if (it) "Array" else "Variable" } ?: "Нет дескриптора"),
                        )
                    }
                    fireHidKeysLocked(usages)
                }
            }

            else -> {
                // Необработанные сообщения тоже оставляют следы: если в автомобиле используется нетрадиционный идентификатор для
                // выдачи медиа-ключей/кнопок, здесь можно сразу

                // Вы можете увидеть соответствующий номер сообщения в журнале (ключевая точка наблюдения для устранения
                // неполадок кнопки переключения песни).

                // С полным шестнадцатеричным кодом: семейства BMW iD9 0xad00/0xad02/0xad03 нет ни в общедоступном списке Apple.
                // Нет никаких ссылок на именование реализации — вы можете полагаться только на структуру тела сообщения, чтобы
                // сделать вывод, является ли это каналом ввода (сенсорный/HID).

                unknownCsmIds++
                if (unknownCsmIds <= 20) {
                    log(
                        "IAPLINK: необработанный CSM 0x${frame.messageId.toString(16)}" +
                            "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=" +
                            frame.body.joinToString(" ") { "%02x".format(it) },
                    )
                }
            }
        }
    }

    /**
     * преобразует пакет «Использование нажато на этот раз» во внедрение мультимедийных клавиш Android.
     *
     * HID-отчет автомобиля       — **статус**: нажатие и удержание будет непрерывно передавать одно и то же
     * растровое изображение с частотой ~10 Гц, а отпустив кнопку, можно нажать 0x00.
     * Таким образом,       срабатывает только один раз (по краю), когда «вновь появляется», в противном случае
     * долгое нажатие приведет к пропуску десятков песен;
     * Перезапуск       разрешен только в том случае, если одна и та же кнопка нажата непрерывно более чем [HID_HOLD_REPEAT_MILLIS].
     * сохраняет ощущение «непрерывного переключения песен», нажав и удерживая рулевое колесо.
     */
    private fun fireHidKeysLocked(usages: List<Int>) {
        val now = nowMillis()
        if (usages.isEmpty()) {
            lastHidUsages = emptySet()
            hidHoldStartedAtMs = 0L
            return
        }
        val fresh = usages.filter { it !in lastHidUsages }
        val repeat =
            fresh.isEmpty() &&
                hidHoldStartedAtMs > 0 &&
                now - hidHoldStartedAtMs >= HID_HOLD_REPEAT_MILLIS
        lastHidUsages = usages.toSet()
        if (fresh.isNotEmpty()) hidHoldStartedAtMs = now
        if (fresh.isEmpty() && !repeat) return
        if (repeat) hidHoldStartedAtMs = now
        val firing = if (fresh.isNotEmpty()) fresh else usages
        for (usage in firing) {
            val keyCode = ConsumerKeys.keyCodeFor(usage)
            log(
                "IAPLINK: автомобильный мультимедийный ключ ${ConsumerKeys.nameOf(usage)}(0x%02x) → keyCode=".format(usage) +
                    (if (keyCode != 0) "$keyCode был введен" else "Нет соответствующего ключа Android (игнорируется)"),
            )
            if (keyCode != 0) onMediaKey?.invoke(keyCode)
        }
    }

    /**
     * Всплеск после  0xAA05 (Фрида: настоящая машина отправит эту партию сразу после успешной сертификации, до
     * запроса на подписку автомашины).
     * 0xFFFA/0xFFF0/0x4158/0x5001 использует побайтовые константы - расположение полей существует только при захвате
     * пакетов на реальной машине.
     * Перекодирование       чрезвычайно подвержено псевдонимам (сравните константы Frida для AADisplay WifiIap2Link).
     */
    private fun sendMib3BurstLocked() {
        val sessionId =
            mib3WirelessSessionId ?: UUID.randomUUID().toString().uppercase().also {
                mib3WirelessSessionId = it
            }
        sendCsmLocked(
            WIRELESS_SESSION_ID,
            Iap2Csm.param(0, sessionId.toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(AUX_STATUS_NOTIFICATION, Iap2Csm.param(0x0006, ByteArray(0)))
        sendCsmLocked(
            DEVICE_LANGUAGE,
            Iap2Csm.param(0, "zh".toByteArray(Charsets.UTF_8)),
        )
        sendCsmLocked(DEVICE_TIME, mib3DeviceTimeParams())
        sendCsmLocked(
            DEVICE_INFORMATION_UPDATE,
            Iap2Csm.param(0, "iPhone".toByteArray(Charsets.UTF_8)),
        )
        sendRawCsmLocked(MIB3_START_LOCATION_INFORMATION)
        sendRawCsmLocked(MIB3_GPRMC_DATA_STATUS)
        log("IAPLINK: отправлен пакет MIB3 (идентификатор сеанса $sessionId) – ожидание подписки автомобиля")
        if (!mib3ControlSessionNotified) {
            mib3ControlSessionNotified = true
            onControlSessionComplete?.invoke()
        }
    }

    /** 0x4E0B: u64 секунды + s16 смещение часового пояса (минуты) + u8 летнее время. */
    private fun mib3DeviceTimeParams(): ByteArray {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetMin = zone.getOffset(now) / 60_000
        val dst = if (zone.inDaylightTime(java.util.Date(now))) 1 else 0
        val seconds = ByteArray(8)
        for (i in 0 until 8) {
            seconds[i] = (now / 1000L ushr ((7 - i) * 8)).toByte()
        }
        val tz =
            byteArrayOf(
                (offsetMin shr 8).toByte(),
                offsetMin.toByte(),
            )
        return Iap2Csm.param(0, seconds) + Iap2Csm.param(1, tz) + Iap2Csm.param(2, byteArrayOf(dst.toByte()))
    }

    /** Массив u16 с обратным порядком байтов (список идентификаторов сообщений в идентификационной информации). */
    private fun u16List(bytes: ByteArray): List<Int> =
        (0 until bytes.size / 2).map {
            ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF)
        }

    /** 0x1D01 Параметр идентификации (имя/модель/производитель) → материал ключа памяти (NUL для удаления, пустое место для пропуска). */
    private fun mfiIdentityOf(params: Map<Int, Iap2Csm.Param>): String =
        listOf(0, 1, 2)
            .mapNotNull { id ->
                params[id]
                    ?.value
                    ?.decodeToString()
                    ?.trimEnd('\u0000')
                    ?.takeIf { it.isNotBlank() }
            }.joinToString(" ")

    private fun handleFrameLocked(bytes: ByteArray) {
        frameCount++
        val frames = parser.offer(bytes)
        for (frame in frames) {
            val hex = frame.body.joinToString(" ") { "%02x".format(it) }
            if (frameCount <= 30 || frameCount % 100 == 0) {
                log(
                    "IAPLINK: rx 0x${frame.messageId.toString(16)} " +
                        "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=${hex.take(160)}",
                )
            }
            maybeRespondControlCsmLocked(frame)
        }
        if (frames.isEmpty() && frameCount <= 10) {
            log("IAPLINK: rx ${bytes.size}B (не CSM) hex=" + bytes.joinToString(" ") { "%02x".format(it) }.take(160))
        }
    }

    /** Обратный вызов механизма: кадр данных сеанса EA (номер 2) - высоковероятный носитель касания HID. */
    var onEaData: ((ByteArray) -> Unit)? = null

    /**
     * Обратный вызов механизма : мультимедийные ключи Android, анализируемые из отчета HID, созданного сеансом управления iAP2.
     *
     * Воспроизведение предыдущей и следующей песни на рулевом колесе/панели автомобиля в CarPlay `0x6802 AccessoryHIDReport`,
     * появится только после фактического установления соединения iAP2 (см. [eventLinkArmed]).
     */
    var onMediaKey: ((Int) -> Unit)? = null

    /** Клавиши навигации (ручка iDrive/четырехпозиционная): оставьте на уровне сеанса вставку виртуальных экранов (DPAD_*), а не глобальных мультимедийных клавиш. */
    var onNavKey: ((Int) -> Unit)? = null

    /** Дескриптор HID, заданный транспортным средством в 0x6800 StartHID (используется для анализа отчетов; ноль = не указано). */
    private var hidDescriptor: ByteArray? = null

    fun handleEaData(bytes: ByteArray) {
        eaCount++
        val hex = bytes.joinToString(" ") { "%02x".format(it) }
        if (eaCount <= 30 || eaCount % 100 == 0) {
            log("IAPLINK: данные советника #$eaCount ${bytes.size}B hex=${hex.take(200)}")
        }
        onEaData?.invoke(bytes)
    }

    companion object {
        /** Базовая линия холодного запуска туннеля MFi (реальная машина Frida 20B имеет приоритет; порядок будет переупорядочен в соответствии с памятью машины автомобиля). */
        val TUNNEL_MFI_BASELINE = listOf(20, 32, 16)
        val TUNNEL_MFI_WINDOWS = mapOf(20 to 6_000L, 32 to 3_000L, 16 to 3_000L)
        const val TUNNEL_MFI_SILENT_WINDOW_MS = 1_500L

        /**
         * Транспортное средство определяет, что «оно ожидает возврата маркера» по количеству маркеров SYN, которые оно
         * непрерывно отправляет по каналу событий.
         * Реальная машина           (MIB3) больше не будет отправлять повторно после того, как мы ее активно обнаружим;
         * Honda HFT непрерывно подвергалась испытаниям сотни раз.
         * Приняв           за 2, можно избежать повторной передачи обнаружения MIB3, не заставляя машину ждать слишком долго.
         */
        const val EVENT_LINK_MARKER_THRESHOLD = 2

        /**
         * Согласование канала управления: перезапуск следующего раунда после отсутствия прогресса в каждом раунде,
         * вплоть до нескольких раундов.
         * Маркер машины           — 1 Гц, раунд 4 секунды ⇒ каждый раунд может получить 4 возможности повторной
         * отправки; 5 раундов ≈ 20 секунд,
         * После           остановитесь и молча подождите (он все равно будет обработан нормально, если он действительно отправит 0x1D01).
         */
        const val CONTROL_SYN_ROUND_MILLIS = 4_000L
        const val MAX_CONTROL_SYN_ROUNDS = 5

        /**
         * 0x6802 Номер параметра сообщения AccessoryHIDReport.
         *
         * Фактическое измерение Honda HFT: параметр 0 = тип/описание отчета (`00 03`, никогда не менялось),
         * Параметр 1           = настоящее HID-сообщение (например, `04` = следующая песня). Получение неправильного
         * номера параметра означает, что все ключи декодируются как «Нет».
         */
        const val HID_REPORT_DATA_PARAM = 1

        /** Подробности параметров записываются только в первых нескольких кадрах (ключевые действия будут запускаться в каждом последующем кадре, и журнала достаточно). */
        const val HID_REPORT_VERBOSE_FRAMES = 8

        /** Как долго удерживать одну и ту же кнопку, прежде чем ее можно будет нажать снова (имитируя непрерывные длинные нажатия, никогда не повторяя короткие нажатия). */
        const val HID_HOLD_REPEAT_MILLIS = 1_500L

        /**
         * Список параметров  0x6802 → HID-сообщение.
         *
         * сначала принимает параметр [HID_REPORT_DATA_PARAM]; возвращает по умолчанию (для отдельных машин задается только один параметр)
         * «Последние 1–8 байтов параметра» — обратите внимание, что **нельзя брать первый**: параметр 0 Honda HFT всегда
         * `00 03` (тип отчета), при использовании в качестве значения сообщения все ключи будут интерпретироваться как «нет».
         */
        fun hidReportFromParams(params: List<Iap2Csm.Param>): ByteArray? =
            params.firstOrNull { it.id == HID_REPORT_DATA_PARAM }?.value?.takeIf { it.isNotEmpty() }
                ?: params.lastOrNull { it.value.isNotEmpty() && it.value.size <= 8 }?.value

        // ---- Номер сообщения MIB3 (официального названия нет/не включен в небольшой набор Iap2MessageIds) ----
        const val MFI_AUTH_SERIAL = 0xAA06
        const val WIRELESS_SESSION_ID = 0x4E0C
        const val DEVICE_TIME = 0x4E0B
        const val DEVICE_LANGUAGE = 0x4E0A
        const val DEVICE_INFORMATION_UPDATE = 0x4E09
        const val AUX_STATUS_NOTIFICATION = 0xA100
        const val START_COMMUNICATIONS_UPDATES = 0x4157
        const val CALL_STATE_UPDATE = 0x4155
        const val START_CALL_STATE_UPDATES = 0x4154
        const val START_LIST_UPDATES = 0x4170
        const val LIST_UPDATE = 0x4171

        /** ---- Ниже приведены побайтовые константы, записанные Фридой (настоящий iPhone MIB3 → Audi MMI) ---- */
        private val HEX_DIGITS = "0123456789abcdef"

        /** 0xFFFA StartLocationInformation. */
        internal val MIB3_START_LOCATION_INFORMATION: ByteArray =
            hex(
                "40400032fffa00080000000000140004000100088001000000fa" +
                    "0004000200088002000000fa000400040008800400000014",
            )

        /** 0xFFF0 GPRMCDataStatusValuesNotification. */
        internal val MIB3_GPRMC_DATA_STATUS: ByteArray =
            hex("4040000efff00004000000040001")

        /** 0x5001 NowPlayingUpdate Пустое состояние (нет мультимедиа/нет воспроизведения). */
        internal val MIB3_NOW_PLAYING_UPDATE_IDLE: ByteArray =
            hex(
                "404000555001002a00000005000100000800040000000000050006000005000c0000" +
                    "0500100000050012000005001a8000250001000500000000080001000000000005" +
                    "000500000500060000050007000005001000",
            )

        /** 0x4158 CommunicationsUpdate Пустое состояние (доступен заполнитель для сценария отсутствия SIM-карты или сигнала). */
        internal val MIB3_COMMUNICATIONS_UPDATE_IDLE: ByteArray =
            hex(
                "404000644158000500000100050001060005000200000e0004e697a053494de58da1" +
                    "00000500050100050006010005000700000500080000050009000005000a000005" +
                    "000b000005000c010005000d000005000e000005000f0000050010000005001100",
            )

        private fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                val hi = HEX_DIGITS.indexOf(clean[i * 2])
                val lo = HEX_DIGITS.indexOf(clean[i * 2 + 1])
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}
