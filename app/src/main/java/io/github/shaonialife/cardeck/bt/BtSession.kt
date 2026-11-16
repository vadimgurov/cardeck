package io.github.shaonialife.cardeck.bt

import android.content.Context
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.util.DeviceIdentity

/**
 * Основной процесс сеанса управления BT (общий для Сервера/Клиента, почти скопированный из двух предыдущих):
 * Создание сеанса отправителя → конечная точка доставки фазы 1 → поддержание активности (блокировка до тех пор, пока канал не отключится).
 */
internal fun Iap2LinkChannel.runSenderSession(
    context: Context,
    timeoutMillis: Long,
    log: (String) -> Unit,
    onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit,
    onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onLinkClosed: () -> Unit = {},
    /** SSID точки доступа автомобиля и машины, известный в этом процессе (он будет использоваться, когда нет новых учетных данных, чтобы избежать отключения соединения в каждом раунде). */
    knownCarSsid: () -> String? = { null },
    /** 0x1D01 Генерация ссылки для определения портрета (CLASSIC_MIB3/EVENT_LINK), обратный вызов после завершения этапа 1. */
    onGeneration: (ReceiverProfile.LinkGeneration) -> Unit = {},
) {
    val session =
        Iap2SenderSession(
            this,
            { line -> log(line) },
            resolveBluetoothMac(context, log),
            DeviceIdentity.deviceName(context),
            onWifiCredentials,
            onEndpointUpdate,
            knownCarSsid,
            // Длина запроса MFi сохраняется в соответствии с идентификацией транспортного средства (за исключением бесшумной
            // пробной версии 20B в течение 6 секунд).
            context.applicationContext,
        )
    val endpoint = session.run(timeoutMillis)
    onGeneration(session.linkGeneration)
    onEndpoint(endpoint)
    // Переход к этапу 2: поддержание работоспособности канала управления и запись дополнительных сообщений;
    // Сопряжение AirPlay инициируется обратным вызовом onEndpoint.

    session.pumpIncomingForever()
    // Связь отключена (насос поддержания активности отключается): уведомите главный элемент управления об отключении
    // «Обработки сеанса» - в противном случае транспортное средство и машина будут повторно соединены после отключения связи.

    // Новая конечная точка будет заблокирована зомби-раундами (исправлена реальная машина Audi 3315 22 октября 2026
    // г., см. SessionController.onSenderLinkClosed).

    onLinkClosed()
}

/**
 * анализирует реальный MAC-адрес Bluetooth при запуске сеанса (для 0x4300/0x4E0E).
 *
 * **требуется ForceRetry**: `DeviceIdentity` имеет 10-секундное окно молчания для сбоев синтаксического анализа,
 * в то время как сеансы BT, как правило,
 * Та же миллисекундная конструкция, в которой Шизуку только что готова — и все это в течение 10 секунд после
 * неудачного первого синтаксического анализа (Шизуку еще не готова)
 * Вызовы   напрямую возвращают заполнитель `02:00:00:00:00:00`. Audi будет использовать идентификатор передачи 0x4300 для связи с RFCOMM.
 * Проверьте, значение-заполнитель приводит к его **тихому удалению** (канальный уровень подтверждает как обычно,
 * а уровень CSM больше не отвечает).
 * 19 октября 2026 г. Audi 0.1.7 Журнал находится здесь: все колесо является значением-заполнителем, а машина никогда не выдавала 0x4301.
 */
private fun resolveBluetoothMac(
    context: Context,
    log: (String) -> Unit,
): String {
    val cached = DeviceIdentity.cachedBluetoothMac()
    if (cached != null) return cached
    val mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
    if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
        log(
            "BT: ⚠ Не удалось проанализировать настоящий MAC-адрес Bluetooth (все еще значение-заполнитель) — автомобиль " +
                "проверит идентификацию передачи 0x4300," +
                "Значение заполнителя может привести к тому, что 0x4301 не будет выдан. Пожалуйста, подтвердите, что " +
                "Shizuku активирован и авторизован.",
        )
    } else {
        log(
            "BT: встроенный Bluetooth MAC=$mac (0x4300/0x4E0E имеет то же происхождение, что и идентификатор TXT " +
                "широковещательной передачи ctrl)",
        )
    }
    return mac
}
