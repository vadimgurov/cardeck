package io.github.shaonialife.cardeck.bt

import android.content.Context

/**
 * Переключатель «Автоматически подключаться при посадке в автомобиль» (по умолчанию включен): следует ли
 * автоматически запускать сеанс при подключении Bluetooth ACL автомобиля.
 *
 * и `DisplayPrefs.screenOffOnConnect` отображаются вместе на панели «Настройки → Дисплей»;
 * читается [CarBluetoothReceiver] при каждой трансляции ACL (возможно, процесс был только что запущен без кэширования).
 */
object AutoConnectPrefs {
    private const val PREFS = "settings"
    private const val KEY = "auto_connect_on_bt"

    /** По умолчанию включено: это переключатель, который «автоматически включается при открытии двери». Он отключается только тогда, когда пользователь активно его выключает. */
    const val DEFAULT = true

    fun enabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, DEFAULT)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }
}
