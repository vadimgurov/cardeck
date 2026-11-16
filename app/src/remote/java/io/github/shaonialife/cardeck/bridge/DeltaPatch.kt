package io.github.shaonialife.cardeck.bridge

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Приложение  для исправления разницы блоков (жесткое согласование с двоичным форматом deltadiff.ts на стороне
 * сервера, не меняйте его отдельно):
 *
 * "CDDELTA1" 8B магия
 * targetLen u64 LE
 * цельSha256 32B
 * baseLen u64 LE
 * базаSha256 32B
 * ops: 0x01 COPY(u64 oldOffset,u64 len) | 0x02 ЛИТЕРАЛ (u64 длина + байты)
 *
 * Безопасность   не зависит от надежности самого патча: продукт sha256 принудительно проверяется после
 * применения. Если он не совпадает, весь пакет будет отброшен.
 * полностью откатился. Базовая версия фиксирована для использования собственного исходного каталога процесса
 * (единственного, который можно прочитать и сопоставить с базовой версией сервера).
 * побайтно согласованный источник).
 */
object DeltaPatch {
    private const val MAGIC = "CDDELTA1"

    /**
     * применяет [patch] к [base] (который должен побайтно соответствовать базовой дифференциальной линии сервера) для записи [out].
     * возвращает значение null = успех; в противном случае возвращает причину сбоя (вызывающая программа возвращается к полной загрузке).
     */
    fun apply(
        patch: File,
        base: File,
        out: File,
    ): String? {
        if (!base.isFile) return "Базовый APK не существует."
        val sha = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(patch, "r").use { pf ->
            val magic = ByteArray(8)
            pf.readFully(magic)
            if (!String(magic, Charsets.US_ASCII).startsWith(MAGIC)) return "Формат патча неправильный"
            val targetLen = pf.readLong()
            val targetSha = ByteArray(32).also { pf.readFully(it) }
            val baseLen = pf.readLong()
            val baseSha = ByteArray(32).also { pf.readFully(it) }
            if (base.length() != baseLen) return "Длина базовой линии не соответствует (${base.length()}/$baseLen)"
            if (!sha256Of(base).contentEquals(baseSha)) return "Базовый уровень sha256 не соответствует"
            RandomAccessFile(base, "r").use { bf ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        when (pf.readByte().toInt()) {
                            0x01 -> {
                                // КОПИРОВАТЬ: искать+копировать из базовой линии
                                val off = pf.readLong()
                                var len = pf.readLong()
                                bf.seek(off)
                                while (len > 0) {
                                    val n = bf.read(buf, 0, minOf(buf.size.toLong(), len).toInt())
                                    if (n < 0) return "Базовое чтение выходит за пределы"
                                    output.write(buf, 0, n)
                                    sha.update(buf, 0, n)
                                    written += n
                                    len -= n
                                }
                            }

                            0x02 -> {
                                // ЛИТЕРАЛ: исправление строковых байтов
                                var len = pf.readLong()
                                while (len > 0) {
                                    val n = pf.read(buf, 0, minOf(buf.size.toLong(), len).toInt())
                                    if (n < 0) return "Патч прочитан за пределами"
                                    output.write(buf, 0, n)
                                    sha.update(buf, 0, n)
                                    written += n
                                    len -= n
                                }
                            }

                            else -> return "неизвестная команда"
                        }
                        if (written > targetLen) return "Товар слишком длинный"
                        if (written == targetLen) break
                    }
                    if (!sha.digest().contentEquals(targetSha)) {
                        return "Продукт sha256 не соответствует (патч может не " +
                            "соответствовать базовой версии)"
                    }
                    return null
                }
            }
        }
    }

    internal fun sha256Of(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }
}
