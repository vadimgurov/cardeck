package io.github.shaonialife.cardeck.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Служба синхронизации AirPlay (NTP через RTCP, сравните catplay clock/timing_server.rs + rtcp_timesync.rs).
 *
 * начальная НАСТРОЙКА объявляет этот порт (timingPort); автомобильный компьютер (ведущий медиа-часы) отправляет
 * ему 32-байтовый запрос синхронизации времени,
 * Мы отвечаем в соответствии с семантикой NTP: t1=эхо-запрос t3 (время отправки клиента), t2=время локального
 * приема, t3=время локальной отправки.
 * Отсутствует тайминг: Ранее объявленный таймингПорт=0, машина не может синхронизировать с нами часы мультимедиа
 * - поток экрана SETUP был 400
 * — подозреваемый номер один (реализация Catplay в реальном автомобиле всегда привязана к реальному порту).
 *
 * Формат строки   (с обратным порядком байтов): [0x80][pt][длина=7][rtp_ts=0][t1(8)][t2(8)][t3(8)],
 * pt: запрос 0xD2 / ответ 0xD3; время = NTP 64 бита (секунды <<32 | 2^32 доли), взятые из монотонных часов.
 */
class TimingServer(
    private val log: (String) -> Unit = {},
) {
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    /** Подсчет полученных запросов timesync (диагностика: действительно ли машина синхронизирует медиачасы). */
    private var syncRequests = 0L
    private var lastSyncLogAt = 0L

    /** Подсчет пакетов, не синхронизированных по времени (диагностика: есть ли какие-либо другие UDP-пакеты, попадающие на этот порт). */
    private var unexpected = 0L

    val port: Int get() = socket?.localPort ?: 0

    fun start(): Boolean {
        if (port != 0 && running) return true
        return try {
            val s = DatagramSocket()
            s.reuseAddress = true
            socket = s
            running = true
            Thread({ loop(s) }, "airplay-timing").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("ВРЕМЯ: Ошибка запуска — ${error.message}")
            false
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
    }

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(256)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: Exception) {
                return // розетка закрыта
            }
            val request = buffer.copyOfRange(0, packet.length)
            val response = buildResponse(request, System.nanoTime())
            if (response == null) {
                unexpected++
                if (unexpected <= 5) {
                    log("ВРЕМЯ: получено сообщение без синхронизации времени ${request.size}B (накоплено $unexpected).")
                }
                continue
            }
            syncRequests++
            val now = System.currentTimeMillis()
            if (syncRequests <= 3 || now - lastSyncLogAt >= 30_000L) {
                lastSyncLogAt = now
                log("ВРЕМЯ: ответ синхронизации времени #$syncRequests → ${packet.address.hostAddress}:${packet.port}")
            }
            try {
                s.send(DatagramPacket(response, response.size, packet.address, packet.port))
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val PT_TIMESYNC_REQUEST: Byte = 0xD2.toByte()
        private const val PT_TIMESYNC_RESPONSE: Byte = 0xD3.toByte()
        private const val PACKET_BYTES = 32

        /**
         * Создает ответ синхронизации времени; возвращает значение null для запросов без синхронизации времени.
         * [nowNanos]: Текущее значение монотонных часов (семантика System.nanoTime может быть любой отправной точкой —
         * при расчете разницы NTP используются только относительные величины).
         */
        fun buildResponse(
            request: ByteArray,
            nowNanos: Long,
        ): ByteArray? {
            if (request.size < PACKET_BYTES) return null
            val version = (request[0].toInt() and 0xFF) shr 6
            if (version != 2) return null
            if (request[1] != PT_TIMESYNC_REQUEST) return null
            val length = ((request[2].toInt() and 0xFF) shl 8) or (request[3].toInt() and 0xFF)
            if (length != PACKET_BYTES / 4 - 1) return null

            val response = ByteArray(PACKET_BYTES)
            response[0] = (2 shl 6).toByte()
            response[1] = PT_TIMESYNC_RESPONSE
            response[2] = 0
            response[3] = (PACKET_BYTES / 4 - 1).toByte()
            // rtp_ts(4..8) = 0
            System.arraycopy(request, 24, response, 8, 8) // t1 = запрошенный t3 (эхо ntp_xmit)
            val now = ntpFromNanos(nowNanos)
            System.arraycopy(now, 0, response, 16, 8) // t2 = время приема
            System.arraycopy(now, 0, response, 24, 8) // t3 = время отправки
            return response
        }

        /** Монотонная наносекунда → NTP 64 бита (секунды <<32 | (доля наносекунды * 2^32 / 1e9)), прямой порядок байтов 8 байт. */
        fun ntpFromNanos(nanos: Long): ByteArray {
            val secs = Math.floorDiv(nanos, 1_000_000_000L)
            val fracNanos = Math.floorMod(nanos, 1_000_000_000L)
            val frac = (fracNanos.toLong() shl 32) / 1_000_000_000L
            val packed = (secs shl 32) or (frac and 0xFFFF_FFFFL)
            return ByteArray(8) { index -> (packed ushr (8 * (7 - index))).toByte() }
        }
    }
}
