package io.github.shaonialife.cardeck.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Патч  SPS level_idc (22 октября 2026 г., Audi 0628, ремонт «Push Flow нормальный, но экран автомобиля черный»).
 *
 * Справочная информация: MediaFormat.KEY_LEVEL игнорируется фактическими измерениями (кодировщик MagicOS не
 * генерирует исключения и не вступает в силу),
 * по умолчанию имеет разрешение 1412x654 при 30 кадрах в секунду и по закону соответствует уровню 3.1/3.2 -
 * декодер Audi DELPHI поддерживает этот уровень.
 * молчит и не выполняет рендеринг. Исторически все успешные сеансы с реальными автомобилями имели уровень 4.0
 * (та же модель, что и настоящие iPhone).
 */
class SpsLevelPatchTest {
    /** Создайте минимальный SPS: заголовок NAL(1B) + профиль(1B) + совместимость(1B) + уровень(1B) + хвост. */
    private fun spsOf(level: Int) = byteArrayOf(0x67, 0x64, 0x00, level.toByte(), 0x11, 0x22)

    @Test
    fun levelBelow40IsRaised() {
        // И 3.1(31), и 3.2(32) следует дополнить до 40.
        for (level in listOf(31, 32)) {
            val patched = patchSpsLevel(spsOf(level))
            assertEquals(40, patched[3].toInt() and 0xFF)
            // Остальные байты остаются неизменными
            assertEquals(0x64, patched[1].toInt() and 0xFF)
            assertEquals(6, patched.size)
        }
    }

    @Test
    fun levelAtOrAbove40IsUntouched() {
        // 4.0(40), 4.2(42) сохраняют исходное значение – без понижения версии
        for (level in listOf(40, 41, 42)) {
            val sps = spsOf(level)
            assertTrue(patchSpsLevel(sps).contentEquals(sps))
        }
    }

    @Test
    fun malformedSpsIsPassedThrough() {
        // Слишком короткие SPS (отсутствующие байты уровня) возвращаются как есть, без их выбрасывания.
        assertTrue(patchSpsLevel(byteArrayOf(0x67)).size == 1)
    }

    @Test
    fun nonSpsPayloadUntouched() {
        // Уже 40 без изменений (даже если остальные байты выглядят как SPS)
        val sps = spsOf(40)
        val out = patchSpsLevel(sps)
        assertEquals(sps[3], out[3])
    }
}
