package io.github.shaonialife.cardeck.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import io.github.shaonialife.cardeck.util.Bg
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * использует точку доступа автомобиля для передачи трафика CarPlay.
 *
 * **Два способа доступа к сети (автоматическая командная строка по умолчанию, начиная с 22 октября 2026 г.)**:
 * — Тихий путь (по умолчанию, см. `SessionController.joinCarWifiSilently`): выполнение Шизуку.
 * `cmd wifi connect-network`, система напрямую подключается к точке доступа (**0 всплывающих окон**), а затем
 * [bindCurrentWifi] захватывает дескриптор `Network` и связывает его с `requestNetwork` без спецификатора.
 * - [joinCarPlayNetwork]: `WifiNetworkSpecifier` Сеть для конкретного приложения - запасной вариант, когда молчащие пути недоступны.
 * Сеть   видна только этому процессу (она не сохраняется и не становится сетью по умолчанию), но при первом
 * присоединении появится окно подтверждения системы.
 *
 * Ключевые моменты:
 * - Если в запросе не указан `NET_CAPABILITY_INTERNET`, то система не отключится по причине "нет интернета";
 * — перед созданием сокета/регистрацией mDNS оба пути должны быть `bindProcessToNetwork` — широковещательная рассылка mDNS
 * (`NsdManager.registerService`) не имеет параметра сети и может использовать только сеть по умолчанию процесса;
 * — SSID должен точно совпадать (имя точки доступа может содержать пробелы/китайский язык): тихий путь
 * проверяется с помощью `cmd wifi status`.
 */
class WifiCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** Результаты подключения для сетей конкретного приложения. */
    sealed interface JoinResult {
        /** Связан и связан с этим процессом; [network] может использоваться для определения NSD/конечной точки. */
        data class Joined(
            val network: Network,
        ) : JoinResult

        /** Система не разрешена (пользователь запрещен/точка доступа вне диапазона/время ожидания истекло). Вы можете попробовать еще раз. */
        data object Unavailable : JoinResult

        /** Этот тип безопасности не может быть выражен с помощью WifiNetworkSpecifier (WEP) — вызывающая сторона использует старый путь. */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    val currentNetwork: Network? get() = boundNetwork.get()
    private val boundNetwork = AtomicReference<Network?>(null)

    /**
     * Вызывается, когда сеть конкретного приложения отключена системой (точка доступа отключена или находится вне
     * зоны действия). Сеансовый уровень предлагает пользователю соответствующие запросы;
     * пустая реализация = все равно (например, привязать пути вручную).
     */
    @Volatile
    var onNetworkLost: ((ssid: String) -> Unit)? = null

    /**
     * — это адрес шлюза текущей подключенной сети (сценарий точки доступа автомобиля = IP-адрес автомобиля).
     * Резервный путь в случае сбоя обнаружения mDNS.
     */
    fun gatewayAddress(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /**
     * IPv4-адрес этого компьютера в текущей подключенной сети (например, 10.174.189.57).
     * используется для фильтрации результатов mDNS: когда мобильный телефон имеет двойное подключение (подключен к
     * автомобильной точке доступа + собственная точка доступа для совместного использования с компьютером),
     * mDNS обнаружит приемник AirPlay компьютера в сети — адреса за пределами этого сегмента сети не являются автомобильными компьютерами.
     */
    fun localIpv4Address(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.firstOrNull { it.contains('.') && !it.contains(':') }
                ?.substringBefore('/')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Все IPv6-адреса этого компьютера в текущей связанной сети.
     *
     * Принятие сегментом сети результатов       mDNS: `_airplay._tcp` Audi 4xxx часто отправляет только локальный IPv6,
     * Старый фильтр определяет только на основе префикса IPv4 и отбрасывает само транспортное средство как
     * «нелокальный сегмент сети» (19 октября 2026 г.).
     */
    fun localIpv6Addresses(): List<String> {
        val network = boundNetwork.get() ?: return emptyList()
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.filter { it.contains(':') }
                ?.map { it.substringBefore('%').substringBefore('/') }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * использует сеть конкретного приложения (`WifiNetworkSpecifier`) для подключения к автомобильной точке доступа
     * — **прикрытие, когда бесшумный путь недоступен**.
     * Когда       присоединяется в первый раз, появляется всплывающее окно подтверждения системы; сеть не будет
     * сохранена и не станет сетью по умолчанию.
     *
     * блокируется до тех пор, пока соединение не будет успешным/система не отклонит/истечет время ожидания
     * (внутренняя фрагментация 200 мс, [isCancelled] может выйти немедленно).
     * После успешного завершения       сеть процесса будет привязана, и вызывающая сторона сможет напрямую
     * зарегистрировать mDNS/создать сокет.
     *
     * Сопоставление типов безопасности       в соответствии с 0x5703 (xcertplay Iap2WirelessSecurity):
     * 0=НЕТ 1=WEP 2=WPA/WPA2 3=переход WPA3 4=только WPA3.
     * WEP не имеет соответствующего API (возврат к [JoinResult.Unsupported], вызывающая сторона выбирает старый путь).
     */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long = 120_000L,
        isCancelled: () -> Boolean = { false },
        /** Быстрое обнаружение при отсутствии обратного вызова в первом окне (обращение оболочки в миллисекундах + привязка ≤3 с, не должно блокировать повторную передачу). */
        quickFallback: (() -> Network?)? = null,
        /** Окно перевыпуска также не имеет глубины после обратного звонка (connect-network, уровень 10s - окно этого раунда прошло, экономия времени для следующего раунда). */
        deepFallback: (() -> Network?)? = null,
    ): JoinResult {
        val specifier =
            buildSpecifier(ssid, passphrase, securityType)
                ?: return JoinResult.Unsupported
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
        // Ритм ожидания разработан в соответствии с «окном машины» (определен реальный журнал машины Audi 3315 2026-10-23): 0x4301
        // Сервисное окно AirPlay закрывается примерно через 15 секунд демонтажа BT на автомобиле и примерно через
        // 21 секунду (опоздание означает ECONNREFUSED, и BT снова будет исправен.

        // Также нельзя активировать) - Бюджет доступа к сети, начиная с 0x5703, составляет всего ~9-14 секунд.
        // спецификатор в приостановленном состоянии

        // («Система сохранила SSID + часть ПЗУ (фактическое тестирование Android 16/Xiaomi)» или забудьте → запросить
        // Сразу после операции запрашивается бюджет **постоянного ожидания** без обратных вызовов) для каждого этапа:
        //  ① Первое окно 5 с (измеряется 0,03–3 с при обычном доступе к сети; отсутствие обратного вызова 5 с означает зависание);
        //  ② Быстрое обнаружение (быстрый возврат: если система самостоятельно подключилась к SSID, она будет привязана напрямую, ≤3 с);
        // ③ Спецификатор повторной отправки (освобождается после зависания фактического теста → повторная отправка через
        // 1,6 с, а затем подключение к сети - ** проверено как действительное)

        //     Средства восстановления должны следовать за обнаружением**), окно 20 с;
        //  ④ Deep Fallback (deepFallback: прямое подключение к сети, уровень 10 с) — этот раунд окон неизбежен.
        // Это прошло. Значение состоит в том, чтобы сначала позволить телефону подключиться к точке доступа, а в
        // следующем раунде (телефон повторно подключится к BT через 0,17 с) использовать ярлык «уже подключено».

        // Уроки первого окна в старые 20-е годы: цепочка была удалена до того, как было завершено первое окно первого
        // раунда (15,2 секунды), что занимает место после первого окна **

        // Попытка выполнения не будет предпринята; во втором раунде 20с+1,6с=21,6с порт 7000 отказался подключаться к сети.
        // Известная стоимость: если пользователь удерживает всплывающее окно авторизации системы при первом подключении
        // более 5 секунд, этот раунд запросов будет отозван и отправлен повторно.

        // Всплывающее окно появляется один раз — единовременные затраты на UX намного меньше, чем сбой всего подключения автомобиля.
        val firstWindowMs = minOf(timeoutMillis, 5_000L)
        val secondWindowMs = minOf(timeoutMillis - firstWindowMs, 20_000L).coerceAtLeast(0)
        val unavailable = AtomicBoolean(false)
        var network = requestAndAwait(ssid, request, firstWindowMs, isCancelled, unavailable)
        // Повторная отправка бессмысленна после того, как система явно отклоняет ее (onUnavailable: пользователь
        // отклонил поле авторизации/точка доступа находится вне зоны действия).

        // Только еще один отказ - надо отличать от pending (нулевой обратный вызов).
        if (network == null && !isCancelled() && !unavailable.get() && secondWindowMs > 0) {
            release()
            if (!isCancelled()) {
                // Успешный быстрый поиск = привязан к точке доступа автомобиля (система автоматически переподключается) и
                // возвращается напрямую;

                // Повторный выпуск спецификатора модели автомобиля, который «сохранен в системе и подключен к точке доступа»,
                // приведет только к повторному зависанию системы (сохранение

                // SSID — одно из условий зависания), короткое замыкание — правильное поведение.
                quickFallback?.invoke()?.let { fallback ->
                    log("Wi-Fi: быстрый доступ к сети успешен ($fallback) — пропустить спецификатор и отправить повторно")
                    return JoinResult.Joined(fallback)
                }
            }
            if (!isCancelled()) {
                log(
                    "WiFi: Нулевой обратный вызов в первом окне ${firstWindowMs / 1000}s - спецификатор может зависнуть, " +
                        "повторно отправить сетевой запрос (измеренная повторная отправка занимает 1,6 с для подключения к сети)",
                )
                network = requestAndAwait(ssid, request, secondWindowMs, isCancelled, unavailable)
            }
            if (network == null && !isCancelled() && !unavailable.get()) {
                release()
                if (!isCancelled()) {
                    deepFallback?.invoke()?.let { fallback ->
                        log("WiFi: успешное глубокое погружение в сеть ($fallback)")
                        return JoinResult.Joined(fallback)
                    }
                }
            }
        }
        if (network == null) {
            log(
                "WiFi: Ожидание присоединения к \"$ssid\" не удалось (первое окно ${firstWindowMs / 1000}s + окно " +
                    "повторной передачи ${secondWindowMs / 1000}s + двухуровневая крышка)",
            )
            release()
            return JoinResult.Unavailable
        }
        return JoinResult.Joined(network)
    }

    /** Инициируйте requestNetwork и подождите, пока окно не завершится/успех/отменится; за освобождение отвечает вызывающий абонент. */
    private fun requestAndAwait(
        ssid: String,
        request: NetworkRequest,
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        unavailable: AtomicBoolean,
    ): Network? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    boundNetwork.set(network)
                    result.set(network)
                    log(
                        "WiFi: подключен к сети приложения \"$ssid\" ($network, bindProcessToNetwork=$bound) – не влияет " +
                            "на работу других приложений в сети",
                    )
                    latch.countDown()
                }

                override fun onUnavailable() {
                    log(
                        "WiFi: Система не позволяет подключиться к \"$ssid\" (пользователь отказывается или точка " +
                            "доступа находится вне зоны действия)",
                    )
                    latch.countDown()
                }

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        connectivityManager.bindProcessToNetwork(null)
                        log("Wi-Fi: сеть приложения \"$ssid\" отключена ($network)")
                        onNetworkLost?.invoke(ssid)
                    }
                }
            }
        // Сначала отмените предыдущий обратный вызов: быстрый методbindCurrentWifi использует слот обратного вызова
        // совместно с этим запросом.

        // Прямая перезапись приведет к тому, что старый обратный вызов останется в ConnectivityManager до тех пор, пока не будет отключен.
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        callback.set(newCallback)
        val accepted =
            try {
                connectivityManager.requestNetwork(request, newCallback)
                true
            } catch (error: Exception) {
                log(
                    "Wi-Fi: не удалось инициировать сетевой запрос для конкретного приложения (${error.message}) — может " +
                        "потребоваться разрешение на определение местоположения или система его не поддерживает.",
                )
                false
            }
        if (!accepted) return null
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) return null
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                return null
            }
        }
        return result.get()
    }

    /** Создает спецификатор; возвращает значение NULL, если WEP или аргумент недействителен (используется старый путь). */
    private fun buildSpecifier(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): WifiNetworkSpecifier? {
        return try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            when (securityType) {
                // Открыть точку доступа: без пароля
                0 -> Unit
                // WEP: WifiNetworkSpecifier не имеет соответствующего API (больше не используется в современных автомобилях).
                1 -> return null
                // Режим перехода WPA/WPA2 и WPA3: точка доступа в переходном режиме также принимает WPA2-PSK.
                2, 3 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
                // только WPA3
                4 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa3Passphrase(passphrase)
                }
                // Неизвестный тип: попробуйте WPA2 с паролем (скорее получится, чем отказаться).
                else -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
            }
            builder.build()
        } catch (error: Exception) {
            log("Wi-Fi: не удалось создать параметры сети для конкретного приложения (${error.message}).")
            null
        }
    }

    /**
     * привязывает «подключенный в данный момент Wi-Fi» — используйте его для захвата дескриптора `Network` после
     * успешного подключения к Интернету из тихой командной строки.
     *
     * Запрос       **без спецификатора** (объявить только TRANSPORT_WIFI, удалить INTERNET), чтобы система не отскочила
     * Любое окно подтверждения также немедленно перезвонит недавно подключенному автомобильному Wi-Fi. Если в точке
     * доступа нет Интернета, система может не
     * установлен в качестве сети по умолчанию и должен быть явно указан в параметреbindProcessToNetwork, чтобы наш
     * сокет/mDNS использовал Wi-Fi.
     * со сторожевым таймером: когда Wi-Fi не подключен, requestNetwork будет молча зависать на неопределенный срок -
     * никаких четких указаний не будет дано через 5 секунд.
     * (запрос остается в ожидании, привязка по-прежнему будет автоматически завершена, а обратный вызов будет
     * выполнен после того, как пользователь присоединится к точке доступа).
     */
    fun bindCurrentWifi(
        /** Привязать SSID целевой точки доступа — требуется для уведомлений об отключении ([onNetworkLost]); передать значение null, если неизвестно (нет уведомлений об отключении). */
        ssidHint: String? = null,
        onResult: (Boolean) -> Unit = {},
    ) {
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        // Одновременное чтение и запись потока обратного вызова сети и потока сторожевого таймера Bg — атомарный флаг
        // обеспечивает видимость и однократную доставку.

        val reported =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    boundNetwork.set(network)
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    log("WiFi: текущая сеть Wi-Fi привязана к $network, bindProcessToNetwork=$bound.")
                    if (reported.compareAndSet(false, true)) onResult(true)
                }

                override fun onUnavailable() {
                    log("Wi-Fi: соединение Wi-Fi отсутствует. Сначала добавьте принимающую точку доступа в настройках системы.")
                    if (reported.compareAndSet(false, true)) onResult(false)
                }

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        // Завершить работу/выйти из области действия: отмените привязку сети процесса, иначе все сокеты продолжат
                        // указывать на мертвую сеть;

                        // И уведомите уровень сеанса (карточка героя не может остановиться на «Подключено»).
                        // Та же семантика, что и у пути спецификатора:bindCurrentWifi — это Audi 3315.
                        // Основной путь, если onLost не пересылается, он остановится на ложном «Подключено», пока не истечет время
                        // контрольного сигнала.

                        // (Проверено и определено 23 октября 2026 г.).
                        connectivityManager.bindProcessToNetwork(null)
                        log("Wi-Fi: привязанная сеть Wi-Fi отключена ($network)")
                        ssidHint?.let { onNetworkLost?.invoke(it) }
                    }
                }
            }
        callback.set(newCallback)
        connectivityManager.requestNetwork(request, newCallback)
        // Подскажите сторожевой таймер (**не определяет успех или неудачу**): запрос сети, когда «телефон не подключен к WiFi»
        // Он будет зависать до тех пор, пока не завершится привязка системы + DCHP, прежде чем перезвонить в Доступном —
        // измеренные пользователи присоединяются вручную

        // До 10 с+ в точке доступа. Здесь мы только даем пользователю ориентир в 5 секунд. Тайм-аут определяется
        // пользователем вызывающего абонента.

        // WIFI_BIND_WAIT_S несет ответственность; не отменяйте запрос здесь (поздний обратный вызов все равно будет привязан правильно).
        Bg.run("wifi-bind-watchdog") {
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@run
            }
            if (!reported.get()) {
                log(
                    "Wi-Fi: он не был привязан в течение 5 секунд (возможно, телефон еще не подключен к точке доступа) — " +
                        "перейдите в «Настройки» > «WLAN», чтобы присоединиться к принимающей точке доступа. После " +
                        "присоединения этот раунд привязки будет завершен автоматически.",
                )
            }
        }
    }

    fun release() {
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        if (boundNetwork.getAndSet(null) != null) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }
}
