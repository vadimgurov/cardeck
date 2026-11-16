package io.github.shaonialife.cardeck.launcher

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import io.github.shaonialife.cardeck.PhoneSettingsActivity
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.launcher.settings.DefaultMapPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.DesktopPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.DisplayPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.DockPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.OrientationPanelFragment
import io.github.shaonialife.cardeck.launcher.settings.SettingsMenus
import io.github.shaonialife.cardeck.launcher.settings.SettingsToastHost
import io.github.shaonialife.cardeck.launcher.settings.SupersamplePanelFragment

/**
 * Настройки автомобиля  (хост с двумя столбцами: левое меню/правый контейнер фрагментов содержимого).
 * Страница содержимого   реализована с помощью Fragment (пакета настроек), а двойные столбцы автомобиля и
 * мобильного телефона используют одну и ту же панель.
 */
class SettingsActivity :
    AppCompatActivity(),
    SettingsToastHost {
    private var toastView: TextView? = null
    private val menus = mutableListOf<LinearLayout>()
    private var currentMenu = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VirtualLauncher.dockAtBottom) {
            // Вертикальный узкий экран: сжатие в две колонки — вперед вторичное меню на стороне мобильного телефона (список
            // → страница содержания)

            startActivity(
                android.content.Intent(
                    this,
                    PhoneSettingsActivity::class.java,
                ),
            )
            finish()
            return
        }
        setContentView(R.layout.activity_settings)
        toastView = findViewById(R.id.settingsToast)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { close() }

        collectMenus()
        if (savedInstanceState == null) showMenu(0) else restoreCurrentMenu()
    }

    /** Собирается из записей меню XML (в порядке идентификаторов, т. е. в порядке меню). */
    private fun collectMenus() {
        val ids =
            intArrayOf(
                R.id.menuDesktopApps,
                R.id.menuSupersample,
                R.id.menuOrientation,
                R.id.menuDock,
                R.id.menuDefaultMap,
                R.id.menuDisplay,
            )
        ids.forEach { id -> menus.add(findViewById<LinearLayout>(id)) }
        menus.forEachIndexed { index, menu ->
            menu.setOnClickListener { showMenu(index) }
        }
    }

    /** Переключение меню: выбранный стиль + замена фрагмента правого столбца. */
    private fun showMenu(index: Int) {
        if (index == currentMenu) {
            applyMenuStyle(index)
            return
        }
        currentMenu = index
        applyMenuStyle(index)
        val fragment = fragmentFor(index)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settingsContainer, fragment)
            .commit()
    }

    private fun restoreCurrentMenu() {
        currentMenu = -1
        val restored = supportFragmentManager.findFragmentById(R.id.settingsContainer)
        val index =
            when (restored) {
                is DesktopPanelFragment -> SettingsMenus.DESKTOP
                is SupersamplePanelFragment -> SettingsMenus.SUPERSAMPLE
                is OrientationPanelFragment -> SettingsMenus.ORIENTATION
                is DockPanelFragment -> SettingsMenus.DOCK
                is DefaultMapPanelFragment -> SettingsMenus.DEFAULT_MAP
                is DisplayPanelFragment -> SettingsMenus.DISPLAY
                else -> 0
            }
        showMenu(index)
    }

    private fun applyMenuStyle(index: Int) {
        val ripple =
            runCatching {
                val out = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                getDrawable(out.resourceId)
            }.getOrNull()
        menus.forEachIndexed { i, menu ->
            menu.background = if (i == index) getDrawable(R.drawable.bg_menu_item) else ripple
            for (c in 0 until menu.childCount) {
                val child = menu.getChildAt(c)
                if (child is TextView) {
                    child.setTextColor(if (i == index) 0xFFF5F5F7.toInt() else 0xFF8E8E93.toInt())
                    child.paint.isFakeBoldText = i == index
                }
            }
        }
    }

    private fun fragmentFor(index: Int): Fragment = SettingsMenus.createFragment(index)

    private fun close() {
        finish()
    }

    override fun onBackPressed() = close()

    /** Световая подсказка на странице (системный тост не требуется: положение отображения на виртуальном экране неконтролируемо). */
    override fun showToast(text: String) {
        val view = toastView ?: return
        view.text = text
        view.animate().cancel()
        view.alpha = 0f
        view
            .animate()
            .alpha(1f)
            .setDuration(160)
            .withEndAction {
                view.postDelayed({
                    view
                        .animate()
                        .alpha(0f)
                        .setDuration(220)
                        .start()
                }, 1_250)
            }.start()
    }
}
