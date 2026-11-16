package io.github.shaonialife.cardeck.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.media.MediaSessionsState
import io.github.shaonialife.cardeck.render.CARD_MARGIN_PX
import io.github.shaonialife.cardeck.render.DASH_GAP_PX
import io.github.shaonialife.cardeck.render.DASH_HANDLE_TOUCH_PX
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * Основная область рабочего стола CarPlay: чистый черный фон + сетка прокрутки значков (горизонтальный экран
 * 4×3, характеристики CarPlay; вертикальный экран в соответствии с сеткой)
 * адаптивное соотношение сторон, до 6 строк, см. ComputeCellMetrics) + нижняя точка страницы.
 * Макет   объявлен в res/layout (activity_grid / item_grid_page / item_app_cell),
 * Код   выполняет только динамический расчет размера сетки и привязку данных. Нет алфавитной навигации (по
 * сравнению с главным экраном CarPlay).
 *
 * по умолчанию отображает только приложения карты (по умолчанию выбрано DesktopApps), а вход в «Настройки
 * автомобиля» зафиксирован в первом кадре;
 * Пользователи могут проверить приложения, которые будут отображаться на странице настроек, и вернуться на
 * рабочий стол (onResume) для немедленного обновления.
 */
class GridActivity : AppCompatActivity() {
    /** Сетка рабочего стола: Страница панели управления (страница 0)/Вход с фиксированными настройками/Общие приложения. */
    private sealed interface Cell {
        object Dashboard : Cell

        object Pinned : Cell

        data class App(
            val entry: VirtualLauncher.AppEntry,
        ) : Cell
    }

    private var pages: List<List<Cell>> = emptyList()
    private var pager: ViewPager2? = null
    private var dotsRow: LinearLayout? = null
    private var cellWidthPx = 0
    private var cellHeightPx = 0
    private var iconSizePx = 0

    /** Текущее количество строк (горизонтальный экран 3; вертикальный экран адаптируется в соответствии с соотношением сторон сетки, емкость страниц = СТОЛБЦЫ × строки). */
    private var rows = ROWS_MIN

    /** Кэш манифеста приложения (возобновление PackageManager каждый раз замедляется до рабочего стола - сканируется только при первой загрузке). */
    private var appsCache: List<VirtualLauncher.AppEntry>? = null

    /** Фоновый слой обоев (фрагмент полнопоточного холста; изменение изображения на заднем плане при смене версии). */
    private var wallpaperView: ImageView? = null
    private var wallpaperShownVersion = -1L

    /** Мониторинг смены обоев (обратный вызов основного потока WallpaperStore; декодирование само переключается на фоновый поток в этом классе). */
    private val wallpaperListener = { _: Long -> refreshWallpaperIfChanged() }

    /** Панель режима информационной панели (отображается в правой половине области приложения при разделенном экране; левая половина закрыта плиткой карты). */
    private var dashboardView: android.view.View? = null

    /** Изменение режима приборной панели (уведомление VirtualLauncher). */
    private val dashboardListener = { dashboard: Boolean ->
        runOnUiThread { applyDashboardMode(dashboard) }
    }

    /** Мониторинг состояния мультимедиа (обратный вызов основного потока MediaSessionsState). */
    private val mediaStateListener = {
        runOnUiThread { renderMediaCard() }
    }

    /** Полное растровое изображение холста готово (обратный вызов после первой сборки/перестройки WallpaperStore). */
    private val canvasReadyListener = { _: Long -> runOnUiThread { refreshDashWallpaper() } }

    /** Визуализация карты (прозрачность/размытие темного экрана) — соответствует док-станции (DockPrefs). */
    private val dockPrefsListener = {
        runOnUiThread {
            applyDashCardChrome()
            refreshDashWallpaper()
        }
    }

    /** Изменения макета карты (переключение полноэкранного/разделенного экрана меняет область фрагмента обоев музыкальной карты).
     */
    private val mapLayoutListener: (Boolean) -> Unit =
        { _ -> runOnUiThread { refreshDashWallpaper() } }

    /**
     * зрение карты приложения: альфа-темный экран и радиус размытия обоев (RenderEffect, радиус меняется вместе с сеткой экрана)
     * Плотность       увеличена в 1,5 раза, чтобы визуально соответствовать док-станции).
     */
    private fun applyDashCardChrome() {
        val panel = dashboardView ?: return
        val scrim = panel.findViewById<View>(R.id.dashScrim)
        val alpha = (DockPrefs.scrimOpacity(this) * 255 / 100).coerceIn(0, 255)
        scrim.background =
            android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(alpha, 0, 0, 0),
            )
        val wallpaper = panel.findViewById<ImageView>(R.id.dashWallpaper)
        val percent = DockPrefs.blur(this)
        if (percent <= 0) {
            wallpaper.setRenderEffect(null)
        } else {
            val radius = percent / 100f * 40f * resources.displayMetrics.density
            wallpaper.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    /**
     * Обои с музыкальной картой: обрезка «области музыкальной карты» в полном фрагменте холста (то же отображение,
     * что и у полноэкранных обоев,
     * Область       = от правой стороны карты карты до правого края карты).
     */
    private fun refreshDashWallpaper() {
        val panel = dashboardView ?: return
        val wallpaper = panel.findViewById<ImageView>(R.id.dashWallpaper) ?: return
        val params = wallpaper.layoutParams ?: return
        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        Bg.run("wallpaper-dash") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@GridActivity,
                        canvasWidth,
                        canvasHeight,
                    )
                }.getOrNull() ?: return@run
            val margin = (CARD_MARGIN_PX * scale).toInt()
            // Вырежьте фрагмент обоев, соответствующий области музыкальной карты (в соответствии с фактическим положением
            // карты, следите за положением маркера разделенного экрана):

            // Горизонтальный экран: правая сторона (левый край = центр ручки + половина ручки); вертикальный экран: нижний
            // (тот же стиль, что и верхний край)

            val musicStart = VirtualLauncher.dashMusicStartScreen()
            val cropX: Int
            val cropY: Int
            if (VirtualLauncher.dockAtBottom) {
                cropX = margin
                cropY =
                    (if (musicStart >= 0) musicStart else margin * 2 + (VirtualLauncher.mapTileHeight * scale).toInt())
                        .coerceAtMost(bitmap.height - 2)
            } else {
                cropX =
                    (
                        if (musicStart >= 0) {
                            musicStart + (VirtualLauncher.dockWidthPx * scale).toInt()
                        } else {
                            (VirtualLauncher.dockWidthPx + VirtualLauncher.appAreaWidth / 2 + CARD_MARGIN_PX / 2) * scale
                        }.toInt()
                    ).coerceAtMost(bitmap.width - 2)
                cropY = margin.coerceAtMost(bitmap.height - 2)
            }
            val cropW = (bitmap.width - cropX - margin).coerceAtLeast(2)
            val cropH = (bitmap.height - cropY - margin).coerceAtLeast(2)
            runOnUiThread {
                wallpaper.setImageBitmap(
                    Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH),
                )
                // Повторите RenderEffect после того, как растровое изображение будет готово (эффекты просмотра все равно должны
                // вступить в силу после обновления растрового изображения).

                applyDashCardChrome()
            }
        }
    }

    /** Прогресс музыкальной карты автоматически обновляется (1 с; хвост рендеринга продолжает планироваться, а позиция рассчитывается на основе точки привязки + скорости). */
    private val mediaTick =
        object : Runnable {
            override fun run() {
                renderMediaCard()
                tickHandler.postDelayed(this, 1_000)
            }
        }

    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** мм:сс (более 1 часа ч:мм:сс). */
    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1_000
        val h = totalSec / 3_600
        val m = totalSec % 3_600 / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    }

    /**
     * Рендеринг музыкальной карты: кнопка обложки/названия/певца/прогресса/воспроизведения (воспроизведение = значок
     * паузы, пауза = значок воспроизведения).
     *
     * В копирайтинге следует различать три причины «отсутствия названия песни», иначе это будет вводить в заблуждение:
     * 1. Сеанс воспроизводится, но **нет прав на уведомление** → метаданные всегда равны нулю (статус сеанса и воспроизведения).
     * все еще виден). В это время отображается сообщение «Невозможно прочитать информацию о воспроизведении» и
     * предлагается указать, где воспроизвести;
     * 2. Сеанс есть, но он приостановлен/нет метаданных → «Не воспроизводится»;
     * 3. Никакого разговора → То же, что «не играть».
     *
     * Заголовок и подзаголовок должны определяться парами: был заголовок «Сейчас исполняется» (статическая копия макета) с подзаголовком.
     * Противоречивое отображение «Не играет» (скриншот фактического теста), статическая копия изменена на «Медиа», нажмите здесь еще раз.
     * Статус       жестко запрограммирован в две строки, чтобы они не попадали из разных источников.
     */
    private fun renderMediaCard() {
        val panel = dashboardView ?: return
        val now = MediaSessionsState.nowPlaying
        val title = now?.title?.takeIf { it.isNotBlank() }
        val artist = now?.artist?.takeIf { it.isNotBlank() }
        // Сеанс есть, но метаданные не читаются + Несанкционировано → Проблема с разрешением, а не «не играет».
        val missingDueToPermission =
            now != null && title == null && !MediaSessionsState.accessGranted
        panel.findViewById<TextView>(R.id.dashMediaTitle).text =
            title ?: if (missingDueToPermission) "Невозможно прочитать информацию о воспроизведении" else "Ничего не воспроизводится"
        panel.findViewById<TextView>(R.id.dashMediaArtist).text =
            artist
                ?: when {
                    missingDueToPermission -> "«Права на использование уведомлений» не предоставлены — включите их в настройках системы."
                    now?.playing == true -> "Сейчас играю"
                    else -> ""
                }
        panel.findViewById<ImageView>(R.id.dashPlayPause).setImageResource(
            if (now?.playing == true) R.drawable.ic_media_pause else R.drawable.ic_media_play,
        )
        panel.findViewById<TextView>(R.id.dashMediaTime)?.let { time ->
            if (now != null && now.durationMs > 0) {
                time.visibility = View.VISIBLE
                time.text =
                    "${formatTime(now.displayPositionAt(android.os.SystemClock.elapsedRealtime()))} / " +
                    formatTime(now.durationMs)
            } else {
                time.visibility = View.GONE
            }
        }
        panel.findViewById<ProgressBar>(R.id.dashMediaProgress)?.let { bar ->
            if (now != null && now.durationMs > 0) {
                bar.visibility = View.VISIBLE
                bar.progress =
                    (now.displayPositionAt(android.os.SystemClock.elapsedRealtime()) * 1000 / now.durationMs)
                        .toInt()
                        .coerceIn(0, 1000)
            } else {
                bar.visibility = View.GONE
            }
        }
        panel.findViewById<ImageView>(R.id.dashCover)?.let { cover ->
            val art = now?.art
            if (art != null) {
                cover.setImageBitmap(art)
                cover.visibility = View.VISIBLE
                // Обрезка закругленных углов (одноразовая настройка контура)
                if (cover.clipToOutline != true) {
                    cover.clipToOutline = true
                    val dp = resources.displayMetrics.density
                    cover.outlineProvider =
                        object : android.view.ViewOutlineProvider() {
                            override fun getOutline(
                                view: View,
                                outline: android.graphics.Outline,
                            ) {
                                outline.setRoundRect(
                                    0,
                                    0,
                                    view.width,
                                    view.height,
                                    10 * dp,
                                )
                            }
                        }
                }
                // Нажмите на обложку → Откатите воспроизводящее приложение (повторное использование задачи: возврат к интерфейсу
                // воспроизведения)

                cover.setOnClickListener {
                    val pkg = now.pkg ?: return@setOnClickListener
                    val component =
                        packageManager.getLaunchIntentForPackage(pkg)?.component
                            ?: run {
                                VirtualLauncher.logSink
                                    ?.invoke("СМИ: $pkg не имеет записи запуска, игнорирует клики по обложке")
                                return@setOnClickListener
                            }
                    Bg.run("launch-app") { VirtualLauncher.launchApp(component) }
                }
            } else {
                // Заполнитель не исчезает (НЕВИДИМЫЙ): обложка загружается асинхронно/контент не прыгает между песнями.
                cover.visibility = View.INVISIBLE
                cover.setOnClickListener(null)
            }
        }
        // Отображение прогресса должно длиться несколько секунд: продолжайте планирование в конце рендеринга
        // (антирепликация: сначала удалите)

        tickHandler.removeCallbacks(mediaTick)
        tickHandler.postDelayed(mediaTick, 1_000)
    }

    /** Режим панели управления → синхронизация положения пейджера (0 = панель мониторинга, 1+ = рабочий стол; он не будет перемещаться, если находится в целевой позиции). */
    private fun applyDashboardMode(dashboard: Boolean) {
        val pager = findViewById<ViewPager2>(R.id.gridPager)
        if (dashboard && pager.currentItem != 0) pager.setCurrentItem(0, false)
        if (!dashboard && pager.currentItem == 0) pager.setCurrentItem(1, false)
    }

    /** Управление музыкой: системные мультимедийные клавиши (AudioManager глобально маршрутизируется к активным мультимедийным сеансам, разрешения не требуются). */
    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grid)

        // Размер сетки/количество строк рассчитываются динамически в зависимости от экрана (отношения макета указаны в
        // XML, измерение размера — в коде).

        computeCellMetrics()
        // Условие гонки реконструкции виртуального экрана: когда dm временно равен 0, размер ячейки/значка будет
        // рассчитан как 0, и привязка первого кадра рухнет.

        // (Ширина и высота Bitmap.createBitmap 0). Зажимаем минимальное значение, чтобы обеспечить низ, и пересчитываем
        // + перепривязываем после выкладки первого кадра пейджера.

        if (iconSizePx <= 0) {
            iconSizePx = 48
            findViewById<ViewPager2>(R.id.gridPager).post {
                computeCellMetrics()
                iconSizePx = iconSizePx.coerceAtLeast(48)
                if (cellWidthPx > 0) reload()
            }
        }

        // Фон обоев (индекс 0 = внизу): окно должно быть непрозрачным — при нажатии прозрачного окна перед окном приложения
        // Будет раскрыто содержимое приложения (причина, по которой значок на рабочем столе накладывается на заголовок
        // после нажатия кнопки «Домой»).

        // Отображение фрагментов: смещайте обои аспектной заливки «всего холста видеопотока» в соответствии с положением закрепления.
        // Это окно просто отображает фрагменты в области приложения, которые являются непрерывными попиксельно с
        // фрагментами на доке (тот же путь рендеринга).

        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth > 0 && canvasHeight > 0) {
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            wallpaperView =
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    layoutParams =
                        android.widget.FrameLayout
                            .LayoutParams(
                                canvasWidth,
                                canvasHeight,
                            ).apply {
                                if (VirtualLauncher.dockAtBottom) {
                                    // Автомобильная машина с вертикальным экраном: док-станция находится внизу, часть рабочего стола =
                                    // верхняя
                                    // часть
                                    // холста.

                                    leftMargin = 0
                                    topMargin = 0
                                } else {
                                    // Автомобиль с горизонтальным экраном: док-станция находится слева, часть рабочего стола = правая часть
                                    // холста.

                                    leftMargin = -(VirtualLauncher.dockWidthPx * scale).toInt()
                                    topMargin = 0
                                }
                            }
                }
            root.addView(wallpaperView, 0)
            // Уведомление об изменении регистрации + немедленная сверка (охватывает изменения, произошедшие до регистрации –
            // события не будут переизданы)

            WallpaperStore
                .addListener(wallpaperListener)
            refreshWallpaperIfChanged()

            // Панель информационной панели изменена на домашнюю страницу ViewPager2 (создается при привязке PageAdapter),
            // Здесь зарегистрированы только глобальные прослушиватели: визуальная карта/статус мультимедиа/фрагмент
            // обоев/режим информационной панели.

            DockPrefs.addListener(dockPrefsListener)
            MediaSessionsState
                .addListener(mediaStateListener)
            WallpaperStore
                .addCanvasReadyListener(canvasReadyListener)
            VirtualLauncher.addDashboardListener(dashboardListener)
            VirtualLauncher.addMapLayoutListener(mapLayoutListener)
            applyDashboardMode(VirtualLauncher.dashboardMode)
        }

        pager =
            findViewById<ViewPager2>(R.id.gridPager).apply {
                offscreenPageLimit = 2
                adapter = PageAdapter()
            }
        dotsRow = findViewById(R.id.dotsRow)
        pager?.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDots(position)
                    // Пользовательские слайды для переключения панели управления/рабочего стола: панель запуска обратной записи
                    // (включая задачи карты и макет плитки)

                    if (position == 0 && !VirtualLauncher.dashboardMode) VirtualLauncher.showDashboard()
                    if (position != 0 && VirtualLauncher.dashboardMode) VirtualLauncher.showDesktop()
                }

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    offsetPixels: Int,
                ) {
                    // Во время процесса скольжения плитка следует за перемещением пальца (положение семплирования синтезатора
                    // перемещается влево/вправо по мере продвижения);

                    // Отображение плиток заранее при возвращении на панель управления (чисто визуально, статус будет согласован при
                    // установке IDLE)

                    if (position == 0) {
                        VirtualLauncher.setTileSlide(positionOffset)
                        if (positionOffset < 1f) VirtualLauncher.showMapTile()
                    }
                }

                override fun onPageScrollStateChanged(state: Int) {
                    if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) {
                        VirtualLauncher.setTileSlide(0f)
                        // Завершенная выверка: режим информационной панели соответствует странице, на которой она расположена (включая
                        // восстановление выверки после аварийного прерывания).

                        VirtualLauncher.syncDashboard(pager?.currentItem == 0)
                    }
                }
            },
        )
    }

    /** При изменении версии обоев фон перекодируется и меняет изображение (рабочий стол обновится через ≤2 секунды после смены обоев на мобильном телефоне). */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        val current = WallpaperStore.version
        if (current == wallpaperShownVersion) return
        wallpaperShownVersion = current
        val params = view.layoutParams as? android.widget.FrameLayout.LayoutParams
        Bg.run("wallpaper-grid") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@GridActivity,
                        params?.width ?: 0,
                        params?.height ?: 0,
                    )
                }.getOrNull()
            if (bitmap != null) {
                // Диагностика: выборка растрового изображения по трем точкам (ширина 25%/50%/75% по центральной линии) –
                // распознавание «отсутствует содержимое растрового изображения»

                // И «проблема просмотра/синтеза» (локальный logcat подавлен, logSink используется для удаления журналов файлов)
                val samples =
                    listOf(0.25f, 0.5f, 0.75f).joinToString(",") {
                        "%06x".format(bitmap.getPixel((bitmap.width * it).toInt(), bitmap.height / 2))
                    }
                VirtualLauncher.logSink?.invoke(
                    "WALLPAPER-GRID: bitmap=${bitmap.width}x${bitmap.height} " +
                        "view=${params?.width}x${params?.height} left=${params?.leftMargin} " +
                        "samples=[$samples]",
                )
                runOnUiThread { view.setImageBitmap(bitmap) }
            } else {
                VirtualLauncher.logSink?.invoke("ОБОИ-СЕТКА: Ошибка декодирования.")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Также переопределяет возврат со страницы настроек: установите флажок «Изменения отражаются немедленно».
        reload()
        refreshWallpaperIfChanged()
    }

    /**
     * Рассчитайте размер сетки и количество строк:
     * ландшафт (спецификация CarPlay): исправлено 4 столбца × 3 строки, поведение такое же, как и раньше.
     * Вертикальный экран: фиксированные 3 ряда растягивают сетку в тонкую и высокую полосу размером ~ 1:2,5 (значки
     * занимают только 30%, полноэкранный пустой кадр,
     * 17 октября 2026 г. Пользователь открыл «Показать границу макета», и снимок экрана был открыт); нажмите «Высота сетки ≈ Ширина × 1,25»
     * адаптивное добавление строк (верхний предел 6, соответствует внешнему виду вертикального главного экрана
     * iPhone 4×6), синхронизация размеров значков
     * соответствует ширине сетки, чтобы избежать дисбаланса в соотношении слишком большой сетки и слишком маленького значка.
     */
    private fun computeCellMetrics() {
        val dm = resources.displayMetrics
        val density = dm.density
        val chromeHorizontalPx = (2 * 10 * density).toInt() // item_grid_page отступы слева и справа
        val chromeVerticalPx = ((8 + 26 + 16) * density).toInt() // Отступы сверху/снизу + область точек страницы
        val usableW = dm.widthPixels - chromeHorizontalPx
        val usableH = dm.heightPixels - chromeVerticalPx
        if (usableW <= 0 || usableH <= 0) {
            cellWidthPx = 0
            cellHeightPx = 0
            iconSizePx = 0
            return
        }
        cellWidthPx = usableW / COLUMNS
        // Объедините горизонтальные и вертикальные экраны: сначала нажмите 3 ряда для пробного расчета. Если сетка
        // слишком высокая (вертикальный экран), добавьте строки, чтобы они сходились к целевому соотношению.

        var rowCount = ROWS_MIN
        if (cellWidthPx > 0) {
            rowCount = (usableH / (cellWidthPx * CELL_HEIGHT_RATIO)).toInt().coerceIn(ROWS_MIN, ROWS_MAX)
        }
        rows = rowCount
        cellHeightPx = usableH / rowCount
        iconSizePx =
            (cellHeightPx * 0.63f)
                .toInt()
                .coerceAtMost((cellWidthPx * 0.72f).toInt())
    }

    /** Перечитать список приложений и выбор рабочего стола (вызывается на первом кадре и каждый раз при возврате на рабочий стол). */
    private fun reload() {
        val apps =
            appsCache ?: run {
                val scanned = VirtualLauncher.launchableApps(this)
                // iconCache используется для запуска иконок в доке: обновляется вся таблица - основной поток дока на другом экране
                // Одновременное чтение, очистка + запись по одному в общий HashMap без синхронизации будет повреждено (обзор
                // неприятного запаха P1)

                VirtualLauncher.replaceIconCache(
                    scanned.associate { it.component to it.icon },
                )
                appsCache = scanned
                // Включите общий кеш на уровне процесса: после сканирования рабочего стола настройки автомобиля будут открыты
                // мгновенно при первом открытии.

                VirtualLauncher.adoptAppsCache(scanned)
                scanned
            }

        val selected = DesktopApps.selected(this, apps)
        val cells =
            buildList {
                add(Cell.Pinned) // Первая сетка на рабочем столе: настройки автомобиля и машины (исправлено, отменить нельзя)
                apps.filter { it.component.flattenToString() in selected }.forEach { add(Cell.App(it)) }
            }
        // Страница 0 = Панель мониторинга, за которой следует страница сетки рабочего стола: проведите пальцем влево или
        // вправо, чтобы переключаться между собой.

        pages = listOf(listOf<Cell>(Cell.Dashboard)) + cells.chunked(COLUMNS * rows)

        val current = pager?.currentItem ?: 0
        pager?.adapter?.notifyDataSetChanged()
        rebuildDots()
        // Согласование между местоположением и режимом информационной панели (цикл домашнего ключа/запуск приложения
        // изменит режим информационной панели):

        // Только исправьте направление, не перетаскивайте пользователя, который запарковался на странице N на рабочем
        // столе, обратно на страницу 1.

        if (VirtualLauncher.dashboardMode && current != 0) pager?.setCurrentItem(0, false)
        if (!VirtualLauncher.dashboardMode && current == 0) pager?.setCurrentItem(1, false)
        if ((pager?.currentItem ?: 0) >= pages.size) pager?.setCurrentItem(pages.size - 1, false)
        updateDots(pager?.currentItem ?: 0)
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = if (pages.getOrNull(position)?.firstOrNull() is Cell.Dashboard) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            if (viewType == 0) {
                // Страница информационной панели = контейнер без полей: панель музыкальной карты + маркер перетаскивания
                // разделенного экрана (дескриптор падает на расширенную область экрана).

                // В пределах центрального шва и не заблокированный фрагментами карты, наложенными синтезатором, экран приложения
                // Touch-and-go будет распределяться нормально)

                val container = android.widget.FrameLayout(parent.context)
                container.layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                val panel = layoutInflater.inflate(R.layout.view_dashboard, container, false)
                container.addView(panel)
                container.addView(DashSplitHandle(parent.context))
                DashboardHolder(container, panel)
            } else {
                val grid = layoutInflater.inflate(R.layout.item_grid_page, parent, false) as GridLayout
                grid.columnCount = COLUMNS
                // Количество строк определяется с помощью CompeCellMetrics в соответствии с горизонтальным и вертикальным
                // экранами (горизонтальный экран — 3, вертикальный экран — до 6).

                grid.rowCount = rows
                PageHolder(grid)
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (holder) {
                is DashboardHolder -> holder.bind()
                is PageHolder -> holder.bind(position)
            }
        }
    }

    /** Держатель страницы информационной панели: вместе со страницей создается контейнер без полей (панель музыкальной карты + маркер разделенного экрана). */
    private inner class DashboardHolder(
        container: View,
        val panel: View,
    ) : RecyclerView.ViewHolder(container) {
        private var handle: DashSplitHandle? = null

        fun bind() {
            dashboardView = panel
            val scale = VirtualLauncher.gridScale()
            val cardMargin = (CARD_MARGIN_PX * scale).toInt()
            panel.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ).apply {
                        if (VirtualLauncher.dockAtBottom) {
                            leftMargin = cardMargin
                            rightMargin = cardMargin
                            topMargin = cardMargin
                            bottomMargin = cardMargin
                        } else {
                            topMargin = cardMargin
                            rightMargin = cardMargin
                            bottomMargin = cardMargin
                        }
                    }
            panel.outlineProvider =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: android.graphics.Outline,
                    ) {
                        outline.setRoundRect(0, 0, view.width, view.height, cardMargin * 1.4f)
                    }
                }
            panel.clipToOutline = true
            panel.findViewById<ImageView>(R.id.dashPrev).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            panel.findViewById<ImageView>(R.id.dashNext).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            panel.findViewById<ImageView>(R.id.dashPlayPause).setOnClickListener {
                // Явно запускать воспроизведение/паузу в соответствии с текущим состоянием; оптимистичный перевернутый значок
                // (обратный вызов состояния может задерживаться),

                // Запросите службу прослушивания для повторной синхронизации реального статуса через 400 мс.
                val state = MediaSessionsState.nowPlaying
                val playing = state?.playing == true
                dispatchMediaKey(
                    if (playing) {
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                    } else {
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    },
                )
                // Оптимистичные обновления сохраняют обложку и другие поля (копия); повторно синхронизировать истинный статус через 400 мс
                MediaSessionsState.publish(
                    state
                        ?.copy(playing = !playing)
                        ?: MediaSessionsState.NowPlaying(
                            null,
                            null,
                            !playing,
                        ),
                )
                panel.postDelayed(
                    {
                        MediaSessionsState
                            .requestResync()
                    },
                    400,
                )
            }
            // Чтобы выйти из полноэкранного режима карты, нажмите кнопку «Домой» на доке (ворота маршрутизации showDashboard автоматически
            // setMapFullscreen(false)), кнопка «Полноэкранная карта» больше не размещается на карточке дашборда
            bindSplitHandle(scale)
            applyDashCardChrome()
            renderMediaCard()
            refreshDashWallpaper()
        }

        /** Настройте маркеры разделения экрана (обратные вызовы направления/размера/перетаскивания) и разместите панели и маркеры в соответствии с текущим разделенным экраном. */
        private fun bindSplitHandle(scale: Float) {
            val child = (itemView as? android.widget.FrameLayout)?.getChildAt(1) as? DashSplitHandle
            handle = child ?: return
            val portrait = VirtualLauncher.dockAtBottom
            // Сенсорная полоса шире, чем визуальный центральный зазор (DASH_HANDLE_TOUCH_PX > DASH_GAP_PX): переполнение с обеих сторон
            // На карте нажимается раздел, чтобы расширить сенсорную цель — раздел переполнения на стороне карты покрыт
            // плитками и невидим, но доступен для касания.

            val thickness = (DASH_HANDLE_TOUCH_PX * scale).toInt()
            child.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else thickness,
                        if (portrait) thickness else ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            child.configure(portrait) { centerScreen, final ->
                if (final) {
                    // зафиксируйте внутренние ограничения и действительно измените размеры экрана карты; notifyMapLayout сработает
                    // Обои нарезаны и перерезаны. commit возвращает значение после ограничения, на основе которого корректируется
                    // запас, чтобы предотвратить дрейф.

                    layoutSplit(VirtualLauncher.commitDashSplitScreen(centerScreen), scale)
                } else {
                    layoutSplit(centerScreen, scale)
                    VirtualLauncher.previewDashSplitScreen(centerScreen)
                }
            }
            layoutSplit(VirtualLauncher.dashHandleCenterScreen(), scale)
        }

        /** Центр ручки (пиксели экрана приложения) → поля музыкальной карты и ручка. Если она не прикреплена (<0), используется нижняя осевая линия. */
        private fun layoutSplit(
            centerScreen: Float,
            scale: Float,
        ) {
            val portrait = VirtualLauncher.dockAtBottom
            val center =
                if (centerScreen >= 0) {
                    centerScreen
                } else {
                    // Внизу: центральная линия области приложения (в соответствии со старой версией фиксированного разделенного экрана)
                    ((if (portrait) VirtualLauncher.appAreaHeight else VirtualLauncher.appAreaWidth) / 2f) * scale
                }
            // Музыкальная карта уступает место визуальному шву; ручка нажимается до достижения ширины и центра (шире шва,
            // нажимается на карты с обеих сторон)

            val halfGap = DASH_GAP_PX / 2f * scale
            val halfTouch = DASH_HANDLE_TOUCH_PX / 2f * scale
            (panel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                if (portrait) {
                    lp.topMargin = (center + halfGap).toInt()
                } else {
                    lp.leftMargin = (center + halfGap).toInt()
                }
            }
            handle?.let { h ->
                (h.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                    if (portrait) {
                        lp.topMargin = (center - halfTouch).toInt()
                    } else {
                        lp.leftMargin = (center - halfTouch).toInt()
                    }
                }
                h.requestLayout()
            }
            panel.requestLayout()
        }
    }

    private inner class PageHolder(
        private val grid: GridLayout,
    ) : RecyclerView.ViewHolder(grid) {
        fun bind(page: Int) {
            grid.removeAllViews()
            pages[page].forEach { cell ->
                val view = layoutInflater.inflate(R.layout.item_app_cell, grid, false)
                val icon =
                    view.findViewById<ImageView>(R.id.cellIcon).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    }
                val label = view.findViewById<TextView>(R.id.cellLabel)
                when (cell) {
                    is Cell.Dashboard -> Unit // Дашборд представляет собой самостоятельную страницу и не входит в сетку.
                    is Cell.Pinned -> {
                        icon.setImageResource(R.drawable.ic_app_settings)
                        label.text = getString(R.string.car_settings)
                        view.setOnClickListener { openSettings() }
                    }

                    is Cell.App -> {
                        icon.setImageBitmap(AppIcons.rounded(cell.entry.icon, iconSizePx))
                        label.text = cell.entry.label
                        view.setOnClickListener {
                            // Никакой системный Toast не используется: положение отображения на виртуальном экране неконтролируемо
                            // (происхождение SettingsToastHost);

                            // Само приложение является обратной связью
                            Bg.run("launch-app") { VirtualLauncher.launchApp(cell.entry.component) }
                        }
                    }
                }
                // Фокус: события DPAD, вводимые в ручку iDrive/клавиши направления на рулевом колесе, зависят от системы фокуса.
                // Ячейки сетки должны быть фокусируемыми + иметь видимую рамку выделения (нажатие клавиши автоматически выйдет
                // из сенсорного режима).

                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.foreground =
                    androidx.core.content.ContextCompat
                        .getDrawable(this@GridActivity, R.drawable.focus_ring)
                grid.addView(
                    view,
                    GridLayout.LayoutParams().apply {
                        width = cellWidthPx
                        height = cellHeightPx
                    },
                )
            }
        }
    }

    private fun openSettings() {
        // Скользящие переходы предоставляются windowAnimationStyle темы SettingsActivity.
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun rebuildDots() {
        val row = dotsRow ?: return
        row.removeAllViews()
        if (pages.size < 2) return // На одной странице не отображаются точки страницы (сравните с прототипом)
        val density = resources.displayMetrics.density
        repeat(pages.size) {
            row.addView(
                android.view.View(this).apply {
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(0x66FFFFFF)
                        }
                },
                LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                },
            )
        }
        updateDots(0)
    }

    private fun updateDots(selected: Int) {
        val row = dotsRow ?: return
        for (i in 0 until row.childCount) {
            (row.getChildAt(i).background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(if (i == selected) 0xFFFFFFFF.toInt() else 0x66FFFFFF.toInt())
        }
    }

    override fun onDestroy() {
        tickHandler.removeCallbacks(mediaTick)
        WallpaperStore
            .removeListener(wallpaperListener)
        VirtualLauncher.removeDashboardListener(dashboardListener)
        VirtualLauncher.removeMapLayoutListener(mapLayoutListener)
        MediaSessionsState
            .removeListener(mediaStateListener)
        DockPrefs.removeListener(dockPrefsListener)
        WallpaperStore
            .removeCanvasReadyListener(canvasReadyListener)
        super.onDestroy()
    }

    override fun onBackPressed() = Unit // Рабочий стол не выходит

    private companion object {
        const val COLUMNS = 4 // Характеристики CarPlay: обязательные 4 столбца.

        /** Количество горизонтальных строк спецификации CarPlay (используется как нижний предел при адаптации к вертикальному экрану). */
        const val ROWS_MIN = 3

        /** Верхний предел адаптивных строк вертикального экрана (согласован с внешним видом вертикального основного экрана iPhone 4×6). */
        const val ROWS_MAX = 6

        /** Целевое соотношение сторон сетки (высота/ширина): цель конвергенции для вертикального экрана и строк. */
        const val CELL_HEIGHT_RATIO = 1.25f
    }
}
