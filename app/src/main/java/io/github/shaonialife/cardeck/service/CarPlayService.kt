package io.github.shaonialife.cardeck.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.shaonialife.cardeck.MainActivity
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.audio.SilentProjectionActivity
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * внешняя служба поддержания активности: резидентно с типом linkedDevice во время установления соединения,
 * предотвращает закрытие приложения политикой энергосбережения системы/MagicOS после перехода в фоновый режим, что приводит к отключению.
 * Логика подключения   по-прежнему выполняется в потоке, производном от MainActivity — этот сервис отвечает за поддержание процесса.
 */
class CarPlayService : Service() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Поддержание активности экрана: блокировка пробуждения ЦП поддерживает реакцию потока кодирования/протокола и
     * обнаружение активности TCP;
     * Блокировка Wi-Fi с низкой задержкой предотвращает радикальное энергосбережение беспроводной сети (в противном
     * случае принимающая сторона будет считаться отключенной в течение 20 секунд, и соединение будет отключено).
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true && wifiLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock =
            power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CarPlaySender:session").apply {
                setReferenceCounted(false)
                acquire()
            }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock =
            wifi
                .createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "CarPlaySender:wifi",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Выход из автомобиля: автомобильно-машинный Bluetooth ACL отключен (доставляется startService — процесс уже
        // находится в приоритетном состоянии службы, когда служба работает.

        // На него не распространяются ограничения на фоновый запуск). Disconnect приведет к внутренней остановке Service → onDestroy.
        if (intent?.action == ACTION_AUTO_STOP_SESSION) {
            fileLog("SVC: получено сообщение «Автоматически завершается при выходе из машины» —— Закрыть разговор.")
            if (SessionController.isSessionRunning()) {
                SessionController.disconnect(applicationContext)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // Тип должен быть явно указан как linkedDevice:
        // 2. Параметр startForeground подберет все типы, объявленные в манифесте (включая mediaProjection).
        // Тип запуска mediaProjection в Android 14 требует AppOps PROJECT_MEDIA (действительно только в том случае, если
        // пользователь разрешает это) ——

        // SecurityException аварийно завершает работу сразу после перезапуска/истечения срока авторизации (фактическое
        // тестирование состоялось 9 октября 2026 г.).

        // Но **когда проекция уже есть** вы должны продолжать использовать тип mediaProjection: в противном случае на
        // этот раз startForeground

        // Тип будет удален, а Android 14 также прекратит проецирование (только что полученный автоматически подключенный
        // токен будет признан недействительным).

        val fgsTypes =
            if (mediaProjection != null) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            fgsTypes,
        )
        acquireLocks()
        // Когда получен результат авторизации (пользователь только что подтвердил скринкаст): обновите тип интерфейса, а
        // затем создайте проекцию (попробовать/поймать безопасно)

        if (pendingProjectionResult != null) {
            upgradeToProjection()
            takeProjection()
        } else if (mediaProjection != null) {
            upgradeToProjection()
        }
        // Автоматическое подключение на плате: процесс может быть запущен с помощью широковещательной рассылки ACL.
        // Создайте объект сеанса (клиент/сервер BT,

        // Разъем Wi-Fi и вещательная станция mDNS находятся в убедитьсяеSessionObjects), а затем выполните тот же
        // процесс, что и кнопку «Домой».

        // Поместите фоновый поток: обязательно читайте настройки/привязку UserService во время холодного запуска и не
        // помещайте его в основной поток службы.

        if (intent?.action == ACTION_AUTO_START_SESSION) {
            fileLog("SVC: Получить «Автоматически запускаться при посадке в машину» — начать сеанс")
            val app = applicationContext
            Bg.run("auto-session-start") {
                SessionController.ensure(app)
                // Автоматическое соединение по умолчанию имеет звук: сначала получите токен скринкаста в автоматическом режиме;
                // в случае неудачи он вернется к отправке только видео.

                SessionController.audioEnabledForSession = acquireProjectionSilently()
                SessionController.startConnection(app)
            }
        }
        return START_STICKY
    }

    /**
     * автоматически получает MediaProjection (авторизованный источник AudioPlaybackCapture) при автоматическом подключении.
     *
     * Жесткие ограничения порядка в       Android 14: **сначала** получите результат авторизации → **затем** обновите тип FGS до
     * mediaProjection → можно настроить `getMediaProjection`. Непосредственное повторное использование здесь
     * [pendingProjectionResult] + [upgradeToProjection] + [takeProjection],
     * проходит тот же путь, что и ручная авторизация (с той лишь разницей, что токен происходит от [SilentProjectionActivity]).
     *
     * @return Получать ли экземпляр; если false, он автоматически подключится и отправит только видео (без
     * всплывающего окна, без повторной попытки)
     */
    private fun acquireProjectionSilently(): Boolean {
        val grant =
            SilentProjectionActivity.request(applicationContext)
                ?: run {
                    fileLog("SVC: не удалось получить токен тихого кастинга — это автоматическое соединение передает только видео.")
                    return false
                }
        pendingProjectionResult = grant.resultCode to grant.data
        upgradeToProjection()
        takeProjection()
        val ok = mediaProjection != null
        fileLog(
            if (ok) {
                "SVC: Токен бесшумного скринкаста готов (автоматическое соединение со звуком)"
            } else {
                "SVC: токен тихой проекции недействителен (ошибка getMediaProjection)"
            },
        )
        return ok
    }

    private fun takeProjection() {
        val (code, data) = pendingProjectionResult ?: return
        pendingProjectionResult = null
        try {
            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = manager.getMediaProjection(code, data)
            // Android 14+: обратный вызов должен быть зарегистрирован перед созданием VirtualDisplay (обязательное управление ресурсами)
            projection?.registerCallback(
                object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        mediaProjection = null
                        // Диагностика ключа: потеря авторизации приведет к недоступности аудио/зеркала (ранее записывался только logcat,
                        // а шифрование HKS не было видно)

                        fileLog("SVC: MediaProjection был остановлен системой (onStop) — аудио/зеркало необходимо повторно авторизовать")
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
            mediaProjection = projection
            if (projection != null) {
                fileLog("SVC: MediaProjection готов (проекция успешно создана)")
            } else {
                fileLog("SVC: getMediaProjection возвращает значение null (срок действия авторизации истек?)")
            }
        } catch (error: Exception) {
            fileLog("SVC: ошибка getMediaProjection – ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun upgradeToProjection() {
        try {
            androidx.core.app.ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (error: Exception) {
            fileLog("SVC: не удалось обновить тип mediaProjection FGS – ${error.message}")
        }
    }

    override fun onDestroy() {
        releaseLocks()
        // Android 14+: MediaProjection привязан к типу mediaProjection переднего плана ——
        // Когда служба поддержки активности остановлена (например, пользователь нажимает «Отключить»), система также
        // прекращает проецирование.

        fileLog(
            "SVC: onDestroy (служба поддержки активности остановлена) mediaProjection=" +
                (if (mediaProjection != null) "Все еще там (будет переработано системой)" else "null"),
        )
        super.onDestroy()
    }

    /** Ключевые события, такие как жизненный цикл проекции: logcat + двойная запись файла (файл — единственный читаемый канал в среде HKS). */
    private fun fileLog(message: String) {
        android.util.Log.i("CarPlayService", message)
        DebugFileLog
            .append(this, message)
    }

    companion object {
        private const val CHANNEL_ID = "carplay_connection"
        private const val NOTIFICATION_ID = 1

        /** Автоматическое подключение при посадке в автомобиль: автомобильный список ACL Bluetooth доставляется CarBluetoothReceiver при подключении. */
        const val ACTION_AUTO_START_SESSION = "io.github.shaonialife.cardeck.action.AUTO_START"

        /** Автоматически закрывать кабину после выхода из машины: доставляется при отключении ACL (отправляется только при работающей службе, см. CarBluetoothReceiver). */
        const val ACTION_AUTO_STOP_SESSION = "io.github.shaonialife.cardeck.action.AUTO_STOP"

        /** Режим зеркала: пишет MainActivity после авторизации; виртуальный экран можно создать только после того, как сервис объявит тип FGS. */
        @Volatile
        var mediaProjection: android.media.projection.MediaProjection? = null

        /** Промежуточная обработка результатов авторизации: вызывается службой после обновления основного типа getMediaProjection (требования к заказу для Android 14+). */
        @Volatile
        var pendingProjectionResult: Pair<Int, Intent>? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarPlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarPlayService::class.java))
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "CarDeck Connect",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Оставайтесь на связи со своим автомобилем"
                    setShowBadge(false)
                },
            )
        }
        // Нажмите на уведомление → откройте основной интерфейс приложения (раньше setContentIntent отсутствовал, и при
        // нажатии не было ответа)

        val contentIntent =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("КарДек работает")
            .setContentText("Связь с автомобилем сохраняется. Нажмите на приложение, чтобы просмотреть журнал.")
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
