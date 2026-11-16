package io.github.shaonialife.cardeck.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.util.Bg

/**
 * Всегда включен + Темный экран: решение для обхода экрана блокировки, заменяющее экран ожидания.
 *
 * В сценарии «автомобиль-машина» экран блокировки телефона блокирует ввод с экранов, отличных от стандартных
 * (жесткая граница системы), и блокировку безопасности невозможно снять командой.
 * Благодаря этому решению экран никогда не выключается автоматически (время ожидания экрана устанавливается на
 * максимальное значение и восстанавливается до исходного значения при отключении),
 * будет отображать почти черный полноэкранный режим после 45 секунд бездействия (имитируя внешний вид
 * покоящегося экрана). Коснитесь любого места телефона, чтобы удалить его и перезапустить таймер.
 *
 * Сенсорный датчик  : окно размером 1 пиксель + FLAG_WATCH_OUTSIDE_TOUCH, захват реальных прикосновений в любом
 * месте экрана (включая сторонние приложения),
 * используется только для сброса времени и не перехватывает и не потребляет никаких событий.
 */
object ScreenDimmer {
    private const val TAG = "ScreenDimmer"
    private const val IDLE_TIMEOUT_MS = 45_000L
    private const val TIMEOUT_MAX = 2_147_483_647

    /** Controlled by settings (on by default). */
    @Volatile
    var enabled: Boolean = true

    /**
     * автоматический главный выключатель темного экрана (45 секунд без рабочего экрана/закрытие темного экрана).
     * remote debugging variant in Application
     * При запуске для       установлено значение false — для устранения неполадок необходимо смотреть на экран
     * телефона в режиме реального времени. «Черный экран через некоторое время потемнеет» просто блокирует процесс устранения неполадок.
     * site (2026-10-24 user request); всегда включено, это не влияет, ложный экран вручную ([enterDimNow]’s
     * force path) is also not affected.
     */
    @Volatile
    var autoDimAllowed: Boolean = true

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var handler: Handler? = null
    private var dimView: View? = null
    private var watchView: View? = null

    @Volatile
    private var dimShown = false

    /**
     * Истинный и ложный экран: физическая панель отключена нами (SurfaceControl POWER_MODE_OFF).
     * восстановит электропитание на замыкающем пути только тогда, когда этот объект установлен; когда система
     * действительно находится в спящем режиме (нажмите кнопку питания),
     * Трансляция       SCREEN_OFF очищается - после этого панелью управляет DisplayPowerController, не хватайтесь за нее.
     */
    @Volatile
    private var panelOffByUs = false

    private var screenOffReceiver: BroadcastReceiver? = null

    /**
     * Есть ли в данный момент активный сеанс скринкастинга.
     *
     * **Автоматическое затемнение экрана и постоянный свет действуют только во время сеанса** (отзыв пользователя о
     * реальном тестировании от 19 октября 2026 г.):
     * прикрепится при запуске приложения (чтобы сделать «ручной фейковый экран» доступным в любое время), но сторона
     * автомобиля будет отключена
     * (`VideoSender.onEnded`) не выпустит VirtualLauncher, а таймер темного экрана на 45 секунд по-прежнему…
     * ведет себя так: "сессия была отключена в течение длительного времени, а экран телефона через некоторое время
     * внезапно стал черным". So automatic scheduling must be session driven,
     * Руководство [enterDimNow] не подпадает под это ограничение (явное действие пользователя).
     */
    @Volatile
    private var sessionActive = false

    private val showRunnable = Runnable { showDim() }

    /** Начало/окончание сеанса (вызывается SessionController при запуске и остановке потоковой передачи). */
    fun setSessionActive(active: Boolean) {
        if (sessionActive == active) return
        sessionActive = active
        val ctx = appContext ?: return
        val h = handler
        if (active) {
            if (!enabled) return
            enableStayAwake(ctx)
            h?.post { schedule() }
            Log.i(
                "CarPlaySender",
                "DIMMER: сеанс начат; затемнение после ${IDLE_TIMEOUT_MS / 1000} с бездействия",
            )
        } else {
            h?.removeCallbacks(showRunnable)
            // Если по окончании сеанса темный экран закрывается (автомобиль автоматически отключается), немедленно уберите
            // его, чтобы пользователь мог видеть обычный интерфейс телефона.

            h?.post { hideDim() }
            restorePanelIfNeeded()
            restoreTimeout(ctx)
            Log.i("CarPlaySender", "ДИММЕР: сеанс завершен – автоматическое затемнение остановлено, время ожидания экрана восстановлено")
        }
    }

    fun attach(context: Context) {
        if (appContext != null) {
            // Прикреплено: Если в этот раз он переключен на «Включить», он будет включен всегда, и автоматическое отсчет
            // времени будет завершено (сенсорный датчик был подвешен ранее)

            if (enabled && sessionActive) {
                enableStayAwake(context)
                handler?.post { schedule() }
            }
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Отсутствие разрешения на плавающее окно, темный экран/сенсорный датчик недоступен (всегда включено недоступно)")
            appContext = context.applicationContext
            windowManager = null
            handler = Handler(Looper.getMainLooper())
            return
        }
        appContext = context.applicationContext
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        // Всегда включено только во время сеанса: присоединение не меняет время ожидания экрана на «никогда» при запуске приложения.
        // В противном случае экран телефона никогда не выключится, когда он не подключен (его также не поставят обратно
        // после отключения автомобиля).

        if (enabled && sessionActive) enableStayAwake(context)
        handler?.post {
            addWatcher()
            registerScreenOffReceiver(context)
            if (enabled && sessionActive) schedule()
        }
        if (!enabled) {
            Log.i("CarPlaySender", "ДИММЕР: сенсорный датчик включен (всегда включен/темный экран выключен)")
        } else if (sessionActive) {
            Log.i(
                "CarPlaySender",
                "DIMMER: запущено; затемнение после ${IDLE_TIMEOUT_MS / 1000} с бездействия",
            )
        } else {
            Log.i("CarPlaySender", "DIMMER: ожидание начала сеанса; автозатемнение пока отключено")
        }
    }

    /** Отключите всегда включенный/темный экран, но сохраните сенсорные датчики (нет необходимости перестраивать окно при его повторном включении). */
    fun disableDim() {
        enabled = false
        val ctx = appContext ?: return
        restoreTimeout(ctx)
        handler?.removeCallbacks(showRunnable)
        handler?.post { hideDim() }
        restorePanelIfNeeded()
        Log.i("CarPlaySender", "ДИММЕР: всегда включен/темный экран выключен (сенсорный щуп сохранен)")
    }

    /**
     * Должен ли действовать автоматический темный экран (чистая функция, см. [shouldAutoDim]).
     *
     * был разобран для одиночного тестирования: если что-то из этих трех неверно, то на темном экране играть нельзя.
     * Ошибка, протестированная пользователями, именно
     * "Сеанс завершился (sessionActive=false), но таймер все еще на месте" - экран телефона без причины становится черным.
     */
    fun shouldAutoDim(
        enabled: Boolean,
        sessionActive: Boolean,
        attached: Boolean,
    ): Boolean = enabled && sessionActive && attached

    fun detach() {
        val h = handler
        handler = null
        // Одновременно с этим должна быть удалена метка сеанса: в противном случае при следующем подключении (запуске
        // приложения без подключения) будет использован предыдущий раунд сеанса.

        // истинное и немедленное планирование возвращает к старой проблеме «автоматического затемнения, даже если сеанса нет».
        sessionActive = false
        restorePanelIfNeeded()
        appContext?.let { unregisterScreenOffReceiver(it) }
        h?.removeCallbacksAndMessages(null)
        val wm = windowManager
        val dim = dimView
        val watch = watchView
        dimView = null
        watchView = null
        dimShown = false
        windowManager = null
        h?.post {
            dim?.let { v -> runCatching { wm?.removeView(v) } }
            watch?.let { v -> runCatching { wm?.removeView(v) } }
        }
        val ctx = appContext
        appContext = null
        if (ctx != null) restoreTimeout(ctx)
        Log.i("CarPlaySender", "DIMMER: остановлено (восстановление тайм-аута экрана)")
    }

    /** Готов ли темный экран (присоединение завершено и есть возможность плавания окон). */
    fun isAttached(): Boolean = handler != null && windowManager != null

    /** Вручную введите ложный экран (активируется кнопкой домашней страницы; нажмите на темный экран или в любом месте экрана телефона, чтобы восстановить). */
    fun enterDimNow() {
        val h = handler ?: return
        // Разрешение отсутствовало при прикреплении и было предоставлено позже: добавьте здесь оконный менеджер и сенсорный датчик.
        if (windowManager == null) {
            val ctx = appContext ?: return
            if (!android.provider.Settings.canDrawOverlays(ctx)) return
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            h.post { addWatcher() }
        }
        // Чтобы ручной режим сна имел смысл, экран должен оставаться включенным (иначе вы не сможете «нажать, чтобы
        // возобновить», если действительно заснете)

        appContext?.let { if (enabled) enableStayAwake(it) }
        h.post { showDim(force = true) }
    }

    /** Вызывается при физическом прикосновении (любое приложение/интерфейс) или щелчке по темному экрану: сброс автоматического времени темного экрана. */
    fun onUserInteraction() {
        val h = handler ?: return
        if (panelOffByUs) {
            // Просыпайтесь от настоящего и поддельного экрана: сначала включите панель, а затем уберите темный экран (иначе
            // пользователь увидит черный экран)

            restorePanelIfNeeded()
        }
        if (dimShown) hideDim()
        if (enabled && sessionActive) schedule()
    }

    private fun schedule() {
        if (!sessionActive) return
        handler?.removeCallbacks(showRunnable)
        handler?.postDelayed(showRunnable, IDLE_TIMEOUT_MS)
    }

    private fun showDim(force: Boolean = false) {
        // Автоматический путь должен иметь активный сеанс: темный экран никогда не появится после завершения сеанса
        // (особенно, когда сторона автомобиля отключена)

        if (!force && !shouldAutoDim(enabled, sessionActive, isAttached())) return
        // Вариант удаленной отладки: автоматический поддельный экран/темный экран не будет запланирован (вам нужно
        // смотреть на экран для устранения неполадок), пути вручную не ограничены.

        if (!force && !autoDimAllowed) return
        val ctx = appContext
        if (ctx == null) {
            android.util.Log.w("ScreenDimmer", "showDim: appContext пуст (присоединение не выполняется)")
            return
        }
        val wm = windowManager
        if (wm == null) {
            android.util.Log.w("ScreenDimmer", "showDim: windowManager пуст (отсутствуют разрешения для плавающего окна?)")
            return
        }
        if (dimShown) return
        val view =
            View(ctx).apply {
                setBackgroundColor(Color.argb(0xF8, 0, 0, 0))
                isClickable = true
                setOnClickListener { onUserInteraction() }
            }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            )
        runCatching { wm.addView(view, params) }
            .onSuccess {
                dimView = view
                dimShown = true
                Log.i("CarPlaySender", "DIMMER: отображается темный экран.")
                // Настоящие и поддельные экраны: после установки темного экрана отключите физический экран напрямую. Система
                // остается интерактивной (установлен тайм-аут «всегда включен»).

                // Потому что он никогда по-настоящему не заснет), на виртуальный экран это вообще не влияет; сенсорный
                // просыпается через щуп.

                setPanelPower(false)
            }.onFailure { Log.w(TAG, "Не удалось добавить темную штору: ${it.message}") }
    }

    private fun hideDim() {
        val view = dimView ?: return
        dimView = null
        dimShown = false
        runCatching { windowManager?.removeView(view) }
    }

    /** Сенсорный датчик размером 1 пиксель: фиксируйте события ВНИЗ в любом месте экрана, чтобы сбросить время без использования событий. */
    private fun addWatcher() {
        val ctx = appContext ?: return
        val wm = windowManager ?: return
        val view =
            View(ctx).apply {
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN ||
                        event.action == MotionEvent.ACTION_OUTSIDE
                    ) {
                        onUserInteraction()
                    }
                    false
                }
            }
        val params =
            WindowManager
                .LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
        runCatching { wm.addView(view, params) }
            .onSuccess { watchView = view }
            .onFailure { Log.w(TAG, "Не удалось добавить сенсорный датчик: ${it.message}") }
    }

    // ---- Истинные и ложные экраны: отключение слоя SurfaceControl/подача питания на физический экран (через Shizuku UserService) ----

    /** Выключите/восстановите питание панели. AIDL блокирует вызовы связующих и всегда выполняется в фоновом режиме; в случае неудачи он остается в темном состоянии (старое поведение). */
    private fun setPanelPower(on: Boolean) {
        Bg.run("dimmer-panel") {
            val svc = CarInputClient.service
            if (svc == null) {
                Log.i(TAG, "Пропуск питания панели (UserService не готов, только темный экран)")
                return@run
            }
            val ok =
                runCatching { svc.setScreenPower(on) }
                    .onFailure { Log.w(TAG, "Вызов setScreenPower($on) не удался: $it") }
                    .getOrDefault(false)
            if (ok) {
                panelOffByUs = !on
                Log.i("CarPlaySender", "ДИММЕР: источник питания панели ${if (on) "включено" else "выключено"} (Истинный и ложный экран)")
            } else {
                Log.i(TAG, "Сбой блока питания панели $on (ПЗУ не поддерживается? Сохраняйте состояние темного экрана)")
            }
        }
    }

    /** Единый выход из пути закрытия: будет восстановлено только «то питание, которое мы отключили», чтобы избежать конкуренции с системным управлением питанием панели. */
    private fun restorePanelIfNeeded() {
        if (panelOffByUs) setPanelPower(true)
    }

    /**
     * Выход  «True Screen во время подключения» (вызов пути восстановления SessionController):
     * убирает темный экран + восстанавливает питание панели + очищает флаг выключения (идемпотентный - просто
     * безвредно работает, когда питание не выключено).
     * Разница между       и [onUserInteraction]: здесь **запрограммированное закрытие** (выключение/завершение
     * сеанса/начало восстановления),
     * не перепланирует автоматическое время; темный экран обеспечивает обнаружение касания во время отключения
     * электроэнергии (нажмите на темный экран, чтобы проснуться, фактический сенсорный тест пользователя
     * Микросхема       продолжает работать после отключения питания панели), поэтому темную завесу необходимо
     * снимать одновременно с восстановлением питания.
     */
    fun exitPanelOff() {
        val h = handler ?: return
        panelOffByUs = false
        h.post {
            if (dimShown) hideDim()
            setPanelPower(true)
        }
    }

    /**
     * истинный сон (нажмите кнопку питания) и разблокировка закрытия жизненного цикла:
     * - SCREEN_OFF: Панель возвращается к управлению питанием системы (снимает метку), убирает темный экран и
     * останавливает таймер - сам интерфейс экрана блокировки
     * «Затенение + Антислучайное касание», темный экран станет лишь остатком, требующим большего количества нажатий
     * после разблокировки (пользовательский тест 2026-10-23).
     * - USER_PRESENT: изменение автоматической синхронизации ложного экрана после разблокировки - разблокировка по
     * отпечатку пальца/лицу не генерирует события касания экрана,
     * Невозможно получить зонд. Если вы не восполните это здесь, таймер остановится после разблокировки.
     */
    private fun registerScreenOffReceiver(context: Context) {
        if (screenOffReceiver != null) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            if (panelOffByUs) {
                                panelOffByUs = false
                                Log.i(
                                    TAG,
                                    "Система действительно спит - панель возвращается в режим управления питанием " +
                                        "системы (отметка на экране сна снята)",
                                )
                            }
                            if (dimShown) {
                                handler?.removeCallbacks(showRunnable)
                                hideDim()
                                Log.i(TAG, "True Sleep — удален темный экран (экран блокировки берет на себя функцию маскировки)")
                            }
                        }

                        Intent.ACTION_USER_PRESENT -> {
                            if (enabled && sessionActive && isAttached()) {
                                schedule()
                                Log.i(TAG, "Разблокировать - перенести автоматическое время экрана праздничного экрана")
                            }
                        }
                    }
                }
            }
        screenOffReceiver = receiver
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
    }

    private fun unregisterScreenOffReceiver(context: Context) {
        screenOffReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenOffReceiver = null
    }

    // ---- Экран всегда включен: измените тайм-аут системного экрана на «никогда» и восстанавливайте его при отключении ----

    private fun enableStayAwake(ctx: Context) {
        Bg.run("dimmer-stayawake") {
            val current =
                Shell
                    .exec("settings get system screen_off_timeout")
                    .output
                    .trim()
                    .toIntOrNull()
            if (current == null) {
                Log.w(TAG, "Не удалось прочитать тайм-аут экрана (Шизуку не готова?)")
                return@run
            }
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (current < TIMEOUT_MAX && !prefs.contains("screen_off_timeout_backup")) {
                prefs.edit().putInt("screen_off_timeout_backup", current).apply()
            }
            Shell.exec("settings put system screen_off_timeout $TIMEOUT_MAX")
            Log.i("CarPlaySender", "DIMMER: установите для тайм-аута экрана значение «никогда» (исходное значение $current).")
        }
    }

    private fun restoreTimeout(ctx: Context) {
        Bg.run("dimmer-restore") {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.contains("screen_off_timeout_backup")) return@run
            val backup = prefs.getInt("screen_off_timeout_backup", 60_000)
            prefs.edit().remove("screen_off_timeout_backup").apply()
            Shell.exec("settings put system screen_off_timeout $backup")
            Log.i("CarPlaySender", "DIMMER: тайм-аут экрана снова установлен на $backup.")
        }
    }
}
