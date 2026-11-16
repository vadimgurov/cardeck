package io.github.shaonialife.cardeck.iap2

/**
 * Каталог сообщений с точки зрения «Отправитель (символ iPhone)» в беспроводном сеансе CarPlay.
 *
 * полная синхронизация беспроводной сети (сравните эталон/xcertplay Iap2WirelessControlClient с
 * Процесс захвата пакетов эталонного/carplay-wifi-extractor   README):
 *
 * 1. Установление связи Bluetooth RFCOMM (сначала два устройства должны выполнить сопряжение Bluetooth на системном уровне)
 * 2. Подтверждение канального уровня: узел отправляет FF55 SYN → Наш SYN-ACK → Обе стороны согласовывают параметры FF5A.
 * 3. Мобильный телефон → Аксессуары: StartIdentification(0x1D00)
 * Аксессуары   → мобильный телефон: IdentificationInformation(0x1D01) // Идентификация автомобиля и машины + список возможностей
 * Мобильный телефон   → Аксессуары: Идентификация принята(0x1D02)
 * 4. Сертификация MFi (сегмент 0xAA00, аксессуары зарекомендовали себя; на стороне мобильного телефона проверку можно пропустить)
 * 5. Мобильный телефон → Аксессуары: RequestAccessoryWiFiConfiguration(0x5702)
 * Аксессуар → Мобильный телефон: AccessoryWiFiConfiguration(0x5703) // TLV в виде обычного текста: SSID/пароль/канал
 * 6. Мобильный телефон подключается к точке доступа, mDNS разрешает _airplay._tcp аксессуара.
 * 7. Мобильный телефон → Аксессуары: CarPlayAvailability(0x4300)
 * Аксессуары   → мобильный телефон: CarPlayStartSession(0x4301) // Порт/адрес/открытый ключ AirPlay
 * 8. Мобильный телефон → Аксессуары: WirelessCarPlayUpdate(0x4E0D), DeviceTransportIdentifier(0x4E0E)
 * 9. Основной сеанс AirPlay (стиль RTSP + потоковая передача H.264 + обратная передача HID Touch) — этап 2
 */
object Iap2MessageIds {
    // ----Идентификация----
    const val START_IDENTIFICATION = 0x1D00 // Мобильный телефон → Аксессуары
    const val IDENTIFICATION_INFORMATION = 0x1D01 // Аксессуары → Мобильные телефоны
    const val IDENTIFICATION_ACCEPTED = 0x1D02 // Мобильный телефон → Аксессуары
    const val IDENTIFICATION_REJECTED = 0x1D03 // Аксессуары → Мобильные телефоны

    // ---- Беспроводная CarPlay ----
    const val CARPLAY_AVAILABILITY = 0x4300 // Телефон → Аксессуары: спросите, можете ли вы начать
    const val CARPLAY_START_SESSION = 0x4301 // Аксессуары → Телефон: Параметры беспроводной сети + Конечная точка AirPlay
    const val WIRELESS_CARPLAY_UPDATE = 0x4E0D // Мобильный телефон → Аксессуары: Уведомление о доступности беспроводной сети
    const val DEVICE_TRANSPORT_IDENTIFIER = 0x4E0E // Мобильный телефон → Аксессуары: уведомление о логотипе Bluetooth/USB

    // ----Приветствие о состоянии устройства (пакет сообщений, отправленных настоящим iPhone до 0x4300)----
    const val DEVICE_INFORMATION_UPDATE = 0x4E09 // Мобильный телефон → Аксессуары: название устройства
    const val DEVICE_LANGUAGE_UPDATE = 0x4E0A // Мобильный → Аксессуары: Язык
    const val DEVICE_TIME_UPDATE = 0x4E0B // Mobile → Аксессуары: Время (u64 эпоха + u16 tz + u8 dst)
    const val DEVICE_UUID_UPDATE = 0x4E0C // Мобильный → Аксессуары: UUID

    // ---- Обмен конфигурацией WiFi ----
    const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702 // Мобильный телефон → Аксессуары
    const val ACCESSORY_WIFI_CONFIGURATION = 0x5703 // Аксессуары → Мобильные телефоны

    // ---- HID (Этап 2: контактная транзитная связь от автомобиля к машине) ----
    const val START_HID = 0x6800 // Аксессуары → Мобильные телефоны
    const val DEVICE_HID_REPORT = 0x6801 // Мобильный телефон → Аксессуары (Ответ)
    const val ACCESSORY_HID_REPORT = 0x6802 // Аксессуары → Мобильные телефоны (Touch/Knob Events)
    const val STOP_HID = 0x6803

    // ---- Аутентификация MFi (односторонняя: мобильный телефон проверяет аксессуары; отправитель может пропустить
    // проверку и самостоятельно сообщить об успехе) ----

    const val MFI_REQUEST_CERTIFICATE = 0xAA00 // Мобильный телефон → Аксессуары
    const val MFI_CERTIFICATE = 0xAA01 // Аксессуары → Мобильный телефон (параметр 0=сертификат)
    const val MFI_REQUEST_CHALLENGE = 0xAA02 // Мобильный телефон → Аксессуары (параметр 0=Вызов 16B)
    const val MFI_RESPONSE = 0xAA03 // Аксессуары → Мобильный телефон (Параметр 0=Подпись)
    const val MFI_AUTHENTICATION_FAILED = 0xAA04 // Мобильный телефон → Аксессуары
    const val MFI_AUTHENTICATION_SUCCEEDED = 0xAA05 // Мобильный телефон → Аксессуары (результаты, сообщаемые вами самими)

    // ----Запрос на подписку отправлен во время подключения аксессуара (просто примите и проигнорируйте)----
    const val START_NOW_PLAYING_UPDATES = 0x5000
    const val START_ROUTE_GUIDANCE_UPDATES = 0x5200
    const val START_POWER_UPDATES = 0xAE00
    const val START_COMMUNICATIONS_UPDATES = 0x4157
    const val START_CALL_STATE_UPDATES = 0x4154

    // ----Сеанс внешнего доступа (сеанс канала № 2 передает данные EA; сенсорный HID-носитель с высокой вероятностью) ----
    const val EA_OPEN_SESSION = 0xEA00 // Мобильный телефон → Аксессуары (явно поддерживается списком дебиторской задолженности Audi)
    const val EA_CLOSE_SESSION = 0xEA01 // Мобильный телефон → Аксессуары
    const val EA_SESSION_STATUS = 0xEA03 // Аксессуары → Мобильные телефоны (четко указано в списке отправки Audi)

    fun nameOf(messageId: Int): String =
        when (messageId) {
            START_IDENTIFICATION -> "StartIdentification"
            IDENTIFICATION_INFORMATION -> "IdentificationInformation"
            IDENTIFICATION_ACCEPTED -> "IdentificationAccepted"
            IDENTIFICATION_REJECTED -> "IdentificationRejected"
            CARPLAY_AVAILABILITY -> "CarPlayAvailability"
            CARPLAY_START_SESSION -> "CarPlayStartSession"
            WIRELESS_CARPLAY_UPDATE -> "WirelessCarPlayUpdate"
            DEVICE_TRANSPORT_IDENTIFIER -> "DeviceTransportIdentifier"
            REQUEST_ACCESSORY_WIFI_CONFIGURATION -> "RequestAccessoryWiFiConfiguration"
            ACCESSORY_WIFI_CONFIGURATION -> "AccessoryWiFiConfiguration"
            START_HID -> "StartHID"
            DEVICE_HID_REPORT -> "DeviceHIDReport"
            ACCESSORY_HID_REPORT -> "AccessoryHIDReport"
            STOP_HID -> "StopHID"
            EA_OPEN_SESSION -> "StartEASession"
            EA_CLOSE_SESSION -> "StopEASession"
            EA_SESSION_STATUS -> "EASessionStatus"
            START_NOW_PLAYING_UPDATES -> "StartNowPlayingUpdates"
            START_ROUTE_GUIDANCE_UPDATES -> "StartRouteGuidanceUpdates"
            START_POWER_UPDATES -> "StartPowerUpdates"
            START_COMMUNICATIONS_UPDATES -> "StartCommunicationsUpdates"
            START_CALL_STATE_UPDATES -> "StartCallStateUpdates"
            else -> "0x%04X".format(messageId)
        }
}
