package io.github.shaonialife.cardeck.net

/**
 * анализирует выходные данные `cmd wifi list-networks` (чистая функция, однотестовое покрытие).
 *
 * Реальный формат   (выровнено по пробелу, **без табуляции**, SSID может содержать пробелы; одна и та же сеть
 * может иметь несколько строк в зависимости от типа безопасности):
 * ```
 * Идентификатор сети SSID Тип безопасности
 * 0 2-502 wpa2-psk
 * 1 iPhone Ли Чжихуа wpa3-sae
 * ```
 * Правила синтаксического анализа  : первый сегмент = netId, последний сегмент = тип безопасности, все в
 * середине записывается обратно в SSID.
 *
 * Известные границы: если SSID содержит несколько последовательных пробелов, они будут сжаты в один — это редко
 * случается с именами автомобильных точек доступа;
 * скорее пропустит решение (без запроса пользователя), чем по ошибке удалит другие сети.
 */
object WifiSavedNetworks {
    /**
     * возвращает список сетевых идентификаторов, которые точно соответствуют SSID (дублирование удаляется; при
     * отсутствии совпадений возвращается пустое значение).
     * Вызывающий может выполнить `forget-network`, только если список не пуст.
     */
    fun idsOf(
        listNetworksOutput: String,
        ssid: String,
    ): List<Int> =
        listNetworksOutput
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                // Минимум netId + SSID + безопасность три раздела
                if (parts.size < 3) return@mapNotNull null
                val id = parts.first().toIntOrNull() ?: return@mapNotNull null
                val name = parts.subList(1, parts.size - 1).joinToString(" ")
                if (name == ssid) id else null
            }.distinct()
            .toList()
}
