package io.github.shaonialife.cardeck.net

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.Socket

/**
 * Проверка работоспособности TCP-узла CarPlay при длительном соединении (по сравнению с DiPlay `TcpLiveness`, версия 21 октября 2026 г.).
 *
 * Почему это необходимо указать явно: время активности ядра Android по умолчанию составляет **два часа** до
 * начала обнаружения. Двигатель глохнет /
 * Когда мобильный телефон выходит из зоны действия точки доступа, ссылка часто не отправляет RST (Wi-Fi
 * пропадает напрямую), поэтому поток чтения канала управления зависает
 * На   `read()` пока не истечет системный тайминг - экран зависает, сторожевой таймер не срабатывает, и
 * пользователь может только вручную отключиться и снова подключиться.
 *
 * Значение   соответствует DiPlay: обнаружение начинается после 10 секунд простоя, один раз каждые 3 секунды, и
 * если нет ответа в течение 3 раз подряд, узел определяется как неработающий.
 * (всего ~19 секунд), а затем используйте `TCP_USER_TIMEOUT`, чтобы установить верхний предел в 20 секунд для
 * «отправленных неподтвержденных данных».
 * 19s намеренно больше, чем сторожевой таймер AirPlay 15s в автомобиле, чтобы мы не могли завершить обычный сеанс раньше, чем автомобиль.
 *
 * **Разница API (перечеркнута)**: `ParcelFileDescriptor.fromSocket` возвращается до API 29
 * сокет **сам** в упаковке, закрытие/завершение закроет исходный сокет вместе. Так что используйте его только в том случае, если вам 29+.
 * берет копию; 26~28 заменяются на отражение. `Socket.getFileDescriptor$()` напрямую устанавливает исходный fd (этот метод находится в
 * Android 9 относится к светлому серому списку, можно вызывать любой targetSdk); API 25 и ниже не являются общедоступными
 * `setsockoptInt`, записывайте только одну строку журнала.
 */
object TcpLiveness {
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6

    /** То же значение по умолчанию, что и у DiPlay (секунды/отсчеты/мс). */
    private const val KEEP_IDLE_SECONDS = 10
    private const val KEEP_INTERVAL_SECONDS = 3
    private const val KEEP_COUNT = 3
    private const val USER_TIMEOUT_MILLIS = 20_000

    /**
     * открывает и настраивает проверки активности для долгоживущих сокетов. Если не получается, просто сохраните
     * журнал и не выбрасывайте его – машине он может не понадобиться.
     * Но соединение не должно блокироваться из-за неудачной настройки.
     */
    fun configure(
        socket: Socket,
        label: String,
        log: (String) -> Unit,
    ) {
        runCatching { socket.keepAlive = true }
        if (Build.VERSION.SDK_INT < 26) {
            log("TCP: $label Невозможно настроить поддержку активности (API ${Build.VERSION.SDK_INT} <26).")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ParcelFileDescriptor.fromSocket(socket).use { duplicate ->
                    tune(duplicate.fileDescriptor)
                }
            } else {
                val fd =
                    socket.javaClass
                        .getMethod("getFileDescriptor\$")
                        .invoke(socket) as FileDescriptor
                tune(fd)
            }
            log(
                "TCP: поддержка активности $label включена" +
                    "（idle=${KEEP_IDLE_SECONDS}s interval=${KEEP_INTERVAL_SECONDS}s count=$KEEP_COUNT" +
                    " userTimeout=${USER_TIMEOUT_MILLIS}ms）",
            )
        } catch (error: Exception) {
            log("TCP: настройка поддержки активности $label не удалась (${error.javaClass.simpleName}: ${error.message})")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun tune(fd: FileDescriptor) {
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, KEEP_IDLE_SECONDS)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, KEEP_INTERVAL_SECONDS)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, KEEP_COUNT)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, USER_TIMEOUT_MILLIS)
    }
}
