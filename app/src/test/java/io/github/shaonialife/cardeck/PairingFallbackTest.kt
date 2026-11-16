package io.github.shaonialife.cardeck

import io.github.shaonialife.cardeck.airplay.pairingRetryBackoffMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полный тест решения проблемы спаривания: все сцены взяты из сравнения системной диагностики реальной машины
 * (17.10.2026, docs/PROTOCOL-NOTES.md §13).
 */
class PairingFallbackTest {
    @Test
    fun `Успешное сопряжение не требует резервного сценария`() {
        val f =
            pairingFallback(
                true,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `Audi 403 после verify — не разрывать BT и ждать повторного приглашения`() {
        // Захват пакетов на реальном компьютере: раздел аутентификации 403 после прохождения парной проверки
        // (отображается как исключение проверки/аутентификации IOException для клиента)

        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = true,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse("403=Автомобиль не готов，сломать BT пропущу 18~20s автоматическое повторное приглашение", f.useBtRetireHack)
        assertFalse("403=Доверие установлено，Экспресс будет отклонен", f.usePlainSession)
    }

    @Test
    fun `pair-setup 403 - Все еще не удалось после быстрого пути - Также запрещено BT`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = true,
                pairingForbidden = true,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `DiPlay Форма разорванной цепи - Нет 403 - Зарезервировать второй абзац hack`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertTrue(f.useBtRetireHack)
        assertTrue(f.usePlainSession)
    }

    @Test
    fun `pre-HomeKit старая машина машина - Сопряжение не поддерживается - Говори правду ясно`() {
        val f =
            pairingFallback(
                false,
                noPairing = true,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = false,
                pairingUnsupported = true,
            )
        assertFalse(f.useBtRetireHack)
        assertTrue(f.usePlainSession)
    }

    @Test
    fun `Конечная точка не решена - RTSP Не подключено - Оба кармана не включены`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = false,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `Повторить откат - 403 длинный - Остальные короткие`() {
        assertEquals(5_000L, pairingRetryBackoffMs("RTSP/1.0 403 Forbidden"))
        assertEquals(5_000L, pairingRetryBackoffMs("HTTP/1.1 403"))
        assertEquals(500L, pairingRetryBackoffMs("RTSP/1.0 500 Internal"))
        assertEquals(500L, pairingRetryBackoffMs("Connection reset"))
    }
}
