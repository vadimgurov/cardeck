package io.github.shaonialife.cardeck.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Глобальный журнал отладки  (files/main_debug.log).
 *
 * Logcat модели Honor зашифрован HKS. Этот файл является единственным каналом отладки на стороне adb (чтение от имени).
 * MainActivity и CarPlayService (диагностика жизненного цикла проекции) используют одну и ту же блокировку,
 * чтобы избежать одновременной записи и пересечения одного и того же файла.
 */
object DebugFileLog {
    private val lock = Any()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * мост отладки: каждый зарегистрированный журнал синхронно передается прослушивателю (**вариант удаленной
     * отладки** установлен в приложении;
     * вариант ежедневного выпуска/выпуска всегда равен нулю, никаких накладных расходов). seq увеличивается
     * монотонно — seq пополняется при отключении и повторном нажатии.
     * Прослушиватель вызывается вне блокировки записи, и исключение поглощается: ему нельзя позволить замедлять или прерывать запись файла.
     */
    @Volatile
    var tap: ((seq: Long, line: String) -> Unit)? = null

    private val sequence =
        java.util.concurrent.atomic
            .AtomicLong(0)

    /** Порядковый номер последнего зарегистрированного журнала (0 = журнал для этого процесса отсутствует). */
    val lastSeq: Long get() = sequence.get()

    /**
     * Строка отметки «Этот процесс запускается». Файлы журналов сохраняются во всех процессах (до 2 МБ), когда
     * пользователи сообщают о проблемах.
     * Логи исторических раундов заглушат действительно полезные части - по умолчанию экспорт берет только контент после последней отметки.
     * Сама метка       записывается в файл вместо того, чтобы просто запоминать смещение памяти: файл может быть
     * усечен на 2 МБ, и смещение будет недействительным.
     */
    const val SESSION_MARKER = "===== Запускается процесс CarDeck (начальная точка этого журнала) ====="

    /**
     * записывает эту метку запуска. Вызывается один раз [io.github.shaonialife.cardeck.SenderApp.onCreate];
     * Повторные вызовы       будут генерировать несколько тегов, и последний из них все равно будет использоваться
     * для экспорта, что является безопасным поведением.
     */
    fun markSessionStart(context: Context) {
        append(context, SESSION_MARKER)
    }

    /**
     * читает текст журнала. Когда [currentSessionOnly] = true, возвращается только последний [SESSION_MARKER].
     * Содержимое после       (возвращает полный текст, если он не отмечен - это относится к файлам первой установки/старой версии).
     */
    fun read(
        context: Context,
        currentSessionOnly: Boolean,
    ): String? =
        runCatching {
            val text = File(context.filesDir, "main_debug.log").readText(Charsets.UTF_8)
            if (currentSessionOnly) currentSessionSlice(text) else text
        }.getOrNull()

    /**
     * считывает исходные байты в конце файла (для моста удаленной отладки `log_file.req` максимум [maxBytes]).
     * безопасен для параллельной записи потоков: только чтение, а не запись; если файл не существует, возвращается ноль.
     */
    fun readTail(
        context: Context,
        maxBytes: Int,
    ): ByteArray? =
        runCatching {
            val file = File(context.filesDir, "main_debug.log")
            if (!file.isFile) return null
            val len = file.length()
            val start = if (len <= maxBytes) 0L else len - maxBytes
            file.inputStream().use { input ->
                input.channel.position(start)
                input.readBytes()
            }
        }.getOrNull()

    /**
     * принимает содержимое после последней отметки запуска (чистая функция, одна граница тестового покрытия).
     * Нет пометки → Полный текст: В файле, написанном старой версией, пометки нет. На этот раз полный текст лучше, чем пустой.
     */
    internal fun currentSessionSlice(text: String): String {
        val markerAt = text.lastIndexOf(SESSION_MARKER)
        return if (markerAt < 0) text else text.substring(markerAt + SESSION_MARKER.length)
    }

    fun append(
        context: Context,
        message: String,
    ) {
        try {
            synchronized(lock) {
                val file = File(context.filesDir, "main_debug.log")
                // Прямое удаление, превышающее лимит, также потеряет пометку «этот запуск» (экспорт будет выполняться до полной истории).
                // Немедленно перезапишите пометку после удаления, чтобы новый усеченный файл все еще можно было разделить по сеансам.
                if (file.length() > 2_000_000) {
                    file.delete()
                    FileWriter(
                        file,
                        true,
                    ).use {
                        it
                            .append('[')
                            .append(timestamp.format(Date()))
                            .append("] ")
                            .append(SESSION_MARKER)
                            .append('\n')
                    }
                }
                FileWriter(file, true).use { writer ->
                    writer
                        .append('[')
                        .append(timestamp.format(Date()))
                        .append("] ")
                        .append(message)
                        .append('\n')
                }
            }
            tap?.let { listener ->
                val line = "[" + timestamp.format(Date()) + "] " + message
                runCatching { listener(sequence.incrementAndGet(), line) }
            }
        } catch (_: Throwable) {
        }
    }
}
