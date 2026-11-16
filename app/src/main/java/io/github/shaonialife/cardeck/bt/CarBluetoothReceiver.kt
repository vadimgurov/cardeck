package io.github.shaonialife.cardeck.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.shaonialife.cardeck.service.CarPlayService
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * автоматически подключается при посадке в автобус/автоматически закрывает кабинку при выходе из автобуса.
 *
 * **Зачем использовать статический приемник манифеста**: `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` в
 * находится в списке исключений неявного вещания (четко указан в документации разработчика Android «Неявные
 * исключения вещания»), поэтому даже если
 * Процесс приложения   убит, и процесс можно будет подтянуть в момент подключения автомобиля по Bluetooth.
 *
 * **Почему можно запустить службу переднего плана**: Android 12+ запрещает запуск FGS в фоновом режиме, и ее **нет** в списке исключений
 * «Трансляция по Bluetooth»; это приложение можно использовать, поскольку оно имеет `SYSTEM_ALERT_WINDOW` (темный экран/сенсорный датчик).
 * зависит от этого) - это официальное исключение (требуются только разрешения под targetSdk 34; Android 15+ дополнительно
 * Для   в данный момент требуется видимое плавающее окно). Если разрешение на плавающее окно не предоставлено,
 * `startForegroundService` выдаст
 * `ForegroundServiceStartNotAllowedException`, здесь записываются только логи - пользователь открывает приложение вручную
 * можно подключить один раз (в это время активность видна и не ограничена).
 *
 * **Целевая фильтрация**: установка приоритета целевого транспортного средства, выбранного пользователем
 * ([TargetDevicePrefs]); если не выбрано, нажмите название автомобиля
 * Ключевые слова  . Подключения ACL, такие как наушники/часы, не будут активированы случайно.
 *
 * Аудио Примечание. При автоматическом подключении передается только видео. Для AudioPlaybackCapture требуется
 * токен авторизации MediaProjection.
 * Система   не позволяет осуществлять автоматическое получение без токена (приложение PROJECT_MEDIA является
 * лишь одним из шлюзов и не генерирует токен).
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED
        ) {
            return
        }
        val device = deviceOf(intent) ?: return
        val app = context.applicationContext
        // Переключатель «Автоматически подключаться при посадке в машину» (Настройки → Дисплей, по умолчанию включено)
        if (!AutoConnectPrefs.enabled(app)) return
        // Эти два доступа требуют BLUETOOTH_CONNECT; сдаться, если не разрешено (невозможно определить, целевая это машина или нет)
        val address = runCatching { device.address }.getOrNull() ?: return
        val name = runCatching { device.name }.getOrNull()

        if (!isCarDevice(app, address, name)) {
            DebugFileLog.append(app, "АВТО: игнорировать неавтомобильный Bluetooth ${name ?: "?"}（$address）")
            return
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            DebugFileLog.append(app, "АВТО: Bluetooth автомобиля подключен к ${name ?: "?"} ($address) — Автоматически запускать сеанс.")
            runCatching {
                context.startForegroundService(
                    Intent(context, CarPlayService::class.java)
                        .setAction(CarPlayService.ACTION_AUTO_START_SESSION),
                )
            }.onFailure { error ->
                DebugFileLog.append(
                    app,
                    "АВТО: Автоматический запуск был перехвачен системой (${error.javaClass.simpleName}: ${error.message})" +
                        "—— Разрешите параметр «Показывать поверх других приложений» и повторите попытку или вручную " +
                        "откройте приложение, чтобы подключиться один раз.",
                )
            }
        } else {
            DebugFileLog.append(app, "АВТО: Bluetooth автомобиля отключен ${name ?: "?"} ($address) — — Завершить сеанс.")
            // Когда сеанс запущен, служба является процессом переднего плана, startService это разрешает; если он не
            // запущен, это означает, что сеанса вообще нет.

            runCatching {
                context.startService(
                    Intent(context, CarPlayService::class.java)
                        .setAction(CarPlayService.ACTION_AUTO_STOP_SESSION),
                )
            }
        }
    }

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun isCarDevice(
        context: Context,
        address: String,
        name: String?,
    ): Boolean {
        val target = TargetDevicePrefs.get(context)
        if (target != null) return TargetDevicePrefs.matches(target, address)
        val label = name ?: return false
        return CAR_KEYWORDS.any { label.contains(it, ignoreCase = true) }
    }

    private companion object {
        /** Что нужно знать, если целевой автомобиль не выбран: общие ключевые слова имени автомобиля и машины Bluetooth (реальный автомобиль + испытательный стенд). */
        val CAR_KEYWORDS = listOf("BMW", "MINI", "AUDI", "PORSCHE", "MERCEDES", "BENZ", "VOLVO", "CARPLAY")
    }
}
