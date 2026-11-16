package io.github.shaonialife.cardeck.util

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import io.github.shaonialife.cardeck.shizuku.Shell

/**
 * Собственное разрешение идентификации: MAC-адрес Bluetooth и имя устройства (требуется для беспроводного протокола CarPlay).
 *
 * Фон   (тест реального автомобиля Audi MIB3, 9 октября 2026 г.): настройка уровня приложения Android 11+
 * `BluetoothAdapter.getAddress()` получит только заполнитель `02:00:00:00:00:00`;
 * и автомобильный компьютер анализирует беспроводную группу (bluetoothTransportIdentifier) 0x4300 и последующих
 * 0x4E0E будет проверен на предмет соответствия фактическому партнеру RFCOMM — значение-заполнитель приводит к
 * автоматическому удалению транспортного средства.
 * (Явления: канальный уровень подтверждает как обычно, уровень CSM больше не отвечает, и 0x4301 невозможно дождаться).
 *
 * Порядок разбора  :
 * 1. Оболочка Шизуку: `content query --uri content://settings/secure/bluetooth_address`
 * (параметры безопасности системы, читаемые с идентификатором оболочки, без root-доступа)
 * 2. `Settings.Secure` прямое чтение (большинство моделей не могут прочитать его в обычных приложениях, поэтому будьте осторожны)
 * Успешные результаты постоянно кэшируются; сбой не повторяется в течение 10 секунд (что позволяет избежать
 * блокировки на 4 секунды для каждого соединения).
 */
object DeviceIdentity {
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    @Volatile
    private var cachedMac: String? = null

    @Volatile
    private var lastAttemptAtMillis = 0L

    /**
     * Real Bluetooth MAC (формат двоеточия в верхнем регистре); ошибка синтаксического анализа возвращает заполнитель.
     *
     * @param ForceRetry Игнорируйте окно без вывода сообщений и немедленно повторите попытку. После того, как
     * трансляция ctrl перейдет на «Начать соединение»,
     * Первая регистрация       может догнать Shizuku, которая еще не готова и может получить только
     * значение-заполнитель; пока машина нажимает идентификатор TXT
     * (=настоящий MAC-адрес Bluetooth) соответствует тому, приглашать или нет — значение заполнителя равно никогда
     * не приглашалось. Обнаружено на стороне трансляции
     * должен иметь возможность немедленно повторить попытку при занятии позиции, а не ждать, пока истечет 10-секундное окно молчания.
     */
    fun bluetoothMac(
        context: Context,
        forceRetry: Boolean = false,
    ): String {
        cachedMac?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (!forceRetry && now - lastAttemptAtMillis < RETRY_QUIET_MILLIS) return PLACEHOLDER_MAC
        synchronized(this) {
            cachedMac?.let { return it }
            lastAttemptAtMillis = now
            val resolved = resolveViaShizuku() ?: resolveViaSettings(context) ?: PLACEHOLDER_MAC
            if (resolved != PLACEHOLDER_MAC) cachedMac = resolved
            return resolved
        }
    }

    /**
     * Собственный MAC-адрес Bluetooth, который был проанализирован и кэширован; еще не был успешно проанализирован и
     * возвращает значение null (не запускает запрос оболочки).
     *
     * используется для НАСТРОЙКИ уровня сеанса AirPlay, которую «нельзя заблокировать в оболочке» —
     * Стадия BT беспроводного CarPlay       должна сначала вызвать [bluetoothMac], и кеш будет готов, когда он придет сюда.
     */
    fun cachedBluetoothMac(): String? = cachedMac

    /** Имя устройства (используется 0x4E09 DeviceInformationUpdate; имя Bluetooth, отправленное реальным компьютером, является локальным именем Bluetooth). */
    fun deviceName(context: Context): String =
        try {
            context
                .getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: "iPhone"
        } catch (_: Exception) {
            "iPhone"
        }

    private fun resolveViaShizuku(): String? =
        try {
            val result =
                Shell.exec(
                    "content query --uri content://settings/secure/bluetooth_address",
                    timeoutMs = 4_000,
                )
            MAC_REGEX.find(result.output)?.value?.uppercase()
        } catch (_: Exception) {
            null
        }

    private fun resolveViaSettings(context: Context): String? =
        try {
            Settings.Secure
                .getString(context.contentResolver, "bluetooth_address")
                ?.takeIf { MAC_REGEX.matches(it) }
                ?.uppercase()
        } catch (_: Exception) {
            null
        }

    private const val RETRY_QUIET_MILLIS = 10_000L
}
