package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.iap2.MfiLengthMemory
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIB3 WiFi-туннельное подтверждение (сервер VirtualIap2Link × голый двигатель, действующий как автомобильный клиент).
 *
 * Время   — это реальное поведение машины, зафиксированное AADisplay с Frida: рука мобильного телефона (отправка
 * обнаружения один раз) → автомобиль, машина SYN →
 * Мобильный телефон   SYN|ACK → (ссылка NORMAL) мобильный телефон 0x1D00 → машина 0x1D01 → мобильный телефон 0x1D02+0xAA00 →
 * (0xAA06 → 0xAA00) → 0xAA01 → Мобильный телефон 0xAA02 Challenge **20B** → Мобильный телефон 0xAA03 → Мобильный телефон
 * 0xAA05 + пакет → подписка на транспортное средство (0x5000/0x4157/0x4154/0x4170) → соответствующий пустой ответ.
 */

/**
 * Разделение по заголовку кадра ссылки (FF5A + длина u16 + заголовок 9B): механизм может выводить несколько кадров одновременно.
 * Маркер   (6B, а не FF5A) пропущен.
 */
private fun splitLinkFrames(chunk: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var off = 0
    while (off + 9 <= chunk.size) {
        if (chunk[off] != 0xFF.toByte() || chunk[off + 1] != 0x5A.toByte()) {
            off += 1
            continue
        }
        // Поле длины = вся длина кадра (включая заголовок 9B), длина без полезной нагрузки.
        val frameLen = ((chunk[off + 2].toInt() and 0xFF) shl 8) or (chunk[off + 3].toInt() and 0xFF)
        // Структура кадра: заголовок 9B (включая проверку заголовка) + полезная нагрузка + проверка хвостовой части 1B
        // (len = вся длина кадра);

        // Кадр «голого» подтверждения/эха не имеет полезной нагрузки (весь кадр имеет размер ровно 9 байт) – вместо
        // прерывания он пропускается, кадры данных, следующие за тем же блоком, анализируются как обычно.

        if (frameLen < 9 || off + frameLen > chunk.size) break
        if (frameLen > 9) out.add(chunk.copyOfRange(off + 9, off + frameLen - 1))
        off += frameLen
    }
    return out
}

class VirtualIap2LinkTest {
    private class Harness {
        val controlSent = ArrayList<ByteArray>()
        val allControl = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()
        val car = Iap2LinkEngine()
        val link =
            VirtualIap2Link(
                log = { },
                sendEventIap = {
                    eventSent.add(it)
                    true
                },
                sendControlIap = {
                    controlSent.add(it)
                    allControl.add(it)
                },
                tunnelWriter = { null },
                // Отключить поток битов: детерминировано в рамках теста (без помех при повторной передаче)
                alive = { false },
                nowMillis = { clock },
            )
        var clock = 0L
        var now = 1L

        /** Автомобиль → Мобильный: подайте байты и очистите исходящую ссылку. */
        fun carToLink() {
            val out = car.takeOutput()
            if (out.isNotEmpty()) link.onCarBytes(out)
        }

        /** Мобильный телефон → автомобиль-машина: передайте исходящие байты канала управления обратно в двигатель автомобиля-машины. */
        fun linkToCar() {
            while (controlSent.isNotEmpty()) {
                val batch = controlSent.toList()
                controlSent.clear()
                batch.forEach { car.feed(it, now) }
            }
        }

        fun exchange() {
            carToLink()
            linkToCar()
            now += 600
            clock += 600
            // Таймер движения на стороне транспортного средства: после принятия таблицы одноранговых узлов в дополнение к
            // голому подтверждению используется механизм тайм-аута (реальная машина ведет себя таким же образом ——

            // После нашего SYN|ACK в журнале BT машина сразу возвращает ACK)
            car.advanceTime(now)
            linkToCar()
        }

        fun exchangeUntilNormal(rounds: Int = 8) {
            repeat(rounds) {
                exchange()
                if (car.state == Iap2LinkEngine.State.NORMAL) return
            }
        }

        /** Компьютер автомобиля отправляет CSM и возвращает последовательность номеров сообщений CSM, впоследствии отправленную мобильным телефоном по каналу управления. */
        fun carSends(
            messageId: Int,
            body: ByteArray = ByteArray(0),
        ): List<Int> {
            val before = controlFrames().size
            assertTrue(car.sendControl(Iap2Csm.encode(messageId, body), now))
            exchange()
            val ids = controlFrames().drop(before).map { it.second }
            return ids
        }

        /** Исходящий канал управления → (Разделение кадра канала) → Завершите байты кадра CSM номером сообщения.
         * Полезная нагрузка каждого кадра канала содержит ровно один CSM: Синтаксический анализатор должен создаваться
         * кадр за кадром — полезная нагрузка LSP SYN|ACK.
         */
        fun controlFrames(): List<Pair<ByteArray, Int>> {
            val out = ArrayList<Pair<ByteArray, Int>>()
            for (chunk in allControl) {
                splitLinkFrames(chunk).forEach { payload ->
                    Iap2Csm.Parser().offer(payload).forEach { frame ->
                        out.add(payload to frame.messageId)
                    }
                }
            }
            return out
        }

        /** Полные байты кадра CSM самого последнего указанного номера сообщения (используются для побайтового сравнения констант Frida). */
        fun lastRawFrameOf(messageId: Int): ByteArray? = controlFrames().lastOrNull { it.second == messageId }?.first

        /** Последовательность длины отправленного запроса 0xAA02 (длина параметра 0; если записей несколько, в порядке их появления). */
        fun challengeLengths(): List<Int> =
            controlFrames()
                .filter { it.second == 0xAA02 }
                .map { (raw, _) ->
                    Iap2Csm
                        .parseParams(raw.copyOfRange(6, raw.size))
                        .first()
                        .value.size
                }
    }

    @Test
    fun mib3ArmSendsExactlyOneDetectOnControlChannel() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.link.armMib3()
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.controlSent.map { it.toList() })
        assertTrue(h.eventSent.isEmpty())
    }

    @Test
    fun preArmMarkerGetsNoReply() {
        val h = Harness()
        h.link.begin()
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertTrue(h.controlSent.isEmpty())
        assertTrue(h.eventSent.isEmpty())
    }

    /**
     * Honda HFT (фактическое измерение 20 октября 2026 г.): Автомобиль неоднократно отправляет маркеры с частотой 1
     * Гц по каналу событий, указывая на то, что он застрял.
     * Состояние обнаружения, ожидание **нашей** таблицы сеансов. Начиная со второго маркера, его необходимо повысить
     * до «маркер + эти часы».
     * Пакет синхронизации      ", и **может идти только через канал управления** - исходящая запись не читается по
     * каналу событий (подводный камень, на который наступил r6).
     *
     * 23 октября 2026 г. Второе определение: это поведение **все поколения** (больше не назначается EVENT_LINK) - Audi
     * также требует, чтобы канал был НОРМАЛЬНЫМ, иначе он будет разорван примерно через 10 секунд после ЗАПИСИ
     * (доказательство двойного журнала 0.1.9/0.1.17).
     * Настоящая причина «переговоров о приостановке использования Audi UI» заключалась в том, что modeChanged не был
     * повторен после завершения переговоров.
     */
    @Test
    fun repeatedMarkersUpgradeToControlChannelHandshake() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.EVENT_LINK)
        h.link.armMib3()
        val detect = h.controlSent.size
        // № 1: Еще не определено (MIB3 отправит еще одну повторную передачу обнаружения)
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals("первый marker Никакие дополнительные байты не должны отправляться", detect, h.controlSent.size)
        // № 2: Обновление до пакета маркер + синхронизация
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        val sent = h.controlSent.drop(detect)
        assertTrue("Повторить marker Байт согласования должен быть повторно выдан после", sent.isNotEmpty())
        val payload = sent.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        assertTrue(
            "Первая партия переизданий должна содержать marker（FF 55）",
            payload.size >= 6 && payload[0] == 0xFF.toByte() && payload[1] == 0x55.toByte(),
        )
        assertTrue(
            "marker Необходимо внимательно следить за FF5A таблица сеансов（Это то, чего ждут машина и машина.）",
            payload.size >= 12 && payload[6] == 0xFF.toByte() && payload[7] == 0x5A.toByte(),
        )
        assertTrue(
            "Стол для сеансов необходимо принести SYN Бит（0x80）",
            (payload[10].toInt() and 0x80) != 0,
        )
        assertTrue("Исходящие сообщения не должны попадать в канал событий.", h.eventSent.isEmpty())
    }

    /**
     * 23 октября 2026 г. Вторичное решение: **Все поколения должны ответить на маркер, чтобы завершить согласование канала**. Автомобиль
     * Маркер       бесконечно повторяет передачу с частотой 1 Гц (журнал владельца 0.1.17: намного превышает «2 ~ 5
     * пауз после» старого комментария),
     * Если       не отвечает, канал никогда не достигнет NORMAL. После ЗАПИСИ вся сессия будет снесена транспортным
     * средством примерно через 10 секунд (фактический тест 0.1.9).
     * 10,17 с, 0,1,17 фактическое измерение 10,18 с). Настоящая причина «переговоров о приостановке использования
     * Audi UI» тогда заключалась в том, что после завершения переговоров не хватало
     * Режимы      Changed Reclaim (SessionController.scheduleModesReclaim).
     */
    @Test
    fun markersEscalateRegardlessOfGeneration() {
        for (generation in ReceiverProfile.LinkGeneration.entries) {
            val h = Harness()
            h.link.setGeneration(generation)
            h.link.armMib3()
            val detect = h.controlSent.size
            // №1: Еще не определено (автомобиль обнаружит и отправит повторно)
            h.link.onCarBytes(Iap2LinkEngine.MARKER)
            assertEquals("$generation первый marker Никакие дополнительные байты не должны отправляться", detect, h.controlSent.size)
            // № 2: Переход на маркер + таблицу сессий
            h.link.onCarBytes(Iap2LinkEngine.MARKER)
            assertTrue(
                "$generation Нет. 2 а marker Байт согласования должен быть активирован（0.1.17 Ложь「игнорировать」маршрут）",
                h.controlSent.size > detect,
            )
            assertTrue("$generation Исходящие сообщения не должны попадать в канал событий.", h.eventSent.isEmpty())
        }
    }

    @Test
    fun fullMib3HandshakeWithBurstAndSubscriptions() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        assertEquals(Iap2LinkEngine.State.NORMAL, h.car.state)
        // обнаружение(1) + SYN|ACK + 0x1D00 прошло через канал управления; машина получила 0x1D00
        assertTrue(h.controlFrames().map { it.second }.contains(0x1D00))

        // 0x1D01 → 0x1D02 + 0xAA00
        assertEquals(
            listOf(0x1D02, 0xAA00),
            h.carSends(0x1D01, Iap2Csm.string(0, "Audi_MMI_3315") + Iap2Csm.string(1, "AUDI 5163")),
        )

        // 0xAA06 (серийный номер аутентификации) → повторно отправить 0xAA00
        assertEquals(listOf(0xAA00), h.carSends(0xAA06, Iap2Csm.string(0, "serial")))

        // 0xAA01 (сертификат) → 0xAA02 вызов 20B (реальная длина машины MIB3; форма DiPlay — 32B)
        val challengeFrames = h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(0xAA02), challengeFrames)
        val challengeBody =
            h
                .lastRawFrameOf(0xAA02)!!
                .copyOfRange(6, h.lastRawFrameOf(0xAA02)!!.size)
                .let { Iap2Csm.parseParams(it).first() }
        assertEquals(20, challengeBody.value.size)

        // 0xAA03 (подписной ответ) → 0xAA05 + пакет (0x4E0C/0xA100/0x4E0A/0x4E0B/0x4E09/0xFFFA/0xFFF0)
        assertEquals(
            listOf(0xAA05, 0x4E0C, 0xA100, 0x4E0A, 0x4E0B, 0x4E09, 0xFFFA, 0xFFF0),
            h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96))),
        )

        // Ответ подписки: 0x5000 → 0x5001 пусто; 0x4157 → 0x4158; 0x4154 → 0x4155; 0x4170 → 0x4171
        assertEquals(listOf(0x5001), h.carSends(0x5000))
        assertEquals(listOf(0x4158), h.carSends(0x4157))
        assertEquals(listOf(0x4155), h.carSends(0x4154))
        assertEquals(listOf(0x4171), h.carSends(0x4170))
    }

    @Test
    fun nowPlayingIdleReplyIsTheFridaConstantVerbatim() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "x"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        h.carSends(0x5000)
        // Байт за байтом равен константе захвата пакетов Frida — расположение полей существует только в реальном
        // поведении машины, и его легко перекодировать, изменив форму.

        assertEquals(
            VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE.toList(),
            h.lastRawFrameOf(0x5001)!!.toList(),
        )
        assertEquals(
            VirtualIap2Link.MIB3_COMMUNICATIONS_UPDATE_IDLE.toList(),
            run {
                h.carSends(0x4157)
                h.lastRawFrameOf(0x4158)!!.toList()
            },
        )
    }

    @Test
    fun tunnelMfiUsesRememberedLengthPerCar() {
        // Идентификация отличается от других тестов, чтобы избежать перекрестных помех в памяти процесса.
        val identity = "MemCar MemModel MemMfr tunnel"
        MfiLengthMemory.recordSuccess(null, identity, 32)
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "MemCar") + Iap2Csm.string(1, "MemModel") + Iap2Csm.string(2, "MemMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        // Удар по памяти: 32B сразу занимает первое место, больше не пытается с 20B
        assertEquals(listOf(32), h.challengeLengths())
    }

    @Test
    fun silentChallengeRetriesNextLengthWithinSession() {
        val h = Harness()
        h.link.setGeneration(ReceiverProfile.LinkGeneration.CLASSIC_MIB3)
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "SilCar") + Iap2Csm.string(1, "SilMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(20), h.challengeLengths())
        // 20B Бесшумно: переведите часы за пределы окна 6 с, подайте маркер, чтобы запустить проверку ритма (drainLocked)
        h.clock += 7_000
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(listOf(20, 32), h.challengeLengths())
        // 32B получил ответ → 0xAA05 + взрыв как обычно
        val after = h.controlFrames().size
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        assertTrue(
            "frames=${h.controlFrames().drop(after).map { it.second }}",
            h
                .controlFrames()
                .drop(after)
                .map { it.second }
                .containsAll(listOf(0xAA05, 0x4E0C, 0xFFFA)),
        )
    }

    /**
     * 0x6802 Получить параметры: Honda HFT, измеренная рама `00 06 00 00 00 03 | 00 05 00 01 04`.
     *
     * Параметр 0       всегда равен `00 03` (тип отчета), параметр 1 — это HID-сообщение (0x04 = следующая песня).
     * Старая реализация       принимает «первый короткий параметр» → всегда получает 00 03 → растровое изображение
     * полностью равно 0 → при нажатии кнопки никаких действий не происходит.
     */
    @Test
    fun hidReportComesFromParamOneNotParamZero() {
        // Однострочное написание: для этого правила ktlint требует «один параметр на строку для нескольких строк».
        // Однострочное письмо ближе к читаемой форме исходного кадра.

        val body = byteArrayOf(0x00, 0x06, 0x00, 0x00, 0x00, 0x03, 0x00, 0x05, 0x00, 0x01, 0x04)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x04), report?.toList())
    }

    @Test
    fun hidReportFallsBackToLastShortParamWhenOnlyOneGiven() {
        val body = byteArrayOf(0x00, 0x05, 0x00, 0x00, 0x08)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x08), report?.toList())
    }
}
