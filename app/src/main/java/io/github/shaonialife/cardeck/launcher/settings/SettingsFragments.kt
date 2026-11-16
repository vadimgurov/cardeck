package io.github.shaonialife.cardeck.launcher.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.bt.AutoConnectPrefs
import io.github.shaonialife.cardeck.input.TouchRouter
import io.github.shaonialife.cardeck.launcher.AppIcons
import io.github.shaonialife.cardeck.launcher.DefaultMapPrefs
import io.github.shaonialife.cardeck.launcher.DesktopApps
import io.github.shaonialife.cardeck.launcher.DockPrefs
import io.github.shaonialife.cardeck.launcher.SupersamplePrefs
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.launcher.settings.AppRowsAdapter.AppHolder
import io.github.shaonialife.cardeck.ui.DarkMessageDialog
import io.github.shaonialife.cardeck.util.Bg

/**
 * Панель содержимого настроек автомобиля и машины (фрагмент): Настройки автомобиля и машины, двойной правый столбец,
 * Вторичные страницы меню мобильного телефона используют один и тот же набор экземпляров панели без логического копирования.
 */
class DesktopPanelFragment : SettingsPanelFragment() {
    private var countText: TextView? = null
    private var selected: MutableSet<String> = mutableSetOf()
    private lateinit var adapter: DesktopAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_desktop, container, false)

    override fun onViewCreated(view: View) {
        countText = view.findViewById(R.id.settingsCount)
        adapter = DesktopAdapter()
        setupAppList(view, R.id.appList, R.id.appListIndex, adapter) {
            selected.clear()
            selected += DesktopApps.selected(requireContext(), apps)
            updateCount()
        }
    }

    private fun updateCount() {
        countText?.text = "${selected.size} выбрано"
    }

    private fun toggle(
        entry: VirtualLauncher.AppEntry,
        checked: Boolean,
    ) {
        val key = entry.component.flattenToString()
        if (checked) selected.add(key) else selected.remove(key)
        DesktopApps.save(requireContext(), selected)
        updateCount()
        hostToast(if (checked) "«${entry.label}» отображается на рабочем столе." else "«${entry.label}» удален с рабочего стола.")
        adapter.notifyDataSetChanged()
    }

    private inner class DesktopAdapter : AppRowsAdapter(this@DesktopPanelFragment, R.layout.item_app_pick, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = PickerHolder(row)
    }

    private inner class PickerHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.pickIcon)
        private val name: TextView = row.findViewById(R.id.pickName)
        private val check: ImageView = row.findViewById(R.id.pickCheck)

        override fun bind(entry: VirtualLauncher.AppEntry) {
            val key = entry.component.flattenToString()
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            check.setImageResource(
                if (key in selected) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            row.setOnClickListener {
                val nowChecked = key !in selected
                toggle(entry, nowChecked)
                check.setImageResource(
                    if (nowChecked) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
                )
            }
        }
    }
}

class SupersamplePanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_supersample, container, false)

    override fun onViewCreated(view: View) {
        setupAppList(view, R.id.ssList, R.id.ssListIndex, SupersampleAdapter())
    }

    private inner class SupersampleAdapter : AppRowsAdapter(this@SupersamplePanelFragment, R.layout.item_app_ss, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = SsHolder(row)
    }

    private inner class SsHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.ssIcon)
        private val name: TextView = row.findViewById(R.id.ssName)
        private val segAuto: TextView = row.findViewById(R.id.ssAuto)
        private val segOn: TextView = row.findViewById(R.id.ssOn)
        private val segOff: TextView = row.findViewById(R.id.ssOff)

        override fun bind(entry: VirtualLauncher.AppEntry) {
            val iconPx = (28 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            refresh(SupersamplePrefs.mode(requireContext(), entry.component))

            fun pick(
                mode: Int,
                label: String,
            ) {
                SupersamplePrefs.setMode(requireContext(), entry.component, mode)
                refresh(mode)
                hostToast("Повышенное разрешение «${entry.label}»: $label (действует при следующем запуске)")
            }
            segAuto.setOnClickListener { pick(SupersamplePrefs.MODE_AUTO, "Авто") }
            segOn.setOnClickListener { pick(SupersamplePrefs.MODE_ON, "Включено") }
            segOff.setOnClickListener { pick(SupersamplePrefs.MODE_OFF, "Выключено") }
        }

        private fun refresh(mode: Int) {
            val segs =
                listOf(
                    segAuto to SupersamplePrefs.MODE_AUTO,
                    segOn to SupersamplePrefs.MODE_ON,
                    segOff to SupersamplePrefs.MODE_OFF,
                )
            segs.forEach { (view, segMode) ->
                val active = segMode == mode
                view.background = if (active) requireActivity().getDrawable(R.drawable.bg_segment_sel) else null
                view.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            }
        }
    }
}

class OrientationPanelFragment : SettingsPanelFragment() {
    /**
     * Набор пакетов, который фактически охватывается на стороне системы (проверяется один раз в фоновом режиме при
     * открытии панели, привязка строк выполняет только поиск в таблице памяти ——
     * `dumpsys platform_compat` около 30 мс/36 КБ каждый раз, ни разу на строку).
     * Обновить после открытия панели/сброса остатков; щелкните встроенный переключатель для обновления на месте,
     * чтобы избежать повторной проверки.
     */
    private var systemOn: Set<String> = emptySet()
    private var systemStateLoaded = false
    private lateinit var listAdapter: OrientationAdapter
    private lateinit var panelView: View

    /**
     * Поддерживает ли эта машина «принудительное горизонтальное отображение» — ноль = обнаружение.
     *
     * Два ChangeIds являются пользовательскими элементами OEM (не входят в стандартную таблицу AOSP): зарегистрирован Honor Android 15,
     * Redmi Android 13 не зарегистрирован. Если он не поддерживается, весь столбец будет выделен серым цветом и
     * будет указана причина. В противном случае пользователь будет неоднократно нажимать переключатель.
     * каждый раз получает только сообщение об ошибке (фактическое тестирование состоялось 18 октября 2026 г.).
     */
    private var supported: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_orientation, container, false)

    override fun onViewCreated(view: View) {
        panelView = view
        listAdapter = OrientationAdapter()
        // Пересчитайте баннер после того, как список приложений будет готов (приложения в первом кадре пусты, и все
        // будут ошибочно оценены как «нет в списке»)

        setupAppList(view, R.id.oriList, R.id.oriListIndex, listAdapter) { updateOrphanBanner() }
        // Обнаружение возможностей: один dumpsys (около 30 мс, результат кэшируется в процессе), параллельно с запросом состояния системы.
        Bg.run("orientation-capability") {
            val ok = AppOrientationCompat.changeIdsRegistered()
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                supported = ok
                applyCapabilityState()
                listAdapter.notifyDataSetChanged()
            }
        }
        Bg.run("orientation-system-state") {
            val state = AppOrientationCompat.systemEnabled()
            activity?.runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                systemOn = state
                systemStateLoaded = true
                listAdapter.notifyDataSetChanged()
                updateOrphanBanner()
            }
        }
    }

    /**
     * Настройте панель в соответствии с результатами обнаружения способностей: если она не поддерживается, будет
     * отображаться баннер, весь столбец будет затемнен и на него нельзя будет нажать.
     *
     * Делает список серым, а не скрывает его: пользователи могут видеть «Эта машина не может этого сделать» вместо
     * того, чтобы столкнуться с исчезающей функцией.
     * Угадай причину. Копия баннера объясняет, что ПЗУ отсутствует и его необходимо изменить, чтобы оно вступило в
     * силу (чтобы не принять его за проблему Шизуку).
     */
    private fun applyCapabilityState() {
        if (!::panelView.isInitialized) return
        val banner = panelView.findViewById<TextView>(R.id.oriUnsupportedBanner) ?: return
        val list = panelView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.oriList)
        val index = panelView.findViewById<LinearLayout>(R.id.oriListIndex)
        if (supported != false) {
            banner.visibility = View.GONE
            list?.alpha = 1f
            index?.alpha = 1f
            return
        }
        banner.visibility = View.VISIBLE
        banner.text =
            "Эта функция не поддерживается изначально: требуется Android 14 и выше." +
            "(Оба элемента совместимости с альбомной ориентацией появились на платформе только начиная с Android 14). \n" +
            "Текущая версия Android ${android.os.Build.VERSION.RELEASE}" +
            "(SDK ${android.os.Build.VERSION.SDK_INT}), переключатель неактивен и недоступен."
        // Потемнение всей колонки означает «неработоспособен»; события щелчка перехватываются нажатием, поддерживаемым в OriHolder
        list?.alpha = 0.38f
        index?.alpha = 0.38f
    }

    /**
     * Баннер , оставшийся за пределами панели (невозможно щелкнуть в списке, но клавиша наложения включена на
     * стороне системы): щелкните всплывающее окно для подтверждения, а затем очистите его.
     * включает две категории: неустановленные приложения (переопределения видны только в rawOverrides) и исторические грязные данные.
     * Клавиши       `пакет/компоненты` — им нет соответствующих строк в списке приложений, и маленькие символы в
     * строке не могут быть закрыты.
     */
    private fun updateOrphanBanner() {
        if (!::panelView.isInitialized) return
        val banner = panelView.findViewById<TextView>(R.id.oriOrphanBanner) ?: return
        // Не осуждайте, если список приложений не готов: пустые приложения сделают все открытые оверлеи «выглядящими как
        // остатки из списка».

        // (onAppsReady будет вызван снова после фактической загрузки списка)
        if (apps.isEmpty()) return
        val known = apps.map { it.component.packageName }.toSet()
        val orphans = systemOn.filter { it !in known }.sorted()
        if (orphans.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        // Ключ компонента (включая /) очень длинный: часть имени пакета при отображении обрезается, а полный ключ
        // остается для всплывающего окна.

        val summary =
            orphans.take(3).joinToString("、") { it.substringBefore('/') } +
                if (orphans.size > 3) " Подождите ${orphans.size}" else ""
        banner.text = "За пределами списка имеются остатки ${orphans.size} горизонтального экрана: $summary\nНажмите, чтобы очистить"
        banner.setOnClickListener {
            val host = activity ?: return@setOnClickListener
            DarkMessageDialog.show(
                activity = host,
                title = "Очистить остатки горизонтального экрана",
                message =
                    "Следующие элементы больше не находятся в списке приложений, но наложение ландшафта на стороне " +
                        "системы все еще включено:\n" +
                        orphans.joinToString("\n") { "· $it" } +
                        "\n\nОчистка немедленно перезапустит эти запущенные приложения (несохраненное состояние " +
                        "интерфейса будет потеряно);" +
                        "Удаленные записи не затрагиваются. Продолжать?",
                actions =
                    listOf(
                        DarkMessageDialog.Action("Отмена") {},
                        DarkMessageDialog.Action("Очистить", DarkMessageDialog.Emphasis.DANGER) {
                            Bg.run("orientation-orphan-clear") {
                                val cleared = orphans.count { AppOrientationCompat.clearSystemOnly(it) }
                                activity?.runOnUiThread {
                                    if (!isAdded || isDetached) return@runOnUiThread
                                    hostToast("Удалены остатки $cleared/${orphans.size} на горизонтальном экране.")
                                    systemOn = systemOn - orphans.toSet()
                                    listAdapter.notifyDataSetChanged()
                                    updateOrphanBanner()
                                }
                            }
                        },
                    ),
            )
        }
    }

    private inner class OrientationAdapter :
        AppRowsAdapter(this@OrientationPanelFragment, R.layout.item_orientation_car, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = OriHolder(row)
    }

    private inner class OriHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.oriIcon)
        private val name: TextView = row.findViewById(R.id.oriName)
        private val hint: TextView = row.findViewById(R.id.oriHint)
        private val check: ImageView = row.findViewById(R.id.oriCheck)
        private lateinit var entry: VirtualLauncher.AppEntry
        private var shown = false

        override fun bind(entry: VirtualLauncher.AppEntry) {
            this.entry = entry
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            // Источником факта является проверенный статус интерфейса: клик = инвертировать текущий статус отображения (без
            // перечитывания настроек,

            // Избегайте искажения показаний, приводящего к обратному вычислению направления - фактическое измерение «отмена»
            // снова станет «разрешением»)

            shown = AppOrientationCompat.isEnabled(requireContext(), pkg())
            refresh(shown)

            row.setOnClickListener {
                // Вверху панели обнаружено, что данный аппарат его не поддерживает (ПЗУ не прописал ChangeId): просто игнорируйте щелчок.
                // Больше никаких обреченных поездок туда и обратно, никаких надоедливых тостов.
                if (supported == false) return@setOnClickListener
                // **Asynchronous**: внутри setEnabled есть 2 оболочки (тайм-аут каждой 4 секунды, в худшем случае 8 секунд),
                // Прямые синхронные вызовы приведут к тому, что основной поток, в котором находится обратный вызов щелчка, зависнет в ANR.
                val target = !shown
                shown = target
                refresh(shown) // Эхо немедленно (оптимистичное обновление), откат в случае сбоя
                AppOrientationCompat.setEnabledAsync(requireContext(), pkg(), target) { result ->
                    if (!isAdded || isDetached) return@setEnabledAsync
                    when (result) {
                        AppOrientationCompat.WriteResult.OK -> {
                            // Обновить системный кеш на месте (запись выполнена синхронно, нет необходимости перепроверять dumpsys)
                            systemOn = if (target) systemOn + pkg() else systemOn - pkg()
                            refresh(target)
                            // am compat вступает в силу сразу после записи: система тут же убивает целевое приложение и при возврате
                            // перезапускается в новом направлении.

                            // (Фактическое измерение 2026-10-15: pid исчезает сразу после включения/выключения). Старый «проверка работы +»
                            // Процесс «Спросить, закончилось ли все» спрашивает о приложениях, которые были уничтожены системой, а pidof
                            // конкурирует с состоянием уничтожения,

                            // Если подсказка верна, а иногда и неверна («не работает» вводит в заблуждение), просто удалите ее полностью и
                            // скажите правду.

                            hostToast(
                                "Альбомная ориентация «${entry.label}»: ${if (target) "вкл." else "выкл."} — " +
                                    "приложение перезапустится.",
                            )
                        }

                        AppOrientationCompat.WriteResult.UNSUPPORTED -> {
                            shown = !target
                            refresh(shown)
                            supported = false
                            applyCapabilityState()
                            hostToast(
                                if (AppOrientationCompat.changeIdsRegistered()) {
                                    "Системное ограничение: пользовательская версия может изменять только настройки " +
                                        "совместимости отлаживаемых приложений."
                                } else {
                                    "Эта функция не поддерживается собственной системой (требуется Android 14 и выше)."
                                },
                            )
                        }

                        AppOrientationCompat.WriteResult.FAILED -> {
                            shown = !target
                            refresh(shown)
                            hostToast("Ошибка записи (Шизуку не готова или истекло время ожидания) — попробуйте еще раз позже.")
                        }
                    }
                }
            }
        }

        private fun pkg() = entry.component.packageName

        /**
         * Флажок  + небольшой текст о состоянии системы. Три государства:
         * Проверка включена + Система включена → Серый текст «Горизонтальный экран включен (действителен во время сеанса)» (нормальный)
         * Отметьте флажок + система включена → выделите красным «Остаток системы: горизонтальный экран все еще
         * действует, щелкните, чтобы очистить» (ненормально, щелкните, чтобы очистить)
         * Другое → Скрыть (флажок сам выражает предпочтение и не шумит)
         */
        private fun refresh(enabled: Boolean) {
            check.setImageResource(
                if (enabled) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            val onSystem = systemStateLoaded && pkg() in systemOn
            when {
                onSystem && enabled -> {
                    hint.text = "Горизонтальный экран включен (действителен во время сеанса)"
                    hint.setTextColor(0xFF8E8E93.toInt())
                    hint.visibility = View.VISIBLE
                    hint.setOnClickListener(null)
                    hint.isClickable = false
                }

                onSystem -> {
                    hint.text = "Остаток системы: Горизонтальный экран все еще действует · Нажмите, чтобы очистить"
                    hint.setTextColor(0xFFFF6B6B.toInt())
                    hint.visibility = View.VISIBLE
                    hint.isClickable = true
                    // Очистите только наложения на стороне системы и оставьте настройки без изменений (изначально настройки
                    // отключены); всплывающее окно перезапустит приложение.

                    hint.setOnClickListener {
                        val host = activity ?: return@setOnClickListener
                        DarkMessageDialog.show(
                            activity = host,
                            title = "Очистить остатки горизонтального экрана",
                            message =
                                "Наложение горизонтального экрана на стороне системы «${entry.label}» все еще действует " +
                                    "(возможно, остаток до удаления)." +
                                    "\n\nОчистка приведет к немедленному перезапуску приложения (запущенные приложения " +
                                    "потеряют несохраненное состояние интерфейса). Продолжать?",
                            actions =
                                listOf(
                                    DarkMessageDialog.Action("Отмена") {},
                                    DarkMessageDialog.Action("Очистить", DarkMessageDialog.Emphasis.DANGER) {
                                        Bg.run("orientation-row-clear") {
                                            val ok = AppOrientationCompat.clearSystemOnly(pkg())
                                            activity?.runOnUiThread {
                                                if (!isAdded || isDetached) return@runOnUiThread
                                                if (ok) {
                                                    systemOn = systemOn - pkg()
                                                    refresh(shown)
                                                    hostToast(
                                                        "Остатки горизонтального экрана «${entry.label}» удалены — " +
                                                            "приложение будет перезапущено.",
                                                    )
                                                } else {
                                                    hostToast("Очистить не удалось (исключение Сидзуку)")
                                                }
                                            }
                                        }
                                    },
                                ),
                        )
                    }
                }

                else -> {
                    hint.visibility = View.GONE
                    hint.setOnClickListener(null)
                }
            }
        }
    }
}

class DockPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_dock, container, false)

    override fun onViewCreated(view: View) {
        val scrimSeek = view.findViewById<SeekBar>(R.id.dockScrimSeek)
        val scrimValue = view.findViewById<TextView>(R.id.dockScrimValue)
        val blurSeek = view.findViewById<SeekBar>(R.id.dockBlurSeek)
        val blurValue = view.findViewById<TextView>(R.id.dockBlurValue)
        // Ширина столбца (50–100 %, с шагом 5; **вступает в силу при следующем подключении** – то же, что и в случае с
        // сеткой/размытием выше).

        // Эффект в реальном времени отличается: размер/плотность пикселей виртуального экрана док-станции создается в
        // соответствии с предпочтениями при установлении сеанса)

        val widthSeek = view.findViewById<SeekBar>(R.id.dockWidthSeek)
        val widthValue = view.findViewById<TextView>(R.id.dockWidthValue)
        val savedWidth = DockPrefs.widthPercent(requireContext())
        widthSeek.progress = (savedWidth - DockPrefs.WIDTH_MIN) / DockPrefs.WIDTH_STEP
        widthValue.text = "$savedWidth%"
        widthSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    widthValue.text = "${DockPrefs.WIDTH_MIN + progress * DockPrefs.WIDTH_STEP}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent =
                        DockPrefs.WIDTH_MIN + widthSeek.progress * DockPrefs.WIDTH_STEP
                    DockPrefs.setWidthPercent(requireContext(), percent)
                    hostToast("Ширина столбца закрепления $percent% сохранена и вступит в силу при следующем подключении.")
                }
            },
        )

        scrimSeek.progress =
            DockPrefs
                .scrimOpacity(requireContext())
        scrimValue.text = "${scrimSeek.progress}%"
        scrimSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scrimValue.text = "$value%"
                    DockPrefs
                        .setScrimOpacity(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )

        blurSeek.progress =
            DockPrefs
                .blur(requireContext())
        blurValue.text = "${blurSeek.progress}%"
        blurSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    blurValue.text = "$value%"
                    DockPrefs
                        .setBlur(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
    }
}

class DefaultMapPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_map, container, false)

    override fun onViewCreated(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.mapOptions)
        val installed =
            VirtualLauncher.MAP_PACKAGES_PRIORITY.mapNotNull { pkg ->
                val launch =
                    requireContext().packageManager.getLaunchIntentForPackage(pkg)
                        ?: return@mapNotNull null
                val label =
                    runCatching {
                        requireContext()
                            .packageManager
                            .getApplicationInfo(pkg, 0)
                            .loadLabel(requireContext().packageManager)
                            .toString()
                    }.getOrDefault(pkg)
                pkg to label
            }
        if (installed.isEmpty()) {
            container.addView(
                TextView(requireContext()).apply {
                    text = "Не установлено поддерживаемое картографическое приложение (автомобильная версия Amap/Baidu, " +
                        "мобильная версия Amap/Baidu/Tencent)"
                    setTextColor(0xFF8E8E93.toInt())
                    textSize = 13f
                },
            )
            return
        }
        var current =
            DefaultMapPrefs.manualPackage(requireContext())
                ?: installed.firstOrNull()?.first
        installed.forEach { (pkg, label) ->
            val row = layoutInflater.inflate(R.layout.item_map_option, container, false)
            val iconPx = (34 * resources.displayMetrics.density).toInt()
            val appIcon =
                runCatching {
                    requireContext().packageManager.getApplicationIcon(pkg)
                }.getOrNull()
            if (appIcon != null) {
                row
                    .findViewById<ImageView>(R.id.mapOptionIcon)
                    .setImageBitmap(AppIcons.rounded(appIcon, iconPx))
            }
            row.findViewById<TextView>(R.id.mapOptionName).text = label
            val radio = row.findViewById<ImageView>(R.id.mapOptionRadio)

            fun refresh() {
                radio.setImageResource(
                    if (pkg == current) R.drawable.bg_radio_on else R.drawable.bg_radio_off,
                )
            }
            refresh()
            row.setOnClickListener {
                current = pkg
                DefaultMapPrefs.set(requireContext(), pkg)
                for (i in 0 until container.childCount) {
                    (container.getChildAt(i).tag as? (() -> Unit))?.invoke()
                }
                hostToast("Карта по умолчанию: $label")
            }
            row.tag = { refresh() }
            container.addView(row)
        }
    }
}

/**
 * Панель «Дисплей»: масштабирование разрешения потока (30–160 %, вступает в силу при следующем подключении) +
 * целевая частота кадров (30/60,
 * переключение в реальном времени во время работы). Карта героя показывает эффективное значение во время работы
 * сеанса (снимок SessionController).
 * отличается от значения предпочтения на этой странице.
 */
class DisplayPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_display, container, false)

    override fun onViewCreated(view: View) {
        val ctx = requireContext()
        val scaleSeek = view.findViewById<SeekBar>(R.id.displayScaleSeek)
        val scaleValue = view.findViewById<TextView>(R.id.displayScaleValue)
        val scaleNote = view.findViewById<TextView>(R.id.displayScaleNote)
        val fps30 = view.findViewById<TextView>(R.id.btnFps30)
        val fps60 = view.findViewById<TextView>(R.id.btnFps60)

        // Истинный экран при подключении (физическая панель выключена): включен по умолчанию; переключение во время
        // сеанса вступает в силу немедленно

        val screenOffOn = view.findViewById<TextView>(R.id.btnScreenOffOn)
        val screenOffOff = view.findViewById<TextView>(R.id.btnScreenOffOff)
        var screenOffEnabled = DisplayPrefs.screenOffOnConnect(ctx)

        fun refreshScreenOff(enabled: Boolean) {
            screenOffOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            screenOffOn.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            screenOffOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            screenOffOff.setTextColor(if (enabled) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt())
        }

        refreshScreenOff(screenOffEnabled)

        fun applyScreenOff(enabled: Boolean) {
            if (enabled == screenOffEnabled) return
            screenOffEnabled = enabled
            DisplayPrefs.setScreenOffOnConnect(ctx, enabled)
            refreshScreenOff(enabled)
            // Им можно управлять на экране автомобиля: сразу втягивать и втягивать, не дожидаясь следующего подключения.
            SessionController.applyScreenOffPreference(enabled)
            hostToast(
                if (enabled) {
                    "Включено: физическая приостановка экрана при " +
                        "подключении."
                } else {
                    "Закрыто: использовать только темный экран при подключении."
                },
            )
        }
        screenOffOn.setOnClickListener { applyScreenOff(true) }
        screenOffOff.setOnClickListener { applyScreenOff(false) }

        // Автоматически подключаться при посадке в машину (автоматически начинать сеанс при подключении списка контроля
        // доступа Bluetooth в автомобиле): включено по умолчанию.

        val autoConnectOn = view.findViewById<TextView>(R.id.btnAutoConnectOn)
        val autoConnectOff = view.findViewById<TextView>(R.id.btnAutoConnectOff)
        var autoConnectEnabled = AutoConnectPrefs.enabled(ctx)

        fun refreshAutoConnect(enabled: Boolean) {
            autoConnectOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            autoConnectOn.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            autoConnectOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            autoConnectOff.setTextColor(if (enabled) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt())
        }

        refreshAutoConnect(autoConnectEnabled)

        fun applyAutoConnect(enabled: Boolean) {
            if (enabled == autoConnectEnabled) return
            autoConnectEnabled = enabled
            AutoConnectPrefs.setEnabled(ctx, enabled)
            refreshAutoConnect(enabled)
            hostToast(
                if (enabled) {
                    "Включено: автоматическое подключение при посадке в " +
                        "машину."
                } else {
                    "Закрыто: посадка в машину больше не начинается автоматически."
                },
            )
        }
        autoConnectOn.setOnClickListener { applyAutoConnect(true) }
        autoConnectOff.setOnClickListener { applyAutoConnect(false) }

        // Необработанный журнал HID (калибровка ключа): по умолчанию выключен; при включении hidSendReport записывает
        // одну строку HID-RAW для каждого кадра

        val hidRawOn = view.findViewById<TextView>(R.id.btnHidRawOn)
        val hidRawOff = view.findViewById<TextView>(R.id.btnHidRawOff)
        var hidRawEnabled = DisplayPrefs.hidRawLog(ctx)

        fun refreshHidRaw(enabled: Boolean) {
            hidRawOn.setBackgroundResource(
                if (enabled) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            hidRawOn.setTextColor(if (enabled) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            hidRawOff.setBackgroundResource(
                if (enabled) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            hidRawOff.setTextColor(if (enabled) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt())
        }

        refreshHidRaw(hidRawEnabled)

        fun applyHidRaw(enabled: Boolean) {
            if (enabled == hidRawEnabled) return
            hidRawEnabled = enabled
            DisplayPrefs.setHidRawLog(ctx, enabled)
            refreshHidRaw(enabled)
            hostToast(if (enabled) "Исходный журнал HID включен (выключите его после калибровки)" else "Необработанный журнал HID закрыт")
        }
        hidRawOn.setOnClickListener { applyHidRaw(true) }
        hidRawOff.setOnClickListener { applyHidRaw(false) }

        // Прогресс ползунка (0..26) → процент (30..160, шаг 5)
        fun progressOf(percent: Int) = (percent - DisplayPrefs.SCALE_MIN) / DisplayPrefs.SCALE_STEP

        fun percentOf(progress: Int) = DisplayPrefs.SCALE_MIN + progress * DisplayPrefs.SCALE_STEP

        val saved = DisplayPrefs.scalePercent(ctx)
        scaleSeek.progress = progressOf(saved)
        scaleValue.text = "$saved%"

        fun refreshScaleNote() {
            val runningNow =
                SessionController.videoSender
                    ?.isRunning == true
            scaleNote.text =
                if (runningNow) {
                    "После сохранения вступит в силу следующее подключение; для текущего соединения нажмите" +
                        "${SessionController.activeScalePercent} % выполняется"
                } else {
                    "После сохранения вступит в силу следующее подключение."
                }
        }
        refreshScaleNote()

        scaleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scaleValue.text = "${percentOf(progress)}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent = percentOf(scaleSeek.progress)
                    DisplayPrefs.setScalePercent(ctx, percent)
                    hostToast("Разрешение $percent% сохранено и вступит в силу при следующем подключении.")
                    refreshScaleNote()
                }
            },
        )

        fun refreshFps(active30: Boolean) {
            // Невыделенные фрагменты используют темный фон (того же цвета, что и контейнер) вместо нулевого фона, чтобы фон
            // не сбрасывал отступы.

            fps30.setBackgroundResource(
                if (active30) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            fps30.setTextColor(
                if (active30) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt(),
            )
            fps60.setBackgroundResource(
                if (active30) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            fps60.setTextColor(
                if (active30) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt(),
            )
        }

        val savedFps = DisplayPrefs.fps(ctx)
        refreshFps(savedFps < 60)

        fun applyFps(fps: Int) {
            DisplayPrefs.setFps(ctx, fps)
            refreshFps(fps < 60)
            VirtualLauncher
                .setStreamFps(fps)
            val live =
                SessionController.videoSender
                    ?.isRunning == true
            hostToast("Частота кадров изменена на ${fps}fps${if (live) " (применено сразу)" else ""}")
            if (live) SessionController.activeTargetFps = fps
        }
        fps30.setOnClickListener { applyFps(30) }
        fps60.setOnClickListener { applyFps(60) }

        // Регулирование сенсорного ввода (0 = выключено, шаг 8 мс): уменьшает накопление событий «задержка
        // взаимодействия, но нормальная частота кадров».

        val throttleSeek = view.findViewById<SeekBar>(R.id.throttleSeek)
        val throttleValue = view.findViewById<TextView>(R.id.throttleValue)

        fun throttleText(ms: Int) = if (ms <= 0) "Выключено" else "${ms}ms"

        val savedThrottle = DisplayPrefs.moveThrottleMs(ctx)
        throttleSeek.progress = savedThrottle / 8
        throttleValue.text = throttleText(savedThrottle)
        TouchRouter.moveThrottleMs = savedThrottle.toLong()

        throttleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    val ms = progress * 8
                    throttleValue.text = throttleText(ms)
                    DisplayPrefs.setMoveThrottleMs(ctx, ms)
                    TouchRouter.moveThrottleMs = ms.toLong()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    hostToast(
                        "касание дросселирования" + throttleText(DisplayPrefs.moveThrottleMs(ctx)),
                    )
                }
            },
        )
    }
}
