package io.github.shaonialife.cardeck.iap2

import android.content.Context

/**
 * MFi бросает вызов длине **установленной в автомобиле энергонезависимой памяти**.
 *
 * Зачем нужен   (фактическое испытание Audi MMI 3315 19 октября 2026 г.): 20B Challenge молчит в течение 6
 * секунд без реакции на этом автомобиле.
 * вернул подпись через 0,64 секунды после смены на 32B — зря ожидание по 6 секунд для каждого соединения. Разные
 * приёмники имеют противоположные предпочтения по длине:
 * — DiPlay (Carlinkit AuthV3): принимает только **32B**, если длина не совпадает, ссылка будет разорвана напрямую;
 * — Audi MMI 6318: 20Б секунд на ответ, 32Б раз на снятие цепи за 0,4 секунды;
 * — Audi MMI 3315: 20B без звука, **32B** для ответа.
 * Таким образом, невозможно ни жестко запрограммировать определенный файл глобально, ни полагаться исключительно
 * на внутреннюю память (она будет потеряна при перезапуске приложения и будет погашена при каждом повторном подключении).
 * 6 секунд). Здесь сохраняются три вещи в соответствии с «идентичностью автомобиля и машины (модель + производитель)»:
 *
 * - [Entry.succeeded]: длина самой последней **успешно полученной подписи** → в следующий раз выберите ее напрямую, исключая тестирование;
 * - [Entry.silent]: длительность отсутствия ответа в окне → в очереди назад (но все еще сохраняется в кармане,
 * прошивка автомобиля может измениться);
 * - [Entry.rejected]: 0xAA04 явно отклоняет или **приводит к разрыву длины цепочки** → занимает последнее место.
 *
 * Правила сортировки   см. в [orderAttempts] (чистая функция, удобная для одиночного тестирования): успешная
 * передача всегда первая; остальные используются
 * Базовый приказ, данный вызывающим абонентом (сначала реальный автомобиль 20B / сначала испытательный стенд 32B), и, наконец, тихий и
 * отклонен. Если все передачи базовой линии были отклонены, попробуйте их все как обычно, чтобы избежать постоянного застревания.
 */
object MfiLengthMemory {
    /** Память MFi для одного автомобиля. */
    data class Entry(
        val succeeded: Int? = null,
        val silent: Set<Int> = emptySet(),
        val rejected: Set<Int> = emptySet(),
    ) {
        val isEmpty: Boolean get() = succeeded == null && silent.isEmpty() && rejected.isEmpty()
    }

    private const val FILE = "mfi_length_memory"
    private const val KEY_OK = "ok_"
    private const val KEY_SILENT = "silent_"
    private const val KEY_REJECTED = "rejected_"

    /** Идентификатор автомобиля → ключ хранения. Используйте только стабильные поля, чтобы избежать влияния суффиксов переменных в именах Bluetooth на попадания. */
    fun receiverKey(identityText: String): String =
        identityText
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "unknown" }

    /** Чтение памяти определенного автомобиля (при отсутствии контекста она превращается в таблицу внутрипроцессной памяти для облегчения одиночного тестирования). */
    fun load(
        context: Context?,
        identityText: String,
    ): Entry {
        val key = receiverKey(identityText)
        val prefs = prefs(context)
        if (prefs == null) {
            return synchronized(memory) { memory[key] ?: Entry() }
        }
        val ok = prefs.getInt(KEY_OK + key, 0).takeIf { it > 0 }
        return Entry(
            succeeded = ok,
            silent = readLengths(prefs.getString(KEY_SILENT + key, null)),
            rejected = readLengths(prefs.getString(KEY_REJECTED + key, null)),
        )
    }

    fun recordSuccess(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            // Успех подделает старое молчаливое/отклоненное заключение этого механизма (поведение может измениться после
            // обновления прошивки)

            Entry(succeeded = length, silent = entry.silent - length, rejected = entry.rejected - length)
        }
    }

    fun recordSilent(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            if (entry.succeeded == length) {
                entry
            } else {
                entry.copy(silent = entry.silent + length, rejected = entry.rejected - length)
            }
        }
    }

    fun recordRejected(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            if (entry.succeeded == length) {
                entry
            } else {
                entry.copy(rejected = entry.rejected + length, silent = entry.silent - length)
            }
        }
    }

    /**
     * Hard Reject: соединение напрямую отключается узлом во время запроса такой длины (в отличие от 0xAA04 Reject Word/Silence).
     * Это веское доказательство — даже если длина равна зафиксированному «успеху», его необходимо отменить: в ответе
     * MFi есть неясность атрибуции.
     * (поздние ответы, поступившие после того, как предыдущая передача превысила окно, будут записаны на текущей
     * головке передачи, фактические измерения DiPlay 2026-10-24:
     * Поздний ответ       32B записывается как успех 20B, а 20B будет сломан и навсегда заблокирован в каждом последующем раунде).
     */
    fun recordHardRejected(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            val succeeded = if (entry.succeeded == length) null else entry.succeeded
            Entry(
                succeeded = succeeded,
                silent = (entry.silent - length),
                rejected = entry.rejected + length,
            )
        }
    }

    /** Для печати: сожмите память в строку журнала (для удобства сравнения с фактическими измерениями). */
    fun describe(entry: Entry): String =
        if (entry.isEmpty) {
            "Нет"
        } else {
            buildList {
                entry.succeeded?.let { add("успех=${it}B") }
                if (entry.silent.isNotEmpty()) add("тишина=${entry.silent.sorted().joinToString("/")}B")
                if (entry.rejected.isNotEmpty()) add("отклонено=${entry.rejected.sorted().joinToString("/")}B")
            }.joinToString("，")
        }

    /**
     * вычисляет порядок попыток в этом раунде (чистая функция).
     *
     * @param baseline Порядок базовых линий (список длин), заданный вызывающей стороной по типу получателя.
     * @param запись Постоянная память этого автомобиля.
     * @return Длина последовательных попыток; гарантированно непусто и охватывает все уровни базовой линии.
     */
    fun orderAttempts(
        baseline: List<Int>,
        entry: Entry,
    ): List<Int> {
        // «Удалось, но было жестко отвергнуто» = Конфликт памяти (поздний ответ и неправильное запоминание),
        // неспособность продолжать занимать первое место——

        // Понизьте версию до обычных файлов, чтобы участвовать в сортировке (ранжируется перед отклоненными файлами)
        val succeeded = entry.succeeded?.takeIf { it !in entry.rejected }
        val rest = baseline.filter { it != succeeded }
        // Только когда «остаются еще ларьки, не приговоренные к смертной казни», отвергнутые будут стоять на последнем месте;
        // Все отвергнуто. Описание не соответствует памяти (сменить прошивку/сменить машину на такую ​​же), пробуйте все как обычно.
        val allRejected = rest.isNotEmpty() && rest.all { it in entry.rejected }
        val rejected = if (allRejected) emptySet() else entry.rejected.filter { it in baseline }.toSet()
        val silent = entry.silent.filter { it in rest && it !in rejected }.toSet()
        val normal = rest.filter { it !in silent && it !in rejected }
        val silentOrdered = rest.filter { it in silent }
        val rejectedOrdered = rest.filter { it in rejected }
        return (listOfNotNull(succeeded) + normal + silentOrdered + rejectedOrdered).distinct()
    }

    // ---- Внутренний ----

    private val memory = HashMap<String, Entry>()

    private fun prefs(context: Context?): android.content.SharedPreferences? = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun update(
        context: Context?,
        identityText: String,
        transform: (Entry) -> Entry,
    ) {
        val key = receiverKey(identityText)
        synchronized(memory) {
            val current = load(context, identityText)
            val next = transform(current)
            memory[key] = next
            val prefs = prefs(context) ?: return
            prefs
                .edit()
                .apply {
                    if (next.succeeded != null) putInt(KEY_OK + key, next.succeeded) else remove(KEY_OK + key)
                    putString(KEY_SILENT + key, next.silent.sorted().joinToString(","))
                    putString(KEY_REJECTED + key, next.rejected.sorted().joinToString(","))
                }.apply()
        }
    }

    private fun readLengths(raw: String?): Set<Int> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.toSet()
            .orEmpty()
}
