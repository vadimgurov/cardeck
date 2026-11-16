package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MFi бросает вызов долговременной памяти автомобильных прессов.
 *
 * Предыстория   (фактическое измерение Audi MMI 3315 19 октября 2026 г.): 20B бросил вызов машине, и машина
 * молчала в течение 6 секунд, не отвечая, и вошел 32B.
 * возвращает подпись через 0,64 секунды. Различные ресиверы имеют противоположные предпочтения (DiPlay
 * распознает только 32B, MMI 6318 распознает 20B), поэтому
 * Память   должна храниться отдельно в соответствии с идентичностью транспортного средства и машины, а холодный
 * запуск должен иметь разумный базовый уровень.
 */
class MfiLengthMemoryTest {
    private val baselineCar = listOf(20, 32, 16) // Реальная серия автомобилей: приоритет 20B
    private val baselineTestBench = listOf(32, 20, 16) // Испытательный стенд: приоритет 32Б

    @Test
    fun receiverKeyIsStableAndFilesystemFriendly() {
        // Название 0x1D01 + модель + соединение производителя (с пробелами и регистровыми различиями)
        val a = MfiLengthMemory.receiverKey("audi_mmi_3315 audi 5163 delphi")
        val b = MfiLengthMemory.receiverKey("Audi_MMI_3315  AUDI 5163 DELPHI")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[a-z0-9_]+")))
        // Разные машины необходимо ставить на разные ключи (3315 и 6318 не могут засорять память друг друга)
        assertFalse(a == MfiLengthMemory.receiverKey("audi_mmi_6318 audi 3260 delphi"))
    }

    @Test
    fun emptyMemoryKeepsBaselineOrder() {
        assertEquals(baselineCar, MfiLengthMemory.orderAttempts(baselineCar, MfiLengthMemory.Entry()))
        assertEquals(
            baselineTestBench,
            MfiLengthMemory.orderAttempts(baselineTestBench, MfiLengthMemory.Entry()),
        )
    }

    @Test
    fun rememberedSuccessGoesFirst() {
        // MMI 3315 помнит успех 32B → сразу 32B в следующем раунде, больше не попадая первым 20B и 6 секунд молчания
        val entry = MfiLengthMemory.Entry(succeeded = 32)
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.orderAttempts(baselineCar, entry))
        // То же самое относится и к сценарию на испытательном стенде (изначально 32В находится спереди, порядок остается неизменным)
        assertEquals(listOf(32, 20, 16), MfiLengthMemory.orderAttempts(baselineTestBench, entry))
    }

    @Test
    fun silentAndRejectedLengthsSinkButStayAsFallback() {
        val entry = MfiLengthMemory.Entry(silent = setOf(20), rejected = setOf(16))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        // Нет никакого уровня успеха; 20B молчит, 16B отклоняется, а 32B выдвигается.
        assertEquals(listOf(32, 20, 16), order)
        // Не теряйте передачи: память влияет только на порядок, а не на определенную длину, которая никогда не будет опробована.
        assertEquals(baselineCar.toSet(), order.toSet())
    }

    @Test
    fun allRejectedFallsBackToTryingEverything() {
        // Возник конфликт между памятью и текущей ситуацией (прошивка изменена/разные машины с одинаковым
        // идентификатором): Если вам все отказали, постарайтесь, как обычно, избежать постоянного зависания.

        val entry = MfiLengthMemory.Entry(rejected = setOf(20, 32, 16))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(baselineCar, order)
    }

    @Test
    fun onlySucceededLengthIsDroppedFromDuplicateCandidates() {
        val entry = MfiLengthMemory.Entry(succeeded = 20)
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals(20, order.first())
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun succeededAndRejectedContradictionDemotesMemory() {
        // 24 октября 2026 г. Фактический тест DiPlay: Поздний ответ ошибочно успешно записал 20B, а затем 20B отключился в каждом раунде.
        // В противоречивом состоянии (успех ∧ отклонен) 20B больше не может занимать первое место — понизьте его до
        // участия в обычном рейтинге.

        val entry = MfiLengthMemory.Entry(succeeded = 20, rejected = setOf(20))
        val order = MfiLengthMemory.orderAttempts(baselineCar, entry)
        assertEquals("Стенд, который был принудительно отклонен, не может снова занимать первое место.", 32, order.first())
        assertTrue("Конфликтная позиция все еще находится в списке кандидатов（Прошивка может измениться）", 20 in order)
    }

    @Test
    fun describeShowsWhatWasRemembered() {
        assertEquals("Нет", MfiLengthMemory.describe(MfiLengthMemory.Entry()))
        val text =
            MfiLengthMemory.describe(
                MfiLengthMemory.Entry(succeeded = 32, silent = setOf(20), rejected = setOf(16)),
            )
        assertTrue(text.contains("успех=32B"))
        assertTrue(text.contains("тишина=20B"))
        assertTrue(text.contains("отклонено=16B"))
    }
}
