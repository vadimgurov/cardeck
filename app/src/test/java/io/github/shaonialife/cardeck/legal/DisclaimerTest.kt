package io.github.shaonialife.cardeck.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclaimerPolicyTest {
    @Test
    fun contentNotScrollableOnlyNeedsCountdown() {
        // Копия может поместиться на одном экране (нет необходимости прокручивать): Просто дождитесь обратного отсчета.
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = false, scrolledToBottom = false))
        assertTrue(canAcceptDisclaimer(countdownDone = true, contentScrollable = false, scrolledToBottom = false))
    }

    @Test
    fun scrollableContentRequiresBothCountdownAndBottom() {
        assertFalse(canAcceptDisclaimer(countdownDone = true, contentScrollable = true, scrolledToBottom = false))
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = true, scrolledToBottom = true))
        assertTrue(canAcceptDisclaimer(countdownDone = true, contentScrollable = true, scrolledToBottom = true))
    }

    @Test
    fun shorterElapsedButAtBottomStillBlocked() {
        // Скольжение вниз, но не ожидание 10 секунд: все еще неприемлемо (два условия связаны)
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = true, scrolledToBottom = true))
    }
}

class DisclaimerVersionTest {
    @Test
    fun firstRunNeedsDisclaimer() {
        assertTrue(needsDisclaimerAtLaunch(acceptedVersion = 0, currentVersion = 1))
    }

    @Test
    fun acceptedCurrentVersionNeedsNothing() {
        assertFalse(needsDisclaimerAtLaunch(acceptedVersion = 1, currentVersion = 1))
        // Старая версия принята (1), но сейчас это 2: копия изменилась и должна быть подтверждена повторно.
        assertTrue(needsDisclaimerAtLaunch(acceptedVersion = 1, currentVersion = 2))
        // Принята более новая версия (резервный вариант для установки старого APK): больше не беспокойтесь.
        assertFalse(needsDisclaimerAtLaunch(acceptedVersion = 3, currentVersion = 2))
    }
}

class DisclaimerHintTest {
    @Test
    fun hintsExplainWhyAcceptIsBlocked() {
        // Ни один из них не удовлетворен: в подсказке указаны требования как обратного отсчета, так и скользящего.
        assertEquals(
            "Прокрутите страницу вниз и подождите 7 секунд.",
            disclaimerBlockReason(
                countdownDone = false,
                contentScrollable = true,
                scrolledToBottom = false,
                remainingSeconds = 7,
            ),
        )
        // Только обратный отсчет
        assertEquals(
            "Пожалуйста, подождите 3 секунд, прежде чем принять",
            disclaimerBlockReason(
                countdownDone = false,
                contentScrollable = false,
                scrolledToBottom = false,
                remainingSeconds = 3,
            ),
        )
        // только прокручивать
        assertEquals(
            "Пожалуйста, прокрутите вниз, чтобы прочитать",
            disclaimerBlockReason(
                countdownDone = true,
                contentScrollable = true,
                scrolledToBottom = false,
                remainingSeconds = 0,
            ),
        )
        // Принято: без запроса
        assertNull(
            disclaimerBlockReason(
                countdownDone = true,
                contentScrollable = true,
                scrolledToBottom = true,
                remainingSeconds = 0,
            ),
        )
    }
}

class DisclaimerContentTest {
    @Test
    fun contentCoversMandatoryTopics() {
        val text = DisclaimerContent.sections.joinToString("\n") { it.title + "\n" + it.body }
        // При совместном использовании необходимо четко объяснить три категории контента: объем тестирования, риски
        // вождения и системные разрешения.

        assertTrue(text.contains("Проверены только следующие конфигурации"))
        assertTrue(text.contains("Безопасность вождения"))
        assertTrue(text.contains("Shizuku"))
        // Неофициальное заявление (чтобы вас не приняли за связь с Apple)
        assertTrue(text.contains("неофициальный"))
        assertTrue(text.contains("товарными знаками"))
    }

    @Test
    fun contentHasNoMarkdownMarkers() {
        // Если текст вводится непосредственно в TextView: `**`/`#` будет отображаться как есть, что является ошибкой при наборе текста.
        val text = DisclaimerContent.sections.joinToString("") { it.body }
        assertFalse(text.contains("**"))
        assertFalse(text.contains("##"))
        assertFalse(text.contains("`"))
    }
}
