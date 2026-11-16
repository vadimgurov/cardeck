package io.github.shaonialife.cardeck.video

import io.github.shaonialife.cardeck.airplay.BinaryPlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * экранный поток KeepAlive возвращает формат кадра.
 *
 * 19 октября 2026 г. Фактические измерения Audi MMI 3315: Когда используется только UDP KeepAlive + /feedback, ЗАПИСЬ составляет около
 * 10.2s Весь сеанс был сброшен (все каналы видео/событий исправны). Catplay по-прежнему транслируется каждую
 * секунду, пока видео продолжает передаваться
 * записывает в порт данных экрана кадр KeepAlive — это независимый сигнал KeepAlive на принимающем экране.
 * Когда формат кадра   записан неправильно, принимающая сторона молча игнорирует его, и явление все равно
 * «отключается через 10 секунд», поэтому требуется одиночная тестовая блокировка.
 */
class VideoKeepAliveFrameTest {
    private fun header(frame: ByteArray) = frame.copyOfRange(0, 128)

    private fun bodySize(header: ByteArray): Int =
        ByteBuffer
            .wrap(header, 0, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    @Test
    fun emptyKeepAliveIsOpcode2HeaderOnly() {
        val frame = VideoSender.buildKeepAliveFrame(withStats = false, frames = 30, bytesPerSecond = 1.0, framesPerSecond = 30)
        assertEquals(128, frame.size)
        assertEquals(0, bodySize(frame))
        assertEquals(2, frame[4].toInt() and 0xFF)
    }

    @Test
    fun statsKeepAliveIsOpcode5WithBplistBody() {
        val frame =
            VideoSender.buildKeepAliveFrame(
                withStats = true,
                frames = 30,
                bytesPerSecond = 900_000.0,
                framesPerSecond = 30,
            )
        val head = header(frame)
        assertEquals(5, head[4].toInt() and 0xFF)
        val size = bodySize(head)
        assertEquals(frame.size - 128, size)

        val body = frame.copyOfRange(128, frame.size)
        val stats = BinaryPlist.parse(body) as Map<*, *>
        assertEquals(30L, stats["encoderCurrentFPS"])
        assertEquals(30L, stats["sentFramesAvg"])
        assertEquals(900_000.0, stats["txUsageAvg"])
    }

    @Test
    fun statsBodyLengthIsMirroredIntoParams14AsLittleEndianFloat() {
        val frame = VideoSender.buildKeepAliveFrame(withStats = true, frames = 30, bytesPerSecond = 1.0, framesPerSecond = 30)
        val expected = (frame.size - 128).toFloat()
        // params[14] находится по смещению 120..128, а последние 4 байта — это длина тела f32 LE (так же, как catplay)
        val declared = ByteBuffer.wrap(frame, 124, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(expected, declared, 0.0f)
        // Первые 4 байта имеют фиксированное значение 0.0f.
        assertEquals(0.0f, ByteBuffer.wrap(frame, 120, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0.0f)
    }

    @Test
    fun keepAliveHeaderCarriesNoChaChaTag() {
        // KeepAlive — это текстовый фрейм: длина тела должна быть равна фактическому телу (исключая тег 16B),
        // В противном случае принимающая сторона будет рассматривать байты после тела статистики как заголовок
        // следующего кадра, и общая граница кадра будет смещена.

        val frame = VideoSender.buildKeepAliveFrame(withStats = true, frames = 1, bytesPerSecond = 0.0, framesPerSecond = 1)
        val size = bodySize(header(frame))
        assertTrue("body=$size", size > 0)
        assertEquals(128 + size, frame.size)
    }

    /**
     * Заголовок кадра  VideoConfig должен соответствовать Catplay `ScreenFrame::config`.
     *
     * Audi 4851 (AUDI 5163 / DELPHI, журнал 0.1.4) фактический тест: сопряжение, НАСТРОЙКА, ЗАПИСЬ, потоковая передача,
     * Все касания на       нормальные, **экран автомобиля весь чёрный**. catplay прописал в заголовке конфигурации
     * два поля, которые мы пропустили:
     * — `small_param[1] |= ScreenFlag::Encrypted (0x04)` — заявляет, что видеокадр запечатан;
     * - `params[1]` = (ширина, высота) два f32 LE - машина машины строит полотно декодера соответственно.
     * DiPlay/WinPlay считывает только тело и не проверяет заголовок, поэтому тестовый стенд никогда не подвергался воздействию.
     */
    @Test
    fun videoConfigHeaderDeclaresEncryptionAndCanvasSize() {
        val header = VideoSender.buildVideoConfigHeader(bodySize = 35, width = 1412, height = 654)

        assertEquals(35, bodySize(header))
        assertEquals(1, header[4].toInt() and 0xFF) // код операции = VideoConfig
        // small_param[1] со смещением головки 6; Зашифровано = 1<<2
        assertEquals(0x04, header[6].toInt() and 0x04)

        // params[0] (смещение 8..16) — это слот PTS, в конфигурации необходимо оставить 0
        for (index in 8 until 16) {
            assertEquals("params[0] должно быть 0（компенсация $index）", 0, header[index].toInt())
        }

        // params[1] (смещение 16..24) = ширина f32 LE, высота f32 LE
        val width = ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).float
        val height = ByteBuffer.wrap(header, 20, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(1412.0f, width, 0.0f)
        assertEquals(654.0f, height, 0.0f)
    }

    @Test
    fun videoConfigHeaderUsesEncodedEvenDimensions() {
        // Размер кодировщика был выровнен по четному числу во время построения, и заголовок должен сообщать то же
        // значение (машина строит холст в соответствии с ним).

        val header = VideoSender.buildVideoConfigHeader(bodySize = 20, width = 1260, height = 654)
        assertEquals(1260.0f, ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0.0f)
        assertEquals(654.0f, ByteBuffer.wrap(header, 20, 4).order(ByteOrder.LITTLE_ENDIAN).float, 0.0f)
    }
}
