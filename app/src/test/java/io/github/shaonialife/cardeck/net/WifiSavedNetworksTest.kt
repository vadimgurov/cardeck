package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `cmd wifi list-networks` Анализ: используется только для того, чтобы «спросить пользователя, следует ли очистить»,
 * Ошибка   приведет к ошибочному удалению пользовательской сети, поэтому граница должна быть заблокирована.
 */
class WifiSavedNetworksTest {
    /** Результаты измерений на реальной машине (Honor Android 17): выравнивание по пробелу, без вкладок. */
    private val realOutput =
        """
        Network Id      SSID                         Security type
        0            2-502                            wpa2-psk
        0            2-502                            wpa3-sae^
        1            Ли ЧжихуаiPhone                    wpa3-sae
        2            Weimob_office                    open
        2            Weimob_office                    owe 
        3            Audi_MMI_9310_5GHz               wpa2-psk
        """.trimIndent()

    @Test
    fun findsExactMatchIgnoringOtherNetworks() {
        assertEquals(listOf(3), WifiSavedNetworks.idsOf(realOutput, "Audi_MMI_9310_5GHz"))
    }

    @Test
    fun dedupesMultipleSecurityRowsForSameNetwork() {
        // Один и тот же netId может иметь две строки wpa2/wpa3.
        assertEquals(listOf(0), WifiSavedNetworks.idsOf(realOutput, "2-502"))
    }

    @Test
    fun handlesSsidWithSpacesAndNonAscii() {
        // Пространства в середине SSID реорганизуются в одно пространство.
        assertEquals(listOf(2), WifiSavedNetworks.idsOf(realOutput, "Weimob_office"))
        assertEquals(listOf(1), WifiSavedNetworks.idsOf(realOutput, "Ли ЧжихуаiPhone"))
    }

    @Test
    fun noMatchReturnsEmpty() {
        // Несуществующие SSID не должны совпадать приблизительно (иначе чужая сеть будет по ошибке удалена)
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, "Audi_MMI_9310"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, "Redmi K40"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, ""))
    }

    @Test
    fun headerOnlyOrEmptyOutputIsSafe() {
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf("Network Id SSID Security type", "X"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf("", "X"))
    }

    @Test
    fun skipsMalformedRows() {
        val messy =
            """
            Network Id      SSID                         Security type
            notanumber   Foo                          wpa2-psk
            7            OnlyTwoColumns
            8            Bar                          wpa2-psk
            """.trimIndent()
        assertEquals(listOf(8), WifiSavedNetworks.idsOf(messy, "Bar"))
    }
}
