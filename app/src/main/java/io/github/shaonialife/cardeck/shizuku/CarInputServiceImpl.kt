package io.github.shaonialife.cardeck.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import java.lang.reflect.Method

/**
 * Реализация Shizuku UserService: запуск в резидентном процессе оболочки (uid 2000),
 * напрямую вводит события касания в указанный дисплей посредством отражения InputManager.injectInputEvent.
 *
 * Местоположение   injectInputEvent меняется в зависимости от версии:
 * — Android 13 и более ранние версии: метод экземпляра InputManager;
 * — Android 14+: перейдите на одноэлементный метод InputManagerGlobal.getInstance().
 * В сигнатуре некоторых версий   имеется третий параметр targetDisplayId, который является адаптивным по типу формального параметра;
 * Старая подпись   (два параметра) использует InputEvent.setDisplayId для указания целевого экрана.
 *
 * Ошибка отражения   (скрытая разница API в ПЗУ), обнаруженная с помощью ping()/diag(), вызывающая сторона возвращается к `input -d`.
 * (перенесено из CarInputServiceImpl эталонного проекта android-auto)
 */
class CarInputServiceImpl(
    private val context: Context,
) : ICarInput.Stub() {
    /**
     * Выключен ли нами физический экран. Службу необходимо восстановить до того, как она совершит самоубийство
     * (самоочистка из-за истечения срока действия из-за обновления APK).
     * В противном случае телефон остановится на «логически активном, физически черном» экране (для восстановления
     * нажмите кнопку питания дважды).
     */
    @Volatile
    private var physicalScreenOff = false

    /**
     * Самоочистка зомби (основная причина, 16 октября 2026 г.): повторное использование Shizuku в соответствии с
     * «компонент + имя процесса + версия (APK LastUpdateTime)»
     * Резидентный процесс UserService      . После переустановки APK на процесс старой версии не влияет
     * принудительная остановка и не перерабатывается Shizuku;
     * А виртуальный экран, который он создает, можно освободить только сам по себе (releaseDisplay проверяет только
     * карту этого процесса) - производительность следующая
     * «После перезапуска обнаружен остаточный экран, нажмите «Очистить», но отпустите 0/N». Этот процесс
     * периодически сравнивает данные, считанные при запуске.
     * LastUpdateTime" не соответствует текущему установочному значению, то есть срок действия его собственного dex
     * истек и он будет передан в процесс переработки системы.
     * Все виртуальные экраны, которые он содержит, освобождаются.
     */
    private val startupLastUpdateTime: Long =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(-1L)

    init {
        fileLog("service start: pkg=${context.packageName} lastUpdate=$startupLastUpdateTime pid=${android.os.Process.myPid()}")
        Thread({
            while (true) {
                try {
                    Thread.sleep(STALE_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now =
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                    }.getOrDefault(-1L)
                if (startupLastUpdateTime > 0 && now > 0 && now != startupLastUpdateTime) {
                    // Если физический экран отключен нами, его необходимо восстановить, прежде чем совершить самоубийство: в
                    // противном случае процесс новой версии еще не начался.

                    // Телефон завис на «логически активном, физически черном» экране (дважды нажмите кнопку питания, чтобы восстановиться).
                    if (physicalScreenOff) {
                        fileLog(
                            "устаревшее самопожертвование: восстановите светлый физический экран (чтобы не " +
                                "останавливаться на черном экране после обновления)",
                        )
                        runCatching { setPhysicalScreenPower(true) }
                    }
                    fileLog(
                        "stale self-reap: lastUpdateTime $startupLastUpdateTime -> $now，" +
                            "выход pid=${android.os.Process.myPid()} (виртуальный экран, который он содержит, " +
                            "перерабатывается вместе с процессом)",
                    )
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@Thread
                }
            }
        }, "carinput-stale-check").apply {
            isDaemon = true
            start()
        }
    }

    private class Injector(
        val receiver: Any,
        val method: Method,
    )

    private val injector: Injector? by lazy {
        val im =
            try {
                context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            } catch (t: Throwable) {
                Log.d(TAG, "проверить исключение getSystemService(INPUT_SERVICE)", t)
                null
            }
        if (im != null) {
            val m = findInjectMethod(im.javaClass.methods)
            if (m != null) return@lazy Injector(im, m)
        }
        try {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val global = cls.getMethod("getInstance").invoke(null)
            val m = findInjectMethod(cls.methods)
            if (m != null) return@lazy Injector(global, m)
        } catch (t: Throwable) {
            Log.d(TAG, "проверка исключения InputManagerGlobal", t)
        }
        null
    }

    private fun findInjectMethod(methods: Array<Method>): Method? =
        methods.firstOrNull {
            it.name == "injectInputEvent" &&
                it.parameterTypes.size >= 2 &&
                it.parameterTypes[0] == InputEvent::class.java &&
                it.parameterTypes[1] == Integer.TYPE
        }

    private val setDisplayIdMethod: Method? by lazy {
        (InputEvent::class.java.methods + MotionEvent::class.java.methods)
            .firstOrNull { it.name == "setDisplayId" && it.parameterTypes.size == 1 }
    }

    private var injectFailLogged = false

    private fun inject(
        event: InputEvent,
        displayId: Int,
    ): Boolean {
        val inj = injector
        if (inj == null) {
            recycle(event)
            return false
        }
        return try {
            setDisplayIdMethod?.invoke(event, displayId)
            val m = inj.method
            val result: Boolean =
                when {
                    m.parameterTypes.size == 2 -> m.invoke(inj.receiver, event, 2) as Boolean
                    m.parameterTypes[2] == java.lang.Long.TYPE -> m.invoke(inj.receiver, event, 2, 0L) as Boolean
                    else -> m.invoke(inj.receiver, event, 2, displayId) as Boolean
                }
            if (!result && !injectFailLogged) {
                injectFailLogged = true
                Log.d(TAG, "injectInputEvent возвращает ложный метод = $m")
            }
            result
        } catch (t: Throwable) {
            if (!injectFailLogged) {
                injectFailLogged = true
                Log.d(TAG, "метод внедрения исключения = ${inj.method}", t)
            }
            false
        } finally {
            recycle(event)
        }
    }

    /** KeyEvent не имеет семантики многократного использования пула (переработка устарела), необходимо перерабатывать только MotionEvent. */
    private fun recycle(event: InputEvent) {
        (event as? MotionEvent)?.recycle()
    }

    /**
     * Вставка ключа  (ручка iDrive → клавиша направления/клавиша подтверждения): используется совместно с touch injectInputEvent,
     * Разница между       заключается в том, что событие заменяется на KeyEvent, а setDisplayId также
     * устанавливается на целевой виртуальный экран.
     */
    override fun keyEvent(
        displayId: Int,
        keyCode: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        if (!inject(down, displayId)) return false
        val up = KeyEvent(now, now + 24, KeyEvent.ACTION_UP, keyCode, 0)
        return inject(up, displayId)
    }

    @Suppress("DEPRECATION")
    private fun build(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent? =
        try {
            val e =
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    x,
                    y,
                    1f,
                    1f,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                )
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            e
        } catch (t: Throwable) {
            Log.d(TAG, "buildMotionEventException", t)
            null
        }

    override fun tap(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        val up = build(now, now + 50, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
        return inject(up, displayId)
    }

    override fun swipe(
        displayId: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val duration = durationMs.coerceIn(50, 1500)
        val down = build(now, now, MotionEvent.ACTION_DOWN, startX.toFloat(), startY.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        val steps = 8
        for (i in 1 until steps) {
            val t = duration * i / steps
            val ix = startX + (endX - startX) * i / steps
            val iy = startY + (endY - startY) * i / steps
            val move = build(now, now + t, MotionEvent.ACTION_MOVE, ix.toFloat(), iy.toFloat()) ?: return false
            if (!inject(move, displayId)) return false
        }
        val up = build(now, now + duration, MotionEvent.ACTION_UP, endX.toFloat(), endY.toFloat()) ?: return false
        return inject(up, displayId)
    }

    private var dragDownTime = 0L
    private var dragDisplay = -1

    override fun pointerDown(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        dragDownTime = now
        dragDisplay = displayId
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        return inject(down, displayId)
    }

    override fun pointerMove(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (dragDisplay != displayId) return false
        val now = SystemClock.uptimeMillis()
        val move = build(dragDownTime, now, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat()) ?: return false
        return inject(move, displayId)
    }

    override fun pointerUp(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (dragDisplay != displayId) return false
        dragDisplay = -1
        val now = SystemClock.uptimeMillis()
        val up = build(dragDownTime, now, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
        return inject(up, displayId)
    }

    // ---- Доверенный виртуальный экран ----

    // VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 30 (@hide/@SystemApi, во время компиляции нельзя заключать в кавычки, запишите исходное значение)
    private val virtualDisplays = HashMap<Int, android.hardware.display.VirtualDisplay>()

    // ---- Стратегия отображения IME ----

    /**
     * Политика IME по умолчанию для доверенного виртуального экрана  — FALLBACK — клавиатура направляется системой
     * на экран мобильного телефона по умолчанию, а сторона автомобиля всегда
     * не виден (17 октября 2026 г., Динчен, нажмите на окно поиска «Настройки» автомобиля, чтобы найти основную
     * причину отсутствия клавиатуры). Явно после создания доверенного экрана
     * Установите LOCAL, чтобы клавиатура отображалась на этом экране и пересылалась на машину вместе с потоком синтеза.
     *
     * Значение перечисления       отражается из констант SystemApi, а не жестко запрограммированного 0: ПЗУ
     * производителя имеет риск волшебного изменения значения перечисления (17 октября 2026 г.)
     * Используйте эту защиту при устранении неполадок      ; фактические измерения этой машины соответствуют AOSP =
     * 0). Если его невозможно прочитать, он возвращается к 0 и записывается в журнал.
     */
    private val imePolicyLocal: Int by lazy {
        val value =
            try {
                Class
                    .forName("android.view.WindowManager")
                    .getField("DISPLAY_IME_POLICY_LOCAL")
                    .getInt(null)
            } catch (t: Throwable) {
                fileLog("Отражение DISPLAY_IME_POLICY_LOCAL не выполнено, резервное значение AOSP 0: $t")
                0
            }
        fileLog("DISPLAY_IME_POLICY_LOCAL=$value")
        value
    }

    /**
     * Агент IWindowManager (отражается именем метода, не зависит от номера транзакции AIDL).
     * В этом ПЗУ используется ServiceManager+Stub.asInterface; WindowManagerGlobal.getWindowManagerService
     * Путь       приведет к завершению процесса (фактическое тестирование состоялось 17 октября 2026 г.), поэтому не используйте его.
     */
    private val windowManagerProxy: Any? by lazy {
        try {
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "window")
            Class
                .forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (t: Throwable) {
            fileLog("Ошибка отражения IWindowManager (политика IME не вступит в силу): $t")
            null
        }
    }

    /**
     * Установите для политики IME доверенного виртуального экрана значение LOCAL (клавиатура отображается на этом экране).
     * Сбой       не влияет на основное звено: худший сценарий тот же, что и до изменения - клавиатура остается на
     * главном экране телефона и невидима со стороны автомобиля.
     */
    private fun applyImePolicyLocal(displayId: Int) {
        val proxy = windowManagerProxy ?: return
        try {
            proxy.javaClass
                .getMethod("setDisplayImePolicy", Integer.TYPE, Integer.TYPE)
                .invoke(proxy, displayId, imePolicyLocal)
            val readBack =
                runCatching {
                    proxy.javaClass
                        .getMethod("getDisplayImePolicy", Integer.TYPE)
                        .invoke(proxy, displayId)
                }.getOrNull()
            fileLog("setDisplayImePolicy($displayId, LOCAL=$imePolicyLocal) ок, читаем обратно=$readBack")
        } catch (t: Throwable) {
            fileLog("setDisplayImePolicy($displayId, LOCAL=$imePolicyLocal) не удалось: $t")
        }
    }

    /** Журнал файла: logcat процесса оболочки шифруется HKS и записывается в /data/local/tmp для чтения adb. */
    private fun fileLog(message: String) {
        try {
            java.io.FileWriter("/data/local/tmp/carinput_svc.log", true).use { w ->
                w
                    .append(
                        android.text.format.DateFormat
                            .format("HH:mm:ss.SSS", System.currentTimeMillis())
                            .toString(),
                    ).append(' ')
                    .append(message)
                    .append('\n')
            }
        } catch (_: Throwable) {
        }
    }

    // WrongConstant: TRUSTED/ALWAYS_UNLOCKED/OWN_DISPLAY_GROUP в флагах является константой @hide.
    // (Реализация во время выполнения была доказана на реальных машинах), модель IntDef lint распознает только общедоступные API.
    @android.annotation.SuppressLint("WrongConstant")
    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        trusted: Boolean,
    ): Int {
        return try {
            fileLog("createDisplay $name ${width}x$height@$dpi start")
            // Ключ: необходимо использовать «Контекст пакета оболочки» — система проверяет, соответствует ли packageName
            // вызывающему uid(2000),

            // Использование контекста этого приложения сообщит об исключении SecurityException («имя пакета должно
            // соответствовать uid владельца»)

            val shellContext =
                try {
                    context.createPackageContext("com.android.shell", 0)
                } catch (t: Throwable) {
                    fileLog("Ошибка createPackageContext(com.android.shell): $t, откатитесь к этому контексту приложения.")
                    context
                }
            fileLog("ctx package=${shellContext.packageName}")
            val dm = shellContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6 (@hide):
            // Если не объявлено, приложение будет видеть конфигурацию «-touch» (без сенсорного устройства) → некоторые
            // приложения (например, Toutiao)

            // Вырезанный макет большой головы в стиле телевизора/настольного компьютера (фактический тест 11 октября 2026
            // г.: причина, по которой область поиска занимает половину экрана).

            //
            // VIRTUAL_DISPLAY_FLAG_PUBLIC (Public API = 1) должен быть объявлен с доверенным экраном (решено 17 октября 2026 г.):
            // Если DisplayDeviceInfo не объявлен с помощью FLAG_PRIVATE (владелец = оболочка 2000), WMS.addWindow
            // Шлюз доступа displayContent.hasAccess(session.mUid) → Display.hasAccess разрешает только частные экраны.
            // Owner/system/root/UID-present — Окно клавиатуры стороннего метода ввода (uid 10xxx) будет
            // ADD_INVALID_DISPLAY(-9) отклонен и никогда не будет добавлен на этот экран (в поле поиска «Настройки» со
            // стороны автомобиля нет клавиатуры)

            // Последняя основная причина: политика была ЛОКАЛЬНОЙ, токен был перемещен на экран, а служба IME также
            // показывала окно, но окно добавить невозможно).

            // Публикация только ослабляет доступ к окну; «Автоматическое зеркалирование общедоступного экрана по умолчанию»
            // в ПЗУ доступно только в том случае, если оно не объявлено.

            // Вступает в силу, когда OWN_CONTENT_ONLY, мы заявили, что это не будет затронуто. Экран док-станции не является
            // доверенным, и сцена ввода отсутствует.

            // Держите это в секрете (и избегайте зависания несвязанных окон).
            val baseFlags =
                (if (trusted) FLAG_TRUSTED or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC else 0) or
                    FLAG_SUPPORTS_TOUCH or
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            // Для доверенного экрана дополнительно требуется «независимая группа дисплеев + всегда разблокирована»:
            // · OWN_DISPLAY_GROUP: виртуальный экран не переходит в спящий режим, когда основной экран телефона спит
            // (физический экран все еще выводит кадры после выключения питания);

            // · ALWAYS_UNLOCKED: Keyguard не будет отображаться на экране, когда устройство заблокировано (в противном
            // случае автомобиль увидит заблокированный/черный экран).

            // OWN_DISPLAY_GROUP мультиплексирует ADD_TRUSTED_DISPLAY (требуется доверенный экран); ВСЕГДА_РАЗБЛОКИРОВАНО
            // Также требуется ADD_ALWAYS_UNLOCKED_DISPLAY — некоторые ПЗУ не имеют оболочки и будут выдавать
            // SecurityException зависает весь экран, поэтому понизьте его версию и повторите попытку.
            var vd: android.hardware.display.VirtualDisplay? = null
            if (trusted) {
                vd =
                    createVirtualDisplayAttempt(
                        dm,
                        name,
                        width,
                        height,
                        dpi,
                        surface,
                        baseFlags or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED,
                        "ДОВЕРЕННАЯ+НЕЗАВИСИМАЯ ГРУППА+ВСЕГДА_РАЗБЛОКИРОВАНА",
                    )
                if (vd == null) {
                    fileLog(
                        "$name не удалось использовать ALWAYS_UNLOCKED — понижение до независимой группы (ROM может не " +
                            "предоставить оболочке это разрешение)",
                    )
                    vd =
                        createVirtualDisplayAttempt(
                            dm,
                            name,
                            width,
                            height,
                            dpi,
                            surface,
                            baseFlags or FLAG_OWN_DISPLAY_GROUP,
                            "Доверенная + независимая группа",
                        )
                }
                if (vd == null) {
                    fileLog("$name не удалось использовать OWN_DISPLAY_GROUP — возврат к базовым флагам")
                    vd = createVirtualDisplayAttempt(dm, name, width, height, dpi, surface, baseFlags, "Надежная основа")
                }
            } else {
                // Экран док-станции (ненадежный) по умолчанию попадает в **группу дисплеев по умолчанию**: когда физический
                // главный экран выключается/блокируется, вся группа гаснет.

                // Производительность автомобиля: «Экран приложения нормальный, а док-панель полностью черная», и все
                // восстанавливается после разблокировки (фактическое тестирование 23 октября 2026 г.).

                // То же понижение на три уровня, что и доверенный экран: Группа + Разблокировка → Группа → Базовый.
                // · AOSP очищает OWN_DISPLAY_GROUP только тогда, когда AUTO_MIRROR, а AUTO_MIRROR очищает OWN_DISPLAY_GROUP
                // только тогда, когда

                //    ПУБЛИЧНЫЙ устанавливается автоматически; док не объявляет PUBLIC, поэтому этот флаг будет сохранен;
                //  · Оба требуют только разрешений (ADD_TRUSTED_DISPLAY / ADD_ALWAYS_UNLOCKED_DISPLAY),
                //    "Недоверенная" семантика дока не изменена (сторонние задачи по-прежнему нельзя загружать).
                vd =
                    createVirtualDisplayAttempt(
                        dm,
                        name,
                        width,
                        height,
                        dpi,
                        surface,
                        baseFlags or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED,
                        "Ненадежный+Независимая группа+ALWAYS_UNLOCKED",
                    )
                if (vd == null) {
                    fileLog(
                        "$name не удалось использовать ALWAYS_UNLOCKED — понижение до независимой группы (ROM может не " +
                            "предоставить оболочке это разрешение)",
                    )
                    vd =
                        createVirtualDisplayAttempt(
                            dm,
                            name,
                            width,
                            height,
                            dpi,
                            surface,
                            baseFlags or FLAG_OWN_DISPLAY_GROUP,
                            "Ненадежная + независимая группа",
                        )
                }
                if (vd == null) {
                    fileLog(
                        "$name не удалось использовать OWN_DISPLAY_GROUP — вернуться к базовым флагам (экран перейдет в " +
                            "спящий режим, когда главный экран выключен)",
                    )
                    vd = createVirtualDisplayAttempt(dm, name, width, height, dpi, surface, baseFlags, "ненадежный")
                }
            }
            val created = vd
            val id = created?.display?.displayId ?: -1
            fileLog("createVirtualDisplay -> vd=$created id=$id")
            // Считайте обратное подтверждение (учитывайте фактическое измерение 23 октября 2026 г.): Когда флаг вступит в
            // силу, флаги слоя отображения должны быть

            // ALWAYS_UNLOCKED (1 << 9) — Система отключится автоматически без OWN_DISPLAY_GROUP
            // ALWAYS_UNLOCKED (создан успешно без исключений, но без исключений прочитан обратно), должен быть найден путем
            // обратного чтения.

            if (id >= 0) {
                val realFlags =
                    try {
                        dm.getDisplay(id)?.flags ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                val effective = (realFlags and 0x200) != 0
                fileLog(
                    "ALWAYS_UNLOCKED прочитайте display.flags=0x${realFlags.toString(16)} ->" +
                        if (effective) "Вступить в силу" else "Неэффективно (блокировка экрана по-прежнему повлияет на экран)",
                )
            }
            if (created == null || id < 0) {
                Log.w(TAG, "createDisplay $name -> null")
                return -1
            }
            synchronized(virtualDisplays) { virtualDisplays[id] = created }
            // Только доверенные экраны могут устанавливать политики IME (WMS генерирует SecurityException для ненадежных
            // экранов), и это необходимо:

            // Экран док-станции содержит только собственную DockActivity и не имеет сцен ввода.
            if (trusted) applyImePolicyLocal(id)
            Log.i(TAG, "createDisplay $name id=$id size=${width}x$height@$dpi")
            id
        } catch (t: Throwable) {
            fileLog("createDisplay $name failed: $t")
            Log.w(TAG, "createDisplay $name failed: $t")
            -1
        }
    }

    override fun resizeDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        dpi: Int,
    ): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays[displayId] } ?: return false
        return try {
            vd.resize(width, height, dpi)
            Log.i(TAG, "resizeDisplay id=$displayId -> ${width}x$height@$dpi")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "resizeDisplay $displayId failed: $t")
            false
        }
    }

    override fun releaseDisplay(displayId: Int): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays.remove(displayId) }
        if (vd == null) {
            // Не в этой карте процесса: этот экран был создан другим (более старой версией) процессом UserService, и только
            // создатель может его освободить.

            // См. CarInputClient.reapStaleUserServices (завершить процесс удержания) в нижней части приложения.
            fileLog("ReleaseDisplay id=$displayId -> Не удерживается этим процессом (не может быть выпущено между процессами)")
            return false
        }
        return try {
            vd.release()
            Log.i(TAG, "releaseDisplay id=$displayId")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "releaseDisplay $displayId failed: $t")
            false
        }
    }

    /** Создать виртуальный экран; в случае сбоя (включая SecurityException / ROM молча возвращает ноль), он возвращает ноль и оставляет след. */
    private fun createVirtualDisplayAttempt(
        dm: android.hardware.display.DisplayManager,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
        label: String,
    ): android.hardware.display.VirtualDisplay? =
        try {
            fileLog("createDisplay($name) $label flags=0x${flags.toString(16)}")
            dm.createVirtualDisplay(name, width, height, dpi, surface, flags, null, null)
        } catch (t: Throwable) {
            fileLog("createDisplay($name) $label исключение: $t")
            null
        }

    // ----Физический источник питания главного экрана (настоящий экран; система бодрствует) ----

    /**
     * выполняет аппаратное выключение и включение питания для **физического главного экрана** (так же, как scrcpy `-S/--turn-screen-off`):
     * не нажимает кнопку питания (которая блокирует экран), а напрямую настраивает setDisplayPowerMode SurfaceFlinger.
     * Система по-прежнему считает, что экран включен → не блокирует экран, не входит в режим дремоты, а виртуальный
     * экран продолжает кадрировать.
     */
    override fun setPhysicalScreenPower(on: Boolean): Boolean {
        val ok = setPanelPower(on)
        if (ok) physicalScreenOff = !on
        fileLog("setPhysicalScreenPower($on) ok=$ok")
        return ok
    }

    /**
     * Унифицированное ядро панели управления питанием  (точка слияния двух наборов реализаций, 24 октября 2026 г.):
     * Трехстороннее перечисление токена       сортируется по «измеренной скорости прохождения» (любое ПЗУ может
     * работать, пока доступен один путь);
     * использует отражение SurfaceControl.setDisplayPowerMode при выключении/включении питания.
     */
    private fun setPanelPower(on: Boolean): Boolean {
        val token =
            physicalDisplayToken() ?: run {
                fileLog("setPanelPower($on) не удалось: встроенный токен экрана невозможно получить при каждом входе")
                return false
            }
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        return try {
            Class
                .forName("android.view.SurfaceControl")
                .getMethod("setDisplayPowerMode", IBinder::class.java, Integer.TYPE)
                .invoke(null, token, mode)
            true
        } catch (t: Throwable) {
            fileLog("setPanelPower($on) не удалось: $t")
            false
        }
    }

    /**
     * токен физического главного экрана, трехсторонний последовательный откат (разные ПЗУ имеют разные аспекты
     * удаления и модификации, наиболее стабильные во всем тесте):
     * ① SurfaceControl.getInternalDisplayToken — единственный доступный в реальном тесте Honor A17 (SDK 37),
     * ставится первым (MagicOS сохраняет этот «старый» API);
     * ② Загрузчик классов DisplayControl — традиционный вход в SDK 34+ (то же самое, что scrcpy: в Services.jar
     * Класс       требует, чтобы ClassLoaderFactory + android_servers JNI был видимым);
     * ③ SurfaceControl.getPhysicalDisplayIds/getPhysicalDisplayToken — старый вход для Android 11–14.
     * Первый идентификатор физического экрана — это встроенный экран (AOSP согласен, что первый элемент массива — это экран по умолчанию).
     */
    // BlockedPrivateApi/DEPRECATION: этот метод намеренно скрывает/отменяет API (UserService запускается в оболочке).
    // Домены не контролируются скрытыми API-интерфейсами прикладного уровня; поведение трехстороннего резервного
    // копирования во время выполнения было доказано).

    @android.annotation.SuppressLint("BlockedPrivateApi")
    @Suppress("DEPRECATION")
    private fun physicalDisplayToken(): IBinder? {
        // ① getInternalDisplayToken (SurfaceControl некоторых ПЗУ все еще сохраняется)
        runCatching {
            val sc = SurfaceControl::class.java
            (sc.getDeclaredMethod("getInternalDisplayToken").invoke(null) as? IBinder)?.let {
                fileLog("token via getInternalDisplayToken")
                return it
            }
        }.onFailure { fileLog("Ошибка записи getInternalDisplayToken: ${it.message}") }
        // ② DisplayControl (новая система: методы перенесены из SurfaceControl)
        displayControlClass?.let { cls ->
            runCatching {
                val ids = cls.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
                val id = ids.firstOrNull() ?: return@runCatching
                (cls.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType!!).invoke(null, id) as? IBinder)?.let {
                    fileLog("token via DisplayControl id=$id")
                    return it
                }
            }.onFailure { fileLog("Ошибка записи токена DisplayControl: ${it.message}") }
        }
        // ③ getPhysicalDisplayIds/getPhysicalDisplayToken (Android 11–14)
        runCatching {
            val sc = SurfaceControl::class.java
            val ids = sc.getDeclaredMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val id = ids.firstOrNull() ?: return@runCatching
            (sc.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, id) as? IBinder)?.let {
                fileLog("token via SurfaceControl.getPhysicalDisplayToken id=$id")
                return it
            }
        }.onFailure { fileLog("Ошибка записи getPhysicalDisplayToken: ${it.message}") }
        return null
    }

    /** Загрузчик классов DisplayControl (для истинного экрана): работает только в Shizuku UserService (uid оболочки). */
    private val displayControlClass: Class<*>? by lazy {
        try {
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val create =
                factory.getDeclaredMethod(
                    "createClassLoader",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    ClassLoader::class.java,
                    Integer.TYPE,
                    java.lang.Boolean.TYPE,
                    String::class.java,
                )
            val classpath = System.getenv("SYSTEMSERVERCLASSPATH")
            if (classpath.isNullOrEmpty()) {
                fileLog("SYSTEMSERVERCLASSPATH пуст — невозможно загрузить DisplayControl")
                return@lazy null
            }
            val loader =
                create.invoke(
                    null,
                    classpath,
                    null,
                    null,
                    ClassLoader.getSystemClassLoader(),
                    0,
                    true,
                    null,
                ) as ClassLoader
            val cls = loader.loadClass("com.android.server.display.DisplayControl")
            // Статические методы JNI регистрируются в libandroid_servers.so; они должны быть загружены в этот ClassLoader
            registerAndroidServersLibrary(cls)
            fileLog("ДисплейКонтроль готов.")
            cls
        } catch (t: Throwable) {
            fileLog("Не удалось загрузить DisplayControl (SDK ${Build.VERSION.SDK_INT}): $t.")
            null
        }
    }

    /**
     * регистрирует JNI libandroid_servers.so в ClassLoader, где находится [cls] (тот же путь, что и scrcpy).
     *
     * Этот класс работает только в Shizuku UserService (app_process оболочки uid 2000) и не зависит от процесса приложения.
     * Ограничение скрытого API      ; BlockedPrivateApi lint определяется процессом приложения targetSdk, поэтому здесь он исключен.
     */
    @SuppressLint("BlockedPrivateApi")
    private fun registerAndroidServersLibrary(cls: Class<*>) {
        val loadLibrary0 =
            Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        loadLibrary0.isAccessible = true
        loadLibrary0.invoke(Runtime.getRuntime(), cls, "android_servers")
    }

    override fun ping(): Boolean = injector != null

    override fun setScreenPower(on: Boolean): Boolean {
        val ok = setPanelPower(on)
        fileLog("setScreenPower($on) ok=$ok")
        return ok
    }

    override fun diag(): String =
        "injector=" + (injector?.let { "${it.receiver.javaClass.name} ${it.method}" } ?: "null") +
            " setDisplayId=${setDisplayIdMethod != null}"

    override fun pid(): Int = android.os.Process.myPid()

    companion object {
        private const val TAG = "CarInputSvc"

        /** Цикл проверки самоочистки: после переустановки APK старый процесс завершится самостоятельно не позднее чем через 15 секунд (стоимость — всего один запрос в личку). */
        private const val STALE_CHECK_INTERVAL_MS = 15_000L

        /** VIRTUAL_DISPLAY_FLAG_TRUSTED (@hide; определение AOSP = 1 << 10 = 0x400). */
        private const val FLAG_TRUSTED = 1 shl 10

        /** VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH (@hide; определение AOSP = 1 << 6 = 0x40). */
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6

        /**
         * VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP (@hide; определение AOSP = 1 << 11 = 0x800).
         * Виртуальный экран           переходит в собственную группу отображения: когда главный экран мобильного
         * телефона находится в спящем режиме или выключен, он не отключается.
         * требует ADD_TRUSTED_DISPLAY (требуется доверенный экран) — см. проверку DisplayManagerService.
         */
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11

        /**
         * VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED (@hide; определение AOSP = 1 << 12 = 0x1000).
         * Keyguard не отображается на экране, когда экран заблокирован. **Также требуется ADD_ALWAYS_UNLOCKED_DISPLAY**,
         * Некоторым ПЗУ не предоставляется оболочка — необходимо, чтобы у createDisplay была возможность перейти на
         * более раннюю версию и повторить попытку, иначе создание всего экрана завершится неудачно.
         */
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12

        /** SurfaceControl отображает режим питания: ВЫКЛ = оборудование выключено, НОРМАЛЬНЫЙ = светится нормально. */
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2

        /** SurfaceControl отображает режим питания (@hide; AOSP определяет OFF=0/NORMAL=2, используемый в качестве резервного при сбое отражения). */
    }
}
