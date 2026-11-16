package io.github.shaonialife.cardeck.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Беспроводной клиент iAP2 RFCOMM на стороне мобильного телефона (роль iPhone) **активно подключается к
 * автомобилю** — недавно добавлено на этапе реального автомобиля.
 *
 * истинное направление установления беспроводного соединения CarPlay (сравните с клиентским режимом
 * carplay-wifi-extractor, проверенным на реальных автомобилях):
 * После того, как мобильный телефон   и автомобильный компьютер завершают сопряжение Bluetooth на системном
 * уровне, ** мобильный телефон активно подключается к вспомогательной службе ** автомобильной машины SDP-трансляции.
 * (UUID 00000000-deca-fade-deca-deafdecacaff, измерено в Audi MIB3 SDP);
 * Автомобильное устройство   никогда активно не подключается к мобильному телефону (тестовый стенд DiPlay
 * подключается обратно, что является поведением при тестировании принимающей стороны).
 *
 * После подключения   он повторно использует тот же передающий конец, что и тестовый стенд ([Iap2SenderSession]),
 * Обратный вызов [onEndpoint] при успешной передаче учетных данных точки доступа и конечной точки AirPlay.
 */
class Iap2BluetoothClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 срабатывает сразу по прибытии (привязка к Wi-Fi + трансляция _carplay-ctrl), что является предварительным условием для настоящего автомобиля 0x4301. */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** После фазы 1, когда автомобильный компьютер повторно выдает 0x4301 (окно CarPlay открывается поздно), он передается конечной точке горячего обновления главного управления. */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** Уведомить мастера, когда канал отправителя отключен (насос поддержания активности отключается) (освободите раунд зомби, см. SessionController.onSenderLinkClosed). */
    private val onLinkClosed: () -> Unit = {},
    /** SSID точки доступа транспортного средства, известный в этом процессе (он будет использоваться, когда в этом раунде не будет новых учетных данных, чтобы избежать отключения соединения через 8 секунд в каждом раунде). */
    private val knownCarSsid: () -> String? = { null },
    /** 0x1D01 Генерация ссылки для определения изображения (обратный вызов после завершения этапа 1, используется для отправки этапа AirPlay). */
    private val onGeneration: (ReceiverProfile.LinkGeneration) -> Unit = {},
    /** Целевая машина (динамическое чтение: не нужно перезапускать ссылку после выбора, она вступит в силу в следующем раунде попыток). */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
) {
    companion object {
        /**
         * UUID вспомогательного терминала (автомобильного устройства) беспроводной службы iAP2.
         * UUID на мобильном телефоне —...cafe (xcertplay IAP2_IPHONE_UUID), а UUID на стороне аксессуара —...caff.
         */
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        private const val SESSION_TIMEOUT_MILLIS = 90_000L
        private const val RETRY_DELAY_MILLIS = 3_000L
        private const val FAILURE_LOG_THROTTLE_MILLIS = 15_000L

        /** Регулирование журнала «Bluetooth подключен → отключен» (автомобиль может автоматически повторно подключаться несколько раз, чтобы избежать обновления экрана). */
        private const val PROFILE_LOG_THROTTLE_MILLIS = 15_000L

        /** Окно ожидания обнаружения службы SDP (fetchUuidsWithSdp → широковещательная рассылка ACTION_UUID). */
        private const val SDP_PROBE_TIMEOUT_MILLIS = 2_500L
    }

    private var thread: Thread? = null
    private val currentSocket = AtomicReference<BluetoothSocket?>(null)

    /**
     * Число генераций клиентских потоков: start()/stop() увеличится. Поток, застрявший в заблокированном методе Connect(),
     * После разблокировки       stop() (закрытия соединительного сокета) он завершится напрямую из-за несовместимости поколений ——
     * предотвращает «остывание потока в живых после остановки, а также новое и старое поколение одновременных
     * соединений при повторном запуске пользователя»
     * (фактическое тестирование 11 октября 2026 г.: несколько потоков-зомби, одновременно запускающих RFCOMM,
     * приводят к частому отключению автомобиля).
     */
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong(0)

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    @Volatile
    private var running = false

    /** Быстрое регулирование, когда целевого устройства нет в списке сопряженных устройств (что позволяет избежать обновления экрана каждые 3 секунды). */
    private var lastTargetMissingLogAt = 0L

    /** Дедупликация журнала «Нет службы iAP2 → Пропустить» (только один раз для каждого адреса, повторный опрос не будет обновлять экран). */
    private val skippedNoService = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Кэш заключения «предоставляется ли услуга iAP2» (адрес → заключение): SDP-запрос стоит недешево, не перепроверяйте каждые 3 секунды. */
    private val iap2ServiceCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Предоставляет ли устройство  службу iAP2 на стороне аксессуара (…caff): сначала прочитайте системный кэш
     * uuids, если он отсутствует, затем
     * `fetchUuidsWithSdp` запускает запрос SDP и ожидает широковещательной рассылки `ACTION_UUID` (тайм-аут
     * указывает на отсутствие обслуживания).
     * Вывод кэшируется до остановки клиента - сценарий обновления прошивки автомобиля и замены UUID покрывается перезапуском приложения.
     * Разрешение      : start() проверено BLUETOOTH_CONNECT; uuids/fetchUuidsWithSdp
     * SecurityException поглощается runCatching (сбой обнаружения обрабатывается как отсутствие обслуживания, звук продолжается).
     */
    @SuppressLint("MissingPermission")
    private fun awaitIap2Service(device: BluetoothDevice): Boolean {
        iap2ServiceCache[device.address]?.let { return it }

        fun cachedHas(): Boolean =
            runCatching {
                device.uuids?.any { it == ACCESSORY_IAP2_UUID } == true
            }.getOrDefault(false)

        if (cachedHas()) {
            iap2ServiceCache[device.address] = true
            return true
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: android.content.Intent,
                ) {
                    @Suppress("DEPRECATION")
                    val hit = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (hit?.address == device.address) latch.countDown()
                }
            }
        val started =
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    android.content.IntentFilter(BluetoothDevice.ACTION_UUID),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                device.fetchUuidsWithSdp()
            }.getOrDefault(false)
        if (started) {
            try {
                latch.await(SDP_PROBE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        runCatching { context.unregisterReceiver(receiver) }
        val has = cachedHas()
        iap2ServiceCache[device.address] = has
        return has
    }

    /** Временная метка регулирования журнала «Bluetooth подключен → отключен». */
    private var lastProfileLogAt = 0L

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT-C: отсутствует разрешение BLUETOOTH_CONNECT")
            return false
        }
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT-C: Bluetooth недоступен или не включен.")
            return false
        }
        // Сброс вывода SDP и пропуск журнала при каждом запуске (новое сопряжение/повторное обнаружение после смены прошивки)
        iap2ServiceCache.clear()
        skippedNoService.clear()
        running = true
        val myGeneration = generation.incrementAndGet()
        val target = targetProvider()
        log(
            "BT-C: Старт — активное подключение к беспроводной службе iAP2 (…caff) автомобиля." +
                if (target != null) {
                    ", цель: «${target.name}» ${target.address} (подключено только к этому устройству)"
                } else {
                    "(Цель не указана: последовательно опробовать все устройства-кандидаты, которые можно указать в " +
                        "разделе «Подготовка к подключению»)."
                },
        )
        Thread({ connectLoop(myGeneration) }, "iap2-bt-client").apply {
            isDaemon = true
            thread = this
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connectLoop(myGeneration: Long) {
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            } ?: return
        var lastFailureLogAt = 0L
        while (running && generation.get() == myGeneration) {
            // Бесшумное шумоподавление (включено после пользовательского тестирования 10 октября 2026 г.) — во время сеанса
            // полностью бесшумно, попытки подключения/журналы не генерируются:

            // - inboundActive: принимающая сторона подключается к нам (два RFCOMM наступают друг на друга; двойные сеансы,
            // протестированные Audi, хаотичны)

            // - sessionActive: сеанс CarPlay установлен (получена конечная точка) - все попытки соединения с другими
            // ресиверами приостанавливаются

            // Типичный сценарий: больше не нужно бездумное обнаружение автомобиля Audi при подключении к Redmi/DiPlay
            // (устранение шума журнала и сбоев Bluetooth);

            //    Автоматически возобновляется после завершения сеанса (завершение сеанса BT/отключение вручную на любом конце).
            if (BtSessionGate.inboundActive || BtSessionGate.sessionActive) {
                sleepQuietly(2_000)
                continue
            }
            val candidates = candidateDevices(adapter)
            if (candidates.isEmpty()) {
                log(
                    "BT-C: нет сопряженного устройства Bluetooth. Сначала завершите сопряжение Bluetooth с автомобилем в " +
                        "настройках системы.",
                )
                sleepQuietly(RETRY_DELAY_MILLIS * 3)
                continue
            }
            var connected: BluetoothSocket? = null
            for (device in candidates) {
                if (!running || generation.get() != myGeneration) return
                val name =
                    try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    } ?: device.address
                runCatching { adapter.cancelDiscovery() }
                // Автоматический режим (цель не выбрана) сначала проходит через шлюз SDP: Кандидаты = все сопряженные
                // устройства, наушники/часы.

                // Служба iAP2 отсутствует, и HFP/A2DP будет отключен напрасно, если его невозможно подключить (отключение на
                // уровне устройства дополнительно повлияет на

                // Другие онлайн-устройства) — убедитесь, что ...caff предоставлен, прежде чем начинать процесс «отключить +
                // подключить». выбранная цель

                // Устройство не проходит через шлюз (явно указанный пользователем, исходный путь и время сохраняются).
                if (targetProvider() == null && !awaitIap2Service(device)) {
                    if (skippedNoService.add(device.address)) {
                        log(
                            "BT-C: пропустить «$name» (сервис iAP2 не найден — устройства, не поддерживающие CarPlay, " +
                                "оставьте их звук подключенным)",
                        )
                    }
                    continue
                }
                // «Только сопряжение, а не соединение»: Audi MIB3 фактически протестировал мобильный телефон и автомобиль в
                // состоянии Bluetooth-подключения

                // (HFP/A2DP онлайн), автомобиль не открывает окно службы iAP2 беспроводной системы CarPlay——
                // Производительность такова, что соединение разрывается каждые 3 секунды. iAP2 — это автономная прямая служба
                // SDP, и с этими профилями

                // Неважно, сам CarPlay после отключения не пострадает (звук идет через Wi-Fi AirPlay).
                run {
                    val dropped = BtProfiles.disconnect(context, device)
                    if (dropped.isNotEmpty()) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                            lastProfileLogAt = now
                            val left = BtProfiles.stillConnected(context, device.address)
                            log(
                                "BT-C: $name находится в состоянии подключения Bluetooth (${dropped.joinToString("/")})——Отключено" +
                                    if (left.isEmpty()) "" else "(Еще существует ${left.joinToString("/")} онлайн)",
                            )
                            log("BT-C: Автомобиль не принимает CarPlay в состоянии «Bluetooth Connected» — продолжайте только сопряжение")
                        }
                    }
                }
                val socket = tryConnect(device)
                if (socket != null) {
                    log("BT-C: ✓ Беспроводная служба iAP2, подключенная к $name (установлен RFCOMM)")
                    connected = socket
                    break
                } else {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastFailureLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastFailureLogAt = now
                        log(
                            "BT-C: Не удалось подключиться к $name (автомобиль не готов или нет окна беспроводного " +
                                "сопряжения), повторите попытку через 3 секунды...",
                        )
                    }
                }
            }
            if (connected == null) {
                sleepQuietly(RETRY_DELAY_MILLIS)
                continue
            }
            if (generation.get() != myGeneration) {
                // stop() успешно конкурирует с Connect(): срок действия этого потока истек, и сеанс не будет установлен.
                currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
                return
            }
            runSession(connected)
            sleepQuietly(RETRY_DELAY_MILLIS)
        }
        log("BT-C: Клиент остановлен")
    }

    /**
     * Устройство-кандидат : отдайте приоритет сопряженным устройствам с UUID на стороне аксессуара в кэше SDP;
     * деградирует для всех сопряженных устройств, когда кэш отсутствует (запрос SDP во время подключения будет фильтроваться сам по себе).
     * Если указан целевой автомобиль, возвращается только устройство** (во избежание неправильного подключения, если
     * пользователь соединил несколько автомобилей).
     */
    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: android.bluetooth.BluetoothAdapter): List<BluetoothDevice> {
        val bonded =
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        targetProvider()?.let { target ->
            // Целевая политика: попадание по адресу является единственным кандидатом и больше не фильтруется кешем SDP (кэш
            // может отсутствовать,

            // Пользователь должен попробовать, если это явно указано); когда цель отсутствует в парном списке, она выдаст
            // подсказку и будет ждать бездействия.

            val hit = bonded.filter { TargetDevicePrefs.matches(target, it.address) }
            if (hit.isEmpty()) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTargetMissingLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                    lastTargetMissingLogAt = now
                    log(
                        "BT-C: целевого устройства «${target.name}» (${target.address}) нет в списке сопряженных устройств." +
                            "——Перейдите к настройкам Bluetooth системы, чтобы завершить сопряжение, или измените цель в " +
                            "«Подготовке подключения».",
                    )
                }
                return emptyList()
            }
            return hit
        }
        val withService =
            bonded.filter { device ->
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }
        return if (withService.isNotEmpty()) withService else bonded
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): BluetoothSocket? {
        val socket =
            try {
                device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
            } catch (error: IOException) {
                return null
            }
        // Зарегистрируйтесь перед подключением: Connect() — это блокирующий вызов (запрос SDP + установление соединения
        // может занять до десятков секунд),

        // stop() может прервать его с помощью close() только в том случае, если он может получить ссылку на сокет, иначе поток застрянет.
        currentSocket.set(socket)
        return try {
            socket.connect()
            socket
        } catch (error: IOException) {
            currentSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
            null
        }
    }

    private fun runSession(socket: BluetoothSocket) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt-car") { line -> log(line) }
        activeChannel = channel
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                onEndpoint,
                onWifiCredentials,
                onEndpointUpdate,
                onLinkClosed,
                knownCarSsid,
                onGeneration,
            )
        } catch (error: IOException) {
            log("BT-C: СЕССИЯ не удалась — ${error.message}")
        } finally {
            activeChannel = null
            currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
            runCatching { socket.close() }
            channel.close()
            log("BT-C: этот раунд сеанса заканчивается (клиент все еще работает и автоматически переподключится)")
        }
    }

    /** Активно завершить текущий сеанс управления BT (не затрагивая цикл повторного подключения). Используется для восстановления состояния выведенного из эксплуатации канала управления перед сопряжением AirPlay. */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT-C: Текущий сеанс управления был активно отключен.")
        }
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    fun stop() {
        running = false
        // Увеличение поколений: даже если поток застревает в методе Connect(), он позже покончит с собой из-за
        // несовместимых поколений и не будет воскрешен при следующем запуске.

        generation.incrementAndGet()
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
        activeChannel?.let { runCatching { it.close() } }
        thread?.interrupt()
        thread = null
        log("БТ-С: остановлено")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }
}

/**
 * Шлюз входящего сеанса: пока активен сеанс iAP2, активно подключенный транспортным средством, исходящий клиент
 * приостанавливает попытки подключения.
 * Цель: Транспортные средства обычно имеют только один беспроводной контекст iAP2, и одновременное установление
 * двустороннего соединения будет мешать друг другу.
 */
object BtSessionGate {
    /** Входящий сеанс iAP2 (автомобиль-машина активно подключен) активен: исходящий приостанавливается, чтобы избежать взаимодействия двух RFCOMM друг с другом. */
    @Volatile
    var inboundActive: Boolean = false

    /**
     * Сеанс CarPlay установлен (устанавливается после успешного получения конечной точки по любому пути, поддерживаемой SessionController):
     * Приостанавливает все попытки исходящего подключения (бесшумное шумоподавление) — обычно Audi больше не
     * обнаруживается при подключении к DiPlay.
     * Очищается SessionController, когда сеанс завершается/отключается вручную.
     */
    @Volatile
    var sessionActive: Boolean = false
}
