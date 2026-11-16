package io.github.shaonialife.cardeck.replay

import io.github.shaonialife.cardeck.airplay.VirtualIap2Link
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Возврат повтора туннеля**: Записанный CSM автомобильно-машинного туннеля (IAPLINK rx) подается на реальный один за другим.
 * [VirtualIap2Link] (форма MIB3), последовательность номеров сообщений CSM, которую мы возвращаем, должна быть такой же, как записанная.
 * Последовательность передачи туннеля   согласована (0x4E0C/0x4E0B и т. д. содержат случайный контент, сравните
 * номер сообщения и последовательность первого появления).
 *
 * Сторона автомобиля   воспроизводится движком (повторно используя метод смены кадров VirtualIap2LinkTest), а записанное тело CSM
 * внедряется через движок — фокусируется на управлении конечным автоматом сеанса и не воспроизводит канальный уровень.
 */
class TunnelReplayTest {
    @Test
    fun recordedTunnelCsmReplaysIdentically() {
        val fixtures = CarFixture.loadDirectory()
        val usable = fixtures.filter { it.tunnelRx.isNotEmpty() }
        if (usable.isEmpty()) {
            println("REPLAY: Нет туннеля fixture——пропустить")
            return
        }
        val failures = ArrayList<String>()
        usable.forEach { fixture -> replay(fixture, failures) }
        assertTrue("Регрессия воспроизведения туннеля не удалась：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private class Harness {
        val controlAll = ArrayList<ByteArray>()
        val eventAll = ArrayList<ByteArray>()
        var clock = 0L
        var engineClock = 1L
        val car = Iap2LinkEngine()
        val pending = ArrayList<ByteArray>()
        val link =
            VirtualIap2Link(
                log = { },
                sendEventIap = {
                    eventAll.add(it)
                    true
                },
                sendControlIap = {
                    controlAll.add(it)
                    pending.add(it)
                },
                tunnelWriter = { null },
                alive = { false },
                nowMillis = { clock },
            )

        fun exchange() {
            var guard = 0
            while (guard++ < 8) {
                val out = car.takeOutput()
                if (out.isNotEmpty()) link.onCarBytes(out)
                drainPendingToCar()
                engineClock += 600
                clock += 600
                car.advanceTime(engineClock)
                drainPendingToCar()
                if (car.state == Iap2LinkEngine.State.NORMAL && pending.isEmpty()) return
            }
        }

        private fun drainPendingToCar() {
            while (pending.isNotEmpty()) {
                val batch = pending.toList()
                pending.clear()
                batch.forEach { car.feed(it, engineClock) }
            }
        }
    }

    private fun replay(
        fixture: CarFixture,
        failures: MutableList<String>,
    ) {
        val h = Harness()
        h.link.begin()
        h.link.armMib3()
        h.car.start(h.engineClock)
        h.exchange()
        assertEquals("${fixture.car}: Ссылка не достигнута NORMAL", Iap2LinkEngine.State.NORMAL, h.car.state)

        fixture.tunnelRx.forEach { (messageId, body) ->
            assertTrue(
                "${fixture.car}: Вводить 0x${messageId.toString(16)} не удалось（Двигатель отказывается присоединяться к очереди）",
                h.car.sendControl(Iap2Csm.encode(messageId, body), h.engineClock),
            )
            h.exchange()
        }

        val replayCore = distinctCsmIds(extractCsmPayloads(h.controlAll))
        val recordedCore = fixture.tunnelTxIds.distinct()
        // Старые версии могут записывать меньше ответов, чем текущий код (намеренные дополнения, такие как пакеты MIB3).
        // Запись должна быть упорядоченной подпоследовательностью результата;
        // дополнительные ответы текущего кода разрешены.
        val subsequenceOk = isOrderedSubsequence(recordedCore, replayCore)
        if (!subsequenceOk) {
            failures.add(
                "${fixture.car}: Смещение последовательности ответов туннеля（Запись должна представлять собой " +
                    "упорядоченную последовательность воспроизведения.）\n" +
                    "  Запись=${recordedCore.joinToString { "0x%04x".format(it) }}\n" +
                    "  Воспроизведение=${replayCore.joinToString { "0x%04x".format(it) }}",
            )
        } else {
            println(
                "REPLAY ✓ ${fixture.car} Последовательность туннелей согласована（Запись ${recordedCore.size} ⊆ " +
                    "Воспроизведение ${replayCore.size}）",
            )
        }
    }

    private fun isOrderedSubsequence(
        needle: List<Int>,
        haystack: List<Int>,
    ): Boolean {
        var i = 0
        haystack.forEach { id ->
            if (i < needle.size && needle[i] == id) i += 1
        }
        return i == needle.size
    }

    private fun distinctCsmIds(payloads: List<ByteArray>): List<Int> {
        val seen = LinkedHashSet<Int>()
        payloads.forEach { payload ->
            Iap2Csm.Parser().offer(payload).forEach { frame -> seen.add(frame.messageId) }
        }
        return seen.toList()
    }
}
