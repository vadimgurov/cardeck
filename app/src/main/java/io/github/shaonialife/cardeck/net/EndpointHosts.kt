package io.github.shaonialife.cardeck.net

/**
 * Сортировка и определение кандидатов на адрес получателя (чистая функция, однотестовое покрытие).
 *
 * Предыстория: Android-клиент может легко ошибочно принять «имеет адрес» за «имеет адрес IPv4» — Audi MIB3
 * `_airplay._tcp` При публикации только локального IPv6 (`fe80::…%wlan0`) старый код будет
 * Отбросьте  , а затем вернитесь к `шлюз:7000`; и шлюз/порт машины с прошивкой 4ххх может не быть
 * `10.174.189.1:7000`, поэтому его невозможно подключить (это номер 4851, о котором сообщили пользователи 19 октября 2026 г.).
 *
 * Стратегия сортировки   (настоящий iPhone/catplay также напрямую использует адрес, указанный Bonjour):
 * 1. IPv4 - самый стабильный на Android;
 * 2. Глобальный IPv6;
 * 3. Свяжите локальный IPv6 (необходимо использовать с областью действия, оставьте все как есть и позвольте
 * вызывающей стороне заполнить `%iface`).
 * Формы одного и того же адреса с областью действия или без нее считаются дубликатами, и сохраняется только та, которая появляется первой.
 */
object EndpointHosts {
    fun isIpv4(host: String): Boolean = host.contains('.') && !host.contains(':')

    fun isIpv6(host: String): Boolean = host.contains(':')

    fun isLinkLocalIpv6(host: String): Boolean {
        val normalized = host.substringBefore('%').lowercase()
        return normalized.startsWith("fe80:") ||
            normalized == "fe80" ||
            normalized.startsWith("fe8") ||
            normalized.startsWith("fe9") ||
            normalized.startsWith("fea") ||
            normalized.startsWith("feb")
    }

    /** Удалите суффиксы области видимости, такие как `%wlan0`/`%3`. */
    fun stripScope(host: String): String = host.substringBefore('%')

    /**
     * Указывает, предоставила ли принимающая сторона адрес, который можно попробовать (учитываются как IPv4, так и IPv6).
     * используется для определения того, является ли конечная точка самодостаточной и требуется ли завершение mDNS.
     * Не пишите `contains('.')` снова.
     */
    fun hasUsable(addresses: List<String>): Boolean = candidates(addresses).isNotEmpty()

    /** Сортировка + удаление дубликатов. Пустые строки/чистые области всегда удаляются. */
    fun candidates(addresses: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(addresses.size)

        fun add(host: String) {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) return
            val base = stripScope(trimmed)
            if (base.isEmpty()) return
            if (!isIpv4(base) && !isIpv6(base)) return
            val key = base.lowercase()
            if (seen.add(key)) out += trimmed
        }
        addresses.filter(::isIpv4).forEach(::add)
        addresses.filter { isIpv6(it) && !isLinkLocalIpv6(it) }.forEach(::add)
        addresses.filter(::isLinkLocalIpv6).forEach(::add)
        return out
    }

    /**
     * Принадлежит ли адрес  к «сегменту автомобильной сети, где находится машина».
     *
     * — IPv4: соответствует префиксу /24 локального IPv4 (наследовать старую фильтрацию для предотвращения
     * случайного подключения к Mac по Wi-Fi);
     * — IPv6-ссылка локальная: естественно только по этой ссылке, принимается напрямую;
     * — глобальный IPv6: принимается только в том случае, если он соответствует первым 64 битам локального адреса
     * IPv6 (отклоняется, если локальный IPv6 не может быть получен,
     * скорее вернется к шлюзу, чем будет использовать устройство AirPlay в общедоступной/домашней сети в качестве
     * автомобильного компьютера).
     */
    fun isSameNetwork(
        host: String,
        localIpv4: String?,
        localIpv6: List<String> = emptyList(),
    ): Boolean {
        if (isIpv4(host)) {
            val prefix = localIpv4?.substringBeforeLast('.')?.plus(".")
            return prefix == null || host.startsWith(prefix)
        }
        if (!isIpv6(host)) return false
        if (isLinkLocalIpv6(host)) return true
        val candidate = ipv6Prefix64(host) ?: return false
        return localIpv6.any { ipv6Prefix64(it) == candidate }
    }

    /** Первые 64 бита IPv6 (первые 4 группы гекстетов, сравниваемые после расширения; в случае сбоя анализа возвращается ноль). */
    fun ipv6Prefix64(host: String): String? {
        val groups = expandIpv6(stripScope(host)) ?: return null
        return groups.take(4).joinToString(":")
    }

    /** Разверните текст IPv6 на группы по 8 (с обработкой сжатия `::`), вернув список шестнадцатеричных символов нижнего регистра. */
    private fun expandIpv6(host: String): List<String>? {
        if (!host.contains(':')) return null
        val doubleColon = host.indexOf("::")
        val head: List<String>
        val tail: List<String>
        if (doubleColon >= 0) {
            head = host.substring(0, doubleColon).split(':').filter { it.isNotEmpty() }
            tail = host.substring(doubleColon + 2).split(':').filter { it.isNotEmpty() }
        } else {
            head = host.split(':')
            tail = emptyList()
        }
        if (head.size + tail.size > 8) return null
        val fill = 8 - head.size - tail.size
        val groups = ArrayList<String>(8)
        groups += head
        repeat(fill) { groups += "0" }
        groups += tail
        if (groups.size != 8) return null
        if (groups.any { it.length > 4 || it.any { c -> c.digitToIntOrNull(16) == null } }) return null
        return groups.map { it.lowercase().padStart(4, '0') }
    }
}
