package io.github.shaonialife.cardeck.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Обнаружение службы mDNS: найдите широковещательную передачу _airplay._tcp на принимающей стороне (автомобильный компьютер/xcertplay),
 * анализирует конечную точку AirPlay и запись TXT (deviceid/features/pi/pk/model…).
 *
 * В реальной временной последовательности этот шаг происходит после присоединения к автомобильной точке доступа
 * и перед началом основного сеанса AirPlay.
 */
class CarPlayServiceBrowser(
    context: Context,
    private val log: (String) -> Unit,
    /** Принятие результата: кэшировать/сообщать только реальные конечные точки автомобиля и машины (mDNS будет смешиваться с другими устройствами AirPlay, когда телефон имеет двойное подключение). */
    private val accept: (NsdServiceInfo) -> Boolean = { true },
    private val onEndpoint: (NsdServiceInfo) -> Unit = {},
) {
    companion object {
        const val AIRPLAY_TYPE = "_airplay._tcp"
        const val CARPLAY_CTRL_TYPE = "_carplay-ctrl._tcp"
        private const val TAG = "NSD"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discovering = false

    /**
     * был преобразован в `_airplay._tcp` (этот сегмент сети). В тот момент, когда вы присоединитесь к автомобильной
     * точке доступа, вы начнете делать открытия в фоновом режиме.
     * обычно срабатывает в тот момент, когда адрес конечной точки действительно необходим — сравните с кошачьей
     * игрой: он использует резидентный кеш Bonjour +
     * `watch`, адрес готов при получении приглашения ctrl; мы только что проверили перед сопряжением и зря ждали несколько секунд.
     */
    @Volatile
    private var resolved: NsdServiceInfo? = null

    /** Очистите предыдущий раунд кеша при запуске нового сеанса (возможно, автомобиль изменил свой адрес/порт). */
    fun resetCache() {
        resolved = null
    }

    /**
     * и т. д. результаты анализа (кэш опроса). Возвращает ноль = тайм-аут еще не найден.
     * Вызывающий абонент обычно продолжает выполнять другие действия (привязку к сети, рукопожатие) во время
     * ожидания, а не просто блокирует спящий режим.
     */
    fun awaitEndpoint(timeoutMillis: Long): NsdServiceInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            resolved?.let { return it }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return resolved
            }
        }
        return resolved
    }

    fun start(serviceType: String = AIRPLAY_TYPE) {
        if (discovering) {
            log("$TAG: Уже сканирую")
            return
        }
        log("$TAG: начните открывать $serviceType")
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        discovering = true
    }

    fun stop() {
        if (!discovering) return
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        discovering = false
        log("$TAG: Остановить обнаружение")
    }

    private val discoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                discovering = false
                log("$TAG: Обнаружен код ошибки запуска = $errorCode.")
            }

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                log("$TAG: код ошибки остановки = $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                log("$TAG: Найден сервис ${serviceInfo.serviceName} (${serviceInfo.serviceType}), разбор...")
                resolveWithRetry(serviceInfo, attempts = 3)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                log("$TAG: Служба исчезла ${serviceInfo.serviceName}")
            }
        }

    private fun resolveWithRetry(
        serviceInfo: NsdServiceInfo,
        attempts: Int,
    ) {
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempts > 1) {
                        Thread {
                            Thread.sleep(200)
                            resolveWithRetry(info, attempts - 1)
                        }.start()
                    } else {
                        log("$TAG: не удалось выполнить синтаксический анализ кода ${info.serviceName} = $errorCode.")
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: "no-host"
                    val txt =
                        info.attributes.entries.joinToString(", ") { (key, value) ->
                            val text =
                                value
                                    ?.toString(Charsets.UTF_8)
                                    ?.trimEnd { it.code == 0 }
                                    .orEmpty()
                            "$key=$text"
                        }
                    // Принятие размещается перед кэшированием: конечные точки, не являющиеся автомобильными (например, Mac на том же
                    // Wi-Fi), не должны загрязнять awaitEndpoint.

                    val accepted = accept(info)
                    log(
                        "$TAG: OK ${info.serviceName} -> " +
                            "$host:${info.port}${if (accepted) "" else "（Неавтомобильное транспортное средство，игнорировать）"}",
                    )
                    if (txt.isNotEmpty()) log("$TAG: TXT $txt")
                    if (accepted) resolved = info
                    onEndpoint(info)
                }
            }
        nsdManager.resolveService(serviceInfo, listener)
    }
}
