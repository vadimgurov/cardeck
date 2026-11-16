package io.github.shaonialife.cardeck.input

import android.content.Context
import io.github.shaonialife.cardeck.input.TouchReports.Contact
import io.github.shaonialife.cardeck.launcher.CardGesture
import io.github.shaonialife.cardeck.launcher.LetterboxHintController
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.resolveCardGesture
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.util.Bg

/**
 * Сенсорная маршрутизация: добавьте контакт «автомобиль-машина» hidSendReport в виртуальный экран со стороны
 * автомобиля-машины (режим невидимого экрана).
 *
 * делится по координате x главного экрана: x < ширина док-станции → экран док-станции; остальное → экран
 * приложения (x ширина дока перевода).
 * внедряется через Shizuku UserService (реальные координаты 1:1 и миллисекундная инъекция).
 *
 * (original "desktop" (Presentation direct projection) and "mirror" (barrier-free continueStroke) two modes
 * был удален по требованию 10 октября 2026 г. )
 */
object TouchRouter {
    /** Экспорт журнала диагностики (полученный MainActivity в файл журнала). */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - diagAt < 1000L) return
        diagAt = now
        logSink?.invoke("TOUCH: $message")
    }

    private var pressed = false
    private var pressedDisplay = -1
    private var failStreak = 0

    /** Наложение состояния жеста карты (исключительное касание после нажатия карты, больше не внедряется в приложение). */
    private var overlayPressed = false

    /** Состояние закрепленной маршрутизации ручки разделенного экрана (весь поток касания фиксируется на экране приложения маршрутизации после нажатия на панель ручки). */
    private var splitHandlePressed = false

    /**
     * Дроссель впрыска MOVE: 0 = выключено; >0 ходов, прошедших меньше указанного количества миллисекунд с момента последней инъекции
     * напрямую объединяется и отбрасывается (события границы вниз/вверх и межэкранное переключение никогда не
     * регулируются, а конечная позиция переносится вверх).
     * Symptoms: Interaction lags but normal frame rate (event dispatching piles up). Настройка панели «Дисплей»
     * регулируется, по умолчанию — 24 мс.
     */
    @Volatile
    var moveThrottleMs: Long = 24L

    @Volatile
    private var throttleInited = false

    private var lastInjectAt = 0L
    private var throttledCount = 0
    private var lastThrottleLogAt = 0L

    // Коэффициент преобразования координат касания автомобиля с машиной (система координат просмотра уведомлений,
    // например 1160×540) в координаты потока (1450×674 после масштабирования).

    // Когда масштабирование разрешения составляет ≠100 %, поток больше, чем представление. Невыполнение
    // масштабирования приведет к смещению общего щелчка вправо вниз (шунт док-станции также смещен).

    @Volatile
    private var coordScaleX = 1f

    @Volatile
    private var coordScaleY = 1f

    /** Установите при установлении сеанса: Размер просмотра (отображение уведомлений автомобиля) → Размер потока (кодирование/виртуальный экран). */
    fun setCoordinateScale(
        viewW: Int,
        viewH: Int,
        streamW: Int,
        streamH: Int,
    ) {
        coordScaleX = if (viewW > 0) streamW.toFloat() / viewW else 1f
        coordScaleY = if (viewH > 0) streamH.toFloat() / viewH else 1f
    }

    fun onContact(
        context: Context,
        contact: Contact,
    ) {
        // Занятость свободного слота (не нажата и 0,0) игнорируется.
        if (!contact.down && contact.xPixels == 0 && contact.yPixels == 0) return
        val px = (contact.xPixels * coordScaleX).toInt().coerceAtLeast(0)
        val py = (contact.yPixels * coordScaleY).toInt().coerceAtLeast(0)

        // Карточка наложения (подсказка «Принудительное горизонтальное положение экрана не вступает в силу») отдает
        // приоритет прикосновению к потреблению: она рисуется на черной рамке области приложения.

        // Попадание должно быть определено до внедрения, иначе касание проникнет в базовое приложение.
        run {
            val visible = LetterboxHintController.isVisible()
            val hit = visible && LetterboxHintController.contains(px.toFloat(), py.toFloat())
            when (resolveCardGesture(visible, overlayPressed, contact.down, hit, appGestureActive = pressed)) {
                CardGesture.NOT_HANDLED -> overlayPressed = false
                CardGesture.CONSUME -> {
                    overlayPressed = contact.down
                    return
                }
                CardGesture.TAP -> {
                    overlayPressed = false
                    LetterboxHintController.onTap()
                    return
                }
            }
        }

        val launcher = VirtualLauncher
        val dockW = launcher.dockWidthPx
        if (dockW <= 0 || launcher.appDisplayId < 0 || launcher.dockDisplayId < 0) return
        var svc = CarInputClient.service
        if (svc == null) {
            // Активно запускать повторную привязку (привязка теряется после перезагрузки телефона/перезапуска Shizuku).
            // **должен быть в фоновом режиме**: убедитесь, что внутри системы есть синхронный вызов
            // связывателяbindUserService, и эта статья

            // Прикосновение происходит из потока событий iAP2; основной поток еще хуже (обратный вызов Shizuku также
            // проходит через основной поток и блокируется).

            Bg.run("touch-rebind") { CarInputClient.ensure(context) }
            svc = CarInputClient.service
        }
        if (svc == null) {
            failStreak++
            // Один отчет в первый раз и каждые 200 раз: На реальном устройстве происходят десятки касаний в секунду, и
            // чистка их по одному приведет к заливке журнала.

            if (failStreak == 1 || failStreak % 200 == 0) {
                diag(
                    "Служба внедрения недоступна ($failStreak раз подряд, фоновая перепривязка;" +
                        "Если ошибка по-прежнему не работает, откройте приложение Shizuku или разрешите его работу в " +
                        "фоновом режиме в настройках системы.)",
                )
            }
            return
        }
        failStreak = 0
        // Координаты главного экрана → ветка:
        //  Горизонтальный экран: вертикальная полоса слева от дока (x <dockW), приложение справа (x Translation DockW)
        //  Вертикальный экран: горизонтальная полоса внизу дока (y > appHeight), приложение сверху.
        // Экран карты (панель управления/полноэкранный режим, имеет приоритет над областью применения): полный экран =
        // все области применения; разделенный экран = область плитки в правой части дока.

        // Экран карты и поток создаются в соотношении 1:1, а координаты переводятся напрямую; остальное — активный экран
        // приложения (включая преобразование коэффициента суперсэмплинга).

        var inDock: Boolean
        var targetDisplay = -1
        var localX = 0
        var localY = 0
        var routedToMap = false
        // Липкая маршрутизация ручки разделенного экрана: нажмите и приземлитесь внутри руля → весь поток касания
        // фиксируется на экране приложения маршрутизации (экране, на котором расположена сетка)

        // пока не поднимут. При быстром перетаскивании ручка будет отставать от пальца и координаты выйдут за пределы
        // ручки (или даже за край плитки карты).

        // Покадровое изменение решения в соответствии с координатами приведет к повторному примечанию на разных экранах
        // (экран приложения pointerUp + экран карты pointerDown), прерывание перетаскивания

        // И исказите карту (проверка плана и решение 21 октября 2026 г.). Режим аналогичен жесту наложения карты выше.
        val handlePin =
            splitHandlePressed ||
                (contact.down && !pressed && launcher.dashHandleHit(px.toFloat(), py.toFloat()))
        if (handlePin) {
            splitHandlePressed = contact.down
            inDock = false
            targetDisplay =
                if (launcher.gridDisplayId >= 0) launcher.gridDisplayId else launcher.activeAppDisplayId()
            if (launcher.dockAtBottom) {
                localX = px
                localY = py
            } else {
                val scale = launcher.gridScale().coerceAtLeast(1f)
                localX = ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        } else if (launcher.dockAtBottom) {
            val appH = launcher.appHeightPx
            inDock = py >= appH
            // Ячейка карты (верхняя половина области приложения; полный экран = вся область приложения). Координаты касания/потока сверху:
            // прямоугольник плитки py ∈ [CARD_MARGIN_PX, +mapTileHeight], на экране y = py-margin (вычет верхнего поля)
            if (!inDock &&
                launcher.mapDisplayId >= 0 &&
                (launcher.mapFullscreen || launcher.dashboardMode) &&
                launcher.tileSlideFraction <= 0.01f &&
                px < launcher.appAreaWidth &&
                (
                    launcher.mapFullscreen ||
                        (py >= CARD_MARGIN_PX && py < CARD_MARGIN_PX + launcher.mapTileHeight)
                )
            ) {
                inDock = false
                targetDisplay = launcher.mapDisplayId
                localX = px
                localY = if (launcher.mapFullscreen) py else py - CARD_MARGIN_PX
                routedToMap = true
            }
            if (!routedToMap) {
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.appDisplayId
                localX = px
                localY = if (inDock) py - appH else py
            }
        } else {
            inDock = px < dockW
            if (!inDock &&
                launcher.mapDisplayId >= 0 &&
                (launcher.mapFullscreen || launcher.dashboardMode) &&
                launcher.tileSlideFraction <= 0.01f
            ) {
                val tileX = px - dockW
                val tileVisibleW = if (launcher.mapFullscreen) Int.MAX_VALUE else launcher.mapTileWidth
                if (tileX in 0 until tileVisibleW) {
                    inDock = false
                    targetDisplay = launcher.mapDisplayId
                    // Размер экрана карты динамически изменяется в соответствии с макетом (разделенный экран = размер плитки, полный
                    // экран = размер области приложения),

                    // Содержимое экрана — это координатная область: смещение относительно левого края области приложения — это
                    // координата внутри экрана, которую можно преобразовать напрямую.

                    localX = tileX
                    localY = py
                    routedToMap = true
                }
            }
            if (!routedToMap) {
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.activeAppDisplayId()
                val scale = if (inDock) 1f else launcher.activeAppScale().coerceAtLeast(1f)
                localX = if (inDock) px else ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        }
        if (targetDisplay < 0) {
            diag("Целевой экран недействителен. Dock=${launcher.dockDisplayId} app=${launcher.appDisplayId}.")
            return
        }
        // Регулирование MOVE: последовательные ходы на одном экране объединяются и отбрасываются, когда интервал
        // недостаточен (конечный автомат не перемещается, последняя позиция

        // переносятся последующими ходами/вверх). Переключение вниз/вверх/поперек экрана не регулирует скорость.
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (!throttleInited) {
            throttleInited = true
            moveThrottleMs =
                DisplayPrefs
                    .moveThrottleMs(context)
                    .toLong()
        }
        if (moveThrottleMs > 0 &&
            contact.down &&
            pressed &&
            pressedDisplay == targetDisplay &&
            nowMs - lastInjectAt < moveThrottleMs
        ) {
            throttledCount++
            if (nowMs - lastThrottleLogAt > 5_000) {
                logSink?.invoke(
                    "TOUCH: вступает в силу регулирование ${moveThrottleMs}ms – кумулятивные слияния $throttledCount раз",
                )
                throttledCount = 0
                lastThrottleLogAt = nowMs
            }
            return
        }
        try {
            val ok: Boolean =
                when {
                    contact.down && !pressed -> {
                        pressed = true
                        pressedDisplay = targetDisplay
                        svc.pointerDown(targetDisplay, localX, localY)
                    }

                    contact.down && pressedDisplay != targetDisplay -> {
                        // Перетаскивание по экранам: сначала закройте перо на старом экране, затем нажмите на новом экране.
                        runCatching { svc.pointerUp(pressedDisplay, 0, 0) }
                        pressedDisplay = targetDisplay
                        svc.pointerDown(targetDisplay, localX, localY)
                    }

                    contact.down -> svc.pointerMove(targetDisplay, localX, localY)

                    else -> {
                        pressed = false
                        svc.pointerUp(pressedDisplay, localX, localY)
                    }
                }
            if (ok) lastInjectAt = nowMs
            if (!ok) {
                diag(
                    "Впрыск отклонен disp=$targetDisplay ($localX,$localY)" +
                        "down=${contact.down} pressed=$pressed pressedDisplay=$pressedDisplay",
                )
            } else if (contact.down) {
                diag("Ввести ОК disp=$targetDisplay ($localX,$localY)")
            }
        } catch (e: Exception) {
            diag("Исключение инъекции: ${e.message}")
        }

        // Режим информационной панели: касания фрагментов карты одновременно передаются детектору «Нажатие → Полноэкранная карта».
        // (Логика внедрения не затрагивается; касание = быстрое касание, панорамирование/двойное нажатие/двойное нажатие
        // пальца и другие жесты карты не срабатывают).

        // Включено как для горизонтальных, так и для вертикальных экранов: tileX передает внутренние координаты плитки
        // (используется только для определения смещения, значение начальной точки не чувствительно).

        val tileHit =
            !inDock &&
                targetDisplay == launcher.mapDisplayId &&
                launcher.dashboardMode &&
                !launcher.mapFullscreen
        updateMapTileTap(
            launcher,
            contact,
            tileHit,
            if (launcher.dockAtBottom) px - CARD_MARGIN_PX else px - dockW,
        )
    }

    fun reset() {
        pressed = false
        pressedDisplay = -1
        overlayPressed = false
        splitHandlePressed = false
        cancelMapTileTap()
        tapDragSlot = -1
        coordScaleX = 1f
        coordScaleY = 1f
    }

    // ---- Обнаружение плитки карты панели мониторинга «быстрый щелчок → полноэкранная карта» ----
    // Решение по клику: нажмите → поднимите ≤400 мс и смещение ≤30 пикселей; нет второго нажатия в течение 300 мс после подъема
    // (Окно подтверждения позволяет избежать случайного прикосновения к карте при двойном щелчке для
    // масштабирования). Жесты карты, такие как перетаскивание плиток/два пальца, не срабатывают.

    private const val TAP_MAX_MS = 400L
    private const val TAP_MAX_MOVE_PX = 30
    private const val TAP_CONFIRM_MS = 300L
    private val tapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var tapArmSlot = -1

    /** Жест удержания (слот), который был расценен как перетаскивание: недействителен, пока он не будет поднят - в противном случае долгое скольжение отменяется → повторно активируется.
     */
    private var tapDragSlot = -1
    private var tapDownAt = 0L
    private var tapX = 0
    private var tapY = 0
    private var tapConfirm: Runnable? = null

    private fun updateMapTileTap(
        launcher: VirtualLauncher,
        contact: Contact,
        inTile: Boolean,
        tileX: Int,
    ) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!launcher.dashboardMode || launcher.mapFullscreen) {
            cancelMapTileTap()
            return
        }
        if (contact.down) {
            if (inTile && tapConfirm != null) {
                // Повторное нажатие во время подтверждения: не открывать карту на весь экран.
                cancelMapTileTap()
                return
            }
            if (tapArmSlot < 0 && inTile && tapDragSlot < 0) {
                tapArmSlot = contact.slot
                tapDownAt = now
                tapX = tileX
                tapY = contact.yPixels
            } else if (tapArmSlot >= 0 &&
                (
                    !inTile ||
                        contact.slot != tapArmSlot ||
                        kotlin.math.abs(tileX - tapX) + kotlin.math.abs(contact.yPixels - tapY) > TAP_MAX_MOVE_PX
                )
            ) {
                // Перетаскивание/перетаскивание плитки/два пальца: весь жест отключен до тех пор, пока его не поднимут (см.
                // комментарий TapDragSlot)

                tapDragSlot = tapArmSlot
                cancelMapTileTap()
            }
        } else {
            if (tapDragSlot >= 0) {
                if (contact.slot == tapDragSlot) tapDragSlot = -1
                return
            }
            if (tapArmSlot >= 0 && contact.slot == tapArmSlot) {
                val ok =
                    inTile &&
                        now - tapDownAt <= TAP_MAX_MS &&
                        kotlin.math.abs(tileX - tapX) + kotlin.math.abs(contact.yPixels - tapY) <= TAP_MAX_MOVE_PX
                cancelMapTileTap()
                if (ok) {
                    tapConfirm =
                        Runnable {
                            tapConfirm = null
                            if (launcher.dashboardMode && !launcher.mapFullscreen) {
                                logSink?.invoke("ПУСК: Нажмите на карту → полноэкранная карта.")
                                launcher.setMapFullscreen(true)
                            }
                        }
                    tapHandler.postDelayed(tapConfirm!!, TAP_CONFIRM_MS)
                }
            }
        }
    }

    private fun cancelMapTileTap() {
        tapArmSlot = -1
        tapConfirm?.let { tapHandler.removeCallbacks(it) }
        tapConfirm = null
    }
}
