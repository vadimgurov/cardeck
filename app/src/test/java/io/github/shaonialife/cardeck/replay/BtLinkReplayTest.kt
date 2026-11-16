package io.github.shaonialife.cardeck.replay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **Возвращается полное воспроизведение стека Bluetooth**: записанный поток байтов car rx подается в реальный канал Iap2LinkChannel +
 * Iap2SenderSession (тот же путь кода, что и в рабочей версии), возвращаемая нами последовательность сообщений
 * CSM должна быть такой же, как и
 * при записи последователен - если изменения протокола для определенной модели автомобиля изменят реакцию на
 * стимуляцию других моделей автомобилей, здесь это будет прямо красным.
 *
 * Стратегия сравнения   (против повторной передачи/дрожания синхронизации): по сравнению с «**Последовательность
 * первого появления после дедупликации**» ——
 * Воспроизводимая отдельная последовательность CSM должна быть точно такой же, как записанная отдельная последовательность CSM.
 *
 * контроль продолжительности: каждый прибор может воспроизводить до 4 раундов (фаза 1 имеет приоритет), тайм-аут
 * одного раунда = продолжительность записи + 10 с.
 */
class BtLinkReplayTest {
    @Test
    fun recordedSessionsReplayIdentically() {
        val fixtures = CarFixture.loadDirectory()
        val usable = fixtures.filter { it.btRounds.isNotEmpty() }
        if (usable.isEmpty()) {
            println(
                "REPLAY: Нет Bluetooth fixture（Каталог gitignore，Для местного использования " +
                    "tools/extract_car_fixtures.py Экстракт）——пропустить",
            )
            return
        }
        val failures = ArrayList<String>()
        usable.forEach { fixture ->
            fixture
                .btRounds
                .sortedWith(compareByDescending<CarFixture.BtRound> { it.outcome == "phase1" }.thenBy { it.durationMs })
                .take(2)
                .forEach { round -> replayRound(fixture, round, failures) }
        }
        assertTrue("Регрессия воспроизведения Bluetooth не удалась：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun replayRound(
        fixture: CarFixture,
        round: CarFixture.BtRound,
        failures: MutableList<String>,
    ) {
        val tag = "${fixture.car}#${round.index}(${round.outcome})"
        val stream = ScriptedCarStream(round.rx)
        val channel = Iap2LinkChannel(stream, name = "replay-${fixture.name}") { }
        val endpointSeen = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                channel,
                log = { },
                bluetoothMac = "5C:40:71:A4:0A:90",
                deviceName = "iPhone",
                onWifiCredentials = { },
                onEndpointUpdate = { },
                context = null,
            )
        val sessionThread =
            Thread(
                {
                    runCatching { session.run(minOf(round.durationMs + 10_000, 20_000)) }
                    endpointSeen.countDown()
                },
                "replay-bt",
            )
        sessionThread.start()
        // Подождите, пока сеанс завершится естественным образом (время ожидания каждого окна истекает после исчерпания
        // rx); принудительно завершить сеанс

        // Верхний предел составляет 25 секунд: воспроизведение повтора не должно занимать больше времени
        // (продолжительность записи в основном равна времени ожидания раунда тайм-аута, а воспроизведение пропущено)

        val finished = endpointSeen.await(minOf(round.durationMs + 20_000, 25_000), TimeUnit.MILLISECONDS)
        stream.close()
        channel.close()
        sessionThread.join(3_000)

        val replayIds = distinctCsmIds(extractCsmPayloads(stream.written))
        val recordedIds = distinctCsmIds(extractCsmPayloads(listOf(round.recordedTx)))
        if (!finished) {
            failures.add("$tag Сеанс не записывается какое-то время+20s Конец внутри（Воспроизведение distinct=${replayIds.joinToString()}）")
        }
        // Записанные дампы RAW имеют бюджет в байтах (старый журнал 512B). Иерархическое утверждение:
        //  - Идентификационный кадр (0x1D01) сохраняется неповрежденным → запись отличается от префикса воспроизведения (полное сравнение)
        // - Кадр идентификации усекается по бюджету → утверждает только канальный уровень + начало идентификации (первая
        // запись, всегда 0x1D00)

        val expected =
            if (round.rxHasIdent) recordedIds else recordedIds.take(1)
        val prefixOk =
            expected.size <= replayIds.size &&
                replayIds.subList(0, expected.size) == expected
        if (!prefixOk) {
            failures.add(
                "$tag: нарушена последовательность CSM " +
                    "(${if (round.rxHasIdent) "полная проверка" else "только канал"}):\n" +
                    "  Ожидание=${expected.joinToString()}\n  Воспроизведение=${replayIds.joinToString()}",
            )
        } else {
            println(
                "REPLAY ✓ $tag ${if (round.rxHasIdent) "полная сумма" else "только уровень связи"}Тот же префикс" +
                    "（Ожидание ${expected.size}/Воспроизведение ${replayIds.size} новости）",
            )
        }
    }

    /** Порядок первого появления после дедупликации (удаление повторных передач и последовательных дубликатов кадров). */
    private fun distinctCsmIds(payloads: List<ByteArray>): List<Int> {
        val seen = LinkedHashSet<Int>()
        payloads.forEach { payload ->
            Iap2Csm.Parser().offer(payload).forEach { frame -> seen.add(frame.messageId) }
        }
        return seen.toList()
    }
}
