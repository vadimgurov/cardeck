package io.github.shaonialife.cardeck.launcher

import android.content.Context

/**
 * Настройки внешнего вида панели закрепления  (сохранение SharedPreferences + уведомление прослушивателя).
 *
 * - scrimOpacity: интенсивность темного экрана закрепления 0..100 (% черного; 0 = обои хорошо видны, 100 = полностью черный)
 * - размытие: интенсивность размытия фона по Гауссу 0..100 (0 = нет размытия; сопоставлено с радиусом размытия RenderEffect)
 *
 * Прослушиватель   вызывается обратно в основном потоке (срабатывает при перетаскивании ползунка на странице
 * настроек), и DockActivity соответствующим образом повторно отображает.
 */
object DockPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_SCRIM = "dockScrimOpacity"
    private const val KEY_BLUR = "dockBlur"
    private const val KEY_WIDTH = "dockWidthPercent"

    /** Значение интенсивности темного экрана по умолчанию (%). */
    const val DEFAULT_SCRIM = 20

    /**
     * Масштабирование ширины столбца (50–100 с шагом 5; по умолчанию 100). Толщина в пикселях умножается на
     * плотность экрана док-станции**——
     * Макет содержимого       остается неизменным, отображается меньше пикселей, а вся док-станция
     * (значок/часы/текст) пропорционально сужается.
     * **Вступает в силу только при следующем подключении** (Виртуальный экран док-станции создается в соответствии с
     * размером/плотностью при установлении сеанса, а также сглаживанием/размытием
     * Эффект       в реальном времени другой).
     */
    const val WIDTH_MIN = 50
    const val WIDTH_MAX = 100
    const val WIDTH_STEP = 5
    const val WIDTH_DEFAULT = 100

    fun widthPercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_WIDTH, WIDTH_DEFAULT)
            .coerceIn(WIDTH_MIN, WIDTH_MAX)

    fun setWidthPercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_WIDTH, percent.coerceIn(WIDTH_MIN, WIDTH_MAX))
            .apply()
    }

    /** Значение силы размытия по умолчанию (%). */
    const val DEFAULT_BLUR = 40

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun scrimOpacity(context: Context): Int = prefs(context).getInt(KEY_SCRIM, DEFAULT_SCRIM).coerceIn(0, 100)

    fun blur(context: Context): Int = prefs(context).getInt(KEY_BLUR, DEFAULT_BLUR).coerceIn(0, 100)

    fun setScrimOpacity(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit().putInt(KEY_SCRIM, value.coerceIn(0, 100)).apply()
        notifyListeners()
    }

    fun setBlur(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit().putInt(KEY_BLUR, value.coerceIn(0, 100)).apply()
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }
}
