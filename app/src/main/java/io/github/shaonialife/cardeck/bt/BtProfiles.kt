package io.github.shaonialife.cardeck.bt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Политика Bluetooth «**только сопряжение, без подключения**».
 *
 * Фактическое измерение Audi MIB3 (18 октября 2026 г., отчет пользователя): мобильный телефон и автомобиль
 * находятся в состоянии Bluetooth **подключено**.
 * Когда   (вызов HFP/медиа-профиль A2DP онлайн), автомобиль не открывает службу iAP2 беспроводной системы CarPlay.
 * Окно   — ведет себя так же, как `BT-C: подключитьне удалось（Автомобиль не готов или недоступенНетОкно
 * сопряжения линий）`, повторяет попытки каждые 3 секунды.
 * Беспроводная система CarPlay настоящего iPhone   не зависит от этих файлов конфигурации: канал управления
 * проходит через iAP2 RFCOMM (независимый
 * SDP), звук идет через AirPlay по Wi-Fi. Поэтому правильная форма: **Сопряжение на уровне системы + iAP2.
 * RFCOMM, другие файлы конфигурации подключаться** не будут.
 *
 * Описание реализации: `BluetoothHeadset/A2dp/LeAudio.disconnect(BluetoothDevice)`
 * **@SystemApi** — — Невидим во время компиляции, но для аннотации времени выполнения требуется только `BLUETOOTH_CONNECT`.
 * (уже есть в этом приложении), поэтому отражение настраивается. Для запроса статуса используйте общедоступный `getProfileProxy` +
 * `connectedDevices` (требуется основной Looper, есть в процессе приложения).
 *
 * однажды попытался переместить этот раздел в Shizuku UserService (uid оболочки) для выполнения, но в реальном
 * тестировании это оказалось невозможным:
 * `BluetoothManager.getAdapter()` в   UserService возвращает ноль (применение бесхозяйного Looper
 * контекст), запрос всегда «не подключен», что маскирует истинный статус. Таким образом, это фиксируется в процессе подачи заявки.
 */
object BtProfiles {
    private const val TAG = "BT-PROFILE"

    /** Соответствующие профили (упорядочены по приоритету отключения). */
    private val profiles: List<Int> =
        listOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.LE_AUDIO,
        )

    private fun profileName(profile: Int): String =
        when (profile) {
            BluetoothProfile.HEADSET -> "Звонок(HFP)"
            BluetoothProfile.A2DP -> "Аудио (A2DP)"
            BluetoothProfile.LE_AUDIO -> "Аудио с низким энергопотреблением (LE)"
            else -> "profile#$profile"
        }

    /** идентификатор профиля → Приобретенный агент (повторное использование в процессе; сам агент не имеет состояния сеанса). */
    private val proxies = HashMap<Int, BluetoothProfile>()

    /** Имя профиля, который в данный момент находится в сети (подключен) по этому адресу. */
    fun connectedNames(
        context: Context,
        address: String,
    ): List<String> =
        profiles.mapNotNull { id ->
            val p = proxy(context, id) ?: return@mapNotNull null
            val connected =
                try {
                    @Suppress("DEPRECATION")
                    p.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
                } catch (_: Throwable) {
                    false
                }
            if (connected) profileName(id) else null
        }

    /**
     * пытается отключить все профили от этого устройства.
     *
     * @return Файл конфигурации, который был в сети до отключения (пустой = изначально не подключался, никаких действий не требуется)
     */
    fun disconnect(
        context: Context,
        device: BluetoothDevice,
    ): List<String> {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return emptyList()
            }
        val before = connectedNames(context, address)
        if (before.isEmpty()) return emptyList()
        for (id in profiles) {
            if (profileName(id) !in before) continue
            proxy(context, id)?.let { disconnectProfile(it, device) }
        }
        if (!awaitDisconnected(context, address, 1_500L)) {
            // Отключение на уровне конфигурации не действует (ограничение тока индивидуального ПЗУ): отключение на уровне
            // устройства (@hide, также требуется только

            // BLUETOOTH_CONNECT). Устройства, подключенные в данный момент к автомобилю/наушникам, также могут быть
            // отключены, что и ожидается.

            runCatching { BluetoothDevice::class.java.getMethod("disconnect").invoke(device) }
            awaitDisconnected(context, address, 500L)
        }
        return before
    }

    /** Файл конфигурации, который все еще находится в сети после отключения (используется для журналов, чтобы определить, действительно ли он отключен). */
    fun stillConnected(
        context: Context,
        address: String,
    ): List<String> = connectedNames(context, address)

    private fun awaitDisconnected(
        context: Context,
        address: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (connectedNames(context, address).isEmpty()) return true
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return connectedNames(context, address).isEmpty()
    }

    private fun proxy(
        context: Context,
        profile: Int,
    ): BluetoothProfile? {
        synchronized(proxies) { proxies[profile]?.let { return it } }
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
                ?: return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<BluetoothProfile?>(null)
        val requested =
            try {
                adapter.getProfileProxy(
                    context.applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(
                            id: Int,
                            service: BluetoothProfile,
                        ) {
                            ref.set(service)
                            latch.countDown()
                        }

                        override fun onServiceDisconnected(id: Int) {
                            // Старый прокси становится недействительным после перезапуска службы Bluetooth: очистите кеш и получите его
                            // снова в следующий раз.

                            synchronized(proxies) { proxies.remove(id) }
                            latch.countDown()
                        }
                    },
                    profile,
                )
            } catch (_: Throwable) {
                false
            }
        if (!requested) return null
        // Обратный вызов `getProfileProxy` осуществляется через **Обработчик основного потока**: если этот метод
        // выполняется в основном потоке,

        // Обратный вызов никогда не может быть выполнен → он должен дождаться таймаута (1,2 с) и вернуть ноль. Лучше
        // явно потерпеть неудачу, чем

        // Тихая задержка — вызывающий абонент должен перейти в фоновый поток (все записи этого объекта являются фоновыми вызовами).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.w(TAG, "proxy() вызывается в основном потоке - обратный вызов getProfileProxy будет заблокирован и вернет ноль")
            return null
        }
        if (!latch.await(1_200, TimeUnit.MILLISECONDS)) return null
        return ref.get()?.also { synchronized(proxies) { proxies[profile] = it } }
    }

    /**
     * Отключает профиль. `disconnect(BluetoothDevice)` — это @SystemApi (время компиляции
     * невидимый), вызов отражения; сбой не выдается, вызывающий абонент определяет в соответствии с окончательным статусом.
     */
    private fun disconnectProfile(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
    ): Boolean =
        try {
            proxy.javaClass
                .getMethod("disconnect", BluetoothDevice::class.java)
                .invoke(proxy, device)
                ?.let { it as? Boolean }
                ?: true
        } catch (_: Throwable) {
            false
        }
}
