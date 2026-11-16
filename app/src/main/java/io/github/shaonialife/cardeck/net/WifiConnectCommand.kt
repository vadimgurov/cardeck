package io.github.shaonialife.cardeck.net

/**
 * создает команду `cmd wifi connect-network` для Shizuku для тихого доступа к сети (чистая функция, однотестовое покрытие).
 *
 * Синтаксис   (AOSP `WifiShellCommand#connect-network`):
 * ```
 * cmd wifi Connect-Network <ssid> open|owe|wpa2|wpa3 [<парольная фраза>] [-m] [-d] [-b <bssid>]
 * ```
 * Ключевые моменты:
 * — SSID/пароль всегда будет [quote] (одинарная кавычка + встроенный escape-код одинарной кавычки), имена с
 * пробелами/китайским языком/кавычками не будут
 * Когда оболочка   разобрана, внедрение команд отсутствует;
 * — тип безопасности соответствует `Iap2WirelessSecurity` 0x5703 (0=НЕТ 1=WEP 2=WPA/WPA2
 * 3=переход WPA3 4=WPA3). У WEP нет соответствующего ключевого слова, нажмите `wpa2`, чтобы попробовать один раз
 * (в соответствии со старым карманным путем);
 * Режим перехода WPA3 также принимает WPA2, поэтому используйте `wpa2` в качестве пути спецификатора;
 * - **exit=0 команды не означает, что она подключена**: она только передает конфигурацию WifiService и
 * возвращает результат, вызывающая сторона должна
 * опрашивает `cmd wifi status` и другие SSID (см. `SessionController.waitForCarSsid`).
 */
object WifiConnectCommand {
    /** Литералы оболочки POSIX с одинарными кавычками (`$`/space/Chinese/двойные кавычки остаются нетронутыми). */
    fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /**
     * полная командная строка. Когда `passphrase` пуст (или [securityType] имеет значение 0=NONE), он
     * рассматривается как открытая точка доступа.
     */
    fun build(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): String {
        val open = passphrase.isEmpty() || securityType == 0
        val security =
            when {
                open -> "open"
                securityType == 4 -> "wpa3"
                else -> "wpa2"
            }
        val credentials = if (open) "" else " " + quote(passphrase)
        return "cmd wifi connect-network " + quote(ssid) + " " + security + credentials
    }
}
