package io.github.shaonialife.cardeck.net

/**
 * `_airplay._tcp` TXT `features` растровый анализ (формируется как `0x44540380,0x61`).
 *
 * Сначала немного: `низкий32Бит,высокий32Бит`, объединенный в 64 бита. Приемник AirPlay использует бит38
 * `HK_PAIRING_AND_ENCRYPT` сказал: «поддерживает сопряжение/шифрование HomeKit» — старый автомобильный телефон
 * (эра CarPlay 210.81) не объявляет этот бит и принимает только сеансы открытого текста `et=NONE`.
 *
 * всегда будет возвращать значение null (неизвестно), если синтаксический анализ не удался, и вызывающая сторона
 * будет обрабатывать это как «Сохранить старое поведение».
 * не должен рассматривать ошибку синтаксического анализа как «сопряжение не поддерживается» — это приведет к
 * тому, что обычные автомобили будут понижены до уровня открытого текста.
 */
object AirPlayFeatures {
    /** Поддержка /pair-setup + /pair-verify (AirPlayFeature::HK_PAIRING_AND_ENCRYPT). */
    const val HK_PAIRING_AND_ENCRYPT: Long = 1L shl 38

    /** Поддерживает дублирование экрана. */
    const val SCREEN: Long = 1L shl 7

    /** Управление автомобилем (CarPlay). */
    const val CARPLAY_CONTROL: Long = 1L shl 37

    fun parse(txt: String?): Long? {
        val text = txt?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 2) return null
        val low = parseHex(parts[0]) ?: return null
        val high = if (parts.size == 2) parseHex(parts[1]) ?: return null else 0L
        if (low < 0 || high < 0) return null
        return (high shl 32) or low
    }

    /** Заявляет ли принимающая сторона о поддержке сопряжения/шифрования HomeKit; null = TXT отсутствует или не анализируется. */
    fun supportsPairing(features: Long?): Boolean? = features?.let { (it and HK_PAIRING_AND_ENCRYPT) != 0L }

    private fun parseHex(value: String): Long? {
        val cleaned = value.removePrefix("0x").removePrefix("0X")
        if (cleaned.isEmpty()) return null
        return cleaned.toLongOrNull(16)
    }
}
