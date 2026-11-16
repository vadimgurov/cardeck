package io.github.shaonialife.cardeck.launcher.settings

import androidx.fragment.app.Fragment
import io.github.shaonialife.cardeck.R

/** 车机设置菜单注册表：车机双栏与手机二级菜单共用（菜单项/标题/Fragment 工厂）。 */
object SettingsMenus {
    const val DESKTOP = 0
    const val SUPERSAMPLE = 1
    const val ORIENTATION = 2
    const val DOCK = 3
    const val DEFAULT_MAP = 4
    const val DISPLAY = 5

    data class Item(
        val id: Int,
        val title: String,
        val desc: String,
        val iconRes: Int,
    )

    val ALL =
        listOf(
            Item(DESKTOP, "Приложения", "Приложения на главном экране автомобиля", R.drawable.ic_menu_grid),
            Item(SUPERSAMPLE, "Высокое разрешение", "Улучшенный рендеринг портретных приложений", R.drawable.ic_menu_zoom),
            Item(ORIENTATION, "Альбомная ориентация", "Портретные приложения на весь экран", R.drawable.ic_screen_rotation),
            Item(DOCK, "Панель Dock", "Обои, затемнение и размытие", R.drawable.ic_menu_dock),
            Item(DEFAULT_MAP, "Карты", "Картографическое приложение для панели приборов", R.drawable.ic_menu_map),
            Item(DISPLAY, "Экран", "Разрешение и частота кадров", R.drawable.ic_menu_display),
        )

    fun titleOf(id: Int): String = ALL.first { it.id == id }.title

    fun createFragment(id: Int): Fragment =
        when (id) {
            DESKTOP -> DesktopPanelFragment()
            SUPERSAMPLE -> SupersamplePanelFragment()
            ORIENTATION -> OrientationPanelFragment()
            DOCK -> DockPanelFragment()
            DISPLAY -> DisplayPanelFragment()
            else -> DefaultMapPanelFragment()
        }
}
