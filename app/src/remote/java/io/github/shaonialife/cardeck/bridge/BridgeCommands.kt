package io.github.shaonialife.cardeck.bridge

import android.content.Context
import android.content.Intent
import io.github.shaonialife.cardeck.SessionController
import org.json.JSONObject

/**
 * Белый список команд отладки  (**Никогда не отпускайте снаряды/отражения** — радиус взрыва при захвате удаленного канала)
 * ограничивается этими действиями). Все они используют существующие общедоступные API и соответствуют соглашению
 * «оболочка запрета потоков пользовательского интерфейса».
 *
 * требует «подсказку в плавающем окне + автоматический переход к основному интерфейсу» при удаленном
 * запуске/отключении: пользователь, скорее всего, остановится в этот момент.
 * На странице настроек отладки   (страница ввода адреса) вы не можете увидеть ход подключения, не отпрыгивая
 * (отзыв пользователя о фактическом тестировании 2026-10-21).
 */
object BridgeCommands {
    fun handle(
        context: Context?,
        name: String,
        args: JSONObject,
    ): JSONObject {
        val ctx =
            context?.applicationContext
                ?: return fail("Контекст приложения не готов")
        return when (name) {
            "ping" -> JSONObject().put("ok", true).put("detail", "pong ${System.currentTimeMillis()}")

            "get_state" ->
                JSONObject()
                    .put("ok", true)
                    .put(
                        "detail",
                        SessionController.statusLine() +
                            " Session=${if (SessionController.isSessionRunning()) "работает" else "остановлен"}",
                    )

            "start_connection" -> {
                // Это эквивалентно нажатию кнопки «Начать подключение» на мобильном телефоне: если интерфейс (Shizuku/Bluetooth)
                // не удовлетворен, в журнале будет указано объяснение.

                SessionController.ensure(ctx)
                SessionController.startConnection(ctx)
                BridgeOverlay.show(
                    ctx,
                    "Разработчик инициирует подключение - заходим в основной интерфейс, прогресс " +
                        "видим на карточке героя",
                )
                openMainUi(ctx)
                JSONObject().put("ok", true).put("detail", "Соединение инициировано (перешел на основной интерфейс)")
            }

            "stop_connection" -> {
                SessionController.disconnect(ctx)
                BridgeOverlay.show(ctx, "Разработчик отключил телефон")
                JSONObject().put("ok", true).put("detail", "Отключено")
            }

            else -> fail("Неизвестная команда: $name")
        }
    }

    /** Выведите основной интерфейс на передний план (пользователь может находиться на странице настроек отладки и не видеть прогресс подключения).
     * Это приложение освобождено от ограничений на запуск активности в фоновом режиме: разрешение плавающего окна (SYSTEM_ALERT_WINDOW)
     */
    private fun openMainUi(ctx: Context) {
        val intent =
            Intent()
                .setClassName(ctx.packageName, "io.github.shaonialife.cardeck.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { ctx.startActivity(intent) }
    }

    private fun fail(detail: String): JSONObject = JSONObject().put("ok", false).put("detail", detail)
}
