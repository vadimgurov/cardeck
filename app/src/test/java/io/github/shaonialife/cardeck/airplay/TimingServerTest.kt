package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** Служба синхронизации: передача туда и обратно в кодировке NTP + конструкция запрос-ответ (сравните формат проводной связи catplay rtcp_timesync.rs). */
class TimingServerTest {
    @Test
    fun ntpEncodingRoundTrips() {
        val values = listOf(0L, 123_456_789L, 999_999_999L, 1_000_000_000L, 12_345_678_901_234L)
        for (nanos in values) {
            val packed = ByteBuffer.wrap(TimingServer.ntpFromNanos(nanos)).long
            val secs = packed shr 32
            val fracNanos = ((packed and 0xFFFF_FFFFL) * 1_000_000_000L) shr 32
            val back = secs * 1_000_000_000L + fracNanos
            // При фиксации дробных битов с фиксированной точкой происходит усечение: тот же тест «кошачьей игры» допускает ±1 нс.
            assertTrue("nanos=$nanos back=$back", Math.abs(back - nanos) <= 1)
        }
    }

    @Test
    fun respondsToTimesyncRequestEchoingT3() {
        val request = ByteArray(32)
        request[0] = (2 shl 6).toByte()
        request[1] = 0xD2.toByte()
        request[2] = 0
        request[3] = 7
        val clientNanos = 987_654_321L
        val packed = ByteBuffer.wrap(TimingServer.ntpFromNanos(clientNanos)).long
        for (index in 0 until 8) {
            request[24 + index] = (packed ushr (8 * (7 - index))).toByte()
        }

        val response = TimingServer.buildResponse(request, nowNanos = 5_000_000_000L)
        assertNotNull(response)
        response!!
        assertEquals(32, response.size)
        assertEquals((2 shl 6).toByte(), response[0])
        assertEquals(0xD3.toByte(), response[1])
        assertEquals(7, ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF))
        // t1 = запрошено эхо t3
        assertArrayEquals(request.copyOfRange(24, 32), response.copyOfRange(8, 16))
        // t2 = t3 = NTP encoding of local 5s
        val expected = TimingServer.ntpFromNanos(5_000_000_000L)
        assertArrayEquals(expected, response.copyOfRange(16, 24))
        assertArrayEquals(expected, response.copyOfRange(24, 32))
    }

    @Test
    fun rejectsNonTimesyncPackets() {
        assertNull(TimingServer.buildResponse(ByteArray(8), 0))
        assertNull(TimingServer.buildResponse(ByteArray(32), 0)) // Все нули: версия=0

        val badVersion = ByteArray(32)
        badVersion[0] = (3 shl 6).toByte()
        badVersion[1] = 0xD2.toByte()
        assertNull(TimingServer.buildResponse(badVersion, 0))

        val responsePt = ByteArray(32)
        responsePt[0] = (2 shl 6).toByte()
        responsePt[1] = 0xD3.toByte() // The response type should not be answered again
        assertNull(TimingServer.buildResponse(responsePt, 0))
    }
}
