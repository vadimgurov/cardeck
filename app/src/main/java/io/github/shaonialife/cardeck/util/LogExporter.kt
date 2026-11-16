package io.github.shaonialife.cardeck.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экспорт журнала : записывайте текущий журнал как `внизнагрузка/CarDeck/CarDeck-log-<время>.txt`, видимый в обычных файловых менеджерах.
 *
 * Зачем удалять MediaStore.Downloads вместо личного каталога приложения:
 * - `filesDir`/`getExternalFilesDir` Обычные пользователи не могут найти его в файловом менеджере (Android 11+
 * Системный файловый менеджер   напрямую блокирует `Android/data`), а экспорт журнала равен отсутствию экспорта;
 * — MediaStore.Downloads не требует каких-либо разрешений на хранение. Он появится в системном каталоге
 * «Загрузки» сразу после установки диска.
 * Вы также можете напрямую выбирать вложения, отправленные по WeChat/QQ/электронной почте.
 *
 * Содержимое   имеет приоритет над `files/main_debug.log`: оно записывает весь журнал AppendLog (на него не
 * влияет переключение журнала пользовательского интерфейса,
 * сохраняет самые последние 2 МБ); только когда файл не читается, он возвращается в буфер памяти
 * пользовательского интерфейса (самые последние 20–40 КБ).
 */
object LogExporter {
    /** Область экспорта: по умолчанию экспортируются только журналы после запуска этого процесса (исторические раунды являются помехой для устранения неполадок). */
    enum class Scope(
        val label: String,
        val fileNameTag: String,
    ) {
        CURRENT_SESSION("Только на этот раз", ""),
        ALL("Все журналы истории", "-all"),
    }

    data class Result(
        val uri: Uri,
        val fileName: String,
        /** Абсолютный путь, отображаемый пользователю (щелкните по нему, чтобы найти его в файловом менеджере). */
        val displayPath: String,
        val bytes: Long,
        val scope: Scope,
    )

    /** Каталог экспорта (относительно корня внешнего хранилища): Downloads/CarDeck. */
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/CarDeck"

    /** Префикс абсолютного пути, отображаемый в файловом менеджере (основное внешнее хранилище пользователя; /sdcard в хранилище разделов). */
    private const val DISPLAY_ROOT = "/storage/emulated/0"

    /** Экспорт и возврат результатов; возвращает значение null в случае ошибки (вызывающая сторона запрашивает пользователя). Любой поток регулируется (включая IO). */
    fun export(
        context: Context,
        fallbackText: String,
        scope: Scope = Scope.CURRENT_SESSION,
    ): Result? {
        val body = buildContent(context, fallbackText, scope)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "CarDeck-log-$stamp${scope.fileNameTag}.txt"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                // Сначала пометьте ожидающим, а затем напишите: чтобы файловый менеджер/альбом не сканировал половину файла.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream возвращает ноль")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Result(uri, fileName, "$DISPLAY_ROOT/$RELATIVE_DIR/$fileName", bytes.size.toLong(), scope)
        } catch (_: Exception) {
            // Половина файла (IS_PENDING=1) не видна пользователю, но будет занимать место — очистите его
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun buildContent(
        context: Context,
        fallbackText: String,
        scope: Scope,
    ): String =
        buildString {
            appendLine("Журнал работы CarDeck")
            appendLine("Время экспорта:" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("Версия приложения:" + versionName(context))
            appendLine("Экспортный диапазон:" + scope.label)
            appendLine(
                "Устройство: ${Build.MANUFACTURER} ${Build.MODEL} |" +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("==================================================")
            val fileLog = DebugFileLog.read(context, currentSessionOnly = scope == Scope.CURRENT_SESSION)
            if (!fileLog.isNullOrBlank()) {
                if (scope == Scope.CURRENT_SESSION) {
                    appendLine("(Только журналы после запуска этого процесса; если вам нужна история, нажмите «Экспортировать все»)")
                } else {
                    appendLine(
                        "(Все файлы журналов; максимальный размер файла — 2 МБ. При превышении система сохраняет " +
                            "только последний раздел)",
                    )
                }
                append(fileLog)
                if (!fileLog.endsWith('\n')) appendLine()
            } else {
                // Когда метка запуска только что была записана, а другие журналы не были записаны на диск (или файл не
                // читается), буфер интерфейса все еще является действительным источником.

                appendLine("(Журнал файла недоступен — буферизация интерфейса экспорта, может быть неполной)")
                append(fallbackText)
            }
        }

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("?")
}
