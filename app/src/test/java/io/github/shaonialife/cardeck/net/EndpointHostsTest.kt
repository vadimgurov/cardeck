package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кандидат адреса получателя  и разрешение функций mDNS.
 *
 * Оба являются кандидатами на основную причину «старая машина не может подключиться» (19 октября 2026 г., Audi 4xxx / CarPlay 210.81):
 * — старый код распознает только IPv4 (`contains('.')`) → транспортное средство, которое публикует только
 * локальный IPv6, считается не имеющим адреса;
 * — старый код не считывает функции TXT → Невозможно распознать автомобиль до HomeKit, работающий без окна сопряжения 120 секунд.
 */
class EndpointHostsTest {
    @Test
    fun ipv6OnlyEndpointIsUsable() {
        assertTrue(EndpointHosts.hasUsable(listOf("fe80::f822:90ff:fe13:3b18%wlan0")))
        assertTrue(EndpointHosts.hasUsable(listOf("fe80::1")))
        assertFalse(EndpointHosts.hasUsable(emptyList()))
        assertFalse(EndpointHosts.hasUsable(listOf("", "not-an-ip")))
    }

    @Test
    fun ipv4PreferredThenGlobalIpv6ThenLinkLocal() {
        val ordered =
            EndpointHosts.candidates(
                listOf(
                    "fe80::1%wlan0",
                    "2001:db8::5",
                    "10.174.189.1",
                ),
            )
        assertEquals(listOf("10.174.189.1", "2001:db8::5", "fe80::1%wlan0"), ordered)
    }

    @Test
    fun duplicateScopedAndUnscopedAddressesCollapse() {
        val ordered = EndpointHosts.candidates(listOf("fe80::1", "fe80::1%wlan0", "FE80::1%3"))
        assertEquals(1, ordered.size)
        assertEquals("fe80::1", ordered.first())
    }

    @Test
    fun ipv4NetworkFilterUses24Prefix() {
        assertTrue(EndpointHosts.isSameNetwork("10.174.189.1", "10.174.189.57"))
        assertFalse(EndpointHosts.isSameNetwork("172.30.8.246", "10.174.189.57"))
        // Нет ограничений, если локальный IPv4 недоступен (старое поведение)
        assertTrue(EndpointHosts.isSameNetwork("10.174.189.1", null))
    }

    @Test
    fun linkLocalIpv6AlwaysAcceptedGlobalIpv6NeedsMatchingPrefix() {
        assertTrue(EndpointHosts.isSameNetwork("fe80::1%wlan0", "10.0.0.2"))
        assertTrue(
            EndpointHosts.isSameNetwork(
                "2001:db8:1:2::5",
                "10.0.0.2",
                listOf("2001:db8:1:2::99"),
            ),
        )
        assertFalse(
            EndpointHosts.isSameNetwork(
                "2001:db8:9:9::5",
                "10.0.0.2",
                listOf("2001:db8:1:2::99"),
            ),
        )
        // Нет информации о локальном IPv6: отклоните глобальный IPv6 (лучше использовать шлюз, а не случайно
        // подключаться к устройствам домашней сети)

        assertFalse(EndpointHosts.isSameNetwork("2001:db8:1:2::5", "10.0.0.2"))
    }

    @Test
    fun ipv6Prefix64ExpandsCompressedForms() {
        assertEquals("2001:0db8:0001:0002", EndpointHosts.ipv6Prefix64("2001:db8:1:2::5"))
        assertEquals("fe80:0000:0000:0000", EndpointHosts.ipv6Prefix64("fe80::1%wlan0"))
        assertEquals("2001:0db8:0000:0000", EndpointHosts.ipv6Prefix64("2001:db8::"))
        assertNull(EndpointHosts.ipv6Prefix64("not-ipv6"))
    }
}

class AirPlayFeaturesTest {
    @Test
    fun parsesDiplayStyleFeatureBitmap() {
        // DiPlay: функции=0x5653aee2,0x61
        val features = AirPlayFeatures.parse("0x5653aee2,0x61")
        assertEquals((0x61L shl 32) or 0x5653aee2L, features)
        // Старшие 32 бита bit38 → глобальный бит38
        assertTrue(features!! and AirPlayFeatures.HK_PAIRING_AND_ENCRYPT != 0L)
        assertEquals(true, AirPlayFeatures.supportsPairing(features))
    }

    @Test
    fun preHomeKitBitmapLacksPairingBit() {
        // Эпоха CarPlay 210.81 (таблица совместимости Catplay README): нет HK_PAIRING_AND_ENCRYPT
        val features = AirPlayFeatures.parse("0x00000080,0x0")
        assertEquals(false, AirPlayFeatures.supportsPairing(features))
    }

    @Test
    fun missingOrMalformedFeaturesStayUnknown() {
        assertNull(AirPlayFeatures.parse(null))
        assertNull(AirPlayFeatures.parse(""))
        assertNull(AirPlayFeatures.parse("not-hex"))
        assertNull(AirPlayFeatures.parse("0x1,0x2,0x3"))
        // Неизвестно должно возвращать ноль и не должно рассматриваться как «соответствие не поддерживается».
        assertNull(AirPlayFeatures.supportsPairing(null))
    }
}
