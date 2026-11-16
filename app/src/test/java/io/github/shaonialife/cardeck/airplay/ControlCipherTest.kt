package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Зашифрованные кадры канала управления взаимодействуют с форматом опорного приемника (двунаправленная, кадровая и фрагментированная доставка). */
class ControlCipherTest {
    @Test
    fun phoneAndReceiverCiphersInteropBothDirections() {
        val phoneWriteKey = ByteArray(32) { it.toByte() }
        val phoneReadKey = ByteArray(32) { (it + 7).toByte() }
        val phone = ControlCipher(phoneReadKey, phoneWriteKey)
        val receiver = ControlCipher(phoneWriteKey, phoneReadKey) // Взгляд получателя: читайте и пишите, пишите и читайте

        val request =
            "SETUP rtsp://192.168.149.253:7000/ RTSP/1.0\r\nContent-Length: 4\r\n\r\nbody"
                .toByteArray()
        assertArrayEquals(request, receiver.decrypt(phone.encrypt(request)).data)

        val reply = "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()
        assertArrayEquals(reply, phone.decrypt(receiver.encrypt(reply)).data)
    }

    @Test
    fun largePayloadIsChunkedIntoMultipleFrames() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 1 })
        val receiver = ControlCipher(ByteArray(32) { 1 }, ByteArray(32))
        val big = ByteArray(0x5000) { (it % 251).toByte() } // Превышает верхний предел 1422 на кадр.
        assertArrayEquals(big, receiver.decrypt(phone.encrypt(big)).data)
    }

    /**
     * Верхний предел открытого текста кадра управления  должен составлять 1422 (тег 1440 MTU - 2B AAD - 16B), что аналогично кошачьей игре.
     * `AirPlayCipherCodec` соответствует. В старой реализации используется 0x4000: кадры, превышающие MTU, будут строго оцениваться как
     * Нарушение протокола       (сторона декодирования Catplay напрямую сообщает PayloadTooBig, если len > 1422).
     */
    @Test
    fun eachFrameStaysWithinCatplayMtu() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 3 })
        val receiver = ControlCipher(ByteArray(32) { 3 }, ByteArray(32))
        val big = ByteArray(5000) { (it % 127).toByte() }
        val wire = phone.encrypt(big)

        // Формат покадровой маршрутизации: длина 2 байта + открытый текст + тег 16 байт; длина открытого текста любого
        // кадра не должна превышать 1422

        var offset = 0
        var frames = 0
        while (offset < wire.size) {
            val length = (wire[offset].toInt() and 0xFF) or ((wire[offset + 1].toInt() and 0xFF) shl 8)
            assertTrue("Длина открытого текста кадра $length превышать 1422（MTU 1440）", length <= 1422)
            offset += 2 + length + 16
            frames++
        }
        assertEquals("5000 Байты делятся как минимум на 4 рамка", 4, frames)
        assertArrayEquals(big, receiver.decrypt(wire).data)
    }

    @Test
    fun partialFramesDeliveredInPieces() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 2 })
        val receiver = ControlCipher(ByteArray(32) { 2 }, ByteArray(32))
        val message = "RTSP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
        val encrypted = receiver.encrypt(message + message)

        val half = encrypted.size / 2
        val first = phone.decrypt(encrypted.copyOfRange(0, half))
        val second = phone.decrypt(first.rest + encrypted.copyOfRange(half, encrypted.size))
        assertArrayEquals(message + message, first.data + second.data)
    }
}
