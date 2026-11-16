package io.github.shaonialife.cardeck.launcher.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.util.Bg

/** Установите хост панели: световые подсказки на странице (автомобиль = просмотр всплывающего уведомления на странице; мобильный телефон = системное всплывающее сообщение). */
interface SettingsToastHost {
    fun showToast(text: String)
}

/**
 * Установить базовый класс панели содержимого: общий манифест приложения (прямое чтение кэша + асинхронное
 * сканирование холодного пути) и световое приглашение хоста.
 * Манифест приложения   используется на уровне процесса (кэш VirtualLauncher), и каждая панель открывается через
 * несколько секунд после сканирования рабочего стола.
 */
abstract class SettingsPanelFragment : Fragment() {
    var apps: List<VirtualLauncher.AppEntry> = emptyList()
        private set

    /** Обратный вызов манифеста (основной поток; синхронный обратный вызов при попадании в кэш). */
    protected fun loadApps(onReady: () -> Unit) {
        val appContext = requireContext().applicationContext
        VirtualLauncher.peekCachedApps()?.takeIf { it.isNotEmpty() }?.let {
            apps = it
            onReady()
            return
        }
        Bg.run("apps-scan") {
            val scanned = VirtualLauncher.launchableApps(appContext)
            VirtualLauncher.adoptAppsCache(scanned)
            requireActivity().runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                apps = scanned
                onReady()
            }
        }
    }

    /** Общая сборка списка манифеста приложения: LayoutManager + буквенная панель навигации + асинхронная загрузка (включая заполнитель загрузки). */
    protected fun setupAppList(
        container: View,
        listId: Int,
        indexBarId: Int,
        adapter: AppRowsAdapter,
        onAppsReady: () -> Unit = {},
    ) {
        val list = container.findViewById<RecyclerView>(listId)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        SettingsRows.setupIndexBar(this, container.findViewById<LinearLayout>(indexBarId), list) { adapter.rows }
        val loading = container.findViewById<TextView>(R.id.listLoading)
        loadApps {
            loading.visibility = View.GONE
            onAppsReady()
            adapter.notifyDataSetChanged()
        }
    }

    protected fun hostToast(text: String) {
        val host = activity
        if (host is SettingsToastHost) {
            host.showToast(text)
        } else {
            android.widget.Toast
                .makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        onViewCreated(view)
    }

    /** onViewCreated упрощенная версия (эта страница не имеет требований к восстановлению состояния). */
    protected abstract fun onViewCreated(view: View)
}
