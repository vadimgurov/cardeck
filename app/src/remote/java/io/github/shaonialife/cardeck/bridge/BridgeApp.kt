package io.github.shaonialife.cardeck.bridge

import android.os.Handler
import android.os.Looper
import io.github.shaonialife.cardeck.SenderApp
import io.github.shaonialife.cardeck.ui.ScreenDimmer
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * Приложение для удаленного варианта  (манифест переопределяет android:name).
 *
 * устанавливает перехват журнала и мост отладки в самой ранней точке входа процесса; стандартный вариант не
 * содержит этого файла, поэтому ни о чем не может быть и речи.
 */
class BridgeApp : SenderApp() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // Удаленное устранение неполадок требует просмотра экрана мобильного телефона в реальном времени: в этом
        // варианте доступен автоматический ложный экран/темный экран (черный экран без работы в течение 45 секунд).

        // Всегда отключен, только всегда включен; ручной поддельный экран не затрагивается (запрос пользователя 24 октября 2026 г.).
        ScreenDimmer.autoDimAllowed = false
        DebugFileLog.append(
            this,
            "МОСТ: запущен вариант удаленной отладки " +
                "(${io.github.shaonialife.cardeck.BuildConfig.VERSION_NAME}, автоматический темный экран отключен)",
        )
        // Отключение видно пользователю: плавающая подсказка + обратный отсчет автоматического переподключения в
        // статусной строке страницы настроек (2026-10-22

        // требования пользователя). При восстановлении короткая подсказка закроет окно, а повторяющиеся удары будут
        // естественным образом дедуплицированы с помощью синглтона плавающего окна (новые бомбы перезапишут старые бомбы).

        BridgeClient.connectionEvents = { connected ->
            main.post {
                if (connected) {
                    BridgeOverlay.show(this, "Отладочное соединение восстановлено", dismissMillis = 3_000)
                } else {
                    BridgeOverlay.show(
                        this,
                        "Соединение для отладки было отключено — автоматическое повторное подключение (прогресс смотрите " +
                            "в строке состояния на странице отладки)",
                        dismissMillis = 6_000,
                    )
                }
            }
        }
        // Сначала очистите зависший старый сеанс установки (чтобы предотвратить блокировку последующих установок), а
        // затем решите подключиться/продолжить.

        Bg.run("bridge-clean-sessions") { ReinstallFlow.abandonStaleSessions(this) }

        // Продолжение после переустановки: Установить перед последней установкой → Откройте страницу настроек и появится
        // всплывающее окно «Продолжить вход в режим отладки?»

        // (Обычное всплывающее окно вместо плавающего окна — плавающее окно легко игнорировать, реальные отзывы пользователей)
        if (BridgePrefs.resumePending(this)) {
            DebugFileLog.append(this, "МОСТ: обнаружен флаг перед переустановкой — запрос на продолжение отладки")
            BridgeSetupActivity.startForResume(this)
        } else if (BridgePrefs.autoConnect(this) && BridgePrefs.serverUrl(this).isNotBlank()) {
            Bg.run("bridge-start") { BridgeClient.start(this) }
        }
    }
}
