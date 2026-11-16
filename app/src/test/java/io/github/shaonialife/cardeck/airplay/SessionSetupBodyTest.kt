package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Защита от регрессии тела сообщения SETUP на уровне сеанса.
 *
 * WinPlay (проверено 19 октября 2026 г.) фильтрует сеансы по «адресу iPhone Bluetooth, выбранному пользователем».
 * ID устройства в теле сообщения   не соответствует прямому 403 — использовалось поддельное значение, записанное catplay.
 * ff:ee:dd:cc:bb:aa, похоже, соединение автоматически отключается примерно через 45 секунд (принимающая сторона
 * ожидает истечения времени ожидания видео).
 */
class SessionSetupBodyTest {
    private fun client() =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "DESKTOP-43KNTO6",
                passphrase = "secret",
                channel = 0,
                securityType = 2,
                airPlayPort = 17000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf("192.168.137.1"),
            ),
            log = {},
        )

    @Test
    fun deviceIdIsCarriedVerbatimIntoSetupBody() {
        val body = client().buildSessionSetupBody("10:C1:97:1A:C7:44", timingPort = 35449)
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertEquals("10:C1:97:1A:C7:44", root["deviceID"])
    }

    @Test
    fun deviceIdIsNotTheCatplayPlaceholder() {
        val body = client().buildSessionSetupBody("10:C1:97:1A:C7:44", timingPort = 35449)
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertNotEquals("ff:ee:dd:cc:bb:aa", root["deviceID"])
    }

    @Test
    fun bodyKeepsTheFieldsReceiversNegotiateOn() {
        val port = 35449
        val body = client().buildSessionSetupBody("10:C1:97:1A:C7:44", port)
        val root = BinaryPlist.parse(body) as Map<*, *>
        // TimingPort — это запись синхронизации часов мультимедиа; функции определяют включенные функции (если они
        // отсутствуют, видеоконвейер не включен)

        assertEquals(port.toLong(), root["timingPort"])
        assertTrue((root["features"] as List<*>).contains("viewAreas"))
        assertEquals(true, root["keepAliveLowPower"])
    }

    /**
     * Непарные (et=NONE) сеансы должны явно приносить `et=1` (кошачья игра `AirPlayEncryptionType::NONE`).
     * Старая машина       до HomeKit распознает только текстовые разговоры; когда этот ключ отсутствует, принимающая
     * сторона будет строго рассматривать его как
     * Отклонено       «Шифрование заявлено, но нет ключа».
     */
    @Test
    fun unpairedSessionDeclaresEtNone() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("10:C1:97:1A:C7:44", 35449, encrypted = false),
            ) as Map<*, *>
        assertEquals(1L, root["et"])
        assertEquals(false, root["updateSessionRequest"])
    }

    @Test
    fun pairedSessionOmitsEt() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("10:C1:97:1A:C7:44", 35449, encrypted = true),
            ) as Map<*, *>
        assertNull(root["et"])
    }
}
