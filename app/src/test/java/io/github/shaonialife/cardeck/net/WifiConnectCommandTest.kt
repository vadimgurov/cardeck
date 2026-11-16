package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `cmd wifi connect-network` Защита возврата для сборки параметров.
 *
 * Имя/пароль автомобильной точки доступа   происходит от 0x5703 (выдается автомобилем и машиной) и может
 * содержать пробелы, китайские иероглифы или даже одинарные кавычки;
 * Единственная ошибка в написании  : «Не удалось выполнить тихий доступ к сети, и спецификатор вернулся со
 * всплывающим окном». По журналу трудно определить причину.
 * Здесь закреплена граница и сопоставление типов безопасности заблокировано (0=НЕТ 1=WEP 2=WPA2 3=Переход 4=WPA3).
 */
class WifiConnectCommandTest {
    @Test
    fun quotesSsidAndPassphraseWithSpaces() {
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW 12345' wpa2 'p@ss word'",
            WifiConnectCommand.build("DIRECT-BMW 12345", "p@ss word", 2),
        )
    }

    @Test
    fun escapesSingleQuotes() {
        // Написание POSIX: 'pa'\''ss' - закрытие кавычек, выход из одинарных кавычек, повторное открытие кавычек
        assertEquals(
            "cmd wifi connect-network 'a b' wpa2 'pa'\\''ss'",
            WifiConnectCommand.build("a b", "pa'ss", 2),
        )
    }

    @Test
    fun openNetworkHasNoPassphrase() {
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' open",
            WifiConnectCommand.build("DIRECT-BMW", "", 0),
        )
    }

    @Test
    fun emptyPassphraseFallsBackToOpen() {
        // Если параметры противоречивы (указан WPA2, но не указан пароль), половина команды не генерируется. Попробуйте
        // открыть точку доступа.

        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' open",
            WifiConnectCommand.build("DIRECT-BMW", "", 2),
        )
    }

    @Test
    fun wpa3OnlyUsesWpa3Keyword() {
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa3 'secret123'",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 4),
        )
    }

    @Test
    fun wpa3TransitionAndWepFallBackToWpa2() {
        // Точка доступа в переходном режиме одновременно принимает WPA2; В WEP нет ключевого слова, попробуйте также wpa2
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa2 'secret123'",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 3),
        )
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa2 'secret123'",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 1),
        )
    }

    @Test
    fun keepsNonAsciiSsidIntact() {
        assertEquals(
            "cmd wifi connect-network 'машина машина Точка доступа' wpa2 'Пароль 1234'",
            WifiConnectCommand.build("машина машина Точка доступа", "Пароль 1234", 2),
        )
    }
}
