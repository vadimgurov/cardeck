package io.github.shaonialife.cardeck.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стратегия повторной отправки привязки UserService (22 октября 2026 г. гвоздь регрессии Redmi K60E «неизбежный
 * тупик при сенсорном вводе»).
 *
 * Реальный испытательный полигон  : видео передается нормально, и событие касания достигает мобильного телефона,
 * но инъекция не удалась с первого раза, и количество ошибок возрастает до
 * 1700+; журнал обновляется каждые 2–3 секунды: «Состояние зависания привязки снято (тайм-аут привязки не был
 * отозван), немедленно повторите попытку».
 *
 * Основная причина   заключается в том, что окно принятия решения (1,5 с) имеет ту же величину, что и обычная
 * задержка обратного вызова, и unbindUserService(remove=true) будет unbindUserService(remove=true) после принятия решения.
 * — этот параметр **уничтожит процесс удаленного обслуживания**. Итак: «Инициировать → 1,5 секунды до смерти →
 * Завершить процесс → Возродиться →
 * Обратный вызов   снова опаздывает», образуя самоподдерживающийся джиттер, который никогда не сходится.
 */
class BindRebindPolicyTest {
    private val timeout = 8_000L

    @Test
    fun idleStateIsReadyToRebind() {
        // В настоящее время привязка не выполняется → может быть инициирована напрямую
        assertTrue(shouldRebind(binding = false, waitedMs = 0, stuckTimeoutMs = timeout))
    }

    @Test
    fun freshlyIssuedBindingKeepsWaiting() {
        // Вам придется подождать, пока он не будет только что инициирован (включая точку 1,5 с, которую старая
        // реализация неправильно оценит) - это корень тупика.

        listOf(0L, 100L, 1_500L, 1_501L, 3_000L, 7_999L).forEach { waited ->
            assertFalse(
                "подожди ${waited}ms Не следует приговаривать к смертной казни（Старая реальность сейчас 1.5s Просто " +
                    "приговорите его к смерти и убейте процесс обслуживания.）",
                shouldRebind(binding = true, waitedMs = waited, stuckTimeoutMs = timeout),
            )
        }
    }

    @Test
    fun onlyGenuinelyStuckBindingIsRebound() {
        assertTrue(shouldRebind(binding = true, waitedMs = timeout, stuckTimeoutMs = timeout))
        assertTrue(shouldRebind(binding = true, waitedMs = timeout * 3, stuckTimeoutMs = timeout))
    }

    @Test
    fun zeroBindingSinceIsTreatedAsJustIssued() {
        // Когда привязкаSince не записана (0), ее не следует «Долго ждать» и немедленно отправлять повторно.
        assertFalse(shouldRebind(binding = true, waitedMs = 0, stuckTimeoutMs = timeout))
    }
}
