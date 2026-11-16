package io.github.shaonialife.cardeck.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Эффективные условия для автоматического затемнения экрана.
 *
 * 19 октября 2026 г. Отзыв пользователя: «После включения темного экрана, даже если сеанс отключен, темный экран
 * все равно будет активирован через некоторое время».
 * Первопричина в том, что при запуске приложения прикрепляет расписания таймер на 45 секунд, а со стороны автомобиля таймер отключается.
 * `VideoSender.onEnded` — VirtualLauncher не освобождается, а таймер срабатывает как обычно.
 *
 * Теперь автоматический темный экран должен соответствовать следующим требованиям: настройки включены + сеанс
 * активен + прикреплен (с разрешением плавающего окна).
 * Если какое-либо из условий не выполнено, воспроизведение на темном экране невозможно; ручной «фальшивый экран»
 * не подлежит ограничениям сеанса (явное действие пользователя).
 */
class ScreenDimmerTest {
    @Test
    fun autoDimRequiresActiveSession() {
        assertFalse(
            "Никогда больше не играйте на темном экране после окончания сеанса.（На этот раз bug）",
            ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = false, attached = true),
        )
        assertTrue(
            ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = true, attached = true),
        )
    }

    @Test
    fun autoDimRequiresSettingEnabled() {
        assertFalse(ScreenDimmer.shouldAutoDim(enabled = false, sessionActive = true, attached = true))
    }

    @Test
    fun autoDimRequiresAttachment() {
        // Если разрешение на плавающее окно отсутствует, windowManager имеет значение null и showDim не может добавить окно.
        assertFalse(ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = true, attached = false))
    }

    @Test
    fun allConditionsMustHold() {
        val cases =
            listOf(
                Triple(true, true, true) to true,
                Triple(true, true, false) to false,
                Triple(true, false, true) to false,
                Triple(true, false, false) to false,
                Triple(false, true, true) to false,
                Triple(false, true, false) to false,
                Triple(false, false, true) to false,
                Triple(false, false, false) to false,
            )
        cases.forEach { (input, expected) ->
            val (enabled, session, attached) = input
            assertTrue(
                "enabled=$enabled session=$session attached=$attached Ожидание $expected",
                ScreenDimmer.shouldAutoDim(enabled, session, attached) == expected,
            )
        }
    }
}
