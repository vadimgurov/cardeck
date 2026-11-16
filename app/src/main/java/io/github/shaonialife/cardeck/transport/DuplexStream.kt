package io.github.shaonialife.cardeck.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Блокировка абстракции двунаправленного потока байтов: сокет Bluetooth RFCOMM используется совместно с тестированием обратной связи в памяти. */
interface DuplexStream : Closeable {
    /** Блокирует чтение; возвращает -1, чтобы указать конец потока. */
    fun read(buffer: ByteArray): Int

    /** Блокирует запись всех байтов или выдает исключение. */
    fun write(bytes: ByteArray)

    override fun close()
}

class StreamDuplexStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {},
) : DuplexStream {
    /**
     * Примечание: чтение/запись не может совместно использовать блокировку — поток чтения блокируется в read() и
     * удерживает блокировку в ожидании данных.
     * Поток записи       никогда не сможет получить блокировку (фактически срабатывает на реальном устройстве
     * Bluetooth). Безопасность параллелизма при записи обеспечивается
     * Гарантия блокировки канала Iap2LinkChannel (все записи выполняются последовательно внутри блокировки), чтение происходит только
     * - единственная читающая ветка. Потоки ввода/вывода разъема Bluetooth независимы друг от друга, а перекрестное
     * чтение и запись безопасны.
     */
    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        onClose()
    }
}

/** Пара потоков кольцевой проверки памяти: на каждом конце содержится DuplexStream для модульного тестирования. */
class InMemoryStreamPair {
    private val aToB = Pipe()
    private val bToA = Pipe()

    val sideA: DuplexStream =
        object : DuplexStream {
            override fun read(buffer: ByteArray) = aToB.source.read(buffer)

            override fun write(bytes: ByteArray) = bToA.writeAndFlush(bytes)

            override fun close() {
                aToB.close()
                bToA.closePartner()
            }
        }

    val sideB: DuplexStream =
        object : DuplexStream {
            override fun read(buffer: ByteArray) = bToA.source.read(buffer)

            override fun write(bytes: ByteArray) = aToB.writeAndFlush(bytes)

            override fun close() {
                bToA.close()
                aToB.closePartner()
            }
        }

    private class Pipe {
        val source = PipedInputStream(1 shl 16)
        val sink = PipedOutputStream()

        init {
            sink.connect(source)
        }

        fun writeAndFlush(bytes: ByteArray) {
            sink.write(bytes)
            sink.flush()
        }

        fun close() {
            runCatching { sink.close() }
            runCatching { source.close() }
        }

        /** Закройте выход узла и позвольте узлу прочитать EOF. */
        fun closePartner() {
            runCatching { source.close() }
        }
    }
}

class DuplexClosedException(
    message: String,
) : IOException(message)
