package io.github.shaonialife.cardeck.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.shaonialife.cardeck.util.Bg
import moe.shizuku.server.IShizukuService
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Шлюз Shizuku + выполнение оболочки + привязка CarInput UserService.
 * Активация   (беспроводная отладка, Android 11+, root не требуется): Параметры разработчика → Беспроводная
 * отладка → Сопряжение → Приложение Shizuku. Нажмите, чтобы запустить.
 * (перенесено из эталонного проекта android-auto)
 */
object ShizukuGate {
    enum class State { NOT_INSTALLED, NOT_RUNNING, UNAUTHORIZED, READY }

    /** Код запроса авторизации Shizuku (по нему фильтруется обратный вызов результата авторизации). */
    const val PERMISSION_REQUEST_CODE = 101

    /** Имя пакета приложения Shizuku (открыть/обнаружить общий доступ). */
    const val PACKAGE_NAME = "moe.shizuku.privileged.api"

    fun state(context: Context): State {
        return try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return State.NOT_INSTALLED
        }.let {
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) return State.NOT_RUNNING
                if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    State.READY
                } else {
                    State.UNAUTHORIZED
                }
            } catch (_: Throwable) {
                State.NOT_RUNNING
            }
        }
    }

    fun requestPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /**
     * Загрузочное копирование, когда оно не готово (чистая функция, однократная проверка).
     *
     * Три состояния неготовности имеют совершенно разные методы обработки (установка/запуск/авторизация), смешанные в одно предложение.
     * «Шизуку не готова» Пользователи понятия не имеют, с чего начать — это, скорее всего, вопрос, с которого их
     * спросят после того, как они поделятся им с друзьями.
     */
    internal fun guidanceOf(state: State): ShizukuGuidance =
        when (state) {
            State.NOT_INSTALLED ->
                ShizukuGuidance(
                    title = "Сначала установите Shizuku",
                    message =
                        "Для подключения к автомобилю нужен доступ Shizuku к shell: создание виртуальных " +
                            "экранов, передача касаний и чтение состояния окон выполняются через него.\n\n" +
                            "Установите Shizuku и запустите службу по официальной инструкции " +
                            "(через беспроводную отладку, без root), затем вернитесь и нажмите «Проверить снова».",
                    primaryLabel = null,
                )

            State.NOT_RUNNING ->
                ShizukuGuidance(
                    title = "Служба Shizuku не запущена",
                    message =
                        "Shizuku установлен, но служба не запущена, поэтому подключение не будет работать " +
                            "(на экране автомобиля не будет изображения и касаний).\n\n" +
                            "Откройте Shizuku и нажмите «Запустить». После перезагрузки телефона это нужно " +
                            "сделать снова. Затем вернитесь и нажмите «Проверить снова».",
                    primaryLabel = "Открыть Shizuku",
                )

            State.UNAUTHORIZED ->
                ShizukuGuidance(
                    title = "CarDeck ещё не разрешено использовать Shizuku",
                    message =
                        "Служба Shizuku запущена, но CarDeck ещё не получил разрешение, поэтому подключение " +
                            "не будет работать (на экране автомобиля не будет изображения и касаний).\n\n" +
                            "Нажмите «Запросить разрешение», затем «Разрешить» в появившемся окне. Если вы " +
                            "ранее отказали, разрешите CarDeck вручную в Shizuku → «Приложения».",
                    primaryLabel = "Запросить разрешение",
                )

            State.READY -> ShizukuGuidance("Shizuku готов", "", null)
        }
}

/** Загрузить контент, когда Шизуку не готов (см. [ShizukuGate.guidanceOf]). */
internal data class ShizukuGuidance(
    val title: String,
    val message: String,
    /** null = На этом компьютере нет исполняемого файла основного действия (некуда перейти, если он не установлен). */
    val primaryLabel: String?,
)

/** Выполните команду как оболочку (эквивалент оболочки adb). Shizuku API 13 необходимо вызвать newProcess через привязку IshizukuService. */
object Shell {
    class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    /** Совокупное количество вызовов `Shell.exec` основным потоком (в целях диагностики; см. защиту в [exec]). */
    @Volatile
    var mainThreadViolations: Int = 0
        private set

    /**
     * вызывает аварийный выход оболочки (SessionController.appendLog) в основном потоке.
     * Длительность одной оболочки обычно составляет порядка 100 мс, а таймаут может достигать нескольких секунд. Для
     * ANR достаточно нескольких строк——
     * Этот тип проблемы встречается только на реальных машинах (logcat не читается в HKS), и необходимо вводить журналы файлов.
     */
    @Volatile
    var violationSink: ((String) -> Unit)? = null

    fun exec(
        cmd: String,
        timeoutMs: Long = 6000L,
    ): Result {
        // **Оболочка запрета потоков пользовательского интерфейса**: Каждый исполнитель представляет собой новый процесс
        // Shizuku + ожидание процесса.

        // Здесь нет принудительного перехвата (что сделает недействительными изначально доступные функции), а запись и
        // предупреждение - исторически

        // Из-за этого возникло две ошибки ANR на реальных устройствах (отключить очистку, флажок горизонтального экрана).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            mainThreadViolations++
            violationSink?.invoke(
                "SHELL: ⚠ Основной поток вызывает оболочку (время $mainThreadViolations) cmd=${cmd.take(60)}." +
                    "(ограничение $timeoutMs мс) — вместо этого следует использовать Bg.run/фоновый поток",
            )
        }
        val binder =
            rikka.shizuku.Shizuku.getBinder()
                ?: return Result(-1, "Шизуку не на связи")
        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)

            Bg.run("shell-stderr") {
                try {
                    process.errorStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes()
                    }
                } catch (_: Exception) {
                }
            }

            val output =
                try {
                    process.inputStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes().decodeToString().trim()
                    }
                } catch (_: Exception) {
                    ""
                }

            if (!process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.toString())) {
                process.destroy()
            }
            val exit =
                try {
                    process.exitValue()
                } catch (_: Exception) {
                    -1
                }
            Result(exit, output)
        } catch (e: Exception) {
            Result(-1, "Не удалось выполнить команду: ${e.message ?: e.javaClass.simpleName}.")
        }
    }
}

/** Управление привязкой CarInput UserService: после однократной привязки все сенсорные инъекции напрямую подключаются к резидентному процессу оболочки (уровень миллисекунд). */
object CarInputClient {
    @Volatile
    var service: ICarInput? = null
        private set

    /**
     * ожидает готовности UserService (блокируется, **может вызываться только в фоновых потоках**).
     *
     * Служба все еще может быть создана при первой активации сеанса       - в это время, если сразу будет
     * определено, что «доверенный экран недоступен» и будет возвращена сторона приложения,
     * Экран       PUBLIC потеряет преимущество IME/touch доверенного экрана, а резервный путь будет в большей
     * степени зависеть от UserService.
     * (22 октября 2026 г. K60E: Ошибка внедрения при первом касании, в журнале «Создано три экрана (откат)»).
     *
     * @return готовое обслуживание; возвращает значение null после тайм-аута (вызывающая сторона обрабатывает его в
     * соответствии с исходной логикой возврата).
     */
    fun awaitReady(
        context: Context,
        timeoutMs: Long = 3_000L,
    ): ICarInput? {
        service?.let { return it }
        ensure(context)
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            service?.let { return it }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return service
            }
            ensure(context)
        }
        return service
    }

    @Volatile
    private var binding = false

    @Volatile
    private var appContext: Context? = null

    /** Параметр UserService (для отмены привязки необходим тот же экземпляр). */
    @Volatile
    private var userServiceArgs: rikka.shizuku.Shizuku.UserServiceArgs? = null

    /** Экспорт журнала диагностики (журнал доступа к файлу, logcat не читается при шифровании HKS). */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(
        message: String,
        force: Boolean = false,
    ) {
        Log.i(TAG, message)
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - diagAt < 1_000L) return
        diagAt = now
        logSink?.invoke("SHIZUKU: $message")
    }

    private var binderListenerAdded = false

    /**
     * перезапускает оставшиеся процессы UserService из старой версии и возвращает фактическое количество уничтожений
     * (0, если условий недостаточно).
     *
     * Shizuku повторно использует резидентный процесс оболочки по принципу «компонент + имя процесса + версия
     * (lastUpdateTime)» — после переустановки APK
     * Принудительная остановка не влияет на процесс старой версии       и не перерабатывается Шизуку; только
     * процесс, создавший виртуальный экран, может
     * Версия       (releaseDisplay проверяет только карту этого процесса), производительность такая: «После
     * перезапуска обнаружен остаточный экран, нажмите «Очистить», чтобы очистить его»
     * выпускает 0/N". Здесь мы перечисляем процессы :car_input и уничтожаем все, кроме текущего связанного процесса
     * - сохраняется только старый процесс.
     * остаточный экран, процесс перезапускает экран и освобождает его (инъекция касания не прерывается, текущий
     * экран сеанса принадлежит связанному процессу).
     * сотрудничает с истекшим самовозвратом на стороне UserService (устаревшим самовозвратом CarInputServiceImpl) двойной страховкой.
     *
     * Блокирующий вызов       (включая командную строку ps/kill) должен выполняться в фоновом потоке; он
     * автоматически запускается один раз, когда привязка успешна.
     */
    fun reapStaleUserServices(): Int {
        val bound = service ?: return 0
        val keepPid =
            try {
                bound.pid()
            } catch (t: Throwable) {
                // В старом dex нет транзакции pid() (обычно этого не происходит: Shizuku повторно использует версию, а связанная
                // должна быть текущей версией)

                diag("reap пропущен: pid() недоступен (${t.javaClass.simpleName})")
                return 0
            }
        val procName = (appContext?.packageName ?: return 0) + ":car_input"
        val ps = Shell.exec("ps -A -o PID,ARGS", 5_000L)
        if (!ps.ok) {
            diag("reap пропущен: PS не удалось (${ps.output.take(40)})")
            return 0
        }
        val stale =
            ps.output
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    val pid = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    if (parts.getOrNull(1) == procName && pid != keepPid) pid else null
                }.toList()
        if (stale.isEmpty()) return 0
        val killed = stale.count { pid -> Shell.exec("kill -9 $pid", 3_000L).ok }
        // принудительное: окно регулирования в 1 с сразу после привязки поглотит этот ключевой результат (необходимо
        // проверить при устранении неполадок)

        diag(
            "Старая версия процесса UserService $killed/${stale.size} была переработана (соответственно будет освобожден " +
                "ее остаточный виртуальный экран)",
            force = true,
        )
        return killed
    }

    /**
     * Физический главный экран  выключен/включен (настоящий экран, такой же, как scrcpy `-S`). Вернуть false в случае неудачи:
     * ROM не предоставляет оболочке соответствующие разрешения, или SDK 34+ не может получить токен отображения.
     */
    fun setPhysicalScreenPower(on: Boolean): Boolean =
        try {
            service?.setPhysicalScreenPower(on) ?: false
        } catch (t: Throwable) {
            diag("setPhysicalScreenPower($on) не удалось: ${t.javaClass.simpleName} ${t.message}")
            false
        }

    /**
     * Кнопки  выводятся на указанный дисплей (ручка iDrive → клавиши направления/клавиши подтверждения).
     * принимает событие injectInputEvent из UserService (уровень миллисекунд); в случае сбоя вызывающая сторона
     * возвращается к оболочке `input -d`.
     */
    fun keyEvent(
        displayId: Int,
        keyCode: Int,
    ): Boolean =
        try {
            service?.keyEvent(displayId, keyCode) ?: false
        } catch (t: Throwable) {
            diag("keyEvent($displayId,$keyCode) не удалось: ${t.javaClass.simpleName} ${t.message}")
            false
        }

    private val conn =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                // ⚠️ Этот обратный вызов отправляется библиотекой Shizuku в **основной поток** (используется ShizukuServiceConnection
                // новый обработчик(Looper.getMainLooper())). Поэтому здесь нельзя блокировать вызовы:
                // ping() — это межпроцессный вызов связывателя, который может быть заблокирован, когда процесс службы занят
                // (создание виртуального экрана/внедрение).

                // Сотни миллисекунд или даже дольше. Как только основной поток будет заблокирован, все последующие обратные
                // вызовы будут поставлены в очередь, и привязка будет всегда истинной.

                // ——22.10.2026 «Неизбежный тупик» K60E — именно эта цепочка.
                val bound = ICarInput.Stub.asInterface(binder)
                service = bound
                binding = false
                bindingSince = 0L
                Bg.run("shizuku-ping") {
                    val ok =
                        try {
                            bound?.ping() == true
                        } catch (_: Throwable) {
                            false
                        }
                    diag("connected ping=$ok", force = true)
                    if (!ok) {
                        // Будут удалены только результаты этой привязки (в течение этого периода повторная привязка могла быть успешной)
                        if (service === bound) {
                            service = null
                            binding = false
                        }
                    } else {
                        // После успешной привязки остаточный процесс старой версии будет плавно переработан (удерживаемый им виртуальный
                        // экран не может быть освобожден между процессами.

                        // Только переработка процесса может заставить экран исчезнуть). Выполнение фонового потока: внутри содержит
                        // оболочку ps/kill.

                        reapStaleUserServices()
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                diag("service disconnected", force = true)
                service = null
                binding = false
                bindingSince = 0L
            }
        }

    /** Время последней попытки привязки (для предотвращения часто повторяющихся попыток). */
    @Volatile
    private var lastBindAttemptAt = 0L

    /** Время инициации этой привязки (используется для определения задержки обратного вызова; 0 = привязка в данный момент не выполняется). */
    @Volatile
    private var bindingSince = 0L

    /**
     * Убедитесь, что UserService привязан. Может вызываться часто: служба пуста и автоматически перепривязывается
     * >1,5 с с момента последней попытки.
     * (После перезагрузки мобильного телефона/перезапуска Shizuku подшивка может быть потеряна, а процесс останется,
     * поэтому соединение необходимо активно восстанавливать).
     *
     * **Этот метод может быть вызван в основном потоке** (обнаружение запуска MainActivity), а метод bindUserService
     * Синхронный вызов связывателя       — унифицированное изменение фона, основной поток переходит из рук в руки только один раз.
     */
    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app
        addBinderListeners()
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // Не делайте вызовы связывателя в основном потоке: сам обратный вызов также берет на себя основной Looper,
            // блокировка здесь равна самоблокировке

            Bg.run("shizuku-bind") { ensureInternal(app) }
            return
        }
        ensureInternal(app)
    }

    private fun addBinderListeners() {
        if (binderListenerAdded) return
        binderListenerAdded = true
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListener {
                // Сервер Shizuku (пере)активен: старое соединение должно быть недействительным и перепривязка разрешена.
                diag("Биндер Shizuku готов — перепривязываем UserService", force = true)
                binding = false
                bindingSince = 0L
                lastBindAttemptAt = 0L
                appContext?.let { Bg.run("shizuku-bind") { ensureInternal(it) } }
            }
            rikka.shizuku.Shizuku.addBinderDeadListener {
                diag("Привязка Shizuku отключена (выход/перезапуск сервера) — сенсорная инъекция временно недоступна", force = true)
                service = null
                binding = false
                bindingSince = 0L
            }
        } catch (_: Throwable) {
        }
    }

    private fun ensureInternal(app: Context) {
        if (service != null) return
        val now = android.os.SystemClock.uptimeMillis()
        // Дросселирование в течение 1,5 с: привязка произойдет автоматически, пока Шизуку заморожена системой, и ее
        // необходимо восстановить как можно скорее после оттаивания.

        if (now - lastBindAttemptAt < BIND_THROTTLE_MS) return
        // **Повторная инициализация** только в случае «слишком долгого ожидания». Старая реализация зависала после
        // исправления на 1,5 секунды и блокировалась перед повторной привязкой.

        // unbindUserService(remove=true) — — Это приведет к непосредственному завершению только что запущенного служебного процесса:
        // Подключенный обратный вызов Шизуку отправляет сообщения в основной поток → обратный вызов будет задерживаться,
        // когда основной поток занят →

        // Через 1,5 секунды считается, что он «застрял» → завершить процесс → повторно запустить → обратный вызов снова
        // задерживается → снова убить…

        // Это самоподдерживающийся джиттер, который никогда не сходится (22 октября 2026 г., реальная машина K60E: отказ
        // впрыска более 1700 раз,

        // Кисть «Привязка застрявшего состояния была снята» каждые 2–3 секунды).
        // Теперь: окно ожидания уменьшено до 8 секунд, и **никогда не завершайте здесь процесс обслуживания** (перепривязка идемпотентна,
        // Шизуку снова вызовет уже запущенную службу связывания).
        if (binding) {
            val waited = if (bindingSince == 0L) 0L else now - bindingSince
            if (!shouldRebind(binding = true, waitedMs = waited, stuckTimeoutMs = BIND_STUCK_TIMEOUT_MS)) {
                // Все еще ожидаю в обычном режиме: без регистрации или прерывания
                return
            }
            binding = false
            diag(
                "Ожидание привязки ${waited}ms все еще не перезванивается - повторите попытку (не завершайте процесс службы)",
                force = true,
            )
        }
        lastBindAttemptAt = now
        val gate = ShizukuGate.state(app)
        if (gate != ShizukuGate.State.READY) {
            diag("Привязка паузы: состояние=$gate")
            return
        }
        binding = true
        bindingSince = now
        try {
            val args =
                userServiceArgs ?: rikka.shizuku.Shizuku
                    .UserServiceArgs(
                        // Имя пакета берется из среды выполнения (не кодируйте жестко): оно будет автоматически следовать после
                        // изменения имени.
                        ComponentName(app.packageName, CarInputServiceImpl::class.java.name),
                    ).processNameSuffix("car_input")
                    .debuggable(true)
                    // Номер версии взят из APK LastUpdateTime (см. аннотацию serviceVersion).
                    .version(serviceVersion())
                    .also { userServiceArgs = it }
            // **Never unbindUserService(remove=true)**: этот параметр уничтожит процесс удаленной службы.
            // Старая реализация вызывает его перед каждой повторной попыткой → убивает вновь созданный процесс → обратный
            // вызов никогда не ждет

            // → Повторите попытку и завершите еще раз, вызывая самоподдерживающееся дрожание (одна из основных причин
            // неизбежного тупика при внедрении K60E 2026-10-22).

            // Сама по себе служба связывания BindUserService является идемпотентной: когда служба уже запущена, Shizuku
            // снова свяжет существующую связующую программу.

            // Обратный вызов того же ServiceConnection (установлен для удаления дубликатов), предварительное отвязывание не требуется.
            rikka.shizuku.Shizuku.bindUserService(args, conn)
            diag("Запрошена служба BindUserService (ожидание обратного вызова, тайм-аут ${BIND_STUCK_TIMEOUT_MS}ms)")
        } catch (e: Throwable) {
            binding = false
            bindingSince = 0L
            diag("bindUserService failed: ${e.message}")
        }
    }

    /**
     * Версия UserService = APK LastUpdateTime (секунды). Шизуку нажмите «компонент + имя процесса + версия», чтобы повторно использовать
     * Процесс резидентной службы      : переустановка APK не изменит процесс, если номер версии жестко
     * запрограммирован — повторно используемый старый процесс по-прежнему запускает старый dex.
     * Новый код не вступает в силу автоматически (фактическое тестирование 15 октября 2026 г.: после добавления
     * SUPPORTS_IME на доверенный экран флаги по-прежнему имеют старые значения)
     * The root cause of       0x448; и принудительная остановка не может уничтожить эти процессы оболочки, и они
     * будут накапливаться все больше и больше).
     * LastUpdateTime при переустановке изменится → будет запущен новый процесс; константа, когда APK остается
     * неизменным → резидентный процесс будет повторно использоваться в обычном режиме.
     */
    private fun serviceVersion(): Int =
        try {
            val pm = appContext?.packageManager ?: return 15

            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(appContext!!.packageName, 0)
            (info.lastUpdateTime / 1000L).toInt()
        } catch (_: Throwable) {
            15 // Итог: связано с исторической последовательностью номеров рукописных версий, гарантированно не менее 14.
        }

    private const val TAG = "CarInputClient"

    /** Минимальный интервал между двумя инициациями привязки (привязка завершится автоматически, если Шизуку заморожена, и ее необходимо восстановить как можно скорее после разморозки). */
    private const val BIND_THROTTLE_MS = 1_500L

    /**
     * — это верхний предел времени ожидания для определения того, что «эта привязка застряла».
     *
     * должна быть намного больше, чем обычная задержка обратного вызова: подключенный обратный вызов Шизуку
     * отправляется в основной поток, в то время как сам процесс службы
     * для появления + загрузка декса (пакет отладки работает медленнее). Старое значение 1,5 с имеет ту же длину,
     * что и окно регулирования, поэтому «инициировать → 1,5 с позже».
     * Самоподдерживающееся дрожание       при приговоре к смертной казни → завершить процесс и перезапустить (22
     * октября 2026 г. K60E измеряется каждые 2–3 секунды).
     */
    private const val BIND_STUCK_TIMEOUT_MS = 8_000L
}

/**
 * Чистая функция  «должна ли эта привязка считаться зависшей и повторно переданной» (ядро конечного автомата привязки [CarInputClient]).
 *
 * разделен на чистые функции, чтобы обеспечить возвращение настоящей машины (Redmi K60E 2026-10-22): старая реализация теперь
 * безоговорочно определяется как зависший, когда «≥1,5 с с момента последнего запуска», **и это окно имеет ту же
 * величину, что и обычная задержка обратного вызова** ——
 * Подключенный Шизуку обратный вызов отправляет в основной поток, и процесс обслуживания также должен порождать + загружать dex.
 * 1.5s is often not enough. After death, the old code will still be `unbindUserService(remove=true)`
 * Сервисный процесс   убит, поэтому «инициировать → умереть → убить → инициировать снова» никогда не сходится, и
 * внедрение касания начинается с первого раза.
 * будет уничтожен, как только к нему прикоснутся (в журнале будет отображаться сообщение «Состояние зависания
 * привязки было освобождено» каждые 2–3 секунды).
 *
 * Привязка   @param Выполняется ли в настоящее время привязка?
 * @param waitedMs Как долго ожидается привязка (игнорируется, если [binding] имеет значение false)
 * @return true = should be resent; false = продолжать ждать (или вы можете инициировать его, если вы уже простаиваете)
 */
internal fun shouldRebind(
    binding: Boolean,
    waitedMs: Long,
    stuckTimeoutMs: Long,
): Boolean = !binding || waitedMs >= stuckTimeoutMs
