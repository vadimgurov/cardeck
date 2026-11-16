package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Дедупликация/фильтрация сканирования приложений (отзывы пользователей 23 октября 2026 г. о том, что «некоторые
 * приложения не были отсканированы» — это регрессия).
 *
 * live: старая реализация `queryIntentActivities(...).map{...}.take(200)` - верхний предел записывается перед сортировкой**,
 * принимает первые 200 чисел в порядке, возвращаемом системой. На моделях с большим количеством установленных
 * приложений приложения, перечисленные внизу, исчезнут автоматически.
 * А какие из них будут потеряны, зависит от порядка возврата системы, который выглядит как "несколько пропущено случайно".
 *
 * Фактические измеренные данные   (Redmi K40): система возвращает 83 действия программы запуска, а после дедупликации имеется 82 пакета.
 * (com.android.contacts содержит 4 действия Launcher в одном пакете).
 */
class LauncherScanTest {
    @Test
    fun duplicateLauncherActivitiesOfSamePackageCollapse() {
        // Если в пакете имеется несколько действий запуска, будет сохранено только одно (в противном случае на рабочем
        // столе появятся дублирующиеся значки).

        val result =
            dedupeLauncherPackages(
                listOf("com.android.contacts", "com.android.contacts", "com.android.contacts", "com.android.contacts", "com.zhihu.android"),
                selfPackage = "io.github.shaonialife.cardeck",
            )
        assertEquals(listOf("com.android.contacts", "com.zhihu.android"), result)
    }

    @Test
    fun selfPackageIsExcluded() {
        val result =
            dedupeLauncherPackages(
                listOf("com.other.app", "io.github.shaonialife.cardeck", "com.another.app"),
                selfPackage = "io.github.shaonialife.cardeck",
            )
        assertEquals(listOf("com.other.app", "com.another.app"), result)
    }

    @Test
    fun noTruncationBeyondTwoHundredApps() {
        // Старая реализация take(200) здесь потеряет 50 — это основная причина того, что «некоторые приложения не могут вымести»
        val many = (1..250).map { "com.example.app$it" }
        val result = dedupeLauncherPackages(many, selfPackage = "io.github.shaonialife.cardeck")
        assertEquals("Список приложений не должен быть усечен（старая реализация take(200) Потерять хвост）", 250, result.size)
        assertEquals("com.example.app250", result.last())
    }

    @Test
    fun originalOrderIsPreserved() {
        // Порядок определяется сортировкой вызывающего объекта, и эту функцию нельзя переставлять (иначе ключ сортировки
        // пиньинь будет не на своем месте).

        val result = dedupeLauncherPackages(listOf("c", "a", "b", "a"), selfPackage = "self")
        assertEquals(listOf("c", "a", "b"), result)
    }

    @Test
    fun emptyInputIsHandled() {
        assertEquals(emptyList<String>(), dedupeLauncherPackages(emptyList(), selfPackage = "self"))
    }
}
