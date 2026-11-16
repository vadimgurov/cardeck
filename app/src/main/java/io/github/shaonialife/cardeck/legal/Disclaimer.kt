package io.github.shaonialife.cardeck.legal

import android.content.Context

/**
 * Статус принятия отказа от ответственности и правила выпуска.
 *
 * записывается с **номером версии** вместо логического значения: после совместного использования копия неизбежно
 * будет повторяться (новые тестовые модели, дополнительные условия),
 * Добавьте [CURRENT_VERSION] +1, чтобы старые пользователи могли повторно подтвердить свой запрос; логическое
 * значение можно отобразить снова только после удаления и повторной установки.
 */
object DisclaimerPrefs {
    /**
     * Текущая версия заявления об отказе от ответственности. **При изменении копирайтинга disclaimer_content его
     * необходимо синхронизировать +1**——
     * Пользователи принимают содержание «тогдашней версии» и должны подтвердить это повторно, если условия изменятся.
     *
     * v2 (18 октября 2026 г.): приложение переименовано в CarDeck, добавлены «неофициальные», «не MFi» и инструкции
     * с открытым исходным кодом MIT.
     * v3 (16.11.2026): Интерфейс и отказ от ответственности изменены на русский язык.
     */
    const val CURRENT_VERSION = 3

    private const val PREFS = "disclaimer"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"

    fun acceptedVersion(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ACCEPTED_VERSION, 0)

    fun hasAcceptedCurrent(context: Context): Boolean = acceptedVersion(context) >= CURRENT_VERSION

    fun accept(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCEPTED_VERSION, CURRENT_VERSION)
            .apply()
    }

    /** Только для тестирования/диагностики: Отмените прием (при следующем запуске процесс будет перезапущен). */
    fun reset(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACCEPTED_VERSION)
            .apply()
    }
}

/**
 * Доступные условия для кнопки «Принять» (чистая функция, однократная проверка).
 *
 * выйдет только в том случае, если одновременно будут выполнены три условия:
 * 1. Окончание обратного отсчета ([countdownDone]) — заставить пользователей успеть осознать, что это
 * утверждение, связанное с безопасностью;
 * 2. Содержимое не переполнилось ([contentScrollable]=false) или не сместилось вниз ([scrolledToBottom])——
 * Длинный текст должен быть прочитан до конца, и его нельзя обмануть тем, «переполняется ли контент»;
 * 3. (Гарантировано звонившим) Пользователь не нажал "Отказаться от использования".
 *
 * Примечание. Обратный отсчет и прокрутка выполняются **параллельно**, а не последовательно. В противном случае
 * пользователи длинных копий будут перемещаться через 30 секунд.
 * приходится ждать еще 10 секунд, что является плохим опытом.
 */
internal fun canAcceptDisclaimer(
    countdownDone: Boolean,
    contentScrollable: Boolean,
    scrolledToBottom: Boolean,
): Boolean = countdownDone && (!contentScrollable || scrolledToBottom)

/**
 * Вам по-прежнему необходимо отображать обязательное заявление при запуске: Этот компьютер не получил **текущую версию**.
 */
internal fun needsDisclaimerAtLaunch(
    acceptedVersion: Int,
    currentVersion: Int,
): Boolean = acceptedVersion < currentVersion

/**
 * Подсказка внизу (сообщите пользователям, «почему нельзя нажать кнопку»). Верните ноль, чтобы указать, что вы можете щелкнуть.
 */
internal fun disclaimerBlockReason(
    countdownDone: Boolean,
    contentScrollable: Boolean,
    scrolledToBottom: Boolean,
    remainingSeconds: Int,
): String? =
    when {
        !countdownDone && contentScrollable && !scrolledToBottom ->
            "Прокрутите страницу вниз и подождите $remainingSeconds секунд."

        !countdownDone -> "Пожалуйста, подождите $remainingSeconds секунд, прежде чем принять"
        contentScrollable && !scrolledToBottom -> "Пожалуйста, прокрутите вниз, чтобы прочитать"
        else -> null
    }
