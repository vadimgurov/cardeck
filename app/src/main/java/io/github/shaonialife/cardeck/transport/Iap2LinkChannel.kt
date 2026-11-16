package io.github.shaonialife.cardeck.transport

import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Канал связи : поток чтения + драйвер ритмического потока [Iap2LinkEngine], доступный для сеансового уровня.
 * блокирует sendControl/recvControl и awaitReady.
 */
class Iap2LinkChannel(
    private val stream: DuplexStream,
    name: String = "iap2-link",
    private val log: (String) -> Unit = {},
) : Closeable {
    private val engine = Iap2LinkEngine()
    private val lock = Object()
    private val controls = LinkedBlockingQueue<ByteArray?>()
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var terminated = false
    private var readySignalled = false

    /** Бюджет необработанного дампа байтов (на каждое направление на соединение), используемый для реального сравнения на уровне байтов. */
    private var rxDumpBudget = RAW_DUMP_LIMIT
    private var txDumpBudget = RAW_DUMP_LIMIT_TX

    /** Бюджет сводного журнала по кадрам (на соединение по направлению). */
    private var rxFrameLogs = 0
    private var txFrameLogs = 0

    val isAlive: Boolean get() = !terminated

    init {
        engine.onPeerSynchronizationAdopted = { sync ->
            log(
                "ССЫЛКА: Примите таблицу одноранговых сеансов maxOutgoing=${sync.maxOutgoing} maxLen=${sync.maxLength}" +
                    "sessions=" + sync.sessions.joinToString { "${it.id}/k${it.kind}/v${it.version}" },
            )
        }
        engine.onUnknownSessionData = { sessionId, size ->
            log("ССЫЛКА: удалить кадр данных (байты $size) неизвестного сеанса $sessionId.")
        }
        engine.onFrameReceived = { control, sequence, ack, sessionId, size ->
            // Ключевые наблюдения для отладки реального автомобиля: возвращает ли и какой кадр автомобиль на этапе
            // распознавания (все молчаливые различия зависят от его положения)

            if (rxFrameLogs < FRAME_LOG_LIMIT) {
                rxFrameLogs++
                log(
                    "LINK rx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        engine.onFrameSent = { control, sequence, ack, sessionId, size ->
            if (txFrameLogs < FRAME_LOG_LIMIT) {
                txFrameLogs++
                log(
                    "LINK tx: ctl=0x${"%02x".format(control)} seq=$sequence ack=$ack " +
                        "session=$sessionId len=$size",
                )
            }
        }
        Thread({ runReader() }, "$name-reader").apply {
            isDaemon = true
            start()
        }
        Thread({ runPacer() }, "$name-pacer").apply {
            isDaemon = true
            start()
        }
    }

    /** Дождитесь завершения согласования канала (сеанс 10 доступен для записи). */
    fun awaitReady(timeoutMillis: Long): Boolean {
        if (!readyLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        return !terminated
    }

    /** Максимальная управляющая полезная нагрузка, согласованная партнером (после вычета заголовка кадра и контрольной суммы), которая до согласования равна нулю. */
    fun peerMaxControlPayload(): Int? = synchronized(lock) { engine.peerSynchronization?.maxLength?.minus(Iap2LinkEngine.HEADER_BYTES + 1) }

    /** Ставит в очередь полезные данные полного управления; возвращает false, если ссылка не работает. Внутреннее отставание, когда окно заполнено, выдается драйвером ACK. */
    fun sendControl(
        bytes: ByteArray,
        awaitAck: Boolean = true,
    ): Boolean {
        synchronized(lock) {
            if (terminated) return false
            val accepted = engine.sendControl(bytes, nowMillis(), awaitAck)
            drainOutputLocked()
            return accepted
        }
    }

    /** Удалить следующую управляющую нагрузку; возвращает значение null при тайм-ауте или конце ссылки. */
    fun recvControl(timeoutMillis: Long): ByteArray? {
        if (timeoutMillis == 0L && terminated && controls.isEmpty()) return null
        val polled =
            try {
                controls.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        return if (polled === null || polled === POISON) null else polled
    }

    override fun close() {
        finish(null)
    }

    // ---- Внутренний ----

    private fun runReader() {
        try {
            synchronized(lock) {
                engine.start(nowMillis())
                drainOutputLocked()
            }
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) {
                    finish(null)
                    return
                }
                if (count == 0) continue
                synchronized(lock) {
                    if (terminated) return
                    dumpInbound(buffer.copyOf(count))
                    engine.feed(buffer.copyOf(count), nowMillis())
                    processEventsLocked()
                    drainOutputLocked()
                    signalReadyLocked()
                }
            }
        } catch (error: Throwable) {
            finish(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Захват оригинального байтового пакета  (единственное средство сравнения на уровне байтов для реальных
     * автомобилей): сбрасывать только переднюю часть в каждом направлении
     * [RAW_DUMP_LIMIT] байт для предотвращения взрыва журнала долгосрочных сеансов.
     */
    private fun dumpInbound(bytes: ByteArray) {
        if (bytes.isEmpty() || rxDumpBudget <= 0) return
        val shown = minOf(bytes.size, rxDumpBudget)
        log("RAW rx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        rxDumpBudget -= shown
    }

    private fun runPacer() {
        while (!terminated) {
            val deadline: Long?
            synchronized(lock) {
                if (terminated) return
                engine.advanceTime(nowMillis())
                processEventsLocked()
                drainOutputLocked()
                signalReadyLocked()
                deadline = engine.nextDeadlineMillis()
                if (deadline == null) {
                    try {
                        lock.wait(PACER_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
            if (deadline != null) {
                val sleep = (deadline - nowMillis()).coerceIn(1, PACER_POLL_MILLIS)
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** Вызов с удержанием блокировки: извлекает события механизма и обрабатывает выходные данные. */
    private fun processEventsLocked() {
        while (true) {
            when (val event = engine.pollEvent() ?: return) {
                is Iap2LinkEngine.Event.Control -> {
                    if (!controls.offer(event.bytes)) {
                        engine.feedEof()
                        return
                    }
                }

                is Iap2LinkEngine.Event.Dead -> {
                    finishLocked(event.reason?.let { IOException(it) })
                    signalReadyLocked()
                    return
                }
            }
        }
    }

    private fun drainOutputLocked() {
        val bytes = engine.takeOutput()
        if (bytes.isEmpty()) return
        dumpOutbound(bytes)
        try {
            stream.write(bytes)
        } catch (error: IOException) {
            finishLocked(error)
        }
        signalReadyLocked()
    }

    private fun dumpOutbound(bytes: ByteArray) {
        if (txDumpBudget <= 0) return
        val shown = minOf(bytes.size, txDumpBudget)
        log("RAW tx: " + bytes.copyOf(shown).joinToString(" ") { "%02x".format(it) })
        txDumpBudget -= shown
    }

    private fun signalReadyLocked() {
        if (!readySignalled && engine.state == Iap2LinkEngine.State.NORMAL) {
            readySignalled = true
            readyLatch.countDown()
        }
    }

    private fun finish(reason: String?) {
        synchronized(lock) { finishLocked(reason?.let { IOException(it) }) }
    }

    private fun finishLocked(failure: IOException?) {
        if (terminated) return
        terminated = true
        controls.offer(POISON) // Sentinel: пробуждение заблокированного получателя (LBQ не принимает значение null)
        readyLatch.countDown()
        lock.notifyAll()
        runCatching { stream.close() }
        if (failure != null) {
            log("LINK closed: ${failure.message}")
        } else {
            log("LINK closed")
        }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val PACER_POLL_MILLIS = 20L

        /** Бюджет необработанного байтового дампа: rx должен покрыть все кадры переговоров на автомобиле (Audi измерил около 140 байт), оставив запас в 512 байт. */
        // Бюджет указывается в байтах. Был 512/256: Полное доведение не может его охватить (одиночный кадр 0x1D01 - 565B),
        // При извлечении фикстур воспроизведения (tools/extract_car_fixtures.py) будет отсутствовать хвост – упоминается покрытие 4096.
        // Вся идентификация/MFi/обмен учетными данными, длинные сеансы ограничены бюджетом, чтобы предотвратить взрыв журнала.
        const val RAW_DUMP_LIMIT = 4096
        const val RAW_DUMP_LIMIT_TX = 4096

        /** Ограничение покадрового сводного журнала (на одно соединение на каждое направление). */
        const val FRAME_LOG_LIMIT = 40

        /** Датчик завершения соединения; реальные управляющие данные содержат по крайней мере один кадр CSM и не будут пустыми. */
        val POISON = ByteArray(0)
    }
}
