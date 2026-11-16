package io.github.shaonialife.cardeck.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.github.shaonialife.cardeck.launcher.settings.AppOrientationCompat
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.media.NotificationAccess
import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.render.DASH_GAP_PX
import io.github.shaonialife.cardeck.render.DASH_HANDLE_TOUCH_PX
import io.github.shaonialife.cardeck.render.DualDisplayComposer
import io.github.shaonialife.cardeck.render.OverlayCard
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.ui.ScreenDimmer
import io.github.shaonialife.cardeck.util.Bg

/**
 * Средство запуска виртуального экрана  (двойной экран + архитектура синтеза GL):
 *
 * Экран док-станции   (узкая полоска, резидент DockActivity)──┐
 * ├─ DualDisplayComposer ─→ Поверхность кодера
 * Экран приложения   (GridActivity/стороннее приложение)──┘
 *
 * - Приложение запускается на экран приложения (shell am start --display экран приложения); тот же компонент
 * запускается снова, чтобы вернуть существующие задачи (продолжить работу).
 * — Возврат на рабочий стол = Верните GridActivity на передний план, и фоновые задачи приложений не будут завершены.
 * - Touch отклоняется TouchRouter по координате x главного экрана: x <dockWidth → экран док-станции, иначе →
 * экран приложения (панорамирование).
 */
object VirtualLauncher {
    private const val TAG = "VirtualLauncher"

    /**
     * ширина закрепления (dp; доступная ширина экрана приложения = общая ширина — пиксели, преобразованные из этого
     * значения). В комплект CarPlay входят часы/батарея.
     * Базовая плотность 420 точек на дюйм (↔ холст Audi design 1160×540), от SessionController по короткой стороне холста
     * распределен в равных пропорциях - панель дока и текст/значки одновременно увеличиваются под автомобиль в
     * высоком разрешении, занимая постоянные 23,3% короткой стороны холста.
     * (= Design 126/540; 17.10.2026 Исправлено: «Док выглядит маленьким при высоком разрешении»).
     */
    const val DOCK_WIDTH_DP = 48

    /**
     * Соотношение сторон Letterbox (ширина/высота, записываемые в систему Шизуку при создании сеанса) для приложений
     * с портретной ориентацией.
     * Размер окна       по умолчанию составляет всего ~303px≈188dp@240dpi (около 1,78); 1.05 ослабляет его примерно до 514 пикселей.
     */
    private const val LETTERBOX_ASPECT_RATIO = 1.05f

    /** Целевая логическая ширина (dp) окна почтового ящика: ≈ ширина экрана мобильного телефона, запускающая компактный макет мобильного телефона для приложений электронной коммерции. */
    private const val LETTERBOX_TARGET_DP = 410f

    /**
     * Увеличение передискретизации экрана приложений на горизонтальном экране (единственный рычаг для уменьшения
     * размера фиксированного пикселя, см. прилагаемые примечания).
     * Когда       равен 2.0, накладные расходы пикселей равны 4x (очевидно, задержка по измерениям пользователей);
     * когда 1,5, накладные расходы пикселей равны 4x, а верхний коэффициент фиксированной площади равен
     * 12% (исходное 18%) - компромиссная точка качества/плавности изображения (корректировка параметра по отзывам
     * пользователей 2026-10-11).
     */
    private const val APP_SUPERSAMPLE = 1.5f

    /** Максимальная высота экрана приложения после передискретизации (пикселей; контролирует нагрузку на графический процессор и память). */
    private const val SUPERSAMPLE_MAX_HEIGHT = 1620

    // ---- Разделение экрана приборной панели (перетащите ручку) ----
    // Соотношение = отношение длины главной оси плитки к «доступной длине» (основная ось области применения — поля
    // на обоих концах — ширина ручки).

    /** Масштаб по умолчанию: 0,5 помещает центр маркера точно в середину области приложения (см. аннотацию DashSplitGeom). */
    const val DASH_SPLIT_DEFAULT_FRACTION = 0.5f

    /** Пропорциональная область зажима: на любую карту приходится минимум 20%, что предотвращает ее перетаскивание в линию (кнопки/закругленные углы полностью сжимаются). */
    const val DASH_SPLIT_MIN_FRACTION = 0.2f

    const val DASH_SPLIT_MAX_FRACTION = 0.8f

    private const val PREF_KEY_DASH_SPLIT = "dashSplitFraction"

    fun clampDashSplitFraction(fraction: Float): Float = fraction.coerceIn(DASH_SPLIT_MIN_FRACTION, DASH_SPLIT_MAX_FRACTION)

    /** Геометрия разделенного экрана (система координат области приложения: начало горизонтального экрана = правый край закрепления, начало вертикального экрана = верхний край потока). */
    data class DashSplitGeom(
        val fraction: Float,
        /** длина основной оси тайла (горизонтальная=mapTileWidth, вертикальная=mapTileHeight). */
        val tileLen: Int,
        /** Боковой край маркера плитки (та же семантика, что и переопределение композитора). */
        val tileEdge: Float,
        /** Центр ручки. */
        val splitCenter: Float,
        /** Начинается содержательная сторона карты на противоположной стороне (музыкальная карта). */
        val otherStart: Float,
    )

    /**
     * Чистая геометрическая функция разделенного экрана (фиксировано одно измерение): доступная длина = главная ось
     * − поле × 2 − ширина ручки;
     * tileLen = масштаб × доступный; центр дескриптора = поле + tileLen + половина дескриптора (0,5 — это ровно
     * находится в центре области приложения, что соответствует старому поведению с разделением экрана по центральной
     * линии); начало музыкальной карты = поле + tileLen + ширина маркера.
     */
    fun dashSplitGeom(
        appMainLen: Int,
        fraction: Float,
    ): DashSplitGeom {
        val usable = (appMainLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()).coerceAtLeast(1)
        val clamped = clampDashSplitFraction(fraction)
        val tileLen = (clamped * usable).toInt()
        val edge = CARD_MARGIN_PX + tileLen
        return DashSplitGeom(
            fraction = clamped,
            tileLen = tileLen,
            tileEdge = edge.toFloat(),
            splitCenter = edge + DASH_GAP_PX / 2f,
            otherStart = edge + DASH_GAP_PX,
        )
    }

    /** Длина основной оси разделенного экрана (система координат области приложения): по горизонтали = appAreaWidth, по вертикали = appAreaHeight. */
    private fun splitMainLen(): Int = if (dockAtBottom) appAreaHeight else appAreaWidth

    val runningApps = LinkedHashSet<ComponentName>()

    private val runningAppsListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Обратный вызов регистрации дока: обновляется при изменении текущего списка (док занимает эксклюзивный экран, всегда возобновляется, а onResume больше не запускается). */
    fun addRunningAppsListener(listener: () -> Unit) {
        runningAppsListeners.add(listener)
    }

    fun removeRunningAppsListener(listener: () -> Unit) {
        runningAppsListeners.remove(listener)
    }

    private fun notifyRunningAppsChanged() {
        runningAppsListeners.forEach { runCatching { it() } }
    }

    /**
     * Кэш значков рабочего стола (используется DockActivity для отображения работающих значков). Нить сканирования
     * рабочего стола заменяет весь стол и доки
     * Параллельное чтение основного потока - замена энергозависимых ссылок, старая таблица, полученная считывателем,
     * также является полной и самосогласованной (ранее очищается + по одной
     * записывает в один и тот же HashMap, межэкранное чтение и запись не синхронизируются и могут быть повреждены
     * при одновременном использовании).
     */
    @Volatile
    private var iconCacheRef: Map<ComponentName, Drawable> = emptyMap()

    val iconCache: Map<ComponentName, Drawable>
        get() = iconCacheRef

    /** После завершения сканирования рабочего стола вся таблица будет заменена кэшем значков. */
    fun replaceIconCache(fresh: Map<ComponentName, Drawable>) {
        iconCacheRef = fresh
    }

    @Volatile
    var dockDisplayId: Int = -1
        private set

    @Volatile
    var appDisplayId: Int = -1
        private set

    /** Ширина в пикселях, занимаемая док-станцией на главном экране композиции (порог шунтирования TouchRouter; горизонтальный экран = ширина левой вертикальной полосы). */
    @Volatile
    var dockWidthPx: Int = 0
        private set

    /** Автомобильный телефон с вертикальным экраном (высота экрана>ширина): док-станция заменяется горизонтальной полосой внизу. */
    @Volatile
    var dockAtBottom: Boolean = false
        private set

    /** высота панели док-станции (книжная ориентация экрана; TouchRouter нажмите y для перенаправления). */
    @Volatile
    var dockHeightPx: Int = 0
        private set

    /** Высота области приложения (вертикальный экран; y < appHeightPx принадлежит экрану приложения). */
    @Volatile
    var appHeightPx: Int = 0
        private set

    /**
     * Увеличение передискретизации экрана приложения (увеличение рендеринга экрана приложения передискретизации; 1 =
     * экран передискретизации не создается).
     * Координаты касания       преобразуются в соответствии с увеличением текущего **активного экрана приложения** (см. [activeAppScale]).
     */
    @Volatile
    var hiScale: Float = 1f
        private set

    /** Экран приложения с передискретизацией (существует, когда машина находится в режиме горизонтального экрана; в режиме машины с вертикальным экраном - -1, и все родные). */
    @Volatile
    var appHiDisplayId: Int = -1
        private set

    /** Экран, на котором расположена основная область рабочего стола (GridActivity): горизонтальный экран = экран с суперсэмплированием (с сохранением существующего внешнего вида), вертикальный экран = собственный экран. */
    @Volatile
    var gridDisplayId: Int = -1
        private set

    /** Размер холста видеопотока (основа для сопоставления аспектов фрагментов обоев рабочего стола). */
    @Volatile
    var streamWidthPx: Int = 0
        private set

    @Volatile
    var streamHeightPx: Int = 0
        private set

    // ---- Экран карты (разделенный экран информационной панели): эксклюзивный экран для картографических
    // приложений, разделенный экран = размер плитки, полный экран = размер области приложения ----

    /** DisplayId экрана карты (создается в ландшафтном режиме и режиме доверенного экрана; -1 = нет). */
    @Volatile
    var mapDisplayId: Int = -1
        private set

    /** Прогресс скольжения панели мониторинга ↔ рабочего стола (0 = плитка на месте, 1 = полностью выдвинута): подавление внедрения событий плитки во время скольжения. */
    @Volatile
    var tileSlideFraction: Float = 0f
        private set

    /** Визуальное смещение плиток определяется GridActivity при скольжении пейджера. */
    fun setTileSlide(fraction: Float) {
        tileSlideFraction = fraction
        composer?.tileSlideFraction = fraction
    }

    /** Настройка «Дисплей» переключает целевую частоту кадров в режиме реального времени (без звука, если композитор не создан — будет считываться из настроек при подключении). */
    fun setStreamFps(fps: Int) {
        composer?.targetFps = fps
    }

    /** Размер фрагмента экрана карты (фактический размер дисплея в форме разделенного экрана; соответствует прямоугольнику рисования SPLIT DualDisplayComposer). */
    @Volatile
    var mapTileWidth: Int = 0
        private set

    @Volatile
    var mapTileHeight: Int = 0
        private set

    /**
     * Центр маркера разделенного экрана информационной панели (пиксель потока, начало координат вверху;
     * горизонтальный экран = x, вертикальный экран = y). <0 = не прикреплено.
     * постоянно просматривается [previewDashSplit] во время перетаскивания, а [commitDashSplit] фиксируется и извлекается после отпускания.
     * [mapTileWidth]/[mapTileHeight]. Геометрический вывод выполняется с помощью чистых функций [dashSplitGeom].
     */
    @Volatile
    var dashSplitPos: Float = -1f
        private set

    /** Действующее на данный момент соотношение разделения экрана (прикрепить восстановление из настроек, зафиксировать обратную запись). */
    @Volatile
    private var dashSplitFraction = DASH_SPLIT_DEFAULT_FRACTION

    /** Размер области приложения (целевой размер экрана карты в полноэкранном режиме). */
    @Volatile
    var appAreaWidth: Int = 0
        private set

    @Volatile
    var appAreaHeight: Int = 0
        private set

    @Volatile
    var mapFullscreen: Boolean = false
        private set

    /** Текущий логический размер экрана карты (изменение размера идемпотентной базовой линии; 0 = не создано/не выпущено). */
    @Volatile
    private var mapDisplayW = 0

    @Volatile
    private var mapDisplayH = 0

    /** Логический размер собственного экрана приложения (фактический источник размера экрана, преобразованный черными рамками почтового ящика; не изменяется после создания). */
    @Volatile
    private var appDisplayW = 0

    @Volatile
    private var appDisplayH = 0

    /** Логический размер экрана приложения суперсэмплинга (как указано выше). */
    @Volatile
    private var appHiDisplayW = 0

    @Volatile
    private var appHiDisplayH = 0

    /** Плотность экрана карты (фиксируется на уровне 200 точек на дюйм при создании, не изменяется при изменении размера — логический размер масштабируется в зависимости от пикселей). */
    private const val MAP_DISPLAY_DPI = 200

    /** Активирован ли режим приборной панели (разделенный экран). */
    @Volatile
    var dashboardMode: Boolean = false
        private set

    /** Мониторинг изменения режима панели мониторинга (схема переключения GridActivity). */
    private val dashboardListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addDashboardListener(listener: (Boolean) -> Unit) {
        dashboardListeners.add(listener)
    }

    fun removeDashboardListener(listener: (Boolean) -> Unit) {
        dashboardListeners.remove(listener)
    }

    private fun notifyDashboard() {
        dashboardListeners.forEach { runCatching { it(dashboardMode) } }
    }

    /** Мониторинг изменения макета плитки карты (скрытый/разделенный экран/полноэкранный режим). */
    private val mapLayoutListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addMapLayoutListener(listener: (Boolean) -> Unit) {
        mapLayoutListeners.add(listener)
    }

    fun removeMapLayoutListener(listener: (Boolean) -> Unit) {
        mapLayoutListeners.remove(listener)
    }

    private fun notifyMapLayout() {
        mapLayoutListeners.forEach { runCatching { it(mapFullscreen) } }
    }

    /** Белый список приложений карты (приоритет: AutoNavi для мобильных устройств и мобильных устройств → Baidu для мобильных устройств и мобильных устройств → AutoNavi для мобильных устройств → Baidu для мобильных устройств → Tencent). */
    val MAP_PACKAGES_PRIORITY =
        listOf(
            "com.autonavi.amapauto",
            "com.baidu.naviauto",
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
            "com.tencent.map",
        )

    fun isMapPackage(packageName: String): Boolean = packageName in MAP_PACKAGES_PRIORITY

    /**
     * Установлена карта по умолчанию (явные пользовательские настройки имеют приоритет - DefaultMapPrefs; в
     * противном случае приоритет имеет
     * впервые установлен; null = нет приложения карты).
     *
     * ⚠️ Эта функция является **только** транспозицией [DefaultMapPrefs.resolve], и последнюю нельзя вызывать в обратном порядке.
     * Эта функция (будет бесконечно рекурсивна → StackOverflowError). Сканирование приоритезации уже реализовано в Resolve.
     * реализация      , она отвечает за корректность; зарезервировано здесь только для семантики внешнего API
     * (вызывающей стороной является VirtualLauncher
     * Главный вход в      ). 18 октября 2026 г. Динджи.
     */
    fun defaultMapComponent(context: Context): ComponentName? = DefaultMapPrefs.resolve(context)

    /**
     * Увеличение рендеринга экрана, на котором расположен рабочий стол, относительно видеопотока (собственный экран
     * = 1, экран суперсэмплинга = hiScale).
     * Используется для преобразования размера/смещения при рисовании «Full-Stream Canvas Wallpaper Slice» на рабочем столе.
     */
    fun gridScale(): Float = if (gridDisplayId >= 0 && gridDisplayId == appHiDisplayId) hiScale else 1f

    private var dockDisplay: VirtualDisplay? = null
    private var appDisplay: VirtualDisplay? = null
    private var appHiDisplay: VirtualDisplay? = null

    /** Дескриптор экрана карты в резервном состоянии (доверенное состояние создается оболочкой и не имеет этого дескриптора процесса). */
    private var mapDisplay: VirtualDisplay? = null
    private var composer: DualDisplayComposer? = null

    /** Идентификатор активного экрана приложения в данный момент (цель внедрения TouchRouter). */
    fun activeAppDisplayId(): Int = if (composer?.isActiveAppHi() == true && appHiDisplayId >= 0) appHiDisplayId else appDisplayId

    /** Координатное увеличение текущего активного экрана приложения (экран суперсэмплинга = hiScale, собственный экран = 1). */
    fun activeAppScale(): Float = if (composer?.isActiveAppHi() == true && appHiDisplayId >= 0) hiScale else 1f

    /** Прямоугольник области приложения на экране вывода (верхнее начало) — используется для позиционирования наложенной карты. */
    fun appAreaRect(): RectF? {
        if (streamWidthPx <= 0 || streamHeightPx <= 0) return null
        return if (dockAtBottom) {
            RectF(0f, 0f, streamWidthPx.toFloat(), appHeightPx.toFloat())
        } else {
            RectF(dockWidthPx.toFloat(), 0f, streamWidthPx.toFloat(), streamHeightPx.toFloat())
        }
    }

    /** Плотность пользовательского интерфейса Canvas **масштаб** (т/д/160; та же базовая линия, что и у док-экрана: 420 т/д при 540 по короткому краю). */
    fun uiDensity(): Float = ((420f * minOf(streamWidthPx, streamHeightPx) / 540f).coerceIn(120f, 1680f)) / 160f

    /** Указывает размер логического пикселя виртуального экрана (источник истины, когда почтовый ящик обнаруживает отсутствие mMaxBounds). */
    fun hintDisplaySize(displayId: Int): Pair<Int, Int>? =
        when (displayId) {
            appDisplayId -> if (appDisplayW > 0) appDisplayW to appDisplayH else null
            appHiDisplayId -> if (appHiDisplayW > 0) appHiDisplayW to appHiDisplayH else null
            mapDisplayId -> if (mapDisplayW > 0) mapDisplayW to mapDisplayH else null
            else -> null
        }

    /** Повесьте/подберите оверлейные карты на синтезаторе. */
    fun setOverlayCard(card: OverlayCard?) {
        composer?.overlayCard = card
    }

    /** Контекст сеанса (используется для повторного исследования после перезапуска почтового ящика; значение null, если не подключен). */
    fun appContext(): Context? = appContextRef

    /**
     * Монтирование: Создайте синтезатор GL → Создайте два виртуальных экрана в готовом обратном вызове → Поднимите Dock/Grid.
     * [encoderSurface] Входная поверхность для энкодера; возвращает, все ли успешно.
     */
    @SuppressLint("WrongConstant")
    fun attach(
        context: Context,
        encoderSurface: Surface,
        totalWidth: Int,
        totalHeight: Int,
        densityDpi: Int,
        log: (String) -> Unit,
    ): Boolean {
        detach()
        // Новый сеанс: решения на уровне пользователя, такие как «Больше не запрашивать этот сеанс», становятся недействительными.
        LetterboxHintController.resetSession()
        // Наложение «Ландшафт приложения усилия» **должно быть записано перед запуском любого приложения** (23 октября
        // 2026 г., реальная машина Audi 3315)

        // (Динцзи): `am compat enable` немедленно завершит целевое приложение (переопределение может вступить в силу
        // только в новом процессе) ——

        // Если написано после запуска приложения, вновь созданный процесс карты/рабочего стола на рабочем столе будет
        // немедленно завершен, а виртуальный экран

        // Оставлены только статичные обои, причем картинка, полученная автомобилем, никогда не изменится, и интерфейс
        // CarPlay не будет переключаться (отображается как «нажатие»).

        // Но машина и машина все еще находятся в исходном интерфейсе". Записи ставятся в очередь в начале сопряжения
        // (обработка конечной точки SessionController),

        // сюда прикрепляю только стоки - обычно завершаются (~0мс). Будьте осторожны и не запускайте applyAll синхронно:
        // Кадр KeepAlive порта экрана отправляется только из цикла дренажного кодера, и поток стока возвращается в этом методе.
        // Затем начните - каждая заблокированная здесь секунда будет съедать ~10 секунд "RECORD → first KeepAlive"
        // Бюджет снятия цепи (порог, измеренный Audi).
        if (!AppOrientationCompat.awaitPending()) {
            log(
                "ОРИЕНТАЦИЯ: ⚠ Перезапись не истощается в течение 6 с — приложение можно запустить со старой " +
                    "конфигурацией ориентации (первое приложение, запущенное после завершения фоновой записи, будет уничтожено один раз)",
            )
        }
        val density = densityDpi / 160f
        // Масштабирование ширины панели док-станции (панель «Дисплей» 50–100 %, вступает в силу при следующем
        // подключении): толщина пикселей и экрана док-станции.

        // Коэффициент плотности ** умножения ** — макет содержимого dp остается неизменным, отображается меньше
        // пикселей, вся док-станция (значок/часы/

        // текст) пропорционально сужены; уменьшение только толщины, но не плотности приведет к переполнению содержимого
        // или появлению пустого пространства. Нижний предел — 80 пикселей.

        // Крайнее полотно придавлено. Нижний предел плотности 120 Anti-ROM отклоняет ненормальную плотность.
        val dockScale = DockPrefs.widthPercent(context) / 100f
        val thicknessPx =
            (DOCK_WIDTH_DP * density * dockScale)
                .toInt()
                .coerceAtLeast((80 * dockScale).toInt().coerceAtLeast(1))
        val dockDensityDpi = (densityDpi * dockScale).toInt().coerceAtLeast(120)
        // Направление автомобиля определяет положение дока: горизонтальный экран → левая вертикальная полоса;
        // вертикальный экран → нижняя горизонтальная полоса (по запросу пользователя 11 октября 2026 г.)

        dockAtBottom = totalHeight > totalWidth
        streamWidthPx = totalWidth
        streamHeightPx = totalHeight
        val appWidth: Int
        if (dockAtBottom) {
            dockWidthPx = totalWidth
            dockHeightPx = thicknessPx
            appHeightPx = (totalHeight - thicknessPx).coerceAtLeast(200)
            appWidth = totalWidth
        } else {
            dockWidthPx = thicknessPx
            dockHeightPx = totalHeight
            appHeightPx = totalHeight
            appWidth = (totalWidth - thicknessPx).coerceAtLeast(200)
        }
        appAreaWidth = appWidth
        appAreaHeight = appHeightPx
        // Плотность экрана приложения вертикального экрана: целевая логическая ширина окна — вертикальная ширина экрана
        // мобильного телефона (LETTERBOX_TARGET_DP).

        // Неправильное использование плотности Dpi дока (которая пропорциональна разрешению холста, а не плотности
        // мобильного телефона) приведет к сжатию окна до ~206dp — текст будет выглядеть вдвое больше.

        // (2026-10-14 Измерение вертикального экрана: основная причина явно большего размера шрифта в приложениях и на
        // настольных компьютерах).

        val appPortraitDensity =
            (appWidth / LETTERBOX_TARGET_DP * 160f).toInt().coerceIn(160, 640)
        // Экран карты: создается в соответствии с полным размером области приложения, динамически изменяет размер между
        // разделенным экраном и полноэкранным режимом (см. resizeMapDisplay).

        // Размер плитки = SPLIT рисует прямоугольник, а поля используют значение контракта CARD_MARGIN_PX (больше никаких голых чисел).
        // Соотношение разделенного экрана регулируется пользователем, перетаскивающим маркер (постоянное предпочтение);
        // геометрический вывод ориентирован на [dashSplitGeom]

        // Чистая функция (один тест опровергает четырехфакторную согласованность). Длина основной оси плитки
        // (горизонтальная = ширина, вертикальная = высота) = соотношение × доступная длина.

        dashSplitFraction =
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getFloat(PREF_KEY_DASH_SPLIT, DASH_SPLIT_DEFAULT_FRACTION)
                .let(::clampDashSplitFraction)
        if (dockAtBottom) {
            val geom = dashSplitGeom(appHeightPx, dashSplitFraction)
            mapTileWidth = appWidth - CARD_MARGIN_PX * 2
            mapTileHeight = geom.tileLen
            dashSplitPos = geom.splitCenter
        } else {
            val geom = dashSplitGeom(appWidth, dashSplitFraction)
            mapTileWidth = geom.tileLen
            mapTileHeight = appHeightPx - CARD_MARGIN_PX * 2
            // Область приложения горизонтального экрана начинается с правого края дока (начало вертикального экрана x — это
            // начало потока, без смещения)

            dashSplitPos = geom.splitCenter + dockWidthPx
        }
        // Выбор плотности горизонтального экрана приложения (калибровка измерена 11 октября 2026 г.):
        // Приложения с вертикальной блокировкой экрана (например, Toutiao) помещаются системой в узкое окно на
        // горизонтальном экране, а логическая ширина окна

        // = высота экрана в пикселях/соотношение сторон почтового ящика, а затем преобразуется в dp по плотности экрана.
        // Плотность высокая → только окно ~343dp,

        // Приложение представляет собой узкоэкранный макет с «крупными шрифтами и большим окном поиска»; плотность ~200
        // → окно ~411dp ≈ ширина мобильного телефона,

        // Приложение представляет собой компактный мобильный макет (проверка по скриншоту). Цель: окно почтового ящика ≈
        // LETTERBOX_TARGET_DP.

        val landscapeApp = appWidth > appHeightPx && !dockAtBottom
        // Суперсэмплинг: некоторые приложения (протестированные Toutiao) резервируют верхнюю область в соответствии с
        // «физическими пикселями строки состояния мобильного устройства».

        // (28dp@560dpi=98 пикселей исходных пикселей, независимо от плотности дисплея), что составляет 18% от высокого потока 540 пикселей.
        // Только 3,5% на мобильных телефонах - нельзя удалить (рисование внутри приложения), можно рассчитывать только
        // на "отрисовку большего количества пикселей и последующее сжатие"

        // Уменьшите долю фиксированных пикселей. ×2: 98 пикселей → 49 пикселей (9%); верхний предел высоты
        // суперсэмплинга составляет 1620 пикселей.

        val appScaleFactor =
            if (landscapeApp) {
                minOf(APP_SUPERSAMPLE, SUPERSAMPLE_MAX_HEIGHT.toFloat() / appHeightPx)
            } else {
                1f
            }
        val appHiWidth = (appWidth * appScaleFactor).toInt()
        val appHiHeight = (appHeightPx * appScaleFactor).toInt()
        this.hiScale = appScaleFactor
        val appHiDensity =
            if (landscapeApp) {
                val letterboxWindowPx = appHiHeight / LETTERBOX_ASPECT_RATIO
                (letterboxWindowPx / LETTERBOX_TARGET_DP * 160f).toInt().coerceIn(180, 640)
            } else {
                appPortraitDensity
            }
        // Плотность экрана собственного приложения: сохраните тот же логический размер, что и экран суперсэмплинга (на
        // макет приложения не влияет экран, на котором оно расположено).

        // Суперсэмплинг — это всего лишь разница в разрешении рендеринга). Горизонтальный экран: исходное разрешение
        // 1034×540 при ~200 точек на дюйм = 827×432 точек на дюйм,

        // Эквивалент экрана с передискретизацией 824×431dp (1551×810 @301dpi); машина с вертикальным экраном:
        // используйте плотность потока напрямую.

        val appNativeDensity =
            if (landscapeApp) {
                val hiLogicalHeightDp = appHiHeight * 160f / appHiDensity
                (appHeightPx * 160f / hiLogicalHeightDp).toInt().coerceIn(160, 640)
            } else {
                appPortraitDensity
            }

        // Ожидание привязки Shizuku UserService (для создания доверенного виртуального экрана требуется разрешение оболочки)
        var svc = CarInputClient.service
        if (svc == null &&
            ShizukuGate
                .state(context) ==
            ShizukuGate.State.READY
        ) {
            val deadline = SystemClock.uptimeMillis() + 2_500L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                CarInputClient
                    .ensure(context)
                Thread.sleep(150)
                svc = CarInputClient.service
            }
        }
        val useTrusted = svc != null
        log("LAUNCHER: виртуальный экран: ${if (useTrusted) "доверенный (shell)" else "прикладной (резерв)"}")

        val composer = DualDisplayComposer(encoderSurface, totalWidth, totalHeight, dockAtBottom)
        composer.targetFps =
            DisplayPrefs
                .fps(context)
        this.composer = composer
        val displaysCreated = arrayOf(false)
        composer.onSurfacesReady = { dockSurface, appSurface, appHiSurface, mapSurface ->
            // Приоритет: оболочка Shizuku создает доверенный виртуальный экран (VIRTUAL_DISPLAY_FLAG_TRUSTED) ——
            // Разрешить попадание на этот экран новым задачам сторонних приложений; решить проблему «переходы задач внутри
            // приложения перенаправляются на главный экран телефона».

            var viaService = false
            if (useTrusted) {
                runCatching {
                    // Остаточная очистка: виртуальный экран с тем же именем, оставшийся после перезапуска процесса/последнего раунда
                    // сеанса, всегда будет занимать место, если его не освободить.

                    // (Новая ширина/новая поверхность не вступает в силу — основная причина отсутствия визуальных изменений после
                    // изменения ширины дока 11 октября 2026 г.)

                    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    dm.displays
                        .filter {
                            it.name == "CarPlayDock" ||
                                it.name == "CarPlayApp" ||
                                it.name == "CarPlayAppHi" ||
                                it.name == "CarPlayMap"
                        }.forEach { stale ->
                            runCatching { svc!!.releaseDisplay(stale.displayId) }
                                .onSuccess { log("LAUNCHER: остаточный виртуальный экран ${stale.name}#${stale.displayId} освобожден.") }
                        }
                    Thread.sleep(150) // Подождите, пока система завершит выход из системы.
                    // Экран док-станции не декларирует доверие: на нем размещается только собственная DockActivity, чтобы
                    // предотвратить ускользание сторонних задач.

                    val dockId =
                        svc!!.createDisplay(
                            "CarPlayDock",
                            dockWidthPx,
                            dockHeightPx,
                            dockDensityDpi,
                            dockSurface,
                            false,
                        )
                    val appId =
                        svc!!.createDisplay(
                            "CarPlayApp",
                            appWidth,
                            appHeightPx,
                            appNativeDensity,
                            appSurface,
                            true,
                        )
                    // Экран суперсэмплинга (недоступен на автомобилях с портретным экраном и при возврате к стороне приложения)
                    val appHiId =
                        if (appHiSurface != null) {
                            svc!!.createDisplay(
                                "CarPlayAppHi",
                                appHiWidth,
                                appHiHeight,
                                appHiDensity,
                                appHiSurface,
                                true,
                            )
                        } else {
                            -1
                        }
                    // Экран карты (создается как на горизонтальном, так и на вертикальном экранах; создается в соответствии с полным
                    // размером области приложения, размер динамически изменяется между разделенным экраном и полноэкранным режимом)

                    val mapId =
                        if (mapSurface != null) {
                            svc!!.createDisplay(
                                "CarPlayMap",
                                appWidth,
                                appHeightPx,
                                200,
                                mapSurface,
                                true,
                            )
                        } else {
                            -1
                        }
                    if (dockId >= 0 && appId >= 0) {
                        dockDisplayId = dockId
                        appDisplayId = appId
                        appDisplayW = appWidth
                        appDisplayH = appHeightPx
                        if (appHiId >= 0) {
                            appHiDisplayId = appHiId
                            appHiDisplayW = appHiWidth
                            appHiDisplayH = appHiHeight
                        } else if (appHiSurface != null) {
                            log(
                                "LAUNCHER: не удалось создать экран суперсэмплинга — для этого сеанса были использованы " +
                                    "все собственные экраны.",
                            )
                        }
                        if (mapId >= 0) {
                            mapDisplayId = mapId
                            // Размер создания = полный размер области приложения (идемпотентная базовая линия resizeMapDisplay)
                            mapDisplayW = appWidth
                            mapDisplayH = appHeightPx
                        } else if (mapSurface != null) {
                            log("ЗАПУСК: не удалось создать экран карты — на информационной панели нет фрагмента карты.")
                        }
                        viaService = true
                    } else {
                        // Поэкранные трассировки: ПЗУ, такие как EMUI, молчат при созданииVirtualDisplay на стороне оболочки.
                        // Возвращать значение null** (исключение не создается) — этот элемент не будет введен во время общего отката и
                        // только

                        // По слову «Возврат» невозможно определить, какой экран был отклонен (Huawei Mate30Pro/EMUI12
                        // 20.10.2026 Фактическое позиционирование)
                        if (dockId < 0) {
                            log(
                                "LAUNCHER: не удалось создать доверенный экран CarPlayDock (createVirtualDisplay " +
                                    "возвращает значение null — ПЗУ ограничивает виртуальный экран на стороне оболочки?)",
                            )
                        }
                        if (appId < 0) {
                            log("LAUNCHER: не удалось создать доверенный экран CarPlayApp (как указано выше)")
                        }
                        // Полууспешный: выпустить построенное
                        if (dockId >= 0) runCatching { svc!!.releaseDisplay(dockId) }
                        if (appId >= 0) runCatching { svc!!.releaseDisplay(appId) }
                        if (appHiId >= 0) runCatching { svc!!.releaseDisplay(appHiId) }
                        if (mapId >= 0) runCatching { svc!!.releaseDisplay(mapId) }
                    }
                }.onFailure { log("LAUNCHER: исключение создания доверенного экрана ${it.message}") }
            }
            if (viaService) {
                displaysViaService = true
                log(
                    "LAUNCHER: создание трёх экранов (доверенное) Dock=$dockDisplayId(${dockWidthPx}px)" +
                        "app=$appDisplayId(${appWidth}x$appHeightPx @${appNativeDensity}dpi) " +
                        "appHi=$appHiDisplayId(${appHiWidth}x$appHiHeight @${appHiDensity}dpi scale=x$appScaleFactor)",
                )
                // Установление сеанса: запись совместимых переопределений для списка «принудительного ландшафта приложений» —
                // **перенесено в начало присоединения**

                // (Сначала пропишите оверлей, а потом тяните приложение, см. комментарии в начале аттача, повторная постановка в
                // очередь здесь не делается).

                // Интерфейс источника данных музыкальной карты: установление сеанса — это надежная возможность для «Шизуку
                // должна быть доступна»,

                // Уведомить о праве на использование один раз (идемпотент; возврат сразу после авторизации).
                // Охватите весь путь «Шизуку не готова при запуске приложения и будет готова позже».
                Bg.run("notification-access-session") {
                    NotificationAccess.ensureGranted(context.applicationContext, log)
                }
                // Запись с перезаписью является асинхронной (зонд будет внутренне ожидать Pending и ждать, пока она истощится);
                // Если приложение уже запущено и ранее было восстановлено в виде вертикального окна, оно будет обнаружено и
                // предложено здесь.

                LetterboxHintController.scheduleProbe(context, "Создание сессии")
            } else {
                // Резервный вариант: ПУБЛИЧНЫЙ экран на стороне приложения (задачи перехода внутри приложения будут выводиться
                // на главный экран телефона, а форма перехода на более раннюю версию будет максимально доступной)

                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dockDisplay =
                    dm.createVirtualDisplay(
                        "CarPlayDock",
                        dockWidthPx,
                        dockHeightPx,
                        dockDensityDpi,
                        dockSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        null,
                        null,
                    )
                appDisplay =
                    dm.createVirtualDisplay(
                        "CarPlayApp",
                        appWidth,
                        appHeightPx,
                        appNativeDensity,
                        appSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        null,
                        null,
                    )
                if (appHiSurface != null) {
                    appHiDisplay =
                        dm.createVirtualDisplay(
                            "CarPlayAppHi",
                            appHiWidth,
                            appHiHeight,
                            appHiDensity,
                            appHiSurface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                            null,
                            null,
                        )
                    appHiDisplayId = appHiDisplay?.display?.displayId ?: -1
                    appHiDisplayW = appHiWidth
                    appHiDisplayH = appHiHeight
                }
                // Также построен экран карты (на стороне приложения PUBLIC): запустите карту и перейдите в оболочку `am start --display`,
                // Никакого доверия не требуется; resize использует общедоступный API на стороне приложения (см. resizeMapDisplay).
                // Раньше в резервном состоянии вообще не создавался экран карты — после того, как доверенное создание не удалось
                // выполнить автоматически на EMUI12, панель мониторинга

                // Плитки карты никогда не будет (фактический тест Huawei Mate30Pro 20 октября 2026 г.).
                if (mapSurface != null) {
                    mapDisplay =
                        dm.createVirtualDisplay(
                            "CarPlayMap",
                            appWidth,
                            appHeightPx,
                            MAP_DISPLAY_DPI,
                            mapSurface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                            null,
                            null,
                        )
                    val fallbackMapId = mapDisplay?.display?.displayId ?: -1
                    if (fallbackMapId >= 0) {
                        mapDisplayId = fallbackMapId
                        mapDisplayW = appWidth
                        mapDisplayH = appHeightPx
                    } else {
                        log("ЗАПУСК: не удалось создать экран карты (резервный вариант) — на панели мониторинга нет плитки карты.")
                    }
                }
                dockDisplayId = dockDisplay?.display?.displayId ?: -1
                appDisplayId = appDisplay?.display?.displayId ?: -1
                appDisplayW = appWidth
                appDisplayH = appHeightPx
                log(
                    "LAUNCHER: создание трёх экранов (резервный вариант) Dock=$dockDisplayId(${dockWidthPx}px)" +
                        "app=$appDisplayId(${appWidth}x$appHeightPx @${appNativeDensity}dpi) " +
                        "appHi=$appHiDisplayId(${appHiWidth}x$appHiHeight @${appHiDensity}dpi scale=x$appScaleFactor) " +
                        "map=$mapDisplayId(${mapDisplayW}x$mapDisplayH) — — Доверенный экран недоступен, а ПУБЛИЧНЫЙ " +
                        "экран на стороне приложения отменён.",
                )
            }
            displaysCreated[0] = dockDisplayId >= 0 && appDisplayId >= 0
        }
        return try {
            composer.start(
                dockWidthPx,
                dockHeightPx,
                appWidth,
                appHeightPx,
                if (landscapeApp) appHiWidth else 0,
                if (landscapeApp) appHiHeight else 0,
                appWidth,
                appHeightPx,
            )
            // Переместить положение маркера разделенного экрана в композитор (переопределяет формулу центральной линии по
            // умолчанию; предварительный просмотрDashSplit продолжает обновляться при перетаскивании)

            composer.setTileEdgeOverride(dashSplitPos - DASH_GAP_PX / 2f)
            if (!displaysCreated[0]) {
                log("LAUNCHER: не удалось создать виртуальный экран.")
                detach()
                return false
            }
            // Политика ориентации экрана приложения: **игнорировать направление, запрошенное приложением**, и виртуальный
            // экран остается в той форме, в которой он был создан.

            // Приложения с вертикальным экраном (Toutiao и т. д.) будут запрашивать портрет на телефонах с горизонтальным
            // экраном. Если система соответствует требованиям, вся

            // Поворот виртуального экрана (поворот 0→3 в dumpsys, логический размер 2251×1152→1152×2251),
            // Компоновщик конвертирует по всему отображению кадра, а тач конвертирует по не повернутым координатам -
            // производительность такая "разрешение изменилось +

            // «Клик смещается, и на цель невозможно щелкнуть», и при возвращении на рабочий стол она все еще находится во
            // вращающемся состоянии (фактическое тестирование 18 октября 2026 г.).

            // Сжатая в почтовом ящике (см. соотношение сторон ниже) — это форма, которую должен иметь автомобиль.
            applyIgnoreOrientationRequest(appDisplayId, appHiDisplayId, mapDisplayId)
            // Расслабьте окно почтового ящика приложений с вертикальным экраном (узкое по умолчанию → ширина телефона);
            // восстановить при отключении (отсоединить)

            Shell.exec("cmd window set-letterbox-style --aspectRatio $LETTERBOX_ASPECT_RATIO")
            log(
                "LAUNCHER: соотношение сторон почтового ящика = $LETTERBOX_ASPECT_RATIO" +
                    "appDensity=${appNativeDensity}dpi appHiDensity=${appHiDensity}dpi",
            )
            // Самозапускающийся компонент должен быть записан как «applicationId/абсолютное имя класса»: `am -n` принимает
            // спецификацию только с косой чертой.

            // И относительное имя класса расширяется с помощью applicationId - в пакете отладки .rd (суффикс applicationId).
            // `pkg/.launcher.DockActivity` будет указывать на несуществующий класс, а чистое имя класса будет напрямую отклонено.
            // (Неверное имя компонента), оба способа записи приведут к тому, что док-станция/сетка не запустится, а экран
            // автомобиля станет черным.

            // 0 кадров в секунду (фактическое измерение в пакете от 21 октября 2026 г.). Абсолютное имя класса берется из
            // ссылки на класс и автоматически сопровождается рефакторингом.

            val selfPkg = context.packageName
            val dockOk = startOn(dockDisplayId, "$selfPkg/${DockActivity::class.java.name}")
            gridDisplayId = if (appHiDisplayId >= 0) appHiDisplayId else appDisplayId
            val gridOk = startOn(gridDisplayId, "$selfPkg/${GridActivity::class.java.name}")
            appContextRef = context.applicationContext
            log("LAUNCHER: dock=$dockOk grid=$gridOk gridDisplay=$gridDisplayId")
            // Когда рабочий стол находится в режиме суперсэмплинга, активный экран по умолчанию = экран суперсэмплинга
            // (рабочий стол отображается при запуске).

            if (appHiDisplayId >= 0) composer.requestActiveApp(true)
            startShizukuHealer()
            // Компонент темного экрана готов (всегда включен + функция темного экрана; главный переключатель интерфейса
            // управляет запуском и остановкой)

            ScreenDimmer
                .attach(context)
            dockOk && gridOk
        } catch (error: IllegalStateException) {
            log("LAUNCHER: не удалось запустить композитор GL – ${error.message}")
            detach()
            false
        }
    }

    /** Флаг доверенного экрана: если значение равно true, виртуальный экран удерживается оболочкой UserService и должен быть освобожден службой. */
    @Volatile
    private var displaysViaService = false

    fun detach() {
        ScreenDimmer
            .detach()
        // Политика направления восстановления (false = следовать запросу приложения, системное значение по умолчанию).
        //
        // **должен быть асинхронным и не считываться**: отсоединение запускается функцией Disconnect() в потоке
        // пользовательского интерфейса, и здесь каждый

        // `Shell.exec` — это все Shizuku newProcess + ожидание процесса (один уровень 100 мс, доступный в случае исключения)
        // тайм-аут в секундах). Трех экранов × (set+get) = 6 циклов туда и обратно достаточно, чтобы вызвать ANR
        // (фактическое измерение на 18 октября 2026 г.:

        // После ручного отключения постоянно выскакивает сообщение «Приложение не отвечает». Перейдите на однократную
        // доставку в фоновый режим и больше не читайте обратно——

        // Это путь **очистки**. В случае неудачи останется только политика одного направления, что не повлияет на корректность сессии;
        // Проверка обратного чтения требуется только при установлении пути (applyIgnoreOrientationRequest).
        //
        // Время выполнения: виртуальный экран высвобождается сразу после доставки, но фоновая задача и выпуск
        // выполняются одновременно, чтобы гарантировать, что команда

        // Попаданием является **все еще действующий displayId**. Здесь строка идентификатора сначала синхронизируется и
        // ставится в очередь во время доставки;

        // Если он был выпущен (идентификатор недействителен), команда сообщит только об ошибке и не повлияет на
        // последующие сеансы (он будет сброшен во время следующего раунда создания).

        val orientationIds = intArrayOf(appDisplayId, appHiDisplayId, mapDisplayId).filter { it >= 0 }
        if (orientationIds.isNotEmpty()) {
            Bg.run("orientation-cleanup") {
                orientationIds.forEach { runCatching { setIgnoreOrientationRequest(it, false, verify = false) } }
            }
        }
        if (displaysViaService) {
            val svc = CarInputClient.service
            if (svc != null) {
                if (dockDisplayId >= 0) runCatching { svc.releaseDisplay(dockDisplayId) }
                if (appDisplayId >= 0) runCatching { svc.releaseDisplay(appDisplayId) }
                if (appHiDisplayId >= 0) runCatching { svc.releaseDisplay(appHiDisplayId) }
                // Экран карты также удерживается оболочкой: если его не отпустить, он останется после отключения (обнаруживается
                // при обнаружении запуска после переустановки)

                if (mapDisplayId >= 0) runCatching { svc.releaseDisplay(mapDisplayId) }
            }
            displaysViaService = false
        }
        appContextRef = null
        watchdogActive = false
        // Восстановить соотношение сторон почтового ящика до системного значения по умолчанию (не оставляя параметры сеанса в системе).
        // **должен выполняться в фоновом режиме**: это команда оболочки (ограничение по времени ожидания — 6 с), и
        // отсоединение может быть заблокировано основным потоком.

        // Или запускается поток обратного вызова Шизуку; в журнале K60E от 22 октября 2026 г. есть «SHELL: ⚠ вызов основного потока».
        // оболочка (первый раз) cmd=cmd окно сброса аспекта в стиле почтового ящика».
        Bg.run("letterbox-reset") {
            runCatching { Shell.exec("cmd window reset-letterbox-style aspectRatio") }
        }
        runCatching { composer?.stop() }
        composer = null
        runCatching { dockDisplay?.release() }
        dockDisplay = null
        runCatching { appDisplay?.release() }
        appDisplay = null
        runCatching { appHiDisplay?.release() }
        appHiDisplay = null
        runCatching { mapDisplay?.release() }
        mapDisplay = null
        dockDisplayId = -1
        appDisplayId = -1
        appHiDisplayId = -1
        gridDisplayId = -1
        mapDisplayId = -1
        mapTileWidth = 0
        mapTileHeight = 0
        dashSplitPos = -1f
        mapDisplayW = 0
        mapDisplayH = 0
        appDisplayW = 0
        appDisplayH = 0
        appHiDisplayW = 0
        appHiDisplayH = 0
        appAreaWidth = 0
        appAreaHeight = 0
        mapFullscreen = false
        // Карточка подсказки принадлежит состоянию сеанса: очищайте ее, когда экран разделен (композитор остановлен, а
        // текстура карты уничтожена с контекстом GL)

        LetterboxHintController.clear()
        if (dashboardMode) {
            dashboardMode = false
            notifyDashboard()
        }
        dockWidthPx = 0
        dockHeightPx = 0
        appHeightPx = 0
        dockAtBottom = false
        hiScale = 1f
        streamWidthPx = 0
        streamHeightPx = 0
    }

    private fun startOn(
        displayId: Int,
        component: String,
    ): Boolean {
        if (displayId < 0) return false
        val result =
            Shell.exec(
                "am start -n $component --display $displayId --windowingMode 1",
                timeoutMs = 8000,
            )
        return result.ok && result.output.contains("Starting:", ignoreCase = true)
    }

    /** Возврат на рабочий стол: задача GridActivity возвращается на передний план (фоновое приложение сохраняется); композитор переключается обратно на экран, на котором находится рабочий стол.
     *
     * Запуск оболочки       должен быть асинхронным: вызывающая сторона (док-станция возвращается на рабочий
     * стол/рабочий пейджер останавливается) все находятся в основном потоке.
     */
    fun goGrid() {
        val grid = if (gridDisplayId >= 0) gridDisplayId else appDisplayId
        val pkg = appContextRef?.packageName ?: return
        Bg.run("go-grid") {
            if (!startOn(grid, "$pkg/${GridActivity::class.java.name}")) Log.w(TAG, "Не удалось вернуться на рабочий стол")
        }
        composer?.requestActiveApp(appHiDisplayId >= 0 && grid == appHiDisplayId)
        // Подсказка на рабочем столе не имеет смысла (верхний слой не является сторонним приложением), поэтому просто закройте его.
        LetterboxHintController.clear()
    }

    // ---- Приборная панель (разделенный экран) и цикл режима домашней клавиши ----
    //
    // Семантика главного ключа (определяется пользователем): Приложение → Панель управления; Рабочий стол → Панель
    // управления; Панель управления → Рабочий стол.

    // Панель мониторинга = левая половина области приложения – это фрагмент карты (размер экрана карты изменяется в
    // соответствии с размером фрагмента), а правая половина – это фрагмент карты.

    // Музыкальная карта GridActivity; задачи карты остаются на экране карты, полноэкранный ⇄ разделенный экран
    // только изменяет размер, но не перемещается,

    // Навигация и другой статус приложения не теряются.

    /**
     * Клавиша ⌂ дока : цикл режимов (панель управления ⇄ рабочий стол; приложение/полноэкранная карта → панель инструментов).
     * Обратите внимание, что сначала необходимо сбросить MapFullscreen — шлюз маршрутизации зависит от него, и
     * пропущенный сброс затронет рабочий стол.
     * продолжает отправляться на экран скрытой карты (фактический тест: причина, по которой на рабочий стол нельзя
     * щелкнуть, но внедрение журнала прошло успешно).
     */
    fun onHomePressed() {
        when {
            mapFullscreen -> {
                setMapFullscreen(false)
                showDashboard()
            }

            dashboardMode -> showDesktop()
            else -> showDashboard()
        }
    }

    /** Войдите в панель управления: экран карты сворачивается в плитки, оккупация экрана карты по умолчанию (включена, если отсутствует) и макет разделенного экрана рабочего стола. */
    fun showDashboard() {
        syncDashboard(true)
        goGrid()
        composer?.requestActiveApp(appHiDisplayId >= 0 && gridDisplayId == appHiDisplayId)
        logSink?.invoke("LAUNCHER: режим панели управления (карта + музыкальная карта)")
    }

    /**
     * Единственный порт согласования в режиме информационной панели: установите DashboardMode → уведомление (значок
     * закрепления/пейджер GridActivity)
     * → Выполните миссию на карте и переключитесь на SPLIT при входе и спрячьте плитку при выходе.
     * Во время процесса скольжения пейджера       выполняется только предварительный просмотр смещения плитки
     * (showMapTile), а статус всегда согласовывается при его урегулировании (IDLE).
     */
    fun syncDashboard(dashboard: Boolean) {
        val changed = dashboardMode != dashboard
        dashboardMode = dashboard
        if (changed) notifyDashboard()
        if (dashboard) {
            Bg.run("dashboard-enter") {
                ensureMapTask()
                if (mapFullscreen) setMapFullscreen(false)
                // В течение этого периода пользователь мог нажать на обычное приложение (launchApp установит значение СКРЫТО и
                // закроет панель управления):

                // Режим просмотра, не закрывайте резервный слой карты
                if (dashboardMode) {
                    resizeMapDisplay(fullscreen = false)
                    composer?.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
                }
            }
        } else {
            composer?.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
        }
    }

    /** Скользящий предварительный просмотр: видны только вырезанные плитки (не перемещайте DashboardMode/monitoring), и они будут согласованы с помощью syncDashboard после завершения. */
    fun showMapTile() {
        composer?.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
    }

    /** Вернитесь к обычному рабочему столу: скройте слой карты и выйдите из панели управления, а рабочий стол вернется к сетке значков во всю ширину (задачи карты сохраняются). */
    fun showDesktop() {
        syncDashboard(false)
        goGrid()
        logSink?.invoke("LAUNCHER: режим рабочего стола (сетка значков)")
    }

    /**
     * Экран карты в полноэкранном режиме ⇄ разделенный экран: динамическое изменение размера экрана карты до
     * целевого размера (полный экран = область приложения, разделенный экран = размер отображения плитки),
     * Приложения экранной карты отправляются системой для настройки изменений и ретрансляции (задачи не переносятся,
     * навигация и другие состояния, естественно, продолжаются).
     * Синтезатор переключает макет синхронно: после изменения размера содержимое экрана карты является содержимым
     * целевой области, и вся карта отображается без обрезки образцов.
     */
    fun setMapFullscreen(fullscreen: Boolean) {
        if (mapDisplayId < 0 || mapFullscreen == fullscreen) return
        mapFullscreen = fullscreen
        resizeMapDisplay(fullscreen)
        composer?.setTileLayout(
            if (fullscreen) {
                DualDisplayComposer.TileLayout.FULLSCREEN
            } else {
                DualDisplayComposer.TileLayout.SPLIT
            },
        )
        notifyMapLayout()
        logSink?.invoke("LAUNCHER: экран карты: ${if (fullscreen) "во весь экран" else "разделённый"}")
        // После изменения размера экрана карты внутренняя и внешняя рамки почтового ящика изменились: повторное
        // исследование в полноэкранном режиме и отсутствие подсказок, когда плитка разделенного экрана слишком мала.

        if (fullscreen) {
            appContextRef?.let {
                // изменение размера вступило в силу, и целью является экран карты (не нужно ждать, пока компоновщик изменит макет)
                LetterboxHintController.scheduleProbe(it, "Карта во весь экран", displayIdHint = mapDisplayId)
            }
        } else {
            LetterboxHintController.clear()
        }
    }

    /**
     * Предварительный просмотр положения разделенного экрана во время перетаскивания: **Изменяйте компоновщик только
     * для рисования прямоугольника** (текстура карты растягивается и визуализируется целиком и не перемещается).
     * буфер, неподвижный виртуальный экран) — перетаскивание происходит плавно и не приводит к изменению
     * конфигурации картографического приложения. Только отпусти
     * [commitDashSplit] истинное изменение размера.
     *
     * @param центр ручкиstreamPos (пиксель потока, начало координат сверху; альбомная х / книжная y), внутренний зажим
     */
    fun previewDashSplit(streamPos: Float) {
        val clamped = clampDashSplitStream(streamPos) ?: return
        composer?.setTileEdgeOverride(clamped - DASH_GAP_PX / 2f)
    }

    /**
     * Отпустите и успокойтесь: Зажать → Обновить положение ручки и полученный размер плитки → Реально изменить размер экрана карты (включая
     * Синхронизация потребительского буфера      ) → коэффициент постоянства → уведомление (перерезка фрагмента обоев музыкальной карты).
     *
     * @return Центр маркера после зажима (потоковая передача пикселей) — пользовательский интерфейс соответствующим
     * образом корректирует поля, чтобы предотвратить дрейф отображения и состояния.
     */
    fun commitDashSplit(streamPos: Float): Float {
        val clamped = clampDashSplitStream(streamPos) ?: return dashSplitPos
        val geom =
            dashSplitGeom(
                splitMainLen(),
                (clamped - splitMainOffset() - CARD_MARGIN_PX - DASH_GAP_PX / 2f) / splitUsableLen(),
            )
        dashSplitPos = clamped
        dashSplitFraction = geom.fraction
        if (dockAtBottom) mapTileHeight = geom.tileLen else mapTileWidth = geom.tileLen
        composer?.setTileEdgeOverride(clamped - DASH_GAP_PX / 2f)
        if (!mapFullscreen) resizeMapDisplay(false)
        notifyMapLayout()
        appContextRef
            ?.getSharedPreferences("settings", Context.MODE_PRIVATE)
            ?.edit()
            ?.putFloat(PREF_KEY_DASH_SPLIT, dashSplitFraction)
            ?.apply()
        logSink?.invoke(
            "LAUNCHER: разделение экрана приборной панели разрешено ${if (dockAtBottom) "высота карты" else "ширина карты"}=" +
                "${if (dockAtBottom) mapTileHeight else mapTileWidth}px (пропорция" +
                String.format("%.2f", dashSplitFraction) + "）",
        )
        return clamped
    }

    /** Текущий центр дескриптора преобразуется в пиксели экрана приложения (используется для макета GridActivity; возвращает -1, если не прикреплен). */
    fun dashSplitScreenPos(): Float = if (dashSplitPos < 0) -1f else dashSplitPos * gridScale()

    /** Центр дескриптора · пиксель экрана приложения (система координат области приложения, смещение стыковки прикреплено; начальная точка маркера ВНИЗ, <0 = не прикреплена). */
    fun dashHandleCenterScreen(): Float = if (dashSplitPos < 0) -1f else (dashSplitPos - splitMainOffset()) * gridScale()

    /** Начиная со стороны содержимого пикселя экрана приложения музыкальной карты (горизонтально = левый край, вертикально = верхний край; <0 = не прикреплено). */
    fun dashMusicStartScreen(): Int =
        if (dashSplitPos < 0) {
            -1
        } else {
            ((dashSplitPos - splitMainOffset() + DASH_GAP_PX / 2f) * gridScale()).toInt()
        }

    /** Пиксели экрана приложения [previewDashSplit] вводятся в эталонную версию (перетащите маркер для прямой настройки и внутренней замены пикселей потока). */
    fun previewDashSplitScreen(centerScreen: Float) {
        previewDashSplit(splitMainOffset() + centerScreen / gridScale().coerceAtLeast(1e-6f))
    }

    /** Пиксели экрана приложения [commitDashSplit] введены в эталонную версию; возвращается зажатый центр дескриптора (пиксели экрана приложения). */
    fun commitDashSplitScreen(centerScreen: Float): Float =
        (
            commitDashSplit(splitMainOffset() + centerScreen / gridScale().coerceAtLeast(1e-6f)) -
                splitMainOffset()
        ) * gridScale()

    /**
     * Попадает ли контакт в полосу маркера разделенного экрана (координаты пикселей потока) — при определении липкой
     * маршрутизации TouchRouter используется:
     * Нажмите и ударьте, чтобы зафиксировать экран приложения маршрутизации для всего потока касания (манипулятор не
     * изменится, если палец находится за пальцем во время перетаскивания или координаты выходят за пределы).
     * В противном случае перерегистрация между экранами прервет перетаскивание и случайно коснется карты.
     * Рассмотрение плана будет завершено 21 октября 2026 г.).
     */
    fun dashHandleHit(
        x: Float,
        y: Float,
    ): Boolean {
        if (dashSplitPos < 0 || !dashboardMode || mapFullscreen) return false
        val half = DASH_HANDLE_TOUCH_PX / 2f
        return if (dockAtBottom) {
            y in dashSplitPos - half..dashSplitPos + half
        } else {
            x in dashSplitPos - half..dashSplitPos + half
        }
    }

    /** Центр маркера координат потока → смещение системы координат области приложения (горизонтальный экран минус ширина закрепления; начало координат по оси Y на вертикальном экране — это начало потока). */
    private fun splitMainOffset(): Float = if (dockAtBottom) 0f else dockWidthPx.toFloat()

    /** Доступная длина главной оси = главная ось области применения — поля на обоих концах — ширина центрального шва (та же формула, что и у DashSplitGeom). */
    private fun splitUsableLen(): Int = (if (dockAtBottom) appAreaHeight else appAreaWidth) - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()

    /**
     * фиксирует центр ручки координат потока; возвращает ноль, если он не прикреплен.
     * Границы должны быть преобразованы в соответствии с **областью пропорционального ограничения** (20%~80%) —
     * нажмите [поле, поле+доступно] для ограничения, равное разрешению.
     * Коэффициент       [0,1], карту можно вытянуть в линию (основная причина «вытягивания до 0», измеренная
     * пользователями 22 октября 2026 г.).
     */
    private fun clampDashSplitStream(streamPos: Float): Float? {
        val usable = splitUsableLen()
        if (usable <= 0) return null
        val base = splitMainOffset() + CARD_MARGIN_PX + DASH_GAP_PX / 2f
        val min = base + DASH_SPLIT_MIN_FRACTION * usable
        val max = base + DASH_SPLIT_MAX_FRACTION * usable
        return streamPos.coerceIn(min, max)
    }

    /**
     * изменяет размер экрана карты до целевого размера макета (идемпотент: размер остается неизменным - каждое
     * изменение размера запускает экран
     * Изменения/перенастройка конфигурации приложения, по возможности избегайте). Шизуку сохраняет свой текущий
     * размер, когда он не связан или не может быть вызван.
     * Полное отображение композитора       все еще существует (но плотность пользовательского интерфейса карты в
     * настоящее время не соответствует видимой области).
     */
    private fun resizeMapDisplay(fullscreen: Boolean) {
        val id = mapDisplayId
        if (id < 0) return
        val w = if (fullscreen) appAreaWidth else mapTileWidth
        val h = if (fullscreen) appAreaHeight else mapTileHeight
        if (w <= 0 || h <= 0 || (w == mapDisplayW && h == mapDisplayH)) return
        runCatching {
            val applied =
                if (displaysViaService) {
                    // Доверенная форма: изменение размера стороны оболочки
                    val svc = CarInputClient.service ?: return@runCatching
                    svc.resizeDisplay(id, w, h, MAP_DISPLAY_DPI)
                } else {
                    // Резервное состояние: экран создается этим приложением, а API напрямую доступен на стороне приложения — пары оболочки.
                    // Дисплей, созданный другими, не имеет разрешения на изменение размера (проверка владельца токена).
                    val display = mapDisplay ?: return@runCatching
                    display.resize(w, h, MAP_DISPLAY_DPI)
                    true
                }
            if (applied) {
                mapDisplayW = w
                mapDisplayH = h
                // Размер потребительского буфера должен быть синхронизирован — в противном случае содержимое нового размера
                // будет отрисовано в левой верхней части старого буфера.

                // (Содержимое плитки находится слева, ширина — это только значение пропорции, а правая сторона полностью черная)
                composer?.resizeMapInput(w, h)
                logSink?.invoke(
                    "LAUNCHER: размер карты → ${w}x$h@${MAP_DISPLAY_DPI}dpi " +
                        "(${if (fullscreen) "во весь экран" else "разделённый"})",
                )
            }
        }
    }

    /**
     * Приложение карты запускается на экране карты (полноэкранный режим): изменение размера экрана карты → запуск →
     * полноэкранный режим синтезатора.
     * Переключение между картографическими приложениями (Amap ↔ Baidu) также осуществляется на одном экране, а
     * старые задачи очищаются (взаимоисключающие).
     */
    private fun launchMapOnMapDisplay(component: ComponentName): Boolean {
        dashboardMode = false
        notifyDashboard()
        setMapFullscreen(true)
        // Сбрасывается, когда на одном экране есть другие задачи карты (белый список является взаимоисключающим; задача
        // карты по умолчанию сохраняется для повторного использования).

        val ctx = appContextRef
        val defaultPkg = ctx?.let { defaultMapComponent(it)?.packageName }
        if (component.packageName != defaultPkg) {
            taskIdsOnDisplayPkg(component.packageName, mapDisplayId).forEach { taskId ->
                Shell.exec("am stack remove $taskId", timeoutMs = 4000)
            }
        }
        val result = startMapApp(component, mapDisplayId, forceNewTask = true)
        val ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
        logSink?.invoke(
            "ЗАПУСК(${component.packageName}): → Экран карты в полноэкранном режиме ok=$ok out=${result.output.take(80)}",
        )
        if (ok) {
            synchronized(runningApps) { runningApps.add(component) }
            notifyRunningAppsChanged()
            appContextRef?.let {
                LetterboxHintController.scheduleProbe(it, "Начало карты", displayIdHint = mapDisplayId)
            }
        }
        return ok
    }

    /** Укажите на экране список идентификаторов задач для указанного пакета (для очистки экрана). */
    private fun taskIdsOnDisplayPkg(
        pkg: String,
        displayId: Int,
    ): List<String> = tasksOf(taskIdsByDisplayPkg(), pkg, displayId)

    /**
     * Запустите приложение карты на указанном экране: нажмите [mapLaunchCandidates], чтобы попробовать `am start -n`
     * по одному кандидату за раз.
     *
     * Явный основной интерфейс автомобильной версии карты       (Amap `MainMapActivity`, Baidu `NaviAutoActivity`) находится в
     * может отсутствовать в некоторых настроенных прошивках/версиях, а жесткое кодирование одной точки приведет к
     * тому, что экран карты станет черным, а в журнале будет отсутствовать только одно предложение «`Starting:`».
     * Таким образом, если первый кандидат потерпит неудачу, он вернется к компоненту, проанализированному средством
     * запуска, и зарегистрирует каждую попытку.
     */
    private fun startMapApp(
        component: ComponentName,
        displayId: Int,
        forceNewTask: Boolean,
        extraArgs: String = "",
    ): Shell.Result {
        val suffix =
            (if (forceNewTask) " -f 0x18000000" else "") +
                (if (extraArgs.isEmpty()) "" else " $extraArgs")
        var last: Shell.Result? = null
        for (target in mapLaunchCandidates(component)) {
            val result = Shell.exec("am start -n $target --display $displayId$suffix", timeoutMs = 8000)
            val ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
            if (ok) return result
            logSink?.invoke(
                "ЗАПУСК (${component.packageName}): Не удалось запустить запись $target (${result.output.take(80)}) — " +
                    "попробуйте следующий кандидат.",
            )
            last = result
        }
        return last ?: Shell.exec("am start -n ${component.flattenToShortString()} --display $displayId$suffix", timeoutMs = 8000)
    }

    /**
     * , как только `dumpsys activity activities` анализирует «displayId → пакет → список идентификаторов задач».
     * Каждая из трех предыдущих функций запроса создает дамп один раз (launchApp может запускаться до 3 раз за раз,
     * с тайм-аутом 4 секунды для каждого раза.
     * команда оболочки      ) — отсюда единообразно делать снимки для повторного использования.
     */
    private fun taskIdsByDisplayPkg(): Map<Int, Map<String, List<String>>> {
        val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4000).output
        var current = -1
        val out = HashMap<Int, HashMap<String, MutableList<String>>>()
        for (line in dump.lineSequence()) {
            displayRegex.find(line)?.let { current = it.groupValues[1].toInt() }
            val task = taskRegex.find(line) ?: continue
            out
                .getOrPut(current) { HashMap() }
                .getOrPut(task.groupValues[2]) { ArrayList() }
                .add(task.groupValues[1])
        }
        return out
    }

    private fun tasksOf(
        snapshot: Map<Int, Map<String, List<String>>>,
        pkg: String,
        displayId: Int,
    ): List<String> = snapshot[displayId]?.get(pkg).orEmpty()

    /**
     * Убедитесь, что на экране карты отображается карта по умолчанию: начинаю выполнять существующие задачи =
     * возвращаю на стойку регистрации (статус сохраняется, навигация продолжается),
     * Нет задачи = начало нового. Фоновый вызов потока.
     */
    private fun ensureMapTask() {
        if (mapDisplayId < 0) {
            // Диагностика (18.10.2026 "Карта карты не рендерится после переустановки"): Раньше возвращалась молча и не распознавалась.
            // «Экран карты не создан» и «Экран карты создан, но приложение не запущено»
            logSink?.invoke("LAUNCHER: экран карты не создан (mapDisplayId=-1) – пропустить запуск карты по умолчанию.")
            return
        }
        val ctx =
            appContextRef ?: run {
                logSink?.invoke("LAUNCHER: appContext не готов – пропуск запуска карты по умолчанию")
                return
            }
        val component =
            defaultMapComponent(ctx) ?: run {
                logSink?.invoke("ЗАПУСК: Приложение карты не найдено (внесено в белый список) – ячейка карты пуста")
                return
            }
        // Без MULTIPLE_TASK: когда задача уже находится на экране карты, это эквивалентно ее возвращению на передний план.
        val result = startMapApp(component, mapDisplayId, forceNewTask = false)
        logSink?.invoke(
            "LAUNCHER: Карта по умолчанию ${component.packageName} → экран карты (display=$mapDisplayId) ok=" +
                (result.ok && result.output.contains("Starting:", ignoreCase = true)) +
                (if (!result.ok) " err=${result.output.take(120)}" else ""),
        )
    }

    /**
     * начать/Переключить приложение。
     * Целевые экраны маршрутизируются в соответствии с предпочтениями передискретизации приложения.（SupersamplePrefs）：
     * - Передискретизация включена（Или автоматически определите, что это приложение с вертикальным экраном.）→ Экран
     * передискретизации（1551×810 убрать，Верхняя полоса фиксированного пикселя имеет небольшую долю）
     * - Передискретизация выключена（Горизонтальный экран/Класс карты）→ родной экран（1:1 рендеринг，яснее、GPU накладные расходы 1/2.25）
     *
     * Бен ROM（MagicOS）измеренное поведение：`am start --display X` Выполняйте только существующие задачи"принесли на стойку регистрации"，
     * не падай X экран；`am task move` тишинаНетэффективный；`am task remove` не существует，Для очистки задач
     * `am stack remove <taskId>`（Эта версия RootTask id == taskId）。проверено и проверено：
     * 1) На целевом экране уже есть эта задача пакета. → Обычный запуск возвращает на стойку регистрации.（повторное
     * использование，Сохранить стек задач）
     * 2) Нет целевого экрана → Сначала очистите пакет**Еще один экран приложения**назадача（Избегайте двойных
     * экземпляров после переключения предпочтений），
     * снова `-f 0x18000000`（NEW_TASK|MULTIPLE_TASK）Создайте новый экземпляр на целевом экране
     *
     * Известные ограничения（2026-10-10 Фактическое измерение）：Когда приложение также имеет экземпляр на мобильном
     * телефоне，Базовая плотность процесса застряла на уровне
     * экран мобильного телефона（560dpi），Текст, отображаемый на экране автомобиля, слишком велик（≈2.2
     * раз）。Единственное лекарство - принудительно перезапустить процесс.，
     * Но это уничтожит экземпляр, используемый на мобильном телефоне пользователя.（неприемлемо）——Поэтому мы будем
     * сохранять статус-кво и не заниматься этим.。
     */
    fun launchApp(component: ComponentName): Boolean {
        if (appDisplayId < 0) return false
        // Наложение «принудительного ландшафта» при установлении сеанса записывается асинхронно, и am compat немедленно
        // уничтожит целевое приложение:

        // Перед запуском необходимо дождаться очистки очереди, иначе приложение будет перезаписано и завершено сразу
        // после запуска (отображается как сбой/возврат на экран мобильного телефона).

        // Исполнитель однопоточный FIFO, при нормальных обстоятельствах покрытие уже записано и возвращается сразу сюда.
        AppOrientationCompat.awaitPending()
        // Приложение карты → полноэкранный экран карты (задача всегда находится на экране карты; нажмите на сетку на
        // рабочем столе, чтобы перейти на экран карты,

        // При использовании той же задачи, что и в панели управления, статус навигации не теряется)
        if (isMapPackage(component.packageName) && mapDisplayId >= 0) {
            return launchMapOnMapDisplay(component)
        }
        val ctx = appContextRef
        val wantHi =
            ctx != null &&
                appHiDisplayId >= 0 &&
                SupersamplePrefs.shouldSupersample(ctx, component)
        val targetDisplay = if (wantHi) appHiDisplayId else appDisplayId
        // Запросы задач используют снимок dumpsys (ранее onTarget + очистка двух экранов выполнялась максимум 3 раза с
        // командой таймаута 4 секунды)

        val tasks = taskIdsByDisplayPkg()
        val onTarget = tasksOf(tasks, component.packageName, targetDisplay).isNotEmpty()
        if (!onTarget) {
            // Очистка старых задач на другом экране приложения после переключения настроек (двойные экземпляры на разных
            // экранах сохраняют свои состояния, что легко перепутать)

            val otherDisplay = if (targetDisplay == appHiDisplayId) appDisplayId else appHiDisplayId
            if (otherDisplay >= 0) {
                tasksOf(tasks, component.packageName, otherDisplay).forEach { taskId ->
                    Shell.exec("am stack remove $taskId", timeoutMs = 4000)
                    Log.i(TAG, "Задача очистки старого экрана pkg=${component.packageName} Task=$taskId display=$otherDisplay")
                }
            }
        }
        var ok: Boolean
        val result: Shell.Result
        if (isMapPackage(component.packageName)) {
            // Резервный вариант, когда экран карты недоступен (mapDisplayId < 0): по-прежнему нажмите главный интерфейс
            // автомобиля, чтобы запустить его,

            // Но переходит на экран приложения и включает полноэкранный режим окна.
            result = startMapApp(component, targetDisplay, forceNewTask = !onTarget, extraArgs = "--windowingMode 1")
            ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
            if (!ok && onTarget) {
                val retry =
                    startMapApp(component, targetDisplay, forceNewTask = true, extraArgs = "--windowingMode 1")
                ok = retry.ok && retry.output.contains("Starting:", ignoreCase = true)
            }
        } else {
            val target = component.flattenToShortString()
            result =
                if (onTarget) {
                    Shell.exec("am start -n $target --display $targetDisplay", timeoutMs = 8000)
                } else {
                    // MULTIPLE_TASK: даже если у приложения есть задачи на других экранах, оно вынуждено создать новый экземпляр на
                    // целевом экране.

                    Shell.exec("am start -n $target --display $targetDisplay -f 0x18000000", timeoutMs = 8000)
                }
            ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
            if (!ok && onTarget) {
                // Не удалось повторно использовать путь (возможно, задача была перемещена системой): вернуться к MULTIPLE_TASK Создать.
                val retry = Shell.exec("am start -n $target --display $targetDisplay -f 0x18000000", timeoutMs = 8000)
                ok = retry.ok && retry.output.contains("Starting:", ignoreCase = true)
            }
        }
        logSink?.invoke(
            "LAUNCH(${component.packageName}): hi=$wantHi disp=$targetDisplay onTarget=$onTarget ok=$ok " +
                "out=${result.output.take(80)}",
        )
        if (ok) {
            synchronized(runningApps) { runningApps.add(component) }
            notifyRunningAppsChanged()
            // Откройте обычное приложение на панели управления/полноэкранной карте: выйдите из панели управления и скройте слой карты,
            // В противном случае полоса SPLIT продолжает рисоваться в приложении (фрагмент измеренной карты закрывает открытое приложение)
            if (dashboardMode || mapFullscreen) {
                dashboardMode = false
                mapFullscreen = false
                notifyDashboard()
                composer?.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
                logSink?.invoke("LAUNCHER: выход из панели управления (обычное приложение открыто)")
            }
            // Синтезатор переключается на целевой экран (экран с суперсэмплированием ждет, пока он выйдет из кадра, прежде
            // чем переключиться; родной экран переключается мгновенно)

            composer?.requestActiveApp(targetDisplay == appHiDisplayId)
            // Если приложение уже запущено и выведено системой на передний план в соответствии со старой конфигурацией:
            // проверьте, работает ли оно после запуска.

            // почтовый ящик (карточка с подсказкой действует только для приложений из списка «Приложения», другие приложения не включаются)
            val ctx = appContextRef
            if (ctx != null) {
                LetterboxHintController.scheduleProbe(ctx, "Запуск приложения", displayIdHint = targetDisplay)
            }
        } else {
            Log.w(TAG, "Ошибка запуска: ${result.output.take(120)}")
        }
        return ok
    }

    // Китайские иероглифы ICU → Транслитерация пиньинь (встроена в Android, сторонняя библиотека пиньинь не требуется).
    // «Han-Latin» выводит тоны, добавляет тоны Latin-ASCII (гао → гао).
    // Транслитератор не является потокобезопасным, и доступ равномерно блокируется.
    private val hanLatinLock = Any()
    private val hanLatin by lazy {
        android.icu.text.Transliterator
            .getInstance("Han-Latin; Latin-ASCII")
    }

    private fun toLatin(text: String): String =
        synchronized(hanLatinLock) {
            runCatching { hanLatin.transliterate(text) }.getOrDefault(text)
        }

    /** Ключ сортировки: преобразуйте полное имя в нижний регистр пиньинь, сохраняйте только буквы и цифры (цифры <буквы, то есть группа #, естественно, идет первой). */
    private fun pinyinKey(label: String): String = toLatin(label).lowercase().filter { it.isLetterOrDigit() }

    /** Клавиша сортировки пиньинь (используется для сортировки внешних списков, таких как страницы настроек, по алфавитной группировке). */
    fun sortKeyOf(label: String): String = pinyinKey(label)

    @Volatile
    private var appsCache: List<AppEntry>? = null

    /** Кэш списка приложений (общий для настроек рабочего стола и автомобиля; недействителен при установке/удалении трансляции). */
    fun cachedApps(context: Context): List<AppEntry> = appsCache ?: scanLaunchableApps(context).also { appsCache = it }

    /**
     * только читает общий кеш, **никогда не сканирует**: страницы настроек и т. д. должны использовать это в
     * интерфейсе, где основной поток извлекает список.
     * будет сканировать асинхронно самостоятельно при промахе кеша (механизм синхронизации кэшированных приложений
     * перенесет полное сканирование приложения в поток пользовательского интерфейса).
     */
    fun peekCachedApps(): List<AppEntry>? = appsCache

    /** Включайте результаты сканирования внешнего потока в общий кеш (страницу настроек можно открыть сразу после сканирования рабочего стола). */
    fun adoptAppsCache(apps: List<AppEntry>) {
        if (appsCache == null) appsCache = apps
    }

    fun invalidateAppsCache() {
        appsCache = null
    }

    fun launchableApps(context: Context): List<AppEntry> = scanLaunchableApps(context)

    /**
     * сканирует все запускаемые приложения (сетка рабочего стола используется совместно со страницей настроек).
     *
     * должен быть отказоустойчивым в трех областях (проверено после отзыва пользователей от 23 октября 2026 г. о
     * том, что «некоторые приложения не были отсканированы»):
     * 1. **Больше нет `take(200)`**: старая реализация записывает верхний предел **перед `sortedBy`**, т.е.
     * `queryIntentActivities` Возвращает первые 200 элементов последовательности — если приложение установлено в
     * течение длительного времени, последующие будут потеряны напрямую.
     * и какие из них потеряны, зависит от порядка возврата системы (выглядит как "некоторые пропущены случайно"). Оставьте их все пока.
     * 2. **Попытка/перехват приложения для каждого приложения**: `loadLabel`/`loadIcon` выдает APK с исключениями ресурсов.
     * `Resources.NotFoundException` и т. д.; старая реализация представляет собой цепочку последовательностей, и плохое приложение будет
     * `toList()` выкидывает весь → рабочий стол **ни одно приложение не отображается**. Теперь единичные сбои пропускаются и учитываются.
     * 3. **Удалите дубликаты из одного пакета**: пакет может иметь несколько действий запуска (фактически на K40 измерено 83).
     * Активность       соответствует только 82 пакетам (com.android.contacts — 4), и старая реализация будет отображаться повторно.
     *
     * Приложения, которые       по-прежнему не может найти, относятся к уровню видимости/многопользовательского
     * уровня пакета Android и не находятся под контролем этой функции:
     * - рабочий профиль, клонирование/двойное открытие приложения под другим идентификатором пользователя,
     * `queryIntentActivities` возвращает только текущего пользователя;
     * - Функция ПЗУ "Скрыть приложение" сделает приложение невидимым для этого процесса;
     * — Приложения только с записями LEANBACK/пользовательской категории не относятся к семантике LAUNCHER.
     */
    private fun scanLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent =
            android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
        val resolved =
            try {
                pm.queryIntentActivities(intent, 0)
            } catch (error: Throwable) {
                logSink?.invoke("LAUNCHER: Ошибка сканирования приложения – ${error.message} (рабочий стол будет пуст)")
                return emptyList()
            }
        var skipped = 0
        val infos = resolved.mapNotNull { it.activityInfo }
        // Дедупликация нескольких записей в одном пакете (о стратегиях см. в разделе dedupeLauncherPackages, есть
        // отдельные тесты): сохранять первую запись каждого пакета.

        val keepOrder = dedupeLauncherPackages(infos.map { it.packageName }, context.packageName)
        val firstInfoByPackage = LinkedHashMap<String, android.content.pm.ActivityInfo>(keepOrder.size)
        infos.forEach { info -> firstInfoByPackage.putIfAbsent(info.packageName, info) }
        // Ключи пиньинь учитываются только один раз для каждого приложения (ранее они многократно расшифровывались
        // попарно в компараторе, 150 приложений ≈ тысячи вызовов отделения интенсивной терапии)

        val apps =
            keepOrder
                .mapNotNull { firstInfoByPackage[it] }
                .mapNotNull { info ->
                    // Одно приложение loadLabel/loadIcon, выдающее исключение, не может обрушить весь список.
                    runCatching {
                        val label = info.loadLabel(pm).toString().ifBlank { info.packageName }
                        AppEntry(
                            component = ComponentName(info.packageName, info.name),
                            label = label,
                            icon = info.loadIcon(pm),
                        ) to pinyinKey(label)
                    }.onFailure {
                        skipped++
                    }.getOrNull()
                }
        if (skipped > 0 || resolved.size != apps.size) {
            // Диагностические следы: когда пользователи сообщают о «меньшем количестве приложений», эта строка позволяет отличить
            // «Система выдает только такое количество/несколько записей в одном пакете, которые объединены» и «Загрузка
            // метки/значка не удалась и была пропущена»

            logSink?.invoke(
                "LAUNCHER: система сканирования приложений возвращает действия ${resolved.size} → приложения ${apps.size}" +
                    if (skipped > 0) {
                        "($skipped пропущен: не удалось загрузить " +
                            "метку/значок)"
                    } else {
                        "(Несколько записей в одном пакете были объединены)"
                    },
            )
        }
        return apps.sortedBy { it.second }.map { it.first }
    }

    /** Клавиши группировки панели навигации по буквам: цифры → «#», буквы ASCII → заглавные буквы, китайские иероглифы → первая буква пиньинь. */
    fun indexLetterOf(label: String): String {
        val first = label.firstOrNull() ?: return "#"
        return when {
            first.isDigit() -> "#"
            first.isLetter() && first.code < 128 -> first.uppercaseChar().toString()
            else -> {
                val latin = toLatin(first.toString()).lowercase()
                latin.firstOrNull { it in 'a'..'z' }?.uppercaseChar()?.toString() ?: "#"
            }
        }
    }

    // ---- Самоисцеление Шизуку ----

    @Volatile
    private var watchdogActive = false
    private var watchdogThread: Thread? = null

    val taskRegex = Regex("""Task\{[0-9a-f]+ #(\d+) type=standard A=\d+:(\S+)""")
    private val displayRegex = Regex("""Display: mDisplayId=(\d+)""")

    /** Экспорт журнала диагностики (полученный MainActivity в файл журнала). */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    /** Контекст приложения (требуется для самоисцеления Шизуку). */
    @Volatile
    private var appContextRef: android.content.Context? = null

    /**
     * Самовосстановление Shizuku Touch Injection: попробуйте разморозить и повторно подключиться, когда услуга
     * отключена (каждые 4 секунды; в рабочем состоянии).
     * Служба       пропускается напрямую, если она не пуста, нулевые издержки). Решите проблему «необходимо
     * принудительно остановить приложение для перезапуска».
     *
     * Примечание. Оригинальный «Task Guard» (приложение автоматически закрывается после перехода на главный экран)
     * был удален после фактической проверки компьютера 10 октября 2026 г.:
     * Переходы в приложении под доверенным виртуальным экраном естественным образом остаются на экране автомобиля, а
     * при открытии со стороны мобильного телефона они естественным образом следуют на мобильный телефон и с автомобильной док-станции.
     * перезапускается обратно на сторону автомобиля – вмешательство в обратный ход больше не требуется.
     */
    private fun startShizukuHealer() {
        if (watchdogActive) return
        watchdogActive = true
        watchdogThread =
            Thread({
                while (watchdogActive && appDisplayId >= 0) {
                    try {
                        val ctx = appContextRef
                        if (ctx != null && CarInputClient.service == null) {
                            runCatching {
                                Shell.exec("am unfreeze moe.shizuku.privileged.api", timeoutMs = 2500)
                            }
                            CarInputClient
                                .ensure(ctx)
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        Thread.sleep(4000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "shizuku-healer").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * заставляет экран приложения игнорировать направление, запрошенное приложением (сохраняя
     * горизонтальную/вертикальную форму при создании виртуального экрана).
     *
     * Команда       принимает только true/1/false/0 — при передаче `reset` будет сообщено
     * `Error: expecting true, 1, false, 0, but we get reset` и **записывает только stderr**,
     * и `Shell.exec` не читают stderr, поэтому молчание недопустимо (предшественник этого метода был написан так:
     * Политика ориентации       так и не вступила в силу, и Toutiao повернул весь экран AppHi в вертикальный экран).
     * Поэтому после написания здесь его необходимо прочитать для проверки.
     */
    private fun applyIgnoreOrientationRequest(vararg ids: Int) {
        val targets = ids.filter { it >= 0 }
        if (targets.isEmpty()) return
        val failed = targets.filter { id -> !setIgnoreOrientationRequest(id, true) }
        if (failed.isEmpty()) {
            logSink?.invoke(
                "LAUNCHER: экран приложения проигнорировал запрос направления ${targets.joinToString("/")} " +
                    "(Виртуальный экран вращения приложения с защитой от портретного просмотра)",
            )
        } else {
            logSink?.invoke(
                "ЗАПУСК: ⚠ Экран приложения игнорирует ошибку запроса направления $failed (прочитано неверно)" +
                    "——Приложения с вертикальным экраном могут поворачивать виртуальный экран, что приводит к " +
                    "ненормальному соотношению экрана и дрейфу касания.",
            )
        }
    }

    /**
     * устанавливает политику игнорирования направления для одного экрана.
     *
     * Прочтите подтверждение, когда       @param подтвердит true (**используется только для пути установления
     * сеанса** — этот путь уже находится в фоновом потоке,
     * и политика не вступит в силу, она приведет к повороту всего экрана и требует подтверждения). Передайте false,
     * чтобы очистить путь и избежать запуска серии
     * Обращение оболочки       (это была основная причина ANR, 18 октября 2026 г.).
     * @return Всегда истинно, еслиverify=false (только означает, что команда была доставлена)
     */
    private fun setIgnoreOrientationRequest(
        displayId: Int,
        enabled: Boolean,
        verify: Boolean = true,
    ): Boolean {
        if (displayId < 0) return true
        val value = if (enabled) "true" else "false"
        Shell.exec("cmd window set-ignore-orientation-request -d $displayId $value", timeoutMs = 3_000)
        if (!verify) return true
        val readBack =
            Shell
                .exec("cmd window get-ignore-orientation-request -d $displayId", timeoutMs = 3_000)
                .output
        return readBack.contains(value)
    }

    data class AppEntry(
        val component: ComponentName,
        val label: String,
        val icon: Drawable,
    )
}

/**
 * применяет дедупликацию и фильтрацию результатов сканирования (чистая функциональная часть [VirtualLauncher.scanLaunchableApps]).
 *
 * извлекается для одного теста: старая реализация использует `take(200)` и записывается перед сортировкой,
 * которая используется в моделях со многими приложениями.
 * обрезает хвост в порядке возврата системы, показывая, что «иногда отсутствуют несколько приложений» (отзыв пользователя 2026-10-23).
 *
 * @param кандидаты последовательность имени пакета системы `queryIntentActivities` (сохранить исходный порядок)
 * @param selfPackage Имя этого пакета приложения (не отображается на рабочем столе)
 */
internal fun dedupeLauncherPackages(
    candidates: List<String>,
    selfPackage: String,
): List<String> {
    val seen = HashSet<String>(candidates.size)
    return candidates.filter { pkg -> pkg != selfPackage && seen.add(pkg) }
}
