package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессионный тест анализа карты по умолчанию.
 *
 * Фокусом   является путь «карта по умолчанию не установлена»: в старой реализации `resolve()` вызывался обратно в этой ветке.
 * `VirtualLauncher.defaultMapComponent()`, последний переносится обратно в `resolve()`, образуя бесконечную рекурсию
 * → StackOverflowError. Симптом заключается в том, что карточка карты не отображается после удаления и повторной
 * установки (она срабатывает только при очистке настроек), и
 * Пользователи   могут восстановиться после повторного выбора его один раз в настройках — это легко может быть
 * ошибочно принято за проблему виртуального экрана/рендеринга (фактическое тестирование состоялось 18 октября 2026 г.).
 *
 * использует строку имени пакета вместо ComponentName: `resolveDefaultMap` — это общая чистая функция.
 * Все методы класса Android (ComponentName) в единой тестовой среде   представляют собой нереализованные пустые заглушки.
 */
class DefaultMapPrefsTest {
    private val priority = listOf("car.map", "phone.map", "baidu.map")

    /** Используйте строку вместо «Установленное приложение → Запускаемый компонент». */
    private fun pick(
        manual: String?,
        installed: Set<String>,
    ): String? =
        resolveDefaultMap(
            manual = manual,
            priority = priority,
            componentFor = { pkg -> if (pkg in installed) pkg else null },
        )

    /** Карта по умолчанию не установлена: выберите первую «установленную» в порядке приоритета (здесь старая реализация приведет к переполнению стека). */
    @Test
    fun unsetFallsBackToPriorityOrder() {
        assertEquals("phone.map", pick(manual = null, installed = setOf("phone.map", "baidu.map")))
    }

    /** Явные настройки имеют приоритет над приоритетом. */
    @Test
    fun manualChoiceWins() {
        assertEquals("baidu.map", pick(manual = "baidu.map", installed = setOf("car.map", "baidu.map")))
    }

    /** Явно установить удаление приложения: вернуться к приоритету вместо возврата значения null или сбоя. */
    @Test
    fun uninstalledManualFallsBack() {
        assertEquals("car.map", pick(manual = "gone.map", installed = setOf("car.map")))
    }

    /** Карты нет: верните ноль (вызывающая сторона отображает подсказку «Плитка карты пуста»). */
    @Test
    fun noneInstalledReturnsNull() {
        assertNull(pick(manual = null, installed = emptySet()))
    }

    /** Повторный анализ должен завершаться каждый раз (старые реализации вызывали ошибку StackOverflowError). */
    @Test
    fun resolveTerminatesWithoutRecursion() {
        repeat(100) { assertNull(pick(manual = null, installed = emptySet())) }
    }
}

/**
 * Возможный порядок и резервный вход при запуске карты версии автомобиля и машины (Amap/Baidu).
 *
 * Предыстория: намерение LAUNCHER автомобильной версии карты часто попадает на стартовую страницу, а не в
 * основной интерфейс карты, поэтому его необходимо указать явно.
 * указывает основной вид деятельности; но **явная запись не гарантируется во всех прошивках/версиях** (версия
 * машины Baidu настраивается в разных версиях).
 * На прошивке   есть два типа NaviAutoActivity/SlashActivity). Итак, список кандидатов должен быть указан:
 * отдает приоритет явному основному интерфейсу и в случае сбоя возвращается к результату анализа средства запуска.
 */
class MapLaunchCandidatesTest {
    @Test
    fun gaodeCarEditionPrefersMainMapActivity() {
        assertEquals(
            listOf("com.autonavi.amapauto/.MainMapActivity", "com.autonavi.amapauto/.Launcher"),
            mapLaunchCandidates("com.autonavi.amapauto", "com.autonavi.amapauto/.Launcher"),
        )
    }

    @Test
    fun baiduCarEditionUsesLauncherEntryOnly() {
        // Версия автомобиля и машины Baidu (com.baidu.naviauto) не имеет явного входа в главный интерфейс: измерено в пакете 22.0.1.
        // Основной интерфейс карты отсутствует. Активность. Карта создается Mapautosdk в DefaultActivity программы запуска.
        // Внутренний рендеринг. Ошибочно было написано как .NaviAutoActivity (не существует), о запуске сообщается напрямую
        // «Класс активности не существует» — этот кандидат больше не сохраняется.
        assertEquals(
            listOf("com.baidu.naviauto/.SlashActivity.DefaultActivity"),
            mapLaunchCandidates("com.baidu.naviauto", "com.baidu.naviauto/.SlashActivity.DefaultActivity"),
        )
    }

    @Test
    fun phoneMapsFallBackToLauncherComponentOnly() {
        // Мобильная версия не имеет известного выделенного основного интерфейса: сохраняются только результаты анализа лаунчера.
        listOf("com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map").forEach { pkg ->
            assertEquals(listOf("$pkg/.Launcher"), mapLaunchCandidates(pkg, "$pkg/.Launcher"))
        }
    }

    @Test
    fun explicitEntryIsNotDuplicatedWhenItIsTheLauncher() {
        // Когда сама программа запуска указывает на основной интерфейс, не пытайтесь повторно использовать один и тот же компонент.
        assertEquals(
            listOf("com.baidu.naviauto/.NaviAutoActivity"),
            mapLaunchCandidates("com.baidu.naviauto", "com.baidu.naviauto/.NaviAutoActivity"),
        )
    }

    @Test
    fun baiduCarEditionIsWhitelistedAheadOfPhoneMaps() {
        val priority = VirtualLauncher.MAP_PACKAGES_PRIORITY
        val auto = priority.indexOf("com.baidu.naviauto")
        assertTrue("Версия автомобиля и машины Baidu должна быть в белом списке.", auto >= 0)
        assertTrue(
            "Автомобильная версия должна ранжироваться перед мобильной версией.（На автомобиле отдавайте приоритет " +
                "использованию версии автомобиля, адаптированной к горизонтальному экрану.）",
            auto < priority.indexOf("com.baidu.BaiduMap"),
        )
        assertTrue(auto < priority.indexOf("com.autonavi.minimap"))
    }
}
