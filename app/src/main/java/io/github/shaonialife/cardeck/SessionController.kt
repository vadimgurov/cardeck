package io.github.shaonialife.cardeck

import android.content.Context
import android.net.Network
import android.os.SystemClock
import android.view.KeyEvent
import io.github.shaonialife.cardeck.airplay.AirPlayClient
import io.github.shaonialife.cardeck.airplay.PairVerifyClient
import io.github.shaonialife.cardeck.audio.AudioProfile
import io.github.shaonialife.cardeck.audio.AudioProfileMemory
import io.github.shaonialife.cardeck.audio.AudioSender
import io.github.shaonialife.cardeck.audio.AudioTuning
import io.github.shaonialife.cardeck.bt.BtProfiles
import io.github.shaonialife.cardeck.bt.BtSessionGate
import io.github.shaonialife.cardeck.bt.Iap2BluetoothClient
import io.github.shaonialife.cardeck.bt.Iap2BluetoothServer
import io.github.shaonialife.cardeck.bt.TargetDevicePrefs
import io.github.shaonialife.cardeck.capture.HiddenDisplaySource
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.input.ConsumerKeys
import io.github.shaonialife.cardeck.input.HidNavigation
import io.github.shaonialife.cardeck.input.TouchReports
import io.github.shaonialife.cardeck.input.TouchRouter
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.settings.AppOrientationCompat
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.net.AirPlayFeatures
import io.github.shaonialife.cardeck.net.CarPlayCtrlAdvertiser
import io.github.shaonialife.cardeck.net.CarPlayServiceBrowser
import io.github.shaonialife.cardeck.net.EndpointHosts
import io.github.shaonialife.cardeck.net.WifiCarPlayConnector
import io.github.shaonialife.cardeck.net.WifiConnectCommand
import io.github.shaonialife.cardeck.net.WifiSavedNetworks
import io.github.shaonialife.cardeck.service.CarPlayService
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.ui.ScreenDimmer
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import io.github.shaonialife.cardeck.video.VideoSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Концентратор сеансов на уровне процесса: единственный владелец **буфера журнала** и **владения конвейером сеансов**.
 *
 * Предыстория   (11 октября 2026 г. пользователь обнаружил три связанные проблемы): Конвейер проецирования
 * экрана выполняется в потоке, производном от MainActivity.
 * После того, как интерфейс перечеркнут (активность уничтожена), процесс поддерживается службой переднего плана,
 * и конвейер продолжает работать; но повторно введенное приложение создает последнюю
 * Экземпляр активности**——
 * 1. Лог старого потока записывается в UI старого экземпляра (уничтожается) → новый интерфейс "лог отключен"
 * (файл лога фактически не отключен)
 * 2. «Отключить» управляет новыми объектами новых экземпляров → Никто не держит старый конвейер и его нельзя отключить
 * 3. Повторное открытие соединения создаст второй набор объектов → риск двойной сессии.
 *
 * Этот объект повышает эти состояния до одиночных элементов уровня процесса: активность просто «зависает на
 * экране» (журнал подписки, кнопка «Вперед»),
 * Уничтожение/восстановление   на него не повлияет. Логический поток сеанса проходит через [appContext] и не
 * зависит от какого-либо действия.
 */
object SessionController {
    // ----Буферизация журнала (уровень процесса; рендеринг подписки на действия)----

    private val logLock = Any()
    private val logBuilder = StringBuilder()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Отрисовка обратного вызова текущего действия на экране (получена **Эта новая строка журнала**, включая префикс временной метки); очищается, когда Activity onDestroy. */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** Переключатель «Журнал прослушивания» (синхронизируется активностью после чтения из настроек; при выключении сбор или рендеринг не выполняются). */
    @Volatile
    var logEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /** Wi-Fi ожидает окна действий пользователя (включите переключатель WLAN / вручную присоединитесь к лимиту опроса точек доступа). */
    private const val WLAN_WAIT_TIMEOUT_MS = 120_000L

    /** Добавьте верхний предел ответа, ожидающего всплывающего окна с подтверждением (если ответ не будет получен, процесс будет прерван). */
    private const val JOIN_DIALOG_TIMEOUT_S = 90L

    /**
     * — это верхний предел ожидания пустой конечной точки (машина транспортного средства заявляет, что отправит
     * 0x4301, но он не прибыл в пределах окна).
     * Фактическое измерение Audi MMI 9310 0x4301 приходит с опозданием на 2 минуты и 35 секунд - окно поддержки
     * активности должно быть значительно дольше;
     * Удаление ссылки по таймауту       позволяет циклу переподключения BT взять на себя управление (отличие от
     * старого «удаления ссылки 8s» заключается только в длине окна).
     */
    private const val PENDING_START_SESSION_WAIT_MS = 5 * 60_000L

    /**
     * Верхний предел ctrl-приглашений от ожидающих машин (управляемых событиями, покидать сразу после получения).
     * Кошачья игра       использует приглашение в качестве триггера подключения; измеренное приглашение приходит
     * через ~1,0-1,3 секунды после перерегистрации ctrl.
     * Если автомобиль       не отправляет приглашение (старая модель), оно будет заранее выпущено с помощью
     * «discovered_airplay._tcp». В худшем случае это может ждать очень долго.
     */
    private const val CTRL_INVITE_WAIT_MS = 4_000L

    /** Ожидание попадания в кэш mDNS, когда конечная точка не завершена: его начали обнаруживать при подключении к сети, и обычно оно происходит в течение нескольких сотен миллисекунд. */
    private const val ENDPOINT_RESOLVE_WAIT_MS = 3_000L

    /**
     * Верхний предел пары пополнения «обновления шифрования» после отклонения открытого текста SETUP (путь catplay handle_setup_result).
     * приходит сюда только тогда, когда его фактически отбраковывают, обычная машина стоит ноль; ресивер, который не
     * поддерживает сопряжение, будет
     * Пара-установка       сдастся сразу после получения 404/405/455 и не будет ждать заполнения этого окна.
     */
    private const val PAIRING_UPGRADE_WINDOW_MS = 30_000L

    /**
     * Окно повтора для первоначальной НАСТРОЙКИ сеанса с открытым текстом. Порт AirPlay на автомобилях до HomeKit всегда доступен.
     * открывается после того, как «пользователь нажимает на значок мобильного телефона на машине»; открытие порта с
     * опозданием на 1–2 секунды не должно приводить к сбою всего раунда.
     */
    private const val INITIAL_SETUP_WINDOW_MS = 30_000L

    /**
     * Верхний предел ожидания, когда конечная точка достигнута, но мобильный телефон еще не переключился на автомобильную точку доступа.
     * охватывает два пути: «автоматическое присоединение (несколько секунд)» и «присоединение вручную (обычно 10–30 секунд)»;
     * четко запрашивает и сбрасывает sessionStarting после таймаута, не выходя из «Постоянного подключения».
     */
    private const val CAR_WIFI_BIND_WAIT_MS = 45_000L

    /**
     * Верхний предел (в секундах) onAvailable, например  `requestNetwork(WIFI)`.
     * Когда пользователь       вручную присоединяется к точке доступа в настройках системы, обратный вызов будет
     * ждать завершения ассоциации + DHCP, что в реальных измерениях может занять до 10 с+.
     * Старое значение       5s приведет к ошибочной оценке ошибки за 116 мс до успеха и прекращению всего сеанса
     * (см.bindCarWifiAndRegister).
     */
    private const val WIFI_BIND_WAIT_S = 15L

    /**
     * Временной интервал после  `cmd wifi connect-network`, когда система фактически подключена к точке доступа.
     * Сама команда       только приводит к WifiService «соединение» и возвращает результат (exit=0 не означает, что оно подключено).
     * Отключите старую сеть → Сканирование → Ассоциация → DHCP. Время измерения составляет 2–10 с, дольше, если
     * точка доступа 5 ГГц работает медленнее.
     */
    private const val CAR_WIFI_JOIN_WAIT_MS = 25_000L

    /** Временной интервал ожидания фактического включения WLAN после `cmd wifi set-wifi-enabled enabled`. */
    private const val WIFI_ENABLE_WAIT_MS = 10_000L

    /**
     * Такие пользователи, как , подтверждают верхний предел всплывающего окна системы «Подключить автомобильную
     * точку доступа» (эксклюзивная сеть приложения).
     * Окно подтверждения системы       может оставаться в течение длительного времени; [connectCancelled] немедленно
     * завершит работу, как только пользователь отменит соединение.
     */
    // Общее окно 40 с = первый сегмент 20 с + сегмент повторной передачи 20 с (внутренний сегмент соединителя).
    // Автомобиль машина 15с разборка БТ сторожевой

    // Решено, что ожидание должно быть намного короче, чем старое значение 120 с - при старом значении, когда первый
    // раунд глушения достигнет 120 с, машина и машина уже будут отвязаны и повторно приглашены.

    // А дверь заблокировали зомби (22 октября 2026 г. Audi 3315 настоящая машина Dingji).
    private const val SPECIFIER_JOIN_TIMEOUT_MS = 40_000L

    /** Вызывается, когда Activity onCreate: регистрирует applicationContext и создает объект сеанса (идемпотент). */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        ensureSessionObjects(context.applicationContext)
        // Причину, по которой не удалось выполнить запись, совместимую с горизонтальным экраном (ограничение
        // пользовательской сборки/сбой Shizuku), можно определить только здесь:

        // Тост на панели настроек сбоку автомобиля виден только пользователю, а для устранения неполадок необходимы логи.
        AppOrientationCompat.diagSink = ::appendLog
        // Сигнал тревоги, когда основной поток вызывает оболочку (из-за этого возникли два реальных машинных ANR в
        // истории; logcat не читается в HKS)

        Shell.violationSink = ::appendLog
        // Переключатель исходного журнала HID (калибровка ключа): статический флаг инициализируется из настроек, также
        // вступает в силу холодный запуск процесса.

        HidNavigation.rawLogEnabled = DisplayPrefs.hidRawLog(appContext!!)
    }

    /** Добавьте строку журнала: logcat + файл + буферизация на уровне процесса + рендеринг экрана (инкрементная строка отправляется напрямую). Любая резьба регулируется. */
    fun appendLog(message: String) {
        // В файл журнала будет записан NUL: его нельзя открыть с помощью текстового редактора/файлового менеджера
        // (инструмент чтения также распознает его как двоичный),

        // Журналы отзывов пользователей равны Бай Го. Поле адреса фиксированной длины 0x4301 когда-то печатало несколько NUL, как это было.
        // (216 измерено на 18 октября 2026 г.), заменено здесь видимыми заполнителями - кстати, "в строке есть грязные байты"
        // Виден непосредственно в журнале, а не невидим из-за сбоя анализа InetSocketAddress.
        val clean = if (message.indexOf('\u0000') >= 0) message.replace("\u0000", "␀") else message
        android.util.Log.i("CarPlaySender", clean)
        appContext?.let {
            DebugFileLog
                .append(it, clean)
        }
        if (!logEnabled) return
        val line =
            synchronized(logLock) {
                val stamped = '[' + timestamp.format(Date()) + "] " + clean + '\n'
                logBuilder.append(stamped)
                if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
                stamped
            }
        // Инкрементные строки отправляются непосредственно в пользовательский интерфейс: сначала toString весь буфер для
        // каждой строки, а затем setText полный текст.

        // Под высокочастотными логами (touch/frame) находится двойной усилитель потока UI и GC.
        logListener?.invoke(line)
    }

    fun logText(): String = synchronized(logLock) { logBuilder.toString() }

    /**
     * отправляет системные медиа-ключи (AudioManager глобально направляет к **активному в данный момент сеансу
     * мультимедиа**, разрешения не требуются).
     * Кнопки настольной музыкальной карты       делят этот путь с мультимедийными клавишами автомобиля/руля (потребительский HID).
     */
    fun dispatchMediaKey(keyCode: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /**
     * Ручка/четырехпозиционный рычаг → Клавиши направления: добавление виртуального дисплея **экрана приложения**
     * (DPAD требует фокусировки и не может распространяться глобально).
     *
     * переходит к `injectInputEvent` UserService (миллисекундный уровень); не удается вернуться к `input -d <id> keyevent`
     * (еще ~30 мс, но потеря ключа отсутствует). док-экран не задействован — для межэкранной фокусировки требуется
     * модификация стороны лаунчера, см.
     * docs/HID-KNOB.md.
     */
    private fun injectNavigationKey(keyCode: Int) {
        // Установите приоритет внедрения на «видимый в данный момент экран приложения» (сетка рабочего стола/экран
        // открытого приложения), в противном случае вернитесь к главному экрану приложения.

        val displayId =
            VirtualLauncher.activeAppDisplayId().takeIf { it > 0 }
                ?: VirtualLauncher.appDisplayId
        if (displayId <= 0) {
            appendLog("HID: экран приложения не готов (displayId=$displayId) – навигационные клавиши игнорируются.")
            return
        }
        if (CarInputClient.keyEvent(displayId, keyCode)) return
        Shell.exec("input -d $displayId keyevent $keyCode", 3_000L)
    }

    /**
     * очищает буфер журнала уровня процесса (очистка пользовательского интерфейса является обязанностью вызывающей
     * стороны: явный `renderConsole(logText())`).
     * Исторически `logListener?.invoke("")` использовался для уведомления пользовательского интерфейса — это была
     * эпоха «полного набора текста для каждой строки».
     * семантика      ; после перехода на инкрементальный метод AppendConsole пустая строка эквивалентна добавлению
     * пустой строки, а пользовательский интерфейс остается на месте (нажата кнопка «Очистить»
     * не отвечает», основная причина, 16 октября 2026 г.). Не восстанавливайте вызов: пустая строка неотличима от реальной строки журнала.
     */
    fun clearLog() {
        synchronized(logLock) { logBuilder.setLength(0) }
    }

    // ---- Объект сеанса (владение на уровне процесса; перестроение активности не влияет) ----

    private var wifiConnector: WifiCarPlayConnector? = null

    /**
     * К какой автомобильной точке доступа  привязан в данный момент (записывается после успешного выполнения привязки CarWifiAndRegister).
     * Если конечная точка       уже привязана к тому же SSID во время передачи обслуживания, дважды пропустите
     * функцию BindCurrentWifi — сначала она будет
     * Отмена привязки с помощью       Release() прервет вновь зарегистрированную широковещательную рассылку ctrl
     * mDNS и будет тщетно ждать повторного подключения системы.
     */
    @Volatile
    private var boundCarSsid: String? = null

    /** SSID, который задавался вопросом «Очистить ли точку доступа для сохранения системы» во время этого процесса (чтобы избежать повторных всплывающих окон при повторной попытке). */
    private val forgetPromptHandled =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** Привязка Wi-Fi является взаимоисключающей: два пути между автоматическим присоединением и передачей конечной точки могут быть привязаны к одной и той же точке доступа одновременно. */
    private val wifiBindLock = Any()

    /** Публикуемые поля блокировки двойной проверки DCL (блокировка двойной проверки без изменчивости является плохим выпуском - хотя в настоящее время она вызывается только основным потоком, поэтому скрытой опасности нет). */
    @Volatile
    private var bluetoothServer: Iap2BluetoothServer? = null
    private var bluetoothClient: Iap2BluetoothClient? = null
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /**
     * Резидентный поисковик  `_airplay._tcp` (сравните резидентный кеш Bonjour Catplay).
     * Когда       присоединяется к точке доступа, начинается фоновое обнаружение. Адрес, вероятно, будет готов к
     * моменту передачи конечной точки, и необходимости выполнять выборочные проверки не будет.
     */
    private var endpointBrowser: CarPlayServiceBrowser? = null

    @Volatile
    var videoSender: VideoSender? = null

    // ---- «Показать» эффективное значение времени выполнения (снимок при создании сеанса; на карточке героя
    // отображается значение времени выполнения вместо значения предпочтения) ----

    /** Процент масштабирования разрешения этого потока сеанса (генерируется как процент от разрешения транспортного средства и машины; 100 = исходный размер). */
    @Volatile
    var activeScalePercent: Int = 100

    /** Целевая частота кадров данного сеанса (верхний предел ритма синтеза; настройку «Дисплей» можно переключать во время работы, чтобы она вступила в силу в реальном времени). */
    @Volatile
    var activeTargetFps: Int = 30

    @Volatile
    var audioSender: AudioSender? = null

    @Volatile
    var currentClient: AirPlayClient? = null

    @Volatile
    var currentHiddenSource: HiddenDisplaySource? = null

    /** Общий ключ и адрес приемника текущего сеанса: если авторизация проецирования происходит позже, чем подключение, функция аудио автоматически повторно включается. */
    @Volatile
    var currentSharedSecret: ByteArray? = null

    @Volatile
    var currentAudioHost: String? = null

    /** Воспроизводить ли звук на стороне автомобиля для этого сеанса (выбирается во всплывающем окне «Начать подключение»). */
    @Volatile
    var audioEnabledForSession: Boolean = false

    /**
     * «Открытый звук (файл совместимости)»: расположите немедийные файлы (совместимость и т. д.) на первом месте среди кандидатов.
     * 19 октября 2026 г. Фактические измерения Honda HFT — в медиафайле заявлено только 44,1 КБ, а аудиоканал
     * автомобиля потребляет только 48 КБ.
     * «отключит весь автомобиль через несколько секунд после выхода в Интернет» при использовании передачи по умолчанию.
     */
    @Volatile
    var audioPreferCompat: Boolean = false

    /** Звуковое оборудование, которое фактически использовалось в этом раунде (для отключения атрибуции и обратной записи в память; ноль = на этот раз звука нет). */
    @Volatile
    private var activeAudioProfile: AudioProfile? = null

    /** Ключ памяти аудиосистемы (идентификационный текст автомобиля, см. [AirPlayClient.receiverIdentity]). */
    @Volatile
    private var activeAudioReceiverKey: String = ""

    /** Время, когда аудиоканал фактически начинает потоковую передачу (uptimeMillis); 0 = не запущен. */
    @Volatile
    private var activeAudioStartedAt: Long = 0L

    /**
     * Аудио «окно выживания» (мс): Файл считается доступным только в том случае, если он превышает его.
     * На основе фактических измерений от 19 октября 2026 г.: дизассемблирование канала происходит через 6,5 с после
     * начала звука; Осталось 20 секунд для инициализации канала автомобильной аудиосистемы.
     * и маржа для первого периода недогрузки.
     */
    private const val AUDIO_PROFILE_PROBE_MS = 20_000L

    /** Установление сеанса является взаимоисключающим (разрешен только один процесс передачи конечной точки → сопряжение → потоковая передача). */
    val sessionStarting = AtomicBoolean(false)

    /**
     * Поздняя конечная точка (0x4301) получена, когда процесс передачи обслуживания уже запущен, но AirPlayClient еще не создан.
     * используется один раз при запуске потока сопряжения, а затем очищается — в это время нет клиента для
     * обновления, и если он утерян, то до следующего раунда это будет напрасно.
     */
    @Volatile
    private var pendingEndpointUpdate: Iap2SenderSession.CarPlayEndpoint? = null

    /** Пользователь отменяет соединение: поток сопряжения прерывается после обнаружения критической контрольной точки (ожидание mDNS/спаривания не может быть прервано на полпути, степень детализации составляет секунды). */
    @Volatile
    var connectCancelled: Boolean = false

    /**
     * Подсказка в одно предложение для текущего этапа подключения (подзаголовок карты героя см. в журнале для
     * подробного описания процесса).
     * явно устанавливается на каждом этапе; если значение равно нулю, пользовательский интерфейс отображает копию по умолчанию.
     */
    @Volatile
    var phaseHint: String? = null

    /** Отменить «Соединение»: Остановить ссылку + установить флаг отмены. Пользователи могут взять на себя инициативу и выйти из сети, если они застряли в неправильной сети или ждут повторного подключения. */
    fun cancelConnecting(context: Context) {
        connectCancelled = true
        appendLog("СЕАНС: Пользователь отменил соединение – остановка соединения Bluetooth/WiFi")
        disconnect(context)
        // Принудительное освобождение мьютекса: поток процесса сопряжения может быть заблокирован при непрерывном
        // соединении или ожидании mDNS (например, при вызове мобильного телефона).

        // (Подключение к IP-адресу точки доступа, когда он не находится в сегменте сети точки доступа), наконец, далеко
        // - пользователь явно отменяет разблокировку,

        // В противном случае карта героя постоянно «подключается» (выходные данные старого процесса были очищены
        // retireSession, и конфликта нет)

        sessionStarting.set(false)
        phaseHint = "Соединение отменено"
    }

    /**
     * Канал BT на передающей стороне отключен (насос поддержания активности отключается). Когда сопоставление
     * находится в процессе (а не нажато), этот раунд обречен на провал——
     * немедленно освобождает мьютекс «Обработка сеанса» и пробуждает застрявшее ожидание (отключение автомобиля
     * занимает 15 секунд + мы присоединяемся через 120 секунд).
     * Несоответствие окна ожидания однажды заблокировало дверь в раундах зомби: новая конечная точка, вызванная
     * переподключением автомобиля и машины, была «сеанс уже обрабатывается»
     * отказывается, оставляя только функцию поддержания активности, пока пользователь не сдастся, 22 октября 2026 г.
     * Audi 3315 - настоящая машина подтверждена).
     *
     * устанавливает [connectCancelled], чтобы позволить потоку сопряжения, который застрял в Wi-Fi, присоединиться к
     * проверке фрагментации и выйти в течение ≤200 мс;
     * **Непрерывный компонент BT** (скорее всего, автомобиль немедленно подключится к монитору). Этот знак исчезнет
     * в начале нового раунда——
     * Если       может туда зайти, это означает, что компонент BT все еще работает (если пользователь отменит
     * отключение пути, компонент будет остановлен и конечная точка будет остановлена.
     * перехват охраны), можно безопасно очистить.
     */
    fun onSenderLinkClosed() {
        if (videoSender?.isRunning == true) return
        if (sessionStarting.compareAndSet(true, false)) {
            connectCancelled = true
            appendLog(
                ">>> В этом раунде отключения соединения Bluetooth — отпустите «Обработку сеанса» и дождитесь, " +
                    "пока конечная точка повторного соединения автомобиля с машиной вступит во владение.",
            )
        }
    }

    /**
     * SSID точки доступа автомобиля-машины, известный в этом процессе: сначала будет выбран тот, который был
     * привязан, а затем SSID последних учетных данных.
     *
     * поставляет [Iap2SenderSession]. Суть, когда говорят "в этом раунде нет ни 0x5703, ни 0x4301": насколько вы знаете
     * Нет необходимости отключать      , который является точкой доступа автомобиля — используйте mDNS/шлюз в
     * соответствии с текущей сетью, и последний 0x4301 по-прежнему будет обновляться в горячем режиме.
     * 22 октября 2026 г. Журнал Audi 3315: Телефон находился в точке доступа автомобиля со второго раунда, но каждый
     * раунд из-за «нет учетных данных».
     * совершает самоубийство раз в 8 секунд и неоднократно очищает окно приглашения со стороны автомобиля (реальная
     * запись на iPhone занимает 21 секунду для приглашения).
     */

    /** Кэш связи между поколениями, определяемый этим раундом сеанса Bluetooth (запись обратного вызова портрета 0x1D01; ноль = нераспознанный). */
    @Volatile
    private var senderLinkGenerationCache: ReceiverProfile.LinkGeneration? = null

    private fun knownCarHotspotSsid(): String? = boundCarSsid ?: lastCarWifiSsid

    /**
     * Генерация соединения автомобиль-машина, определяемая этим раундом сеанса Bluetooth (портрет 0x1D01, CLASSIC_MIB3 / EVENT_LINK).
     * возвращается к EVENT_LINK, когда объект сеанса не создан или не распознан (совместимое поведение до версии 0.1.13) ——
     * ошибочно принял MIB3 за EVENT_LINK. Он молчит только после отсутствия ответа после переговоров. В противном случае машина зависнет.
     */
    private fun senderLinkGeneration(): ReceiverProfile.LinkGeneration =
        senderLinkGenerationCache ?: ReceiverProfile.LinkGeneration.EVENT_LINK

    /**
     * Последний пароль точки доступа 0x5703 (только память, а не диск).
     * Кнопка «Car Hotspot → Connect» копируется в буфер обмена (добавить кавер-сцену вручную).
     */
    @Volatile
    var lastWifiPsk: String? = null

    /**
     * Последний SSID точки доступа, полученный от автомобиля (0x5703 или 0x4301, пропишите оба пути).
     *
     * Разница между       и [boundCarSsid]:boundCarSsid имеет значение только после **связывания успешно**, и вот оно
     * Пишите, когда вы «получите учетные данные» — используется для определения «к какой точке доступа должен быть
     * подключен этот автомобиль», когда нет новых учетных данных.
     * В журнале Audi       ручная кнопка ложно сообщила: «Пароль точки доступа не получен», что также связано с тем,
     * что в пути 0x4301 не записан psk.
     */
    @Volatile
    var lastCarWifiSsid: String? = null

    /** Перехват пользовательского интерфейса экрана зависания: уведомляет текущее действие о необходимости заполнения поля ввода (может быть пустым) при изменении SSID. */
    @Volatile
    var ssidSink: ((String) -> Unit)? = null

    /** Привязка пользовательского интерфейса к зависанию экрана: пользователю выскакивают важные подсказки о подключении (Wi-Fi не включен/ограничения на подключение к точке доступа и т. д.). */
    @Volatile
    var uiHintSink: ((String) -> Unit)? = null

    /** Характеристики всплывающего окна (двухкнопочный запрос, например, подтверждение добавления точки доступа). */
    data class DialogSpec(
        val title: String,
        val message: String,
        val positive: String,
        val negative: String,
    )

    /**
     * Привязка пользовательского интерфейса к зависающему экрану: всплывающее окно подтверждения с двумя кнопками.
     * onChoice: true = положительный; ложь = отрицательный; null=нет ответа/закрыто (вызывающий абонент держит это в секрете).
     */
    @Volatile
    var dialogSink: ((DialogSpec, onChoice: (Boolean?) -> Unit) -> Unit)? = null

    private fun ensureSessionObjects(context: Context) {
        if (bluetoothServer != null) return
        synchronized(this) {
            if (bluetoothServer != null) return
            wifiConnector =
                WifiCarPlayConnector(context) { line -> appendLog(line) }.also { connector ->
                    // Сеть, специфичная для приложения, отключается системой (автомобиль выключается/покидает зону действия): четко
                    // сообщите об этом пользователю,

                    // Вместо того, чтобы позволить герою молча колебаться между «подключено» и «неудачно».
                    connector.onNetworkLost = { ssid ->
                        boundCarSsid = null
                        phaseHint = "Точка доступа в автомобиле отключена ($ssid)"
                        uiHintSink?.invoke("Автомобильная точка доступа \"$ssid\" отключена. Перезапустите соединение.")
                        appendLog(">>> Автомобильная точка доступа \"$ssid\" отключена – необходимо переподключить эту сессию")
                    }
                }
            val wifiCredentialHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
                { wifi -> onWifiCredentials(wifi) }
            // Динамическое считывание целевого автомобиля и машины: после выбора следующий раунд попыток
            // подключения/следующее соединение вступит в силу без перезапуска соединения.

            val targetProvider: () -> TargetDevicePrefs.Target? = {
                appContext?.let { TargetDevicePrefs.get(it) }
            }
            bluetoothServer =
                Iap2BluetoothServer(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "Соединение автомобиля с машиной") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "Соединение автомобиля с машиной") },
                    onLinkClosed = ::onSenderLinkClosed,
                    knownCarSsid = ::knownCarHotspotSsid,
                    onGeneration = { g ->
                        senderLinkGenerationCache = g
                        appendLog("IAPLINK: обновление кэша между поколениями → $g (для отправки на охрану этапа AirPlay)")
                    },
                    targetProvider = targetProvider,
                )
            bluetoothClient =
                Iap2BluetoothClient(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "Активная машина для подключения автомобиля") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "Активная машина для подключения автомобиля") },
                    onLinkClosed = ::onSenderLinkClosed,
                    knownCarSsid = ::knownCarHotspotSsid,
                    onGeneration = { g ->
                        senderLinkGenerationCache = g
                        appendLog("IAPLINK: обновление кэша между поколениями → $g (для отправки на охрану этапа AirPlay)")
                    },
                    targetProvider = targetProvider,
                )
            ctrlAdvertiser = CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }
            endpointBrowser =
                CarPlayServiceBrowser(
                    context,
                    log = { line -> appendLog(line) },
                    accept = { info ->
                        // Когда мобильный телефон имеет двойное подключение (подключен к точке доступа в автомобиле + точке доступа,
                        // которую вы открываете для совместного использования с компьютером), mDNS будет пересекать сеть.

                        // Откройте для себя приемник AirPlay на компьютере (входит в комплект macOS) — фактическое тестирование 11
                        // октября 2026 г.

                        // Макбук был подключен как автомобильный компьютер на 120 секунд. Транспортные средства, не входящие в этот
                        // сегмент сети, транспортными средствами не считаются.

                        // EndpointHosts используется для определения адреса: IPv4 использует префикс /24, локальный IPv6.
                        // Естественно только по этой ссылке глобальный IPv6 сравнивается по /64. Старая реализация распознает только
                        // IPv4 и будет

                        // Audi 4xxx, который транслирует только fe80::, напрямую отбрасывается (19 октября 2026 г.).
                        val ip = info.host?.hostAddress ?: return@CarPlayServiceBrowser false
                        EndpointHosts.isSameNetwork(
                            ip,
                            wifiConnector?.localIpv4Address(),
                            wifiConnector?.localIpv6Addresses().orEmpty(),
                        )
                    },
                )
        }

        // Если последний процесс аварийно завершился или был завершен во время физического разрыва экрана, панель
        // остановится в состоянии OFF (UserService — это резидентный процесс оболочки,

        // Состояние питания, которое оно сохраняет, не исчезает в процессе применения). Возобновить один раз при запуске
        // нового процесса: в данный момент сеанса нет,

        // Восстановление безопасно — в противном случае пользователь получит черный экран, который невозможно засветить
        // (можно спасти, дважды нажав кнопку питания).

        Bg.run("screen-power-startup-restore") {
            if (ShizukuGate.state(context) != ShizukuGate.State.READY) return@run
            val deadline = android.os.SystemClock.uptimeMillis() + 3_000L
            while (android.os.SystemClock.uptimeMillis() < deadline && CarInputClient.service == null) {
                CarInputClient.ensure(context)
                Thread.sleep(150)
            }
            // Восстанавливается только ситуация «привязанного сервиса»: нет необходимости беспокоить пользователя, когда
            // Шизуку не на ногах (панель нами не закроется)

            if (CarInputClient.service != null && !CarInputClient.setPhysicalScreenPower(true)) {
                appendLog(
                    "ЭКРАН: не удалось начать восстановление физического экрана (ПЗУ не авторизовано для " +
                        "управления питанием оболочки)",
                )
            }
        }
    }

    /** Текст строки состояния (используется Activity для отображения statusText). */
    fun statusLine(): String =
        "wifi=${wifiConnector?.currentNetwork != null} " +
            "bt=${bluetoothServer?.isRunning == true} ctrl=${(ctrlAdvertiser?.port ?: -1) > 0}"

    /** Активен ли сеанс (либо процесс выхода из потоковой передачи, либо BT запущен). */
    fun isSessionRunning(): Boolean =
        videoSender?.isRunning == true ||
            bluetoothServer?.isRunning == true ||
            bluetoothClient?.isRunning == true

    /**
     * Субъект «Начать соединение» (проверка разрешения вызывается после завершения действия): служба поддержания
     * активности на переднем плане + двусторонняя связь Bluetooth.
     * Идемпотент: при наличии сеанса функция start() компонента BT закоротится и не будет открываться дважды.
     */
    fun startConnection(context: Context) {
        connectCancelled = false
        phaseHint = "Bluetooth готов, ждём повторного подключения автомобиля..."
        when (
            ShizukuGate
                .state(context)
        ) {
            ShizukuGate.State.READY ->
                appendLog("ШИЗУКУ: Готово (доступна сенсорная инъекция)")

            ShizukuGate.State.UNAUTHORIZED -> {
                appendLog(
                    "SHIZUKU: Это приложение не авторизовано — был инициирован запрос на авторизацию, нажмите " +
                        "«Разрешить» во всплывающем окне, а затем снова подключитесь.",
                )
                ShizukuGate
                    .requestPermission()
            }

            ShizukuGate.State.NOT_RUNNING ->
                appendLog("SHIZUKU: Служба не запущена. Откройте приложение Shizuku и нажмите «Пуск».")

            ShizukuGate.State.NOT_INSTALLED ->
                appendLog("SHIZUKU: Не установлено (требуется для режима невидимого экрана)")
        }
        CarPlayService
            .start(context)
        // Выпуск возможностей предшествует подключению (подход «кошачьей игры»: CarPlayCtrlServer постоянно транслирует
        // информацию в начале сеанса).

        // `_carplay-ctrl._tcp`, через каждую 1 секунду Force_announce AirPlay будет создаваться только после получения
        // приглашения от машины).

        // Раньше нам приходилось ждать регистрации 0x5703, поэтому этап «автомобильное обнаружение мобильного телефона»
        // всегда отставал от привязки к точке доступа.

        // Каждый раунд занимает на 1–3 секунды больше; теперь он публикуется при запуске соединения, и обеспечения
        // автоматической перерегистрации при смене сети.

        ctrlAdvertiser?.resetInviteCount()
        // Фоновый выпуск: start() необходимо впервые проанализировать реальный MAC-адрес Bluetooth (оболочку) в Shizuku,
        // что невозможно сделать в основном потоке.

        Bg.run("ctrl-publish") { ctrlAdvertiser?.start() }
        // Реальный путь автомобиля: активное подключение к мобильному телефону (сервис аксессуаров SDP...caff)
        // Путь тестового стенда: отслеживайте UUID iPhone на стороне мобильного телефона и дождитесь обратного
        // подключения DiPlay/xcertplay.

        bluetoothClient?.start()
        bluetoothServer?.start()
        // Диагностика (фактическое испытание Audi: CarPlay не открывается, когда мобильный телефон и автомобиль подключены по Bluetooth):
        // Перед подключением введите статус профиля Bluetooth целевого устройства. Если это не удается, вы можете сразу
        // сказать, в этом ли причина.

        Bg.run("bt-profile-diag") {
            val target = appContext?.let { TargetDevicePrefs.get(it) } ?: return@run
            val connected = BtProfiles.connectedNames(context.applicationContext, target.address)
            appendLog(
                "BT-ПРОФИЛЬ: состояние профиля Bluetooth «${target.name}» перед подключением =" +
                    (connected.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "Не подключено"),
            )
        }
    }

    /** «Отключить» (может быть вызван в любом экземпляре Activity — он работает с реальным объектом конвейера). */
    fun disconnect(context: Context) {
        val hadProjection = CarPlayService.mediaProjection != null
        pendingEndpointUpdate = null
        retireSession()
        // Сеанс управления BT прекращается только здесь (состояние пользовательского терминала), а закрытие сокета
        // приводит к немедленному завершению цикла поддержания активности:

        // В противном случае он будет заблокирован до тех пор, пока у однорангового узла не истечет время ожидания
        // (фактически измеренное ~ 24 секунды), и карта героя продолжит вращаться после отмены пользователем.

        // Никогда не отключайтесь во время сопряжения — DiPlay определит, когда обнаружит отключение RFCOMM до того, как
        // туннель iAP2 будет готов.

        // при запуске произошел сбой, и весь беспроводной стек был демонтирован (включая сеанс AirPlay в push-потоке,
        // фактическое тестирование состоялось 16 октября 2026 г.).

        detachActiveBtSessions()
        phaseHint = null
        CarPlayService
            .stop(context)
        bluetoothServer?.stop()
        bluetoothClient?.stop()
        ctrlAdvertiser?.stop()
        endpointBrowser?.stop()
        wifiConnector?.release()
        boundCarSsid = null
        // Окончательное состояние пользователя, охватываемое «Горизонтальным экраном приложения Force»,
        // восстанавливается (первоначально в retireSession - где каждый раунд

        // Также выполняется запуск сопряжения, который уничтожит вновь запущенные приложения в новом раунде и перейдет в
        // реальное конечное состояние 23 октября 2026 г.).

        appContext?.let { ctx -> AppOrientationCompat.resetAllAsync(ctx, ::appendLog) }
        // Системная сеть (connect-network), сохраненная в оболочке, автоматически забывается и восстанавливается: нет
        // точки доступа в Интернет.

        // Использование системой в качестве сети по умолчанию приведет к вытеснению мобильных данных. В случае сбоя
        // будут записываться только журналы (пользователи могут перейти в WLAN, чтобы удалить их вручную).

        cleanupShellFallbackNetwork()
        // Межпоколенческий кеш очищается вместе с сессией: на стороне чтения остается `?: EVENT_LINK`, а семантика
        // остается неизменной; если ты сохранишь это

        // Существует теоретическое окно для чтения кэша предыдущего автомобиля при изменении соединений между
        // автомобилями (Audi → Honda) (проверено 23 октября 2026 г.).

        senderLinkGenerationCache = null
        if (hadProjection) {
            // Android 14+: MediaProjection привязан к службе поддержки активности (тип mediaProjection FGS),
            // Остановка службы также приведет к аннулированию авторизации — вам нужно будет снова нажать «Подать заявку на
            // разрешение на зеркалирование экрана» при следующем подключении.

            appendLog(
                "Служба Keep-Alive вручную отключена и остановлена (восстановлен постоянный свет); Срок действия " +
                    "разрешения на зеркалирование экрана истек с остановкой службы. Если при следующем подключении вам " +
                    "понадобится звук, подайте заявку еще раз.",
            )
        } else {
            appendLog("Служба проверки активности отключена и остановлена вручную (восстановлен постоянный свет)")
        }
    }

    // ---- Истинный экран физического экрана (то же самое, что scrcpy -S/--turn-screen-off) ----

    /** Установлено ли отключение физического главного экрана (используется только для определения того, следует ли его восстанавливать или нет, чтобы избежать ненужных вызовов AIDL). */
    @Volatile
    private var phoneScreenOff = false

    /**
     * Сериализация переключения питания. ввод/восстановление — это все задачи Bg (кешированный пул не гарантирует порядок),
     * Если       не заблокирован, «сначала будет запущено восстановление, введите затем запуск» → панель закроется после завершения сеанса.
     */
    private val screenPowerLock = Any()

    /**
     * Сеанс  переходит в режим «True Screen»: отключите оборудование **физического главного экрана**, система
     * останется в активном состоянии, а виртуальный экран выводит кадры как обычно.
     *
     * Разница между       и темным экраном: темный экран — это просто слой черного изображения, сенсорный слой все
     * еще включен (случайное касание кармана, энергопотребление);
     * Это отключение питания на уровне SurfaceFlinger (одновременно выключается сенсорная микросхема), которое
     * выглядит и ощущается так же, как блокировка экрана, но **не блокируется**——
     * Музыка Amap/QQ на стороне автомобиля не будет приостанавливаться системой Keyguard.
     *
     * регистрирует только сбой: когда ПЗУ не дает разрешения оболочке или не может получить токен отображения,
     * худшим случаем является возврат к темному экрану.
     */
    private fun enterPhoneScreenOff() {
        val ctx = appContext ?: return
        // Нет необходимости выключать экран, когда вы не нажимаете поток (тут же пойдет переключение при отсутствии
        // сеанса в панели настроек)

        if (videoSender?.isRunning != true) return
        Bg.run("screen-power-off") {
            synchronized(screenPowerLock) {
                if (phoneScreenOff) return@synchronized
                // Задача Bg может быть выполнена позже окончания сеанса: экран нельзя выключать после завершения сеанса.
                if (videoSender?.isRunning != true) {
                    appendLog("ЭКРАН: сеанс завершен – пропустить физический экран")
                    return@synchronized
                }
                // Если телефон находится в спящем режиме: сначала разбудите его и позвольте платформе включить физический экран;
                // в противном случае, после того, как мы выключим его, кадр

                // В процессе пробуждения панель снова загорится (оба устройства получают одинаковое состояние питания SurfaceFlinger).
                val interactive =
                    runCatching {
                        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
                    }.getOrDefault(true)
                if (!interactive) {
                    appendLog(
                        "ЭКРАН: Телефон спит - сначала разбудите его, а затем физически закройте экран (чтобы " +
                            "рамка не перезасветила панель)",
                    )
                    Shell.exec("input keyevent KEYCODE_WAKEUP", 4_000L)
                    Thread.sleep(400)
                    // Проведите пальцем по экрану, чтобы открыть экран блокировки без пароля/сценарий SmartLock; замок безопасности
                    // выйдет из строя, просто игнорируйте его

                    Shell.exec("wm dismiss-keyguard", 3_000L)
                }
                // Отключение электроэнергии вызвано темным экраном (фактическое мнение пользователя 24 октября 2026 г.:
                // сенсорная микросхема все еще работает после выключения панели.

                // Нажатие на темный экран разбудит его; темный экран - это еще и карманный щит от случайного касания - без него
                // прикосновение в кармане будет нажимать на приложение на переднем плане).

                // enterDimNow = вход в темный экран + выключение питания панели + учет (panelOffByUs), то же самое, что и ручной экран сна.
                if (ScreenDimmer.isAttached()) {
                    ScreenDimmer.enterDimNow()
                    phoneScreenOff = true
                    appendLog(
                        "ЭКРАН: введен настоящий экран (темный экран обеспечивает обнаружение касания — щелкните " +
                            "темный экран, чтобы проснуться, не нужно нажимать кнопку питания)",
                    )
                } else {
                    // Темный экран недоступен (отсутствие разрешений для плавающих окон), переход на более раннюю версию и явное
                    // отключение электроэнергии: нет сенсорного метода пробуждения, можно только нажать кнопку питания

                    val ok = CarInputClient.setPhysicalScreenPower(false)
                    phoneScreenOff = ok
                    appendLog(
                        if (ok) {
                            "ЭКРАН: физический экран выключен (темный экран недоступен — нет сенсорного метода " +
                                "пробуждения, дважды нажмите кнопку питания для восстановления)"
                        } else {
                            "ЭКРАН: физический экран не выключился (ПЗУ не предоставило разрешения оболочке/не удалось " +
                                "получить токен отображения)"
                        },
                    )
                }
            }
        }
    }

    /**
     * Панель настроек переключается на «Реальный экран при подключении»: немедленно разворачивается и сворачивается
     * во время сеанса (при отсутствии сеанса ничего делаться не будет).
     *
     * можно управлять на экране автомобиля, поэтому на переключение не повлияет, даже если панель телефона темная;
     * выключение его приведет к немедленному включению панели.
     */
    fun applyScreenOffPreference(enabled: Boolean) {
        if (enabled) {
            enterPhoneScreenOff()
        } else {
            restorePhoneScreen(sleepIfCarSide = false)
        }
    }

    /**
     * восстанавливает загорание физического экрана. Идемпотент: ничего не делать, если он не выключен.
     *
     * @param SleepIfCarSide Если сторона автомобиля отключена (люди находятся снаружи машины, а телефон в кармане),
     * это правда: пусть телефон засыпает напрямую.
     * не загорается первой панель (иначе телефон в кармане сначала загорится, а потом выключится); пользователь
     * вручную отключается и становится ложным.
     */
    private fun restorePhoneScreen(sleepIfCarSide: Boolean) {
        Bg.run("screen-power-restore") {
            synchronized(screenPowerLock) {
                if (!phoneScreenOff) return@synchronized
                phoneScreenOff = false
                if (sleepIfCarSide) {
                    // Оставьте SurfaceFlinger в состоянии OFF и дайте платформе нормально спать; платформа включит панель в
                    // следующий раз, когда она проснется

                    Shell.exec("input keyevent KEYCODE_SLEEP", 4_000L)
                    appendLog("ЭКРАН: Сторона автомобиля отключена - телефон переведен в режим сна (панель остается темной)")
                } else {
                    // На этом состояние отключения питания, вызванное темным экраном, заканчивается: удалить темный экран +
                    // восстановить питание панели (идемпотентное)

                    ScreenDimmer.exitPanelOff()
                    val ok = CarInputClient.setPhysicalScreenPower(true)
                    appendLog("ЭКРАН: Физический экран снова стал светиться ok=$ok")
                }
            }
        }
    }

    /** Полностью выведите из эксплуатации сеанс предыдущего поколения перед завершением сеанса/началом нового цикла (утечки в старом конвейере будут накапливать кодировщик/виртуальный дисплей и в конечном итоге удалять буфер -19). */
    fun retireSession() {
        videoSender?.stop()
        videoSender = null
        // Темный экран/всегда включен будет закрыт вместе с сеансом (этот метод также будет выполняться в начале каждого
        // раунда сопряжения и будет сброшен после начала нового раунда потоковой передачи)

        ScreenDimmer.setSessionActive(false)
        // Восстановление физического экрана: если вы оставите панель ВЫКЛЮЧЕННОЙ на пути к сбою/ненормальному пути, она
        // не останется на черном экране.

        restorePhoneScreen(sleepIfCarSide = false)
        audioSender?.stop()
        audioSender = null
        currentSharedSecret = null
        currentAudioHost = null
        currentHiddenSource?.release()
        currentHiddenSource = null
        TouchRouter
            .reset()
        currentClient?.cancelled = true
        currentClient?.close()
        currentClient = null
        // Примечание. Здесь нельзя отключать сеанс управления BT. Этот метод также будет выполняться в начале каждого раунда сопоставления.
        // Когда DiPlay видит, что RFCOMM отключен до того, как туннель WiFi готов, он определяет, что подключение не удалось.
        // closeWirelessStack демонтирует весь беспроводной стек (сервис Bonjour/hotspot/AirPlay) и заменяет установленный
        // Даже сеанс потоковой передачи AirPlay удаляется вместе — основная причина цикла «подключен примерно на 3
        // секунды, а затем отключен».

        // (Проверено 16 октября 2026 г.). Прекращение использования BT происходит только в состоянии пользовательского
        // терминала, см. Disconnect().

        // Выключить сеанс: возобновляется обнаружение исходящего трафика (оно будет установлено снова при следующем установлении сеанса)
        BtSessionGate.sessionActive = false
        // Восстановление покрытия «принудительного применения горизонтального экрана» **здесь не выполняется**
        // (подтверждение реального журнала машины Audi 3315 2026 г.):

        // Этот метод также будет выполняться в начале каждого раунда сопоставления. В это время осталось всего несколько
        // секунд до окончания предыдущего раунда сеанса – предыдущего раунда

        // Приложение рабочего стола/карты (тот же пакет, что и в новом раунде), вероятно, все еще живо; и заявка на эту
        // сессию находится во вложении

        // Затем подтянитесь. Если в этом окне будет выполнено отключение am-compat ofresetAll, будет уничтожен новый раунд.
        // Новое запущенное приложение** → На виртуальном экране остаются только статичные обои → Картинка, получаемая
        // автомобилем, никогда не изменится → Никаких переключений

        // Пользовательский интерфейс CarPlay (пользователи видят «Потоковая передача выполняется, но автомобиль все еще
        // находится в исходном интерфейсе»).

        // Восстановление выполняется только в реальном конечном состоянии: Disconnect() (пользователь отключен) и push
        // onEnded (отключение автомобиля от машины),

        // Оба места вызывают сбросAllAsync соответственно. Новый раунд applyAll идемпотентен старому переопределению (enable override
        // включить), нет необходимости восстанавливать перед записью.
    }

    /** Когда сессия установлена и проекция готова, но звук еще не запущен, происходит динамический дополнительный запуск (авторизация проекции происходит позже сценария подключения). */
    @Synchronized
    fun maybeStartAudioNow(context: Context) {
        val client =
            currentClient ?: run {
                appendLog(
                    "РЕЖИМ: зеркалирование готово (в данный момент сеанса нет — звук автоматически включается при " +
                        "следующем подключении)",
                )
                return
            }
        val secret = currentSharedSecret
        if (secret == null) {
            appendLog("РЕЖИМ: сеанс открытого текста (неспаренный), общий ключ отсутствует, звук не включается повторно.")
            return
        }
        if (audioSender?.isRunning == true) return
        appendLog("РЕЖИМ: сеанс продолжается, и аудиоканал включен динамически...")
        startAudioChannel(context, client, secret, currentAudioHost)
    }

    /**
     * 0x5703 прибывает (получает учетные данные автомобильной точки доступа) и немедленно выполняется — предпосылки
     * для настоящей машины 0x4301:
     * После того, как автомобильная машина обнаружит `_carplay-ctrl._tcp` телефона (TXT id = настоящий Bluetooth MAC) в сети Wi-Fi.
     * берет на себя инициативу отправить приглашение `GET /ctrl-int/1/connect` и отправляет 0x4301 только после
     * того, как приглашение будет принято.
     * Поэтому здесь должно быть: привязка автомобильного WiFi → управление трансляцией; позже, чем 0x4300, обе
     * стороны зайдут в тупик (фактическое измерение Audi).
     */
    fun onWifiCredentials(wifi: Iap2SenderSession.WifiConfiguration) {
        // 0x5703, полученный в потоке push-уведомлений сеанса, является избыточными учетными данными (контроллер DiPlay
        // повторно отправит их один раз при периодическом повторном подключении.

        // Этап 1, 15 октября 2026 г. Одна из движущих сил фактического измерения цикла «автоматическое отключение после
        // подключения»): в это время выполняется привязка.

        // Процесс освободит + перепривяжет сеть процесса (нарушая работу сокета), принудительно перерегистрирует ctrl.
        // mDNS (DiPlay рассматривается как возвращение телефона в режим онлайн и проведение еще одного раунда RFCOMM).
        // Очистите только кеш паролей.

        if (videoSender?.isRunning == true) {
            lastWifiPsk = wifi.passphrase
            lastCarWifiSsid = wifi.ssid
            appendLog(
                "0x5703 Избыточные учетные данные (потоковая передача сеанса) — оставьте существующие привязки " +
                    "Wi-Fi и трансляцию ctrl без изменений.",
            )
            return
        }
        ssidSink?.invoke(wifi.ssid)
        lastWifiPsk = wifi.passphrase
        lastCarWifiSsid = wifi.ssid
        phaseHint = "Получите учетные данные автомобильной точки доступа и привяжите Wi-Fi..."
        appendLog(">>> Получите учетные данные автомобильной точки доступа (0x5703) — немедленно привяжите WiFi + трансляцию _carplay-ctrl")
        // Трансляция ctrl была опубликована в startConnection (catplay: сохраняйте возможность публикации в Интернете перед подключением).
        // Зарегистрируйте SSID здесь и один раз выровняйте регистрацию (если телефон был вручную подключен к
        // автомобильной точке доступа, Shizuku недоступен

        // Это единственная возможность перерегистрироваться); после присоединения к точке доступа,bindCarWifiAndRegister также заставит
        // Перерегистрируйтесь и разместите многоадресную рассылку в сети, где находится автомобиль (требуется для
        // фактического тестирования автомобиля).

        ctrlAdvertiser?.ensureForNetwork(wifi.ssid)
        appendLog("CTRL: трансляция была опубликована при инициировании соединения, здесь она привязана к точке доступа ${wifi.ssid}.")
        // Привязка и переключение выполняются в одном и том же процессе автоматического присоединения:bindCurrentWifi —
        // это слепая привязка, указывающая, что «любой Wi-Fi успешен».

        // Когда мобильный телефон подключен к корпоративной сети Wi-Fi, он ошибочно определит, что находится в точке
        // доступа автомобиля (трансляция ctrl попадает не в ту сеть → обе стороны заблокированы).

        // Вы должны проверить реальный SSID, прежде чем принять решение о привязке или переключении.
        autoJoinCarWifi(wifi)
    }

    /**
     * корректирует реальный Wi-Fi и добавляет автомобильные точки доступа. **Приоритет тихого пути** (от 22 октября 2026 г.):
     * 1. `cmd wifi set-wifi-enabled` + `cmd wifi connect-network` (Шизуку): прямая система
     * подключается к точке доступа, **0 всплывающих окон, 0 пользовательских операций**; точка доступа сохраняется в
     * конфигурации системы и автоматически переподключается при следующем входе в зону действия;
     * 2. `requestNetwork(TRANSPORT_WIFI)` (**без спецификатора**) получает `Network` от Wi-Fi.
     * обрабатывает и привязывает сеть процесса - без спецификатора окно подтверждения системы не появится;
     * 3. Проверьте каждую ссылку. [connectCancelled] - пользователю не разрешено продолжать работу после отмены
     * (иначе PhaseHint будет перезаписан,
     * Трансляция ctrl       была перезапущена с сообщением «нельзя отменить»).
     * возвращается в сеть, специфичную для приложения, только после сбоя тихого пути (Шизуку недоступен/не удалось
     * подключиться к командной строке)
     * (`WifiNetworkSpecifier`, при первом присоединении появится окно подтверждения системы).
     * вернется к руководству «Присоединение к WLAN вручную», если все не удастся (пароль можно скопировать с помощью «Отключить»).
     */
    private fun autoJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        Bg.run("wifi-auto-join") {
            val shellReady =
                appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
            // 1) Предпочтительный путь без вывода сообщений: сеть командной строки Shizuku (0 всплывающих окон).
            if (shellReady) {
                if (joinCarWifiSilently(wifi) is WifiCarPlayConnector.JoinResult.Joined) return@run
                if (connectCancelled) return@run
                // Точка доступа подключена, но Сетевой канал не привязан: спецификатор возвращать нельзя — он будет перезапущен
                // Привяжите точку доступа и появится всплывающее окно подтверждения системы (пользователь увидит «Подключение и
                // всплывающее окно»). Непосредственное завершение и запрос на повторное подключение.

                if (shellWifiStatus().second == wifi.ssid) {
                    phaseHint = "Подключено к автомобильной точке доступа, но не удалось привязаться к сети. Повторите подключение."
                    appendLog(
                        "WIFI: подключен к \"${wifi.ssid}\", но requestNetwork не перезвонил - этот раунд прерван" +
                            "(Не возвращайте появившийся путь спецификатора)",
                    )
                    return@run
                }
            }
            // 2) Резервный вариант: применить выделенную сеть (не влияет на 4G/5G и не будет сохранена как системная сеть).
            if (shellReady && shellWifiDisabled() && !waitForWifiEnabled()) return@run
            if (connectCancelled) return@run
            when (val result = joinCarWifiSpecifier(wifi)) {
                is WifiCarPlayConnector.JoinResult.Joined -> Unit

                WifiCarPlayConnector.JoinResult.Unsupported -> {
                    appendLog(
                        "WIFI: тип безопасности точки доступа ${wifi.securityType} не может быть выражен с " +
                            "использованием сети для конкретного приложения — вернитесь к присоединению на уровне системы.",
                    )
                    legacyJoinCarWifi(wifi, shellReady)
                }

                WifiCarPlayConnector.JoinResult.Unavailable -> promptSpecifierFailed(wifi, shellReady)
            }
        }
    }

    /**
     * Вход в сеть, когда конечная точка  прибывает до 0x5703 (или предыдущая ротация сети не удалась): тихий путь имеет приоритет,
     * Тихо выходит из строя, а затем возвращается в сеть, специфичную для приложения.
     */
    private fun joinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): WifiCarPlayConnector.JoinResult {
        if (!shellReady) return joinCarWifiSpecifier(wifi)
        val silent = joinCarWifiSilently(wifi)
        if (silent is WifiCarPlayConnector.JoinResult.Joined) return silent
        // Точка доступа подключена, но канал не привязан: всплывающий спецификатор больше не будет отображаться (то же
        // самое решение см. в autoJoinCarWifi).

        if (!connectCancelled && shellWifiStatus().second == wifi.ssid) return silent
        return joinCarWifiSpecifier(wifi)
    }

    /**
     * автоматически подключается к автомобильным точкам доступа (**два шага, 0 всплывающих окон**).
     *
     * Шаг 1: Шизуку (uid 2000) выполняет `cmd wifi connect-network "<SSID>" wpa2 "<Пароль>"`,
     * Система       напрямую связывает точку доступа и сохраняет ее как обычную сеть — без использования `WifiNetworkSpecifier`,
     * Поэтому **не вызывает системное окно подтверждения** (всплывающее окно спецификатора является обязательным
     * поведением уровня платформы и не может быть отключено приложением).
     *
     * Шаг 2. Используйте `requestNetwork(TRANSPORT_WIFI)` без спецификатора для захвата вновь подключенного
     * WiFi-дескриптор `Network` и привязка к сети процесса ([bindCarWifiAndRegister]). нет спецификатора,
     * Система       не будет открывать всплывающие окна и может немедленно перезвонить нам по `Network` Wi-Fi автомобиля.
     *
     * Почему для второго шага по-прежнему требуется `bindProcessToNetwork` (вместо того, чтобы просто передавать сеть в NsdManager):
     * — трансляция mDNS `_carplay-ctrl._tcp` `NsdManager.registerService` **Нет сети
     * Параметр      *** (доступен только для `AdvertisingRequest` API 36, этот проект компилируется Sdk 34),
     * зарегистрируйтесь, чтобы следовать
     * **Обработка сети по умолчанию**. Не привязан → Трансляция с сотового прекращается → Машина не может найти
     * мобильный телефон → 0x4301 так и не приходит;
     * — управление AirPlay/события/многократная экспозиция видео/аудио `Socket()`/`DatagramSocket()` — все они
     * полагаются на привязку процесса;
     * — привязка применяется только к сокету этого процесса: навигация/музыка на виртуальном экране является
     * независимым процессом и не будет затронута
     * (`bindProcessToNetwork` не является операцией глобального/системного уровня).
     */
    private fun joinCarWifiSilently(wifi: Iap2SenderSession.WifiConfiguration): WifiCarPlayConnector.JoinResult {
        // Имеет одну и ту же блокировку с внутренним путем спецификатора: оба пути снимают + повторно связывают сеть процесса.
        // См. [joinCarWifiSilentlyLocked] для реализации блокировки.
        return synchronized(wifiBindLock) { joinCarWifiSilentlyLocked(wifi) }
    }

    /** Реализация удержания блокировки [joinCarWifiSilently] (вызывающая сторона должна удерживать [wifiBindLock]). */
    private fun joinCarWifiSilentlyLocked(wifi: Iap2SenderSession.WifiConfiguration): WifiCarPlayConnector.JoinResult {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        val existing = connector.currentNetwork
        if (boundCarSsid == wifi.ssid && existing != null) {
            appendLog("WIFI: точка доступа в автомобиле готова, сетевой канал привязан – пропустите повторное подключение.")
            return WifiCarPlayConnector.JoinResult.Joined(existing)
        }
        if (!ensureWifiEnabledSilently()) return WifiCarPlayConnector.JoinResult.Unavailable
        if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        val current = shellWifiStatus().second
        if (current == wifi.ssid) {
            appendLog(
                "WIFI: мобильный телефон подключен к автомобильной точке доступа \"${wifi.ssid}\" — напрямую " +
                    "установите сетевой канал и привяжите его.",
            )
        } else {
            phaseHint = "Тихое подключение к автомобильной точке доступа ${wifi.ssid}…"
            appendLog(
                ">>> Тихо подключитесь к автомобильной точке доступа \"${wifi.ssid}\" (cmd wifi Connect-Network, 0 всплывающее окно;" +
                    "текущий ${current ?: "не подключено"}）",
            )
            // Команда возвращается после приведения соединения к WifiService. выход=0 не означает, что он подключен —
            // необходимо опросить SSID

            val result =
                Shell.exec(
                    WifiConnectCommand.build(wifi.ssid, wifi.passphrase, wifi.securityType),
                    20_000L,
                )
            if (!result.ok) {
                appendLog(
                    "WIFI: не удалось подключиться к сети (exit=${result.exitCode}" +
                        "${result.output.take(80)}）",
                )
                return WifiCarPlayConnector.JoinResult.Unavailable
            }
            if (!waitForCarSsid(wifi.ssid, CAR_WIFI_JOIN_WAIT_MS)) {
                return WifiCarPlayConnector.JoinResult.Unavailable
            }
        }
        if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        val bound = bindCarWifiAndRegister(wifi.ssid)
        val network = connector.currentNetwork
        return if (bound && network != null) {
            WifiCarPlayConnector.JoinResult.Joined(network)
        } else {
            WifiCarPlayConnector.JoinResult.Unavailable
        }
    }

    /** Когда WLAN выключен, Shizuku напрямую включает его (`cmd wifi set-wifi-enabled`) и сообщает, включен ли он. */
    private fun ensureWifiEnabledSilently(): Boolean {
        if (!shellWifiDisabled()) return true
        appendLog("WIFI: WLAN не включен - включите его через Shizuku (cmd wifi set-wifi-enabled включен, ручная работа не требуется)")
        val result = Shell.exec("cmd wifi set-wifi-enabled enabled", 8_000L)
        if (!result.ok) {
            appendLog("WIFI: не удалось открыть WLAN (exit=${result.exitCode} ${result.output.take(60)})")
            return false
        }
        val deadline = android.os.SystemClock.uptimeMillis() + WIFI_ENABLE_WAIT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            if (!shellWifiDisabled()) return true
            Thread.sleep(500)
        }
        if (shellWifiDisabled()) {
            appendLog("WIFI: тайм-аут ожидания включения WLAN (${WIFI_ENABLE_WAIT_MS / 1000} с)")
            return false
        }
        return true
    }

    /** Опрашивайте `cmd wifi status` до тех пор, пока он не будет связан с [ssid] (можно отменить). */
    private fun waitForCarSsid(
        ssid: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        var lastLogAt = 0L
        var current: String? = null
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (connectCancelled) return false
            current = shellWifiStatus().second
            if (current == ssid) {
                appendLog("WIFI: переключен на точку доступа автомобиля \"$ssid\"")
                return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLogAt >= 3_000L) {
                lastLogAt = now
                phaseHint = "Переключение на автомобильную точку доступа $ssid…"
                appendLog("WIFI: Ожидание переключения на \"$ssid\" (текущий ${current ?: "не подключено"}）…")
            }
            Thread.sleep(500)
        }
        appendLog("WIFI: тайм-аут ожидания переключения на \"$ssid\" (текущий ${current ?: "не подключено"}）")
        return false
    }

    /**
     * использует сеть конкретного приложения (`WifiNetworkSpecifier`) для подключения к автомобильной точке доступа.
     * После успеха сеть процесса связывается.
     *
     * **Просто серверная часть молчаливого пути** (Shizuku недоступен или ПЗУ блокирует `cmd wifi connect-network`):
     * Эта сеть видна только этому процессу, не сохраняется и не становится сетью по умолчанию. Однако при первом
     * присоединении появится окно подтверждения системы.
     * Поэтому       больше не запрашивает «Очистить точки доступа с таким же именем, сохраненные в системе» — тихий
     * путь уже сохранит точки доступа.
     * Конфигурация системы       (это конструкция, а не остаток старой версии).
     */
    private fun joinCarWifiSpecifier(wifi: Iap2SenderSession.WifiConfiguration): WifiCarPlayConnector.JoinResult {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        phaseHint = "Подключение к автомобильной точке доступа ${wifi.ssid}… (не влияет на передачу мобильных данных)"
        appendLog(
            ">>> Подключитесь к \"${wifi.ssid}\" к сети конкретного приложения (не сохраняется как системный Wi-Fi, " +
                "другие приложения продолжают использовать 4G/5G)",
        )
        val result =
            synchronized(wifiBindLock) {
                val existing = connector.currentNetwork
                if (boundCarSsid == wifi.ssid && existing != null) {
                    appendLog("WIFI: Сеть для конкретного приложения уже создана. Пропустите повторное подключение.")
                    WifiCarPlayConnector.JoinResult.Joined(existing)
                } else {
                    connector.joinCarPlayNetwork(
                        ssid = wifi.ssid,
                        passphrase = wifi.passphrase,
                        securityType = wifi.securityType,
                        timeoutMillis = SPECIFIER_JOIN_TIMEOUT_MS,
                        isCancelled = { connectCancelled },
                        quickFallback = { specifierQuickFallback(wifi) },
                        deepFallback = { specifierDeepFallback(wifi) },
                    )
                }
            }
        if (result is WifiCarPlayConnector.JoinResult.Joined) {
            boundCarSsid = wifi.ssid
            phaseHint = "Уже присоединился к автомобильной точке доступа"
            // Ключевое время: зарегистрируйтесь после завершения связыванияProcessToNetwork, чтобы автомобиль мог видеть трансляцию ctrl.
            ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
            appendLog(
                ">>> В автомобильную точку доступа добавлен Wi-Fi (эксклюзивная сеть приложения), трансляция " +
                    "управления перерегистрирована, ожидание приглашения управления автомобилем",
            )
        } else if (!connectCancelled) {
            appendLog("WIFI: не удалось подключиться к сети приложения (${result.javaClass.simpleName}).")
            // Итог: Когда хотспот сохранен в системе, возможно, система автоматически подключилась к нему глобально — спецификатор зависает
            // В таком виде этот ярлык является наиболее стабильным (22 октября 2026 г., реальная машина: пользователь
            // выбирает «Сохранить системную сеть» +

            // Спецификатор Android 16 постоянно находится на рассмотрении). Проверьте текущий SSID и напрямую привяжите
            // существующее соединение.

            val currentSsid = shellWifiStatus().second
            if (currentSsid == wifi.ssid) {
                appendLog(
                    "WIFI: Система в настоящее время подключена к \"$currentSsid\" — напрямую привяжите " +
                        "существующее соединение (сохраните ярлык, к которому автоматически подключается сеть)",
                )
                // Асинхронный обратный вызов bindCurrentWifi: дождитесь завершения привязки (обратный вызов выполняется очень
                // быстро, система уже в сети)

                if (bindExistingCarWifi(connector, wifi.ssid) != null) {
                    boundCarSsid = wifi.ssid
                    phaseHint = "Уже присоединился к автомобильной точке доступа"
                    ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
                    appendLog(">>> WiFi привязан к существующему соединению \"$currentSsid\", трансляция ctrl перерегистрирована")
                    return WifiCarPlayConnector.JoinResult.Joined(connector.currentNetwork!!)
                }
                appendLog("WIFI: не удалось установить существующее соединение — рассматривается как сбой")
            }
        }
        return result
    }

    /**
     * SSID, записанный, когда в этом раунде оболочка сохраняла точку доступа в качестве системной сети — состояние
     * завершения сеанса (отключение и
     * push-поток onEnded) автоматическое восстановление `forget`. Если на системном уровне не сохранена точка
     * доступа в Интернет, она будет считаться сетью по умолчанию.
     * Некоторые ПЗУ выжимают мобильные данные.
     */
    @Volatile
    private var shellFallbackCleanupSsid: String? = null

    /**
     * Спецификатор  — быстрое восстановление при зависании (вызывается после нулевого обратного вызова в первом окне
     * 5с, выполняемом в потоке процесса подключения).
     *
     * Обнаружение: `forget` Возможно, система автоматически повторно подключилась к SSID после старой сети — если
     * это так, она напрямую свяжет существующее соединение.
     * должен поддерживать двусторонний обход оболочки в миллисекундах + ожидание привязки ≤3 с: бюджет доступа к
     * сети транспортного средства, начиная с 0x5703, составляет всего
     * ~9-14 с (0x4301 ~ 15 с после удаления BT), каждая лишняя 1 с здесь сжимается до «перевыпуска спецификатора»
     * Окно       (проверено на подключение к сети за 1,6с, проверенный эффективный метод восстановления).
     *
     * @return Сеть, которая привязана и может использоваться напрямую; null = промах (вызывающая сторона продолжает
     * повторно отправлять спецификатор).
     */
    private fun specifierQuickFallback(wifi: Iap2SenderSession.WifiConfiguration): Network? {
        if (!shellWifiReady()) return null
        val connector = wifiConnector ?: return null
        val current = shellWifiStatus().second
        if (current != wifi.ssid) return null
        appendLog("WIFI: Система подключена к \"$current\" – прямая привязка (быстро подтвердите)")
        val network = bindExistingCarWifi(connector, wifi.ssid)
        if (network != null) boundCarSsid = wifi.ssid
        return network
    }

    /**
     * Окно перевыпуска спецификатора  также не имеет глубины после обратного вызова: `cmd wifi connect-network`
     * напрямую подключен к точке доступа.
     *
     * При выполнении этого шага текущий раунд окна «автомобиль-машина» (~ 15 с) должен пройти — значение состоит в
     * том, чтобы позволить мобильному телефону сначала подключиться к точке доступа «автомобиль-машина»,
     * В следующем раунде (автомобиль переподключается к БТ через 0,17 с после отсоединения цепи) «система
     * подключена» будет использоваться для быстрого выбора ярлыка/ярлыка и больше не будет простаивать.
     *
     * Время очистки регистрации **критическое**: как только соединение с сетью вернется в норму, точка доступа будет настроена в системе.
     * должен немедленно зарегистрировать [shellFallbackCleanupSsid] - если вы подождете, пока «опрос для
     * переключения сети + привязка не будет успешным»
     * Регистрация      , отмена/тайм-аут/сбой привязки, три пути завершатся перед регистрацией, и системная сеть останется (глобальная
     * автоматически подключается к точкам доступа в Интернет и извлекает мобильные данные, проверено и определено 23
     * октября 2026 г.). забудь, что ничего не сохранилось
     * SSID       безвреден и не используется, лучше иметь больше, чем пропустить.
     *
     * Исключение: SSID был сохранен в системе до этого раунда** (пользователь явно выбрал «Сохранить» во всплывающем окне)
     * ——connect-network — это только идемпотентное переподключение, **не регистрирует** очистку, иначе решение
     * пользователя будет сохранено в конце сеанса
     * был удален из Интернета (нарушение «Никогда не удалять без авторизации»).
     */
    private fun specifierDeepFallback(wifi: Iap2SenderSession.WifiConfiguration): Network? {
        if (!shellWifiReady()) return null
        val connector = wifiConnector ?: return null
        appendLog(
            "WIFI: у спецификатора нет обратного вызова в обоих окнах - прямое подключение к прямой сети \"${wifi.ssid}\"" +
                "(Окно для этого раунда, скорее всего, прошло и займет место для следующего раунда)",
        )
        val preExisting = savedNetworkIds(wifi.ssid).isNotEmpty()
        val security =
            if (wifi.passphrase.isEmpty()) "open" else "wpa2 ${shellQuote(wifi.passphrase)}"
        val r = Shell.exec("cmd wifi connect-network ${shellQuote(wifi.ssid)} $security", 10_000L)
        if (!r.ok) {
            appendLog("WIFI: ошибка подключения к сети (${r.output.take(80)})")
            return null
        }
        if (preExisting) {
            appendLog(
                "WIFI: \"${wifi.ssid}\" уже был сохранен в системе ранее (пользователь решил сохранить его) — он " +
                    "не будет удален в конце сеанса.",
            )
        } else {
            shellFallbackCleanupSsid = wifi.ssid
            appendLog(
                "WIFI: подключение к сети выполнено (вновь сохраненная системная сеть, зарегистрированный сеанс " +
                    "будет автоматически очищен в конце)",
            )
        }
        // Переключение сети опроса завершено (отключение старой сети/ассоциации/DHCP, фактическое измерение 2–10 с)
        repeat(30) {
            if (connectCancelled) return null
            Thread.sleep(300)
            if (shellWifiStatus().second == wifi.ssid) {
                val network = bindExistingCarWifi(connector, wifi.ssid)
                if (network != null) {
                    boundCarSsid = wifi.ssid
                    appendLog("WIFI: Connect-Network Подключено \"${wifi.ssid}\"")
                }
                return network
            }
        }
        appendLog(
            "WIFI: 9 с после подключения к сети, точка доступа автомобиля не переключается - невозможность нажатия " +
                "кнопки возврата (системная сеть зарегистрирована и сеанс очищается в конце)",
        )
        return null
    }

    /** Привяжите текущий подключенный Wi-Fi и дождитесь завершения привязки (совместное использование двойного пути). Возвращает связанную сеть или ноль. */
    private fun bindExistingCarWifi(
        connector: WifiCarPlayConnector,
        ssid: String,
    ): Network? {
        var boundOk = false
        // ssidHint: когда привязка отключена (запущена), уровень сеанса уведомляется через onNetworkLost — основной путь
        // — доступ к сети снизу.

        // Если уведомление не будет получено, карта героя остановится на «Подключено», и сеанс завершится до тех пор,
        // пока не истечет время сердцебиения.

        connector.bindCurrentWifi(ssid) { boundOk = it }
        val waitUntil = SystemClock.uptimeMillis() + 3_000
        while (!boundOk &&
            connector.currentNetwork == null &&
            SystemClock.uptimeMillis() < waitUntil &&
            !connectCancelled
        ) {
            Thread.sleep(100)
        }
        return connector.currentNetwork
    }

    /** Состояние завершения сеанса очищает системную сеть, хранящуюся в оболочке ([disconnect] используется совместно с push onEnded). */
    private fun cleanupShellFallbackNetwork() {
        val ssid = shellFallbackCleanupSsid ?: return
        shellFallbackCleanupSsid = null
        Bg.run("wifi-fallback-cleanup") {
            val r = Shell.exec("cmd wifi forget-network ${shellQuote(ssid)}", 5_000L)
            appendLog(
                "WIFI: очистите \"$ssid\", сохраненный в оболочке, в конце сеанса ok=" + r.ok +
                    (if (!r.ok) "(Чтобы удалить вручную, перейдите в «Настройки системы» > «WLAN»)." else ""),
            )
        }
    }

    /** Запускайте `cmd wifi` только тогда, когда Шизуку готова (такая же защита, как и при других вызовах оболочки). */
    private fun shellWifiReady(): Boolean = appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true

    /**
     * Если в системе сохранена точка доступа с таким же именем, пользователю будет предложено очистить ее
     * (**Несанкционированное удаление запрещено**).
     *
     * Зачем спрашивать, а не удалять напрямую: сохранение также имеет законные цели - пользователь может захотеть,
     * чтобы система автоматически подключалась к автомобилю,
     * В       или в автомобильной точке доступа есть Интернет (некоторые модели обеспечивают передачу данных).
     * Удаление влияет только на «Будет ли система глобально
     * «Автоматическое подключение» не имеет ничего общего с этим этапом подключения; выберите «Резерв», чтобы
     * продолжить обычное соединение.
     *
     * Запрашивает только один раз для каждого SSID и каждого процесса: повторная попытка после сбоя сопряжения или
     * повторного входа при передаче конечной точки произойдет здесь снова.
     * Повторяющиеся всплывающие окна блокируют соединение (фактическое тестирование состоится 18 октября 2026 г.). В
     * этом процессе запоминается выбор пользователя.
     *
     * блокируется до тех пор, пока пользователь не выберет (тайм-аут [JOIN_DIALOG_TIMEOUT_S] рассматривается как
     * «зарезервированный» — никаких разрушительных действий не выполняется).
     */
    private fun promptForgetSavedNetworkIfAny(ssid: String) {
        if (!forgetPromptHandled.add(ssid)) return
        val ids = savedNetworkIds(ssid)
        if (ids.isEmpty()) return
        appendLog(
            "WIFI: Точка доступа \"$ssid\" сохранена в системе (netId=${ids.joinToString(",")}）" +
                "—— Спросите пользователя, следует ли очистить (сохранение также может нормально подключаться)",
        )
        val forget =
            askWifiJoinChoice(
                DialogSpec(
                    title = "Точка доступа автомобиля сохранена.",
                    message =
                        "Сеть \"$ssid\" сохранена: телефон будет подключаться к ней автоматически. " +
                            "Без интернета это может повлиять на мобильные данные.\n\n" +
                            "«Удалить» отключает только автоподключение, не текущий сеанс. " +
                            "Если сеть нужна для интернета, выберите «Сохранить».",
                    positive = "Удалить сеть",
                    negative = "Сохранить",
                ),
            )
        if (connectCancelled) return
        when (forget) {
            true -> {
                ids.forEach { id ->
                    val result = Shell.exec("cmd wifi forget-network $id", 5_000L)
                    appendLog(
                        "WIFI: сохраненные на системном уровне точки доступа \"$ssid\" были удалены в соответствии с " +
                            "выбором пользователя (netId=$id ok=${result.ok})",
                    )
                }
            }

            false ->
                appendLog(
                    "WIFI: пользователь решает сохранить \"$ssid\", сохраненный системой, — продолжить " +
                        "подключение к сети конкретного приложения.",
                )

            // Никто не отвечает (пользовательский интерфейс не на переднем плане): нажмите «Удерживать» для обработки, и
            // сеть никогда не будет удалена без подтверждения пользователя.

            null ->
                appendLog(
                    "WIFI: Никто не отвечает, нужно ли очищать сохраненную сеть - обрабатывать как сохраненную " +
                        "(конфигурация пользователя не изменится)",
                )
        }
    }

    /** Проверьте идентификатор сети (`cmd wifi list-networks`), сохраненный системой и чей SSID точно такой же, как целевой. */
    private fun savedNetworkIds(ssid: String): List<Int> {
        val listing = Shell.exec("cmd wifi list-networks", 5_000L)
        if (!listing.ok) {
            appendLog("WIFI: невозможно прочитать сохраненную в системе сеть (exit=${listing.exitCode}) — пропустить проверку.")
            return emptyList()
        }
        return WifiSavedNetworks.idsOf(listing.output, ssid)
    }

    /** Загрузитесь и подождите, пока Wi-Fi не включится; true = включено, чтобы продолжить. */
    private fun waitForWifiEnabled(): Boolean {
        val open =
            askWifiJoinChoice(
                DialogSpec(
                    title = "Wi-Fi не включен",
                    message =
                        "Чтобы подключиться к автомобильной точке доступа, необходимо включить WLAN на мобильном " +
                            "телефоне. Открыть настройки Wi-Fi?",
                    positive = "Открыть настройки Wi-Fi",
                    negative = "Отмена",
                ),
            )
        if (connectCancelled) return false
        when (open) {
            true -> {
                openWlanSettings()
                uiHintSink?.invoke("Пожалуйста, включите переключатель Wi-Fi в настройках WLAN.")
            }

            false -> {
                phaseHint = "Отменено: для подключения к автомобильной точке доступа необходимо включить WLAN."
                appendLog("WIFI: отмена пользователя – автоматическое присоединение прервано (Wi-Fi не включен)")
                return false
            }

            null -> {
                phaseHint = "Wi-Fi не включен — для подключения к автомобильной точке доступа необходимо включить WLAN."
                appendLog("WIFI: Никто не отвечает на всплывающее окно – автоматическое подключение прервано (Wi-Fi не включен)")
                return false
            }
        }
        var waited = 0
        while (shellWifiDisabled() && waited < WLAN_WAIT_TIMEOUT_MS && !connectCancelled) {
            if (waited == 0) {
                phaseHint = "Подождите, пока включится Wi-Fi (до 120 секунд, прерывается при отмене соединения)"
                appendLog("WIFI: Подождите, пока пользователь включит Wi-Fi (до 120 секунд, прекращается при отмене соединения)")
            }
            Thread.sleep(2_000)
            waited += 2_000
        }
        if (connectCancelled) {
            phaseHint = "Соединение отменено"
            return false
        }
        if (shellWifiDisabled()) {
            phaseHint = "Wi-Fi не включен — невозможно подключиться к точке доступа в автомобиле."
            appendLog(
                "WIFI: время ожидания истекло, Wi-Fi все еще не включен – автоматическое подключение прервано, " +
                    "включите WLAN и подключитесь повторно.",
            )
            return false
        }
        return true
    }

    /** И «тихий путь», и сеть для конкретного приложения не удались: дайте пользователю повторить попытку или подключитесь вручную. */
    private fun promptSpecifierFailed(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "Не удалось подключиться к автомобильной точке доступа.",
                    message =
                        "Автоподключение \"${wifi.ssid}\" не удалось (возможно, точка доступа находится вне зоны " +
                            "действия или система отказала в соединении). \n\n" +
                            "«Повторить» автоматически добавит его снова (сначала используйте тихий путь, чтобы избежать " +
                            "всплывающих окон, а если это все равно не сработает, появится окно подтверждения системы);" +
                            "Вы также можете перейти к настройкам WLAN для подключения вручную (пароль скопируется в буфер обмена).",
                    positive = "Попробуйте еще раз",
                    negative = "Открыть настройки Wi-Fi",
                ),
            )
        if (connectCancelled) return
        when (choice) {
            true -> {
                when (val retry = joinCarWifi(wifi, shellReady)) {
                    is WifiCarPlayConnector.JoinResult.Joined -> Unit
                    WifiCarPlayConnector.JoinResult.Unsupported -> legacyJoinCarWifi(wifi, shellReady)
                    WifiCarPlayConnector.JoinResult.Unavailable -> {
                        phaseHint = "Не удалось подключиться к автомобильной точке доступа. Чтобы подключиться вручную, " +
                            "перейдите в настройки WLAN."
                        appendLog(
                            "WIFI: \"${wifi.ssid}\" не был добавлен после повторной попытки — перейдите в систему " +
                                "WLAN для подключения вручную.",
                        )
                    }
                }
            }

            false -> manualJoinCarWifi(wifi)

            null -> {
                phaseHint = "Не удалось подключиться к автомобильной точке доступа. Подключитесь повторно."
                appendLog("WIFI: Никто не отвечает на всплывающее окно – автоматическое подключение прервано")
            }
        }
    }

    /** Обложка руководства: поместите пароль в буфер обмена + перейдите к настройкам WLAN и опросите пользователя, чтобы узнать, подключены ли они. */
    private fun manualJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        copyHotspotPsk(wifi.passphrase)
        openWlanSettings()
        phaseHint = "Пожалуйста, подключите ${wifi.ssid}… в настройках WLAN."
        appendLog(
            "WIFI: Пользователь выбирает подключение вручную — подождите, пока WLAN подключится \"${wifi.ssid}\" (до 120 секунд);" +
                "Примечание. При подключении вручную точка доступа будет сохранена как системная сеть, что может " +
                "повлиять на передачу мобильных данных, если у нее нет доступа к Интернету.",
        )
        val manualDeadline = android.os.SystemClock.uptimeMillis() + WLAN_WAIT_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < manualDeadline) {
            if (connectCancelled) return
            Thread.sleep(5_000)
            if (shellWifiStatus().second == wifi.ssid) {
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
        }
        phaseHint = "Еще не подключен ${wifi.ssid} – автоматическое подключение прервано"
        appendLog("WIFI: тайм-аут ожидания соединения вручную – автоматическое подключение прервано")
    }

    /**
     * Старый путь (тип безопасности, который не может быть выражен с помощью WEP и других спецификаторов): `cmd wifi connect-network`
     * Сохранение и переключение на уровне системы. Обратите внимание, что при этом точка доступа будет сохранена как
     * системная сеть — это может повлиять на передачу мобильных данных при отсутствии Интернета.
     * предназначен только для совместимости.
     */
    private fun legacyJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (!shellReady) {
            phaseHint = "Телефон не подключен к автомобильной точке доступа. Чтобы подключиться к ${wifi.ssid}, перейдите в настройки WLAN."
            appendLog(
                ">>> Мобильный телефон не находится в автомобильной точке доступа \"${wifi.ssid}\", а Шизуку не готов " +
                    "(не может подключиться автоматически) ——" +
                    "Пожалуйста, перейдите в «Системные настройки»> «WLAN», чтобы подключиться вручную (пароль можно " +
                    "скопировать, нажав «Подключиться»).",
            )
            return
        }
        val current = shellWifiStatus().second
        if (current == wifi.ssid) {
            appendLog("WIFI: мобильный телефон уже находится в точке доступа в автомобиле \"$current\" — прямая связь")
            bindCarWifiAndRegister(wifi.ssid)
            return
        }
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "Подключить автомобильную точку доступа",
                    message =
                        "Автоматически подключится к точке доступа \"${wifi.ssid}\". Эта точка доступа не имеет доступа " +
                            "в Интернет, и система может автоматически переключиться обратно на исходный Wi-Fi." +
                            "\n\nПри возникновении отключения перейдите в настройки WLAN, чтобы активно подключиться к " +
                            "\"${wifi.ssid}\" (подключение вручную не будет переключено обратно).",
                    positive = "Продолжать присоединяться автоматически",
                    negative = "Открыть настройки Wi-Fi",
                ),
            )
        if (connectCancelled) return
        if (choice == false) {
            manualJoinCarWifi(wifi)
            return
        }
        if (choice == null) {
            phaseHint = "Не удалось подключиться к автомобильной точке доступа. Подключитесь повторно."
            appendLog("WIFI: Никто не отвечает на всплывающее окно – автоматическое подключение прервано")
            return
        }
        phaseHint = "Автоматическое подключение к автомобильной точке доступа ${wifi.ssid}…"
        appendLog(
            ">>> Текущий Wi-Fi: ${current ?: "не подключено"} (не автомобиль) — " +
                "переключение на \"${wifi.ssid}\" через Shizuku",
        )
        if (connectCancelled) return
        val result =
            Shell.exec(
                WifiConnectCommand.build(wifi.ssid, wifi.passphrase, wifi.securityType),
                10_000L,
            )
        if (!result.ok) {
            phaseHint = "Не удалось автоматически подключиться к точке доступа. Чтобы подключиться к ${wifi.ssid}, " +
                "перейдите в настройки WLAN."
            appendLog(
                "WIFI: ошибка подключения к сети (${result.output.take(80).ifEmpty { "exit=${result.exitCode}" }}）" +
                    "—— Пожалуйста, перейдите в «Настройки системы»> «WLAN», чтобы подключиться вручную (пароль можно " +
                    "скопировать, нажав «Подключиться»)",
            )
            return
        }
        phaseHint = "Подключение к автомобильной точке доступа ${wifi.ssid}... Если она снова переключена на исходный " +
            "Wi-Fi, подключитесь к WLAN вручную."
        appendLog("WIFI: подключение к сети инициировано (автоматическое сохранение сети, ожидание переключения...)")
        // Переключение системы асинхронное (отключить текущую сеть/сканировать/связать/DHCP, 2–10 секунд): опрос для подтверждения SSID.
        repeat(20) {
            if (connectCancelled) {
                appendLog("WIFI: автоматическое подключение отменено (пользователь отключен)")
                return
            }
            val ssid = shellWifiStatus().second
            if (ssid == null) {
                // Команда состояния недоступна (системы, отличные от AOSP): она сводится к попытке прямого подключения (без проверки SSID)
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            if (ssid == wifi.ssid) {
                appendLog("WIFI: переключено на точку доступа в автомобиле ${wifi.ssid}")
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            Thread.sleep(800)
        }
        phaseHint = "Не удалось припарковаться в точке доступа. Чтобы подключиться вручную, перейдите в настройки WLAN ${wifi.ssid}"
        uiHintSink?.invoke(
            "Не удалось припарковаться в точке доступа. Чтобы активировать подключение ${wifi.ssid}, " +
                "перейдите в настройки WLAN.",
        )
        appendLog(
            ">>> Тайм-аут автоматического подключения к Wi-Fi (может быть переключен системой обратно на исходный Wi-Fi) ——" +
                "Перейдите в «Системные настройки» > «Ручное подключение WLAN \"${wifi.ssid}\"» (ручное подключение не " +
                "будет автоматически переключено обратно).",
        )
    }

    /**
     * привязывает текущий Wi-Fi (должна быть точка доступа автомобиля) и перерегистрирует трансляцию ctrl; тайм-аут
     * привязки дает рекомендации.
     *
     * Действие привязки       используется для сериализации [wifiBindLock]: могут запускаться как поток
     * автоматического соединения, так и поток передачи конечной точки,
     * Одновременные вызовыbindCurrentWifi освобождают друг друга (трансляция mDNS немедленно прекращается при отмене привязки).
     *
     * Окно ожидания [WIFI_BIND_WAIT_S] должно быть достаточно длинным: onAvailable `requestNetwork(WIFI)`
     * Когда «телефон не подключен ни к какому Wi-Fi, а пользователь вручную присоединяется к точке доступа в
     * настройках системы», необходимо дождаться фактического
     * будет вызван обратно только после завершения ассоциации +DCHP. Старое значение 5 с является фатальным
     * соревнованием в реальном тесте Audi - решение 20:02:09.352.
     * «Не в точке доступа» прервал весь сеанс, и в 20:02:09.467 (**через 116 мс**) прибыл onAvailable и
     * успешно привязан; пользователь вынужден ждать еще одного раунда переподключения BT (+13 с). Лучше ждать без
     * перерыва, чем симулировать неудачу.
     *
     * @return Успешно ли подключена технологическая сеть?
     */
    private fun bindCarWifiAndRegister(ssid: String): Boolean {
        var bound = false
        synchronized(wifiBindLock) {
            // Другой путь уже привязан к той же точке доступа: не повторяйте его снова Release+requestNetwork
            if (boundCarSsid == ssid && wifiConnector?.currentNetwork != null) {
                bound = true
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                wifiConnector?.bindCurrentWifi(ssid) { ok ->
                    bound = ok
                    latch.countDown()
                }
                // Ожидание сегмента: в течение этого периода, если пользователь отменяет подписку, он должен иметь возможность
                // немедленно выйти, не дожидаясь полного окна.

                val deadline = android.os.SystemClock.uptimeMillis() + WIFI_BIND_WAIT_S * 1_000
                while (!connectCancelled &&
                    bound.not() &&
                    android.os.SystemClock.uptimeMillis() < deadline
                ) {
                    if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                }
                if (bound) boundCarSsid = ssid
            }
        }
        if (bound && !connectCancelled) {
            phaseHint = "Уже присоединился к автомобильной точке доступа"
            // Ключевое время: зарегистрируйтесь после завершения связыванияProcessToNetwork, чтобы автомобиль мог видеть трансляцию ctrl.
            ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            appendLog(
                ">>> Wi-Fi привязан к точке доступа автомобиля, трансляция управления перерегистрирована, ожидание " +
                    "приглашения управления автомобилем (0x4301 будет возвращено позже)",
            )
        } else if (!connectCancelled) {
            phaseHint = "Не удалось привязать сетевой канал автомобильной точки доступа $ssid."
            appendLog(
                ">>> Ожидание ${WIFI_BIND_WAIT_S} с, но все еще не получен дескриптор сети Wi-Fi \"$ssid\"——" +
                    "Пожалуйста, подтвердите, что WLAN подключен к точке доступа, а затем повторно подключитесь.",
            )
        }
        return bound
    }

    /**
     * Всплывающее окно  запрашивает пользователя (блокирует вызывающий поток до момента выбора/закрытия/таймаута 90 секунд):
     * true=положительный; ложь = отрицательный; null=нет ответа (звонивший решил скрыть правду).
     */
    private fun askWifiJoinChoice(spec: DialogSpec): Boolean? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var choice: Boolean? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val sink = dialogSink
            if (sink == null) {
                // Нет хоста пользовательского интерфейса (активность уничтожает окно): скорее прервет работу, чем решит
                // присоединиться от имени пользователя по умолчанию.

                latch.countDown()
                return@post
            }
            sink(spec) { result ->
                choice = result
                latch.countDown()
            }
        }
        latch.await(JOIN_DIALOG_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        return choice
    }

    private fun openWlanSettings() {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    ctx.startActivity(
                        android.content
                            .Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    /** Пароль точки доступа копируется в буфер обмена (вставляется вручную при подключении к странице WLAN), и пользователю предлагается это сделать. */
    private fun copyHotspotPsk(passphrase: String) {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm =
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("carplay-wifi", passphrase),
                    )
                    uiHintSink?.invoke("Пароль точки доступа скопирован (биты ${passphrase.length}) — просто вставьте его в поле пароля.")
                }
            }
        }
    }

    /**
     * WiFi-переключатель с подключенным в данный момент SSID (один запрос `cmd wifi status`).
     * Форма вывода       следующая: `Wifi is enabled` + `Wifi is connected to "Redmi K40"` (нет такой строки, если она не подключена).
     */
    private fun shellWifiStatus(): Pair<Boolean, String?> {
        val result =
            Shell
                .exec("cmd wifi status", 5_000L)
        if (!result.ok) return true to null
        val enabled = !result.output.contains("Wifi is disabled")
        val ssid = Regex("connected to \"(.*)\"").find(result.output)?.groupValues?.get(1)
        return enabled to ssid
    }

    private fun shellWifiDisabled(): Boolean = !shellWifiStatus().first

    /** Литерал оболочки POSIX, заключенный в одинарные кавычки (SSID/пароль может содержать пробелы и специальные символы). */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\''") + "'"

    /** Чтобы воспроизвести состояние «Управление BT завершено»: Отключите сеанс управления iAP2 любого текущего пути (DiPlay реагирует на RTSP только в этом состоянии). */
    private fun detachActiveBtSessions() {
        bluetoothServer?.detachCurrentSession()
        bluetoothClient?.detachCurrentSession()
    }

    /**
     * Приглашение к управлению машиной ожидания (`GET /ctrl-int/1/connect`) — триггерная точка соединения для кошачьей игры.
     *
     * Приглашение       означает «автомобиль обнаружил нас по Wi-Fi и открыл порт AirPlay». В это время связь
     * AirPlay не будет отклонен системой Connection. В старой реализации использовался фиксированный интервал
     * 1,6 секунды, чтобы сделать ставку на то, что приглашение получено:
     * Когда машина быстрая, ожидания не требуется, а когда медленная, ее не хватает. Это управляется событиями, и
     * если mDNS обнаружил `_airplay._tcp`
     * (подтверждено подключение порта) также немедленно освобождается, чтобы не ждать полного окна, когда старая
     * модель не отправляет приглашения.
     *
     * @return true = приглашение получено/конечная точка обнаружена; false = окно ожидания исчерпано
     */
    private fun awaitCtrlInvite(
        ssid: String,
        timeoutMillis: Long,
    ): Boolean {
        val advertiser = ctrlAdvertiser
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        val startedAt = android.os.SystemClock.uptimeMillis()
        var lastLogAt = startedAt
        var reannounced = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (connectCancelled) return false
            val invites = advertiser?.inviteCount ?: 0
            if (invites > 0) {
                appendLog(
                    "CTRL: ✓ Получено приглашение на транспортное средство " +
                        "(${android.os.SystemClock.uptimeMillis() - startedAt}ms, время $invites)" +
                        "—— Подключите AirPlay сейчас",
                )
                return true
            }
            if (endpointBrowser?.awaitEndpoint(0) != null) {
                appendLog(
                    "CTRL: найден _airplay._tcp (приглашение не получено, поведение старой модели) — подключайтесь напрямую к AirPlay",
                )
                return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            // Фактический тест кошачьей игры: объявление Bonjour на iPhone часто игнорируется автомобилем — оно предшествует
            // каждому приглашению

            // Force_announce. Вот переиздание в ожидании, чтобы осветить ситуацию «объявления теряются в момент подключения к сети».
            if (!reannounced && now - startedAt >= 2_000) {
                reannounced = true
                appendLog(
                    "CTRL: приглашение не получено в течение 2 секунд — вынуждено объявить повторно (то же самое, " +
                        "что и в кошачьей игре Force_announce)",
                )
                ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            }
            if (now - lastLogAt > 1_200) {
                appendLog("CTRL: Ожидание приглашения управления автомобилем... (Ожидание ${now - startedAt}ms)")
                lastLogAt = now
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        appendLog(
            "CTRL: ${timeoutMillis}ms Приглашение не получено — нажмите таймаут, чтобы продолжить соединение " +
                "(автомобиль может поехать по старому маршруту обнаружения)",
        )
        return false
    }

    // ---- Следующее тело метода импортируется из MainActivity как есть (логика сеанса не зависит от Activity) ----

    /**
     * Аудиоканал  (мобильный телефон → автомобиль, RTP через UDP): тип настройки = 100, согласование → захват
     * воспроизведения → передача пакета ChaCha.
     *
     * использует MediaProjection (авторизованный источник AudioPlaybackCapture) и требует от пользователя «Начать подключение».
     * Во всплывающем окне       выберите «Включить звук» — одновременная потоковая передача звука и видео
     * значительно увеличит нагрузку (реальные измерения вызовут зависания/отключения).
     *
     * @return Действительно ли аудиоканал запущен (на основании этого вызывающая сторона решает, следует ли повторно
     * выполнить RECORD для активации вновь добавленного потока)
     */
    fun startAudioChannel(
        context: Context,
        client: AirPlayClient,
        sharedSecret: ByteArray?,
        host: String?,
    ): Boolean {
        if (!audioEnabledForSession) {
            appendLog(
                "АУДИО: Звук со стороны автомобиля не включается для этого подключения (выбор всплывающего окна " +
                    "«Начать подключение») — только push-видео",
            )
            return false
        }
        if (sharedSecret == null) {
            // Сеанс открытого текста (et=NONE) не имеет общего ключа для получения выходного ключа DataStream:
            // Вытолкнутую машину RTP невозможно расшифровать. Лучше не давить, чем создавать иллюзию, что «посылка есть, а звука нет».
            appendLog("АУДИО: сеанс открытого текста (неспаренный), нет общего ключа — аудиоканал пропущен")
            return false
        }
        if (host == null) {
            appendLog("АУДИО: адрес приемника недоступен, аудиоканал пропущен.")
            return false
        }
        val projection = CarPlayService.mediaProjection
        if (projection == null) {
            appendLog(
                "АУДИО: Отсутствие разрешения на кастинг (захват воспроизведения зависит от MediaProjection) - аудиоканал не запускается." +
                    "Теперь нажмите «Подать заявку на разрешение на зеркалирование экрана», чтобы немедленно включить " +
                    "его (повторное подключение не требуется); Обратите внимание, что «отключение» приведет к " +
                    "остановке службы и аннулированию разрешения на зеркалирование экрана.",
            )
            return false
        }
        return try {
            // Идентификация автомобиля → используется только для постоянного запоминания того, «какие аудиоустройства этого
            // автомобиля пропустили гром».

            val identity = client.receiverIdentity()
            // Параметры инкапсуляции звука окончательно определены (см. AudioTuning), и переключение больше не предусмотрено.
            val tuning = AudioTuning.RAW_SPF
            val entry = AudioProfileMemory.load(context.applicationContext, identity)
            if (entry.disabled) {
                // Раньше опция «Все передачи приведут к удалению ссылки» автоматически возвращалась к отправке только видео, и
                // пользователям предлагалось нажать на всплывающее окно.

                // «Очистить память». Теперь аудиоканал изменен на AAC-LC, и реальный тест может стабильно работать в течение 147 секунд.
                // Старый вывод уже не вызывает доверия: раз пользователь активно выбрал «Включить звук», просто очистите память
                // и повторите попытку.

                AudioProfileMemory.clearAll(context.applicationContext)
                appendLog(
                    "АУДИО: У этого автомобиля ($identity) есть старая память о «разблокировке всех передач». Он " +
                        "был автоматически удален и повторен.",
                )
            }
            val candidates = client.audioCandidates(preferCompat = audioPreferCompat)
            val ordered = AudioProfileMemory.order(candidates, entry)
            if (ordered.isEmpty()) {
                appendLog("АУДИО: аудиофайл недоступен (candidate=${candidates.map { it.label }}) – только отправить видео")
                return false
            }
            appendLog(
                "АУДИО: кандидат на аудиооборудование =${ordered.map { it.label }}" +
                    "(Память этого автомобиля: ${AudioProfileMemory.describe(entry)};" +
                    "пакет=${tuning.detail})",
            )
            // Сбой SETUP не нарушит сеанс (машина просто не принимает запрос на этот поток), поэтому кандидат удаляется в этом сеансе.
            // Попробовав однажды, вам не придется ждать следующего подключения. Это критически важно для текущего сценария:
            // 102/AAC-LC против 100/PCM

            // Есть два совершенно разных пути. Когда первое не принимается, вам следует немедленно вернуться ко второму,
            // вместо того, чтобы позволять пользователю подключаться понапрасну.

            var chosen: AudioProfile? = null
            var streamInfo: AirPlayClient.AudioStreamInfo? = null
            for (candidate in ordered) {
                val result = client.runAudioStream(sharedSecret, candidate, tuning)
                if (result != null) {
                    chosen = candidate
                    streamInfo = result
                    break
                }
                // Отказ от SETUP не означает "несовместимость машины с машиной": это может быть просто арбитраж ресурсов/дрожание сети.
                // Звук всей машины не может быть приговорен к смертной казни на основании этого (отключение настройки будет
                // распознавать только «несвязанные» доказательства).

                AudioProfileMemory.recordRejected(
                    context.applicationContext,
                    identity,
                    candidate.key,
                    ordered.map { it.key }.toSet(),
                    markExhausted = false,
                )
                appendLog(
                    "АУДИО: Не удалось выполнить согласование для механизма ${candidate.label} — в этом сеансе " +
                        "продолжаются попытки использовать следующий механизм.",
                )
            }
            val profile = chosen
            val audio = streamInfo
            if (profile == null || audio == null) {
                appendLog(
                    "АУДИО: согласование не удалось для всех файлов-кандидатов (всего файлов ${ordered.size}) – на " +
                        "этот раз будут отправлены только видео.",
                )
                return false
            }
            activeAudioProfile = profile
            activeAudioReceiverKey = identity
            activeAudioStartedAt = android.os.SystemClock.uptimeMillis()
            val sender =
                AudioSender(
                    host = host,
                    dataPort = audio.dataPort,
                    streamKey = audio.streamKey,
                    log = { line -> appendLog(line) },
                    context = context.applicationContext,
                    sampleRate = audio.sampleRate,
                    codec = profile.codec,
                    streamType = profile.streamType,
                    adtsFrames = tuning.adts,
                    // Целевой порт пакета синхронизации: отправляется транспортным средством в ответе SETUP. Без него не будет
                    // привязки RTP↔NTP,
                    // Принимающая сторона может только «угадывать на основе времени прибытия» и строго реализовывать это без
                    // планирования трансляции (фактическое тестирование состоится 20 октября 2026 г.).
                    controlPort = audio.controlPort,
                )
            audioSender = sender
            if (!sender.start(projection)) {
                audioSender = null
                activeAudioProfile = null
                activeAudioStartedAt = 0L
                false
            } else {
                true
            }
        } catch (error: Exception) {
            appendLog("АУДИО: Исключение запуска канала – ${error.message}")
            audioSender = null
            activeAudioProfile = null
            activeAudioStartedAt = 0L
            false
        }
    }

    /**
     * конец сеанса**Атрибуция отключения аудио**（2026-10-19 Honda HFT / Alpine Добавлено фактическое измерение）。
     *
     * явление：Избранный「видео+Аудио」После сеанса можно установить、Видео тоже нормальное，Но звук начинает
     * транслироваться примерно через 6s Весь задний руль
     * AirPlay демонтаж сеанса（`EVENT: Конец чтения - Connection reset`），Только отправка видео будет стабильной。
     * Сторона автомобиля нам не скажет「Какой аудиофайл несовместим?」，Вы можете использовать только время выживания, чтобы сделать вывод：
     * - После начала потоковой передачи звука**Не полный** [AUDIO_PROFILE_PROBE_MS] Вся цепочка была сорвана →
     * Прибор признан несовместимым，
     * Записано в список транспортных средств（Все шестерни приговорены к смерти или установлены
     * disabled，внизОтправляйте только видео напрямую）；
     * - Полный → Отметить это снаряжение как доступное，внизПредпочтительно вторичное соединение напрямую。
     *
     * Обратите внимание только на「Звук действительно」атрибуция времени；Чистый видеосеанс завершается без перезаписи памяти.。
     */
    private fun attributeAudioTeardown() {
        val profile = activeAudioProfile ?: return
        val startedAt = activeAudioStartedAt
        val identity = activeAudioReceiverKey
        // Link Watchdog Guard (23 октября 2026 г., владелец, исправление журнала 0.1.17): MIB3 активирован, но контролирует сеанс
        // Демонтирован до завершения = ~10 с сторожевой таймер связи между транспортным средством и машиной (10,18 с
        // после ЗАПИСИ по сравнению с 10,17 с для версии 0.1.9).

        // Тот же стиль), независимо от аудиооборудования. Если его не защитить, файл t102/media будет ошибочно записан
        // как «несовместимый», и в следующем раунде

        // Автоматически переключается на совместимую передачу - автомобиль и машина вообще не связаны на этой дороге
        // (три жестких ограничения AGENTS audio),

        // Молчит навсегда.
        currentClient?.let { client ->
            if (client.mib3LinkArmed && !client.mib3ControlSessionDone) {
                appendLog(
                    "АУДИО: сессия была прервана автомобилем до завершения согласования соединения (режим " +
                        "наблюдения ~10 с) – аудиоустройство не записывается",
                )
                activeAudioProfile = null
                activeAudioStartedAt = 0L
                return
            }
        }
        val lived = if (startedAt > 0) android.os.SystemClock.uptimeMillis() - startedAt else -1L
        // Память этого раунда скоро истечет: при следующем подключении нажмите последнюю память, чтобы снова выбрать файл.
        activeAudioProfile = null
        activeAudioStartedAt = 0L
        if (startedAt == 0L || identity.isBlank()) return
        val ctx = appContext?.applicationContext
        if (lived >= AUDIO_PROFILE_PROBE_MS) {
            AudioProfileMemory.recordWorking(ctx, identity, profile.key)
            appendLog("АУДИО: снаряжение ${profile.label} живо ${lived / 1000} с — записано как снаряжение, доступное для этой машины")
            return
        }
        val allKeys = audioCandidateKeys(identity)
        AudioProfileMemory.recordRejected(ctx, identity, profile.key, allKeys)
        val after = AudioProfileMemory.load(ctx, identity)
        appendLog(
            "АУДИО: ⚠ Gear ${profile.label} начинает течь ${lived / 1000}, а затем машина разбирает всю сессию——" +
                "Установлено, что данное транспортное средство несовместимо с передачей (уже записанной в списке)." +
                if (after.disabled) {
                    "Все шестерни несовместимы: при следующем подключении автоматически будет проталкиваться только " +
                        "видео (со стороны автомобиля звука нет)."
                } else {
                    "В следующий раз, когда вы нажмете «Начать подключение», произойдет автоматическое переключение на " +
                        "следующий файл; вы также можете напрямую выбрать «Включить звук (совместимый файл)»."
                },
        )
        uiHintSink?.invoke(
            if (after.disabled) {
                "Звук этой машины не совместим. При следующем подключении автоматически будет отправлено только видео."
            } else {
                "Это аудиооборудование вызывает отключение и записано; повторное подключение приведет к автоматическому " +
                    "переключению передач."
            },
        )
    }

    /** «Все возможные ключи передач» на автомобиле используются для определения того, все ли передачи примерены. */
    private fun audioCandidateKeys(identity: String): Set<String> =
        runCatching {
            val client = currentClient ?: return@runCatching emptySet()
            val preferred = client.audioCandidates(preferCompat = audioPreferCompat).map { it.key }
            val alternate = client.audioCandidates(preferCompat = !audioPreferCompat).map { it.key }
            (preferred + alternate).toSet()
        }.getOrDefault(emptySet())

    /**
     * Режимы Изменено планирование повторного запроса ресурсов (управляется событием завершения сеанса управления MIB3).
     *
     * Время проверки длинной трансляции AADisplay r17 на Audi: после аутентификации + пакетная передача ** сначала +1,5 с, +4 с/+12 с/
     * +30 с каждый повторяется один раз** (абсолютное время). Если оператор попадает на 0xAA00/0xAA05, он будет сравниваться с iAP POST.
     * захватывает канал управления, и Audi умирает примерно через 12 секунд (исходный комментарий AADisplay) -
     * поэтому событие можно завершить только всплеском
     * срабатывает и не может быть отправлен все сразу после установления сеанса. 082446 Исправлено: второй сеанс
     * управления сбросит ресурсы автомобиля.
     * Арбитраж, срок действия предыдущего оператора 200 OK истек к моменту завершения кластера подписки, и машина ожидает нового.
     */
    @Volatile
    private var modesReclaimEpoch = 0L

    private fun scheduleModesReclaim(client: AirPlayClient) {
        val epoch = ++modesReclaimEpoch
        Bg.run("modes-reclaim") {
            val start = android.os.SystemClock.uptimeMillis()
            for (atMs in longArrayOf(1_500, 4_000, 12_000, 30_000)) {
                while (true) {
                    if (epoch != modesReclaimEpoch || client.cancelled || connectCancelled) return@run
                    val remain = atMs - (android.os.SystemClock.uptimeMillis() - start)
                    if (remain <= 0) break
                    Thread.sleep(minOf(remain, 200L))
                }
                if (epoch != modesReclaimEpoch || client.cancelled || connectCancelled) return@run
                if (!client.assertModes()) return@run
                appendLog("AIRPLAY: обновлен ресурс modeChanged (+${atMs / 1000} с после пакета, время AADisplay r17)")
            }
        }
    }

    /**
     * Завершение конечной точки : установите приоритет поглощения адреса, разрешенного mDNS, а затем решите, следует ли ждать/восстановить.
     *
     * Ключ       (журнал реального автомобиля Audi 4851 19 октября 2026 г.): только 0x4301
     * `fe80::3ca8:64ff:fe7c:293e` (локальный канал IPv6), а `_airplay._tcp` разрешает
     * `10.174.189.1:7000` —— Транспортное средство и машина фактически используют IPv4. **Вы не можете перестать
     * смотреть 0x4301 только потому, что он поддерживает IPv6.
     * mDNS**: это приведет к потере единственного аутентифицированного пути IPv4. Здесь мы сначала выполняем слияние
     * кэша с «нулевым ожиданием».
     * Если у вас есть IPv4, используйте его в соответствии с приоритетом EndpointHosts (IPv4 → IPv6); если кеш
     * промахивается, подождите, сколько потребуется.
     */
    private fun resolveEndpointHost(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint {
        var effective = endpoint
        val cached = endpointBrowser?.awaitEndpoint(0)
        if (cached != null) {
            effective = mergeBrowserEndpoint(effective, cached, source, needPort = effective.airPlayPort <= 0)
        }
        val needHost = !EndpointHosts.hasUsable(effective.ipAddresses)
        val needPort = effective.airPlayPort <= 0
        if (!needHost && !needPort) return effective

        phaseHint = "mDNS разрешает адреса автомобилей и машин…"
        appendLog(
            ">>> [$source] Конечная точка 0x4301 неполная (port=${effective.airPlayPort} " +
                "ips=${effective.ipAddresses}), возьмите машину mDNS _airplay._tcp…",
        )
        // Используйте кеш резидентного средства обнаружения (фоновое обнаружение начинается при подключении к сети, и
        // обычно в это время достигается попадание).

        // Раньше обнаружение и жёсткая повторная попытка занимали 3×6 с: ожидание было напрасным, когда mDNS машины был
        // стабильным, и полные 18 с, когда он был нестабильным.

        // Фильтрация сегментов сети (для предотвращения ошибок связи с сетевым приемником macOS) осуществляется
        // единообразно с помощью функции принятия браузера.

        val info =
            endpointBrowser?.awaitEndpoint(ENDPOINT_RESOLVE_WAIT_MS) ?: run {
                // Итог: IP-адрес автомобиля в сцене горячей точки = шлюз (на самом деле Audi измеряет 0,1, но шлюз является более общим).
                // mDNS нестабильно работает в некоторых сетях/моделях (mDNS реального автомобиля и машины, протестированных 11
                // октября 2026 г., иногда не работает);

                // Адрес шлюза + порт по умолчанию 7000 для прямого подключения, порт AirPlay является фиксированным соглашением.
                val gateway = wifiConnector?.gatewayAddress()
                if (gateway != null && EndpointHosts.candidates(listOf(gateway)).isNotEmpty()) {
                    appendLog(
                        ">>> [$source] mDNS не найден, используйте адрес шлюза $gateway:7000 (шлюз точки доступа " +
                            "автомобиля = машина автомобиля)",
                    )
                    return effective.copy(
                        ipAddresses = listOf(gateway) + effective.ipAddresses,
                        airPlayPort = if (needPort) 7000 else effective.airPlayPort,
                    )
                }
                appendLog(
                    ">>> [$source] mDNS не обнаружил службу AirPlay в автомобиле (возможно, в автомобиле еще не " +
                        "запущен беспроводной сеанс)",
                )
                return effective
            }
        return mergeBrowserEndpoint(effective, info, source, needPort)
    }

    /**
     * Включите результат разрешения mDNS в конечную точку: адрес ** добавляется в начале ** (EndpointHosts будет
     * IPv4 → Глобальный IPv6 → Перестановка IPv6 в локальном канале, порядок влияет только на один и тот же тип).
     * Остальные поля заполняются только в случае их отсутствия (авторитетное значение 0x4301 не учитывается).
     */
    private fun mergeBrowserEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        info: android.net.nsd.NsdServiceInfo,
        source: String,
        needPort: Boolean,
    ): Iap2SenderSession.CarPlayEndpoint {
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
            appendLog(">>> [$source] mDNS преобразуется в адрес автомобиля $address:${info.port}")
        }
        if (needPort && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        // Открытый ключ TXT (для проверки пары; если 0x4301 отсутствует, вы можете получить его только здесь)
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        return result
    }

    /**
     * Вход в горячее обновление, когда 0x4301 переиздается после  Phase 1 (окно CarPlay открывается поздно).
     *
     * имеет три формы собственности:
     * — потоковая передача: резервная конечная точка, игнорируется (та же защита, что и у [onCarPlayEndpoint], во
     * избежание прерывания работоспособных сеансов);
     * — выполняется передача обслуживания/сопряжение: горячее обновление текущей конечной точки AirPlayClient, и
     * новый адрес будет использоваться в следующем раунде цикла сопряжения;
     * Если клиент еще не создан (он все еще привязан к сети/ждет приглашений), он будет временно сохранен и
     * объединен с потоком сопряжения при его запуске;
     * — предыдущий раунд не выполнен (сеанс не запущен): повторите полную передачу управления с новой конечной точкой — Audi MMI 9310
     * Фактическое измерение 0x4301 поступает через 28 секунд после исчерпания 120-секундного окна сопряжения. Старая
     * реализация отбрасывает его как мусор журнала.
     * Автомобиль и машина будут отключены, если никто не будет подключен в течение 15 секунд, а производительность
     * будет «автоматически отключена после подключения».
     */
    private fun onLateEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        if (connectCancelled) {
            appendLog(">>> [$source] 0x4301 Опоздал (соединение отменено) – игнорировать")
            return
        }
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 0x4301 Позднее (нажатие) — игнорировать избыточные конечные точки")
            return
        }
        val client = currentClient
        if (sessionStarting.get()) {
            if (client != null && client.pairingInProgress) {
                // Объединять, а не заменять: 0x4301 может не поддерживать IPv4 (зависимость от разрешения конечной точки
                // измерена Audi MMI 9310).

                // mDNS/Gateway), прямая замена сотрет разрешенный адрес, и цикл сопряжения не сможет подключиться.
                val merged = preferEndpoint(endpoint, client.currentEndpoint)
                val changed = client.updateEndpoint(merged, "0x4301 Поздно")
                appendLog(
                    if (changed) {
                        ">>> [$source] 0x4301 Позднее (выполняется сопряжение) — конечная точка была обновлена в горячем " +
                            "режиме и снова попытается использовать новый адрес в следующем раунде."
                    } else {
                        ">>> [$source] 0x4301 поздно (выполняется сопряжение) — без изменений конечной точки, игнорируется"
                    },
                )
            } else {
                pendingEndpointUpdate = endpoint
                appendLog(
                    ">>> [$source] 0x4301 Позднее (при передаче еще не создан клиент сопряжения) — временное " +
                        "хранилище, используемое при запуске сопряжения",
                )
            }
            return
        }
        // Если пользователь отключен (компонент BT остановлен), последнее сообщение не должно использоваться для возобновления сеанса.
        if (bluetoothClient?.isRunning != true && bluetoothServer?.isRunning != true) {
            appendLog(">>> [$source] 0x4301 Поздно (соединение Bluetooth удалено) – игнорировать")
            return
        }
        appendLog(
            ">>> [$source] 0x4301 Позднее прибытие (предыдущий раунд закончился) — перезапуск раунда соединения на " +
                "основе новой конечной точки",
        )
        onCarPlayEndpoint(endpoint, "$source/0x4301 Поздно")
    }

    /** Включите позднюю конечную точку, временно сохраненную во время передачи обслуживания, во вновь созданный клиент (окно гонки между созданием клиента и получением 0x4301). */
    private fun drainPendingEndpoint(client: AirPlayClient) {
        val late = pendingEndpointUpdate ?: return
        pendingEndpointUpdate = null
        if (client.updateEndpoint(preferEndpoint(late, client.currentEndpoint), "Опоздание во время передачи 0x4301")) {
            appendLog("AIRPLAY: конечная точка 0x4301 соединительного окна была объединена с парным клиентом.")
        }
    }

    /**
     * Задача наблюдения за период ожидания нулевой конечной точки  (машина заявляет, что 0x4301 будет отправлен, но еще не прибыл):
     * 1. Опрос текущего Wi-Fi каждые 5 секунд - пользователь вручную подключается к точке доступа автомобиля, а
     * затем всплывающее окно подтверждает, и после подтверждения используется SSID.
     * Синтетическая конечная точка       повторяет [onCarPlayEndpoint] (нет порта → крышка mDNS/шлюза);
     * 2. Опоздавший 0x4301 уступает место и выходит при запуске нового раунда передачи обслуживания (sessionStarting сбрасывается);
     * 3. Дальше [PENDING_START_SESSION_WAIT_MS] прогресса нет: сохраните журнал и удалите ссылку поддержания активности.
     * позволяет циклу переподключения BT взять на себя управление (единственное отличие от старого «отключения
     * восьмерки» - это длина окна).
     */
    private fun watchForPendingStartSession(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        Bg.run("pending-start-session-watch") {
            var promptedSsid: String? = null
            val deadline = android.os.SystemClock.uptimeMillis() + PENDING_START_SESSION_WAIT_MS
            while (!connectCancelled && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(5_000)
                if (connectCancelled) return@run
                // Позднее прибытие 0x4301 вызвало новый раунд передачи - задача наблюдения была выполнена.
                if (sessionStarting.get()) return@run
                val ssid = shellWifiStatus().second ?: continue
                // Один и тот же SSID будет запрошен только один раз (пользователь не будет вас беспокоить повторно после выбора
                // «Продолжить ждать»); смените сеть и спросите еще раз.

                if (ssid.isEmpty() || promptedSsid == ssid) continue
                promptedSsid = ssid
                val choice =
                    askWifiJoinChoice(
                        DialogSpec(
                            title = "Обнаружен подключенный $ssid",
                            message =
                                "Автомобильный аппарат не отправил информацию о соединении (0x4301) через Bluetooth. " +
                                    "Использовать ли текущую сеть \"$ssid\" в качестве автомобильной точки доступа?" +
                                    "Попробуйте напрямую подключиться к mDNS/шлюзу? (Нажмите «Продолжить ожидание», " +
                                    "чтобы поддерживать связь Bluetooth и дождаться, пока автомобиль доставит его)",
                            positive = "Нажмите на точку доступа автомобиля, чтобы продолжить.",
                            negative = "Продолжайте ждать",
                        ),
                    )
                if (choice == true && !connectCancelled && !sessionStarting.get()) {
                    appendLog(
                        ">>> [$source] Пользователь подтверждает, что текущая сеть \"$ssid\" является точкой " +
                            "доступа «автомобиль-машина» — повторно подключитесь к синтетической конечной точке.",
                    )
                    onCarPlayEndpoint(endpoint.copy(ssid = ssid), "$source/Ручной доступ к сети")
                    return@run
                }
            }
            if (!connectCancelled && !sessionStarting.get()) {
                appendLog(
                    ">>> [$source] Ожидание нулевой конечной точки: время ожидания ${PENDING_START_SESSION_WAIT_MS / 60_000} минут -" +
                        "Машина не выдала 0x4301. Удалите ссылку поддержания активности и позвольте циклу повторного " +
                        "подключения взять на себя управление. Пожалуйста, экспортируйте журнал отзывов.",
                )
                detachActiveBtSessions()
            }
        }
    }

    /**
     * Обновление конечной точки  для цикла сопряжения (неблокируемый): кеш браузера `_airplay._tcp` только для чтения,
     * будет объединен с такими полями, как IP/порт/открытый ключ. Возвращает значение null = нет новой информации
     * (цикл сопряжения продолжается с текущей конечной точки).
     */
    private fun enrichEndpointFromBrowser(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint? {
        val info = endpointBrowser?.awaitEndpoint(0) ?: return null
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
        }
        if (result.airPlayPort <= 0 && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        if (result == endpoint) return null
        appendLog(
            ">>> [$source] Конечная точка обновления mDNS во время сопряжения →" +
                "${result.ipAddresses.firstOrNull()}:${result.airPlayPort}",
        )
        return result
    }

    private fun txtValue(
        info: android.net.nsd.NsdServiceInfo,
        key: String,
    ): String? =
        info.attributes[key]
            ?.toString(Charsets.UTF_8)
            ?.trimEnd { it.code == 0 }
            ?.takeIf { it.isNotEmpty() }

    /**
     * Приоритет конечной точки позднего прибытия: порт/открытый ключ, явно сообщенный транспортным средством, имеет
     * приоритет над синтетическим значением mDNS.
     * , но список IP является объединенным (0x4301 может не иметь IPv4, согласно измерениям Audi MMI 9310).
     */
    private fun preferEndpoint(
        late: Iap2SenderSession.CarPlayEndpoint,
        fallback: Iap2SenderSession.CarPlayEndpoint,
    ): Iap2SenderSession.CarPlayEndpoint =
        late.copy(
            airPlayPort = if (late.airPlayPort > 0) late.airPlayPort else fallback.airPlayPort,
            ipAddresses = (late.ipAddresses + fallback.ipAddresses).distinct(),
            publicKey = late.publicKey ?: fallback.publicKey,
            deviceIdentifier = late.deviceIdentifier ?: fallback.deviceIdentifier,
            sourceVersion = late.sourceVersion ?: fallback.sourceVersion,
            advertisedFeatures = late.advertisedFeatures ?: fallback.advertisedFeatures,
        )

    /**
     * Начальная НАСТРОЙКА (с повторной попыткой повторного подключения).
     *
     * Парный путь: цикл сопряжения только что прошел, НАСТРОЙКИ достаточно (сбой означает настоящий сбой, переход на
     * более раннюю версию/завершение).
     * Путь сеанса в виде обычного текста: порт AirPlay автомобиля нельзя открыть до тех пор, пока «пользователь не
     * нажмет на значок автомобиля».
     * Catplay       также «подключается только после получения приглашения»; здесь каждые 2 секунды в [INITIAL_SETUP_WINDOW_MS]
     * Переподключитесь один раз и повторите попытку НАСТРОЙКИ, чтобы избежать полного сбоя, если порт откроется с
     * опозданием на 1–2 секунды.
     */
    private fun runInitialSetup(
        client: AirPlayClient,
        plainSession: Boolean,
        noPairing: Boolean,
    ): AirPlayClient.SetupInfo? {
        val first = client.runSetup()
        if (first != null || !plainSession) return first
        val deadline = android.os.SystemClock.uptimeMillis() + INITIAL_SETUP_WINDOW_MS
        var attempt = 1
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            attempt++
            appendLog(
                "AIRPLAY: настройка обычного текста не удалась в течение ${attempt - 1} времени — повторно " +
                    "подключитесь и повторите попытку через 2 секунды (порт AirPlay в автомобиле может быть еще не открыт)",
            )
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return null
            }
            if (connectCancelled) return null
            client.close()
            if (!client.reconnect(2_000)) continue
            val retried = client.runSetup()
            if (retried != null) {
                appendLog("AIRPLAY: ✓ НАСТРОЙКА открытого текста завершилась успешно при попытке $attempt.")
                return retried
            }
            // Порт открыт, но SETUP по-прежнему отклонен: решение TXT может быть неверным, и путь обновления шифрования
            // передается вызывающей стороне.

            if (!noPairing) return null
        }
        return null
    }

    /** Точка передачи сеанса BT: после получения 0x5703/0x4301 оба пути имеют один и тот же процесс — подключение к Wi-Fi → сопряжение AirPlay → НАСТРОЙКА → push-потоковая передача. */
    private fun onCarPlayEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        val context = appContext ?: return
        // Резервированная защита конечных точек: принимающий контроллер (протестированный DiPlay) дополняет существующую
        // RFCOMM повторно подключается и повторно отправляет 0x4301. Перезапуск сопряжения в это время приведет к
        // прекращению сеанса и прекращению работоспособного сеанса.

        // (Состояние гонки при остановке энкодера = основная причина цикла «автоматическое отключение после
        // подключения»). Конечные точки в потоке отправки игнорируются;

        // Когда соединение действительно разорвано, поток push-уведомлений завершится сам по себе (isRunning=false), и
        // следующая конечная точка естественным образом снова подключится.

        if (videoSender?.isRunning == true) {
            appendLog(
                ">>> [$source] Отправка сеанса — игнорировать избыточные конечные точки (повторяющийся 0x4301 " +
                    "периодического переподключения на принимающей стороне)",
            )
            return
        }
        if (!sessionStarting.compareAndSet(false, true)) {
            appendLog(">>> [$source] Уже обрабатывается сеанс, игнорируйте эту конечную точку (дождитесь окончания предыдущего раунда)")
            return
        }
        // Новый раунд начинается со сброса флага отмены: предыдущий раунд мог завершиться из-за установки «отключения канала BT» (см.
        // onSenderLinkClosed) — возможность попасть сюда означает, что компонент BT все еще работает (если пользователь отменяет путь,
        // Остановите компонент и будьте перехвачены охранником выше). Эта отметка принадлежит старому патрону, вышедшему
        // на пенсию. Если это не ясно, это приведет к случайному повреждению текущего раунда.

        connectCancelled = false
        // Начинается новый раунд передачи обслуживания: очистите временный кэш поздно прибывшей конечной точки,
        // оставшийся от предыдущего раунда (который мог выйти из строя на полпути).

        // В противном случае он перезапишет конечную точку, только что проанализированную этим сеансом на этапе очистки этого раунда.
        pendingEndpointUpdate = null
        // Пустая конечная точка = машина нового протокола заявляет, что 0x4301 будет отправлен, но окно еще не прибыло
        // (Iap2SenderSession не был отключен,

        // Насос поддержания активности отправит опоздавший 0x4301 обратно в эту функцию через onLateEndpoint и повторит весь процесс).
        // При этом не учитывается sessionStarting: конечная точка позднего прибытия принимает ветвь «последний раунд
        // завершился»; опрос мобильного телефона в период ожидания

        // Wi-Fi — пользователь вручную подключается к автомобильной точке доступа (пароль можно найти в настройках
        // автомобиля, фактический тест на Cadillac CT5), а затем подтверждается

        // Вы можете использовать путь mDNS/шлюза напрямую с этой синтетической конечной точкой SSID.
        if (endpoint.ssid.isEmpty()) {
            appendLog(
                ">>> [$source] Конечная точка не имеет информации о точке доступа (автомобиль не отправляет 0x4301 в " +
                    "окне) - Bluetooth продолжает работать и ожидает позднего прибытия 0x4301;" +
                    "Когда будет обнаружено, что телефон подключен к Wi-Fi, он спросит, нажать ли точку доступа в " +
                    "автомобиле, чтобы продолжить.",
            )
            phaseHint = "Ожидание отправки автомобилем информации о соединении (0x4301)..."
            sessionStarting.set(false)
            watchForPendingStartSession(endpoint, source)
            return
        }
        appendLog(
            ">>> [$source] Получите точку доступа принимающей стороны: SSID=${endpoint.ssid};" +
                "Пароль=${if (endpoint.passphrase.isEmpty()) "(нет)" else "получен"}",
        )
        phaseHint = "Bluetooth был подключен к автомобилю, и адрес точки доступа определен..."
        ssidSink?.invoke(endpoint.ssid)
        // Кэш учетных данных записывается в оба пути: 0x5703 — в onWifiCredentials, 0x4301 — только сюда.
        // При его отсутствии «Car Hotspot → Go to Connect» вручную будет выдаваться ложное сообщение «Пароль точки
        // доступа еще не получен (требуется 0x5703)».

        // ——22 октября 2026 г. Вот как журнал Audi исказил направление расследования.
        if (endpoint.ssid.isNotBlank()) {
            lastCarWifiSsid = endpoint.ssid
            if (endpoint.passphrase.isNotEmpty()) lastWifiPsk = endpoint.passphrase
        }
        // Диагностика (обязательно прочитать при устранении неисправностей старых автомобилей): Является ли адрес IPv4
        // или только IPv6, и декларируют ли функции TXT возможности сопряжения.

        // Обе "неудачи подключения" Audi 4xxx отсюда и происходят - в старой версии журнала эти два пункта полностью отсутствуют.
        val addresses = endpoint.ipAddresses
        val hasIpv4 = addresses.any { EndpointHosts.isIpv4(it) }
        val pairingFlag = AirPlayFeatures.supportsPairing(endpoint.advertisedFeatures)
        appendLog(
            ">>> [$source] Диагностика конечной точки: адреса=${addresses.ifEmpty { listOf("(нет)") }}" +
                "（${if (hasIpv4) {
                    "IPv4"
                } else if (addresses.isEmpty()) {
                    "Нет адреса"
                } else {
                    "только IPv6"
                }}）" +
                " features=" + (endpoint.advertisedFeatures?.let { "0x${it.toString(16)}" } ?: "неизвестно") +
                "Сопряжение с HomeKit =" +
                when (pairingFlag) {
                    true -> "Заявление о поддержке"
                    false -> "Необъявлен (обрабатывается как сеанс открытого текста до HomeKit)"
                    null -> "Неизвестно (пара по запросу)"
                },
        )
        // Портрет автомобиля-машины (точка закрытия сигнала): поступление бокового сигнала mDNS - оценка эпохи + можно
        // объяснить на основе одной строки

        appendLog(
            ReceiverProfile.describe(
                ReceiverProfile.classify(
                    ReceiverProfile.Signals(
                        features = endpoint.advertisedFeatures,
                        srcvers = endpoint.sourceVersion,
                    ),
                ),
            ),
        )
        // Сначала привяжите сеть, а затем проанализируйте конечную точку: многоадресная рассылка mDNS следует за
        // сетьюbindProcessToNetwork и сначала поставьте анализ

        // Будут получены только результаты старой сети (ранее это было покрыто внутренними повторными
        // попыткамиsolveEndpointHost, тщетно ожидавшими несколько секунд).

        // После завершения привязки выполните параллельно три вещи: Ctrl Broadcast для перерегистрации в новой сети,
        // фоновое обнаружение _airplay._tcp,

        // Ожидание приглашения управления автомобилем (автомобиль обнаружил наш авторитетный сигнал).
        val continueWithNetwork: (Boolean) -> Unit = proceed@{ bound ->
            if (!bound) {
                phaseHint = "Телефон не подключен к автомобильной точке доступа. Чтобы подключиться к ${endpoint.ssid}, " +
                    "перейдите в настройки WLAN."
                appendLog(
                    ">>> [$source] Телефон в настоящее время не находится в точке доступа — перейдите в " +
                        "«Настройки» > «WLAN», чтобы присоединиться к \"${endpoint.ssid}\", а затем отключитесь и снова подключитесь.",
                )
                sessionStarting.set(false)
                return@proceed
            }
            // Сетевая привязка завершена: ctrl Broadcast перерегистрируется (падает на сеть, где находится машина), и
            // одновременно запускается фоновое обнаружение mDNS.

            // И то, и другое происходит во время ожидания приглашения и не отнимает дополнительного времени.
            ctrlAdvertiser?.ensureForNetwork(endpoint.ssid, force = true, network = wifiConnector?.currentNetwork)
            endpointBrowser?.resetCache()
            endpointBrowser?.start()
            // 0x4301 поставляется с полным адресом + портом = принимающая сторона четко сказала «подключайтесь сюда», не
            // нужно ждать приглашения

            // (Путь испытательного стенда DiPlay: прямое соединение в этом раунде, исключающее фиксированное ожидание).
            // IPv6 также считается адресом: 0x4301 Audi 4xxx часто присваивается только fe80:: (старый критерий учитывает только IPv4,
            // Такие конечные точки будут считаться «неполными» и будут тщетно ждать приглашения ctrl, прежде чем вернуться на шлюз).
            val endpointSelfContained =
                endpoint.airPlayPort > 0 && EndpointHosts.hasUsable(endpoint.ipAddresses)
            Bg.run("airplay-pairing") {
                try {
                    if (connectCancelled) {
                        appendLog("AIRPLAY: Пользователь отменен – сопряжение прервано")
                        sessionStarting.set(false)
                        return@run
                    }
                    if (endpointSelfContained) {
                        appendLog(
                            "AIRPLAY: 0x4301 Конечная точка завершена " +
                                "(${endpoint.ipAddresses.firstOrNull()}:${endpoint.airPlayPort}) — прямое сопряжение, без " +
                                "ожидания приглашения ctrl",
                        )
                    } else {
                        // Дождитесь приглашения ctrl (catplay CarPlayCtrlServer: приглашение = машина обнаружила телефон и
                        // Окно AirPlay открыто и подключение произойдет сразу после его получения). Раньше было "фиксированный сон 1.6с,
                        // держу пари, что он прибыл",

                        // Когда все быстро, ждать бесполезно; когда оно медленное, этого недостаточно; перейти на
                        // событийно-ориентированный.

                        awaitCtrlInvite(endpoint.ssid, CTRL_INVITE_WAIT_MS)
                    }
                    // Парсинг конечной точки ставится после приглашения: `_airplay._tcp` машины-автомобиля часто выходит в онлайн
                    // после приглашения.

                    // И этот шаг может занять до 3 секунд — он должен выполняться в фоновом потоке и не может занимать поток
                    // обратного вызова сети.

                    var effectiveEndpoint = resolveEndpointHost(endpoint, source)
                    // Здесь включается 0x4301 (более авторитетный, чем синтез mDNS), выданный транспортным средством во время начала
                    // процесса передачи обслуживания.

                    pendingEndpointUpdate?.let { late ->
                        pendingEndpointUpdate = null
                        effectiveEndpoint = preferEndpoint(late, effectiveEndpoint)
                        appendLog(
                            "AIRPLAY: объединить конечную точку 0x4301, которая прибыла с опозданием во время передачи →" +
                                "${effectiveEndpoint.ipAddresses.firstOrNull()}:${effectiveEndpoint.airPlayPort}",
                        )
                    }
                    if (connectCancelled) {
                        appendLog("AIRPLAY: Пользователь отменен – сопряжение прервано")
                        sessionStarting.set(false)
                        return@run
                    }

                    fun newClient() =
                        AirPlayClient(
                            effectiveEndpoint,
                            log = { line -> appendLog(line) },
                            context = context.applicationContext,
                            // Генерация канала: определен образ 0x1D01 на стороне Bluetooth (CLASSIC_MIB3/EVENT_LINK),
                            // Развертывание MIB3 на этапе AirPlay соответствующим образом назначает стратегии маркеров (реконструкция
                            // поколения 2026-10-23).
                            linkGeneration = senderLinkGeneration(),
                            endpointRefresher = { current -> enrichEndpointFromBrowser(current, source) },
                            retryTick = { attempt ->
                                // Автомобиль все еще может не обнаружить Ctrl во время повторной попытки сопряжения (Бонжур пропустил
                                // первое
                                // объявление):

                                // Периодически принудительно воспроизводить и записывать статус mDNS в журнал — в противном случае при
                                // устранении неполадок.

                                // Я вижу только экран повторной попытки обновления «Порт не открыт», и не могу сказать, связано ли это с
                                // тем,
                                // что машина не приглашена, или с

                                // Адрес неверен (в журнале Audi 0.1.7 от 19 октября 2026 г. эти две части информации отсутствуют).
                                ctrlAdvertiser?.ensureForNetwork(
                                    endpoint.ssid,
                                    force = true,
                                    network = wifiConnector?.currentNetwork,
                                )
                                val invites = ctrlAdvertiser?.inviteCount ?: 0
                                val found = endpointBrowser?.awaitEndpoint(0)
                                appendLog(
                                    "AIRPLAY: Повторите попытку #$attempt — Ctrl объявлен повторно;" +
                                        "Приглашения на машине и машине=$invites раз, mDNS _airplay._tcp=" +
                                        (found?.let { "${it.host?.hostAddress}:${it.port}" } ?: "не найден"),
                                )
                            },
                        ).also { created ->
                            // Автомобильные мультимедийные клавиши (канал iAP2 0x6802): предыдущая и следующая песни на рулевом
                            // колесе/панели.

                            // Поместите его сюда вместо точки вызова, чтобы он мог зависнуть при повторном подключении и перестроении
                            // клиента.

                            created.onMediaKey = { keyCode ->
                                dispatchMediaKey(keyCode)
                                appendLog("СОБЫТИЕ: Автомобильный мультимедийный ключ (iAP2 0x6802) → keyCode=$keyCode был введен")
                            }
                            // Ручка/четырёхпозиционный рычаг (канал iAP2 0x6802): DPAD с виртуальным экраном
                            created.onNavKey = { keyCode -> injectNavigationKey(keyCode) }
                            // Сеанс управления MIB3 завершен → пакет modeChanged ресурса (082446 Определено:
                            // Если он объявлен только один раз в начале сеанса, второй сеанс управления сбросит арбитраж ресурсов
                            // транспортного средства и машины.

                            // Автомобиль остается исправным на протяжении всей поездки, но ни разу не выключает экран CarPlay).
                            created.onTunnelControlSessionComplete = {
                                scheduleModesReclaim(created)
                            }
                        }
                    // Перезапись «Принудительного ландшафта приложения» добавляется в очередь в начале сопряжения: `am compat
                    // enable` будет немедленно

                    // Уничтожение приложений в списке должно быть завершено до того, как присоединение загрузит какое-либо
                    // приложение. Фаза спаривания длится десятки секунд

                    // Пустое окно, записи в этот период истощаются естественным образом, для присоединения требуется только сток (~0
                    // мс) - изначально во вложении

                    // Синхронный запуск (1,6 с, 3,2 с в последовательном режиме) будет занимать «ЗАПИСЬ → порт экрана KeepAlive».
                    // Бюджет на удаление цепочки ~10 секунд (кадр KeepAlive запускается после возврата подключения, определен 23
                    // октября 2026 г.).

                    appContext?.let { ctx -> AppOrientationCompat.applyAllAsync(ctx, ::appendLog) }
                    retireSession()
                    // Шумоподавление тишины сеанса (10 октября 2026 г.): приостановить обнаружение исходящего соединения для других
                    // получателей из точки передачи обслуживания.

                    // (Типично: Audi больше не обнаруживает шум при подключении к испытательному стенду DiPlay). Отклоняется при
                    // завершении/отключении сеанса.

                    BtSessionGate.sessionActive = true
                    // Этап 1: оставьте BT на месте для прямого сопряжения + НАСТРОЙКА (форма, соответствующая протоколу)
                    phaseHint = "Сопряжение AirPlay..."
                    var client = newClient()
                    currentClient = client
                    drainPendingEndpoint(client)
                    // Машина старой машины Pre-HomeKit (эра CarPlay 210.81, прошивка Audi 4xxx) в
                    // **не объявлено** в `_airplay._tcp` HK_PAIRING_AND_ENCRYPT: они принимают только
                    // SETUP в виде открытого текста (et=NONE) + поток экрана AES-CTR, установка пары неизбежно завершится сбоем.
                    // Это класс приемника Catplay 1; старая реализация будет простаивать здесь в окне сопряжения 120 секунд,
                    // Пользователь видит «Невозможно подключиться».
                    val noPairing =
                        AirPlayFeatures.supportsPairing(client.currentEndpoint.advertisedFeatures) == false
                    var keys: PairVerifyClient.ControlKeys? =
                        if (noPairing) {
                            client.skipPairing("Функции TXT не заявлены HK_PAIRING_AND_ENCRYPT")
                            client.reconnect()
                            null
                        } else {
                            client.runPairing()
                        }
                    if (connectCancelled) {
                        retireSession()
                        appendLog("AIRPLAY: Пользователь отменен – сопряжение прервано")
                        sessionStarting.set(false)
                        return@run
                    }
                    // Секретный путь после сбоя сопряжения (чисто функциональное принятие решений, сценарий захвата пакетов на
                    // реальной машине определяется PairingFallbackTest)

                    val fallbackAfterFirstRound =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                        )
                    if (fallbackAfterFirstRound.useBtRetireHack) {
                        // Второй этап (только DiPlay на испытательном стенде): Воспроизведите состояние «Управление BT завершено» и
                        // повторите попытку.

                        // Настоящие автомобили (Audi) никогда не смогут отключить BT — канал управления BT настоящей беспроводной
                        // системы CarPlay остается активным на протяжении всего процесса.

                        // При отключении автомобиль полностью удалит окно AirPlay (фактическое испытание на реальном автомобиле).
                        // PairingForbidden (любой сегмент 403, включая сегмент проверки/аутентификации - 21 октября 2026 г., реальная
                        // машина)

                        // Правила sysdiagnose: 403 впервые — это внутреннее состояние автомобиля. Он автоматически перезапустится через
                        // 18–20 с.

                        // Также отключается при повторном приглашении или прохождении во второй раз): отключение БТ приведет к
                        // перестройке всего стека автомобиля и машины, чего случается не хватать.

                        // Предстоящее окно повторного приглашения.
                        // Порог: используйте этот хак только в том случае, если RTSP принимающей стороны (attemptedPairing)
                        // действительно подключен в этом раунде;

                        // Никогда не стоит ломать BT, если конечная точка не разрешена (нет IP)/порт не открыт (наступило 11.10.2026:
                        // Сбой mDNS также вызывает отсоединение, напрасно снимающее окно автомобиля).
                        detachActiveBtSessions()
                        client = newClient()
                        currentClient = client
                        drainPendingEndpoint(client)
                        keys = client.runPairing()
                        if (connectCancelled) {
                            retireSession()
                            appendLog("AIRPLAY: Пользователь отменен – сопряжение прервано")
                            sessionStarting.set(false)
                            return@run
                        }
                    }
                    // Все еще существуют допустимые пути, когда ключи == null: **сеанс открытого текста catplay et=NONE**.
                    //  - Ресивер явно не поддерживает сопряжение (до HomeKit/TXT без HK_PAIRING_AND_ENCRYPT);
                    // - Сопряжение полностью не удалось, но RTSP действительно был подключен (некоторые автомобили распознают только
                    // текстовые разговоры,

                    // Пара-установка/пара-проверка отклоняет все - кошачья игра - это в точности «сначала попробуйте простой текст,
                    // а затем соедините, если не получится»,

                    // Давайте поступим наоборот: отдадим приоритет сопряжению (сохраним DiPlay/известные автомобили), а в случае
                    // неудачи понизим рейтинг).

                    // - **403 доказательства (pairingForbidden) исключены** (захват пакетов на реальном компьютере 21 октября 2026
                    // г., принятие решения)

                    // См. сопряжениеFallback): машина будет ждать, пока будет готова, а затем повторно пригласит примерно через
                    // 20 секунд — открытый текст должен быть

                    //    При отказе будет использовано окно повторного приглашения, и рейтинг не будет понижен.
                    // Раздел взлома мог быть перезапущен для получения ключей: решение основано на статусе после повторного запуска.
                    val plainSession =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                        ).usePlainSession
                    if (plainSession) {
                        phaseHint = "Создать сеанс открытого текста…"
                        appendLog(
                            "AIRPLAY: сопряжение не выполнено " +
                                "(${if (client.pairingUnsupported) "не поддерживается" else "ошибка"}) — " +
                                "переход на et=NONE + AES-CTR",
                        )
                        // В конце цикла сопряжения сокет закрывается/конечный автомат недоступен: перед НАСТРОЙКОЙ необходимо повторно
                        // подключиться.

                        // Неудача не является прямым смертным приговором: порт AirPlay автомобиля может быть еще не открыт, поэтому
                        // попробуем еще раз согласно окну ниже.

                        if (!client.reconnect()) {
                            appendLog(
                                "AIRPLAY: первое переподключение сеанса с открытым текстом не удалось. Повторите " +
                                    "попытку в окне SETUP.",
                            )
                        }
                    }
                    // ключи — это изменяемые переменные захвата в лямбда-выражении: Kotlin не может сначала разумно преобразовать их
                    // и закрепить в неизменяемые значения.

                    val pairedKeys = keys
                    if (pairedKeys != null || plainSession) {
                        phaseHint =
                            if (pairedKeys != null) {
                                "Сопряжение завершено, сеанс CarPlay " +
                                    "установлен…"
                            } else {
                                "Создать сеанс открытого текста…"
                            }
                        if (pairedKeys != null) {
                            appendLog(
                                "AIRPLAY: ✓ Сопряжение завершено writeKey=" +
                                    pairedKeys.phoneWriteKey.joinToString("") { "%02x".format(it) }.take(16) + "… " +
                                    "readKey=" + pairedKeys.phoneReadKey.joinToString("") { "%02x".format(it) }.take(16) + "…",
                            )
                        }
                        // Парный → общий ключ; сеанс открытого текста → ноль (шифрование потока понижено до нулевого ключа AES-CTR)
                        var sessionKey = pairedKeys?.sharedSecret
                        var setup = runInitialSetup(client, plainSession, noPairing)
                        if (setup == null && plainSession && noPairing) {
                            // путь «зашифрованного обновления» Catplay: только если **мы активно пропускаем сопряжение** (TXT не указан
                            // HK_PAIRING_AND_ENCRYPT) и установка открытого текста по-прежнему не выполняется.
                            // Нет смысла пытаться наоборот (сначала была предпринята попытка спаривания, но она не удалась), и придется
                            // подождать еще 30 секунд.

                            val upgraded = client.retryWithPairing(PAIRING_UPGRADE_WINDOW_MS)
                            if (upgraded != null) {
                                sessionKey = upgraded.sharedSecret
                                setup = client.runSetup()
                            }
                        }
                        if (setup != null) {
                            // Запись учетных данных сеанса: используется для динамического захвата звука, когда предполагаемая авторизация
                            // происходит позже, чем соединение.

                            // Используйте client.currentEndpoint вместо effectEndpoint: во время сопряжения
                            // Когда mDNS обновляет конечную точку, фактически подключается адрес в клиенте (2026-10-18:
                            // Поле IP фиксированной длины 0x4301 содержит NUL, mDNS выдает чистый адрес, возьмите старое значение
                            // Это приведет к тому, что видеопорт/keepAlive будет использовать грязные адреса → все разрешения не будут
                            // работать).

                            currentSharedSecret = sessionKey
                            currentAudioHost =
                                client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                            client.connectEventChannel(sessionKey, setup) { command ->
                                when (command["type"]) {
                                    "hidSendReport" -> {
                                        val uuid = command["uuid"]?.toString()
                                        val report = command["hidReport"] as? ByteArray
                                        // Автомобильный медиа-ключ (Consumer Control) использует канал hidSendReport.
                                        // Определение основано на дескрипторе устройства (не жестко запрограммированном uuid или модели
                                        // автомобиля):

                                        // Дескриптор объявляет устройство потребительской страницы (0x0C) в качестве медиа-ключа, а байт
                                        // отчета

                                        // Индекс использования массива (семантика HID Array). Дескрипторы для сенсорных/ручных/телефонных
                                        // устройств.

                                        // Это страницы Digitizer/GenericDesktop/Telephony, никогда сюда не заходите——
                                        // Длина отчетов ручки DiPlay (4B) и телефонии (1B) перекрывается с мультимедийными клавишами,
                                        // Если полагаться только на длину, это приведет к неправильной оценке (например, использование
                                        // телефонии
                                        // 0xB3
                                        // приведет к неправильной оценке «перемотки вперед»).

                                        val descriptor = uuid?.let { setup.hidDescriptors[it] }
                                        val keyMap =
                                            descriptor?.let(ConsumerKeys::parseDescriptor)
                                        val rawValue =
                                            report
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let { it[0].toInt() and 0xFF }
                                        val consumerUsage =
                                            when {
                                                // С дескриптором: разрешается только потребительскими устройствами (не потребительские →
                                                // ноль).

                                                descriptor != null -> keyMap?.let { rawValue?.let(it::usageFor) }
                                                // Отсутствует дескриптор (неизвестное транспортное средство): консервативный запасной
                                                // вариант
                                                // -
                                                // сообщается
                                                // только 1 байт,

                                                // Значение, когда идентификатор использования считывается напрямую
                                                report != null && report.size == 1 -> rawValue?.takeIf { it != 0 }
                                                else -> null
                                            }
                                        // Ручка/шляпа (колесо/шляпа на странице общего рабочего стола):
                                        // Здесь речь идет не о потребительском устройстве; вывод DPAD_* вводится в виртуальный экран.
                                        val navKeys =
                                            if (consumerUsage == null && report != null) {
                                                if (HidNavigation.rawLogEnabled) {
                                                    appendLog(
                                                        "HID-RAW: uuid=$uuid bytes=" +
                                                            report.joinToString(" ") { "%02X".format(it) } +
                                                            " desc=${descriptor?.size ?: 0}B",
                                                    )
                                                }
                                                HidNavigation.handle(uuid.orEmpty(), descriptor, report)
                                            } else {
                                                emptyList()
                                            }
                                        val contacts =
                                            if (consumerUsage == null && navKeys.isEmpty() && report != null) {
                                                // Формат сенсорного экрана определяется дескриптором устройства (страница дигитайзера +
                                                // одно
                                                // касание/несколько
                                                // слотов),

                                                // Резервный вариант на основе длины сообщения, когда дескриптор недоступен - не зависит от
                                                // модели
                                                // автомобиля
                                                // или
                                                // uuid

                                                val touchFormat =
                                                    descriptor?.let(TouchReports::formatFromDescriptor)
                                                TouchReports.parse(report, touchFormat)
                                            } else {
                                                null
                                            }
                                        if (navKeys.isNotEmpty()) {
                                            navKeys.forEach(::injectNavigationKey)
                                            appendLog(
                                                "СОБЫТИЕ: Ручка/Клавиша навигации" +
                                                    navKeys.joinToString("+") { HidNavigation.nameOf(it) } +
                                                    "（uuid=$uuid）",
                                            )
                                        } else if (consumerUsage != null) {
                                            val keyCode = ConsumerKeys.keyCodeFor(consumerUsage)
                                            if (keyCode != 0) {
                                                dispatchMediaKey(keyCode)
                                                appendLog(
                                                    "СОБЫТИЕ: Автомобильный мультимедийный ключ ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        "（uuid=$uuid usage=0x%02x → keyCode=$keyCode）"
                                                            .format(consumerUsage),
                                                )
                                            } else {
                                                appendLog(
                                                    "СОБЫТИЕ: Автомобильный мультимедийный ключ ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        "(uuid=$uuid use=0x%02x) Нет соответствующего ключа Android, игнорируется"
                                                            .format(consumerUsage),
                                                )
                                            }
                                        } else if (keyMap != null && report != null) {
                                            // «Нет кнопки»/сообщение о выпуске потребительского устройства (значение равно 0 или индекс
                                            // выходит
                                            // за
                                            // пределы):

                                            // Тихий подсчет, чтобы избежать сброса экрана при событиях выпуска
                                            consumerReleaseCount++
                                        } else if (contacts != null) {
                                            if (touchLogCount <= 30 || touchLogCount % 50 == 0) {
                                                appendLog(
                                                    "СОБЫТИЕ: коснитесь" +
                                                        contacts.joinToString {
                                                            "#${it.slot}(${it.xPixels},${it.yPixels})${if (it.down) "↓" else "↑"}"
                                                        },
                                                )
                                            }
                                            touchLogCount++
                                            contacts.forEach {
                                                TouchRouter.onContact(
                                                    context.applicationContext,
                                                    it,
                                                )
                                            }
                                        } else if (report != null) {
                                            appendLog(
                                                "СОБЫТИЕ: Другие отчеты HID uuid=$uuid bytes=${report.size}" +
                                                    " hex=${report.joinToString(" ") { "%02x".format(it) }}",
                                            )
                                        }
                                    }

                                    "iAPSendMessage" -> {
                                        // Байты виртуальной связи iAP2 в канале событий (открытие первого маркера транспортного
                                        // средства-машины).

                                        // Подайте ссылку на движок: Handshake → EA Session → Touch HID.
                                        val params = command["params"] as? Map<*, *>
                                        val data = params?.get("data") as? ByteArray
                                        if (data == null) {
                                            appendLog("СОБЫТИЕ: iAPSendMessage нет данных (params=$params)")
                                        } else {
                                            iapEventMessageCount++
                                            client.onIapEventBytes(data)
                                        }
                                    }

                                    "forceKeyFrame" -> {
                                        // Когда автомобиль и компьютер не могут интерпретировать изображение, он запросит I-кадры.
                                        // Требуется
                                        // немедленная
                                        // реакция:

                                        // Перетащите I кадр следующего цикла (1 с), и машина может застрять в состоянии ожидания кадра.
                                        val forced = videoSender?.requestKeyFrame() == true
                                        appendLog(
                                            "СОБЫТИЕ: ключевой кадр запроса машины транспортного средства (forceKeyFrame) →" +
                                                if (forced) {
                                                    "Принудительное кодирование " +
                                                        "I-кадров"
                                                } else {
                                                    "Кодер не готов, ожидание периодического I-кадра"
                                                },
                                        )
                                    }

                                    "changeModes" -> {
                                        // Автомобиль и машина запрашивают арбитраж ресурсов (например, пользователь нажимает на мобильный
                                        // телефон
                                        // в
                                        // MMI):

                                        // Режимы возврата Изменены, чтобы объявить, что на телефоне отображается главный экран/основной
                                        // звук.

                                        appendLog(
                                            "СОБЫТИЕ: Транспортное средство запрашивает изменение режима → " +
                                                "возвращается в режим «Изменено», чтобы взять на себя управление ресурсами.",
                                        )
                                        client.assertModes()
                                    }

                                    else ->
                                        appendLog(
                                            // Нераспознанные команды должны иметь параметры: частные команды автомобиля (например,
                                            // requestUI)
                                            // Все, что вам нужно, - это все в параметрах - только тип типа означает отсутствие типа.
                                            "СОБЫТИЕ: тип команды=${command["type"]} " +
                                                command.entries
                                                    .filter { it.key?.toString() != "type" }
                                                    .joinToString(" ") { (key, value) ->
                                                        "$key=" +
                                                            when (value) {
                                                                is ByteArray -> value.joinToString("") { "%02x".format(it) }
                                                                else -> value.toString().take(240)
                                                            }
                                                    }.take(600),
                                        )
                                }
                            }
                            // Декларация ресурсов (обязательно): Реальный автомобиль напрямую. 400 НАСТРОЙКА экрана потока необъявленных
                            // ресурсов.

                            client.assertModes()
                            // Режим ввода HID поставлен на охрану: /info hidDevices(Audi string "0"/"1", touch="1").
                            // функции дисплея = 0x08 (высокоточное сенсорное управление) заявлено. Без этого шага машина не отправит
                            // hidSendReport.

                            setup.hidDeviceUuids.forEach { uuid ->
                                client.sendHidSetInputMode(uuid, mode = 0)
                            }
                            val screen = client.runScreenStream(sessionKey, setup.displayUuid)
                            if (screen != null) {
                                // Это тот адрес, к которому фактически подключен клиент (он может быть обновлен mDNS во время сопряжения):
                                // Порт видеоданных и KeepAlive строят на его основе сокеты; Конечные точки IPv6 также учитываются.
                                // Доступные адреса (унифицированный порядок EndpointHosts: IPv4 → Глобальный IPv6 → Локальный IPv6 канала).
                                val host = client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                                // Двойная страховка Heartbeat (если какая-либо из них отсутствует, автомобиль будет разобран сторожевым
                                // таймером
                                // ~10-секундный сеанс, фактическое тестирование 11 октября 2026 г.):

                                //   KeepAlive UDP ping (keepAlivePort возвращается программой SETUP транспортного средства, период 10 с)
                                //   + RTSP POST/обратная связь (период 1 с, кошачья игра FEEDBACK_INTERVAL)
                                if (host != null && setup.keepAlivePort != null) {
                                    client.startKeepAlive(host, setup.keepAlivePort!!)
                                }
                                client.startFeedbackLoop()
                                // Туннель iAP (тип-130): автомобиль машина → данные iAP2 EA мобильного телефона (носитель Touch HID).
                                // Сбой при установлении не блокирует сеанс (сенсорное управление недоступно, но видео продолжается в
                                // обычном
                                // режиме).

                                val tunnel = client.runIapTunnel(sessionKey)
                                tunnel?.onIapMessage = { frame ->
                                    // Сбор доказательств касанием: сначала запишите всю сумму, подтвердите формат, а затем подключитесь к
                                    // TouchRouter

                                    if (frame.messageId != 0x0000) {
                                        val hex = frame.body.joinToString(" ") { "%02x".format(it) }
                                        appendLog(
                                            "TUNNEL: rx 0x${frame.messageId.toString(16)} " +
                                                "(${Iap2MessageIds.nameOf(
                                                    frame.messageId,
                                                )}) ${frame.body.size}B" +
                                                if (frame.body.isNotEmpty()) " hex=${hex.take(120)}" else "",
                                        )
                                    }
                                }
                                // Ключевое время (Audi Dingji 2026-10-17): Экран необходимо подключить как можно скорее после НАСТРОЙКИ.
                                // dataPort — в автомобиле установлен сторожевой таймер ~2 с. НАСТРОЙКА АУДИО на самом деле занимает ~2,1 с.
                                // Если он занимает первое место, позднее подключение порта вызовет сброс канала управления транспортным
                                // средством.

                                // (Характеристики журнала: `Аудио SETUP туда и обратно 2091ms` и `EVENT чтение завершено
                                // - Сброс соединения` та же миллисекунда). Итак, сначала создайте VideoSender и
                                // openDataPort(), затем согласуйте аудио и, наконец, ЗАПИСЬ + запустите кодер.
                                var pendingVideoW = 0
                                var pendingVideoH = 0
                                var pendingVideoDensity = 0
                                if (host != null) {
                                    // Предпочтение «Дисплей»: потоковое полотно/виртуальный экран генерируется в соответствии с процентом
                                    // разрешения
                                    // автомобиля.

                                    // Вступает в силу только после установления сеанса — в это время окончательно определяются кодировщик и
                                    // размер
                                    // виртуального экрана;

                                    // Плотность масштабируется синхронно, а логическое расположение (dp) согласовано на 100 %.
                                    val scalePct =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .scalePercent(it)
                                            } ?: 100
                                    val scale = scalePct / 100f
                                    // Выравнивание вниз до четных чисел после масштабирования (требуется H.264): виртуальный
                                    // экран/композитор/кодер

                                    // Три стороны должны быть одинакового размера, чтобы они были выровнены у источника (в VideoSender есть
                                    // еще
                                    // один
                                    // карман)

                                    val streamW =
                                        ((setup.displayWidth * scale).toInt().coerceAtLeast(320) / 2) * 2
                                    val streamH =
                                        ((setup.displayHeight * scale).toInt().coerceAtLeast(180) / 2) * 2
                                    val fps =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .fps(it)
                                            } ?: 30
                                    activeScalePercent = scalePct
                                    activeTargetFps = fps
                                    // Прикосновение автомобиля к машине осуществляется в системе координат представления уведомлений
                                    // (например,
                                    // 1160×540), а поток масштабируется.

                                    // Масштаб (например, 1450×674) — перед внедрением необходимо выполнить сопоставление, иначе щелчок
                                    // будет
                                    // смещаться.

                                    TouchRouter
                                        .setCoordinateScale(
                                            setup.displayWidth,
                                            setup.displayHeight,
                                            streamW,
                                            streamH,
                                        )
                                    appendLog(
                                        "ДИСПЛЕЙ: поток ${setup.displayWidth}x${setup.displayHeight} →" +
                                            "${streamW}x$streamH（$scalePct%）@${fps}fps",
                                    )
                                    pendingVideoW = streamW
                                    pendingVideoH = streamH
                                    // Плотность экрана док-станции пропорциональна короткой стороне холста (основа дизайна: 1160×540 при
                                    // 420
                                    // точек
                                    // на дюйм,

                                    // См. design/launcher-proto.html: док-станция 48dp=126px): высокое разрешение.
                                    // Текст/значок дока под автомобилем (тестовый стенд 2320×1080=2×) имеет ту же пропорцию, что и у Audi.
                                    // Увеличение масштаба — ранее фиксированное разрешение 420 точек на дюйм, при уменьшении вдвое
                                    // док-станция
                                    // выглядит меньше холста (17 октября 2026 г.).

                                    // Максимум 4× (холст уровня 4K).
                                    pendingVideoDensity =
                                        (420f * minOf(streamW, streamH) / 540f)
                                            .toInt()
                                            .coerceIn(120, 1680)
                                    videoSender =
                                        VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            frameCipher = screen.cipher,
                                            width = streamW,
                                            height = streamH,
                                            targetFps = fps,
                                            log = { line -> appendLog(line) },
                                            keepAliveWithStats = setup.keepAliveSendStatsAsBody,
                                            onEnded = {
                                                // Сеанс фактически завершается: отмена тихого шумоподавления (клиент возобновляет
                                                // обнаружение/переподключение)

                                                appendLog(
                                                    ">>> Сеанс CarPlay завершен (нажатие прекращено) – возобновить " +
                                                        "обнаружение Bluetooth",
                                                )
                                                BtSessionGate.sessionActive = false
                                                // Категория отключения автомобиля от машины заканчивается (без операции отключения
                                                // пользователя):
                                                // темный
                                                // экран/всегда включен должен быть закрыт вместе.

                                                // Этот путь не проходит через retireSession (VirtualLauncher все еще висит),
                                                // Если вы пропустите это, вы увидите «Сеанс давно отключен, а экран телефона внезапно
                                                // становится
                                                // черным
                                                // через
                                                // 45
                                                // секунд».

                                                // (Фактическое измерение пользователя на 19 октября 2026 г.).
                                                ScreenDimmer.setSessionActive(false)
                                                // Отключение со стороны автомобиля: когда человек находится вне автомобиля, оставьте
                                                // телефон
                                                // в
                                                // спящем
                                                // режиме
                                                // (панель остается темной).

                                                // Вместо того, чтобы сначала зажечь свет, а затем ждать 60 секунд, чтобы истечет время
                                                // ожидания

                                                restorePhoneScreen(sleepIfCarSide = true)
                                                // Также восстановите горизонтальное покрытие экрана. Выполняемый здесь поток — это конец
                                                // push-потока,
                                                // а
                                                // не
                                                // соединения.

                                                // критический путь, но используйте асинхронный порт единообразно, чтобы поддерживать
                                                // семантику
                                                // «последовательный, нереентерабельный».

                                                appContext?.let { ctx ->
                                                    AppOrientationCompat.resetAllAsync(ctx, ::appendLog)
                                                }
                                                // Здесь также необходимо восстановить системную сеть, хранящуюся в оболочке: связь между
                                                // автомобилем
                                                // и
                                                // машиной
                                                // установлена.

                                                // Самый распространенный способ покинуть сайт (выключить движок), пользователь может не
                                                // нажимать
                                                // «Отключиться»
                                                // —
                                                // просто положить трубку.

                                                // Один конец отключения необходимо оставить (рассмотрено и принято решение 23 октября 2026
                                                // г.).
                                                // следующий
                                                // раунд

                                                // Если вы нырнете глубже, вы будете перерегистрированы, и семантика будет
                                                // самосогласованной.

                                                cleanupShellFallbackNetwork()
                                                // Атрибуция отключения звука: звук был запущен, но был быстро отключен → запишите положение
                                                // Несовместимо, автоматически переключает передачи при следующем подключении (см.
                                                // атрибутAudioTeardown).

                                                attributeAudioTeardown()
                                            },
                                        )
                                    // Сначала подключите порт (сторожевой таймер автомобиля) и дождитесь ЗАПИСИ, прежде чем запускать
                                    // кодировщик.

                                    videoSender?.openDataPort()
                                }
                                // Сначала ЗАПИСЬ, чтобы активировать сеанс и запустить видео, затем согласовать аудио (Audi Dingsha, 17
                                // октября
                                // 2026 г.):

                                // Автомобиль имеет сторожевой таймер в течение примерно 2 секунд «после НАСТРОЙКИ потока экрана» и не может
                                // дождаться ЗАПИСИ/потока данных.

                                // Снести всю сессию. Фактическое измерение самой настройки звука занимает ~2,05–2,16 с (аудиоканал со
                                // стороны
                                // автомобиля).

                                // Инициализация), обязательно произойдет тайм-аут перед ЗАПИСЬЮ - сброс и НАСТРОЙКА звука в журнале.
                                // Возвращается та же миллисекунда, и все 5 фактических измерений повторяются. Итак, порядок изменен на:
                                //   Экран НАСТРОЙКА → Подключить порт данных → ЗАПИСЬ → Запустить кодер (выходной кадр) → НАСТРОЙКА Аудио
                                // Если настройка звука не удалась, будет потерян только звук, а видеосессия останется.
                                client.record()
                                if (host != null) {
                                    val videoW = pendingVideoW
                                    val videoH = pendingVideoH
                                    val videoDensity = pendingVideoDensity
                                    videoSender?.start { surface ->
                                        // Только режим: невидимый экран (двухэкранная панель запуска: док-станция + экран приложения)
                                        val source =
                                            HiddenDisplaySource(
                                                context.applicationContext,
                                                log = { line -> appendLog(line) },
                                            )
                                        currentHiddenSource = source
                                        val ok =
                                            source.attach(
                                                surface,
                                                videoW,
                                                videoH,
                                                videoDensity,
                                            )
                                        appendLog(
                                            "РЕЖИМ: невидимый экран " +
                                                "${if (ok) "запущен (Amap Auto), display=${source.displayId}" else "ошибка запуска (Shizuku готов?)"}",
                                        )
                                        // Темный экран/всегда включен вступает в силу только после фактического начала трансляции экрана
                                        // (см.
                                        // ScreenDimmer.sessionActive):

                                        // При запуске приложения подключаются только сенсорные щупы, чтобы избежать «раньше сеанса и
                                        // телефон
                                        // через
                                        // некоторое время тускнеет».

                                        if (ok) {
                                            ScreenDimmer.setSessionActive(true)
                                            // Виртуальный экран создан → Нажмите переключатель «Виртуальный экран при подключении», чтобы
                                            // определить,
                                            // выключено ли оборудование.

                                            if (DisplayPrefs.screenOffOnConnect(context)) {
                                                enterPhoneScreenOff()
                                            } else {
                                                appendLog(
                                                    "ЭКРАН: «Настоящий экран при подключении» отключен — на этот " +
                                                        "раз используется только темный экран.",
                                                )
                                            }
                                        }
                                    }
                                }
                                // Аудиоканал (необязательно): Тип настройки = 100 → Push-поток UDP RTP.
                                // Повторите переговоры после активации сеанса (ЗАПИСЬ завершена); сбой не повлияет на основную видеосвязь.
                                val audioStarted = startAudioChannel(context, client, sessionKey, host)
                                if (audioStarted) {
                                    // Поток присоединен после ЗАПИСИ: семантика AirPlay требует отправки еще одной ЗАПИСИ.
                                    // Включите новый поток в активный сеанс. Если этот шаг пропущен, машина вернет 200 в режим НАСТРОЙКИ.
                                    // И дайте dataPort, но он на самом деле не будет воспроизводиться (актуальный тест Audi 17 октября 2026
                                    // г.:

                                    // Аудиопакеты отправляются непрерывно, собранный контент ненулевой и автомобиль молчит).
                                    appendLog(
                                        "АУДИО: Аудиопоток присоединен – повторите активацию ЗАПИСИ " +
                                            "(присоединитесь к сеансу после потоковой передачи)",
                                    )
                                    client.record()
                                }
                            }
                        } else {
                            // SETUP на уровне сеанса отклонен (типично: получатель фильтрует ошибку 403 по устройству, см.
                            // описание идентификатора устройства AirPlayClient.runSetup). Активно закройте канал управления:
                            // В противном случае принимающей стороне придется ждать около 45 секунд, пока истечет время ожидания видео,
                            // прежде чем отключать канал BT, и пользователь будет видеть только

                            // «Автоматически отключается после подключения» означает, что невозможно определить, не удалось ли установить
                            // сеанс.

                            phaseHint = "Установление сеанса было отклонено принимающей стороной (сбой установки)"
                            appendLog("AIRPLAY: Не удалось установить сеанс — канал управления был активно закрыт, и раунд завершился.")
                            uiHintSink?.invoke(
                                "Получатель отклонил установление сеанса (SETUP) — проверьте журналы на " +
                                    "наличие подсказок по идентификатору устройства.",
                            )
                            runCatching { client.close() }
                        }
                    }
                } catch (error: Throwable) {
                    // Любое исключение в потоке сопряжения не должно оставлять застрявшее состояние «Сеанс уже обрабатывается».
                    appendLog("AIRPLAY: исключение потока сопряжения - ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    sessionStarting.set(false)
                    // Отключите бесшумное шумоподавление, если при сопряжении не удается установить потоковую передачу
                    // (сбой/исключение/сбой при запуске и досрочное завершение).

                    // В противном случае BT-C будет постоянно парковаться за воротами, и реальный путь транспортного средства будет
                    // отключен.

                    // Остается закрытым, пока активен push-поток, и освобождается VideoSender.onEnded (завершение push-потока).
                    if (videoSender?.isRunning != true) {
                        BtSessionGate.sessionActive = false
                        // Статус завершения гонки: Опоздание 0x4301 Если он находится в состоянии «спаривание не удалось, взаимное
                        // исключение еще не активировано».

                        // Если он попадает в окно, он будет временно сохранен, но никто не будет его потреблять. Здесь еще одно вложение
                        // — подождите 15 секунд, пока машина и машина.

                        // Если никто не подключится, цепочка разорвется. Если вы промахнетесь по этому броску, весь раунд провалится
                        // (анализ 18 октября 2026 г.).

                        val pending = pendingEndpointUpdate
                        if (pending != null && !connectCancelled) {
                            pendingEndpointUpdate = null
                            appendLog(
                                ">>> В конце выяснилось, что временное хранилище опоздало 0x4301 - немедленно " +
                                    "перезапускаем раунд по новой конечной точке",
                            )
                            Bg.run("late-endpoint-retry") {
                                onCarPlayEndpoint(pending, "0x4301 Поздно (дополнительные инвестиции в конце этого раунда)")
                            }
                        }
                    }
                }
            }
        }
        // Если вы уже привязали сеть (autoJoinCarWifi только что привязался и перерегистрировался), не повторяйте
        // процесс присоединения еще раз:

        // WifiCarPlayConnector внутри первого выпуска() →bindProcessToNetwork(null),
        // Он прервет вновь зарегистрированную трансляцию ctrl mDNS и дождется, пока система восстановит привязку.
        val wifiConfig =
            Iap2SenderSession.WifiConfiguration(
                endpoint.ssid,
                endpoint.passphrase,
                endpoint.channel,
                endpoint.securityType,
            )
        if (boundCarSsid == endpoint.ssid && wifiConnector?.currentNetwork != null) {
            appendLog(">>> [$source] Автомобильная точка доступа ${endpoint.ssid} привязана, пропустите повторную привязку")
            continueWithNetwork(true)
        } else {
            // Конечная точка прибывает до 0x5703 (или предыдущая ротация сети не удалась): та же запись – тихий путь имеет приоритет,
            // В случае сбоя он вернется к сети конкретного приложения. Не нажимайте TRANSPORT_WIFI, чтобы слепо привязать
            // любой текущий Wi-Fi:

            // Во время отключения сети он будет привязан к старой сети, и AirPlay точно не сможет подключиться.
            Bg.run("wifi-join-on-endpoint") {
                while (!connectCancelled) {
                    val shellReady =
                        appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
                    when (val result = joinCarWifi(wifiConfig, shellReady)) {
                        is WifiCarPlayConnector.JoinResult.Joined -> {
                            continueWithNetwork(true)
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unsupported -> {
                            // WEP и т. д. не могут быть выражены с помощью спецификатора: перед привязкой подождите, пока пользователь
                            // подключится к системной WLAN.

                            waitForManualWifiThenContinue(wifiConfig) { bound ->
                                continueWithNetwork(bound)
                            }
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unavailable -> {
                            promptSpecifierFailed(wifiConfig, shellReady)
                            // PromptSpecifierFailed внутри, возможно, повторил попытку или вернулся к ручному режиму; оба
                            // Продолжите процесс через joinCarWifiSpecifier → continueWithNetwork,
                            // Этот раунд заканчивается только в случае полного провала.
                            if (boundCarSsid == wifiConfig.ssid && wifiConnector?.currentNetwork != null) {
                                continueWithNetwork(true)
                            } else {
                                sessionStarting.set(false)
                            }
                            return@run
                        }
                    }
                }
                sessionStarting.set(false)
            }
        }
    }

    /** Резервное копирование вручную, когда спецификатор недоступен: опросите системную WLAN, чтобы узнать, подключена ли она к целевой точке доступа, а затем привяжите ее. */
    private fun waitForManualWifiThenContinue(
        wifi: Iap2SenderSession.WifiConfiguration,
        onBound: (Boolean) -> Unit,
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + CAR_WIFI_BIND_WAIT_MS
        var lastHintAt = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            val current = shellWifiStatus().second
            if (current == wifi.ssid) {
                onBound(bindCarWifiAndRegister(wifi.ssid))
                return
            }
            if (android.os.SystemClock.uptimeMillis() - lastHintAt > 5_000) {
                lastHintAt = android.os.SystemClock.uptimeMillis()
                phaseHint = "Ожидание подключения телефона к автомобильной точке доступа ${wifi.ssid}…"
                appendLog(
                    "WIFI: Ожидание подключения пользователя к автомобильной точке доступа в WLAN системы (текущий " +
                        "${current ?: "не подключено"}）…",
                )
                openWlanSettings()
            }
            Thread.sleep(1_000)
        }
        if (!connectCancelled) {
            phaseHint = "Телефон не подключен к автомобильной точке доступа. Чтобы подключиться к ${wifi.ssid}, перейдите в настройки WLAN."
            appendLog(
                ">>> Ожидание истечения времени ожидания точки доступа в автомобиле. Откройте «Настройки» > " +
                    "«WLAN», чтобы присоединиться к \"${wifi.ssid}\" и переподключитесь.",
            )
            uiHintSink?.invoke(
                "Не подключено к автомобильной точке доступа \"${wifi.ssid}\". Чтобы активировать " +
                    "подключение, перейдите в настройки WLAN.",
            )
        }
        onBound(false)
    }

    /** Подсчет низкой частоты событий касания/туннеля (перенесен из поля MainActivity, чтобы обеспечить единообразие поведения регулирования журнала). */
    @Volatile
    private var touchLogCount = 0

    /** Подсчет сообщений «освобождения» медиа-ключа автомобиля-машины (1-байтовый отчет со значением 0, автоматически отбрасывается, учитывается только для справки). */
    @Volatile
    private var consumerReleaseCount = 0

    @Volatile
    private var iapEventMessageCount = 0
}

/** Решение о полном пути после сбоя сопряжения (чистая функция, сценарий захвата пакетов на реальном компьютере определяется PairingFallbackTest). */
internal data class PairingFallback(
    /** Двухэтапный хак «Нарушьте контроль повторения BT и повторите попытку» (только в режиме тестового стенда DiPlay). */
    val useBtRetireHack: Boolean,
    /** et=NONE Понижение сеанса открытым текстом (старая машина/сопряжение до HomeKit полностью отклонено, но RTSP подключен). */
    val usePlainSession: Boolean,
)

/**
 * Основа принятия решения  (системная диагностика реальной машины 21 октября 2026 г., docs/PROTOCOL-NOTES.md §13a):
 * Любой этап 403 = «Доверие iAP2 установлено, и транспортное средство ожидает своей готовности» — первый отказ в
 * соединении является неотъемлемым состоянием транспортного средства.
 * Он автоматически повторно пригласит вас через 18–20 секунд и будет готов во второй раз. В этом состоянии BT
 * прерывается (перестроение всего стека занимает 30~40 секунд, просто не хватает повторного приглашения)
 * окно  ) и понижение рейтинга открытым текстом (должно быть отклонено и окно повторного приглашения потрачено
 * впустую) являются обратными операциями, и обе отключены;
 * Цикл сопряжения повторяет попытку в пределах окна с задержкой в ​​5 секунд (см. PairingRetryBackoffMs).
 */
internal fun pairingFallback(
    keysEstablished: Boolean,
    noPairing: Boolean,
    pairSetupRejected: Boolean,
    pairingForbidden: Boolean,
    attemptedPairing: Boolean,
    pairingUnsupported: Boolean,
): PairingFallback {
    if (keysEstablished) return PairingFallback(useBtRetireHack = false, usePlainSession = false)
    val useBtRetireHack = !noPairing && !pairSetupRejected && !pairingForbidden && attemptedPairing
    val usePlainSession = !pairingForbidden && (pairingUnsupported || attemptedPairing)
    return PairingFallback(useBtRetireHack = useBtRetireHack, usePlainSession = usePlainSession)
}
