package io.github.shaonialife.cardeck.launcher

import android.content.Context
import android.content.pm.ActivityInfo

/**
 * Настройки суперсэмплинга для каждого приложения (три состояния, сохранение SharedPreferences).
 *
 * Автоматически (по умолчанию): включить суперсэмплинг при обнаружении активности запуска «вертикальной блокировки экрана» ——
 * Такие приложения, как  , помещаются системой в узкое окно на горизонтальном экране приложения и нажимаются в строке состояния телефона.
 * резервирует верхнюю пустую полосу для физических пикселей; передискретизация (рендеринг ×1,5 с последующим
 * сжатием) может уменьшить фиксированное соотношение пикселей.
 * Приложения с горизонтальным экраном (тип карты) не требуют суперсэмплинга — исходное разрешение более четкое и плавное.
 * вкл/выкл: явно указывается пользователем, игнорируя автоматическое обнаружение.
 *
 * Обнаружение вертикального экрана считывает ориентацию экрана при запуске (ПОРТРЕТ/ОБРАТНЫЙ/ДАТЧИК/ПОЛЬЗОВАТЕЛЬ).
 */
object SupersamplePrefs {
    const val MODE_AUTO = 0
    const val MODE_ON = 1
    const val MODE_OFF = 2

    private const val PREFS_NAME = "settings"
    private const val KEY_PREFIX = "ss_"

    /** Предпочтения чтения (по умолчанию автоматически). */
    fun mode(
        context: Context,
        component: android.content.ComponentName,
    ): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PREFIX + component.flattenToString(), MODE_AUTO)

    fun setMode(
        context: Context,
        component: android.content.ComponentName,
        mode: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PREFIX + component.flattenToString(), mode)
            .apply()
    }

    /** Переходить ли к экрану приложения передискретизации при этом запуске (окончательный результат после анализа трех состояний). */
    fun shouldSupersample(
        context: Context,
        component: android.content.ComponentName,
    ): Boolean =
        when (mode(context, component)) {
            MODE_ON -> true
            MODE_OFF -> false
            else -> isPortraitActivity(context, component)
        }

    /** Автоматическое определение: заблокировано ли действие при запуске в книжной ориентации (оно будет в форме почтового ящика на экране приложения в альбомной ориентации). */
    fun isPortraitActivity(
        context: Context,
        component: android.content.ComponentName,
    ): Boolean =
        try {
            val info = context.packageManager.getActivityInfo(component, 0)
            when (info.screenOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                -> true

                else -> false
            }
        } catch (_: Exception) {
            false
        }
}
