package io.github.shaonialife.cardeck.audio

import android.content.Context

/**
 * Аудиоаппаратура  **постоянная память** (аналогично [io.github.shaonialife.cardeck.iap2.MfiLengthMemory]
 * тот же набор идей).
 *
 * Предыстория (19 октября 2026 г., реальный автомобиль Honda HFT / Alpine): После выбора соединения «Видео +
 * аудио» сеанс был успешно установлен.
 * Видео   нормальное, настройка звука также возвращает 200 и получает dataPort, но аудиопакет отправляется
 * примерно через 6 секунд после завершения
 * Разрыв сеанса AirPlay (`EVENT: Конец чтения - Connection reset`). Та же машина и машина только видео толкают и всё стабильно.
 * Это показывает, что **определенная звуковая передача** является громом, а разные автомобили имеют разные
 * передачи для грома (доступен Audi media/44.1k,
 * только объявляет media/44.1k, но не может его использовать). Ни шестерни не могут быть жестко
 * запрограммированы глобально, ни слепо тестироваться каждый раз при подключении.
 * Итак, запомните три вещи в зависимости от идентичности автомобиля и машины:
 *
 * - [Entry.working]: снаряжение, которое прослужит **полное окно выживания** →, в следующий раз будет предпочтительнее;
 * — [Entry.rejected]: шестерни, которые были отключены вскоре после выхода в Интернет → заняли последнее место;
 * - [Entry.disabled]: Все шестеренки удалены → Эта машина больше не будет воспроизводить звук, а будет передавать только видео.
 * (Лучше бы звука не было, чем отключиться сразу после начала подключения).
 *
 * определяет «быстрое» окно, заданное вызывающей стороной ([SessionController] использует 20 с).
 */
object AudioProfileMemory {
    /** Аудиопамять на один автомобильный блок. */
    data class Entry(
        val working: String? = null,
        val rejected: Set<String> = emptySet(),
        val disabled: Boolean = false,
    ) {
        val isEmpty: Boolean get() = working == null && rejected.isEmpty() && !disabled
    }

    // v2: Ключ шестеренки добавляет «тип потока + метод кодирования» (102/AAC-LC и 100/PCM — это два совершенно разных пути).
    // Изменение имени файла вместо повторного использования старого файла должно помешать завершению эпохи v1 «всех
    // трех уровней разделения PCM» —

    // Этот набор выводов охватывает только PCM и не имеет справочной ценности для недавно добавленных путей AAC.
    private const val FILE = "audio_profile_memory_v2"
    private const val KEY_OK = "ok_"
    private const val KEY_REJECTED = "rejected_"
    private const val KEY_DISABLED = "disabled_"

    /** Идентификация автомобиля → Ключ хранения (сохраняйте только стабильные символы, чтобы переменная часть имени Bluetooth/строки прошивки не влияла на попадание). */
    fun receiverKey(identityText: String): String =
        identityText
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "unknown" }

    fun load(
        context: Context?,
        identityText: String,
    ): Entry {
        val key = receiverKey(identityText)
        val prefs = prefs(context)
        if (prefs == null) {
            return synchronized(memory) { memory[key] ?: Entry() }
        }
        return Entry(
            working = prefs.getString(KEY_OK + key, null)?.takeIf { it.isNotBlank() },
            rejected = readKeys(prefs.getString(KEY_REJECTED + key, null)),
            disabled = prefs.getBoolean(KEY_DISABLED + key, false),
        )
    }

    /** Это снаряжение заполняет окно выживания: записывается как первый выбор и фальсифицирует исторически отвергнутое заключение (оно может стать лучше после обновления прошивки). */
    fun recordWorking(
        context: Context?,
        identityText: String,
        profileKey: String,
    ) {
        update(context, identityText) { entry ->
            Entry(
                working = profileKey,
                rejected = entry.rejected - profileKey,
                disabled = false,
            )
        }
    }

    /**
     * Это снаряжение недоступно: занимает последнее место.
     *
     * @param markExhausted Устанавливать ли [Entry.disabled] напрямую, когда все передачи запрещены. **Только «расцепить»
     * Этот вид детерминированного доказательства позволяет установить **; Отклонение/тайм-аут SETUP может быть
     * просто джиттером сети или арбитража, поэтому отправляется false.
     */
    fun recordRejected(
        context: Context?,
        identityText: String,
        profileKey: String,
        allCandidates: Set<String>,
        markExhausted: Boolean = true,
    ) {
        update(context, identityText) { entry ->
            if (entry.working == profileKey) {
                entry
            } else {
                val rejected = entry.rejected + profileKey
                val exhausted =
                    markExhausted && allCandidates.isNotEmpty() && allCandidates.all { it in rejected }
                Entry(working = entry.working, rejected = rejected, disabled = exhausted)
            }
        }
    }

    /** Ручное восстановление (сброс после замены ТС или повторная попытка после обновления прошивки ТС). */
    fun reset(
        context: Context?,
        identityText: String,
    ) {
        update(context, identityText) { Entry() }
    }

    /** Существует ли какая-либо память (определяет, отображает ли пользовательский интерфейс запись «Очистить память, совместимую со звуком»). */
    fun hasAny(context: Context?): Boolean {
        val all = prefs(context)?.all ?: return synchronized(memory) { memory.isNotEmpty() }
        return all.any { (key, value) ->
            when {
                key.startsWith(KEY_DISABLED) -> value == true
                key.startsWith(KEY_OK) -> (value as? String)?.isNotBlank() == true
                key.startsWith(KEY_REJECTED) -> (value as? String)?.isNotBlank() == true
                else -> false
            }
        }
    }

    /** Очистите аудиопамять всех автомобилей (обновите прошивку автомобиля, после смены автомобиля повторите попытку). */
    fun clearAll(context: Context?) {
        synchronized(memory) {
            memory.clear()
            prefs(context)?.edit()?.clear()?.apply()
        }
    }

    /** Для печати: впечатайте в одну строку журнала. */
    fun describe(entry: Entry): String =
        when {
            entry.disabled -> "Аудио этого автомобиля определено как несовместимое (загружаются только видео)"
            entry.isEmpty -> "Нет"
            else ->
                buildList {
                    entry.working?.let { add("Доступно=$it") }
                    if (entry.rejected.isNotEmpty()) add("Разорвать цепочку=${entry.rejected.sorted().joinToString("/")}")
                }.joinToString("，")
        }

    /**
     * вычисляет порядок попыток в этом раунде (чистая функция, удобная для одиночного тестирования).
     *
     * Кандидаты       @param Базовые кандидаты ранжируются по принципу «чем выше, тем больше рекомендуется» (из carmachine/info).
     * @param запись Постоянная память этого автомобиля.
     * @return Последовательно проверяются передачи; **Пустой список означает, что больше не воспроизводить звук**
     * (память определила, что все шестерни отключены).
     */
    fun order(
        candidates: List<AudioProfile>,
        entry: Entry,
    ): List<AudioProfile> {
        if (candidates.isEmpty()) return emptyList()
        if (entry.disabled) return emptyList()
        val keys = candidates.map { it.key }.toSet()
        val rejected = entry.rejected.filter { it in keys }.toSet()
        val working = candidates.firstOrNull { it.key == entry.working }
        val rest = candidates.filter { it.key != working?.key }
        // Лишь когда «остаются еще ларьки, не приговоренные к смертной казни», отвергнутый ставится на последнее место;
        // объяснение того, что всех приговорили к смертной казни, несовместимо с памятью.

        // (Для замены автомобиля/обновления прошивки используется тот же идентификационный ключ). В это время попробуйте
        // все как обычно, чтобы избежать постоянного молчания.

        val allRejected = rest.isNotEmpty() && rest.all { it.key in rejected }
        val safe = if (allRejected) rest else rest.filter { it.key !in rejected }
        val risky = if (allRejected) emptyList() else rest.filter { it.key in rejected }
        return (listOfNotNull(working) + safe + risky).distinctBy { it.key }
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
            val next = transform(load(context, identityText))
            memory[key] = next
            val prefs = prefs(context) ?: return
            prefs
                .edit()
                .apply {
                    if (next.working != null) putString(KEY_OK + key, next.working) else remove(KEY_OK + key)
                    putString(KEY_REJECTED + key, next.rejected.sorted().joinToString(","))
                    putBoolean(KEY_DISABLED + key, next.disabled)
                }.apply()
        }
    }

    private fun readKeys(raw: String?): Set<String> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
}
