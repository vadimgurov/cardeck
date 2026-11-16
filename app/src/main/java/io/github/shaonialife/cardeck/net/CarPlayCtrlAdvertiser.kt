package io.github.shaonialife.cardeck.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DeviceIdentity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * транслирует службу управления _carplay-ctrl._tcp на стороне мобильного телефона (символ iPhone) и отвечает на запрос получателя.
 *
 * В беспроводном CarPlay   после обнаружения услуги принимающая сторона обратно подключится к этому порту и отправит его первой.
 * `GET /ctrl-int/1/connect` (несущий AirPlay-Receiver-Device-ID) определяет, находится ли мобильный телефон в сети,
 * Ожидается HTTP 200 (см. xcertplay CarPlayBonjour.probeOnce: строка состояния только для чтения).
 * После прохождения обнаружения   сеанс управления iAP2 передается через туннель типа 130 канала событий AirPlay
 * (последующие действия на этапе 2).
 *
 * Этот класс одновременно регистрирует строки запроса каждого соединения — то, что DiPlay отправит позже, будет непосредственно видно.
 */
class CarPlayCtrlAdvertiser(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val SERVICE_TYPE = "_carplay-ctrl._tcp"

        /**
         * Версия, указанная отправителем в ctrl TXT.
         *
         * **Необходимо поддерживать 950.7.1**: 19 октября 2026 г. Сравнение реальных автомобилей Audi MMI 3315 — изменено на кошачью игру
         * После           566.25.21 (версия отправителя SDK) машина и машина больше даже не обнаруживают ctrl-int, и
         * 0x4301 так и не приходит;
         * Когда           использует 950.7.1, тот же автомобиль выдает 0x4301 в течение 0,65 секунды. Автомобиль видит
         * этот TXT только по Wi-Fi.
         * Очевидно, что он оценивает форму противника на основе «общей версии трансляции принимающей стороны/системы».
         */
        private const val SENDER_SRCVERS = "950.7.1"

        /**
         * Имя экземпляра службы ctrl. Аналогично сохраните `sender` в версии 0.1.6: переименование в `iPhone` вступит в
         * силу одновременно с предыдущим.
         * не может отличить атрибуцию от одного журнала, поэтому возвращается к проверенной и доступной комбинации (19 октября 2026 г.).
         */
        private const val SERVICE_NAME = "sender"

        /** Тестирование можно увидеть: эти два значения проверены на реальных автомобилях, и изменения необходимо сначала протестировать повторно (см. CarPlayCtrlAdvertiserTest). */
        internal fun advertisedSrcversForTest(): String = SENDER_SRCVERS

        internal fun advertisedServiceNameForTest(): String = SERVICE_NAME

        /** Тестирование видно: полный набор типов сервисов, реально транслируемых этим классом (на данный момент удален только ctrl; _device-info). */
        internal fun advertisedServiceTypesForTest(): List<String> = listOf(SERVICE_TYPE)

        private const val TAG = "CTRL"
        private const val MAX_HEAD_BYTES = 16 * 1024

        /** Время восстановления принудительной перерегистрации для того же SSID (см. обеспечениеForNetwork). */
        private const val FORCE_COOLDOWN_MILLIS = 2_000L
    }

    private var serverSocket: ServerSocket? = null
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Продолжается ли принимающий поток (очищается только [stop]; перерегистрация его не трогает, порт и поток сохраняются). */
    @Volatile
    private var acceptRunning = false

    @Volatile
    private var registered = false

    /**
     * Количество приглашений на управление автомобилем (увеличивается на единицу каждый раз при `GET /ctrl-int/1/connect`).
     *
     * Триггерной точкой соединения отправителя Catplay       является это приглашение: оно означает «машина обнаружила нас и открылась».
     * Окно AirPlay», подключитесь к AirPlay сразу после его получения и не переходите в режим фиксированного сна.
     * Соответственно заменяется основной элемент управления.
     * Исходное предположение о времени «переход в режим ожидания 1,6 с после передачи конечной точки».
     */
    @Volatile
    var inviteCount: Int = 0
        private set

    /** Сбрасывается, когда начинается новый раунд подключения (чтобы не воспринимать предыдущий раунд приглашений как сигналы для этого раунда). */
    fun resetInviteCount() {
        inviteCount = 0
    }

    /** SSID WiFi во время регистрации: после смены сети (2.4G → 5G или переключения точки доступа в автомобиле) старая регистрация не будет видна, и вам необходимо зарегистрироваться снова. */
    private var registeredSsid: String? = null

    /** Действующий в настоящее время зарегистрированный прослушиватель (заменяйте новый экземпляр каждый раз, когда вы начинаете, чтобы избежать уже используемых). */
    private var activeListener: NsdManager.RegistrationListener? = null

    /** Это время регистрации (используется для определения принудительного охлаждения). */
    @Volatile
    private var registeredAtMillis: Long = 0L

    /** В этой регистрации прописан MAC TXT (значение размещения = машина не будет приглашена и ее необходимо как можно скорее перерегистрировать). */
    @Volatile
    private var registeredWithMac: String? = null

    /** Сеть, в которой находится процесс во время этой регистрации (используется для определения необходимости повторной регистрации после привязки). */
    private var registeredNetwork: android.net.Network? = null

    val port: Int get() = serverSocket?.localPort ?: -1

    /** Является ли текущий идентификатор TXT заполнителем MAC (автомобиль будет соответствующим образом соответствовать мобильному телефону; если заполнитель используется, он никогда не будет приглашен). */
    val macPlaceholder: Boolean
        get() = registeredWithMac == DeviceIdentity.PLACEHOLDER_MAC

    /**
     * гарантирует, что широковещательная рассылка будет видна в сети для указанного SSID (вызывается каждый раз,
     * когда сеанс получает 0x5703).
     * Тест       на реальном автомобиле (11 октября 2026 г.): после переключения мобильного телефона с точки доступа
     * 2,4G на точку доступа 5G старая регистрация mDNS
     * больше не виден машине (машина больше не отправляет обнаружение ctrl-int → не открывает окно AirPlay).
     * При изменении SSID выйдите из системы, а затем повторно зарегистрируйтесь, чтобы широковещательная рассылка перешла в текущую сеть.
     *
     * @param Force true = безусловная перерегистрация (для коррекции времени «после привязки сети процесса»:
     * Многоадресная рассылка mDNS       следует за сетьюbindProcessToNetwork, регистрация должна произойти после привязки).
     * Сеть       @param — сеть связанного процесса; если она отличается от последней зарегистрированной сети,
     * необходимо перерегистрировать (без ограничений по охлаждению)——
     * Иначе при "переключении на автомобильную точку доступа сразу после регистрации" 2 секунды охлаждения поглотят
     * перерегистрацию ключа после привязки.
     */
    @Synchronized
    fun ensureForNetwork(
        ssid: String,
        force: Boolean = false,
        network: android.net.Network? = null,
    ): Boolean {
        if (registered && registeredSsid == ssid && !force && !macPlaceholder) return true
        // принудительное охлаждение: серия «обеспечивающих» вызовов после привязки (bindCarWifiAndRegister → передача конечной точки)
        // Часто происходит в течение одной и той же секунды, последовательная отмена регистрации + регистрация попадает в NsdManager.
        // Listener-already-in-use, и повторная попытка занимает 1,2 секунды, но замедляет соединение.
        // Исключения: MAC в широковещательной рассылке является значением-заполнителем или сеть процесса изменилась. Это
        // нельзя игнорировать.

        val sameNetwork = network == null || network == registeredNetwork
        if (force &&
            registered &&
            registeredSsid == ssid &&
            !macPlaceholder &&
            sameNetwork &&
            SystemClock.uptimeMillis() - registeredAtMillis < FORCE_COOLDOWN_MILLIS
        ) {
            log(
                "$TAG: Пропустить повторную принудительную перерегистрацию (незадолго до этого был зарегистрирован " +
                    "${SystemClock.uptimeMillis() - registeredAtMillis}ms)",
            )
            return true
        }
        if (registered) {
            log(
                "$TAG: Перерегистрировать широковещательную рассылку mDNS " +
                    "(${registeredSsid ?: "?"}${if (force) "，Коррекция тайминга после привязки" else " → $ssid"}）",
            )
            // Выйдите только из службы, **сохраните порт прослушивания**: Android NSD не поддерживает catplay/JmDNS.
            // reannounce(), повторное объявление возможно только после выхода из системы + перерегистрации; если порт будет
            // изменен одновременно, автомобиль будет использовать кэшированный порт

            // Обнаружение будет подключено к закрытому разъему (автомобильная сторона xcertplay обнаруживает 7 раз за сеанс × 1,5 с,
            // Если порт будет изменен в середине раунда, весь раунд будет признан недействительным). 2026-10-22 В журнале
            // Ауди порт перескакивал до упора при 6 перерегистрациях.

            // (43857→33383→33125→38151→40697→38015), каждый раз появляется пустое окно длительностью до 1,2 с.
            unregisterServiceOnly()
        }
        registeredSsid = ssid
        registeredNetwork = network ?: registeredNetwork
        var ok = start()
        if (!ok) {
            // Выход из системы происходит асинхронно: в крайних случаях RegisterService обнаружит «прослушиватель уже используется»,
            // Попробуйте еще раз позже (тест 10-11 на реальной машине: 300 мс недостаточно, нужно больше времени)
            try {
                Thread.sleep(1_200)
            } catch (_: InterruptedException) {
            }
            ok = start()
        }
        return ok
    }

    @Synchronized
    fun start(): Boolean {
        if (registered) return true
        return try {
            // Порт фиксирован на протяжении всего жизненного цикла рекламодателя: повторная регистрация меняет только
            // регистрацию mDNS и повторно использует тот же самый порт.

            // сокет и принимать потоки (см. комментарии к обеспечениюForNetwork).
            val socket =
                serverSocket ?: ServerSocket(0).also { created ->
                    serverSocket = created
                    acceptRunning = true
                    Thread(::acceptLoop, "ctrl-accept").apply {
                        isDaemon = true
                        start()
                    }
                }
            // Принудительно повторить попытку при занятии MAC (Шизуку может быть не готов к началу соединения и публикации трансляции;
            // Если содержание трансляции будет неправильным, ведущий нас никогда не пригласит)
            var mac = DeviceIdentity.bluetoothMac(context)
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
                log(
                    "$TAG: MAC-адрес Bluetooth преобразован в значение-заполнитель, принудительная повторная попытка " +
                        "${if (mac == DeviceIdentity.PLACEHOLDER_MAC) "（Все еще не удалось——Чеджи может не пригласить，Пожалуйста, подтвердите Shizuku Началось）" else "успех"}",
                )
            }
            val info =
                NsdServiceInfo().apply {
                    serviceName = SERVICE_NAME
                    serviceType = SERVICE_TYPE
                    setPort(socket.localPort)
                    // Идентификатор TXT = настоящий MAC-адрес Bluetooth этой машины (доказательство Catplay CarPlayInviter:
                    // автомобильная машина, нажмите здесь)

                    // В соответствии с приглашаемым мобильным телефоном значение заполнителя приведет к тому, что автомобильный
                    // телефон никогда не будет приглашать → 0x4301 никогда не прибудет).

                    setAttribute("id", mac)
                    setAttribute("srcvers", SENDER_SRCVERS)
                }
            // Используйте новый прослушиватель для каждой регистрации: unregisterService является асинхронным и повторно
            // использует один и тот же прослушиватель.

            // Нажмем «слушатель уже используется» (реальный автомобильный тест 10-11 застрял в трансляции с Ctrl)
            val freshListener = createListener()
            activeListener = freshListener
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, freshListener)
            registered = true
            registeredAtMillis = SystemClock.uptimeMillis()
            registeredWithMac = mac
            // Единственным видимым идентификатором мобильного телефона автомобиля через Wi-Fi являются эти поля TXT:
            // заполнитель MAC/версия будет противоречивым.

            // Напрямую приводит к «нет обнаружения Ctrl, 0x4301 никогда не приходит», но в предыдущих журналах они не были видны.
            // (2026-10-19 Audi 0.1.7 невозможно приписать во время устранения неполадок), поэтому введите строку для каждой регистрации.
            log(
                "$TAG: Отслеживается: ${socket.localPort}, трансляция $SERVICE_TYPE." +
                    "name=$SERVICE_NAME id=$mac srcvers=$SENDER_SRCVERS" +
                    if (mac == DeviceIdentity.PLACEHOLDER_MAC) "(⚠ Заполнитель MAC: Машина не пригласит)" else "",
            )
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                // Транспортное средство с идентификатором-заполнителем не будет приглашено: подождите, пока Shizuku/MAC будет
                // готов в фоновом режиме, и немедленно перерегистрируйтесь.

                // Не допускайте «трансляции заранее, чтобы начать соединение», но неправильно записывайте идентификатор TXT (18
                // октября 2026 г.).

                Bg.run("ctrl-mac-repair") { repairPlaceholderMac() }
            }
            true
        } catch (error: Exception) {
            log("$TAG: Ошибка запуска — ${error.message}")
            false
        }
    }

    /** Самовосстановление заполнителя MAC: повторяйте разрешение каждые 2 секунды и после успеха перерегистрируйтесь в соответствии с текущим SSID. */
    private fun repairPlaceholderMac() {
        repeat(15) {
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return
            }
            if (!registered || !macPlaceholder) return
            val mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) return@repeat
            log(
                "$TAG: MAC-адрес Bluetooth был преобразован в $mac — перерегистрировать широковещательную рассылку ctrl " +
                    "(исправить идентификатор заполнителя)",
            )
            ensureForNetwork(registeredSsid ?: "", force = true)
            return
        }
        log(
            "$TAG: настоящий MAC-адрес Bluetooth не может быть распознан в течение 30 секунд — идентификатор трансляции " +
                "ctrl по-прежнему является значением-заполнителем, и автомобиль не может быть приглашен (подтвердите, что Shizuku запущен)",
        )
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (acceptRunning) {
            val client =
                try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
            Bg.run("ctrl-client") { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: "?"
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readUntilHeaderEnd(input)
            if (head.isEmpty()) {
                log("$TAG: $peer подключен, но нет запроса (EOF)")
                return
            }
            val requestLine = head.lineSequence().firstOrNull() ?: ""
            val headers =
                head
                    .substringAfter("\r\n", "")
                    .lineSequence()
                    .filter { it.contains(':') }
                    .joinToString("; ") { it.trim() }
            log("$TAG: $peer -> [$requestLine] $headers")

            // Приглашение управления автомобилем-машиной: это авторитетный сигнал о том, что «автомобиль-машина обнаружила
            // телефон и окно AirPlay открыто».

            // (catplay CarPlayCtrlSession: передать приглашение в процесс подключения по получении).
            if (requestLine.startsWith("GET /ctrl-int/1/connect")) {
                val receiverId =
                    head
                        .lineSequence()
                        .firstOrNull {
                            it.substringBefore(':').trim().equals("AirPlay-Receiver-Device-ID", ignoreCase = true)
                        }?.substringAfter(':')
                        ?.trim()
                inviteCount++
                log(
                    "$TAG: ✓ Приглашение к управлению автомобилем (время $inviteCount, " +
                        "получатель=${receiverId ?: "?"})——Окно AirPlay открыто",
                )
            }

            val output = client.getOutputStream()
            val response =
                when {
                    requestLine.startsWith("GET /ctrl-int/") -> {
                        log("$TAG: ✓ Ответить на команду ctrl-int 200 OK")
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    }

                    else -> {
                        log(
                            "$TAG: Нераспознанный запрос, обрабатывается как пустой ответ 200 (последующая реализация " +
                                "будет дорабатываться по мере необходимости)",
                        )
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    }
                }
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()

            // Одноранговое соединение: close отключится само; если в период ожидания остались байты (туннель/событие), выгрузите их.
            client.soTimeout = 3_000
            val rest = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    rest.write(buffer, 0, count)
                    if (rest.size() > 64 * 1024) break
                }
            } catch (_: IOException) {
                // Прекратить сброс, когда время чтения истекло
            }
            if (rest.size() > 0) {
                log(
                    "$TAG: байты ${rest.size()}, полученные после ответа:" +
                        rest.toByteArray().take(64).joinToString(" ") {
                            "%02x".format(it.toInt() and 0xFF)
                        },
                )
            }
            log("$TAG: Соединение $peer разорвано.")
        } catch (error: IOException) {
            log("$TAG: Неисправность соединения $peer — ${error.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        while (buffer.size() < MAX_HEAD_BYTES) {
            val count =
                try {
                    input.read(chunk)
                } catch (_: IOException) {
                    break
                }
            if (count < 0) break
            buffer.write(chunk, 0, count)
            val text = buffer.toString("ISO-8859-1")
            if (text.contains("\r\n\r\n")) return text
        }
        return buffer.toString("ISO-8859-1")
    }

    /** Отмените только регистрацию mDNS, сохраните сокет прослушивания и примите поток (при перерегистрации порт остается неизменным). */
    @Synchronized
    private fun unregisterServiceOnly() {
        registered = false
        registeredWithMac = null
        activeListener?.let { runCatching { nsdManager.unregisterService(it) } }
        activeListener = null
    }

    @Synchronized
    fun stop() {
        unregisterServiceOnly()
        registeredSsid = null
        registeredNetwork = null
        acceptRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        log("$TAG: Остановлено")
    }

    /** Каждый раз регистрируется новый прослушиватель (см. комментарий start(); повторное использование одного и того же экземпляра приведет к срабатыванию уже используемого). */
    private fun createListener(): NsdManager.RegistrationListener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                log("$TAG: регистрация mDNS прошла успешно ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) {
                // Состояние сброса: SureForNetwork (0x5703 для нового сеанса) будет перерегистрирован в следующий раз.
                registered = false
                log("$TAG: код ошибки регистрации mDNS = $errorCode (автоматический повтор следующего сеанса)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) = Unit
        }
}
