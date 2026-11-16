package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context
import android.os.Build
import io.github.shaonialife.cardeck.shizuku.Shell
import java.util.concurrent.Executors

/**
 * Единственный порт управления, совместимый с  «принудительным горизонтальным экраном приложения»:
 * — пользовательские настройки сохраняются в этом приложении SharedPreferences (переключение страниц для чтения и записи);
 * — переопределения на стороне системы (совместимые, постоянные) сохраняются только на время сеанса:
 * [applyAllAsync] записывается приложением за приложением при установке сеанса.
 * унифицированное восстановление после завершения сеанса (отключение/принудительное завершение) [resetAllAsync]——
 * предотвращает влияние остатков покрытия на нормальное использование приложения на вертикальном экране на стороне мобильного телефона.
 *
 * **должен выполняться вне критического пути соединения**: оба `am compat` для каждого приложения являются циклическими обходами оболочки,
 * список 6 приложений показал время ~1,6–2,0 с. Раньше восстановление запускалось синхронно перед сопряжением и
 * записывалось в аттаче виртуального экрана.
 * работает синхронно, напрямую увеличивая время подключения Audi примерно на 3,5 с (фактическое измерение 18
 * октября 2026 г.). Оба используют один и тот же поток
 * Сериализация исполнителя   обеспечивает порядок «сначала восстановить предыдущий сеанс, затем записать в этот
 * сеанс» (межпоточный параллелизм перезапишет друг друга).
 *
 * Эффективная комбинация   (фактическое испытание Toutiao/Douyin):
 * OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR + OVERRIDE_ANY_ORIENTATION
 */
object AppOrientationCompat {
    const val CHANGE_UNDEFINED_TO_NOSENSOR = 265451093
    const val CHANGE_ANY_ORIENTATION = 265464455

    /**
     * поддерживает минимальный SDK для принудительного горизонтального экрана (Android 14). Эти два ChangeId
     * являются стандартными элементами AOSP.
     * определен в `android.content.pm.ActivityInfo` начиная с Android 14.
     * (`@ChangeId @Disabled @Overridable`), потребляемый `LetterboxUiController`;
     * Android 13 и более ранних версий вообще не имеют класса ориентации ChangeId.
     */
    const val MIN_SUPPORTED_SDK = 34
    private const val PREFS = "app_orientation"

    /** Последовательный исполнитель: Восстановление и запись выполняются строго в порядке вызовов и не занимают поток соединения/UI. */
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orientation-compat").apply { isDaemon = true }
        }

    /** Доступ к привилегированным ключам осуществляется единообразно по «имени пакета» (am compat также нацелен на имя пакета; сторона чтения представляет собой унифицированный метод RemovePrefix и совместима со старыми ключами). */
    private fun key(pkg: String) = "force_$pkg"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(
        context: Context,
        pkg: String,
    ): Boolean = prefs(context).getBoolean(key(pkg), false)

    /**
     * Результат записи на стороне системы. Различайте «постоянный неподдерживаемый» и «временный сбой» — оба
     * обрабатывают предпочтения противоположными способами:
     * постоянно не поддерживается и не следует оставлять настройки (иначе следующее подключение будет бесполезным и
     * дисплей панели не будет соответствовать состоянию системы).
     * Временный сбой должен сохранять пожелания пользователя (следующее соединение/повторная попытка все еще будет
     * иметь шанс вступить в силу).
     */
    enum class WriteResult {
        /** Оба ChangeId были записаны успешно. */
        OK,

        /**
         * Нативная платформа  **платформа не имеет такой возможности**, и повторная попытка никогда не будет успешной.
         * Вы можете напрямую определить версию SDK:
         *
         * **Эти два ChangeIds являются стандартными элементами AOSP, представленными начиная с Android 14 (SDK 34)**
         * (`android.content.pm.ActivityInfo` с `@ChangeId @Disabled @Overridable`),
         * вступает в силу в `LetterboxUiController`; реорганизован в
         * `AppCompatOrientationOverrides`. Android 13 и более ранние версии** — ознакомительный курс.
         * ChangeId не имеет ** (фактически измеренные значения равны 0 в ActivityInfo для A13 и 9 в A14).
         *
         * Поэтому причины, по которым данная машина его не поддерживает, упорядочены по вероятности:
         * 1. **Android 13 и более ранние версии** (SDK < 34): платформа вообще не определена → `dumpsys
         * Platform_compat` внутриНеттот ChangeId из name，`am compat` Сначала предупреждение
         * `Change ... is not known yet`. Пример: Redmi K40 (Android 13/SDK 33).
         * 2. **OEM отрезал эти ChangeId**: Теоретически они есть у A14+, но некоторые ПЗУ могут их удалить;
         * Критерии для           такие же, как указано выше (dumpsys не имеет названия).
         * 3. **пользовательская сборка + неотлаживаемое приложение**: даже если ChangeId зарегистрирован,
         * `PlatformCompat` также разрешено изменять только отлаживаемые приложения и выходные данные.
         * `Cannot override a change on a non-debuggable app and user build.`
         *
         * не является проблемой Shizuku (оболочка и UserService имеют uid 2000, и результаты совпадают).
         * Практические рекомендации для пользователей          : Обновите модель до Android 14+.
         */
        UNSUPPORTED,

        /** Временный сбой (Шизуку не готов/тайм-аут/исключение команды) — можно повторить попытку. */
        FAILED,
    }

    /**
     * **Асинхронная оболочка** пользовательского переключателя: единственный вход в поток пользовательского интерфейса.
     *
     * `setEnabled` внутренне 2 раза `Shell.exec` (таймаут по 4 с), самое худшее - 8 с - ставьте прямо
     * Обратный вызов щелчка флажка       значительно превысит порог ANR (фиксированный в том же пакете 18 октября
     * 2026 г.). Измените доставку здесь на
     * последовательный исполнитель, результат возвращается обратному вызову основного потока; **серийный номер
     * исполнителя** гарантирует, что одно и то же приложение будет щелкаться постоянно
     * записывают/восстанавливают в порядке щелчков и не перезаписывают друг друга.
     */
    fun setEnabledAsync(
        context: Context,
        pkg: String,
        enable: Boolean,
        onResult: (WriteResult) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { setEnabled(app, pkg, enable) }.getOrElse { WriteResult.FAILED }
            android.os
                .Handler(android.os.Looper.getMainLooper())
                .post { onResult(result) }
        }
    }

    /**
     * Пользовательский переключатель: запись переопределения системы (для страницы настроек). **Предпочтение приходит после успеха**.
     *
     * **может вызываться только в фоновом потоке** (включает 2 внутренних обращения к оболочке, в худшем случае 8
     * с). Пользовательский интерфейс использует [setEnabledAsync].
     *
     * Классификацию отказов       см. в [WriteResult]: `UNSUPPORTED`, когда предпочтение остается прежним
     * (пользователь не желает размещать заказ,
     * , потому что размещение диска приведет к тому, что следующее соединение будет запускать applyAll напрасно);
     * `FAILED` сохраняет пожелания пользователя.
     */
    fun setEnabled(
        context: Context,
        pkg: String,
        enable: Boolean,
    ): WriteResult {
        val action = if (enable) "enable" else "disable"
        val first = Shell.exec("am compat $action $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
        val second =
            if (first.ok) {
                Shell.exec("am compat $action $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            } else {
                // Если первый отклонен, не пишите второй (то же ограничение, за исключением ненужного обращения к оболочке туда и обратно)
                first
            }
        if (first.ok && second.ok) {
            prefs(context).edit().putBoolean(key(pkg), enable).apply()
            return WriteResult.OK
        }
        val unsupported = isPermanentRejection(first.output) || isPermanentRejection(second.output)
        if (!unsupported) {
            // Временный сбой: сохраните пожелания пользователя, ApplyAll в следующий раз повторит попытку.
            prefs(context).edit().putBoolean(key(pkg), enable).apply()
        }
        // Диагностика должна позволять различать две постоянные причины (ChangeId не зарегистрирован/пользовательская
        // сборка не подлежит отладке)

        diagSink?.invoke(
            "ORIENTATION: compat $action " +
                (if (unsupported) "Эта машина не поддерживает" else "не удалось") +
                " pkg=$pkg change=$CHANGE_UNDEFINED_TO_NOSENSOR exit=${first.exitCode} out=${first.output.take(160)}" +
                if (unsupported) "（${describeUnsupported()}）" else "",
        )
        return if (unsupported) WriteResult.UNSUPPORTED else WriteResult.FAILED
    }

    /**
     * Имеет ли  изначально такую возможность** (факт платформы).
     *
     * Решение       разделено на два этапа, сочетающих в себе быстрое и медленное:
     * 1. **Порог версии SDK** (нулевая стоимость): эти два ChangeId являются стандартными элементами AOSP.
     * Определен в `android.content.pm.ActivityInfo` из **Android 14/SDK 34**
     * (`@ChangeId @Disabled @Overridable`, потребляется `LetterboxUiController`;
     * реорганизован в `AppCompatOrientationOverrides`, начиная с Android 16).
     * В ActivityInfo Android 13 и более ранних версий нет класса ориентации ChangeId**
     * (фактически измерено A13=0, A14=9) → Он сразу оценивается как не поддерживаемый, и нет необходимости запускать оболочку.
     * 2. **просмотр дампсиса** (запускается только при SDK ≥ 34): убедитесь, что ПЗУ действительно регистрирует эти два идентификатора.
     * (может быть отрезан отдельными производителями). Зарегистрированные записи имеют `name=...`,
     * незарегистрированные записи имеют только голый
     * `ChangeId(...; rawOverrides={...})`.
     *
     * Результаты       кэшируются в процессе (не изменяются в рамках одной установки).
     */
    fun changeIdsRegistered(): Boolean {
        cachedRegistered?.let { return it }
        // 1) Порог версии: платформы ниже Android 14 вообще не имеют этих ChangeIds.
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK) {
            cachedRegistered = false
            return false
        }
        // 2) Обзор Dumpsys: возьмите два идентификатора отдельно для проверки — вы не можете проверить весь вывод один раз `name=`
        //    (Когда зарегистрирован только один из них, будет ошибочно считать, что оба доступны, и тогда вторая запись не удастся).
        val first = changeIdLine(CHANGE_UNDEFINED_TO_NOSENSOR)
        val second = changeIdLine(CHANGE_ANY_ORIENTATION)
        val value = first.contains("name=") && second.contains("name=")
        // Если dumpsys недоступен (команда не выполнена), он не будет кэшироваться и его можно будет повторить в следующий раз.
        if (first.isNotBlank() || second.isNotBlank()) cachedRegistered = value
        return value
    }

    /** Получите строку с определенным ChangeId в `dumpsys platform_compat` (она может быть пустой, если она не зарегистрирована). */
    private fun changeIdLine(changeId: Int): String =
        Shell
            .exec(
                "dumpsys platform_compat | grep -F 'ChangeId($changeId;' | head -1",
                timeoutMs = 6_000L,
            ).output

    @Volatile
    private var cachedRegistered: Boolean? = null

    /** Конкретная причина постоянного сбоя (записывается в журнал для облегчения устранения неполадок между моделями). */
    private fun describeUnsupported(): String =
        when {
            Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK ->
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ниже 14" +
                    "——Эти два ChangeId появились на платформе только начиная с Android 14."

            !changeIdsRegistered() ->
                "SDK ${Build.VERSION.SDK_INT}, но ПЗУ не регистрирует эти два ChangeId (подозрение на обрезку OEM)"

            else ->
                "ChangeId зарегистрирован, но пользовательская сборка не позволяет изменять неотлаживаемые приложения."
        }

    /**
     * Является ли вывод команды «постоянным сбоем» (невозможно повторить попытку).
     *
     * охватывает два типа формулировок: когда ChangeId не зарегистрирован, `am compat` сначала предупреждает `is not
     * known yet`, а затем отклоняет;
     * дает `non-debuggable app and user build` при регистрации, но ограничен пользовательской сборкой.
     * Оба не имеют никакого отношения к Шизуку, повторная попытка не имеет никакого эффекта.
     */
    private fun isPermanentRejection(output: String): Boolean = classifyCompatFailure(output) == WriteResult.UNSUPPORTED

    /** Экспорт журнала диагностики (SessionController.appendLog получает файл журнала). */
    @Volatile
    var diagSink: ((String) -> Unit)? = null

    /** Асинхронная версия [applyAll]: вызов критического пути соединения (журнал будет вызван после завершения для упрощения устранения неполадок). */
    fun applyAllAsync(
        context: Context,
        log: (String) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val count = runCatching { applyAll(app) }.getOrDefault(0)
            if (count > 0) {
                log(
                    "ОРИЕНТАЦИЯ: для приложений $count включена принудительная альбомная ориентация " +
                        "(эффективно во время сеанса).",
                )
            }
        }
    }

    /** Асинхронная версия [resetAll]: вызывается при завершении сеанса и не блокирует последующее сопряжение. */
    fun resetAllAsync(
        context: Context,
        log: (String) -> Unit,
    ) {
        val app = context.applicationContext
        executor.execute {
            val count = runCatching { resetAll(app) }.getOrDefault(0)
            if (count > 0) log("ОРИЕНТАЦИЯ: восстановлены настройки отображения для приложений $count.")
        }
    }

    /**
     * Дождитесь завершения очереди перезаписи/восстановления (до [timeoutMs]).
     *
     * **Необходимо вызывать перед запуском приложения**: `am compat enable` немедленно завершит целевое приложение.
     * Перезаписать запись изменена на
     * После асинхронного режима      , если пользователь щелкнет значок на рабочем столе до завершения записи,
     * появится сообщение «приложение было перезаписано и закрыто сразу после запуска».
     * Исполнитель       представляет собой однопоточный FIFO. Создание пустой задачи и ожидание ее завершения эквивалентно сливу.
     *
     * @return Будет ли он опорожнен в течение установленного срока; false = тайм-аут (вызывающая сторона должна
     * оставить следы — тихий сбой приведет к
     * Гарантия       «сначала перезаписать, а затем извлечь приложение» незаметно истекла, а приложение все равно
     * было убито, проверено и определено 23 октября 2026 г.).
     */
    fun awaitPending(timeoutMs: Long = 6_000L): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute { done.countDown() }
        return try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Установление сеанса: покрытие по записи, применяемое внутри списка (идемпотентное). Возвращает количество успешно написанных приложений. */
    fun applyAll(context: Context): Int {
        val pkgs = enabledPkgs(context)
        if (pkgs.isEmpty()) return 0
        // Сначала выполните определение возможностей платформы: когда ПЗУ не регистрирует эти два ChangeId (фактический
        // тест на Redmi Android 13),

        // Каждому приложению в списке приходится тщетно запускать два цикла обработки оболочки с тайм-аутом в 4 секунды.
        // Один дампсис может определить,

        // Стоимость составляет около 30 мс, а результаты кэшируются в процессе.
        if (!changeIdsRegistered()) {
            diagSink?.invoke(
                "ОРИЕНТАЦИЯ: Родное ПЗУ не зарегистрировано и совместимо с горизонтальным экраном. ChangeId — на этот " +
                    "раз пропустите все покрытия." +
                    "(приложений: ${pkgs.size}; принудительная альбомная ориентация не поддерживается)",
            )
            return 0
        }
        var count = 0
        for (pkg in pkgs) {
            val first = Shell.exec("am compat enable $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
            if (!first.ok) {
                // ChangeId зарегистрирован, но по-прежнему отклонен → пользовательской сборке не разрешено изменять
                // неотлаживаемые приложения;

                // Он также останавливается целиком и не запускается отдельно для каждого приложения.
                if (isPermanentRejection(first.output)) {
                    diagSink?.invoke(
                        "ОРИЕНТАЦИЯ: Системное ограничение - пользовательская версия позволяет модифицировать только " +
                            "отлаживаемые приложения." +
                            "Совместимые настройки, на этот раз пропустите все горизонтальное покрытие экрана (список " +
                            "приложений ${pkgs.size})",
                    )
                    return 0
                }
                continue
            }
            val second = Shell.exec("am compat enable $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            if (second.ok) count++
        }
        return count
    }

    /** Конец сеанса: Приложения в списке восстанавливаются единообразно (предпочтения сохраняются, отменяются только системные переопределения). Возвращает количество обработанных заявок. */
    fun resetAll(context: Context): Int {
        var count = 0
        enabledPkgs(context).forEach { pkg ->
            Shell.exec("am compat disable $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
            Shell.exec("am compat disable $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            count++
        }
        return count
    }

    /**
     * Набор «ключей переопределения», которые в данный момент переопределяются на стороне системы (источник; ключ
     * может быть именем пакета или
     * `пакет/компоненты` строка в исторических грязных данных).
     *
     * Файл настроек не может прочитать «фактическое состояние системы»: **При удалении настройки будут удалены, но
     * перезапись на стороне системы сохранит их**
     * (Фактическое тестирование 16 октября 2026 г.: на устройстве имеются три остатка Feishu/Dianping/Honor Cloud
     * Music). Запрос может идти только
     * `dumpsys platform_compat` (в этом ПЗУ нет `cmd platform_compat`), фактический измеренный полный размер составляет 30 мс/36 КБ,
     * занимает около 3 КБ после фильтрации двух интересующих ChangeId — **Проверьте один раз при открытии панели**,
     * привязка строк выполняет только поиск в таблице памяти.
     * никогда не следует использовать один раз на строку (40+ приложений × ~100 мс = задержка).
     *
     * **Необходимо разрешить как packageOverrides, так и rawOverrides**: переопределения для неустановленных приложений существуют только в
     * rawOverrides — packageOverrides — это сопоставление после того, как платформа анализирует «установленный
     * пакет», и запись — это когда пакет не существует.
     * исчез (фактическое тестирование 16 октября 2026 г.: переопределения, записанные для неустановленных пакетов,
     * видны только в rawOverrides). «Другие системы
     * Сводка       «Остаток» предназначена для удаления остатков. Если страница отсутствует, баннер останется пустым навсегда.
     *
     * ИЛИ двух ChangeIds: полуотключенное состояние только одного из них также считается "открытым" (при очистке оба
     * будут записаны вместе).
     */
    fun systemEnabled(): Set<String> {
        val out =
            Shell
                .exec(
                    "dumpsys platform_compat | grep -E 'ChangeId\\(${CHANGE_UNDEFINED_TO_NOSENSOR};|ChangeId\\(${CHANGE_ANY_ORIENTATION};'",
                    timeoutMs = 6_000L,
                ).output
        if (out.isBlank()) return emptySet()
        return out
            .lineSequence()
            .flatMap { line ->
                // packageOverrides={com.a=true, ...}; в rawOverrides также могут быть ключи пакета/компонента
                Regex("(?:packageOverrides|rawOverrides)=\\{([^}]*)\\}")
                    .findAll(line)
                    .flatMap { m ->
                        // Ключи допускают использование нескольких печатаемых символов (ключи компонентов содержат /); только возьми =true
                        Regex("([^=,{}\\s]+)=true").findAll(m.groupValues[1]).map { it.groupValues[1] }
                    }
            }.toSet()
    }

    /**
     * указывает, действительно ли включено покрытие указанного пакета на стороне системы (запускает предварительное
     * условие для запроса почтового ящика).
     *
     * Простого просмотра настроек недостаточно: `am compat` Настройки будут сохранены (пожелания пользователя) при
     * возникновении временного сбоя, но на стороне системы нет защиты.
     * В настоящее время запрос «принудительный перезапуск» недействителен. Зонд запускается только один раз в
     * приложении-кандидате, стоимость которого составляет ≈ один grep.
     */
    fun isSystemEnabledFor(pkg: String): Boolean = pkg in systemEnabled()

    /**
     * отменяет переопределение на стороне системы одного «ключа переопределения» (отключение записи в обоих ChangeId).
     * перемещает только системное наложение, **не перемещает настройки** — используется для очистки записей, в
     * которых «предпочтение больше не существует (остатки удаления), но наложение все еще включено»;
     * Для приложений из списка       используйте [setEnabled] (предпочтения будут синхронизированы, чтобы
     * предотвратить отключение пользовательского интерфейса от состояния системы).
     * Примечание: отключение am compat немедленно завершит процесс целевого приложения (новые настройки должны быть
     * перезапущены, чтобы они вступили в силу).
     */
    fun clearSystemOnly(key: String): Boolean {
        val ok1 = Shell.exec("am compat disable $CHANGE_UNDEFINED_TO_NOSENSOR $key", timeoutMs = 4000).ok
        val ok2 = Shell.exec("am compat disable $CHANGE_ANY_ORIENTATION $key", timeoutMs = 4000).ok
        return ok1 && ok2
    }

    private fun enabledPkgs(context: Context): List<String> =
        prefs(context)
            .all
            .filterValues { it == true }
            .keys
            .map { it.removePrefix("force_") }

    /** Список «Горизонтальный экран приложения» (коллекция имен пакетов): используется для обнаружения подсказок почтового ящика, только для чтения. */
    fun enabledPackages(context: Context): Set<String> = enabledPkgs(context).toSet()
}

/**
 * классифицирует выходной сигнал отказа `am compat` (чистая функция, поддающаяся единичному тестированию).
 *
 * считается **постоянным сбоем** по трем причинам (ни один из них не имеет никакого отношения к Шизуку, повторная попытка недействительна):
 * 1. `Cannot override a change on a non-debuggable app and user build.`
 * 2. `Cannot override a default enabled/disabled change on a user build.`
 * 3. `Change <id> is not known yet.` — — Локальное ПЗУ не регистрирует ChangeId
 * (Эти два идентификатора настроены OEM и не входят в стандартную таблицу AOSP; они отсутствуют в реальном тесте Redmi Android 13)
 *
 * Фактический измеренный выход   (18 октября 2026 г.):
 * - Redmi (не зарегистрирован):
 * `Warning! Change 265451093 is not known yet. Enabling/disabling it could have no effect.`
 * `Cannot override a change on a non-debuggable app and user build.`
 * — Honor (зарегистрировано, но аналогичное отклонение при нацеливании на неотлаживаемое приложение): то же, что
 * и вторая формулировка выше.
 * — Шизуку не подключен: `Шизуку не на связи` (временно, можно повторить попытку)
 */
internal fun classifyCompatFailure(output: String): AppOrientationCompat.WriteResult =
    if (output.contains("non-debuggable app and user build") ||
        output.contains("default enabled/disabled change on a user build") ||
        output.contains("is not known yet")
    ) {
        AppOrientationCompat.WriteResult.UNSUPPORTED
    } else {
        AppOrientationCompat.WriteResult.FAILED
    }
