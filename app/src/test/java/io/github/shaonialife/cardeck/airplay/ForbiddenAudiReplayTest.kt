package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Тест воспроизведения «Audi MMI 3315_5 ГГц должен быть отклонен при первом подключении» (системная диагностика
 * реальной машины 21 октября 2026 г.,
 * docs/PROTOCOL-NOTES.md §13a): Транспортное средство всегда отвечает на 403 для запросов на сопряжение в
 * пределах своего собственного окна готовности.
 * (После прохождения проверки пары настоящего iPhone в разделе аутентификации по-прежнему будет 403, и телефон
 * автоматически включится через 18–20 секунд.
 * Повторное приглашение, второй раз будет принято).
 *
 * Приемник с поддержкой сценариев   = ServerSocket обратной связи для всех запросов на настройку пары/проверку пары
 * всегда возвращает `HTTP/1.1 403 Forbidden`, запуская реальный цикл сопряжения [AirPlayClient], утверждая:
 * 1. Установлен `pairingForbidden` (403 в разделе проверки/аутентификации также необходимо отметить — старая реализация распознает только
 * раздел парной настройки  , из-за которого основной элемент управления ошибочно выбирает два неправильных пути:
 * «отключение BT и повторная попытка» и «понижение версии с открытым текстом»);
 * 2. Ритм отсрочки: ≥4,5 с после 403 перед переходом к следующему раунду попыток (5 с отсрочка, см.
 * [pairingRetryBackoffMs]), больше невозможно сбросить машину состояния готовности машины атакой 500 мс;
 * 3. 2–4 раунда попыток в течение 12 секунд, ключ не генерируется (для 403 транспортных средств нет успешного пути).
 */
class ForbiddenAudiReplayTest {
    @Test
    fun `первая компания 403 окно - отметка forbidden и нажмите 5s Отступите и повторите попытку`() {
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val requestAtNs = ArrayList<Long>()
        val lock = Any()
        val acceptor =
            thread(isDaemon = true, name = "forbidden-audi") {
                while (!server.isClosed) {
                    val sock =
                        try {
                            server.accept()
                        } catch (_: Exception) {
                            return@thread
                        }
                    sock.use { s ->
                        s.tcpNoDelay = true
                        s.soTimeout = 4_000
                        val input = BufferedInputStream(s.getInputStream())
                        val out = s.getOutputStream()
                        try {
                            while (true) {
                                // Соединение закрыто: ждать следующего клиента.
                                val head = readHead(input) ?: break
                                val length =
                                    Regex("Content-Length: *(\\d+)", RegexOption.IGNORE_CASE)
                                        .find(head)
                                        ?.groupValues
                                        ?.get(1)
                                        ?.toIntOrNull() ?: 0
                                if (length > 0) skipFully(input, length)
                                synchronized(lock) { requestAtNs.add(System.nanoTime()) }
                                // CSeq должен быть отражен: клиент отправляет ответ в соответствии с CSeq. If it is missing, it will time
                                // out
                                // in
                                // 10s.

                                val cseq =
                                    Regex("CSeq: *(\\d+)", RegexOption.IGNORE_CASE)
                                        .find(head)
                                        ?.groupValues
                                        ?.get(1) ?: "1"
                                out.write(
                                    "HTTP/1.1 403 Forbidden\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n"
                                        .toByteArray(Charsets.ISO_8859_1),
                                )
                                out.flush()
                            }
                        } catch (_: Exception) {
                            // Клиент отключает новое соединение (ошибка 403 быстрого пути) и продолжает обслуживание.
                        }
                    }
                }
            }

        val endpoint =
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "Audi_MMI_3315_5GHz",
                passphrase = "",
                channel = 0,
                securityType = 0,
                airPlayPort = server.localPort,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf("127.0.0.1"),
            )
        val client = AirPlayClient(endpoint, log = {}, context = null)
        val keys =
            try {
                client.runPairing(windowMillis = 12_000)
            } finally {
                runCatching { server.close() }
                runCatching { acceptor.join(1_000) }
            }

        assertNull("403 Невозможно создать ключ в окне", keys)
        assertTrue("verify/Раздел аутентификации 403 Также необходимо установить pairingForbidden", client.pairingForbidden)
        assertTrue(client.attemptedPairing)

        val stamps =
            synchronized(lock) { requestAtNs.sorted() }.map { it / 1_000_000 }
        assertTrue("Слишком мало запросов（${stamps.size} раз）——Петля на самом деле не вращается", stamps.size >= 4)
        // Кластеризация раундов: настройка → проверка, интервал между двумя запросами в одном раунде составляет <3 с, а
        // интервал между раундами составляет ≥4,5 с (задержка 5 с).

        val cycles = ArrayList<Long>()
        for (i in stamps.indices) {
            if (i == 0 || stamps[i] - stamps[i - 1] >= 3_000) cycles.add(stamps[i])
        }
        assertTrue("раунды ${cycles.size}（Ожидание 2~4）", cycles.size in 2..4)
        val gaps = cycles.zipWithNext { a, b -> b - a }
        assertTrue("Недостаточный холостой ход между поворотами.（$gaps ms，Ожидание ≥4500）", gaps.all { it >= 4_500 })
    }

    /** Прочтите конец HTTP-заголовка (\r\n\r\n); вернуть ноль, когда соединение закрыто. */
    private fun readHead(input: InputStream): String? {
        val buf = StringBuilder()
        val chunk = ByteArray(1)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) return if (buf.isEmpty()) null else buf.toString()
            buf.append(chunk[0].toInt().toChar())
            if (buf.length >= 4 && buf.endsWith("\r\n\r\n")) return buf.toString()
            if (buf.length > 16_384) return buf.toString()
        }
    }

    private fun skipFully(
        input: InputStream,
        count: Int,
    ) {
        var left = count
        val buf = ByteArray(4096)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size))
            if (n < 0) return
            left -= n
        }
    }
}
