package io.github.shaonialife.cardeck.launcher

import android.content.ComponentName
import android.content.Context

/**
 * Настройки карты по умолчанию (для плитки информационной панели и запуска экрана карты).
 *
 * Белый список [VirtualLauncher.MAP_PACKAGES_PRIORITY] Одиночный выбор; если не установлено, иметь приоритет
 * был первым, кто его установил. Ключ постоянства — это имя пакета.
 *
 * ⚠️ Логика синтаксического анализа сосредоточена в [resolveDefaultMap] (чистая функция). **Не** позволяйте обратному вызову пути анализа
 * `VirtualLauncher.defaultMapComponent()`: Последний просто модулирует [resolve] этого объекта.
 * вызовет бесконечную рекурсию → StackOverflowError (решено 18 октября 2026 г., см. комментарийsolveDefaultMap).
 */
object DefaultMapPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_DEFAULT_MAP = "defaultMapPackage"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Имя пакета карты по умолчанию, явно заданное пользователем (ноль = не установлено, в порядке приоритета). */
    fun manualPackage(context: Context): String? = prefs(context).getString(KEY_DEFAULT_MAP, null)

    /** Текущая действующая карта по умолчанию (явные настройки имеют приоритет; в противном случае в порядке приоритета используется первая установленная карта). */
    fun resolve(context: Context): ComponentName? =
        resolveDefaultMap(
            manual = manualPackage(context),
            priority = VirtualLauncher.MAP_PACKAGES_PRIORITY,
            componentFor = { pkg -> launchComponent(context, pkg) },
        )

    /** Запускаемая активность этого пакета (не установлен/нет записи при запуске → ноль). */
    private fun launchComponent(
        context: Context,
        pkg: String,
    ): ComponentName? {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        val activity = context.packageManager.resolveActivity(launch, 0)?.activityInfo ?: return null
        // solveActivity может дать подсказку о других пакетах, если совпадений нет: будут приняты только пакеты с тем же именем пакета.
        // В противном случае явная настройка «удалено» будет ошибочно принята за доступную.
        if (activity.packageName != pkg) return null
        return ComponentName(pkg, activity.name)
    }

    fun set(
        context: Context,
        packageName: String?,
    ) {
        if (packageName == null) {
            prefs(context).edit().remove(KEY_DEFAULT_MAP).apply()
        } else {
            prefs(context).edit().putString(KEY_DEFAULT_MAP, packageName).apply()
        }
    }
}

/**
 * **чистая функция**, часть анализа карты по умолчанию (явно установленный приоритет → приоритетное сканирование).
 *
 * Есть две причины, по которым   стал независимым:
 * 1. Можно тестировать индивидуально (контекст не требуется) — см. DefaultMapPrefsTest.
 * 2. Обратные вызовы явно запрещены: эта функция полагается только на входящий [componentFor], а обратные вызовы не разрешены.
 * `VirtualLauncher.defaultMapComponent()`. В старой реализации `resolve()` находился в состоянии «Нет по умолчанию».
 * map», он вызывается обратно, и он вызывается обратно в `resolve()`, образуя бесконечную рекурсию → StackOverflowError.
 * Симптомы (фактическое тестирование 18 октября 2026 г.): Карта карты не отображается после удаления и
 * переустановки (предпочтения очищаются перед входом в эту ветку;
 * Исключение   было проглочено в logcat `Bg.run`, и файл журнала был полностью молчалив). Повторно выберите
 * карту по умолчанию в настройках.
 * восстанавливается мгновенно, что легко ошибочно принять за проблему виртуального экрана/рендеринга.
 */
internal fun <T> resolveDefaultMap(
    manual: String?,
    priority: List<String>,
    componentFor: (String) -> T?,
): T? {
    // Явно установить приоритет; он естественным образом вернется к приоритету, когда его приложение будет удалено.
    manual?.let { pkg -> componentFor(pkg)?.let { return it } }
    priority.forEach { pkg -> componentFor(pkg)?.let { return it } }
    return null
}

/**
 * Активность «Основного интерфейса карты» карты версии автомобиля  (явный ввод `am start -n`; если неизвестно, возвращается ноль).
 *
 * Почему нужно указывать это явно: намерение LAUNCHER автомобильной версии карты часто попадает на стартовую
 * страницу/страницу руководства (будет потеряно
 * `--display`, в результате чего интерфейс переходит на главный экран телефона вместо экрана карты). Фактический
 * тест версии автомобиля AutoNavi должен быть запущен явно.
 * `MainMapActivity`.
 *
 * Версия автомобиля и машины Baidu (`com.baidu.naviauto`) **не требует** явного ввода, возвращает значение null
 * и оставляет его для анализа в системе:
 * Лаунчер   размером 22.0.1 (код версии 1645)
 * `com.baidu.naviauto/.SlashActivity.DefaultActivity`, в комплекте нет независимого интерфейса основной карты.
 * Activity** — карта отображается `com.baidu.mapautosdk` в рамках этого действия.
 * (ошибочно было написано как `.NaviAutoActivity`: такой версии не существует, об этом напрямую сообщает `am start`
 * «Класс активности не существует»; хотя существуют альтернативные варианты, каждый раз при запуске он будет работать безрезультатно.
 * не смог выполнить возврат оболочки и оставил вводящие в заблуждение журналы, удаленные после фактического
 * тестирования 23 октября 2026 г. )
 *
 * ⚠️ Не гарантируется, что явная запись существует во всех версиях, поэтому вызывающая сторона должна использовать [mapLaunchCandidates].
 * Возьмите список кандидатов и попробуйте одного за другим. В случае неудачи вернитесь к разобранному лаунчером
 * компоненту - одна точка записи сделает
 * Экран карты черный и его невозможно проверить.
 */
internal fun carMapMainActivity(packageName: String): String? =
    when (packageName) {
        "com.autonavi.amapauto" -> "com.autonavi.amapauto/.MainMapActivity"
        else -> null
    }

/**
 * Цели-кандидаты `am start -n` для запуска карты  (проверено по порядку):
 * ① Явный основной интерфейс версии автомобиля (если известен) → ② Компонент, анализируемый лаунчером.
 */
internal fun mapLaunchCandidates(
    packageName: String,
    launcherComponent: String,
): List<String> {
    val explicit = carMapMainActivity(packageName)?.takeIf { it != launcherComponent }
    return listOfNotNull(explicit, launcherComponent).distinct()
}

/** Перегрузка ComponentName для [mapLaunchCandidates] (используется для реального пути к машине; одиночный тест использует чисто строковую версию). */
internal fun mapLaunchCandidates(component: ComponentName): List<String> =
    mapLaunchCandidates(component.packageName, component.flattenToShortString())
