package io.github.shaonialife.cardeck.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * iAP2 Bluetooth RFCOMM-мониторинг на стороне мобильного телефона (символ iPhone).
 *
 * После сопряжения iPhone с   True Wireless CarPlay он отслеживает эту службу SDP и ждет обратного подключения автомобиля;
 * DiPlay/xcertplay на испытательном стенде   выполнит поиск сопряженных устройств PHONE_SMART и активно подключится.
 *
 * После подключения  : согласование канала (маркер/синхронизация/ACK) → полное подключение отправителя (Фаза 1),
 * В случае успеха вызывает обратный вызов [onEndpoint] для передачи SSID/пароля точки доступа и конечной точки AirPlay.
 */
class Iap2BluetoothServer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 срабатывает сразу по прибытии (привязка к Wi-Fi + трансляция _carplay-ctrl), что является предварительным условием для настоящего автомобиля 0x4301. */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** После фазы 1, когда автомобильный компьютер повторно выдает 0x4301 (окно CarPlay открывается поздно), он передается конечной точке горячего обновления главного управления. */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** Уведомить мастера, когда канал отправителя отключен (насос поддержания активности отключается) (освободите раунд зомби, см. SessionController.onSenderLinkClosed). */
    private val onLinkClosed: () -> Unit = {},
    /** SSID точки доступа транспортного средства, известный в этом процессе (он будет использоваться, когда в этом раунде не будет новых учетных данных, чтобы избежать отключения соединения через 8 секунд в каждом раунде). */
    private val knownCarSsid: () -> String? = { null },
    /** 0x1D01 Генерация ссылки для определения изображения (обратный вызов после завершения этапа 1, используется для отправки этапа AirPlay). */
    private val onGeneration: (ReceiverProfile.LinkGeneration) -> Unit = {},
    /** Целевой автомобиль (динамическое чтение: отклонять соединения с нецелевых устройств; ноль = все желающие не отклоняются). */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
) {
    companion object {
        /**
         * UUID службы iAP2 для iPhone (опубликовано Oligo Security; с xcertplay
         * CarPlayController.IAP2_IPHONE_UUID соответствует). Аксессуарный конец – …декафф.
         */
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        private const val SDP_NAME = "iAP2"
        private const val SESSION_TIMEOUT_MILLIS = 60_000L
    }

    private var serverSocket: AtomicReference<BluetoothServerSocket?> = AtomicReference(null)

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT: отсутствует разрешение BLUETOOTH_CONNECT.")
            return false
        }
        val adapter =
            try {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT: Bluetooth недоступен или не включен.")
            return false
        }
        return try {
            serverSocket.set(
                adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, IPHONE_IAP2_UUID),
            )
            running = true
            log("БТ: Монитор $SDP_NAME, ожидающий обратного подключения принимающей стороны...")
            Thread(::acceptLoop, "iap2-bt-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IOException) {
            log("BT: Мониторинг не удался — ${error.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        while (running) {
            val socket: BluetoothSocket =
                try {
                    serverSocket.get()?.accept() ?: break
                } catch (error: IOException) {
                    if (running) log("BT: принять не удалось — ${error.message}")
                    break
                }
            val peerName =
                try {
                    socket.remoteDevice?.name
                } catch (_: SecurityException) {
                    null
                } ?: "unknown"
            // Если указан целевой телефон, соединения с других устройств отклоняются (пользователь соединил несколько
            // телефонов: нецелевые телефоны)

            // Контроллер также будет периодически переподключаться, и его освобождение приведет к прерыванию/ошибочному
            // установлению сеанса). Сравнение адресов не чувствительно к регистру.

            val target = targetProvider()
            val peerAddress =
                try {
                    socket.remoteDevice?.address
                } catch (_: SecurityException) {
                    null
                }
            if (target != null && !TargetDevicePrefs.matches(target, peerAddress)) {
                log(
                    "BT: игнорировать соединения с нецелевых устройств name=$peerName addr=${peerAddress ?: "?"}" +
                        "(Текущая цель: «${target.name}»)",
                )
                runCatching { socket.close() }
                continue
            }
            log("BT: Получатель подключился с именем = $peerName, начал сеанс отправителя iAP2.")
            Thread({ runSession(socket) }, "iap2-bt-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runSession(socket: BluetoothSocket) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt") { line -> log(line) }
        activeChannel = channel
        BtSessionGate.inboundActive = true
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                onEndpoint,
                onWifiCredentials,
                onEndpointUpdate,
                onLinkClosed,
                knownCarSsid,
                onGeneration,
            )
        } catch (error: IOException) {
            log("СЕССИЯ не удалась: ${error.message}")
        } finally {
            activeChannel = null
            BtSessionGate.inboundActive = false
            channel.close()
            log("СЕАНС завершается, ожидая повторного подключения принимающей стороны (мониторинг BT все еще работает)")
        }
    }

    /** Активно завершить текущий сеанс управления BT (не влияет на мониторинг). Используется для воспроизведения состояния отключения канала управления перед сопряжением AirPlay. */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT: Текущий сеанс управления был активно отключен (имитируется списание после передачи обслуживания туннеля)")
        }
    }

    fun stop() {
        running = false
        serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
        log("БТ: Слушание прекратилось.")
    }
}
