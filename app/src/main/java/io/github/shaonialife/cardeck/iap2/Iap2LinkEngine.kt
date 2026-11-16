package io.github.shaonialife.cardeck.iap2

import java.io.ByteArrayOutputStream

/** Окно отправки объявляется, когда мобильный телефон отображает SYN|ACK (настоящий iPhone/AADisplay имеет то же значение, что и 0x7F). */
private const val PHONE_DECLARED_MAX_OUTGOING = 0x7F

/**
 * Механизм связи iAP2 (реализация отправителя; протокол канального уровня симметричен, эта реализация работает как «инициатор»).
 *
 * рукопожатие (беспроводной режим, инициатором является мобильный телефон — openWireless от DiPlay/xcertplay для
 * ignoreNegotiation=ложное ожидание инициации мобильного телефона):
 * 1. start() отправляет маркер `FF 55 02 00 EE 10`, а затем отправляет пакет синхронизации (кадр FF5A,
 * control=0x80, полезная нагрузка = SynchronizationPayload)
 * 2. После получения маркера партнер вступает в переговоры и отправляет собственный пакет синхронизации; обе
 * стороны ответят ACK после получения пакета синхронизации.
 * 3. После получения ACK в состоянии согласования он переходит в НОРМАЛЬНЫЙ (доступен сеанс управления 10)
 *
 * Кадр данных  : control=0x40 (с ACK), seq=порядковый номер отправки (зацикливание u8, начальное 99),
 * ack=Последний серийный номер, полученный в последовательности, sessionId=10. Маркер однорангового узла
 * естественным образом будет отброшен при сканировании заголовка кадра.
 *
 * Семантика протокола   относится к ссылке/xcertplay Transport/Iap2LinkEngine.kt (переписанный независимо).
 * упрощенный элемент (RFCOMM — надежный упорядоченный поток): отсутствие буферизации вне очереди и активный EAK,
 * сохранение обнаружения повторяющихся кадров,
 * Окно передачи   и время повторной передачи.
 */
class Iap2LinkEngine(
    private val maxOutgoing: Int = 4,
    private val retransmissionTimeoutMillis: Long = 4_000L,
    private val maxRetransmissions: Int = 4,
) {
    enum class State { IDLE, NEGOTIATING, NORMAL, DEAD }

    data class SessionDescriptor(
        val id: Int,
        val kind: Int,
        val version: Int,
    )

    data class SynchronizationPayload(
        val maxOutgoing: Int,
        val maxLength: Int,
        val retransmissionTimeoutMillis: Int,
        val acknowledgementTimeoutMillis: Int,
        val maxRetransmissions: Int,
        val maxAcknowledgements: Int,
        val sessions: List<SessionDescriptor>,
    ) {
        fun encode(): ByteArray {
            val out = ByteArrayOutputStream(10 + sessions.size * 3)
            out.write(SYNCHRONIZATION_VERSION)
            out.write(maxOutgoing)
            writeU16(out, maxLength)
            writeU16(out, retransmissionTimeoutMillis)
            writeU16(out, acknowledgementTimeoutMillis)
            out.write(maxRetransmissions)
            out.write(maxAcknowledgements)
            sessions.forEach {
                out.write(it.id)
                out.write(it.kind)
                out.write(it.version)
            }
            return out.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): SynchronizationPayload? {
                if (bytes.size < 10 || u8(bytes[0]) != SYNCHRONIZATION_VERSION) return null
                val sessions = ArrayList<SessionDescriptor>()
                var offset = 10
                while (offset + 3 <= bytes.size) {
                    sessions +=
                        SessionDescriptor(
                            u8(bytes[offset]),
                            u8(bytes[offset + 1]),
                            u8(bytes[offset + 2]),
                        )
                    offset += 3
                }
                return SynchronizationPayload(
                    maxOutgoing = u8(bytes[1]),
                    maxLength = readU16(bytes, 2),
                    retransmissionTimeoutMillis = readU16(bytes, 4),
                    acknowledgementTimeoutMillis = readU16(bytes, 6),
                    maxRetransmissions = u8(bytes[8]),
                    maxAcknowledgements = u8(bytes[9]),
                    sessions = sessions,
                )
            }
        }
    }

    sealed class Event {
        class Control(
            val bytes: ByteArray,
        ) : Event()

        class Dead(
            val reason: String?,
        ) : Event()
    }

    private class Packet(
        var sequence: Int,
        val payload: ByteArray,
        var retransmissions: Int = 0,
        var deadlineMillis: Long = 0,
        val awaitAck: Boolean = true,
    )

    var state: State = State.IDLE
        private set
    var peerSynchronization: SynchronizationPayload? = null
        private set

    private var sentSequence = INITIAL_SEQUENCE
    private var lastSentAcknowledged: Int? = null
    private var lastReceivedInOrder = 0

    /**
     * Принятый идентификатор сеанса однорангового управления (определяется при получении полезных данных одноранговой синхронизации).
     * Фактический тест на уровне байтов реального автомобиля Audi (09 октября 2026 г.): машина автомобиля (MIB3)
     * отправляет SYN в качестве инициатора, оператор
     * Таблица сеансов       {1:control, 2:EA}. После того, как телефон повторяет свою таблицу, он взаимодействует как **Сеанс № 1** ——
     * CSM, отправленный 10-го числа, будет автоматически отброшен (канальный уровень подтвердит как обычно, что очень сбивает с толку).
     * Тестовый стенд       DiPlay/xcertplay, заявление 10, после принятия оно будет соответствовать значению по
     * умолчанию, указанному в этой таблице, и поведение не изменится.
     */
    private var adoptedPeerControlSessionId: Int? = null

    /** Принята полезная нагрузка одноранговой синхронизации (для эхо-сигнала/диагностики переговоров). */
    private var adoptedSynchronization: SynchronizationPayload? = null

    /**
     * Режим согласования туннеля  (тип 130). Механизм туннельной связи DiPlay (Iap2LinkEngine.openTunnel,
     * ignoreNegotiation=true) имеет два жестких ожидания (исходный код сравнения на уровне байтов 2026-08):
     * 1. При получении «ACK, но еще не получении SYN от партнера», сразу определяется, что канал не работает
     * (умирает) — поэтому эхо должно быть **before**
     * отправляется голый ACK (эхо-кадр control=SYN|ACK имеет свой собственный бит подтверждения, и согласование
     * между узлами может быть завершено в одном кадре);
     * 2. Его туннель находится в режиме нулевого подтверждения (maxRetransmissions=0/maxAcknowledgements=0) и не отвечает.
     * Любой ACK — ожидание завершения согласования «одноранговым ACK» приведет к взаимоблокировке, и эхо-сигнал считается завершенным.
     * По умолчанию установлено значение false: семантика пути Bluetooth (RFCOMM/канал событий) остается неизменной
     * (Audi требует ACK+echo и т. д. для окончательного ACK).
     */
    var echoFirst: Boolean = false

    /** Диагностика: обратный вызов, когда таблица сеансов однорангового узла принимается впервые (распечатайте таблицу сеансов, предложенную одноранговым узлом, чтобы облегчить устранение неполадок в реальном автомобиле). */
    var onPeerSynchronizationAdopted: ((SynchronizationPayload) -> Unit)? = null

    /** Диагностика: обратный вызов ((sessionId, байты)) при удалении кадров данных с неизвестным идентификатором сеанса. */
    var onUnknownSessionData: ((Int, Int) -> Unit)? = null

    /**
     * Обратный вызов кадра данных сеанса советника (№ 2): после настройки кадры советника больше не будут
     * отбрасываться как неизвестные сеансы.
     * Вместо этого       доставляет этот обратный вызов (коснитесь несущей HID на виртуальном канале канала событий).
     */
    var onEaData: ((bytes: ByteArray) -> Unit)? = null

    /** Диагностика: сводная информация о каждом кадре входящего соединения (управление/последовательность/подтверждение/идентификатор сеанса/длина полезной нагрузки), используемая для устранения неполадок в реальном автомобиле. */
    var onFrameReceived: ((control: Int, sequence: Int, ack: Int, sessionId: Int, size: Int) -> Unit)? = null

    /** Диагностика: сводка каждого кадра исходящего канала (только кадры канального уровня, используемые для сравнения с реальным временем согласования транспортного средства). */
    var onFrameSent: ((control: Int, sequence: Int, ack: Int, sessionId: Int, size: Int) -> Unit)? = null

    private var unknownSessionDropped = 0

    private val unacknowledged = ArrayList<Packet>()
    private val sendBacklog = ArrayDeque<Packet>()
    private val pendingEvents = ArrayDeque<Event>()
    private val output = ByteArrayOutputStream()
    private val receive = ReceiveBuffer()

    private var markerDeadline: Long? = null
    private var syncDeadline: Long? = null
    private var retransmitDeadline: Long? = null

    /** Порядковый номер SYN противоположного конца и наш порядковый номер, когда SYN был последний раз отображен (воспроизводится в том виде, в каком он есть при повторной передаче того же SYN). */
    private var lastEchoPeerSequence: Int? = null
    private var lastEchoOwnSequence = 0

    val isDead: Boolean get() = state == State.DEAD

    /** Инициировал ли этот механизм SYN (start=инициатор; enterNegotiating/startResponder=ответчик). */
    private var originatedSyn = false

    /**
     * **Окно отправки, объявленное этой стороной** (семантика LSP maxOutgoing = собственный верхний предел
     * неподтвержденных пакетов декларирующей стороны):
     * Окно отправки считается этим, а не значением, объявленным партнером - при эхо-сообщении мы объявляем 0x7F (ownSyncOf),
     * Выбор окон на основе значения однорангового узла приведет к тому, что пакетные сообщения застрянут в маленьком
     * окне однорангового узла (пакетные сообщения MIB3 8 × одноранговый узел 4).
     */
    private var declaredMaxOutgoing = maxOutgoing

    /** Введите согласование: отправьте маркер + пакет синхронизации (конец отправки = инициатор, не ждите противоположного маркера окончания). */
    fun start(nowMillis: Long) {
        if (state != State.IDLE) return
        originatedSyn = true
        state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        sendSynchronization()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
    }

    /**
     * Начинается согласование перевыпуска : маркер + пакет синхронизации этой таблицы (позволяет снова запустить
     * запуск из [State.NEGOTIATING]).
     *
     * используется в состоянии, когда «одноранговый узел отправляет только маркеры и не отправляет пакеты
     * синхронизации» — он ожидает нашу таблицу сеансов.
     * Honda HFT (Alpine 8A250-30A) 20.10.2026 Фактическое измерение канала событий: автомобиль и двигатель отправили
     * сигнал через 60 секунд.
     * 60 маркеров `FF 55 02 00 EE 10`, ни одного пакета синхронизации FF5A (что указывает на то, что он застрял в состоянии обнаружения),
     * Но раньше мы отвечали только на маркер, поэтому машина и машина продолжали ретранслировать передачу, и связь
     * так и не удалось установить.
     * сравнивает соединение Bluetooth iAP2 одного и того же автомобиля и машины: первый кадр мобильного телефона -
     * это точно «маркер + FF5A SYN», а автомобиль и машина
     * вернется к своей таблице сеансов через 50 мс — для продвижения выполните ту же комбинацию на канале событий.
     */
    fun initiate(nowMillis: Long) {
        if (state == State.DEAD) return
        originatedSyn = true
        if (state == State.IDLE) state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        sendSynchronization()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
    }

    /**
     * останавливает автоматическую повторную передачу (пакет маркера/синхронизации) в течение периода согласования,
     * и статус остается неизменным.
     *
     * используется в сценарии, когда «одноранговый узел никогда не отвечает на пакет синхронизации»: как только в
     * канале управления возникает бесконечный тайм-аут в 500 мс, одноранговый узел
     * Стек       CarPlay был сбит с толку (проблема, измеренная в канале событий 11 октября 2026 г.), поэтому
     * верхний уровень повторно открывал его по раундам.
     * Явно остановиться после того, как       исчерпан.
     */
    fun stopNegotiationRetries() {
        markerDeadline = null
        syncDeadline = null
    }

    /**
     * Режим ответчика: отправлять только маркер, **не отправлять SYN** — дождаться, пока партнер (транспортное
     * средство) сам отправит SYN, а затем ACK+эхо.
     * Фактическое испытание виртуального канала канала событий Audi (11 октября 2026 г.): после того, как двигатель
     * автомобиля отправляет маркер, он инициирует SYN;
     * Если мы активно отправляем SYN (и усиливаем его повторной отправкой длительностью 500 мс), стек CarPlay автомобиля будет перепутан——
     * показывает, что канал событий больше не отвечает, и даже подтверждение соединения Bluetooth прекращается.
     */
    fun startResponder(nowMillis: Long) {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        // syncDeadline остается нулевым: не отправлять SYN активно, не отправлять SYN повторно.
    }

    /**
     * молча вступает в переговоры (семантика DETECT, сравните с ролью сервера LinkLayer):
     * не отправляет никаких байтов - партнер (клиент) повторно отправляет DETECT каждые 200 мс, мы получаем и отвечаем один раз**,
     * никогда не будет активно повторно отправлять (повторная отправка DETECT сервером вернет конечный автомат клиента в режим обнаружения.
     * SYN никогда не выйдет - фактическое измерение канала событий 2026-10-11).
     */
    fun enterNegotiating() {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
    }

    fun feed(
        bytes: ByteArray,
        nowMillis: Long,
    ) {
        if (state == State.DEAD) return
        var offset = 0
        while (offset < bytes.size && state != State.DEAD) {
            val room = receive.remainingCapacity()
            if (room == 0) {
                die("inbound frame exceeds limit")
                return
            }
            val count = minOf(room, bytes.size - offset)
            receive.append(bytes, offset, count)
            offset += count
            while (parseAvailable(nowMillis) && state != State.DEAD) {
                // Слейте все полные кадры
            }
        }
    }

    fun feedEof() = die(null)

    fun takeOutput(): ByteArray {
        val bytes = output.toByteArray()
        output.reset()
        return bytes
    }

    fun pollEvent(): Event? = if (pendingEvents.isEmpty()) null else pendingEvents.removeFirst()

    fun nextDeadlineMillis(): Long? = listOfNotNull(markerDeadline, syncDeadline, retransmitDeadline).minOrNull()

    fun advanceTime(nowMillis: Long) {
        if (state == State.DEAD) return
        if (markerDeadline != null && markerDeadline!! <= nowMillis) {
            markerDeadline = null
            if (state == State.NEGOTIATING && appendOutput(MARKER)) {
                markerDeadline = nowMillis + MARKER_RESEND_MILLIS
            }
        }
        if (syncDeadline != null && syncDeadline!! <= nowMillis) {
            syncDeadline = null
            if (state == State.NEGOTIATING) {
                // Была принята таблица одноранговых узлов: отправляется только голый ACK (эхо отправляется только один раз в
                // первый раз, чтобы избежать расширения раунда переговоров);

                // Не принято: продолжить повторную отправку этой таблицы SYN (время испытательного стенда)
                if (adoptedSynchronization != null) {
                    sendAcknowledgement()
                } else {
                    sendSynchronization()
                }
                syncDeadline = nowMillis + SYNC_RESEND_MILLIS
            }
        }
        if (retransmitDeadline != null && retransmitDeadline!! <= nowMillis) {
            retransmitDeadline = null
            if (state == State.NORMAL) retransmitDue(nowMillis)
        }
    }

    /**
     * помещает в очередь всю полезную нагрузку сеанса управления. Когда окно заполнено, оно попадает в очередь
     * невыполненных работ и повторяется с помощью ACK или драйвера насоса.
     * , возвращающий false, означает только то, что ссылка не работает.
     *
     * [awaitAck]=false: Отправить и забыть - не включен в список неподтвержденных, не передается повторно, из-за
     * этого ссылка ** не будет подтверждена
     * и умри**. Используется для сообщения «принять, но никогда не ACK» в реальных тестах Audi (идентификация
     * трансмиссии 0x4E0E: пара автомобиль-машина).
     * содержит другие подтверждения миллисекундного уровня, но он не распознается. На основании этого сторожевой
     * таймер повторной передачи однажды отключил всю связь Bluetooth за +20 с.)
     */
    fun sendControl(
        bytes: ByteArray,
        nowMillis: Long,
        awaitAck: Boolean = true,
    ): Boolean {
        if (state == State.DEAD) return false
        val limit = (peerSynchronization?.maxLength ?: 0xFFFF) - HEADER_BYTES - 1
        if (limit <= 0 || bytes.size > limit) {
            die("control payload ${bytes.size} exceeds negotiated limit $limit")
            return false
        }
        sendBacklog += Packet(0, bytes.copyOf(), awaitAck = awaitAck)
        drainSendWindow(nowMillis)
        return true
    }

    // ---- Прием анализа ----

    private fun parseAvailable(nowMillis: Long): Boolean {
        // Отбросить разные байты перед заголовком кадра (включая маркер узла FF55...).
        while (receive.size >= 2 &&
            !(receive[0] == 0xFF.toByte() && receive[1] == 0x5A.toByte())
        ) {
            receive.discard(1)
        }
        if (receive.size < HEADER_BYTES) return false
        val header = receive.peek(HEADER_BYTES)
        if (!checksumValid(header)) {
            receive.discard(1)
            return true
        }
        val length = readU16(header, 2)
        if (length < HEADER_BYTES) {
            receive.discard(1)
            return true
        }
        if (receive.size < length) return false
        val frame = receive.take(length)
        val payloadWithChecksum = frame.copyOfRange(HEADER_BYTES, length)
        if (payloadWithChecksum.isNotEmpty() && !checksumValid(payloadWithChecksum)) {
            receive.discard(1)
            return true
        }
        val payload =
            if (payloadWithChecksum.isEmpty()) {
                null
            } else {
                payloadWithChecksum.copyOf(payloadWithChecksum.size - 1)
            }
        processFrame(
            control = u8(frame[4]),
            sequence = u8(frame[5]),
            ack = u8(frame[6]),
            sessionId = u8(frame[7]),
            payload = payload,
            nowMillis = nowMillis,
        )
        return true
    }

    private fun processFrame(
        control: Int,
        sequence: Int,
        ack: Int,
        sessionId: Int,
        payload: ByteArray?,
        nowMillis: Long,
    ) {
        onFrameReceived?.invoke(control, sequence, ack, sessionId, payload?.size ?: 0)
        if (control and CONTROL_RESET != 0) {
            die("peer sent reset")
            return
        }
        if (control and CONTROL_SYNCHRONIZE != 0) {
            val sync = SynchronizationPayload.decode(payload ?: ByteArray(0))
            // В режиме echoFirst, даже если механизм находится в НОРМАЛЬНОМ режиме, он будет продолжать обрабатывать
            // повторную передачу SYN однорангового узла: одноранговый механизм

            // (DiPlay openTunnel) читает только из TCP. Если наш туннель не был установлен, когда пришёл его SYN,
            // Первое эхо пойдет не в тот канал; его повторная передача SYN в течение 500 мс позволяет нам компенсировать эхо
            // после того, как туннель готов.

            // (Сканирование кадра parseAvailable отбросит маркер FF55 при повторной передаче без каких-либо побочных эффектов).
            //
            // **Только чистый SYN (без бита ACK) возвращает SYN|ACK**: SYN|ACK указывает, что партнер отвечает на наше сообщение.
            // SYN инициатора — это сигнал «он ответил» (см. ветку ACK ниже). Еще один ответ сформируется
            // Бесконечный пинг-понг SYN|ACK, возвращаемый обеими сторонами (однократное тестовое воздействие: согласование
            // никогда не сходится, когда два механизма передают друг другу кадры).

            val isPureSyn = (control and CONTROL_ACKNOWLEDGEMENT) == 0
            if (sync != null && isPureSyn && (state == State.NEGOTIATING || echoFirst)) {
                val firstAdoption = adoptedSynchronization == null
                peerSynchronization = sync
                if (firstAdoption) adoptedSynchronization = sync
                // Тест реального автомобиля Audi (09 октября 2026 г., сравнение на уровне байтов): автомобильный компьютер
                // отправляет SYN в качестве инициатора для объявления своей таблицы сеансов.

                // {1:control,2:EA}, мобильный телефон должен «ACK + повторить таблицу как она есть (SYN|ACK)» — автомобиль
                // отправит сообщение только после того, как увидит эхо.

                // В конце концов ACK завершает согласование (он никогда не возвращает ACK, если возвращает только голый ACK).
                // Эхо заявляет: «Я принимаю ваше заявление».

                // После этого CSM берет идентификатор сеанса управления (1) из таблицы принятия; отправка 10 будет автоматически отменена.
                sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let {
                    adoptedPeerControlSessionId = it.id
                }
                lastReceivedInOrder = sequence
                // **Для каждого** SYN возвращается только один кадр SYN|ACK (голый ACK не возвращается):
                //  ① Полная семантика: SYN|ACK имеет собственный бит подтверждения и поле подтверждения, а голый ACK является избыточным.
                //     (catplay `on_recv_neg_host` отправляет только SYN+ACK);
                // ② Порядковый номер не может конфликтовать: голый ACK использует текущую отправленную последовательность, а
                // SYN|ACK автоматически увеличивается. В следующий раз

                // Голый ACK повторно использует порядковый номер предыдущего кадра. SYN|ACK — BMW отбрасывает кадры с тем же
                // порядковым номером как дубликаты;

                //  ③ При повторной передаче того же самого SYN весь кадр воспроизводится как есть, идемпотентно на уровне байта.
                // 19 октября 2026 г. BMW 87866 Исправлено: старая реализация возвращает голый ACK только после первого принятия,
                // и автомобиль делает это каждый раз.

                // При повторных попытках используются новые серийные номера для возобновления переговоров (161→191→40...),
                // поэтому все 14 раундов застревают в согласовании.

                // 0x1D00 не был достигнут ни разу (журнал: 133 SYN/0 ACK).
                sendSynchronizationEcho(sync, sequence)
                if (firstAdoption) onPeerSynchronizationAdopted?.invoke(sync)
                // Эхо от ответчика (который не отправил SYN) считается завершенным: партнер считает его успешным после просмотра SYN|ACK.
                // Ссылка доступна (настоящий iPhone MIB3 отправляет 0x1D00 сразу после отправки SYN|ACK, не дожидаясь ACK);
                // Инициатор все еще ожидает подтверждения/данных от узла (исходная семантика).
                if (echoFirst || !originatedSyn) completeNegotiationIfPending(nowMillis)
            } else if (sync != null &&
                !isPureSyn &&
                adoptedSynchronization == null &&
                state == State.NEGOTIATING
            ) {
                // Инициатор получает SYN|ACK (ответ узла на наш SYN, полезная нагрузка = таблица узлов):
                // Только принять, **не эхо** (эхо вызовет пинг-понг ответов SYN|ACK). Раньше только в чистом виде
                // Принято в SYN, когда партнер является «чистым ответчиком», инициатор никогда не может достичь НОРМАЛЬНОГО уровня — —
                // Виртуальная связь MIB3 (обнаружение рычага мобильного телефона → SYN автомобиля → SYN мобильного телефона | ACK) точно
                // В такой форме: сторона транспортного средства и машины сходятся из этой ветви, а затем дополняют голый ACK в
                // соответствии с механизмом тайм-аута.

                peerSynchronization = sync
                adoptedSynchronization = sync
                sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let {
                    adoptedPeerControlSessionId = it.id
                }
                // Водяной знак также должен продвигаться вперед (чистая ветвь SYN делает то же самое): если оставить значение 0,
                // инициатор рассматривает первый кадр данных ответчика как «кадр с истекшим сроком действия», только повторно
                // отправляя ack=0 —

                // окно отправки ответчика отравлено этим фиктивным подтверждением (журнал никогда не сбрасывается, модульный
                // тест обнаружен)

                lastReceivedInOrder = sequence
                completeNegotiationIfPending(nowMillis)
            }
        }
        if (control and CONTROL_ACKNOWLEDGEMENT != 0) {
            handleAcknowledgement(ack, nowMillis)
        }
        val isDataFrame =
            payload != null &&
                (control and CONTROL_ACKNOWLEDGEMENT) != 0 &&
                (control and CONTROL_SYNCHRONIZE) == 0 &&
                (control and CONTROL_EXTENDED_ACKNOWLEDGEMENT) == 0
        if (isDataFrame) {
            // Идентификатор сеанса управляющего сообщения: исходная таблица 10 или принятая таблица (Audi=1; испытательный
            // стенд соответствует обоим)

            if (sessionId == SESSION_CONTROL || sessionId == adoptedPeerControlSessionId) {
                // Получение данных от противоположного конца означает, что противоположный конец считает ссылку доступной; если
                // кадр ACK потерян/пропущен, здесь он перейдет в NORMAL.

                completeNegotiationIfPending(nowMillis)
                handleData(sequence, payload!!)
            } else if (sessionId == SESSION_EA && onEaData != null) {
                // Данные сеанса EA (включая сенсорный HID): метод учетной записи однорангового серийного номера не зависит от
                // сеанса управления и может быть вызван напрямую.

                onEaData?.invoke(payload!!)
                sendAcknowledgement()
            } else {
                reportUnknownSessionData(sessionId, payload!!.size)
            }
        }
    }

    private fun completeNegotiationIfPending(nowMillis: Long) {
        if (state == State.NEGOTIATING && peerSynchronization != null) {
            state = State.NORMAL
            markerDeadline = null
            syncDeadline = null
            drainSendWindow(nowMillis)
        }
    }

    private fun reportUnknownSessionData(
        sessionId: Int,
        size: Int,
    ) {
        unknownSessionDropped++
        if (unknownSessionDropped <= 5) onUnknownSessionData?.invoke(sessionId, size)
    }

    /** Диагностика: Сгенерировано эхо-сигнал исходящего пакета синхронизации (туннельный режим используется для шлюза «CSM разрешено отправлять только после доставки эхо-сигнала»). */
    var onSynchronizationEchoSent: (() -> Unit)? = null

    /**
     * повторяет синхронизацию однорангового узла (SYN|ACK, полезная нагрузка точно такая же, как у однорангового
     * узла) - он утверждает, что «мобильный телефон принимает таблицу сеансов автомобиля».
     *
     * **Необходимо использовать новый порядковый номер отправки**: узел проверяет обратный пакет в соответствии со
     * своим собственным методом учета порядкового номера (catplay
     * `NegotiationTx::SynAck` увеличивается на `take_own_seq()`). Повторное использование старой реализации `sentSequence`
     * (никогда не увеличивается после отправки SYN), поэтому SYN и SYN-ACK оба имеют seq=99, и BMW определяет, что
     * это дублирующийся кадр, и отбрасывается——
     * Тайм-аут всех 14 раундов согласования истек, и от транспортного средства не было возвращено ни одного
     * подтверждения (согласно фактическому журналу транспортного средства от 19 октября 2026 г.).
     *
     * поддерживает идемпотентность на уровне байтов для повторных передач одного и того же SYN: воспроизводит
     * последний кадр без увеличения порядкового номера.
     * Туннельный режим DiPlay повторно отправляет один и тот же SYN каждые 500 мс, рассчитывая на это для сохранения исходного поведения.
     */

    /**
     * «Наши параметры», используемые для эха: таблица сеансов/таймаут соответствует оператору однорангового узла
     * (нет особых требований для мобильных телефонов),
     * , но **maxOutgoing упомянул 0x7F** — исходное эхо ограничит наше собственное окно отправки окном на другом конце.
     * маленькое окно (пакет MIB3, 8 элементов находятся в очереди при maxOutgoing=4; объявлен реальный iPhone/AADisplay)
     * 0x7F). maxLength сохраняет значение однорангового узла (=наш верхний предел сбора пакетов, не увеличивайте его).
     */
    private fun ownSyncOf(peer: SynchronizationPayload): SynchronizationPayload =
        peer.copy(maxOutgoing = PHONE_DECLARED_MAX_OUTGOING).also {
            declaredMaxOutgoing = PHONE_DECLARED_MAX_OUTGOING
        }

    private fun sendSynchronizationEcho(
        sync: SynchronizationPayload,
        peerSequence: Int,
    ) {
        if (lastEchoPeerSequence == peerSequence) {
            writePacket(ownSyncOf(sync).encode(), lastEchoOwnSequence, CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT, 0)
            onSynchronizationEchoSent?.invoke()
            return
        }
        val ownSequence = nextSequence(sentSequence).also { sentSequence = it }
        writePacket(ownSyncOf(sync).encode(), ownSequence, CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT, 0)
        lastEchoPeerSequence = peerSequence
        lastEchoOwnSequence = ownSequence
        onSynchronizationEchoSent?.invoke()
    }

    private fun handleAcknowledgement(
        acknowledgement: Int,
        nowMillis: Long,
    ) {
        completeNegotiationIfPending(nowMillis)
        lastSentAcknowledged = acknowledgement
        val window = (peerSynchronization?.maxOutgoing ?: maxOutgoing) + ACK_SLACK
        unacknowledged.removeAll { sequenceDistance(it.sequence, acknowledgement) !in 1..window }
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
        drainSendWindow(nowMillis)
    }

    private fun handleData(
        sequence: Int,
        payload: ByteArray,
    ) {
        val distance = sequenceDistance(sequence, lastReceivedInOrder)
        val peerWindow = peerSynchronization?.maxOutgoing ?: maxOutgoing
        if (distance == 0 || distance > peerWindow + ACK_SLACK) {
            sendAcknowledgement() // Дубликат или срок действия истек: еще раз подтвердите текущий серийный номер.
            return
        }
        // RFCOMM представляет собой надежный упорядоченный поток и не теряет пакеты; скачок порядкового номера может
        // быть вызван только тем, что партнер включает ACK/SYN в порядковый номер.

        // (Методы бухгалтерского учета каждой компании противоречивы). Поэтому примите любую величину продвижения и
        // поднимите уровень воды, чтобы избежать потери кадров, подобной тупику.

        lastReceivedInOrder = sequence
        if (pendingEvents.size >= 256) {
            die("event queue overflow")
            return
        }
        pendingEvents += Event.Control(payload)
        sendAcknowledgement()
    }

    // ----Отправить ----

    private fun drainSendWindow(nowMillis: Long) {
        if (state != State.NORMAL) return
        while (sendBacklog.isNotEmpty()) {
            val packet = sendBacklog.first()
            val peer = peerSynchronization
            val window = declaredMaxOutgoing
            if (lastSentAcknowledged != null &&
                sequenceDistance(sentSequence, lastSentAcknowledged!!) >= window
            ) {
                return
            }
            sendBacklog.removeFirst()
            sentSequence = nextSequence(sentSequence)
            packet.sequence = sentSequence
            packet.deadlineMillis = nowMillis + retransmissionTimeoutMillis
            writePacket(packet.payload, packet.sequence, CONTROL_ACKNOWLEDGEMENT, controlSessionId())
            if (!packet.awaitAck) continue // Отправь и забудь: ни отслеживания, ни повторной передачи (см. комментарий sendControl)
            if (peer == null || peer.maxRetransmissions > 0) {
                unacknowledged += packet
                retransmitDeadline = packet.deadlineMillis
            } else {
                lastSentAcknowledged = sentSequence
            }
        }
    }

    /** Идентификатор сеанса исходящих управляющих сообщений: если принята таблица одноранговых узлов, используйте ее идентификатор управления (Audi=1), в противном случае эту таблицу 10. */
    private fun controlSessionId(): Int = adoptedPeerControlSessionId ?: SESSION_CONTROL

    private fun retransmitDue(nowMillis: Long) {
        val next = unacknowledged.minByOrNull { it.deadlineMillis } ?: return
        next.deadlineMillis = nowMillis + retransmissionTimeoutMillis
        next.retransmissions += 1
        if (next.retransmissions > maxRetransmissions) {
            die("packet ${next.sequence} not acknowledged")
            return
        }
        writePacket(next.payload, next.sequence, CONTROL_ACKNOWLEDGEMENT, controlSessionId())
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
    }

    private fun sendSynchronization() {
        val payload =
            SynchronizationPayload(
                maxOutgoing = maxOutgoing,
                maxLength = 0xFFFF,
                retransmissionTimeoutMillis = retransmissionTimeoutMillis.toInt().coerceIn(0, 0xFFFF),
                acknowledgementTimeoutMillis = 500,
                maxRetransmissions = maxRetransmissions,
                maxAcknowledgements = 3,
                sessions =
                    listOf(
                        SessionDescriptor(SESSION_CONTROL, 0, 2),
                        SessionDescriptor(SESSION_EA, 2, 1),
                        SessionDescriptor(SESSION_FILE_TRANSFER, 1, 2),
                    ),
            ).encode()
        writePacket(payload, sentSequence, CONTROL_SYNCHRONIZE, 0)
    }

    private fun sendAcknowledgement() {
        writePacket(null, sentSequence, CONTROL_ACKNOWLEDGEMENT, 0)
    }

    private fun writePacket(
        payload: ByteArray?,
        sequence: Int,
        control: Int,
        sessionId: Int,
    ) {
        if (state == State.DEAD) return
        onFrameSent?.invoke(control, sequence, lastReceivedInOrder, sessionId, payload?.size ?: 0)
        val length = payload?.size?.plus(HEADER_BYTES + 1) ?: HEADER_BYTES
        val frame = ByteArray(length)
        frame[0] = 0xFF.toByte()
        frame[1] = 0x5A
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        frame[4] = control.toByte()
        frame[5] = sequence.toByte()
        frame[6] = lastReceivedInOrder.toByte()
        frame[7] = sessionId.toByte()
        var sum = 0
        for (index in 0 until HEADER_BYTES - 1) sum += u8(frame[index])
        frame[HEADER_BYTES - 1] = (-sum and 0xFF).toByte()
        if (payload != null) {
            payload.copyInto(frame, HEADER_BYTES)
            var payloadSum = 0
            payload.forEach { payloadSum += u8(it) }
            frame[frame.size - 1] = (-payloadSum and 0xFF).toByte()
        }
        appendOutput(frame)
    }

    private fun die(reason: String?) {
        if (state == State.DEAD) return
        state = State.DEAD
        markerDeadline = null
        syncDeadline = null
        retransmitDeadline = null
        output.reset()
        pendingEvents.clear()
        sendBacklog.clear()
        pendingEvents += Event.Dead(reason)
    }

    private fun appendOutput(bytes: ByteArray): Boolean {
        if (state == State.DEAD) return false
        output.write(bytes)
        return true
    }

    private class ReceiveBuffer {
        private val bytes = ByteArray(0xFFFF + 8)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun remainingCapacity(): Int {
            compact()
            return bytes.size - tail
        }

        operator fun get(index: Int): Byte = bytes[head + index]

        fun append(
            source: ByteArray,
            offset: Int,
            count: Int,
        ) {
            source.copyInto(bytes, tail, offset, offset + count)
            tail += count
        }

        fun discard(count: Int) {
            head += count
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        fun peek(count: Int): ByteArray = bytes.copyOfRange(head, head + count)

        fun take(count: Int): ByteArray {
            val out = peek(count)
            discard(count)
            return out
        }

        private fun compact() {
            if (head == 0) return
            if (head < tail) bytes.copyInto(bytes, 0, head, tail)
            tail -= head
            head = 0
        }
    }

    companion object {
        const val SESSION_CONTROL = 10
        const val SESSION_EA = 11
        const val SESSION_FILE_TRANSFER = 12

        /** Введите поле в дескрипторе сеанса: 0 = сеанс управления (согласно xcertplay/extractor/catplay). */
        private const val CONTROL_KIND = 0

        val MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
        const val HEADER_BYTES = 9
        private const val SYNCHRONIZATION_VERSION = 1
        private const val CONTROL_SYNCHRONIZE = 0x80
        private const val CONTROL_ACKNOWLEDGEMENT = 0x40
        private const val CONTROL_EXTENDED_ACKNOWLEDGEMENT = 0x20
        private const val CONTROL_RESET = 0x10
        private const val INITIAL_SEQUENCE = 99
        private const val MARKER_RESEND_MILLIS = 1_000L
        private const val SYNC_RESEND_MILLIS = 500L
        private const val ACK_SLACK = 10

        private fun sequenceDistance(
            sequence: Int,
            previous: Int,
        ): Int = (sequence - previous) and 0xFF

        private fun nextSequence(sequence: Int): Int = (sequence + 1) and 0xFF

        private fun checksumValid(bytes: ByteArray): Boolean {
            var sum = 0
            bytes.forEach { sum += u8(it) }
            return sum and 0xFF == 0
        }

        private fun readU16(
            bytes: ByteArray,
            offset: Int,
        ): Int = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])

        private fun writeU16(
            out: ByteArrayOutputStream,
            value: Int,
        ) {
            out.write(value ushr 8)
            out.write(value)
        }

        private fun u8(byte: Byte): Int = byte.toInt() and 0xFF
    }
}
