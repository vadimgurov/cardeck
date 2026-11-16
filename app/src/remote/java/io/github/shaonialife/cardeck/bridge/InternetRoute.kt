package io.github.shaonialife.cardeck.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Селектор интернет-каналов Debug Bridge: отслеживает доступные сотовые сети в качестве **резервного**.
 *
 * Зачем это нужно (настройка сеанса реального автомобиля DiPlay 21 октября 2026 г.): Чтобы присоединиться к
 * автомобильной точке доступа, используйте
 * `WifiNetworkSpecifier` + сеть привязки процесса - во время сеанса** все сокеты этого процесса по умолчанию маршрутизируются на
 * Автомобильная точка доступа  ***, но в точке доступа нет Интернета, WebSocket моста отладки отключается за
 * миллисекунду «присоединения к точке доступа».
 * Мультисетевая розетка   — стандартное решение для Android: собираем сокет из сотового `Network.socketFactory`,
 * DNS также использует эту сеть и отделен от маршрута процесса по умолчанию. Журналы собираются как обычно во время автомобильной сессии.
 *
 * Стратегию выбора   см. в разделе [current]: Сеть по умолчанию проверена (обычный ежедневный статус
 * Wi-Fi/сотовой связи) → перейти к настройкам по умолчанию;
 * непроверенный (автомобильная точка доступа) → сотовая связь. Причины отслеживать сотовую связь, но не Wi-Fi:
 * такие вещи, как автомобильные точки доступа
 * Сеть спецификатора   также может сообщать NET_CAPABILITY_INTERNET (не проверено),
 * Нажмите «WiFi с ИНТЕРНЕТОМ», и будет выбрана точка доступа в автомобиле.
 */
class InternetRoute(
    context: Context,
) {
    private val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var cellular: Network? = null

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellular = network
            }

            override fun onLost(network: Network) {
                if (cellular == network) cellular = null
            }
        }

    fun start() {
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        cellular = null
    }

    /** Текущий интернет-канал с наивысшим приоритетом; null = Сеть процесса по умолчанию доступна, и маршрут по умолчанию системы используется напрямую.
     *
     * Сетевой диапазон       по умолчанию ПОДТВЕРЖДЕН (реальный доступ в Интернет, например, домашний/рабочий
     * Wi-Fi), поэтому отдайте ему приоритет и уходите безоговорочно.
     * С сотовой связью       дела обстоят еще хуже: сигнал сотовой связи в среде Wi-Fi в помещении слабый, а
     * APK-файл объемом 15 МБ отключается на полпути фактического измерения.
     * (неожиданное окончание трансляции, 21 октября 2026 г.). Сеть процесса по умолчанию во время сеанса работы с машиной автомобиля:
     * Автомобильная точка доступа       (нет Интернета, не ПРОВЕРЕНО), в это время выйдите только из сотового телефона.
     * **Но VALIDATED — это решение кэша ≠ доступно в настоящее время** (фактическое тестирование 22 октября 2026 г.:
     * точка доступа на тестовом стенде не имеет восходящего канала
     * Интернет по-прежнему помечен как ПРОВЕРЕННЫЙ, переподключается в каждом раунде ENETUNREACH и никогда не меняет
     * путь) — сеть по умолчанию в последнее время
     * При сбое соединения ([noteFailure]) происходит переключение на сотовую сеть; если это удастся ([noteSuccess]),
     * или это будет сеть по умолчанию
     */
    fun current(): Network? {
        val active = cm.activeNetwork ?: return cellular
        // toString() — это десятичная строка netId (общедоступный способ получения сетевой идентификации): замена сети по умолчанию.
        // (Отвязка/отключение сети после завершения сеанса) рассматривается как новая сеть, и решение о сбое сбрасывается.
        val netKey = active.toString()
        if (netKey != lastActiveNetKey) {
            lastActiveNetKey = netKey
            defaultFailStreak = 0
        }
        val caps = cm.getNetworkCapabilities(active) ?: return cellular
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return cellular
        if (defaultFailStreak > 0) return cellular
        return null
    }

    /** Если соединение с сетью по умолчанию не удается один раз, если соединение прерывается постоянно, ячейка будет меняться до тех пор, пока сеть не изменится или не будет успешно сброшена. */
    fun noteFailure(usedDefaultNetwork: Boolean) {
        if (usedDefaultNetwork) defaultFailStreak++
    }

    /** Соединение через сеть по умолчанию установлено успешно: отмените решение об ошибке и восстановите быстрый путь по умолчанию, который дает приоритет. */
    fun noteSuccess(usedDefaultNetwork: Boolean) {
        if (usedDefaultNetwork) defaultFailStreak = 0
    }

    @Volatile
    private var defaultFailStreak = 0

    private var lastActiveNetKey: String? = null
}
