package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.launcher.DesktopApps
import io.github.shaonialife.cardeck.launcher.VirtualLauncher

/** Установите строку списка: заголовок группы/заголовок пиньинь/приложение (общее для трех списков приложений). */
sealed class SettingsRow {
    abstract val id: Long

    data class Header(
        val title: String,
        val key: String,
    ) : SettingsRow() {
        override val id: Long get() = key.hashCode().toLong()
    }

    data class App(
        val entry: VirtualLauncher.AppEntry,
    ) : SettingsRow() {
        override val id: Long get() = entry.component.hashCode().toLong()
    }
}

/**
 * построение строк и панель навигации по буквам (совместно с рабочим столом управления/суперсэмплированием
 * приложения/горизонтальным экраном приложения):
 * Двухуровневая группировка - сначала проверка по рабочему столу («Приложения на рабочем столе» → «Приложения не на рабочем столе»).
 * Только группа «Не на рабочем столе» разделена на разделы по первой букве пиньинь (A/B/…/#, номер приложения
 * принадлежит #), и алфавитной панели навигации.
 * соответствует только последнему - **не разделен на разделы в группе рабочего стола**: число обычно небольшое,
 * если разделить на разделы, его буквы появятся в
 * находится вверху панели навигации (ранг до #/A), и эта буква больше не отображается в последующих разделах, а
 * панель навигации выглядит так:
 * «Ошибка сортировки» (отзыв пользователя 16 октября 2026 г.: Amap на рабочем столе → G занимает первое место на панели навигации).
 */
object SettingsRows {
    fun build(
        context: Context,
        apps: List<VirtualLauncher.AppEntry>,
    ): List<SettingsRow> {
        val selected = DesktopApps.selected(context, apps)
        val onDesktop = apps.filter { it.component.flattenToString() in selected }
        val offDesktop = apps.filter { it.component.flattenToString() !in selected }
        val rows = ArrayList<SettingsRow>(apps.size * 2)
        if (onDesktop.isNotEmpty()) {
            rows += SettingsRow.Header("Приложение на рабочем столе (${onDesktop.size})", "on")
            // Плоский и прямой (сам список упорядочен по пиньинь, и после фильтрации он остается в порядке), без деления на
            // алфавитные разделы и входа в панель навигации.

            rows += onDesktop.map { SettingsRow.App(it) }
        }
        if (offDesktop.isNotEmpty()) {
            rows += SettingsRow.Header("Приложения, которых нет на рабочем столе (${offDesktop.size})", "off")
            rows += letterRows(offDesktop)
        }
        return rows
    }

    private fun letterRows(group: List<VirtualLauncher.AppEntry>): List<SettingsRow> {
        val byLetter = LinkedHashMap<String, MutableList<VirtualLauncher.AppEntry>>()
        group.forEach { entry ->
            byLetter
                .getOrPut(VirtualLauncher.indexLetterOf(entry.label)) { mutableListOf() }
                .add(entry)
        }
        val rows = ArrayList<SettingsRow>(group.size + byLetter.size)
        byLetter.forEach { (letter, list) ->
            rows += SettingsRow.Header(letter, "letter_$letter")
            rows += list.map { SettingsRow.App(it) }
        }
        return rows
    }

    /** Панель навигации с буквами справа: управляется AdapterDataObserver для перестроения при изменении набора строк. */
    fun setupIndexBar(
        fragment: androidx.fragment.app.Fragment,
        bar: LinearLayout,
        list: RecyclerView,
        rowsProvider: () -> List<SettingsRow>,
    ) {
        val density = fragment.resources.displayMetrics.density

        fun rebuild() {
            bar.removeAllViews()
            val letters = LinkedHashSet<String>()
            rowsProvider().forEach { row ->
                if (row is SettingsRow.Header && row.key.startsWith("letter_")) {
                    letters += row.key.removePrefix("letter_")
                }
            }
            letters.forEach { letter ->
                bar.addView(
                    TextView(fragment.requireContext()).apply {
                        text = letter
                        textSize = 9f
                        setTextColor(0xFF8E8E93.toInt())
                        setPadding(0, (1.5f * density).toInt(), 0, (1.5f * density).toInt())
                        setOnClickListener {
                            val target =
                                rowsProvider()
                                    .indexOfFirst { it is SettingsRow.Header && it.key == "letter_$letter" }
                            if (target >= 0) {
                                (list.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(target, 0)
                            }
                        }
                    },
                )
            }
        }
        rebuild()
        list.adapter?.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = rebuild()

                override fun onItemRangeChanged(
                    positionStart: Int,
                    itemCount: Int,
                ) = rebuild()

                override fun onItemRangeInserted(
                    positionStart: Int,
                    itemCount: Int,
                ) = rebuild()

                override fun onItemRangeRemoved(
                    positionStart: Int,
                    itemCount: Int,
                ) = rebuild()

                override fun onItemRangeMoved(
                    fromPosition: Int,
                    toPosition: Int,
                    itemCount: Int,
                ) = rebuild()
            },
        )
    }

    /** Строка заголовка группы повторно использует ViewHolder. */
    class HeaderHolder(
        val title: TextView,
    ) : RecyclerView.ViewHolder(title) {
        fun bind(text: String) {
            title.text = text
        }
    }

    fun inflateHeader(parent: ViewGroup): TextView =
        android.view.LayoutInflater
            .from(parent.context)
            .inflate(R.layout.item_section_header, parent, false) as TextView
}

/**
 * Универсальный адаптер-скелет списка приложений (общий для рабочего стола/суперсэмплинга/горизонтальных трех
 * панелей): двухуровневый группирующий заголовок состоит из скелета
 * Унифицированный рендеринг  , расположение и привязка строк приложения обеспечиваются подклассами — заменяя
 * предыдущие три почти скопированных шаблона адаптера.
 */
abstract class AppRowsAdapter(
    private val fragment: androidx.fragment.app.Fragment,
    private val rowLayout: Int,
    private val appsProvider: () -> List<VirtualLauncher.AppEntry>,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    /** Держатель строки приложения (строка содержимого вне заголовка группы). */
    abstract class AppHolder(
        row: android.view.View,
    ) : RecyclerView.ViewHolder(row) {
        abstract fun bind(entry: VirtualLauncher.AppEntry)
    }

    /** Создайте держатель строки приложения ([rowLayout] был раздут, не нужно заботиться о viewType). */
    protected abstract fun onCreateAppHolder(row: android.view.View): AppHolder

    val rows: List<SettingsRow>
        get() = SettingsRows.build(fragment.requireContext(), appsProvider())

    final override fun getItemViewType(position: Int): Int = if (rows[position] is SettingsRow.Header) 0 else 1

    final override fun getItemId(position: Int): Long = rows[position].id

    final override fun getItemCount(): Int = rows.size

    final override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        if (viewType == 0) {
            SettingsRows.HeaderHolder(SettingsRows.inflateHeader(parent))
        } else {
            onCreateAppHolder(fragment.layoutInflater.inflate(rowLayout, parent, false))
        }

    final override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val row = rows[position]) {
            is SettingsRow.Header -> (holder as SettingsRows.HeaderHolder).bind(row.title)
            is SettingsRow.App -> (holder as AppHolder).bind(row.entry)
        }
    }
}
