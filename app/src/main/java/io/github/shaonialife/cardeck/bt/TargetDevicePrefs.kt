package io.github.shaonialife.cardeck.bt

import android.content.Context

/**
 * Предпочтение выбора «Целевой автомобильной машины» (16 октября 2026 г.):
 *
 * Пользователь   может подключить несколько устройств CarPlay (реальный автомобиль + испытательный стенд). Если
 * цель не указана, обе ссылки нажимают
 * Обработка "Любое сопряженное устройство" - исходящие клиенты пробуют один за другим, входящие серверы
 * принимают всех желающих, к какому устройству подключиться
 * Зависит от того, кто ответит первым (не уверен). После выбора цели только инициируйте подключения к этому
 * устройству и принимайте только это устройство.
 * Соединение   во избежание неправильного подключения к нецелевым автомобилям (обычно: один из двух автомобилей дома).
 *
 * Адрес хранилища   + снимок имени: адрес является основой для оценки (сравнение без учета регистра), а имя
 * используется только для отображения пользовательского интерфейса.
 * (Устройство можно переименовать, отображение основано на снимке, просто выберите его повторно, когда оно не используется).
 */
object TargetDevicePrefs {
    private const val PREFS = "target_device"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"

    data class Target(
        val address: String,
        val name: String,
    )

    /** null = не указано (автоматически: пробовать все исходящие кандидаты, принимать любое входящие устройства). */
    fun get(context: Context): Target? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return Target(address = address, name = prefs.getString(KEY_NAME, null) ?: address)
    }

    fun set(
        context: Context,
        target: Target?,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (target == null) {
            prefs.edit().clear().apply()
        } else {
            prefs
                .edit()
                .putString(KEY_ADDRESS, target.address)
                .putString(KEY_NAME, target.name)
                .apply()
        }
    }

    fun matches(
        target: Target,
        address: String?,
    ): Boolean = address != null && address.equals(target.address, ignoreCase = true)
}
