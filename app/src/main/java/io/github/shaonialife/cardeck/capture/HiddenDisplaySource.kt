package io.github.shaonialife.cardeck.capture

import android.content.Context
import android.view.Surface
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.ShizukuGate

/**
 * Режим невидимого экрана : используйте обычный API для создания ПУБЛИЧНОГО виртуального экрана с тем же
 * разрешением, что и у автомобиля (поверхность кодирует ввод),
 * через Shizuku(shell) `am start --display` Запускаем приложение; Главный экран телефона можно отключить в обычном режиме.
 *
 * отдает приоритет цели запуска «Версия автомобиля Amap» (отображается на горизонтальном экране; его загрузочная
 * страница потеряет --display, необходимо запустить явно
 * MainMapActivity и принудительно полноэкранный режим), резервная мобильная версия Amap не устанавливается.
 * (перенесено из VirtualDisplaySource эталонного проекта android-auto)
 */
class HiddenDisplaySource(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : ScreenSource {
    override val name: String = "Невидимый экран"

    /** Совместимое поле: указывает на displayId экрана приложения в двухэкранной архитектуре. */
    var displayId: Int = -1
        private set

    override fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean {
        detach()
        // Диагностика по штатам: Shizuku «включено» ≠ Это приложение авторизовано (необходимо один раз нажать на
        // авторизацию сервера во всплывающем окне)

        when (val gate = ShizukuGate.state(context)) {
            ShizukuGate.State.READY -> Unit

            ShizukuGate.State.NOT_INSTALLED -> {
                log("SHIZUKU: Приложение Shizuku (shizuku.rikka.app) не установлено.")
                return false
            }

            ShizukuGate.State.NOT_RUNNING -> {
                log(
                    "SHIZUKU: Служба не запущена - откройте приложение Shizuku и нажмите «Пуск» (перезапустите после " +
                        "перезагрузки телефона)",
                )
                return false
            }

            ShizukuGate.State.UNAUTHORIZED -> {
                log(
                    "SHIZUKU: Это приложение не авторизовано — инициирован запрос на авторизацию, нажмите «Разрешить» во " +
                        "всплывающем окне и переподключитесь.",
                )
                ShizukuGate.requestPermission()
                return false
            }
        }
        // Прежде чем создавать экран, подождите, пока UserService действительно не будет готов: сеанс присоединяется
        // сразу после RECORD, и сервис все еще может присутствовать.

        // spawn (декс загрузки отладочного пакета происходит медленнее). Немедленное определение «недоступно» приведет к
        // бесполезному возврату к ПУБЛИЧНОМУ экрану приложения.

        // ——Этот путь в большей степени зависит от UserService и остается полностью нетронутым до запуска службы (22 октября 2026 г. K60E).
        // Этот метод был выполнен в фоновом потоке (обратный вызов VideoSender.start), и его можно безопасно заблокировать и подождать.
        val svc = CarInputClient.awaitReady(context, USER_SERVICE_WAIT_MS)
        if (svc == null) {
            log(
                "ШИЗУКУ: UserService не готов в ${USER_SERVICE_WAIT_MS}ms" +
                    "—— Вернется к ПУБЛИЧНОМУ экрану на стороне приложения (сенсорный экран может быть недоступен, " +
                    "проверьте, не заморожен ли Shizuku системой)",
            )
        }
        // Панель запуска с двумя экранами: композитор GL (экран док-станции + экран приложения) → Coding Surface
        val ok =
            VirtualLauncher.attach(
                context,
                surface,
                width,
                height,
                densityDpi,
                log,
            )
        displayId =
            if (ok) VirtualLauncher.appDisplayId else -1
        return ok
    }

    override fun detach() {
        VirtualLauncher
            .detach()
        displayId = -1
    }

    override fun release() = detach()

    private companion object {
        /**
         * Верхний предел готовности UserService, например . Компромисс по стоимости: если он слишком короткий, он будет
         * ошибочно оценен как «недоступный» и вернется на общедоступный экран (теряя связь).
         * слишком долго будет задерживать первый кадр. НАСТРОЙКА потока экрана со стороны автомобиля → активация есть
         * сторожевой таймер, но ЗАПИСЬ завершена,
         * Порт видеоданных           также подключен. Ожидание в течение 3 секунд не повлияет на выживание сеанса.
         */
        const val USER_SERVICE_WAIT_MS = 3_000L
    }
}
