package io.github.shaonialife.cardeck

import android.Manifest
import android.animation.ObjectAnimator
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import io.github.shaonialife.cardeck.bt.BtProfiles
import io.github.shaonialife.cardeck.bt.TargetDevicePrefs
import io.github.shaonialife.cardeck.input.TouchRouter
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.settings.AppOrientationCompat
import io.github.shaonialife.cardeck.legal.DisclaimerDialog
import io.github.shaonialife.cardeck.legal.DisclaimerPrefs
import io.github.shaonialife.cardeck.legal.needsDisclaimerAtLaunch
import io.github.shaonialife.cardeck.media.NotificationAccess
import io.github.shaonialife.cardeck.service.CarPlayService
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.ui.DarkMessageDialog
import io.github.shaonialife.cardeck.ui.PanelWindow
import io.github.shaonialife.cardeck.ui.ScreenDimmer
import io.github.shaonialife.cardeck.ui.SignalWavesView
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.LogExporter
import java.util.Locale

/**
 * Интерфейс мобильного телефона  (темная реконструкция Материала 3, эскизный проект design/phone-ui-proto.html):
 * Карта героя   отображает состояние соединения и основные операции; подготовка подключения указана; Статус
 * оболочки и журналы складываются и сохраняются.
 * Конвейер сеансов   (объекты, буфер журнала) удерживается [SessionController] — активность просто зависает на экране:
 * Уничтожение и реконструкция   не повлияет на сессию, а лог автоматически продолжит отображаться после
 * повторного входа в интерфейс; Статус соединения будет согласован с помощью опроса в течение 1 с.
 */
class MainActivity : AppCompatActivity() {
    private enum class HeroState { IDLE, CONNECTING, CONNECTED }

    /** Субтитры по умолчанию в подключениях (при отсутствии подсказки сцены). */
    private companion object {
        const val DEFAULT_CONNECTING_HINT =
            "Подтверждение Bluetooth iAP2 → Подключиться к автомобильной точке доступа → " +
                "Установить сеанс CarPlay"
        const val KEY_DISCLAIMER_DEADLINE = "disclaimer_deadline"
    }

    private lateinit var deviceSubtitle: TextView
    private lateinit var shizukuChip: TextView
    private lateinit var shizukuDesc: TextView
    private lateinit var shizukuBadge: TextView
    private lateinit var signalWaves: SignalWavesView
    private lateinit var hubPlate: FrameLayout
    private lateinit var hubCar: ImageView
    private lateinit var hubCaption: TextView
    private lateinit var heroStateText: TextView
    private lateinit var btnResidualCleanup: TextView
    private lateinit var heroSubline: TextView
    private lateinit var heroStats: TextView
    private lateinit var heroProgress: View
    private lateinit var ctaButton: LinearLayout
    private lateinit var ctaIcon: ImageView
    private lateinit var ctaLabel: TextView
    private lateinit var audioTag: TextView
    private lateinit var readyCount: TextView
    private lateinit var ssidInput: EditText
    private lateinit var btDesc: TextView
    private lateinit var btBadge: TextView
    private lateinit var btnPickTarget: TextView
    private lateinit var audioDesc: TextView
    private lateinit var btnAudio: TextView
    private lateinit var switchAlwaysOn: androidx.appcompat.widget.SwitchCompat
    private lateinit var advBody: View
    private lateinit var advChevron: ImageView
    private lateinit var shellStateText: TextView
    private lateinit var logBody: View
    private lateinit var logChevron: ImageView
    private lateinit var switchLog: androidx.appcompat.widget.SwitchCompat
    private lateinit var logText: TextView
    private lateinit var snackbar: TextView
    private lateinit var appVersionFooter: TextView

    // ----Конечный автомат карты героя (1с опрос, согласование SessionController)----
    private var heroState: HeroState? = null
    private var connectedAt = 0L
    private var hubBlink: ObjectAnimator? = null

    // Выполняется остаточная проверка экрана (на карточке героя отображается «Проверка»); уборка обнаруженных остатков приостанавливается.
    // Обратите внимание, что в pendingResidualDisplays кнопку «Очистить остаточный экран» рядом со статусом можно активировать снова.
    private var startupCheckRunning = false
    private var pendingResidualDisplays: List<Pair<Int, String>>? = null

    // Частота кадров карты героя в реальном времени: опрос в течение 1 с для определения разницы между количеством
    // отправленных кадров VideoSender.

    private var lastSentFrames = 0L

    /** Отказ от ответственности: срок обратного отсчета (истёк в реальном времени; прозрачная передача после ротации и реконструкции, без перезапуска). */
    private var disclaimerDeadlineAt = 0L

    private var activeDisclaimer: androidx.appcompat.app.AlertDialog? = null

    /** Мониторинг результатов авторизации Shizuku (onDestroy необходимо выйти из системы, иначе информация будет просачиваться между действиями). */
    private var shizukuPermissionListener:
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener? = null

    /** Пользователь нажимает основную операцию в руководстве «Запросить авторизацию»: процесс подключения автоматически продолжится после успешной авторизации. */
    private var pendingShizukuThenConnect = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val heroTick: Runnable =
        object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                tickHero()
                mainHandler.postDelayed(this, 1_000L)
            }
        }

    /**
     * Регистрация зависания экрана: продолжение лога (включая исторические логи существующих сессий при вычеркивании
     * интерфейса и повторном входе) + заполнение SSID.
     */
    private val uiHintSinkRef: (String) -> Unit = { message ->
        runOnUiThread { if (!isFinishing && !isDestroyed) snack(message, 6_000L) }
    }

    /** Открытое в настоящее время всплывающее окно подтверждения «Присоединения к точке доступа»: связь закрывается при отмене соединения (вызывает прерывание процесса). */
    private var wifiJoinDialog: androidx.appcompat.app.AlertDialog? = null

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) appendConsole(line)
        }
    }

    private val ssidSink: (String) -> Unit = { ssid ->
        runOnUiThread { ssidInput.setText(ssid) }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("Лицензия BLUETOOTH_CONNECT: $granted")
            if (granted && pendingStartAfterBtPermission.compareAndSet(true, false)) {
                // Шизуку можно остановить в период авторизации: здесь также осуществляется предварительная проверка.
                ensureShizukuReady { showAudioChoiceDialog() }
            }
        }

    /** Автоматически продолжать инициировать соединение после авторизации разрешения Bluetooth. */
    private val pendingStartAfterBtPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** Выбор обоев для автомобилей и машин (Photo Picker; разрешение на хранение не требуется). Если этот флажок установлен, он сохраняется и уведомляет композитора об изменении изображений. */
    private val wallpaperPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                log("ОБОИ: Изображения не выбраны")
                return@registerForActivityResult
            }
            val ok =
                WallpaperStore
                    .setFromUri(this, uri)
            log(
                "WALLPAPER: " +
                    "${if (ok) "Обои обновлены（Разговор продолжается 1 Садитесь в автобус за считанные секунды；Оно автоматически вступит в силу при следующем подключении.）" else "Сохранить не удалось"}",
            )
            if (ok) snack("Обои обновлены") else snack("Сохранить не удалось")
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("MODE: RECORD_AUDIO Авторизация: $granted (требуется для аудиоканала)")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                log("РЕЖИМ: разрешение на показ скринкаста отклонено (звук со стороны автомобиля недоступен)")
                return@registerForActivityResult
            }
            // Последовательность действий в Android 14+: сначала передайте результат службе, затем служба обновляет тип FGS,
            // а затем вызывает getMediaProjection.

            CarPlayService.pendingProjectionResult =
                result.resultCode to data
            CarPlayService
                .start(this)
            log("РЕЖИМ: ✓ Разрешение на скринкаст предоставлено (требуется повторная авторизация после перезагрузки телефона)")
            // Сеанс авторизуется (авторизация происходит позже подключения): динамически повторно включать аудиоканал после
            // готовности проекции

            Bg.run("audio-late-start") {
                val deadline = SystemClock.uptimeMillis() + 5_000L
                while (SystemClock.uptimeMillis() < deadline) {
                    if (CarPlayService.mediaProjection != null) break
                    Thread.sleep(200)
                }
                if (CarPlayService.mediaProjection == null) {
                    log("РЕЖИМ: Проекция не готова в течение 5 секунд (неисправность службы?), аудиоканал на этот раз не запускается")
                    return@run
                }
                SessionController.maybeStartAudioNow(applicationContext)
                // При «Начать соединение» я выбрал включение звука и авторизация скринкаста была получена только в этот момент:
                // затем запустите соединение.

                if (pendingStartAfterProjection.compareAndSet(true, false)) {
                    startConnectionNow()
                }
            }
        }

    /** Установите, когда авторизация скринкаста представляет собой префикс «Начать соединение» (выбрано включение звука и еще не авторизовано). */
    private val pendingStartAfterProjection =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * Всплывающее окно выбора звука перед «Начать подключение»:
     * Одновременная потоковая передача аудио и видео       значительно увеличит нагрузку (измеренное стерео PCM 48k
     * ≈1,5 Мбит/с + видео ≈4 Мбит/с),
     * На испытательном стенде       наблюдалось зависание и отключение экрана; Возможности связи реального
     * автомобиля неизвестны. Таким образом, звук не передается по умолчанию и должен быть включен пользователем явно.
     *
     * Три варианта расположены вертикально (горизонтальное расположение вытеснит узкий экран): рекомендуемый пункт
     * «только видео» ставится первым и выделяется сплошным цветом.
     * - Соответствует тексту "Рекомендуется временно не включать его в основном при просмотре видео"; Отмена внизу.
     */
    private fun showAudioChoiceDialog() {
        // Файл по умолчанию = путь к медиафайлу высокого качества (тип = 102 + AAC-LC, настоящий iPhone использует его);
        // «Файл совместимости» = принудительно использовать только старый путь PCM (тип = 100), резервный вариант
        // вручную, когда автомобиль напрямую отклоняет AAC.

        // Вертикальные кнопки имеют размер около 36dp каждая: высота экрана по горизонтали составляет всего ~300dp, и
        // еще одна кнопка «Отмена» вытолкнет за пределы экрана.

        // Поэтому «Отмена» опускается для горизонтальных экранов (всплывающее окно по умолчанию можно закрыть клавишей
        // возврата/щелчком снаружи).

        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val actions =
            buildList {
                add(
                    DarkMessageDialog.Action(
                        "Только видео (более плавное)",
                        DarkMessageDialog.Emphasis.PRIMARY,
                    ) { beginWithAudio(enabled = false) },
                )
                add(DarkMessageDialog.Action("Включить звук") { beginWithAudio(enabled = true) })
                if (!landscape) add(DarkMessageDialog.Action("Отмена") {})
            }
        DarkMessageDialog.show(
            activity = this,
            title = "Хотите воспроизвести звук со стороны автомобиля?",
            message =
                "После включения в автомобиле будет воспроизводиться звук мультимедиа/навигации мобильного телефона, а " +
                    "сам телефон будет отключен (если он не включен, звука в машине не будет). \n" +
                    "Одновременная передача аудио и видео увеличит нагрузку, поэтому при просмотре видео ее можно отключить. \n" +
                    "Для включения звука требуется разрешение «Screencast», которое будет применяться вместе.",
            actions = actions,
        )
    }

    /** Всплывающее окно подтверждения «Отменить соединение» (во избежание случайного касания): прервать текущую попытку подключения и отключить соединение Bluetooth/WiFi. */
    private fun promptCancelConnecting() {
        DarkMessageDialog.show(
            activity = this,
            title = "Отменить соединение?",
            message =
                "Текущая попытка подключения будет остановлена: каналы Bluetooth и Wi-Fi будут отключены, и вам " +
                    "нужно будет снова нажать «Начать подключение».",
            actions =
                listOf(
                    DarkMessageDialog.Action("Продолжить подключение") {},
                    DarkMessageDialog.Action("Отменить соединение", DarkMessageDialog.Emphasis.DANGER) {
                        // Закройте все еще ожидающее всплывающее окно подтверждения «Присоединиться к точке доступа» (его значение
                        // onChoice(null) будет

                        // ConnectCancelled вместе приводит к немедленному завершению процесса autoJoin)
                        wifiJoinDialog?.cancel()
                        wifiJoinDialog = null
                        SessionController.cancelConnecting(this)
                    },
                ),
        )
    }

    /**
     * Предварительная проверка перед началом подключения: разрешение Bluetooth → переключатель Bluetooth →
     * разрешение определения местоположения WLAN (только Android 12/12L).
     * Если       не удовлетворен, появится всплывающее окно с инструкциями (вы можете перейти к системным настройкам
     * Bluetooth одним щелчком мыши, когда Bluetooth выключен). После передачи обратного вызова продолжайте.
     */
    private fun ensureBluetoothReady(onReady: () -> Unit) {
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterBtPermission.set(true)
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        ensureWifiLocationReady {
            val on =
                try {
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                        ?.isEnabled == true
                } catch (_: SecurityException) {
                    true
                }
            if (!on) {
                DarkMessageDialog.show(
                    activity = this,
                    title = "Bluetooth не включен",
                    message =
                        "Беспроводная связь с автомобилем осуществляется через Bluetooth. Сначала включите " +
                            "Bluetooth на вашем телефоне и подтвердите, что он сопряжен с автомобилем.",
                    actions =
                        listOf(
                            DarkMessageDialog.Action("Отмена") {},
                            DarkMessageDialog.Action("Перейдите, чтобы включить Bluetooth", DarkMessageDialog.Emphasis.PRIMARY) {
                                runCatching {
                                    startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                                    )
                                }.onFailure { log("BT: Невозможно открыть настройки Bluetooth, откройте их вручную.") }
                            },
                        ),
                )
                log("BT: Bluetooth не включен — это соединение не инициировано")
                return@ensureWifiLocationReady
            }
            onReady()
        }
    }

    /**
     * Сеть для конкретного приложения  (WifiNetworkSpecifier) требует разрешения на определение местоположения и
     * служб определения местоположения в Android 12/12L;
     * 13+ заменен NEARBY_WIFI_DEVICES и больше не требует позиционирования. Если разрешения отсутствуют, система будет напрямую
     * `requestNetwork` не удалось (отображается как «не удалось подключиться к автомобильной точке доступа», ее
     * сложно проверить самостоятельно), поэтому он перехватывается заранее.
     */
    private fun ensureWifiLocationReady(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onReady()
            return
        }
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterLocationPermission.set(true)
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val locationOn =
            try {
                androidx.core.location.LocationManagerCompat
                    .isLocationEnabled(getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
            } catch (_: Exception) {
                true
            }
        if (!locationOn) {
            DarkMessageDialog.show(
                activity = this,
                title = "Необходимо включить службы определения местоположения.",
                message =
                    "Добавление в Android 12 автомобильной точки доступа (Wi-Fi для конкретного приложения) требует " +
                        "включения служб определения местоположения — система использует это для определения" +
                        "Подключаемый Wi-Fi, который не регистрирует ваше местоположение. \n\nВключите службы " +
                        "определения местоположения и повторите попытку; вы также можете пойти в" +
                        "Настройки WLAN вручную подключаются к точкам доступа.",
                actions =
                    listOf(
                        DarkMessageDialog.Action("Отмена") {},
                        DarkMessageDialog.Action("открыть", DarkMessageDialog.Emphasis.PRIMARY) {
                            runCatching {
                                startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                )
                            }.onFailure {
                                log(
                                    "WIFI: невозможно открыть настройки службы определения местоположения, " +
                                        "включите ее вручную.",
                                )
                            }
                        },
                    ),
            )
            log(
                "WIFI: служба определения местоположения не включена — сеть для конкретного приложения недоступна в " +
                    "Android 12, и это соединение не было инициировано.",
            )
            return
        }
        onReady()
    }

    /** Автоматически продолжать инициированные соединения после авторизации разрешения на определение местоположения (только для Android 12/12L). */
    private val pendingStartAfterLocationPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("ACCESS_FINE_LOCATION Авторизация: $granted")
            if (granted && pendingStartAfterLocationPermission.compareAndSet(true, false)) {
                ensureShizukuReady { showAudioChoiceDialog() }
            }
        }

    /**
     * Всплывающее окно подтверждения «Отключения» (для предотвращения случайного касания): Отключение немедленно прервет экран автомобиля.
     * , и авторизация скринкаста становится недействительной, когда служба поддержания активности остановлена
     * ​​(звук со стороны автомобиля необходимо повторно авторизовать в следующий раз).
     */
    private fun promptDisconnect() {
        DarkMessageDialog.show(
            activity = this,
            title = "Отключиться?",
            message =
                "Потоковая передача данных в автомобиль и машину будет остановлена, служба поддержки активности будет " +
                    "отключена, а экран автомобиля и машины будет немедленно прерван. \n\n" +
                    "Примечание. Срок действия разрешения на зеркалирование экрана истечет после остановки службы. Если " +
                    "в следующий раз вам понадобится звук со стороны автомобиля, повторно авторизуйте его.",
            actions =
                listOf(
                    DarkMessageDialog.Action("Отмена") {},
                    DarkMessageDialog.Action("Отключить", DarkMessageDialog.Emphasis.DANGER) {
                        SessionController.disconnect(this)
                    },
                ),
        )
    }

    /**
     * Жесткое условие для «Начать соединение»: Шизуку должна быть готова.
     *
     * Создание виртуального экрана, сенсорное внедрение, считывание состояния окна/дисплея в режиме невидимого
     * экрана **все через оболочку Shizuku**;
     * Когда       не готов, соединение перейдет в полуотключенное состояние "Bluetooth подключен, экран автомобиля
     * черный + все касания потеряны", что хуже, чем его прямая блокировка.
     * Когда       не готов, соответствующая работоспособная загрузка (установка/запуск/авторизация) предоставляется
     * в соответствии с тремя состояниями.
     */

    /**
     * автоматически предоставляет права на использование уведомлений (идемпотент; фоновый поток).
     *
     * **Вы не можете попробовать это только один раз в onCreate**: если Шизуку к этому времени не будет готова, она
     * будет пропущена навсегда.
     * Пользователи       видят «Музыка играет, но карта показывает, что медиаинформация не подключена» — при отсутствии авторизации
     * `getActiveSessions()` по-прежнему возвращает сеанс (статус воспроизведения нормальный), но `metadata` всегда имеет значение null.
     * Поэтому корректируется один раз каждый раз "Шизуку подтверждает готовность"; при авторизации он возвращается
     * непосредственно внутри компании.
     * не вызывает дополнительных обходов оболочки.
     */
    private fun ensureNotificationAccessAsync() {
        Bg.run("notification-access") {
            if (ShizukuGate.state(this) != ShizukuGate.State.READY) {
                log(
                    "МЕДИА: Шизуку не готова - право на использование уведомлений не может быть предоставлено " +
                        "автоматически на данный момент (будет добавлено, когда оно будет готово)",
                )
                return@run
            }
            NotificationAccess.ensureGranted(this, ::log)
        }
    }

    private fun ensureShizukuReady(onReady: () -> Unit) {
        val state = ShizukuGate.state(this)
        if (state == ShizukuGate.State.READY) {
            // Шизуку может молча пополнить авторизацию, когда она будет готова (если раньше она была пропущена из-за
            // неготовности, добавьте ее сюда)

            ensureNotificationAccessAsync()
            onReady()
            return
        }
        log("ШИЗУКУ: Не готов ($state) — это соединение заблокировано.")
        promptShizukuNotReady(state)
    }

    /**
     * Всплывающее окно Shizuku не готова к загрузке: основная операция меняется в зависимости от статуса с
     * прикрепленными «повторной проверкой» и «отменой».
     * переходит на саморисованную панель [DarkMessageDialog] — системный AlertDialog имеет светлую тему и отделен от
     * темного пользовательского интерфейса.
     */
    private fun promptShizukuNotReady(state: ShizukuGate.State) {
        val guidance = ShizukuGate.guidanceOf(state)
        val actions = mutableListOf<DarkMessageDialog.Action>()
        actions += DarkMessageDialog.Action("Отмена") {}
        actions +=
            DarkMessageDialog.Action("повторить проверку") {
                ensureShizukuReady { showAudioChoiceDialog() }
            }
        when (state) {
            ShizukuGate.State.NOT_RUNNING ->
                actions +=
                    DarkMessageDialog.Action("Открыть Шизуку", DarkMessageDialog.Emphasis.PRIMARY) {
                        val intent = packageManager.getLaunchIntentForPackage(ShizukuGate.PACKAGE_NAME)
                        if (intent == null) {
                            snack("Невозможно открыть приложение Shizuku. Откройте вручную.")
                        } else {
                            runCatching { startActivity(intent) }
                                .onFailure { snack("Невозможно открыть приложение Shizuku. Откройте вручную.") }
                        }
                    }

            ShizukuGate.State.UNAUTHORIZED ->
                actions +=
                    DarkMessageDialog.Action("Запросить авторизацию", DarkMessageDialog.Emphasis.PRIMARY) {
                        pendingShizukuThenConnect = true
                        ShizukuGate.requestPermission()
                        snack("Пожалуйста, нажмите «Разрешить» в окне авторизации Шизуку — после разрешения она продолжится автоматически.")
                    }

            // Не установлено: нет исполняемого основного действия (ссылки на установку не предоставлены).
            ShizukuGate.State.NOT_INSTALLED, ShizukuGate.State.READY -> Unit
        }
        DarkMessageDialog.show(
            activity = this,
            title = guidance.title,
            message = guidance.message,
            actions = actions,
        )
    }

    private fun beginWithAudio(enabled: Boolean) {
        SessionController.audioEnabledForSession = enabled
        // Для звука остается только один режим: согласование канала (приоритет имеет AAC-LC, в случае неудачи он
        // автоматически переключается на PCM) и автоматически завершается в рамках сеанса.

        // Больше нет ручного разветвления «файлов совместимости».
        SessionController.audioPreferCompat = false
        if (!enabled) {
            log("РЕЖИМ: Данное подключение передает только видео (звук на стороне автомобиля не включается)")
            startConnectionNow()
            return
        }
        if (CarPlayService.mediaProjection != null) {
            log("РЕЖИМ: выбрано для включения звука (разрешение на трансляцию экрана готово)")
            startConnectionNow()
            return
        }
        // Для включения звука требуется разрешение на кастинг экрана (источник авторизации для воспроизведения
        // захваченного): сначала примените, а затем запустите соединение после завершения авторизации

        log(
            "РЕЖИМ: Для включения звука требуется разрешение на скринкаст — подайте заявку на него поочередно (от этого " +
                "зависит захват звука), и после авторизации соединение начнется автоматически.",
        )
        pendingStartAfterProjection.set(true)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /** Субъект «Инициировать соединение» (здесь остается проверка разрешений; инициация сеанса передается [SessionController], идемпотентному и не открывающемуся дважды). */
    private fun startConnectionNow() {
        runOnUiThread {
            // Нижняя защита: сюда также могут прийти пути задержки, такие как обратные вызовы авторизации, и Шизуку также
            // необходимо остановить, когда он останавливается на полпути.

            val gate = ShizukuGate.state(this)
            if (gate != ShizukuGate.State.READY) {
                log("ШИЗУКУ: Не готов ($gate) — это соединение заблокировано.")
                promptShizukuNotReady(gate)
                return@runOnUiThread
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // Разрешения на уведомления (API 33+): уведомления служб переднего плана видны; отказ не влияет на сохранение жизни
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
            SessionController.startConnection(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Вращение/Реконструкция: используйте исходное время окончания обратного отсчета (соответственно возобновляется onResume).
        disclaimerDeadlineAt = savedInstanceState?.getLong(KEY_DISCLAIMER_DEADLINE, 0L) ?: 0L

        appVersionFooter = findViewById(R.id.appVersionFooter)
        appVersionFooter.text = "CarDeck v${BuildConfig.VERSION_NAME}"
        deviceSubtitle = findViewById(R.id.deviceSubtitle)
        shizukuChip = findViewById(R.id.shizukuChip)
        shizukuDesc = findViewById(R.id.shizukuDesc)
        shizukuBadge = findViewById(R.id.shizukuBadge)
        signalWaves = findViewById(R.id.signalWaves)
        hubPlate = findViewById(R.id.hubPlate)
        hubCar = findViewById(R.id.hubCar)
        hubCaption = findViewById(R.id.hubCaption)
        heroStateText = findViewById(R.id.heroStateText)
        btnResidualCleanup = findViewById(R.id.btnResidualCleanup)
        btnResidualCleanup.setOnClickListener {
            pendingResidualDisplays?.let { promptResidualDisplays(it) }
        }
        heroSubline = findViewById(R.id.heroSubline)
        heroStats = findViewById(R.id.heroStats)
        heroProgress = findViewById(R.id.heroProgress)
        ctaButton = findViewById(R.id.ctaButton)
        ctaIcon = findViewById(R.id.ctaIcon)
        ctaLabel = findViewById(R.id.ctaLabel)
        audioTag = findViewById(R.id.audioTag)
        readyCount = findViewById(R.id.readyCount)
        ssidInput = findViewById(R.id.ssidInput)
        btDesc = findViewById(R.id.btDesc)
        btBadge = findViewById(R.id.btBadge)
        btnPickTarget = findViewById(R.id.btnPickTarget)
        audioDesc = findViewById(R.id.audioDesc)
        btnAudio = findViewById(R.id.btnAudio)
        switchAlwaysOn = findViewById(R.id.switchAlwaysOn)
        advBody = findViewById(R.id.advBody)
        advChevron = findViewById(R.id.advChevron)
        shellStateText = findViewById(R.id.shellStateText)
        logBody = findViewById(R.id.logBody)
        logChevron = findViewById(R.id.logChevron)
        switchLog = findViewById(R.id.switchLog)
        logText = findViewById(R.id.logText)
        snackbar = findViewById(R.id.snackbar)

        deviceSubtitle.text = deviceName()
        logText.movementMethod = ScrollingMovementMethod()
        // Консоль находится в длинной странице ScrollView: при нажатии родительское приложение не перехватывает
        // вертикальные жесты, иначе невозможно выполнить ручную прокрутку.

        logText.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP,
                -> updateLogBottomButton()
            }
            false
        }
        findViewById<ImageView>(R.id.btnLogBottom).setOnClickListener {
            // Перейти вниз: автоматическое следование вниз будет восстановлено после прокрутки.
            scrollConsoleToBottom()
        }

        // Процесс перезапуска процесса: если последний сеанс не завершился нормально, необходимо восстановить тайм-аут
        // экрана, измененный на «всегда включен».

        run {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs.contains("screen_off_timeout_backup") &&
                VirtualLauncher.appDisplayId < 0
            ) {
                val backup = prefs.getInt("screen_off_timeout_backup", 60_000)
                prefs.edit().remove("screen_off_timeout_backup").apply()
                Bg.run("screen-timeout-restore") {
                    Shell.exec(
                        "settings put system screen_off_timeout $backup",
                    )
                }
            }
        }

        // Результат авторизации Шизуку: строка статуса и подзаголовок карты героя обновляются немедленно; если
        // пользователь руководствуется «Запросить авторизацию»

        // Если вы зайдете сюда (pendingShizukuThenConnect), то после успешной авторизации вы сможете сразу перейти к
        // выбору аудио без необходимости повторного нажатия.

        shizukuPermissionListener =
            rikka.shizuku.Shizuku
                .OnRequestPermissionResultListener { requestCode, grantResult ->
                    if (requestCode != ShizukuGate.PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        log("ШИЗУКУ: Результат авторизации предоставлен = $granted")
                        updateReadyRow()
                        updateShizukuChip()
                        if (granted) {
                            // Шизуку как раз готова: пополните права на использование уведомлений (смените имя пакета/авторизуйтесь в
                            // первый
                            // раз после перезапуска)

                            // Типичный сценарий — onCreate был пропущен, потому что он не был готов)
                            ensureNotificationAccessAsync()
                        }
                        if (granted && pendingShizukuThenConnect) {
                            pendingShizukuThenConnect = false
                            ensureShizukuReady { showAudioChoiceDialog() }
                        } else if (!granted) {
                            snack("Не авторизовано Шизуку – соединение по-прежнему недоступно")
                        }
                    }
                }.also {
                    runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(it) }
                }

        // Концентратор сеансов (уровень процесса): зарегистрировать applicationContext + создать объект сеанса;
        // Регистрация зависающего экрана: продолжение отображения журнала + заполнение SSID
        SessionController.ensure(this)
        SessionController.logListener = logListener
        // Важные напоминания о процессе подключения (Wi-Fi не включен/ограничения на подключение к точке доступа и т.
        // д.) постоянно отображаются в Snackbar.

        SessionController.uiHintSink = uiHintSinkRef
        // Присоединитесь к всплывающему окну подтверждения точки доступа: продолжайте присоединяться автоматически /
        // откройте настройки WLAN для подключения вручную.

        // При отмене соединения связь cancel() закрывается (триггер onChoice(null) → процесс проверяет флаг отмены и прерывает его)
        SessionController.dialogSink = { spec, onChoice ->
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    onChoice(null)
                    return@runOnUiThread
                }
                var answered = false
                // Отмена (ключ возврата/точка снаружи/закрытие посредством связи cancelConnecting) = null, процесс
                // соответствующим образом прерывается;

                // Помимо обратного вызова false, «Открыть WLAN» также отправляет пользователя на системную страницу WLAN для
                // подключения вручную.

                val dialog =
                    DarkMessageDialog.show(
                        activity = this,
                        title = spec.title,
                        message = spec.message,
                        actions =
                            listOf(
                                DarkMessageDialog.Action(spec.negative) {
                                    answered = true
                                    runCatching {
                                        startActivity(
                                            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
                                        )
                                    }
                                    onChoice(false)
                                },
                                DarkMessageDialog.Action(
                                    spec.positive,
                                    DarkMessageDialog.Emphasis.PRIMARY,
                                ) {
                                    answered = true
                                    onChoice(true)
                                },
                            ),
                        onCancel = { if (!answered) onChoice(null) },
                    )
                wifiJoinDialog = dialog
            }
        }
        SessionController.ssidSink = ssidSink
        renderConsole(SessionController.logText())

        setUpUiControls()
        // OnConfigurationChanged не будет срабатывать при запуске в ландшафтном режиме. Здесь раскладка определяется в
        // соответствии с текущим направлением.

        // contentArea не измерялась при первом вызове (width=0, может быть возвращена только оценка ширины экрана),
        // После завершения макета пересчитайте его точно, чтобы избежать разницы в несколько пикселей между шириной
        // карты героя и правым столбцом.

        val startLandscape =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        applyContentLayout(startLandscape)
        findViewById<android.widget.FrameLayout>(R.id.contentArea).post {
            if (!isFinishing && !isDestroyed) applyContentLayout(startLandscape)
        }

        // Отказ от ответственности: Принудительное удаление, если текущая версия не принята (нельзя отменить, требуется
        // обратный отсчет + слайд вниз).

        // Если saveState не пусто (ротация/реконструкция), исходное время крайнего срока используется повторно без изменения времени.
        if (savedInstanceState == null &&
            needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            )
        ) {
            showDisclaimer(requireAcceptance = true)
        }
    }

    /** Запись «Отказ от ответственности» внизу: только для чтения при повторном просмотре (без ограничений по времени, без необходимости прокрутки вниз). */
    private fun showDisclaimer(requireAcceptance: Boolean) {
        if (activeDisclaimer?.isShowing == true) return
        if (requireAcceptance && disclaimerDeadlineAt == 0L) {
            disclaimerDeadlineAt = SystemClock.elapsedRealtime() + DisclaimerDialog.COUNTDOWN_MS
        }
        activeDisclaimer =
            DisclaimerDialog
                .show(
                    activity = this,
                    requireAcceptance = requireAcceptance,
                    deadlineAt = disclaimerDeadlineAt,
                    onDecline = {
                        // Прекращение использования: завершите эту задачу и выйдите (без System.exit сохранится завершение обычного
                        // жизненного цикла).

                        // Если сеанс уже запущен, отключите его обычным способом, чтобы не оставлять неуправляемые push-потоки и
                        // виртуальные экраны.

                        runCatching { SessionController.disconnect(this) }
                        finishAndRemoveTask()
                    },
                    onDismiss = { activeDisclaimer = null },
                )
    }

    /** Хвост инициализации UI onCreate: переключатель/кнопка/свернуть карточку (логика сеанса остается неизменной, висит только новый интерфейс). */
    private fun setUpUiControls() {
        val preferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Очистите настройки режима, оставшиеся от старой версии (режим исправлен для запуска «Невидимый экран»).
        if (preferences.contains("mode")) {
            preferences.edit().remove("mode").apply()
        }

        // ----Отказ от ответственности (пересмотр только для чтения)----
        findViewById<LinearLayout>(R.id.btnDisclaimer).setOnClickListener {
            showDisclaimer(requireAcceptance = false)
        }

        // ---- Вход в удаленную отладку: отображается только в том случае, если в этом компоненте существует пакет
        // удаленной отладки (формальное разрешение пакета имеет значение null и всегда скрыто;

        // Основной код не ссылается ни на один класс моста, полагается на обнаружение компонентов для достижения
        // различий, а код отладки не смешивается с официальным продуктом) ----

        val bridgeSetup =
            Intent().setClassName(packageName, "io.github.shaonialife.cardeck.bridge.BridgeSetupActivity")
        if (packageManager.resolveActivity(bridgeSetup, 0) != null) {
            findViewById<LinearLayout>(R.id.btnBridgeSetup).visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.btnBridgeSetup).setOnClickListener {
                startActivity(bridgeSetup)
            }
        }

        // ---- Строка состояния Сидзуку: когда она не готова, щелкните ее, чтобы просмотреть руководство напрямую (тот
        // же набор копирайтинга, что и «Начать подключение», заблокирован) ----

        findViewById<LinearLayout>(R.id.rowShizuku).setOnClickListener {
            val state = ShizukuGate.state(this)
            if (state == ShizukuGate.State.READY) {
                snack("Shizuku готов — доступен виртуальный экран и сенсорное впрыскивание.")
            } else {
                promptShizukuNotReady(state)
            }
        }

        // ----Всегда включен + темный экран ----
        ScreenDimmer.enabled =
            preferences.getBoolean("alwaysOn", true)
        switchAlwaysOn.isChecked = ScreenDimmer.enabled
        switchAlwaysOn.setOnCheckedChangeListener { _, checked ->
            ScreenDimmer.enabled = checked
            preferences.edit().putBoolean("alwaysOn", checked).apply()
            if (checked) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    promptOverlayPermission("«Всегда включен + темный экран» включен.")
                } else {
                    ScreenDimmer
                        .attach(this)
                }
            } else {
                if (VirtualLauncher.appDisplayId >= 0) {
                    // Во время сеанса: отключается только всегда включенный/темный экран (такие ресурсы, как датчики проекции
                    // экрана, зарезервированы для единого выпуска после завершения сеанса).

                    ScreenDimmer
                        .disableDim()
                } else {
                    ScreenDimmer
                        .detach()
                }
            }
            log("РЕЖИМ: Всегда включено+Темный режим ${if (checked) "вкл." else "выкл."}")
        }

        // ---- Операция мастера карты героя ----
        ctaButton.setOnClickListener {
            when (heroState) {
                HeroState.CONNECTED -> promptDisconnect()
                // Предварительная проверка: Шизуку (жесткая зависимость) → Разрешение/переключение Bluetooth → Выбор аудио.
                HeroState.IDLE -> ensureShizukuReady { ensureBluetoothReady { showAudioChoiceDialog() } }
                // Можно отменить во время подключения: застрял не в той сети/пользователь добровольно убегает, ожидая повторного
                // подключения (всплывающее окно предотвращает случайное касание)

                HeroState.CONNECTING -> promptCancelConnecting()
                null -> {}
            }
        }

        // ---- Подготовка к подключению ----
        findViewById<TextView>(R.id.btnWifi).setOnClickListener {
            val ssid = ssidInput.text.toString().trim()
            // Резервное копирование вручную, когда автоматическое присоединение недоступно: пароль помещается в буфер обмена
            // и может быть вставлен на системную страницу WLAN.

            val psk = SessionController.lastWifiPsk
            if (psk != null) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("carplay-wifi", psk))
                snack("Пароль точки доступа скопирован (биты ${psk.length}) — просто вставьте его в поле пароля.")
                log(
                    "Wi-Fi: пароль точки доступа скопирован в буфер обмена. Чтобы вставить пароль, выберите \"$ssid\";" +
                        "Если появится сообщение о том, что \" не может получить доступ к Интернету \", выберите оставаться на связи.",
                )
            } else {
                snack("Пароля от точки доступа пока нет — он будет получен автоматически после подключения Bluetooth")
                log(
                    "Wi-Fi: у меня еще нет пароля к точке доступа (требуется сеанс Bluetooth 0x5703) — откройте " +
                        "настройки WLAN и вручную выберите \"$ssid\"",
                )
            }
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                log("Wi-Fi: невозможно открыть настройки WLAN. Перейдите в «Настройки» > «WLAN» вручную.")
            }
        }

        // Выбор целевого транспортного средства и компьютера: укажите единственную цель, когда несколько транспортных
        // средств и компьютеров соединены в пару (только отправлять запросы на нее/принимать только соединения от нее)

        btnPickTarget.setOnClickListener { showTargetPicker() }

        // Разрешение звука на стороне автомобиля (ранее «Заявка на разрешение на трансляцию экрана»): RECORD_AUDIO +
        // запрос MediaProjection вместе.

        btnAudio.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }

        // ---- Дисплей автомобиля ----
        findViewById<LinearLayout>(R.id.rowDim).setOnClickListener {
            if (!ScreenDimmer
                    .isAttached()
            ) {
                snack("Темный экран не готов (включается автоматически после успешного подключения)")
                return@setOnClickListener
            }
            ScreenDimmer
                .enterDimNow()
            log("DIMMER: вручную отключить экран (нажмите на экран, чтобы восстановить)")
        }

        findViewById<TextView>(R.id.btnWallpaperPick).setOnClickListener {
            wallpaperPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }
        findViewById<TextView>(R.id.btnWallpaperReset).setOnClickListener {
            val ok =
                WallpaperStore
                    .resetToDefault(this)
            log(
                "WALLPAPER: " +
                    "${if (ok) "Встроенные настройки по умолчанию восстановлены.（CarPlay Официальные темные обои）" else "Восстановление не удалось"}",
            )
            snack(if (ok) "Обои по умолчанию восстановлены" else "Восстановление не удалось")
        }

        // ---- Сложенные карты: Расширенный/Журнал ----
        bindExpandable(R.id.advHeader, advBody, advChevron)
        bindExpandable(R.id.logHeader, logBody, logChevron)

        // ---- Глобальный статус оболочки ----
        findViewById<View>(R.id.btnShellRefresh).setOnClickListener { refreshShellState() }
        findViewById<View>(R.id.btnShellReset).setOnClickListener { resetShellState() }
        refreshShellState(startupCheck = true)

        // ---- Переключение журнала (по умолчанию включено; после закрытия сбор и обработка не выполняются; выберите сохранение) ----
        var logEnabled = preferences.getBoolean("logEnabled", true)
        SessionController.logEnabled = logEnabled
        switchLog.isChecked = logEnabled
        switchLog.setOnCheckedChangeListener { _, checked ->
            logEnabled = checked
            SessionController.logEnabled = checked
            preferences.edit().putBoolean("logEnabled", checked).apply()
            renderConsole(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnClearLog).setOnClickListener {
            // После очистки буфера необходимо явно выполнить повторную визуализацию: обратный вызов журнала имеет
            // инкрементальную семантику добавления, уведомление ClearLog.

            // Невозможно очистить пользовательский интерфейс (см. комментарий SessionController.clearLog, основная причина
            // «Нет ответа после нажатия»)

            SessionController.clearLog()
            renderConsole(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnCopyLog).setOnClickListener {
            val text = SessionController.logText()
            if (text.isEmpty()) {
                snack("Журнал пуст")
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Журнал CarDeck", text))
            snack("Журнал символов ${text.length} скопирован.")
        }
        findViewById<ImageView>(R.id.btnExportLog).setOnClickListener { exportLogFile() }
        // Длительное нажатие для импорта всей истории: по умолчанию будет экспортирован только этот запуск. Если объем
        // исторических журналов велик, вы можете получить их вручную по мере необходимости.

        findViewById<ImageView>(R.id.btnExportLog).setOnLongClickListener {
            exportLogFile(LogExporter.Scope.ALL)
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Права на использование уведомлений (перечисление медиа-сессий, источник данных музыкальной карты):
        // предоставляются автоматически, когда Шизуку готова.

        // Идемпотентный и повторяемый — он может быть не готов при запуске (Shizuku не запускается после перезапуска и
        // не авторизуется после изменения имени пакета),

        // Таким образом, время успешной авторизации Шизуку, установления соединения и т. д. будет снова скорректировано.
        ensureNotificationAccessAsync()

        // ---- Настройки автомобиля (дополнительное меню на мобильном телефоне: рабочий
        // стол/пейзаж/суперсэмплинг/док/карта по умолчанию) ----

        findViewById<LinearLayout>(R.id.btnCarSettings).setOnClickListener {
            startActivity(android.content.Intent(this, PhoneSettingsActivity::class.java))
        }

        // Проверка при запуске: Всегда включено + Нет разрешения на плавающее окно → Активный запрос.
        if (ScreenDimmer.enabled) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                promptOverlayPermission("«Всегда включен + темный экран» включен.")
            } else {
                // Присоединение только зависает с сенсорными датчиками, не включает автоматический таймер темного экрана:
                // автоматический темный экран/всегда включен, зависит от сеанса

                // (ScreenDimmer.setSessionActive), в противном случае он станет черным через 45 секунд, если он не подключен.
                // —— Фактический пользовательский тест «темный экран будет активирован при отключении сеанса» таков (19 октября 2026 г.).
                ScreenDimmer
                    .attach(this)
            }
        }

        TouchRouter.logSink = { line -> log(line) }
        VirtualLauncher.logSink = { line -> log(line) }
        CarInputClient.logSink = { line -> log(line) }

        log("CarDeck Фаза 0 готова")
        log(
            "Настоящий автомобиль: соединение Bluetooth с автомобилем + добавление телефона в точку доступа автомобиля " +
                "-> нажмите «Начать подключение» (автоматическое подключение к автомобилю)",
        )
        log(
            "Тестовый стенд: соединение двух мобильных телефонов по Bluetooth -> Запустите беспроводной режим xcertplay " +
                "на резервном компьютере -> Нажмите «Начать подключение», чтобы дождаться повторного подключения.",
        )
    }

    private fun bindExpandable(
        headerId: Int,
        body: View,
        chevron: ImageView,
    ) {
        findViewById<View>(headerId).setOnClickListener {
            val opening = body.visibility != View.VISIBLE
            body.visibility = if (opening) View.VISIBLE else View.GONE
            chevron
                .animate()
                .rotation(if (opening) 180f else 0f)
                .setDuration(250L)
                .start()
        }
    }

    // ==================== Конечный автомат карты героя ===================

    private fun tickHero() {
        val s =
            when {
                SessionController.videoSender?.isRunning == true -> HeroState.CONNECTED
                SessionController.isSessionRunning() || SessionController.sessionStarting.get() ->
                    HeroState.CONNECTING
                else -> HeroState.IDLE
            }
        if (s != heroState) {
            if (s == HeroState.CONNECTED) connectedAt = SystemClock.elapsedRealtime()
            heroState = s
            applyHeroState(s)
        }
        // Подключение: подзаголовок следует за подсказками на этапе (вы можете увидеть его на карточке героя, вам не
        // нужно прокручивать вниз, чтобы прочитать журнал)

        if (s == HeroState.CONNECTING) {
            heroSubline.text = SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT
        }
        // Запустить остаточную проверку экрана: статус и субтитры временно переключаются на подсказки проверки.
        if (s == HeroState.IDLE && startupCheckRunning) {
            heroStateText.text = "Под проверкой"
            heroSubline.text = "Проверка наличия остаточного экрана..."
            heroProgress.visibility = View.VISIBLE
        }
        // Бездействует, но Шизуку не готов: в подзаголовке прямо указано «Невозможно подключиться даже после нажатия»,
        // чтобы пользователи не нажимали повторно.

        if (s == HeroState.IDLE && !startupCheckRunning) {
            val gate = ShizukuGate.state(this)
            if (gate != ShizukuGate.State.READY) {
                heroSubline.text =
                    "Шизуку не готова (${ShizukuGate.guidanceOf(gate).title})——" +
                    "Невозможно подключиться. Нажмите «Шизуку» ниже, чтобы узнать, что делать."
            }
        }
        updateConnectedSubline()
        updateHeroStats()
        updateAudioTag()
        updateReadyRow()
        updateShizukuChip()
    }

    /**
     * Строка статистики карты героя (отображается только при подключении): разрешение во время выполнения +
     * фактическая/целевая частота кадров.
     * Фактическая частота кадров = 1 с. При опросе учитывается разница между количеством отправленных кадров;
     * сначала выровняйте базовую линию, когда количество новых сеансов вернется к нулю.
     */
    private fun updateHeroStats() {
        if (heroState != HeroState.CONNECTED) return
        val sent = SessionController.videoSender?.framesSentTotal ?: 0
        if (sent < lastSentFrames) lastSentFrames = sent
        val fps = sent - lastSentFrames
        lastSentFrames = sent
        heroStats.text =
            "Разрешение ${SessionController.activeScalePercent}% ·" +
            "$fps/${SessionController.activeTargetFps} fps"
    }

    private fun applyHeroState(s: HeroState) {
        signalWaves.setState(
            when (s) {
                HeroState.IDLE -> SignalWavesView.State.IDLE
                HeroState.CONNECTING -> SignalWavesView.State.CONNECTING
                HeroState.CONNECTED -> SignalWavesView.State.CONNECTED
            },
        )
        val (plateColor, carColor) =
            when (s) {
                HeroState.IDLE -> getColor(R.color.m3_surface_high) to getColor(R.color.m3_on_var)
                HeroState.CONNECTING ->
                    getColor(R.color.m3_pri_container) to getColor(R.color.m3_on_pri_container)
                HeroState.CONNECTED -> getColor(R.color.m3_primary) to getColor(R.color.m3_on_primary)
            }
        hubPlate.background.mutate().setTint(plateColor)
        hubCar.setColorFilter(carColor)
        hubCaption.setTextColor(
            if (s == HeroState.IDLE) getColor(R.color.m3_mono_dim) else 0xFF5C8B96.toInt(),
        )

        hubBlink?.cancel()
        hubBlink = null
        hubCar.alpha = 1f
        if (s == HeroState.CONNECTING) {
            hubBlink =
                ObjectAnimator.ofFloat(hubCar, View.ALPHA, 1f, 0.45f).apply {
                    duration = 1_200L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
        }

        heroStateText.text =
            when (s) {
                HeroState.IDLE -> "Не подключено"
                HeroState.CONNECTING -> "Подключение"
                HeroState.CONNECTED -> "Подключено"
            }
        heroProgress.visibility = if (s == HeroState.CONNECTING) View.VISIBLE else View.GONE
        heroStats.visibility = if (s == HeroState.CONNECTED) View.VISIBLE else View.GONE

        when (s) {
            HeroState.IDLE -> {
                val pending = pendingResidualDisplays
                heroSubline.text =
                    if (pending != null) {
                        "Последний сеанс завершился аварийно, оставив виртуальные экраны ${pending.size} подлежащими очистке."
                    } else {
                        "После сопряжения с Bluetooth автомобиля нажмите кнопку ниже, чтобы инициировать беспроводное соединение."
                    }
                styleCta(R.drawable.bg_cta_fill, R.color.m3_on_primary, R.drawable.ic_play, "Подключить")
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTING -> {
                heroSubline.text = SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT
                styleCta(R.drawable.bg_cta_fill, R.color.m3_on_primary, R.drawable.ic_close, "Отменить соединение")
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTED -> {
                styleCta(R.drawable.bg_cta_outline_err, R.color.m3_error, R.drawable.ic_power, "Отключить")
                audioTag.visibility = View.VISIBLE
            }
        }

        ctaIcon.rotation = 0f
    }

    /** Кнопки основных операций на карте героя имеют единый стиль (у трех состояний только разные стили/значки/копирайтинг). */
    private fun styleCta(
        bgRes: Int,
        colorRes: Int,
        iconRes: Int,
        label: String,
    ) {
        ctaButton.setBackgroundResource(bgRes)
        ctaLabel.setTextColor(getColor(colorRes))
        ctaIcon.imageTintList =
            android.content.res.ColorStateList
                .valueOf(getColor(colorRes))
        ctaIcon.setImageResource(iconRes)
        ctaLabel.text = label
        ctaButton.isEnabled = true
    }

    private fun updateConnectedSubline() {
        if (heroState != HeroState.CONNECTED) return
        val elapsed = SystemClock.elapsedRealtime() - connectedAt
        val mm = elapsed / 60_000
        val ss = elapsed % 60_000 / 1_000
        val ssid =
            ssidInput.text
                .toString()
                .trim()
                .ifEmpty { "Точка доступа автомобиля" }
        heroSubline.text = String.format(Locale.US, "Подключено · %s · %02d:%02d · Потоковое видео", ssid, mm, ss)
    }

    private fun updateAudioTag() {
        if (heroState != HeroState.CONNECTED) return
        val on =
            SessionController.audioEnabledForSession &&
                SessionController.audioSender?.isRunning == true
        val icon =
            ContextCompat.getDrawable(
                this,
                if (on) R.drawable.ic_music_small else R.drawable.ic_volume_off_small,
            )!!
        DrawableCompat.setTint(icon.mutate(), getColor(if (on) R.color.m3_ok else R.color.m3_outline))
        audioTag.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        audioTag.text = if (on) "Звук со стороны автомобиля · Вкл." else "Звук со стороны автомобиля · Выкл."
    }

    /**
     * Количество готовых подключений: Shizuku / SSID точки доступа / сопряжение Bluetooth / разрешение зеркала.
     *
     * Shizuku **жестко добавлен** (соединение будет заблокировано, если оно не готово), поэтому оно учитывается в
     * общем объеме, а строка состояния отображается отдельно.
     */
    private fun updateReadyRow() {
        var ready = 0
        val shizukuState = ShizukuGate.state(this)
        val shizukuReady = shizukuState == ShizukuGate.State.READY
        if (shizukuReady) ready++
        updateShizukuRow(shizukuState)
        if (ssidInput.text
                .toString()
                .trim()
                .isNotEmpty()
        ) {
            ready++
        }
        val btGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        var bondedCount = -1
        if (btGranted) {
            try {
                val adapter =
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                bondedCount = adapter?.bondedDevices?.size ?: -1
            } catch (_: SecurityException) {
                bondedCount = -1
            }
        }
        if (bondedCount > 0) ready++
        if (CarPlayService.mediaProjection != null) ready++

        btBadge.visibility = if (bondedCount >= 0) View.VISIBLE else View.GONE
        if (bondedCount >= 0) {
            val target = TargetDevicePrefs.get(this)
            if (bondedCount > 0) {
                btBadge.background.mutate().setTint(getColor(R.color.m3_ok_dot))
                btBadge.text = "Готово"
                btDesc.text =
                    if (target != null) {
                        "Цель «${target.name}» · Отправлять запросы только на это устройство"
                    } else if (bondedCount > 1) {
                        "Парные единицы $bondedCount · Неуказанные цели будут проверены один за другим"
                    } else {
                        "Сопряжено с устройствами $bondedCount · Соединение восстанавливается автоматически"
                    }
            } else {
                btBadge.background.mutate().setTint(getColor(R.color.m3_amber))
                btBadge.text = "Не в паре"
                btDesc.text = "Еще не подключено — нажмите «Выбрать устройство», чтобы отсканировать и подключить автомобиль к устройству."
            }
            // Запрос на повторный выбор, когда целевое устройство больше не находится в списке сопряженных устройств
            // (например, сопряжение удалено в системе)

            if (target != null && bondedCount > 0) {
                val stillBonded =
                    try {
                        (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                            .adapter
                            ?.bondedDevices
                            ?.any { TargetDevicePrefs.matches(target, it.address) } == true
                    } catch (_: SecurityException) {
                        true
                    }
                if (!stillBonded) {
                    btBadge.background.mutate().setTint(getColor(R.color.m3_amber))
                    btBadge.text = "Необходимо повторно выбрать"
                    btDesc.text = "Цели «${target.name}» больше нет в списке сопряжения. Выберите другое устройство."
                }
            }
            // Во всплывающем окне можно сканировать и напрямую подключать несвязанные устройства, поэтому оно больше не
            // отображается только тогда, когда «уже подключено»:

            // Когда никто не в паре, это вход пользователя.
            btnPickTarget.visibility = View.VISIBLE
        } else {
            btnPickTarget.visibility = View.GONE
        }
        val projectionReady =
            CarPlayService.mediaProjection != null
        if (projectionReady) {
            btnAudio.text = "Авторизованный"
            btnAudio.setTextColor(getColor(R.color.m3_ok))
            audioDesc.text = "Разрешение на трансляцию экрана готово. Включается дополнительно при подключении."
        } else {
            btnAudio.text = "Разрешить"
            btnAudio.setTextColor(getColor(R.color.m3_primary))
            audioDesc.text = "Требуется разрешение «Скринкаст», необязательно · В закрытом состоянии из автомобиля не слышно звука."
        }
        readyCount.text = "$ready/4"
    }

    /**
     * Строка статуса Шизуку в «Подготовке к подключению»: Статус является источником факта (читается один раз для каждого сверки).
     * Когда он не готов, щелкните всю строку, чтобы сразу открыть всплывающее окно с инструкциями — это та же копия,
     * которую вы видите, когда нажимаете «Начать подключение» и блокируетесь.
     */
    private fun updateShizukuRow(state: ShizukuGate.State) {
        val ready = state == ShizukuGate.State.READY
        shizukuBadge.background.mutate().setTint(
            getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
        )
        shizukuBadge.setTextColor(getColor(if (ready) R.color.m3_ok else R.color.m3_on_sec_container))
        shizukuBadge.text = if (ready) "Готово" else "Не готов"
        shizukuDesc.text =
            when (state) {
                ShizukuGate.State.READY -> "Разрешения оболочки готовы · Доступны виртуальный экран и сенсорный ввод."
                ShizukuGate.State.NOT_RUNNING -> "Служба не запущена — нажмите здесь, чтобы узнать, как ее запустить."
                ShizukuGate.State.UNAUTHORIZED -> "Это приложение не авторизовано — нажмите здесь, чтобы запросить авторизацию."
                ShizukuGate.State.NOT_INSTALLED -> "Shizuku не установлена — нажмите здесь, чтобы получить инструкции."
            }
    }

    // ----Всплывающее окно выбора целевого транспортного средства и машины (состояние сканирования/сопряжения)----

    /** Открытое в данный момент всплывающее окно (к нему необходимо добавить обратные вызовы сканирования; при закрытии оно завершится единообразно). */
    private var targetPicker: androidx.appcompat.app.AlertDialog? = null

    private var targetPickerReceiver: BroadcastReceiver? = null

    /** Результаты сканирования отображаются во всплывающем окне (удалить дубликаты по адресу, парные заносятся в верхнюю группу отдельно, а не сюда). */
    private val scannedTargets = LinkedHashMap<String, TargetPickerEntry>()

    /** Адрес устройства, которое нажало «Сопряжение» во время сканирования (автоматически выбирается в качестве целевого после завершения сопряжения). */
    private var pendingBondAddress: String? = null

    private data class TargetPickerEntry(
        val address: String,
        val name: String,
        val rssi: Int,
        val bonded: Boolean,
    )

    /** Установить после того, как разрешение на сканирование отклонено: продолжить обратный вызов авторизации (всплывающее окно могло быть закрыто, поэтому игнорируйте его). */
    private var pendingTargetScan = false

    private val targetScanPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BT-SCAN: BLUETOOTH_SCAN Лицензия: $granted")
            if (granted && pendingTargetScan) {
                pendingTargetScan = false
                targetPicker?.let { startTargetScan(it) }
            } else if (!granted) {
                pendingTargetScan = false
                targetPicker?.let { _ ->
                    updatePickerStatus(
                        "Разрешение «Устройства поблизости» не предоставлено — невозможно сканировать; вы " +
                            "можете зайти в настройки системы для авторизации, а затем снова открыть это всплывающее окно",
                        warn = true,
                    )
                    pickerToast("Разрешение «Устройства поблизости» не предоставлено — невозможно сканировать устройства поблизости.")
                }
            }
        }

    /**
     * Всплывающее окно выбора целевого транспортного средства и машины (изменено 18 октября 2026 г.):
     * Группа 1 «Сопряжено» — выбрано напрямую (передняя часть беспроводной системы CarPlay автомобиля представляет
     * собой сопряжение Bluetooth на системном уровне);
     * Группа 2 «Ближайшие устройства» — сканирование в реальном времени (классическое обнаружение Bluetooth),
     * нажмите на несопряженное устройство, чтобы начать
     * сопряжен с системой и автоматически устанавливается в качестве цели после успешного сопряжения.
     *
     * Первая строка       «automatic (unspecified)» сохраняет исходную семантику: исходящие попытки выполняются одна
     * за другой, а входящие записи не отклоняются;
     * После выбора       исходящие соединения будут подключаться только к цели, а входящие соединения, не являющиеся
     * целевыми, будут отклонены (чтобы предотвратить случайное подключение к другому автомобилю дома).
     * Выбор       вступает в силу немедленно (ссылка динамически считывает цель без повторного подключения).
     */
    private fun showTargetPicker() {
        val adapter =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            snack("Bluetooth не включен. Сначала включите Bluetooth.")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_target_picker, null)
        val dialog =
            androidx.appcompat.app.AlertDialog
                .Builder(this)
                .setView(view)
                .create()
        targetPicker = dialog
        scannedTargets.clear()
        pendingBondAddress = null
        view.findViewById<TextView>(R.id.btnCancelPicker).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnRescan).setOnClickListener { startTargetScan(dialog) }
        renderBondedGroup(view)
        dialog.setOnDismissListener {
            stopTargetScan()
            targetPicker = null
            pendingBondAddress = null
            updateReadyRow()
        }
        dialog.setOnShowListener {
            // Панель имеет закругленные углы: цвет фона окна должен быть прозрачным, иначе четыре угла будут видны.
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * PanelWindow.WIDTH_FRACTION).toInt()
            dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        startTargetScan(dialog)
    }

    /** Рендеринг «парной» группы: автоматический ряд + каждое сопряженное устройство. */
    private fun renderBondedGroup(view: View) {
        val list = view.findViewById<LinearLayout>(R.id.bondedList)
        val empty = view.findViewById<TextView>(R.id.bondedEmpty)
        list.removeAllViews()
        val current = TargetDevicePrefs.get(this)
        // «Авто (не указано)» — это режим, а не устройство: размещается отдельно поверх двух групп, а не смешивается с «парной» группой.
        view.findViewById<LinearLayout>(R.id.autoRowContainer).apply {
            removeAllViews()
            addView(
                inflateTargetRow(
                    name = "Автоматически (цель не указана)",
                    address = "Попробуйте их один за другим · Принимайте соединения с любого устройства",
                    action = if (current == null) "текущий" else "Выбрать",
                    highlighted = current == null,
                    iconTint = R.color.m3_outline,
                ) {
                    TargetDevicePrefs.set(this@MainActivity, null)
                    log(
                        "ЦЕЛЬ: таргетинг отменен: исходящие попытки соединяют устройства одно за другим, входящие " +
                            "принимаются любые устройства.",
                    )
                    // Сначала закройте всплывающее окно, а затем запрос (панель снэк-бара находится в окне «Активность» и будет
                    // закрыта всплывающим окном)

                    targetPicker?.dismiss()
                    snack("Переключен на автоматический режим (цель не указана)")
                },
            )
        }
        val bonded = bondedDevices()
        empty.visibility = if (bonded.isEmpty()) View.VISIBLE else View.GONE
        bonded.forEach { device ->
            val address = device.address
            val name = deviceDisplayName(device)
            val isCurrent = current != null && TargetDevicePrefs.matches(current, address)
            list.addView(
                inflateTargetRow(
                    name = name,
                    address = address,
                    action = if (isCurrent) "текущий" else "Выбрать",
                    highlighted = isCurrent,
                    iconTint = R.color.m3_primary,
                ) {
                    setTarget(device, name, closePicker = true)
                },
            )
        }
    }

    /** Одна строка устройства (общая для обеих групп; при выделении имя использует основной цвет). */
    private fun inflateTargetRow(
        name: String,
        address: String,
        action: String,
        highlighted: Boolean,
        iconTint: Int,
        onClick: () -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.item_target_device, null)
        row.background =
            ContextCompat.getDrawable(
                this@MainActivity,
                if (highlighted) R.drawable.bg_bt_device_row_current else R.drawable.bg_bt_device_row,
            )
        row.findViewById<TextView>(R.id.devName).apply {
            text = name
            setTextColor(getColor(if (highlighted) R.color.m3_primary else R.color.m3_on_surface))
        }
        row.findViewById<TextView>(R.id.devAddr).text = address
        row.findViewById<TextView>(R.id.devAction).apply {
            // «Текущий» = сплошная зеленая капсула + галочка + темный текст; «Выбрать/Пара» = контурная капсула + голубой текст.
            // Одного оттенка недостаточно для различения (голубой #7EDCE6 и зеленый #7BD8A5 трудно отличить при шрифтах
            // маленького размера), поэтому вместо этого используйте морфологические различия.

            if (highlighted) {
                text = "✓ $action"
                setTextColor(getColor(R.color.m3_surface))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_current)
            } else {
                text = action
                setTextColor(getColor(R.color.m3_primary))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tbtn)
            }
            // После переключения сплошного/штрихового заполнения заполнение определяется возможностью рисования и не требует сброса.
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        }
        row.findViewById<ImageView>(R.id.devIcon).setColorFilter(getColor(iconTint))
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * Запустите сканирование устройств поблизости: startDiscovery после регистрации ACTION_FOUND/ACTION_BOND_STATE_CHANGED.
     * Найденные результаты дедуплицируются по адресам, а сопряженные устройства (они входят в предыдущую группу)
     * исключаются; нажмите «Пара», чтобы вызвать createBond().
     */
    private fun startTargetScan(dialog: androidx.appcompat.app.AlertDialog) {
        val adapter =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
            } catch (_: Exception) {
                null
            } ?: return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingTargetScan = true
            updatePickerStatus("Для сканирования требуется разрешение «Ближайшие устройства»...", warn = true)
            targetScanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
            return
        }
        val scannedList = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // Установлен парный адрес: удален из результатов сканирования (чтобы одно и то же устройство не появлялось в двух местах)
        val bondedAddresses =
            bondedDevices()
                .map { it.address }
                .toSet()
        scannedTargets.clear()
        scannedList.removeAllViews()
        updatePickerStatus("Сканирование устройств Bluetooth рядом…")
        stopTargetScan()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                    ?: return
                            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                            onTargetFound(dialog, device, rssi, bondedAddresses)
                        }

                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                    ?: return
                            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                            onBondStateChanged(dialog, device, state)
                        }
                    }
                }
            }
        targetPickerReceiver = receiver
        val filter =
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
        // Трансляции, отправляемые системой (стек Bluetooth): для их получения на некоторых ПЗУ необходимо использовать EXPORTED.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val started =
            try {
                adapter.startDiscovery()
            } catch (_: SecurityException) {
                false
            }
        if (!started) {
            updatePickerStatus(
                "Не удалось начать сканирование. Подтвердите, что Bluetooth включен, и нажмите «Повторное " +
                    "сканирование».",
                warn = true,
            )
        } else {
            log("BT-SCAN: начать сканирование ближайших устройств Bluetooth.")
        }
    }

    /** Устройство сканируется: сопряжено → игнорировать; новое устройство → добавить строку (нажмите «Сопряжение», чтобы начать сопряжение системы). */
    private fun onTargetFound(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        rssi: Int,
        bondedAddresses: Set<String>,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address in bondedAddresses || scannedTargets.containsKey(address)) return
        val name = deviceDisplayName(device)
        scannedTargets[address] = TargetPickerEntry(address, name, rssi, bonded = false)
        val list = dialog.findViewById<LinearLayout>(R.id.scannedList) ?: return
        // Сканирование продолжается: строка состояния сохраняется и подсчитывается, и пользователь знает, что может
        // продолжать ждать других устройств.

        // Намеренно не оставляйте updatePickerStatus — он прокрутит список до строки состояния и захватит позицию
        // прокрутки при обнаружении нового устройства.

        dialog.findViewById<TextView>(R.id.scanStatus)?.apply {
            text = "Сканирование продолжается...${scannedTargets.size} найдены несопряженные устройства"
            setTextColor(getColor(R.color.m3_mono_dim))
            visibility = View.VISIBLE
        }
        list.addView(
            inflateTargetRow(
                name = name,
                address = "$address · ${rssi}dBm",
                action = "спаривание",
                highlighted = false,
                iconTint = R.color.m3_amber,
            ) {
                beginBond(device)
            },
        )
    }

    /** Нажмите на несопряженное устройство: сначала остановите сканирование (при сканировании может произойти сбой сопряжения), а затем создайтеBond(). */
    private fun beginBond(device: BluetoothDevice) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        stopDiscoveryOnly()
        pendingBondAddress = address
        val name = deviceDisplayName(device)
        val ok =
            try {
                device.createBond()
            } catch (error: SecurityException) {
                log("BT-PAIR: Не удалось выполнить сопряжение «$name» — отсутствуют разрешения: ${error.message}")
                false
            }
        if (ok) {
            log("BT-PAIR: Установлено соединение с «$name» ($address) — подтвердите во всплывающем окне системы.")
            pickerNotice("Сопряжение «$name»: подтвердите во всплывающем окне системы.")
        } else {
            pendingBondAddress = null
            log("BT-PAIR: Не удалось инициировать сопряжение «$name» ($address) (возможно, устройство сопряжено или Bluetooth занят)")
            pickerNotice("Невозможно инициировать сопряжение — выполните сопряжение вручную в настройках Bluetooth системы.")
        }
    }

    /**
     * Запрос при открытии всплывающего окна.
     *
     * Всплывающее окно       является **независимым окном** (более высокий уровень + полноэкранная маска), и снэкбар
     * в действии должен быть закрыт им.
     * — пользователи видят только затемненный цветной блок внизу (отзыв от 18 октября 2026 г.). Системный Toast
     * принадлежит более высокому слою окна.
     * может плавать над всплывающим окном; при этом строка состояния во всплывающем окне обновляется (прокручивается
     * до видимого положения) как долговременное видимое описание.
     */
    private fun pickerNotice(message: String) {
        pickerToast(message)
        updatePickerStatus(message, warn = true)
    }

    /**
     * Системное всплывающее сообщение при открытии всплывающего окна. Системное Toast принадлежит более высокому
     * слою окна и может плавать во всплывающем окне;
     * Снэк-бар в       Activity будет полностью закрыт всплывающим окном (независимое окно + маска).
     */
    private fun pickerToast(message: String) {
        android.widget.Toast
            .makeText(this, message, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /** Обновите строку состояния во всплывающем окне и прокрутите до видимого положения (`scanStatus` под заголовком «Устройства поблизости»). */
    private fun updatePickerStatus(
        message: String,
        warn: Boolean = false,
    ) {
        val dialog = targetPicker ?: return
        val status = dialog.findViewById<TextView>(R.id.scanStatus) ?: return
        status.text = message
        status.setTextColor(getColor(if (warn) R.color.m3_amber else R.color.m3_mono_dim))
        status.visibility = View.VISIBLE
        // Строка состояния не прокручивается, пока не выйдет за пределы области просмотра (пользователь не должен терять
        // позицию при просмотре парного списка выше).

        status.post {
            val scroll = dialog.findViewById<android.widget.ScrollView>(R.id.pickerScroll) ?: return@post
            val content = scroll.getChildAt(0) ?: return@post
            val top = offsetWithin(status, content)
            val bottom = top + status.height
            val visibleTop = scroll.scrollY
            val visibleBottom = visibleTop + scroll.height
            if (top < visibleTop || bottom > visibleBottom) {
                val y = top - (12 * resources.displayMetrics.density).toInt()
                scroll.smoothScrollTo(0, y.coerceAtLeast(0))
            }
        }
    }

    /** Смещение содержимого представления относительно предка (накапливается вдоль родительской цепочки вверх; предок должен быть его предком). */
    private fun offsetWithin(
        view: View,
        ancestor: View,
    ): Int {
        var current: View? = view
        var y = 0
        while (current != null && current !== ancestor) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    /** Изменение статуса сопряжения: установить в качестве целевого и в случае успеха закрыть всплывающее окно; дайте подсказку, если не удалось. */
    private fun onBondStateChanged(
        dialog: androidx.appcompat.app.AlertDialog,
        device: BluetoothDevice,
        state: Int,
    ) {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return
            }
        if (address != pendingBondAddress) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                pendingBondAddress = null
                val name = deviceDisplayName(device)
                log("BT-PAIR: ✓ В сочетании с «$name» ($address) — автоматически устанавливается в качестве цели.")
                // Сначала закройте всплывающее окно, а затем подскажите: панель закусок находится в окне «Активность» и будет
                // закрыта, если всплывающее окно не закрыто.

                dialog.dismiss()
                setTarget(device, name)
                // «Только сопряжение, а не подключение»: система/автомобиль часто автоматически создает HFP/A2DP после
                // завершения сопряжения.

                // Подключено, и Audi фактически проверила, что это «подключенное» состояние приведет к тому, что автомобиль
                // отклонит CarPlay. немедленно с

                // Каждое отключение происходит один раз через 1,5 секунды (между этими двумя моментами времени обычно происходит
                // боковое соединение автомобиля и двигателя).

                val app = applicationContext
                Bg.run("bt-pair-only") {
                    repeat(2) { attempt ->
                        val dropped = BtProfiles.disconnect(app, device)
                        if (dropped.isNotEmpty()) {
                            log(
                                "BT-PAIR: ${dropped.joinToString("/")} подключить" +
                                    "(Продолжайте сопряжение только для того, чтобы автомобиль не отклонил CarPlay)",
                            )
                        }
                        if (attempt == 0) {
                            try {
                                Thread.sleep(1_500)
                            } catch (_: InterruptedException) {
                                return@run
                            }
                        }
                    }
                }
            }

            BluetoothDevice.BOND_NONE -> {
                pendingBondAddress = null
                log("BT-PAIR: сопряжение с $address не завершено (отменено пользователем или не удалось)")
                // Всплывающее окно по-прежнему открыто: используйте Toast (плавающее поверх всплывающего окна) + строку
                // состояния во всплывающем окне без скрытой панели закусок.

                pickerNotice(
                    "Сопряжение не завершено. Вы можете нажать «Сопряжение», чтобы повторить попытку, или " +
                        "перейти к настройкам Bluetooth системы, чтобы выполнить сопряжение вручную.",
                )
            }
            // BOND_BONDING: Продолжать ждать (окно подтверждения системы не ответило)
        }
    }

    /**
     * установлен в качестве исходящего пункта назначения; текущее соединение включается при следующей
     * попытке/следующем входящем соединении.
     *
     * @param closePicker true = Сначала закройте всплывающее окно выбора устройства, а затем запрос (панель снэков
     * принадлежит окну активности,
     * Всплывающие окна должны быть закрыты при открытии — см. аннотацию PickerNotice).
     */
    private fun setTarget(
        device: BluetoothDevice,
        name: String,
        closePicker: Boolean = false,
    ) {
        if (closePicker) targetPicker?.dismiss()
        val previous = TargetDevicePrefs.get(this)
        TargetDevicePrefs.set(this, TargetDevicePrefs.Target(device.address, name))
        log(
            "ЦЕЛЬ: Целевому транспортному средству присвоено значение «$name» (${device.address})." +
                "—— Подключайте это устройство только для исходящего трафика и отклоняйте входящие подключения других устройств." +
                if (previous != null && previous.address != device.address) {
                    "(Предыдущая цель: \"${previous.name}\")"
                } else {
                    ""
                },
        )
        snack("В качестве цели установлено «$name» — отправлять запросы только на это устройство.")
        updateReadyRow()
    }

    /** Остановить сканирование и выйти из системы мониторинга (вызывается при закрытии/повторном сканировании всплывающего окна). */
    private fun stopTargetScan() {
        stopDiscoveryOnly()
        targetPickerReceiver?.let {
            runCatching { unregisterReceiver(it) }
            targetPickerReceiver = null
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun stopDiscoveryOnly() {
        // Все точки вызова подтвердили, что BLUETOOTH_SCAN предоставлен (запись процесса сканирования), и исключение
        // фиксируется здесь в целом.

        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.cancelDiscovery()
        } catch (_: Exception) {
        }
    }

    private fun bondedDevices(): List<BluetoothDevice> =
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                .adapter
                ?.bondedDevices
                ?.toList()
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

    /** Отображаемое имя устройства (возврат к адресу, если имени нет); BLUETOOTH_CONNECT необходим для чтения имени, а адрес используется в случае исключения. */
    private fun deviceDisplayName(device: BluetoothDevice): String =
        try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address
        } catch (_: SecurityException) {
            try {
                device.address
            } catch (_: SecurityException) {
                "неизвестное устройство"
            }
        }

    /** Маркетинговое название оборудования (ro.product.marketname), возвращает Build.MODEL, если оно недоступно. */
    private fun deviceName(): String =
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java)
            (get.invoke(null, "ro.product.marketname") as? String)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        } catch (_: Exception) {
            Build.MODEL
        }

    private fun updateShizukuChip() {
        val ready =
            ShizukuGate
                .state(this) ==
                ShizukuGate.State.READY
        shizukuChip.text = if (ready) "Служба автомобиля доступна" else "Шизуку не готова"
        shizukuChip.compoundDrawables.getOrNull(0)?.let {
            DrawableCompat.setTint(
                it.mutate(),
                getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
            )
        }
        // Подзаголовок соответствует прототипу: название устройства · Статус Шизуку (цвет точки зависит от статуса)
        deviceSubtitle.text = "${deviceName()} · ${if (ready) "Shizuku готов" else "Shizuku Не готов"}"
        deviceSubtitle.compoundDrawables.getOrNull(0)?.let {
            DrawableCompat.setTint(
                it.mutate(),
                getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
            )
        }
    }

    // ==================== Общие инструменты пользовательского интерфейса ===================

    private fun log(message: String) {
        // Запись в буфер уровня процесса (logcat + файл + рендеринг экрана обрабатываются SessionController)
        SessionController.appendLog(message)
    }

    /** Запрос/выполнение оболочки (вызывается только фоновым потоком; используется обеими диагностическими панелями, нет необходимости отдельно писать локальные функции). */
    private fun shExec(
        cmd: String,
        timeoutMs: Long = 4_000L,
    ): Shell.Result =
        Shell
            .exec(cmd, timeoutMs)

    private fun sh(cmd: String): String = shExec(cmd).output

    /**
     * экспортирует журнал в «Download/CarDeck/», и во всплывающем окне будет указано местоположение.
     *
     * По умолчанию       импортирует логи только после запуска этого процесса (исторические раунды содержат много
     * шума для устранения неполадок, часто составляют сотни КБ);
     * Нажмите и удерживайте кнопку экспорта, чтобы экспортировать всю историю ([LogExporter.Scope.ALL]).
     * Почему вместо закуски необходимо всплывающее окно: закуска плавает внизу страницы только в течение 2,4 секунды
     * после того, как пользователь нажимает кнопку «Экспорт»
     * часто смотрит на область логов и вообще не замечает этого; и должна быть четкая обратная связь в случае сбоя
     * (запись файлов может быть связана с
     * Память       заполнена/нештатный сбой библиотеки мультимедиа).
     */
    private fun exportLogFile(scope: LogExporter.Scope = LogExporter.Scope.CURRENT_SESSION) {
        snack(if (scope == LogExporter.Scope.ALL) "Экспорт всех журналов..." else "Экспорт журналов этого запуска...")
        val fallback = SessionController.logText()
        Bg.run("export-log") {
            val result = LogExporter.export(applicationContext, fallback, scope)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    snack("Не удалось выполнить экспорт. Проверьте место для хранения и повторите попытку.", 4_000L)
                    return@runOnUiThread
                }
                log("Экспортирован журнал (${scope.label}): ${result.displayPath} (${result.bytes} байт)")
                val scopeNote =
                    if (scope == LogExporter.Scope.CURRENT_SESSION) {
                        "Включаются только журналы после запуска этого приложения. Для более старой истории нажмите и " +
                            "удерживайте кнопку экспорта."
                    } else {
                        "Содержит исторические журналы (предел файла составляет 2 МБ, в случае превышения будет сохранен " +
                            "только самый последний сегмент)."
                    }
                DarkMessageDialog.show(
                    this,
                    title = "Журнал экспортирован.",
                    message =
                        "Местоположение файла:\n${result.displayPath}\n\n" +
                            "Размер: ${formatBytes(result.bytes)}\n$scopeNote\n\n" +
                            "Его можно найти в «Управление файлами → Загрузка → CarDeck», или им можно поделиться " +
                            "напрямую с разработчиками для устранения проблем.",
                    actions =
                        listOf(
                            DarkMessageDialog.Action("открыть файл", DarkMessageDialog.Emphasis.PRIMARY) {
                                openExportedFile(result.uri, result.displayPath)
                            },
                            DarkMessageDialog.Action("открыть папку") {
                                openExportFolder(result.displayPath)
                            },
                            DarkMessageDialog.Action("обмениваться файлами") {
                                shareExportedFile(result.uri)
                            },
                            DarkMessageDialog.Action("Выключено", onClick = {}),
                        ),
                )
            }
        }
    }

    /** Удобочитаемый размер: <1 КБ отображаемых байт (при целочисленном делении будет отображаться вводящее в заблуждение значение «0 КБ»). */
    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes байт"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }

    /**
     * Откройте системный файловый менеджер и перейдите в каталог экспорта (URI документа DocumentsUI может напрямую
     * обращаться к подкаталогу).
     *
     * Почему **явные компоненты** имеют приоритет: неявный каталог ACTION_VIEW + в Honor ROM будет разрешен
     * Случайное совпадение       (фактически измерено 18 октября 2026 г., появился элемент по умолчанию «NetEase
     * Cloud Music»); используется то же намерение
     * `am start` корректно попадает в DocumentsUI. Явно укажите FilesActivity, чтобы пропустить преобразователь,
     * шаг за шагом откатится в случае сбоя (другие имена компонентов ПЗУ могут быть другими → неявные → корень
     * внешнего хранилища → текстовые инструкции).
     */
    private fun openExportFolder(displayPath: String) {
        val folder = displayPath.substringBeforeLast('/')
        val relative = folder.removePrefix("/storage/emulated/0/")
        val dirUri =
            Uri.parse(
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3A" + Uri.encode(relative).replace("/", "%2F"),
            )
        val explicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                component =
                    android.content.ComponentName(
                        "com.android.documentsui",
                        "com.android.documentsui.files.FilesActivity",
                    )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val implicit =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/directory",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        for (intent in listOf(explicit, implicit, storageRoot)) {
            if (runCatching { startActivity(intent) }.isSuccess) {
                if (intent !== explicit) snack("Пожалуйста, откройте по пути: $folder", 5_000L)
                return
            }
        }
        snack("Для просмотра перейдите в «Управление файлами → Загрузка → CarDeck».", 5_000L)
    }

    /** Откройте с помощью системного приложения по умолчанию (большинство ПЗУ доступны для предварительного просмотра файлового менеджера/просмотра текста). */
    private fun openExportedFile(
        uri: Uri,
        displayPath: String,
    ) {
        val view =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(view) }.isSuccess) return
        // Возврат в «Диспетчер файлов» при отсутствии средства просмотра текста: корень внутреннего хранилища
        // DocumentsUI (пользователь вводит Downloads/CarDeck в соответствии с путем)

        val storageRoot =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/root",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (runCatching { startActivity(storageRoot) }.isSuccess) {
            snack("Пожалуйста, откройте по пути: $displayPath.", 5_000L)
            return
        }
        snack(
            "Не существует приложения, которое могло бы открыть файл. Чтобы найти его, перейдите в файловый менеджер и " +
                "следуйте указанному выше пути.",
            5_000L,
        )
    }

    /** Делитесь файлами журналов (WeChat/QQ/электронная почта и т. д.), URI контента имеет временное разрешение на чтение. */
    private fun shareExportedFile(uri: Uri) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        runCatching { startActivity(Intent.createChooser(send, "Делитесь файлами журналов")) }
            .onFailure { snack("Нет приложений, которыми можно поделиться") }
    }

    private fun updateLogBottomButton() {
        findViewById<ImageView>(R.id.btnLogBottom)?.visibility =
            if (consoleAtBottom()) View.INVISIBLE else View.VISIBLE
    }

    /** Максимальное смещение прокрутки содержимого журнала. */
    private fun consoleMaxScroll(): Int {
        val layout = logText.layout ?: return 0
        return (
            layout.getLineBottom(layout.lineCount - 1) +
                logText.paddingBottom - logText.height
        ).coerceAtLeast(0)
    }

    /** Находится ли он уже внизу (допуск 40 пикселей: не допускайте округления высоты строки, чтобы никогда не было возможности определить нижнюю часть на несколько пикселей). */
    private fun consoleAtBottom(): Boolean = consoleMaxScroll() - logText.scrollY <= 40

    private fun scrollConsoleToBottom() {
        // Вылет +16 пикселей для компенсации разницы в округлении (консоль имеет темный фон, и вылет не виден; вид не
        // ограничен, но визуально нечувствителен)

        logText.scrollTo(0, consoleMaxScroll() + 16)
        logText.post { updateLogBottomButton() }
    }

    private fun renderConsole(text: String) {
        // Текст уже был внизу перед изменением (или пользователь прокручивает вверх и читает): решите, следует ли на
        // этот раз автоматически следовать внизу.

        val follow = consoleAtBottom()
        logText.text =
            if (SessionController.logEnabled) {
                text
            } else {
                "Сбор журналов отключен (для восстановления включите " +
                    "переключатель «Журнал мониторинга»)"
            }
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    /**
     * Постепенно добавить строку журнала (SessionController отправляет ее напрямую построчно; ранее каждая строка
     * полностью добавлялась в String + setText
     * Весь текст, двойное усиление UI/GC под высокочастотные логи). При превышении лимита весь буфер процесса будет
     * согласован для немедленного завершения обрезки.
     */
    private fun appendConsole(line: String) {
        val follow = consoleAtBottom()
        if (logText.text.length > 44_000) {
            renderConsole(SessionController.logText())
            return
        }
        logText.append(line)
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    private var snackRunnable: Runnable? = null

    /** Самоокрашенная закусочная M3 (капсула обратного цвета внизу, заменяющая тост). */
    private fun snack(
        message: String,
        durationMs: Long = 2_400L,
    ) {
        snackbar.text = message
        snackbar.visibility = View.VISIBLE
        snackbar.translationY = snackbar.height + 60f
        snackbar
            .animate()
            .translationY(0f)
            .setDuration(260L)
            .start()
        snackRunnable?.let { mainHandler.removeCallbacks(it) }
        snackRunnable =
            Runnable {
                snackbar
                    .animate()
                    .translationY(snackbar.height + 60f)
                    .setDuration(260L)
                    .withEndAction { snackbar.visibility = View.GONE }
                    .start()
            }.also { mainHandler.postDelayed(it, durationMs) }
    }

    /**
     * Анализ виртуального экрана CarPlay в dumpsys display: return (displayId, name).
     * Обратите внимание, что uniqueId имеет вид virtual:com.android.shell,2000,CarPlayDock,36 — мантисса равна 36.
     * Счетчик генерации       вместо идентификатора дисплея; настоящий идентификатор находится в разделе «displayId=39, uniqueId='…'».
     */
    private fun parseCarPlayDisplays(dump: String): List<Pair<Int, String>> =
        Regex("displayId=(\\d+), uniqueId='[^']*CarPlay(Dock|AppHi|App|Map)")
            .findAll(dump)
            .map { it.groupValues[1].toInt() to "CarPlay${it.groupValues[2]}" }
            .distinctBy { it.first }
            .toList()

    /** Чтение глобального состояния оболочки (фоновый поток + заполнение пользовательского интерфейса). [startupCheck] случайно выполняет обнаружение остаточного виртуального экрана. */
    private fun refreshShellState(startupCheck: Boolean = false) {
        if (startupCheck) {
            startupCheckRunning = true
            tickHero()
        }
        shellStateText.text = "Чтение…"
        Bg.run("shell-state") {
            if (ShizukuGate
                    .state(this) !=
                ShizukuGate.State.READY
            ) {
                runOnUiThread {
                    startupCheckRunning = false
                    if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                    shellStateText.text = "Шизуку не готова — для чтения статуса Shell требуется Шизуку"
                }
                return@run
            }

            val letterbox =
                Regex("Aspect ratio:\\s*([0-9.]+)")
                    .find(sh("cmd window get-letterbox-style"))
                    ?.groupValues
                    ?.get(1) ?: "?"
            val forceResizable = sh("settings get global force_resizable_activities").ifBlank { "null" }
            val overlay = sh("settings get global overlay_display_devices").ifBlank { "null" }
            val wmSize = sh("wm size").replace("\n", " / ").ifBlank { "?" }
            val wmDensity = sh("wm density").replace("\n", " / ").ifBlank { "?" }
            val carDisplays = parseCarPlayDisplays(sh("dumpsys display"))
            val displays = carDisplays.joinToString(", ") { "${it.second}(${it.first})" }
            val timeout = sh("settings get system screen_off_timeout").ifBlank { "?" }
            val text =
                buildString {
                    appendLine(
                        "Соотношение сторон почтового ящика: $letterbox (1,05 = настройка сеанса вступает в силу; " +
                            "0,0 = системное значение по умолчанию)",
                    )
                    appendLine("Force_resizable: $forceResizable (экспериментальный режим планшета; по умолчанию ноль/0)")
                    appendLine("overlay_display_devices: $overlay (остатки эксперимента с наложением экрана; должно быть нулевым)")
                    appendLine("wm size: $wmSize")
                    appendLine("wm density: $wmDensity")
                    appendLine("Виртуальный экран CarPlay:" + displays.trim().replace("\n", ", ").ifEmpty { "Нет" })
                    appendLine("screen_off_timeout: $timeout (управление постоянно включенными функциями приложения)")
                }
            runOnUiThread { shellStateText.text = text.trim() }
            if (startupCheck) {
                var residual = carDisplays.filter { it.first !in currentSessionDisplayIds() }
                if (residual.isNotEmpty()) {
                    log(
                        "SHELL: при загрузке обнаружены ${residual.size} остаточные виртуальные экраны:" +
                            residual.joinToString(", ") { "${it.second}(${it.first})" },
                    )
                    // Остаточный экран = последний сеанс завершился аварийно (процесс был прекращен): сеанс, покрытый горизонтальным
                    // экраном, не восстанавливается

                    // Тоже не делал - почистите вместе, чтобы приложение не оставалось в состоянии горизонтального экрана со стороны
                    // телефона

                    runCatching {
                        val reset =
                            AppOrientationCompat
                                .resetAll(applicationContext)
                        if (reset > 0) log("ОРИЕНТАЦИЯ: восстановлено горизонтальное наложение для остаточных сеансов $reset")
                    }
                    // Во-первых, автоматически очистите один раунд (включая старую версию перезапуска процесса UserService — процесс
                    // охвата экрана, который он содержит).

                    // Невозможно выпустить, выпустить может только процесс переработки; после переустановки APK Шизуку повторно
                    // использует его в соответствии с версией

                    // Старый процесс является основной причиной «выпуска очистки точки 0/N», 16 октября 2026 г.). Если его
                    // невозможно очистить, обратитесь к пользователю.

                    val released = releaseDisplays(residual.map { it.first })
                    log("SHELL: запустить автоматическую остаточную очистку (выпустить $released/${residual.size})")
                    if (released > 0) {
                        residual =
                            parseCarPlayDisplays(sh("dumpsys display"))
                                .filter { it.first !in currentSessionDisplayIds() }
                    }
                }
                runOnUiThread {
                    startupCheckRunning = false
                    if (residual.isNotEmpty()) {
                        // Он остается в ожидании, когда пользователь «еще нет», и кнопка рядом со статусом может снова начать очистку.
                        pendingResidualDisplays = residual
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        promptResidualDisplays(residual)
                        // Автоматически разворачивать карточку журнала: две записи, с которых началась очистка, видны напрямую (по
                        // умолчанию карточка свернута).

                        findViewById<View>(R.id.logBody).visibility = View.VISIBLE
                        findViewById<ImageView>(R.id.logChevron)
                            .animate()
                            .rotation(180f)
                            .setDuration(250L)
                            .start()
                    } else if (heroState == HeroState.IDLE) {
                        // Без остатка: восстановить отображение статуса «не подключено» по умолчанию.
                        applyHeroState(HeroState.IDLE)
                    }
                }
            }

            // Предварительная привязка UserService: разогрев сенсорного внедрения и запуск повторного использования
            // остаточного процесса старой версии (обратный вызов привязки включен

            // reapStaleUserServices — Shizuku не перезапускает старый процесс после переустановки APK и виртуальный экран,
            // который он удерживает.

            // Только процесс переработки может освободить его). **Должно быть позже, чем указанное выше остаточное
            // сканирование**: при перезапуске виртуальный экран старого процесса будет немедленно создан.

            // Исчезает, если это происходит до сканирования, остаточный список может стать пустым → resetAll (принудительное
            // восстановление ландшафта) пропускается.

            CarInputClient.ensure(this)
        }
    }

    /** Виртуальный экран, удерживаемый текущим сеансом (не участвует в остаточной очистке). */
    private fun currentSessionDisplayIds(): Set<Int> =
        buildSet {
            val launcher = VirtualLauncher
            listOf(
                launcher.dockDisplayId,
                launcher.appDisplayId,
                launcher.mapDisplayId,
                launcher.gridDisplayId,
            ).forEach { if (it >= 0) add(it) }
        }

    /** Остаточный виртуальный экран CarPlay обнаружен при запуске: спросите, нужно ли его немедленно очистить (остатки после аварийного завершения последнего сеанса). */
    private fun promptResidualDisplays(residual: List<Pair<Int, String>>) {
        if (isFinishing || isDestroyed) return
        val names = residual.joinToString("\n") { "· ${it.second}（displayId=${it.first}）" }
        DarkMessageDialog.show(
            activity = this,
            title = "Обнаружен остаточный виртуальный экран",
            message =
                "Найдены виртуальные экраны ${residual.size} CarPlay, оставшиеся с прошлого сеанса:\n$names\n\n" +
                    "Остаточный экран будет продолжать занимать ресурсы дисплея и может повлиять на следующее " +
                    "подключение. Убираться немедленно?",
            actions =
                listOf(
                    DarkMessageDialog.Action("Еще нет") {},
                    DarkMessageDialog.Action("убирать", DarkMessageDialog.Emphasis.PRIMARY) {
                        pendingResidualDisplays = null
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        Bg.run("residual-cleanup") {
                            val released = releaseDisplays(residual.map { it.first })
                            log("SHELL: очистка остаточного виртуального экрана завершена (выпуск $released/${residual.size})")
                            runOnUiThread {
                                snack("$released/${residual.size} остаточные экраны выпущены")
                                refreshShellState()
                            }
                        }
                    },
                ),
            onCancel = {
                // Клавиша возврата/нажмите внешнюю кнопку, чтобы закрыть = пока нет: сохраните вход «Очистить остаточный экран»
                // на карте героя.

                updateResidualChip()
            },
        )
    }

    /** Кнопка «Очистить экран остатков» рядом со статусом карты героя появляется и исчезает (появляется, когда необходимо очистить остатки). */
    private fun updateResidualChip() {
        btnResidualCleanup.visibility =
            if (pendingResidualDisplays != null) View.VISIBLE else View.GONE
    }

    /** Отпустите указанный виртуальный экран (ленивая привязка CarInputClient + короткое замыкание на 3 секунды и т. д.). Вызывается фоновым потоком и возвращает номер успеха. */
    private fun releaseDisplays(ids: List<Int>): Int {
        if (ids.isEmpty()) return 0
        var svc = CarInputClient.service
        if (svc == null) {
            CarInputClient
                .ensure(this)
            val deadline = SystemClock.uptimeMillis() + 3_000L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(150)
                svc = CarInputClient.service
            }
        }
        val bound = svc ?: return 0
        ids.forEach { runCatching { bound.releaseDisplay(it) } }
        // Экраны, которые не высвобождаются напрямую, скорее всего, хранятся в старой версии процесса UserService
        // (releaseDisplay проверяет только этот процесс).

        // карта не может быть выпущена для разных процессов — известная проблема, из-за которой Shizuku не перезапускает
        // старый процесс после переустановки APK).

        // Перезапустите старый процесс и позвольте ему быть уничтоженным и освобожденным вместе с процессом, а затем
        // просмотрите его с фактическими результатами сканирования (перезапуск процесса является асинхронным).

        CarInputClient.reapStaleUserServices()
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (true) {
            val alive = parseCarPlayDisplays(sh("dumpsys display")).map { it.first }.toSet()
            val gone = ids.count { it !in alive }
            if (gone >= ids.size || SystemClock.uptimeMillis() >= deadline) return gone
            Thread.sleep(200)
        }
    }

    /** Сбросьте глобальный статус оболочки до системных значений по умолчанию одним щелчком мыши (включая освобождение остаточного виртуального экрана и сохранение экрана текущего сеанса). */
    private fun resetShellState() {
        shellStateText.text = "Сброс..."
        Bg.run("shell-reset") {
            if (ShizukuGate
                    .state(this) !=
                ShizukuGate.State.READY
            ) {
                runOnUiThread { shellStateText.text = "Шизуку не готова — для сброса оболочки требуется Шизуку" }
                return@run
            }

            fun sh(cmd: String) =
                Shell
                    .exec(cmd, 5_000L)
            val steps =
                listOf(
                    "соотношение сторон почтового ящика" to "cmd window reset-letterbox-style aspectRatio",
                    "наложение экрана наложения" to "settings delete global overlay_display_devices",
                    "force_resizable" to "settings delete global force_resizable_activities",
                    "wm size" to "wm size reset",
                    "wm density" to "wm density reset",
                )
            val results =
                steps.map { (name, cmd) ->
                    val r = sh(cmd)
                    if (r.ok) "✓ $name" else "✗ $name（${r.output.take(40)}）"
                }
            // Остаточное освобождение виртуального экрана: сохраните каждый экран текущего сеанса и освободите оставшиеся
            // экраны CarPlay* один за другим.

            val ids =
                parseCarPlayDisplays(sh("dumpsys display").output)
                    .map { it.first }
                    .filter { it !in currentSessionDisplayIds() }
                    .toList()
            val released = releaseDisplays(ids)
            val summary = results.joinToString("；") + ";Остаток освобождения экрана $released/${ids.size}"
            log("SHELL-RESET: $summary")
            runOnUiThread {
                shellStateText.text = "Результат сброса:\n" + results.joinToString("\n") +
                    "\nОстаточный виртуальный экран: выпустите $released/${ids.size} (сохраните экран текущего сеанса)\n" +
                    "Совет: настройка почтового ящика автоматически применяется повторно при следующем подключении."
                refreshShellState()
            }
        }
    }

    /** Запрос разрешения приостановленного окна: сначала появится всплывающее окно с инструкциями, а затем после подтверждения пользователя появятся системные настройки. */
    private fun promptOverlayPermission(reason: String) {
        if (android.provider.Settings.canDrawOverlays(this)) return
        DarkMessageDialog.show(
            activity = this,
            title = "Требуется разрешение «Показывать поверх других приложений».",
            message =
                "$reason\n\n" +
                    "Цель: настоящий экран, когда нет никаких операций (экран выключен, но не блокирует экран, чтобы " +
                    "предотвратить отключение сенсорного экрана автомобиля после блокировки экрана телефоном)." +
                    "Он не будет читать или блокировать другой контент приложения, и его можно немедленно восстановить, коснувшись экрана.",
            actions =
                listOf(
                    DarkMessageDialog.Action("Еще нет") {
                        log(
                            "ДИММЕР: Пользователь еще не авторизовал плавающее окно (темный экран недоступен, постоянное " +
                                "освещение все еще действует)",
                        )
                    },
                    DarkMessageDialog.Action("авторизовать", DarkMessageDialog.Emphasis.PRIMARY) {
                        try {
                            startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName"),
                                ),
                            )
                        } catch (_: Exception) {
                            log(
                                "DIMMER: невозможно открыть страницу авторизации, вручную выберите «Настройки» > " +
                                    "«Приложения» > «Специальные разрешения» > «Показать поверх других приложений».",
                            )
                        }
                    },
                ),
        )
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(heroTick)
        // Еще не принято после ротации/реконструкции: перезагрузите компьютер с исходным временем отсечки (без
        // повторного ожидания в течение 10 секунд)

        if (needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            ) &&
            activeDisclaimer?.isShowing != true
        ) {
            showDisclaimer(requireAcceptance = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DISCLAIMER_DEADLINE, disclaimerDeadlineAt)
    }

    /**
     * Ротация (объявлены configChanges, активность не перестроена): представление остается нетронутым со всем состоянием памяти.
     * должен иметь дело с двумя вещами:
     * 1. **Область контента разделена на две колонки** - высота экрана по горизонтали составляет всего ~300dp, а
     * сама карта героя занимает весь экран.
     * Остальной контент выдавливается за пределы экрана (пользователи фактически измеряли «чтобы увидеть, нужно провести пальцем»);
     * 2. **Пересчитать всплывающие окна** - Рассчитываются по ширине экрана в старом направлении (92%). Без
     * пересчета они займут только среднюю половину.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFinishing || isDestroyed) return
        applyContentLayout(landscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        PanelWindow.resizeShowing(this)
        // Всплывающее окно целевого автомобиля и машины не открывает DarkMessageDialog и пересчитывается отдельно.
        targetPicker?.window?.let {
            val width = (resources.displayMetrics.widthPixels * PanelWindow.WIDTH_FRACTION).toInt()
            it.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * Макет области содержимого : один столбец вертикального экрана (карточки героев и подробные сведения находятся
     * в одном элементе ScrollView) ⇄
     * Горизонтальный экран с двойными столбцами (карта героя зафиксирована слева, а справа прокручивается только столбец с подробностями).
     *
     * Почему карточку героя следует перемещать из ScrollView на горизонтальном экране: когда она и детали
     * принадлежат одному и тому же контейнеру прокрутки,
     * Сдвиг правого столбца приведет к удалению карты героя (фактические отзывы пользователей). После перемещения в
     * [contentArea] это фиксированный слой.
     * Прокрутка       работает только на mainScroll; содержимое справа уступает место левому отступу ScrollView.
     *
     * onCreate тоже нужно вызвать один раз - вращение не перестраивается, но **запуск в ландшафтном режиме** не сработает
     * onConfigurationChanged.
     */
    private fun applyContentLayout(landscape: Boolean) {
        val contentArea = findViewById<android.widget.FrameLayout>(R.id.contentArea)
        val scroll = findViewById<android.widget.ScrollView>(R.id.mainScroll)
        val row = findViewById<android.widget.LinearLayout>(R.id.contentRow)
        val hero = findViewById<android.widget.LinearLayout>(R.id.heroCard)
        val detail = findViewById<android.widget.LinearLayout>(R.id.detailColumn)
        val density = resources.displayMetrics.density
        val gap = (12 * density).toInt()
        // Высота экрана по горизонтали составляет всего ~300dp: область сигнала 176→70dp, заполнение 24/20→12dp,
        // Пусть «Статус + Субтитры + Начать соединение» останутся на первом экране.
        // Бюджет: верхняя панель ~60 + форма волны 70 + статус 40 + субтитры 20 + интервал 18 + кнопки 52 + отступы 24 ≈ 284dp
        val waveHeight = ((if (landscape) 70 else 176) * density).toInt()
        for (id in intArrayOf(R.id.heroWaveFrame, R.id.signalWaves)) {
            findViewById<View>(id).layoutParams =
                findViewById<View>(id).layoutParams.apply { height = waveHeight }
        }
        val padTop = ((if (landscape) 12 else 24) * density).toInt()
        val padBottom = ((if (landscape) 12 else 20) * density).toInt()
        hero.setPadding(hero.paddingStart, padTop, hero.paddingEnd, padBottom)
        // Размер кадра горизонтального сигнала составляет всего 70 dp: диск одновременно сжимается до 44 dp, оставляя
        // место для внешней дорожки и дуги.

        // (Диск 74dp заполнит рамку сигнала, и дуга будет полностью выдавлена из видимой области, обнажая скриншоты реальной машины)
        val plateSize = ((if (landscape) 44 else 74) * density).toInt()
        val carSize = ((if (landscape) 24 else 38) * density).toInt()
        findViewById<View>(R.id.hubPlate).layoutParams =
            findViewById<View>(R.id.hubPlate).layoutParams.apply {
                width = plateSize
                height = plateSize
            }
        findViewById<View>(R.id.hubCar).layoutParams =
            findViewById<View>(R.id.hubCar).layoutParams.apply {
                width = carSize
                height = carSize
            }
        // Размер горизонтального окна сигнала составляет всего 70 dp: метка CARPLAY не может быть размещена, и метка уступает место.
        // (Диск является основной визуальной частью, а слово бренда уже находится в верхнем заголовке)
        findViewById<View>(R.id.hubCaption).visibility =
            if (landscape) View.GONE else View.VISIBLE

        row.orientation = android.widget.LinearLayout.VERTICAL
        if (landscape) {
            if (hero.parent === row) {
                row.removeView(hero)
                contentArea.addView(hero)
            }
            // Ширина рассчитывается с использованием **ширины экрана минус отступы страницы** без чтения contentArea.width:
            // В момент вращения последнее по-прежнему является значением измерения в старом направлении (вертикальный экран
            // 1168 → карта героя имеет размер всего 537 пикселей,

            // Подзаголовок сжали в три строки и выставили скриншот реального телефона).
            val pagePadding =
                (16 * resources.displayMetrics.density * 2).toInt()
            val available =
                (resources.displayMetrics.widthPixels - pagePadding).coerceAtLeast(1)
            val heroWidth = (available * 0.46f).toInt().coerceAtLeast(1)
            // Высота должна быть WRAP_CONTENT: FrameLayout будет считать MATCH_PARENT/WRAP заполненным,
            // Если он заполнен, кнопка будет сдвинута к нижнему краю карточки, а подзаголовок будет сжат в три строки
            // (отображается скриншот реального устройства)

            hero.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        heroWidth,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = android.view.Gravity.START or android.view.Gravity.TOP }
            // Содержимое справа уступает ширине карты героя (включая пробелы): полоса прокрутки по-прежнему находится у
            // правого края экрана.

            scroll.setPadding(heroWidth + gap, scroll.paddingTop, scroll.paddingEnd, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        } else {
            if (hero.parent === contentArea) {
                contentArea.removeView(hero)
                row.addView(
                    hero,
                    0,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            (hero.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                topMargin = gap
                marginEnd = 0
            }
            scroll.setPadding(0, scroll.paddingTop, scroll.paddingEnd, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(heroTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Обязательно указать, что остаточное окно не должно быть уничтожено с помощью Activity (Activity будет
        // перестроено во время ротации, а onResume возобновится)

        runCatching { activeDisclaimer?.dismiss() }
        activeDisclaimer = null
        shizukuPermissionListener?.let {
            runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(it) }
        }
        shizukuPermissionListener = null
        pendingShizukuThenConnect = false
        // Мониторинг сканирования/сопряжения Bluetooth заканчивается уничтожением интерфейса: после выхода из Activity
        // он больше не нужен (это также позволяет избежать утечки широковещательной информации)

        runCatching { stopTargetScan() }
        targetPicker = null
        // Только отмените привязку «зависания экрана» и оставьте сеанс без изменений — соединение поддерживается службой
        // переднего плана, а объект удерживается SessionController;

        // Для явного отключения используйте кнопку «Отключить» (любой экземпляр может отключиться от реального конвейера).
        if (SessionController.logListener === logListener) SessionController.logListener = null
        if (SessionController.uiHintSink === uiHintSinkRef) SessionController.uiHintSink = null
        SessionController.dialogSink = null
        if (SessionController.ssidSink === ssidSink) SessionController.ssidSink = null
    }
}
