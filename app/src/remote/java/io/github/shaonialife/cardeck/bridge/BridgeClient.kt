package io.github.shaonialife.cardeck.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.shaonialife.cardeck.BuildConfig
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * клиент моста удаленной отладки (эксклюзивный вариант удаленной отладки).
 *
 * Обязанности: Поддержание длительного соединения WSS/WS с сервером отладки - сопряжение, журналы (пакетное
 * заполнение + потоковая передача в реальном времени),
 * Белый список команд  , подсказка и отправка новой версии. Между сеансом и бизнес-кодом есть только две точки связи:
 * [DebugFileLog.tap] (чтение журналов) и [BridgeCommands] (выполнение команд, использование существующего общедоступного API).
 *
 * Поток  : поддерживает поток соединения «мост-клиент» (соглашение проекта, согласно которому потоки
 * долгосрочного цикла не попадают в пул Bg);
 * После того, как сообщение, полученное   WS, анализируется потоком подключения, оно переключается на основной
 * поток (приостановленное окно) или Bg (команда) по мере необходимости.
 */
object BridgeClient {
    /** Статус соединения (отображение страницы настроек; чтение основного потока). */
    @Volatile
    var stateText: String = "Не подключено"
        private set

    /** Время следующего автоматического переподключения (elapsedRealtime; 0 = не запланировано). Для отображения обратного отсчета. */
    @Volatile
    var nextRetryAtElapsed: Long = 0
        private set

    /** Причина последнего отключения/сбоя (прилагается обратный отсчет строки состояния). */
    @Volatile
    private var lastDisconnectReason: String? = null

    /** В этом процессе установлен APK-файл sha256 (дифференциальный базовый уровень, отчеты hello; кеш ключей версий, чтобы избежать каждого перерасчета) */
    @Volatile
    private var cachedBaseSha: String? = null

    /**
     * Строка состояния страницы настроек: добавление обратного отсчета «Автоматическая повторная попытка через Ns»
     * при отступлении и повторном подключении — отключение должно быть за пользователем.
     * виден и явно ожидаем (запрос пользователя 22 октября 2026 г.), а не просто «соединение отключено».
     */
    fun statusLine(): String {
        val next = nextRetryAtElapsed
        if (next > 0) {
            val remain = ((next - android.os.SystemClock.elapsedRealtime()) / 1000 + 1).coerceAtLeast(0)
            val reason = lastDisconnectReason
            return "Соединение прервано${if (reason != null) ": $reason" else ""} (повтор через $remain с)"
        }
        return stateText
    }

    /**
     * Уведомление о смене статуса соединения (true=подключено; false=ранее подключенное соединение отключено).
     * Уровень приложения       использует его для отображения всплывающих подсказок пользователю; обратный вызов
     * запускается в потоке подключения, и сторона реализации сама переключается на основной поток.
     */
    @Volatile
    var connectionEvents: ((connected: Boolean) -> Unit)? = null

    private var wasConnected = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var running = false

    @Volatile
    private var ws: WsClient? = null

    /** Сотовый интернет-канал: технологическая сеть привязана к бортовой точке доступа во время сеанса связи между автомобилем, и мост отладки должен проходить через нее. */
    @Volatile
    private var route: InternetRoute? = null

    /** Происходит ли текущая попытка подключения через сеть по умолчанию (используется для сброса решения о сбое маршрутизации в случае успешного приветствия). */
    @Volatile
    private var connUsedDefaultNetwork = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Буфер кольцевого журнала (нажмите seq, чтобы передать данные серверу после отключения и повторного подключения). */
    private val ringLock = Any()
    private val ring = ArrayDeque<Pair<Long, String>>()
    private const val RING_CAP = 5_000

    /** Журналы отправляются в режиме реального времени (поток касания ставится в очередь, а поток подключения очищается пакетно). */
    private val pendingLock = Any()
    private val pending = ArrayDeque<Pair<Long, String>>()

    private var tapInstalled = false

    fun start(context: Context) {
        appContext = context.applicationContext
        installTap()
        if (running) return
        running = true
        // Сначала должен быть подключен сотовый канал: первое соединение может произойти в автомобильной сессии
        // (технологическая сеть была привязана)

        route = InternetRoute(context.applicationContext).also { it.start() }
        Thread({ runLoop() }, "bridge-client").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        route?.stop()
        route = null
        ws?.close(1000, "клиент остановлен")
        setState("Отключено")
    }

    /**
     * Сотовый интернет-канал (например, для загрузки APK): во время сеанса процесс привязан к сети автомобиля.
     */
    fun internetNetwork(): android.net.Network? = route?.current()

    private fun setState(text: String) {
        stateText = text
        // no-op: сохранять ссылку на объект стабильной (статус не записывается в журнал файлов, чтобы избежать эха)
        DebugFileLog.tap?.let {}
    }

    private fun installTap() {
        if (tapInstalled) return
        tapInstalled = true
        DebugFileLog.tap = { seq, line ->
            synchronized(ringLock) {
                ring.addLast(seq to line)
                while (ring.size > RING_CAP) ring.removeFirst()
            }
            synchronized(pendingLock) { pending.addLast(seq to line) }
        }
    }

    private fun runLoop() {
        var backoffMs = 2_000L
        var attempt = 0
        while (running) {
            val ctx = appContext ?: return
            val url = BridgePrefs.serverUrl(ctx)
            if (url.isBlank()) {
                setState("Адрес сервера не настроен")
                sleepQuiet(3_000)
                continue
            }
            // Маршрутизация должна быть внесена в поток журнала: Во время сеанса автомобиля технологическая сеть привязана к
            // точке доступа, а соединение осуществляется через сотовую сеть.

            // Является ли оно по-прежнему значением по умолчанию и в чем причина сбоя - отсутствие чтения журналов
            // эквивалентно слепой настройке (фактический тест 2026-10-22:

            // WS отключается при входе в точку доступа и автоматически восстанавливается через 4 минуты. В течение этого
            // периода поток журналов останавливается, но следов, подлежащих проверке, нет)

            val network = route?.current()
            attempt++
            val client =
                WsClient(
                    url,
                    object : WsClient.Listener {
                        override fun onOpen() {
                            setState("Подключено, сопряжение...")
                            // Протокол управляется стороной устройства: сначала отправляется hello (включая код сопряжения), затем сервер
                            // проверяет и затем возвращает ответ.

                            // добро пожаловать — если вы дождетесь приветствия перед отправкой приветствия, произойдет тупиковая ситуация с
                            // сервером.

                            // Статус всегда остается «Сопряжение...» (первый этап фактического тестирования реального устройства)
                            sendHello()
                        }

                        override fun onText(text: String) = onServerMessage(text)

                        override fun onClosed(reason: String) {
                            setState("Отключено: $reason")
                            lastDisconnectReason = reason.take(40)
                            debugLog("Отключено: $reason")
                        }
                    },
                    network,
                )
            ws = client
            setState("Подключение…")
            nextRetryAtElapsed = 0
            connUsedDefaultNetwork = network == null
            debugLog(
                "Попытка #$attempt: сеть=${if (network != null) "мобильная" else "по умолчанию"} " +
                    "пауза=${backoffMs}ms",
            )
            try {
                client.connect()
                backoffMs = 2_000L
            } catch (interrupted: InterruptedException) {
                return
            } catch (error: Exception) {
                // Сбой/отключение соединения: отступите и подключитесь заново (причина сбоя записывается в поток журнала -
                // происходит ли повторный шторм)

                // Он должен быть виден в потоке, иначе удаленная сторона увидит только «журнал прекратил обновление» и не сможет
                // догадаться о причине).

                // По умолчанию о сетевых сбоях необходимо сообщать маршрутизатору: ложное значение VALIDATED (решение о
                // кэшировании) приведет к коллизиям в каждом раунде.

                // Тот же тупик, следующий раунд после noteFailure разрежет соты.
                debugLog("Соединение не удалось (${error.message ?: error.javaClass.simpleName})")
                lastDisconnectReason = (error.message ?: error.javaClass.simpleName).take(40)
                route?.noteFailure(connUsedDefaultNetwork)
            }
            ws = null
            // Установленное соединение достигло этой точки = отключено; уведомить пользователя о том, что появится слой
            // приложения (обратный отсчет находится в строке состояния)

            if (wasConnected) {
                wasConnected = false
                runCatching { connectionEvents?.invoke(false) }
            }
            if (!running) break
            nextRetryAtElapsed = android.os.SystemClock.elapsedRealtime() + backoffMs
            sleepQuiet(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /** Записывается в основной поток журнала (тот же конвейер, что и бизнес-журнал): собственные события подключения моста видны для удаленного устранения неполадок. */
    private fun debugLog(message: String) {
        appContext?.let { DebugFileLog.append(it, "BRIDGE: $message") }
    }

    private fun sleepQuiet(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ----Рукопопожатия и сообщения после установления соединения ----

    private fun onServerMessage(text: String) {
        val msg =
            runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "welcome" -> {
                if (msg.optBoolean("ok")) {
                    setState("Подключено(${msg.optLong("pairedAt")}）")
                    nextRetryAtElapsed = 0
                    debugLog("Сервер отладки подключен (сопряжение пройдено), поток журналов восстановлен.")
                    // В этом раунде подключения используется сеть по умолчанию, и она действительно подключена: очистка заключения
                    // об отказе «ложное ПОДТВЕРЖДЕНИЕ».

                    route?.noteSuccess(connUsedDefaultNetwork)
                    if (!wasConnected) {
                        wasConnected = true
                        runCatching { connectionEvents?.invoke(true) }
                    }
                    // Полное пополнение: Сервер удаляет дубликаты согласно последовательности и оставляет кольцевой буфер.
                    sendLogBatch(since = 0)
                } else {
                    setState("Сопряжение отклонено: ${msg.optString("error")}")
                    ws?.close(1008, "Сопряжение отклонено")
                }
            }

            "log.sync" -> sendLogBatch(msg.optLong("since"))

            "cmd" -> {
                val id = msg.optString("id")
                val name = msg.optString("name")
                val args = msg.optJSONObject("args") ?: JSONObject()
                Bg.run("bridge-cmd") {
                    val result = BridgeCommands.handle(appContext, name, args)
                    send(
                        JSONObject()
                            .put("t", "cmd.result")
                            .put("id", id)
                            .put("ok", result.optBoolean("ok"))
                            .put("detail", result.optString("detail")),
                    )
                }
            }

            "notify" -> {
                val message = msg.optString("text")
                mainHandler.post { BridgeOverlay.show(appContext, message, dismissMillis = 8_000) }
            }

            "apk.offer" -> {
                val delta = msg.optJSONObject("delta")
                ReinstallFlow.offer(
                    appContext,
                    msg.optString("url"),
                    msg.optString("sha256"),
                    msg.optString("version"),
                    deltaUrl = delta?.optString("url").takeIf { !it.isNullOrBlank() },
                    deltaSha256 = delta?.optString("sha256").takeIf { !it.isNullOrBlank() },
                    baseSha256 = delta?.optString("baseSha256").takeIf { !it.isNullOrBlank() },
                    deltaSizeKB = delta?.optInt("sizeKB", -1)?.takeIf { it > 0 },
                )
            }

            "ping" -> send(JSONObject().put("t", "pong").put("id", msg.optString("id")))

            // Загрузка файла журнала (источник исторических фактов за пределами дифференциального кольца): прочтите конец
            // файла main_debug.log.

            // → gzip → обратная передача одного сообщения в формате Base64. Чтение только собственного файла журнала,
            // никаких побочных эффектов.

            "log_file.req" -> {
                val reqId = msg.optString("id")
                val maxKB = msg.optInt("maxKB", 512).coerceIn(16, 4096)
                Bg.run("bridge-log-file") {
                    val ctx = appContext ?: return@run
                    val raw =
                        io.github.shaonialife.cardeck.util.DebugFileLog
                            .readTail(ctx, maxKB * 1024)
                            ?: ByteArray(0)
                    val gz =
                        java.io.ByteArrayOutputStream().use { bos ->
                            java.util.zip
                                .GZIPOutputStream(bos)
                                .use { it.write(raw) }
                            bos.toByteArray()
                        }
                    val payload =
                        JSONObject()
                            .put("t", "log_file.data")
                            .put("id", reqId)
                            .put("rawSize", raw.size)
                            .put("truncated", raw.size >= maxKB * 1024)
                            .put("gzB64", android.util.Base64.encodeToString(gz, android.util.Base64.NO_WRAP))
                    send(payload)
                    debugLog("Файл журнала возвращен (исходный ${raw.size / 1024}KB → gzip ${gz.size / 1024}KB)")
                }
            }
        }
    }

    /**
     * В  установлен sha256 (дифференциальная базовая линия) APK. Хэш 15 МБ ~ 50 мс, засчитывается только один раз,
     * когда процесс впервые приветствует
     * (результат сбрасывается в префы по номеру версии, а кеш читается сразу после переподключения); в случае
     * неудачи возвращается ноль (сервер принимает только полную сумму).
     */
    private fun baseSha256(ctx: Context): String? =
        runCatching {
            BridgePrefs.baseApkSha(ctx, BuildConfig.VERSION_NAME)?.let { return it }
            val source = File(ctx.applicationInfo.sourceDir)
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            BridgePrefs.setBaseApkSha(ctx, BuildConfig.VERSION_NAME, sha)
            sha
        }.getOrNull()

    private fun sendHello() {
        val ctx = appContext ?: return
        val hello =
            JSONObject()
                .put("t", "hello")
                .put("deviceId", BridgePrefs.deviceId(ctx))
                .put("deviceModel", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("code", BridgePrefs.pairCode(ctx))
                .put("lastSeq", DebugFileLog.lastSeq)
                .put("baseSha256", cachedBaseSha ?: baseSha256(ctx)?.also { cachedBaseSha = it } ?: "")
        send(hello)
    }

    /** Заполните журналы в кольцевом буфере для seq > с момента (полный с момента = 0). */
    private fun sendLogBatch(since: Long) {
        val snapshot: List<Pair<Long, String>> =
            synchronized(ringLock) {
                ring.filter { it.first > since }
            }
        if (snapshot.isEmpty()) {
            send(JSONObject().put("t", "log.batch").put("lines", JSONArray()))
            return
        }
        // Сегментированная отправка: один JSON контролируется в пределах ~256 КБ (верхний предел сообщения ws на стороне
        // сервера является дружественным)

        snapshot.chunked(200).forEach { chunk ->
            val lines = JSONArray()
            chunk.forEach { (seq, line) ->
                lines.put(JSONObject().put("seq", seq).put("text", line))
            }
            send(JSONObject().put("t", "log.batch").put("lines", lines))
        }
    }

    /** Очистить очередь журналов реального времени (периодически вызываемую потоком подключения). */
    fun flushPendingLogs() {
        val batch =
            synchronized(pendingLock) {
                if (pending.isEmpty()) return
                val taken = ArrayList<Pair<Long, String>>(pending.size)
                while (pending.isNotEmpty()) taken.add(pending.removeFirst())
                taken
            }
        val lines = JSONArray()
        batch.forEach { (seq, line) -> lines.put(JSONObject().put("seq", seq).put("text", line)) }
        send(JSONObject().put("t", "log.batch").put("lines", lines))
    }

    fun send(json: JSONObject) {
        ws?.let { client ->
            if (!client.sendText(json.toString())) {
                // Если передача не удалась, она будет передана повторно путем повторного подключения (путь восстановления
                // кольцевого буфера).
                synchronized(pendingLock) {}
            }
        }
    }

    /** Позволяет странице настроек вручную запускать трансляцию статуса. */
    fun requestStatus() {
        send(JSONObject().put("t", "status").put("state", stateText))
    }

    init {
        // Время очистки журнала в реальном времени: независимый поток демона, 500 мс на пакет
        Thread(
            {
                while (true) {
                    sleepQuiet(500)
                    if (running) flushPendingLogs()
                }
            },
            "bridge-log-flush",
        ).apply {
            isDaemon = true
            start()
        }
    }
}
