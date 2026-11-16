package io.github.shaonialife.cardeck.media

import android.content.ComponentName
import android.content.Context
import io.github.shaonialife.cardeck.shizuku.Shell
import java.util.concurrent.atomic.AtomicBoolean

/**
 * уведомляет о запросе и предоставлении прав использования (предварительные требования для источника данных музыкальной карты).
 *
 * **Почему отдельный объект**: Раньше авторизация была только в `MainActivity.onCreate` "Шизуку готова"
 * попробовал только один раз. Один раз неправильный тайминг - Шизуку не запускается после перезагрузки телефона
 * или запускается первый раз после смены названия пакета
 * не авторизовал Шизуку — авторизация никогда не будет продлена. Пользователь видит следующее: «Музыка играет,
 * но на карте отображается мультимедийная информация».
 * «  не подключен» (проверено после изменения имени пакета 18 октября 2026 г.). Теперь измените его на **идемпотентный, повторяемый**:
 * Вы можете позвонить [ensureGranted] в любое время, когда Шизуку будет готова. Если разрешено, вы можете
 * пропустить его напрямую (без запуска оболочки).
 */
object NotificationAccess {
    /** Последний подтвержденный статус авторизации в процессе (служба onListenerConnected также обновит его). */
    @Volatile
    var granted: Boolean = false
        private set

    private val granting = AtomicBoolean(false)

    /** Вызывается обратный вызов при подключении службы: система распознала авторизацию, и нет необходимости запускать обнаружение оболочки. */
    internal fun markGranted() {
        granted = true
    }

    /** Этот компонент прослушивателя приложения (форма `пакет/класс`, обязательный формат `cmd notification allow_listener`). */
    fun componentSpec(context: Context): String = ComponentName(context, MediaListenerService::class.java).flattenToString()

    /**
     * Определяет, находится ли приложение уже в списке авторизации из строки системных настроек.
     *
     * Система использует `:` для объединения нескольких прослушивателей в строку (`пакет/класс:пакет/класс:…`).
     * **следует точно сравнивать по записям** — использование `contains(имя пакета)` приведет к неправильной оценке
     * (когда имена других приложений содержат тот же префикс).
     */
    internal fun isGrantedIn(
        enabledListeners: String?,
        componentSpec: String,
    ): Boolean =
        enabledListeners
            ?.split(':')
            ?.any { it.trim().equals(componentSpec, ignoreCase = true) } == true

    /**
     * Убедитесь, что предоставлены права на использование уведомлений (идемпотентные, повторяемые; **фоновый вызов
     * потока**, с внутренним циклическим обходом оболочки).
     *
     * @return true = авторизовано (изначально или на этот раз предоставлено успешно)
     */
    fun ensureGranted(
        context: Context,
        log: (String) -> Unit,
    ): Boolean {
        val spec = componentSpec(context)
        val current =
            Shell
                .exec("settings get secure enabled_notification_listeners", timeoutMs = 4_000)
                .output
        if (isGrantedIn(current, spec)) {
            granted = true
            // «В списке авторизации» не означает «система привязала прослушиватель»: элементы списка остаются там после переустановки APK.
            // Однако привязка на стороне службы уведомлений может не быть установлена (фактическое тестирование 19 октября
            // 2026 г.: Live прослушиватели не имеют

            // Для этого компонента метаданные всегда имеют значение null, и на карте всегда отображается сообщение
            // «Невозможно прочитать информацию о воспроизведении»;

            // Вручную запретить+разрешить немедленное восстановление). Вот самовосстанавливающаяся перепривязка.
            rebindIfNotConnected(context, spec, log)
            return true
        }
        if (!granting.compareAndSet(false, true)) return granted
        try {
            val result = Shell.exec("cmd notification allow_listener $spec", timeoutMs = 4_000)
            // Команда exitCode не может использоваться в качестве критерия (некоторые ПЗУ также возвращают не 0 в случае успеха);
            // **Немедленный** просмотр также ненадежен: происходит задержка при записи настроек и после успешного выполнения
            // фактической тестовой команды.

            // Немедленное чтение по-прежнему является старым значением (18 октября 2026 г., реальная машина: результат cmd =
            // 0, но проверка показала, что оно не было предоставлено).

            // Поэтому нажмите «Короткая повторная попытка» для подтверждения, и если все не удастся, это будет расценено как неудачное.
            var ok = false
            for (attempt in 0 until 4) {
                if (attempt > 0) Thread.sleep(250)
                val after =
                    Shell
                        .exec("settings get secure enabled_notification_listeners", timeoutMs = 4_000)
                        .output
                if (isGrantedIn(after, spec)) {
                    ok = true
                    break
                }
            }
            granted = ok
            log(
                if (ok) {
                    "МЕДИА: уведомите, что право на использование предоставлено (музыкальная карта может отображать " +
                        "название песни/статус воспроизведения)"
                } else {
                    "МЕДИА: Уведомление о том, что права на использование не предоставлены (cmd result=${result.exitCode}) ——" +
                        "Музыкальная карта отображает только статус воспроизведения и не отображает название песни; его " +
                        "можно включить вручную в настройках системы «Права на уведомления»"
                },
            )
            return ok
        } finally {
            granting.set(false)
        }
    }

    /**
     * Если  авторизован, но прослушиватель фактически не подключен, используйте disallow +allow, чтобы запустить перепривязку системы.
     *
     * Критерий       взят из `Live notification listeners` из `dumpsys notification`: только если он появляется в
     * В этом разделе можно вызвать [MediaListenerService.onListenerConnected] и прочитать `metadata`.
     * Этот шаг происходит только изредка после «выхода из приложения на долгое время/после переустановки». Стоимость
     * - это поездка туда и обратно, что приемлемо.
     */
    private fun rebindIfNotConnected(
        context: Context,
        spec: String,
        log: (String) -> Unit,
    ) {
        // Если служба уже подключена (установлен onListenerConnected), нет необходимости ее обнаруживать, что позволяет
        // избежать запуска dumpsys каждый раз при установлении сеанса.

        if (serviceConnected) return
        if (!rebinding.compareAndSet(false, true)) return
        try {
            val live = Shell.exec("dumpsys notification --noredact", timeoutMs = 6_000).output
            if (isLiveIn(live, spec)) {
                serviceConnected = true
                return
            }
            log("МЕДИА: права на использование уведомлений есть в списке, но прослушиватель не привязан – инициируется перепривязка.")
            Shell.exec("cmd notification disallow_listener $spec", timeoutMs = 4_000)
            Thread.sleep(300)
            Shell.exec("cmd notification allow_listener $spec", timeoutMs = 4_000)
            // Привязка является асинхронной: короткое подтверждение опроса, чтобы предотвратить дальнейшее сохранение
            // статуса «разрешено, но нечитаемо» нисходящему потоку.

            for (attempt in 0 until 6) {
                Thread.sleep(400)
                val after = Shell.exec("dumpsys notification --noredact", timeoutMs = 6_000).output
                if (isLiveIn(after, spec)) {
                    serviceConnected = true
                    log("МЕДИА: восстановление прослушивателя (метаданные музыкальной карты восстановлены)")
                    return
                }
            }
            log(
                "МЕДИА: ⚠ Монитор после перепривязки по-прежнему не подключен - музыкальная карта может показывать " +
                    "только статус воспроизведения",
            )
        } catch (_: Exception) {
            // Ограничение оболочки/тайм-аут: не влияет на основной процесс, повторите попытку в следующем сеансе.
        } finally {
            rebinding.set(false)
        }
    }

    /**
     * определяет, находится ли этот компонент в разделе Live Listeners на основе вывода `dumpsys notification`.
     * Надо только прочитать этот раздел - может еще есть этот компонент во "Всех слушателях уведомлений" (авторизация еще есть),
     * Но что действительно определяет возможность чтения метаданных, так это сегмент Live.
     */
    internal fun isLiveIn(
        dump: String,
        componentSpec: String,
    ): Boolean {
        val start = dump.indexOf("Live notification listeners")
        if (start < 0) return false
        val section = dump.substring(start).substringBefore("Snoozed notification listeners")
        return section.contains(componentSpec, ignoreCase = true)
    }

    /** Устанавливается при подключении услуги (вызов [MediaListenerService.onListenerConnected]). */
    @Volatile
    private var serviceConnected: Boolean = false

    internal fun markConnected() {
        serviceConnected = true
    }

    internal fun markDisconnected() {
        serviceConnected = false
    }

    private val rebinding = AtomicBoolean(false)
}
