package io.github.shaonialife.cardeck.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * Док-станция CarPlay (независимый виртуальный экран, включая часы/батарею/работающие приложения/возврат на рабочий стол).
 *
 * две формы (см. res/layout для макета):
 * — Машина с горизонтальным экраном: Док — вертикальная полоса слева → Activity_dock_sidebar.xml (часы вверху, рабочий стол внизу)
 * — машина с вертикальным экраном: док-станция — нижняя горизонтальная полоса → Activity_dock_bar.xml (⌂ |
 * горизонтальное расположение приложения | время/батарея)
 * Форма   определяется шириной и высотой виртуального экрана дока (высота вертикальной полосы>ширина; ширина
 * горизонтальной полосы>высота) и автоматически выравнивается по направлению движения транспортного средства.
 */
class DockActivity : AppCompatActivity() {
    private var appsContainer: LinearLayout? = null
    private var clockView: TextView? = null
    private var batteryText: TextView? = null
    private var batteryBar: View? = null
    private var batteryFill: View? = null
    private var barForm = false

    /**
     * Текущее увеличение расчетной плотности  (420 точек на дюйм). Плотность экрана док-станции определяется
     * SessionController пропорционально короткой стороне холста.
     * Выпускается       (Audi 1160×540→420 без изменений; стенд 2320×1080→840, док и текст/иконка целиком × 2).
     * XML макета       использует dp для автоматического масштабирования в зависимости от плотности; размер
     * шрифта/радиус размытия и т. д. задаются в коде параметром «Design px × это увеличение».
     * гарантирует, что содержимое дока будет в той же пропорции, что и холст под автомобилями с высоким разрешением
     * (исправления 17 октября 2026 г. «при высоком разрешении кажутся маленькими»).
     */
    private val designScale: Float
        get() = resources.displayMetrics.densityDpi / 420f

    private val refreshListener: () -> Unit = { runOnUiThread { refresh() } }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable =
        object : Runnable {
            override fun run() {
                refresh()
                pollHandler.postDelayed(this, 3_000)
            }
        }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker =
        object : Runnable {
            override fun run() {
                // Отсутствие второго дисплея (требование пользователя): выравнивание по обновлению границы в следующую минуту,
                // отсутствие бесполезной перерисовки в течение нескольких секунд.

                clockView?.text =
                    android.text.format.DateFormat
                        .format("HH:mm", System.currentTimeMillis())
                clockView?.let { fitClockText(it) }
                val now = System.currentTimeMillis()
                clockHandler.postDelayed(this, 60_000L - now % 60_000L + 80L)
            }
        }

    /** Фон обоев (полнопоточный фрагмент холста, то же начало и путь, что и на рабочем столе — соединение непрерывное, пиксель за пикселем). */
    private var wallpaperView: ImageView? = null
    private var wallpaperShownVersion = -1L

    /** Мониторинг смены обоев (обратный вызов основного потока WallpaperStore; декодирование само переключается на фоновый поток в этом классе). */
    private val wallpaperListener = { _: Long -> refreshWallpaperIfChanged() }

    /** мониторинг предпочтений внешнего вида дока (перетащите ползунок на странице настроек, чтобы немедленно вступить в силу). */
    private val dockPrefsListener: () -> Unit = { runOnUiThread { applyDockPrefs() } }

    private val batteryReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = updateBattery(intent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        barForm = dm.widthPixels > dm.heightPixels
        setContentView(if (barForm) R.layout.activity_dock_bar else R.layout.activity_dock_sidebar)

        clockView = findViewById(R.id.dockClock)
        batteryText = findViewById(R.id.dockBatteryText)
        batteryBar = findViewById(R.id.dockBatteryBar)
        batteryFill = findViewById(R.id.dockBatteryFill)
        appsContainer = findViewById(R.id.dockApps)
        wallpaperView = findViewById(R.id.dockWallpaper)
        findViewById<ImageView>(R.id.dockHome).apply {
            setOnClickListener { Bg.run("dock-home") { VirtualLauncher.onHomePressed() } }
            // Значок переключается с режимом: активация приборной панели = дом (кликните для возврата на рабочий стол); в
            // противном случае = поле (нажмите, чтобы войти в панель мониторинга).

            // Приложение и рабочий стол имеют одну и ту же сетку Tianzi — действие в обоих сценариях заключается в входе в
            // панель мониторинга, и семантика одинакова.

            VirtualLauncher.addDashboardListener { dashboard ->
                runOnUiThread {
                    setImageResource(
                        if (dashboard) R.drawable.ic_dock_home else R.drawable.ic_dock_dashboard,
                    )
                }
            }
            setImageResource(
                if (VirtualLauncher.dashboardMode) R.drawable.ic_dock_home else R.drawable.ic_dock_dashboard,
            )
        }

        VirtualLauncher.addRunningAppsListener(refreshListener)
        pollHandler.postDelayed(pollRunnable, 3_000)
        clockHandler.post(clockTicker)
        // Размер шрифта устанавливается в соответствии с «design px × designScale» (не вводите XML: autoSize на Honor
        // ROM или узкой док-станции).

        // Это не вступает в силу. Вам все равно придется вручную сжимать часы в соответствии с измеренной шириной после
        // завершения макета — см. fitClockText; 13/22 — значение прототипа)

        clockView?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 22f * designScale)
        batteryText?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13f * designScale)
        clockView?.post { fitClockText(clockView!!) }
        setupWallpaper()
        // Уведомление об изменении регистрации + немедленная сверка (охватывает изменения, произошедшие до регистрации –
        // события не будут переизданы)

        WallpaperStore
            .addListener(wallpaperListener)
        refreshWallpaperIfChanged()
        // внешний вид док-станции (темный/размытый): применить текущие значения + настройки страницы настроек прослушивателя
        DockPrefs.addListener(dockPrefsListener)
        applyDockPrefs()
        // Защищенная системой трансляция: ACTION_BATTERY_CHANGED закреплена, регистрация восстановит текущий уровень заряда батареи.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        VirtualLauncher.removeRunningAppsListener(refreshListener)
        pollHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacks(clockTicker)
        WallpaperStore
            .removeListener(wallpaperListener)
        DockPrefs.removeListener(dockPrefsListener)
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Фрагмент обоев: нарисуйте соответствующую часть этой полосы в «Full Flow Canvas Wallpaper» в окне закрепления.
     * закрепите виртуальный экран и трансляцию 1:1 (420 точек на дюйм), вид холста = размер холста потока:
     * — Боковая панель (автомобиль с горизонтальным экраном): направление левой панели, смещение (0, 0)
     * — Нижняя полоса (машина с вертикальным экраном): нижняя полоса потока, смещение (0, -(appHeight))
     * Окно должно быть непрозрачным: под полупрозрачным окном система синтезирует собственные обои телефона в этот виртуальный экран.
     * (Обои мобильного телефона, отображаемые на док-станции, не совпадали с обоями рабочего стола — основная
     * причина измерена 12 октября 2026 г.).
     */
    private fun setupWallpaper() {
        val launcher = VirtualLauncher
        val streamW = launcher.streamWidthPx
        val streamH = launcher.streamHeightPx
        if (streamW <= 0 || streamH <= 0) return
        val view = wallpaperView ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (barForm) {
            params.topMargin = -(streamH - launcher.dockHeightPx)
        } else {
            params.leftMargin = 0
            params.topMargin = 0
        }
        params.width = streamW
        params.height = streamH
        view.layoutParams = params
        refreshWallpaperIfChanged()
    }

    /**
     * Настройки внешнего вида док-станции приложения: интенсивность темного экрана (черная альфа-версия Scrim View)
     * + размытие фона по Гауссу.
     * (RenderEffect, API 31+; радиус в физических пикселях закрепления, максимум 40 пикселей — умножается на designScale
     * гарантирует, что размытость изображения на автомобилях с высоким разрешением соответствует эталону Audi).
     */
    private fun applyDockPrefs() {
        val scrim = findViewById<View>(R.id.dockScrim) ?: return
        val alpha = (DockPrefs.scrimOpacity(this) * 255 / 100).coerceIn(0, 255)
        scrim.background =
            android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(alpha, 0, 0, 0),
            )
        val wallpaper = wallpaperView ?: return
        val percent = DockPrefs.blur(this)
        if (percent <= 0) {
            wallpaper.setRenderEffect(null)
        } else {
            val radius = percent / 100f * 40f * designScale
            wallpaper.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    /** При изменении версии обоев фон перекодируется и меняет изображение (обновляется синхронно с рабочим столом в режиме прослушивания). */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        val current = WallpaperStore.version
        if (current == wallpaperShownVersion) return
        wallpaperShownVersion = current
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        Bg.run("wallpaper-dock") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@DockActivity,
                        params.width,
                        params.height,
                    )
                }.getOrNull()
            if (bitmap != null) {
                VirtualLauncher.logSink?.invoke(
                    "WALLPAPER-DOCK: bitmap=${bitmap.width}x${bitmap.height} " +
                        "view=${params.width}x${params.height} top=${params.topMargin}",
                )
                runOnUiThread { view.setImageBitmap(bitmap) }
            } else {
                VirtualLauncher.logSink?.invoke("WALLPAPER-DOCK: Ошибка декодирования")
            }
        }
    }

    /** Уменьшайте размер шрифта постепенно, пока ширина текста не поместится (дизайн px × designScale; обводка 22→12, сравните с прототипом дизайна). */
    private fun fitClockText(view: TextView) {
        val available = view.width - view.paddingLeft - view.paddingRight
        if (available <= 0) return
        val scale = designScale
        var size = 22f * scale
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        val paint = view.paint
        while (size > 12f * scale && paint.measureText(view.text.toString()) > available) {
            size -= 0.5f * scale
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    private fun updateBattery(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        runOnUiThread {
            batteryText?.text = (if (charging) "⚡" else "") + "$percent%"
            val bar = batteryBar
            val fill = batteryFill
            if (bar != null && fill != null) {
                bar.post {
                    val width = bar.width
                    if (width > 0) {
                        fill.layoutParams =
                            (fill.layoutParams as FrameLayout.LayoutParams).apply {
                                this.width =
                                    (width * percent / 100)
                                        .coerceAtLeast((3 * resources.displayMetrics.density).toInt())
                            }
                    }
                }
                fill.setBackgroundColor(if (percent <= 20) 0xFFFF6B6B.toInt() else 0xFF34C759.toInt())
            }
        }
    }

    private var lastRendered: List<String> = emptyList()

    private fun refresh() {
        val container = appsContainer ?: return
        val current =
            synchronized(VirtualLauncher.runningApps) {
                VirtualLauncher.runningApps.map { it.flattenToString() }
            }
        if (current == lastRendered) return
        lastRendered = current
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val iconPx = ((if (barForm) 32 else 30) * density).toInt()
        val pad = (6 * density).toInt()
        val apps = synchronized(VirtualLauncher.runningApps) { VirtualLauncher.runningApps.toList() }
        val iconCache = VirtualLauncher.iconCache
        apps.forEach { component ->
            val drawable = iconCache[component] ?: packageManager.defaultActivityIcon
            // Рендеринг растрового изображения (тот же путь, что и сетка рабочего стола): непосредственный набор
            // ImageDrawable создаст ImageView

            // Общий экземпляр Drawable удерживается в течение длительного времени, а его границы отображаются смещенными
            // после повторной привязки и перезаписи сеткой.

            // (Реальный сбой устройства: после нажатия кнопки «Домой» значок в доке становится меньше и смещается влево)
            val bitmap = AppIcons.rounded(drawable, iconPx)
            container.addView(
                ImageView(this).apply {
                    setImageBitmap(bitmap)
                    setPadding(pad, pad, pad, pad)
                    contentDescription = component.shortClassName
                    setOnClickListener {
                        Bg.run("launch-app") { VirtualLauncher.launchApp(component) }
                    }
                },
                LinearLayout.LayoutParams(iconPx + pad * 2, iconPx + pad * 2),
            )
        }
    }

    override fun onBackPressed() = Unit // Док является резидентным и возвращается без ответа.
}
