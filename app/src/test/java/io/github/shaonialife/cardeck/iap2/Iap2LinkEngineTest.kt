package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Два механизма связи выполняют согласование и передачу данных путем прямого обмена выходными байтами (без потоков, без ввода-вывода). */
class Iap2LinkEngineTest {
    @Test
    fun handshakeReachesNormalOnBothSides() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()

        phone.start(0)
        accessory.start(0)

        exchange(phone, accessory)
        exchange(phone, accessory)
        exchange(phone, accessory)

        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertEquals(Iap2LinkEngine.State.NORMAL, accessory.state)
        assertEquals(4, accessory.peerSynchronization?.maxOutgoing)
        assertTrue(phone.peerSynchronization?.sessions?.any { it.id == Iap2LinkEngine.SESSION_CONTROL } == true)
    }

    /**
     * Форма канала событий : автомобиль повторно отправляет маркеры и не отправляет ни одного пакета синхронизации
     * (фактическое измерение Honda HFT 2026-10-20).
     * 60 маркеров за 60 секунд), что указывает на то, что он завис в состоянии обнаружения и ожидает **нашей** таблицы сеансов.
     * Когда       уже находится в режиме ПЕРЕГОВОРОВ, [Iap2LinkEngine.initiate] все еще должен иметь возможность
     * отправлять «маркер + FF5A SYN».
     * Обе стороны сразу сошлись на NORMAL — это вход на путь к клавишам вырезания песни.
     */
    @Test
    fun initiateFromNegotiatingSendsMarkerAndSynThenReachesNormal() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        // Обе стороны молча находятся в режиме ожидания (только отправляют маркеры, но не начинают переговоры)
        phone.enterNegotiating()
        accessory.enterNegotiating()

        phone.initiate(0)
        val out = phone.takeOutput()
        assertArrayEquals("Первый кадр должен быть marker", Iap2LinkEngine.MARKER, out.copyOfRange(0, 6))
        val frames = parseFrames(out)
        assertEquals("marker За ним должен следовать пакет синхронизации", 1, frames.size)
        assertTrue("Пакет синхронизации control Должен принести SYN Бит（0x80）", frames[0].control and 0x80 != 0)

        // Сторона автомобиля получает «маркер + эти часы SYN» → возвращает SYN|ACK (это прогресс, который должен отображаться в журнале)
        accessory.feed(out, tick())
        assertEquals(
            "Сторона автомобиля и машины должна войти после получения нашей формы сеанса. NORMAL",
            Iap2LinkEngine.State.NORMAL,
            accessory.state,
        )
        phone.feed(accessory.takeOutput(), tick())
        assertEquals("фактический phone=${phone.state}", Iap2LinkEngine.State.NORMAL, phone.state)
        assertTrue(
            "Обе стороны должны принять список разговоров другой стороны.",
            phone.peerSynchronization != null && accessory.peerSynchronization != null,
        )
    }

    /**
     * должен иметь возможность остановиться после окончания раундов: неограниченное количество точек длительностью
     * 500 мс на канале управления сбивает с толку стек CarPlay автомобиля.
     * Таким образом, верхний уровень повторно открывается в соответствии с раундом, а
     * [Iap2LinkEngine.stopNegotiationRetries] корректируется после того, как раунд исчерпан.
     * После остановки       он не должен выдавать никаких исходящих байтов, и статус остается ПЕРЕГОВОРЫ (ожидание,
     * пока поезд сам отправит CSM).
     */
    @Test
    fun stopNegotiationRetriesSilencesResends() {
        val phone = Iap2LinkEngine()
        phone.enterNegotiating()
        phone.initiate(0)
        phone.takeOutput()

        phone.advanceTime(1_000L)
        assertTrue(
            "Прежде чем остановиться, вам все равно придется отправить сообщение повторно. marker/Пакет синхронизации",
            phone.takeOutput().isNotEmpty(),
        )

        phone.stopNegotiationRetries()
        phone.advanceTime(60_000L)
        assertTrue("После остановки не должно быть больше исходящих байтов.", phone.takeOutput().isEmpty())
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
    }

    @Test
    fun controlPayloadRoundTripBothDirections() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val phoneToAccessory = Iap2Csm.encode(0x1D00)
        assertTrue(phone.sendControl(phoneToAccessory, 1))
        exchange(phone, accessory)
        val receivedAtAccessory = pollControls(accessory)
        assertEquals(1, receivedAtAccessory.size)
        assertArrayEquals(phoneToAccessory, receivedAtAccessory[0])

        val accessoryToPhone = Iap2Csm.encode(0x1D01, Iap2Csm.string(0, "CarPlay HU"))
        assertTrue(accessory.sendControl(accessoryToPhone, 2))
        exchange(phone, accessory)
        val receivedAtPhone = pollControls(phone)
        assertEquals(1, receivedAtPhone.size)
        assertArrayEquals(accessoryToPhone, receivedAtPhone[0])
    }

    @Test
    fun duplicateFrameIsReAcknowledgedNotRedelivered() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val payload = Iap2Csm.encode(0x1D02)
        assertTrue(phone.sendControl(payload, 1))
        // Перехватите вывод кадра данных с телефона и дважды передайте его на аксессуар.
        val dataFrame = phone.takeOutput()
        accessory.feed(dataFrame, 2)
        accessory.feed(dataFrame, 3)

        assertEquals(1, pollControls(accessory).size)
    }

    @Test
    fun windowBacklogFlushesAfterAcknowledgement() {
        val phone = Iap2LinkEngine(maxOutgoing = 2)
        val accessory = Iap2LinkEngine(maxOutgoing = 8)
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val frames = (0 until 5).map { Iap2Csm.encode(0x5000 + it) }
        frames.forEach { assertTrue(phone.sendControl(it, 10)) }
        // Окно 2: немедленно отправляются только первые 2 кадра, а остальные задерживаются; ACK сбрасывается с помощью
        // handleAcknowledgement

        var now = 11L
        while (pollControls(accessory).size < 5) {
            exchange(phone, accessory)
            now += 1
            phone.advanceTime(now)
            accessory.advanceTime(now)
            assertTrue(!phone.isDead && !accessory.isDead)
        }
    }

    // ---- Возврат деталей переговоров ----

    /**
     * SYN-ACK должен использовать **новый** порядковый номер отправки (партнер проверяет `SYN.seq + 1`).
     *
     * 19 октября 2026 г. Журнал реального автомобиля BMW 87866: наши SYN и SYN-ACK имеют seq=99, и автомобиль и
     * машина считают это дубликатом кадра.
     * был отброшен, время ожидания всех 14 раундов согласования истекло, а 133 SYN не были обменены на ACK. кошачья
     * игра использует `take_own_seq()`
     * использует новые серийные номера, и это поведение здесь заблокировано.
     */
    @Test
    fun synAckUsesFreshSequenceNumber() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput() // маркер + наш SYN (seq=99)

        phone.feed(synFrame(peerSequence = 161), 1)
        val frames = parseFrames(phone.takeOutput())
        val synAck = frames.single { it.control == 0xC0 }

        assertEquals("ack Должен повторить сверстника SYN серийный номер", 161, synAck.ack)
        assertEquals("SYN-ACK Серийный номер должен быть больше SYN Большой 1", 100, synAck.sequence)
    }

    /**
     * В течение периода переговоров SYN|ACK должен быть возвращен для **каждого** SYN.
     *
     * BMW возобновляет переговоры с новым серийным номером в каждом раунде повторных попыток; старая реализация
     * отображает эхо только при первом принятии и возвращает только голый ACK в остальных случаях.
     * Автомобиль-машина никогда не завершит переговоры. catplay `on_recv_neg_host` отправляет SYN+ACK для каждого SYN.
     */
    @Test
    fun everySynGetsASynAckReply() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        // Автомобиль и машина возобновили переговоры с разными серийными номерами в течение трех последовательных
        // раундов (журнал воспроизведения 161 → 191 → 40)

        val sequences = listOf(161, 191, 40)
        val replies = ArrayList<Pair<Int, Int>>() // (synAckSeq, подтверждение)
        sequences.forEachIndexed { index, peerSeq ->
            phone.feed(synFrame(peerSequence = peerSeq), (index + 1).toLong())
            parseFrames(phone.takeOutput())
                .filter { it.control == 0xC0 }
                .forEach { replies += it.sequence to it.ack }
        }

        assertEquals("каждый SYN У всех есть SYN|ACK", sequences.size, replies.size)
        assertEquals(sequences, replies.map { it.second })
        // Серийный номер увеличивается постепенно (99 → 100 → 101 → 102).
        assertEquals(listOf(100, 101, 102), replies.map { it.first })
    }

    /** Повторные передачи одного и того же SYN на уровне байтов должны быть идемпотентными: вся пара кадров воспроизводится без изменений, без каких-либо дополнительных порядковых номеров или создания новых порядковых номеров. */
    @Test
    fun retransmittedSynKeepsTheSameEcho() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        phone.feed(synFrame(peerSequence = 77), 1)
        val first = phone.takeOutput()
        phone.feed(synFrame(peerSequence = 77), 2)
        val again = phone.takeOutput()

        assertArrayEquals("Повторно передать то же самое SYN надо переиграть как есть", first, again)
        assertEquals(100, parseFrames(first).single { it.control == 0xC0 }.sequence)
    }

    /** «Голый» ACK не может конфликтовать с порядковым номером отправленного SYN|ACK (BMW будет отбрасывать кадры с тем же порядковым номером как дубликаты). */
    @Test
    fun ackSequenceNeverDuplicatesTheSynAck() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        val emitted = ArrayList<Int>()
        listOf(161, 191, 40).forEachIndexed { index, peerSeq ->
            phone.feed(synFrame(peerSequence = peerSeq), (index + 1).toLong())
            emitted += parseFrames(phone.takeOutput()).map { it.sequence }
        }
        assertEquals("Серийные номера должны быть строго возрастающими и не иметь дубликатов.", emitted.size, emitted.distinct().size)
        assertEquals(emitted.sorted(), emitted)
    }

    // ---- Инструменты ----

    private class ParsedFrame(
        val control: Int,
        val sequence: Int,
        val ack: Int,
        val frameLength: Int,
    )

    /** Разобрать все кадры FF5A в сегменте вывода (пропуская ведущие маркеры). */
    private fun parseFrames(bytes: ByteArray): List<ParsedFrame> {
        val frames = ArrayList<ParsedFrame>()
        var offset = 0
        while (offset + 9 <= bytes.size) {
            if (bytes[offset] != 0xFF.toByte() || bytes[offset + 1] != 0x5A.toByte()) {
                offset++
                continue
            }
            val frame = parseFrame(bytes, offset)
            frames += frame
            offset += frame.frameLength
        }
        return frames
    }

    /** Разобрать кадр FF5A (длина включает 9-байтовый заголовок + полезную нагрузку + контрольную сумму полезной нагрузки). */
    private fun parseFrame(
        bytes: ByteArray,
        offset: Int,
    ): ParsedFrame {
        require(bytes[offset] == 0xFF.toByte() && bytes[offset + 1] == 0x5A.toByte()) {
            "offset=$offset Нет FF5A Заголовок кадра"
        }
        val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        return ParsedFrame(
            control = bytes[offset + 4].toInt() and 0xFF,
            sequence = bytes[offset + 5].toInt() and 0xFF,
            ack = bytes[offset + 6].toInt() and 0xFF,
            frameLength = length,
        )
    }

    /**
     * создает кадр SYN (включая маркер) на стороне транспортного средства.
     *
     * Заголовок кадра       имеет длину **9 байт**: `FF5A | u16 длина | u8 контроль | u8 seq | u8 ack | u8 сессия |
     * u8 контрольная сумма заголовка`,
     * Таким образом, последовательность находится по смещению 5 внутри кадра, а контрольная сумма заголовка
     * находится по смещению 8 (а не 7 - этот бит является идентификатором сеанса).
     */
    private fun synFrame(peerSequence: Int): ByteArray {
        val engine = Iap2LinkEngine()
        engine.start(0)
        val raw = engine.takeOutput()
        val synOffset = 6 // Префиксный маркер 6 байт
        raw[synOffset + 5] = peerSequence.toByte()
        // Контрольная сумма заголовка = дополнение суммы первых 8 байтов.
        var sum = 0
        for (index in synOffset until synOffset + 8) sum += raw[index].toInt() and 0xFF
        raw[synOffset + 8] = (-sum and 0xFF).toByte()
        return raw
    }

    private fun exchange(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        b.feed(a.takeOutput(), tick())
        a.feed(b.takeOutput(), tick())
    }

    private fun exchangeUntilNormal(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        var guard = 0
        while ((a.state != Iap2LinkEngine.State.NORMAL || b.state != Iap2LinkEngine.State.NORMAL) &&
            guard++ < 10
        ) {
            exchange(a, b)
        }
    }

    private fun pollControls(engine: Iap2LinkEngine): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            when (val event = engine.pollEvent() ?: return out) {
                is Iap2LinkEngine.Event.Control -> out += event.bytes
                is Iap2LinkEngine.Event.Dead -> error("engine died: ${event.reason}")
            }
        }
    }

    private var clock = 0L

    private fun tick(): Long = ++clock
}
