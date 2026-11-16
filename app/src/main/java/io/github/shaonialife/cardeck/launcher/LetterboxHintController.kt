package io.github.shaonialife.cardeck.launcher

import android.content.Context
import io.github.shaonialife.cardeck.launcher.settings.AppOrientationCompat
import io.github.shaonialife.cardeck.render.OverlayCard
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.util.Bg

/**
 * Подсказка «Принудительное горизонтальное расположение экрана не действует»: обнаружение на основе событий →
 * обнаружение черных краев → создание карточек → перезапуск одним щелчком мыши.
 *
 * Зачем он нужен   (18.10.2026 Почитай новое появление реальной машины): Сначала запускается приложение,
 * подключается к машине и потом пишется
 * `am compat` При принудительном горизонтальном экране, хотя система завершает процесс, остальные задачи будут
 * восстановлены в соответствии с **старой конфигурацией**
 * ActivityRecord(`displayChangedWithoutRestart=true`), затем `overrideOrientation`
 * по-прежнему ПОРТРЕТ, `isLetterboxRunning=true`, `letterboxReason=FIXED_ORIENTATION`.
 * перезапишет и перестроит в новом направлении только после принудительной остановки и перезапуска.
 *
 * Обнаружение   срабатывает только в точках событий (установление соединения, запуск приложения, полноэкранный
 * режим карты, после перезапуска) и не осуществляет опрос;
 * Решение   и геометрические расчеты являются чистыми функциями (см. LetterboxHint.kt) и могут измеряться индивидуально.
 */
object LetterboxHintController {
    private const val TAG = "LetterboxHint"

    /** Пользователь нажал «Закрыть»/подтвердил, что восстановление невозможно завершить: в этом сеансе никаких запросов не будет. */
    private val dismissed = HashSet<String>()

    private enum class State { NORMAL, BUSY, FAILED }

    @Volatile
    private var state = State.NORMAL

    /** Цель, соответствующая текущему приглашению (ноль = не отображается). */
    private data class Target(
        val packageName: String,
        val component: String,
        val displayId: Int,
    )

    @Volatile
    private var target: Target? = null

    /** Прямоугольник карты (координаты выходного экрана, начало координат сверху); коснитесь и нажмите, чтобы использовать его напрямую. */
    @Volatile
    private var cardLeft = 0f

    @Volatile
    private var cardTop = 0f

    @Volatile
    private var cardRight = 0f

    @Volatile
    private var cardBottom = 0f

    @Volatile
    private var probeInFlight = false

    fun isVisible(): Boolean = target != null

    /** Поражена ли карта (координаты выходного экрана; вызываются TouchRouter перед внедрением). */
    fun contains(
        x: Float,
        y: Float,
    ): Boolean = target != null && x >= cardLeft && x < cardRight && y >= cardTop && y < cardBottom

    /** Карта была нажата: NORMAL=перезапустить приложение; ЗАНЯТО = игнорировать; НЕУДАЧА = закрыть. */
    fun onTap() {
        when (state) {
            State.NORMAL -> {
                val t = target ?: return
                restart(t)
            }

            State.BUSY -> Unit

            State.FAILED -> dismiss()
        }
    }

    fun dismiss() {
        target?.let { synchronized(dismissed) { dismissed.add(it.packageName) } }
        clear()
    }

    /** Вернитесь на рабочий стол/завершите сеанс: очистите запрос и восстановите статус. */
    fun clear() {
        target = null
        state = State.NORMAL
        cardLeft = 0f
        cardTop = 0f
        cardRight = 0f
        cardBottom = 0f
        VirtualLauncher.setOverlayCard(null)
    }

    /** Новый сеанс: очистите запись «Больше нет запросов для этого сеанса». */
    fun resetSession() {
        synchronized(dismissed) { dismissed.clear() }
        clear()
    }

    /**
     * Обнаружение точки события  (вызывается после установления соединения/запуска приложения/полного экрана карты).
     * Выполнить в фоновом режиме:
     * один раз `dumpsys activity activities` занимает около 20 мс, но все равно не должен связывать поток пользовательского интерфейса.
     *
     * [displayIdHint]: вызывающий абонент **знает** целевой экран (входящий запуск запуска приложения/карты).
     * Нажмите, когда не идет передача
     * Синтезатор       в настоящее время определяет активный экран, но переключение экранов должно ждать первого
     * кадра целевого экрана (до 2 с), и обнаружение завершается раньше, чем переключение.
     * увидит неправильный экран и зачитает пустые результаты (приложения с медленными первыми кадрами, такие как
     * Toutiao, однажды из-за этого пропустили подсказки, 18 октября 2026 г.).
     */
    fun scheduleProbe(
        context: Context,
        reason: String,
        displayIdHint: Int = -1,
        delayMs: Long = 1_200L,
    ) {
        val app = context.applicationContext
        Bg.run("letterbox-probe") {
            if (delayMs > 0) Thread.sleep(delayMs)
            runCatching { probe(app, reason, displayIdHint) }
                .onFailure { android.util.Log.w(TAG, "probe failed after $reason", it) }
        }
    }

    private fun probe(
        context: Context,
        reason: String,
        displayIdHint: Int,
    ) {
        if (probeInFlight) return
        probeInFlight = true
        try {
            val displayId =
                displayIdHint
                    .takeIf { it >= 0 && VirtualLauncher.hintDisplaySize(it) != null }
                    ?: visibleDisplayId() ?: run {
                    if (isVisible()) clear()
                    return
                }
            val (fallbackW, fallbackH) =
                VirtualLauncher.hintDisplaySize(displayId) ?: run {
                    if (isVisible()) clear()
                    return
                }
            // Покрытие направлений записывается асинхронно последовательно: перед проверкой дождитесь освобождения очереди,
            // иначе состояние перед записью может быть прочитано.

            AppOrientationCompat.awaitPending()
            val enabled = AppOrientationCompat.enabledPackages(context)
            if (enabled.isEmpty()) {
                if (isVisible()) clear()
                return
            }
            val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
            val activity =
                parseTopLetterboxActivity(displayId, dump, fallbackW, fallbackH) ?: run {
                    if (isVisible()) clear()
                    return
                }
            val should =
                shouldShowLetterboxHint(activity, enabled, synchronized(dismissed) { dismissed.toSet() })
            if (!should) {
                if (isVisible()) {
                    VirtualLauncher.logSink?.invoke(
                        "ПОДСКАЗКА: Четкая подсказка ($reason)——" +
                            "letterbox=${activity.letterboxed} reason=${activity.letterboxReason}",
                    )
                    clear()
                }
                return
            }
            // Переопределение на стороне системы действительно должно вступить в силу, иначе «принудительный перезапуск»
            // обречен на неэффективность (вводящее в заблуждение сообщение).

            // Настройки включены, но отсутствуют на стороне системы и являются **самовосстанавливающимися** (сбой записи
            // сеанса/удаляется извне):

            // Перезапишите по желанию пользователя. `am compat` уничтожит целевой процесс на месте, поэтому вы должны
            // Возьмите на себя инициативу вернуть его на исходный экран — в противном случае пользователь увидит «нажмите,
            // чтобы открыть приложение, а затем вернетесь на рабочий стол без каких-либо подсказок».

            // (18 октября 2026 г. Фактический заголовок теста: Только этот путь завершится неудачно).
            val changeIds = AppOrientationCompat.changeIdsRegistered()
            var systemOn =
                changeIds && AppOrientationCompat.isSystemEnabledFor(activity.packageName)
            var current = activity
            var healedAndRelaunched = false
            if (!systemOn && changeIds) {
                val healed =
                    AppOrientationCompat.setEnabled(context, activity.packageName, true) ==
                        AppOrientationCompat.WriteResult.OK
                systemOn = healed || AppOrientationCompat.isSystemEnabledFor(activity.packageName)
                VirtualLauncher.logSink?.invoke(
                    "ПОДСКАЗКА: отсутствует покрытие горизонтального экрана на стороне системы «${activity.packageName}» — заполните" +
                        "${if (healed) "успех" else "не удалось"}",
                )
                if (systemOn) {
                    // Перепишите убитый процесс: подождите, пока он завершится, а затем перезапустите его на исходном экране
                    // (эквивалентно действию кнопки, но выполняется автоматически)

                    Thread.sleep(600)
                    val start =
                        Shell.exec(
                            "am start -n ${activity.component} --display $displayId -f 0x18000000",
                            timeoutMs = 8_000,
                        )
                    VirtualLauncher.logSink?.invoke(
                        "СОВЕТ: перезагрузите компьютер после перезаписи «${activity.packageName}» ok=" +
                            (start.ok && start.output.contains("Starting:", ignoreCase = true)),
                    )
                    healedAndRelaunched = true
                    Thread.sleep(2_200)
                    val fresh = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
                    current =
                        parseTopLetterboxActivity(displayId, fresh, fallbackW, fallbackH) ?: run {
                            if (isVisible()) clear()
                            return
                        }
                    if (!shouldShowLetterboxHint(
                            current,
                            enabled,
                            synchronized(dismissed) { dismissed.toSet() },
                        )
                    ) {
                        if (isVisible()) clear()
                        VirtualLauncher.logSink?.invoke(
                            "СОВЕТ: после перезаписи и перезапуска «${activity.packageName}» стал горизонтальным.",
                        )
                        return
                    }
                }
            }
            if (!systemOn) {
                VirtualLauncher.logSink?.invoke(
                    "СОВЕТ: 「${activity.packageName}」почтовый ящик, но этот компьютер не поддерживает горизонтальное наложение экрана——" +
                        "Не предлагать перезапустить (changeIds=$changeIds)",
                )
                if (isVisible()) clear()
                return
            }
            // Если черная рамка слишком узкая (невозможно разместить карту), showCard возвращает false и карта не
            // подвешивается; журнал записывает только фактическое отображение

            val wasVisible = isVisible()
            // После перепрошивки и перезапуска экран по-прежнему вертикальный → Это означает, что это приложение вообще не
            // использует преимущества охвата ориентации, а напрямую выдает "все еще вертикальный экран"

            // Карточка конечного состояния (больше не побуждает пользователей нажимать кнопку перезагрузки, которая
            // неизбежно завершится неудачей)

            state = if (healedAndRelaunched) State.FAILED else State.NORMAL
            // При повторном тестировании после самовосстановления последнее показание должно иметь преимущественную силу
            // (возможно, компонент был заменен перестроенным экземпляром).

            target = Target(current.packageName, current.component, displayId)
            val shown = showCard(current, state)
            if (!shown) {
                target = null
                return
            }
            if (!wasVisible) {
                VirtualLauncher.logSink?.invoke(
                    "ПОДСКАЗКА: горизонтальный экран «${current.packageName}» не действует ($reason)." +
                        " Внутреннее окно=${current.inner.width}x${current.inner.height}" +
                        " Внешняя рамка=${current.full.width}x${current.full.height}" +
                        " Черная рамка слева=${current.inner.left - current.full.left}" +
                        "/right=${current.full.right - current.inner.right}",
                )
            }
        } finally {
            probeInFlight = false
        }
    }

    /**
     * Экран фактически отображается в текущей области приложения: когда карта развернута в полноэкранном режиме, это
     * экран карты, в противном случае — это активный экран приложения.
     * Desktop (GridActivity) отсутствует в списке «Горизонтальный экран приложения», и обнаружение будет естественным образом пропущено.
     */
    private fun visibleDisplayId(): Int? {
        if (VirtualLauncher.mapFullscreen && VirtualLauncher.mapDisplayId >= 0) {
            return VirtualLauncher.mapDisplayId
        }
        val id = VirtualLauncher.activeAppDisplayId()
        return if (id >= 0) id else null
    }

    /**
     * генерирует и вешает карточки на основе прямоугольника почтового ящика.
     *
     * Система координат      : границами dumpsys являются **собственные пиксели экрана приложения**; синтезатор
     * растягивает/масштабирует весь экран, чтобы
     * область приложения, поэтому она преобразуется в координаты выходного экрана в соответствии с шириной области
     * приложения/шириной экрана (соотношение двух осей одинаково, и принимается горизонтальное направление).
     * Карты       отображаются только в том случае, если черная рамка достаточно широка — ширина определяется
     * фактическим размером текста, жестко запрограммированного порога нет.
     */
    private fun showCard(
        activity: LetterboxActivity,
        newState: State,
    ): Boolean {
        val area = VirtualLauncher.appAreaRect() ?: return false
        val display =
            VirtualLauncher.hintDisplaySize(activity.displayId) ?: return false
        if (display.first <= 0 || area.width() <= 0f) return false
        val scale = area.width() / display.first
        val leftBar = (activity.inner.left - activity.full.left) * scale
        val rightBar = (activity.full.right - activity.inner.right) * scale
        val density = VirtualLauncher.uiDensity()
        val margin = LetterboxCardPainter.marginPx(density)
        val needed = LetterboxCardPainter.minWidthPx(density) + 2f * margin
        // Черная рамка слишком узкая (не отличается от полноэкранного горизонтального экрана) → не отображается
        val side = chooseLetterboxSide(leftBar, rightBar, needed) ?: return false
        val barWidth = if (side == LetterboxSide.LEFT) leftBar else rightBar
        val width = minOf(LetterboxCardPainter.maxWidthPx(density), barWidth - 2f * margin).toInt()
        val bitmap =
            LetterboxCardPainter.render(
                density = density,
                widthPx = width,
                maxHeightPx = area.height().toInt(),
                state = newState.toCardState(),
            ) ?: return false
        state = newState
        val barStart = if (side == LetterboxSide.LEFT) area.left else area.right - barWidth
        val left = barStart + (barWidth - bitmap.width) / 2f
        val top = area.top + (area.height() - bitmap.height) / 2f
        cardLeft = left
        cardTop = top
        cardRight = left + bitmap.width
        cardBottom = top + bitmap.height
        VirtualLauncher.logSink?.invoke(
            "ПОДСКАЗКА: карта bitmap=${bitmap.width}x${bitmap.height} rect=" +
                "(${cardLeft.toInt()},${cardTop.toInt()}-${cardRight.toInt()},${cardBottom.toInt()})" +
                " bar=$barWidth density=$density widthPx=$width",
        )
        VirtualLauncher.setOverlayCard(
            OverlayCard(bitmap, cardLeft, cardTop, cardRight, cardBottom),
        )
        return true
    }

    private fun State.toCardState(): LetterboxCardState =
        when (this) {
            State.NORMAL -> LetterboxCardState.NORMAL
            State.BUSY -> LetterboxCardState.BUSY
            State.FAILED -> LetterboxCardState.FAILED
        }

    /**
     * Принудительно перезапустить целевое приложение: принудительно остановить → Перезапустить на **исходном
     * экране** → Повторное исследование.
     * выполнение фонового потока; прежде чем предпринимать какие-либо действия, дождитесь освобождения очереди
     * покрытия направлений (в противном случае старое покрытие все равно будет прочитано после перезапуска).
     */
    private fun restart(t: Target) {
        state = State.BUSY
        reRenderBusy()
        VirtualLauncher.logSink?.invoke("СОВЕТ: Принудительно перезапустите ${t.packageName} (display=${t.displayId})")
        Bg.run("letterbox-restart") {
            runCatching {
                AppOrientationCompat.awaitPending()
                // Написание am compat действительно убьет процесс, но задача восстановится по старой конфигурации; здесь явная
                // остановка будет

                // При следующем запуске выберите новый путь (фактический тест: после перезапуска overrideOrientation=NOSENSOR,
                // areBoundsLetterboxed=false).
                Shell.exec("am force-stop ${t.packageName}", timeoutMs = 8_000)
                Thread.sleep(400)
                val start =
                    Shell.exec(
                        "am start -n ${t.component} --display ${t.displayId} -f 0x18000000",
                        timeoutMs = 8_000,
                    )
                if (!start.ok) {
                    VirtualLauncher.logSink?.invoke(
                        "СОВЕТ: Не удалось перезапустить ${t.packageName}. exit=${start.exitCode} out=${start.output.take(120)}",
                    )
                }
                // Подождите, пока приложение перестроит окно, прежде чем повторить попытку.
                Thread.sleep(2_000)
                val (w, h) = VirtualLauncher.hintDisplaySize(t.displayId) ?: return@runCatching
                val ctx = VirtualLauncher.appContext() ?: return@runCatching
                val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
                val activity = parseTopLetterboxActivity(t.displayId, dump, w, h)
                val still =
                    activity != null &&
                        activity.packageName == t.packageName &&
                        shouldShowLetterboxHint(
                            activity,
                            AppOrientationCompat.enabledPackages(ctx),
                            synchronized(dismissed) { dismissed.toSet() },
                        )
                if (still) {
                    state = State.FAILED
                    if (!showCard(activity!!, State.FAILED)) {
                        // Черная рамка не может удерживать карту (или экран был освобожден): закройте ее напрямую, чтобы избежать
                        // перезапуска без обратной связи.

                        clear()
                    }
                    VirtualLauncher.logSink?.invoke(
                        "ПОДСКАЗКА: «${t.packageName}» по-прежнему остается почтовым ящиком после перезапуска (причина = " +
                            "${activity.letterboxReason})",
                    )
                } else {
                    clear()
                    VirtualLauncher.logSink?.invoke("ПОДСКАЗКА: «${t.packageName}» отображается горизонтально после перезапуска.")
                }
            }.onFailure {
                state = State.NORMAL
                VirtualLauncher.logSink?.invoke("СОВЕТ: исключение перезапуска ${it.message}")
            }
        }
    }

    /** Перерисовка состояния ЗАНЯТО: используйте ширину/положение текущей карты, чтобы избежать повторной обработки. */
    private fun reRenderBusy() {
        val density = VirtualLauncher.uiDensity()
        val width = (cardRight - cardLeft).toInt()
        val maxHeight = (VirtualLauncher.appAreaRect()?.height() ?: 0f).toInt()
        val bitmap = LetterboxCardPainter.render(density, width, maxHeight, LetterboxCardState.BUSY) ?: return
        // Выровняйте верх и низ по центру исходного прямоугольника (высота может измениться)
        val centerY = (cardTop + cardBottom) / 2f
        cardTop = centerY - bitmap.height / 2f
        cardBottom = cardTop + bitmap.height
        cardRight = cardLeft + bitmap.width
        VirtualLauncher.setOverlayCard(OverlayCard(bitmap, cardLeft, cardTop, cardRight, cardBottom))
    }

    /** Для диагностики: запрашиваемое в данный момент имя пакета (нуль, если не отображается). */
    fun currentPackage(): String? = target?.packageName

    /** Работают ли датчики (тестирование/диагностика). */
    fun isProbing(): Boolean = probeInFlight

    /** Снимок координат экрана последней карты (для целей диагностики/тестирования). */
    fun cardRect(): FloatArray = floatArrayOf(cardLeft, cardTop, cardRight, cardBottom)
}
