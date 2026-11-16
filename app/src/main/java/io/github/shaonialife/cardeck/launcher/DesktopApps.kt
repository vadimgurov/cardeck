package io.github.shaonialife.cardeck.launcher

import android.content.Context

/**
 * Выбор настольного приложения (сохранение SharedPreferences).
 *
 * По умолчанию (никогда не устанавливается пользователем): отображает только установленные картографические приложения (Amap/Baidu/Tencent;
 * Сравните требования пользователя («по умолчанию отображать карты, такие как Amap и Baidu»); как только
 * пользователь установит флажок на странице настроек,
 * подлежит явному выбору (включая статус «Отменить все»).
 */
object DesktopApps {
    private const val PREFS_NAME = "settings"
    private const val KEY_SELECTED = "desktopApps"

    /** Имя пакета картографического приложения, которое отмечено по умолчанию. */
    private val MAP_PACKAGES =
        setOf(
            "com.autonavi.amapauto", // Автомобильная версия Amap
            "com.baidu.naviauto", // Версия автомобиля карты Baidu
            "com.autonavi.minimap", // Карта Гаоде
            "com.baidu.BaiduMap", // Карта Байду
            "com.tencent.map", // Карта Тенсента
        )

    /** Приложение, которое в данный момент должно отображаться на рабочем столе (коллекция компонента FlattenToString). */
    fun selected(
        context: Context,
        apps: List<VirtualLauncher.AppEntry>,
    ): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SELECTED)) {
            return apps
                .filter { it.component.packageName in MAP_PACKAGES }
                .map { it.component.flattenToString() }
                .toSet()
        }
        return prefs.getStringSet(KEY_SELECTED, emptySet()).orEmpty().toSet()
    }

    fun save(
        context: Context,
        selection: Set<String>,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_SELECTED, selection.toMutableSet())
            .apply()
    }
}
