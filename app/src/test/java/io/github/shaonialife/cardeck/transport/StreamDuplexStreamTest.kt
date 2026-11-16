package io.github.shaonialife.cardeck.transport

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Регрессионный тест: поток чтения блокируется во время чтения(), а поток записи должен иметь возможность завершить запись().
 * Старая реализация чтения/записи   использует монитор @Synchronized и удерживает блокировку, когда чтение блокируется.
 * пишет постоянное ожидание - фактически сработало на реальном устройстве Bluetooth (журнал прекращается после готовности соединения).
 */
class StreamDuplexStreamTest {
    @Test
    fun writeCompletesWhileReaderBlocksInRead() {
        val source = PipedInputStream(1 shl 16)
        val sink = PipedOutputStream()
        sink.connect(source)
        val stream = StreamDuplexStream(source, sink)

        val reader =
            Thread {
                val buffer = ByteArray(1024)
                try {
                    while (stream.read(buffer) >= 0) {
                        // Постоянная блокировка чтения, имитирующая поток чтения канала ссылки
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }

        Thread.sleep(200) // Убедитесь, что поток чтения заблокирован в read().

        val written = CountDownLatch(1)
        Thread({
            stream.write(byteArrayOf(0x40, 0x40, 0x00, 0x06, 0x1D, 0x00))
            written.countDown()
        }, "writer").apply {
            isDaemon = true
            start()
        }

        assertTrue("write Произошла взаимоблокировка, пока поток чтения был заблокирован", written.await(2, TimeUnit.SECONDS))

        stream.close()
        reader.join(1_000)
    }
}
