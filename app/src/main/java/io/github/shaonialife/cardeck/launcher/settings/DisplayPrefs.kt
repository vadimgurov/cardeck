package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context

/**
 * Настройки панели «Дисплей»: масштабирование разрешения и целевая частота кадров видеопотока/виртуального экрана.
 *
 * — Масштабирование разрешения (30–160 %): создание холста потока и виртуального экрана в соответствии с
 * процентом заявленного разрешения автомобиля.
 * Синхронное масштабирование по плотности (логическая компоновка остается неизменной, меняется только количество
 * пикселей). **Вступает в силу только при следующем подключении**——
 * Кодер   и все виртуальные экраны создаются по размеру при установлении сессии, и менять его в процессе работы бессмысленно.
 * — Частота кадров (30/60): Верхний предел ритма кадров синтезатора, который можно переключать в реальном времени во время работы.
 *
 * «Фактическое эффективное значение» записывается SessionController при установлении сеанса (activeScalePercent /
 * activeTargetFps), карты героев показывают значения времени выполнения вместо значений предпочтений.
 */
object DisplayPrefs {
    const val SCALE_MIN = 30
    const val SCALE_MAX = 160
    const val SCALE_STEP = 5
    const val SCALE_DEFAULT = 100

    const val FPS_DEFAULT = 30

    /** Значение регулирования сенсорного перемещения при впрыске по умолчанию (мс): 0 = выключено по умолчанию, увеличьте тест на панели «Дисплей», когда взаимодействие запаздывает. */
    const val MOVE_THROTTLE_DEFAULT = 0
    const val MOVE_THROTTLE_MAX = 48

    /** Значение истинного экрана по умолчанию при подключении (физическая панель выключена). */
    // По умолчанию **ВЫКЛ** (по запросу пользователя 24.10.2026): отключение панели после успешного подключения
    // является слишком радикальным решением для большинства людей.

    // Желающие могут включить его вручную в Настройки → Дисплей (переключатель сохраняется, меняется только значение по умолчанию)
    const val SCREEN_OFF_DEFAULT = false

    /** Значение по умолчанию для журнала исходных сообщений HID: по умолчанию выключено (временно включается при калибровке ключей, чтобы избежать обновления экрана). */
    const val HID_RAW_LOG_DEFAULT = false

    private const val PREFS_NAME = "settings"
    private const val KEY_SCALE = "display_scale_percent"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MOVE_THROTTLE = "move_throttle_ms"
    private const val KEY_SCREEN_OFF = "screen_off_on_connect"
    private const val KEY_HID_RAW_LOG = "hid_raw_log"

    /**
     * Журнал исходных сообщений  HID (для калибровки ключа): после открытия для каждого кадра будет записана одна строка `hidSendReport`.
     * `HID-RAW: uuid=… bytes=…` в основной журнал, используемый для сравнения байтов при нажатии определенной клавиши.
     */
    fun hidRawLog(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HID_RAW_LOG, HID_RAW_LOG_DEFAULT)

    fun setHidRawLog(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HID_RAW_LOG, enabled)
            .apply()
        io.github.shaonialife.cardeck.input.HidNavigation.rawLogEnabled = enabled
    }

    /**
     * истинный экран при подключении (по умолчанию включено): выключите оборудование физической панели после
     * установления сеанса (так же, как scrcpy `-S`),
     * Система остается в активном режиме, а виртуальный экран отображается как обычно; при выключении используется только темный экран.
     * Переключатель       вступает в силу немедленно в рамках сеанса (см. SessionController.applyScreenOffPreference).
     */
    fun screenOffOnConnect(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREEN_OFF, SCREEN_OFF_DEFAULT)

    fun setScreenOffOnConnect(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SCREEN_OFF, enabled)
            .apply()
    }

    /** Процент масштабирования разрешения потока (30–160 с шагом 5; по умолчанию 100). */
    fun scalePercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SCALE, SCALE_DEFAULT)
            .coerceIn(SCALE_MIN, SCALE_MAX)

    fun setScalePercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SCALE, percent.coerceIn(SCALE_MIN, SCALE_MAX))
            .apply()
    }

    /** Целевая частота кадров (30/60; управляет ритмом кадров синтезатора, а переключатель действует в режиме реального времени во время работы). */
    fun fps(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, FPS_DEFAULT)

    fun setFps(
        context: Context,
        fps: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FPS, if (fps >= 60) 60 else 30)
            .apply()
    }

    /** Сенсорное перемещение вводит регулирование (мс; 0 = выкл.). */
    fun moveThrottleMs(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MOVE_THROTTLE, MOVE_THROTTLE_DEFAULT)
            .coerceIn(0, MOVE_THROTTLE_MAX)

    fun setMoveThrottleMs(
        context: Context,
        ms: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MOVE_THROTTLE, ms.coerceIn(0, MOVE_THROTTLE_MAX))
            .apply()
    }
}
