package io.github.shaonialife.cardeck.airplay

import java.io.ByteArrayOutputStream

/**
 * Минимальный синтаксический анализатор двоичного списка Apple (bplist00): переопределяет тип, используемый списком возможностей /info.
 * (null/bool/int/real/data/ASCII/строка UTF-16/UID/массив/дикт).
 * Сопоставление возвращаемого значения  : Map<String,Any?> / List<Any?> / String / Long / Double / Boolean / ByteArray / null.
 */
object BinaryPlist {
    // ---- Кодировка: Map<String,Any?>/List/String/Long/Int/Boolean/Double/ByteArray ----

    fun encode(value: Any?): ByteArray {
        val objects = ArrayList<ByteArray>()
        val totalObjects = countObjects(value)
        val rootRef = encodeObject(value, objects, totalObjects)
        var offset = 8
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { index, bytes ->
            offsets[index] = offset
            offset += bytes.size
        }
        val offsetIntSize = intBytes(offset)
        val refSize = intBytes(totalObjects)
        val tableSize = objects.size * offsetIntSize
        val tableOffset = offset

        val out = ByteArrayOutputStream(offset + tableSize + 32)
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        objects.forEach(out::write)
        offsets.forEach { writeUint(out, it.toLong(), offsetIntSize) }
        // трейлер: неиспользуемый(6) offsetIntSize(1) refSize(1) numObjects(8) topObject(8) offsetTableOffset(8)
        out.write(ByteArray(6))
        out.write(offsetIntSize)
        out.write(refSize)
        writeUint(out, objects.size.toLong(), 8)
        writeUint(out, rootRef.toLong(), 8)
        writeUint(out, tableOffset.toLong(), 8)
        // Корневой объект должен находиться в байте 8: этот кодер сначала рекурсивно кодирует подобъекты и последним
        // добавляет корневой контейнер.

        // Корень по умолчанию находится в конце. Старые модели автомобилей, такие как CarPlay 210.81, не проверяют topObject прицепа.
        // Смещение прямого чтения 8** (catplay upstream 9ad3a7e «исправьте недавнюю регрессию кодировщика с помощью
        // CarPlay 210.81, в котором жестко закодировано ожидаемое смещение корневого объекта»).
        // Кодировщик Apple также изначально расположен спереди и перестроен в соответствии с той же компоновкой, чтобы
        // соответствовать реальному iPhone.

        return makeRootFirst(out.toByteArray())
    }

    /**
     * перемещает весь корневой объект на смещение 8 и корректирует таблицу смещений (тот же алгоритм, что и в `make_root_first`).
     * Входные данные       должны быть допустимым bplist, только что созданным этим классом (корнем является
     * последний объект), а ненормальный макет возвращается как есть.
     */
    private fun makeRootFirst(data: ByteArray): ByteArray {
        if (data.size < 41 || !data.copyOfRange(0, 8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))) {
            return data
        }
        val trailer = data.size - 32
        val width = data[trailer + 6].toInt() and 0xFF
        if (width != 1 && width != 2 && width != 4 && width != 8) return data
        val count = readUint(data, trailer + 8, 8).toInt()
        val root = readUint(data, trailer + 16, 8).toInt()
        val table = readUint(data, trailer + 24, 8).toInt()
        if (root >= count ||
            table < 9 ||
            table.toLong() + count.toLong() * width > trailer
        ) {
            return data
        }

        fun entry(index: Int): Int = table + index * width
        val rootStart = readUint(data, entry(root), width).toInt()
        if (rootStart !in 8 until table) return data
        if (rootStart == 8) return data
        // Блок байтов, принадлежащий корневому объекту после корня (до таблицы смещений); другие объекты сортируются перед ним.
        var rootEnd = table
        for (index in 0 until count) {
            val offset = readUint(data, entry(index), width).toInt()
            if (offset !in 8 until table) return data
            if (offset > rootStart) rootEnd = minOf(rootEnd, offset)
        }
        val rootLen = rootEnd - rootStart
        if (rootLen <= 0) return data
        val out = data.copyOf()
        // data[8..rootEnd) rootLen вращения вправо: эквивалент перемещения корневого блока вперед и перемещения других объектов вперед.
        val tailLen = rootEnd - 8 - rootLen
        System.arraycopy(data, rootStart, out, 8, rootLen)
        System.arraycopy(data, 8, out, 8 + rootLen, tailLen)
        for (index in 0 until count) {
            val at = entry(index)
            val offset = readUint(data, at, width).toInt()
            val adjusted =
                when {
                    index == root -> 8
                    offset < rootStart -> offset + rootLen
                    else -> offset
                }
            writeUint(out, adjusted.toLong(), width, at)
        }
        return out
    }

    private fun writeUint(
        target: ByteArray,
        value: Long,
        size: Int,
        offset: Int,
    ) {
        for (index in 0 until size) {
            target[offset + index] = (value ushr (8 * (size - 1 - index))).toByte()
        }
    }

    /** Предварительный обход: опорная ширина должна определяться на основе окончательного общего количества объектов (включая сам контейнер). */
    private fun countObjects(value: Any?): Int =
        when (value) {
            is List<*> -> 1 + value.sumOf { countObjects(it) }
            is Map<*, *> -> 1 + value.keys.sumOf { countObjects(it) } + value.values.sumOf { countObjects(it) }
            else -> 1
        }

    private fun encodeObject(
        value: Any?,
        objects: ArrayList<ByteArray>,
        total: Int,
    ): Int {
        val bytes =
            when (value) {
                null -> byteArrayOf(0x00)
                is Boolean -> byteArrayOf(if (value) 0x09 else 0x08)
                is Int -> encodeInt(value.toLong())
                is Long -> encodeInt(value)
                is Double -> byteArrayOf(0x23) + value.toRawBits().toBytes(8)
                is ByteArray -> withLength(0x40, value)
                is String -> encodeString(value)
                is List<*> -> {
                    val refs = value.map { encodeObject(it, objects, total) }
                    withLength(0xA0, refsToBytes(refs, total), refs.size)
                }

                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = value as Map<String, Any?>
                    val keyRefs = map.keys.map { encodeObject(it, objects, total) }
                    val valueRefs = map.values.map { encodeObject(it, objects, total) }
                    withLength(0xD0, refsToBytes(keyRefs + valueRefs, total), map.size)
                }

                else -> throw IllegalArgumentException("Неподдерживаемый тип списка ${value.javaClass.simpleName}")
            }
        objects += bytes
        return objects.size - 1
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.toByteArray(Charsets.US_ASCII)
        return if (value.all { it.code < 0x80 } && ascii.decodeToString() == value) {
            withLength(0x50, ascii)
        } else {
            withLength(0x60, value.toByteArray(Charsets.UTF_16BE), value.length)
        }
    }

    /**
     * Целое число выбирает ширину в соответствии с диапазоном **подписи** (спецификация bplist: целое число
     * подписано, а конец чтения расширен знаком).
     *
     * однажды выбрал ширину по беззнаковому порогу: 35449 (0x8A79) было записано как 2 байта, а Apple нажала его сбоку как int16.
     * -30087. Как только целочисленный порт (timingPort/eventPort/dataPort) упадет до 32768..65535, он
     * становится отрицательным — принимающая сторона может полностью отклонить его или подключиться к неправильному
     * порту. Одиночный тест `SessionSetupBodyTest`
     * Так был обнаружен      .
     */
    private fun encodeInt(value: Long): ByteArray {
        val size =
            when {
                value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE -> 1
                value >= Short.MIN_VALUE && value <= Short.MAX_VALUE -> 2
                value >= Int.MIN_VALUE && value <= Int.MAX_VALUE -> 4
                else -> 8
            }
        return byteArrayOf((0x10 or (log2(size))).toByte()) + value.toBytes(size)
    }

    private fun withLength(
        marker: Int,
        payload: ByteArray,
        count: Int = payload.size,
    ): ByteArray {
        if (count < 15) return byteArrayOf((marker or count).toByte()) + payload
        val lengthBytes = encodeInt(count.toLong())
        return byteArrayOf((marker or 0x0F).toByte()) + lengthBytes + payload
    }

    private fun refsToBytes(
        refs: List<Int>,
        objectCount: Int,
    ): ByteArray {
        val width = intBytes(objectCount)
        val out = ByteArray(refs.size * width)
        refs.forEachIndexed { index, ref ->
            val value = ref.toLong()
            for (byte in 0 until width) {
                out[index * width + byte] = (value ushr (8 * (width - 1 - byte))).toByte()
            }
        }
        return out
    }

    private fun Long.toBytes(size: Int): ByteArray = writeBytes(this, size)

    private fun writeBytes(
        value: Long,
        size: Int,
    ): ByteArray =
        ByteArray(size) { index ->
            (value ushr (8 * (size - 1 - index))).toByte()
        }

    private fun writeUint(
        out: ByteArrayOutputStream,
        value: Long,
        size: Int,
    ) {
        out.write(writeBytes(value, size))
    }

    private fun intBytes(value: Int): Int =
        when {
            value < 0x100 -> 1
            value < 0x10000 -> 2
            value < 0x100000000 -> 4
            else -> 8
        }

    private fun log2(size: Int): Int =
        when (size) {
            1 -> 0
            2 -> 1
            4 -> 2
            else -> 3
        }

    // ---- Анализ ----

    /** Результат анализа + описание восстановления (примечание не пустое, если произошла обрезка, и пустое при обычном синтаксическом анализе). */
    class ParseResult(
        val value: Any?,
        val note: String?,
    )

    /**
     * Отказоустойчивый разбор: первый разбор по стандартной схеме; если это не удастся, обрежьте последние 1–8 байтов и повторите попытку.
     *
     * Сообщение RTSP автомобиля       иногда содержит конечные байты (CRLF/заполнение) в дополнение к `Content-Length`.
     * Трейлер bplist       расположен **32 байта** от конца. Если добавить еще один байт, весь трейлер будет смещен ——
     * ведет себя как «тело явно допустимо, bplist00, но синтаксический анализ не удался». Обрезка хвоста и повторная
     * попытка могут спасти такие пакеты.
     * , и в случае сбоя выдаются подробности исходного исключения (сводка трейлера включена в сообщение).
     */
    fun parseRecovered(bytes: ByteArray): ParseResult {
        val first = runCatching { parse(bytes) }
        first.getOrNull()?.let { return ParseResult(it, null) }
        if (first.isSuccess) return ParseResult(null, null)
        val failure = first.exceptionOrNull() ?: IllegalArgumentException("Не удалось выполнить синтаксический анализ")
        if (bytes.size >= 48) {
            for (trim in 1..8) {
                if (bytes.size - trim < 40) break
                val attempt = runCatching { parse(bytes.copyOfRange(0, bytes.size - trim)) }
                if (attempt.isSuccess) {
                    return ParseResult(attempt.getOrThrow(), "Дополнительные байты $trim в конце (успешно анализируются после обрезки)")
                }
            }
        }
        throw failure as? IllegalArgumentException
            ?: IllegalArgumentException("${failure.javaClass.simpleName}: ${failure.message}")
    }

    fun parse(bytes: ByteArray): Any? {
        if (bytes.size < 40) throw IllegalArgumentException("plist слишком короткий (${bytes.size} байт)")
        val magic = "bplist00".toByteArray(Charsets.US_ASCII)
        if (!bytes.copyOfRange(0, 8).contentEquals(magic)) {
            throw IllegalArgumentException(
                "Не bplist00 (первые 8 байт" + bytes.take(8).joinToString(" ") { "%02x".format(it) } + "）",
            )
        }

        // Последние 32 байта трейлера: unused(6) offsetIntSize(1) objectRefSize(1) numObjects(8) topObject(8) offsetTableOffset(8)
        val offsetIntSize = u8(bytes[bytes.size - 26])
        val objectRefSize = u8(bytes[bytes.size - 25])
        val numObjects = readUint(bytes, bytes.size - 24, 8)
        val topObject = readUint(bytes, bytes.size - 16, 8)
        val offsetTableOffset = readUint(bytes, bytes.size - 8, 8)
        val trailer =
            "trailer{offsetIntSize=$offsetIntSize objectRefSize=$objectRefSize numObjects=$numObjects " +
                "topObject=$topObject offsetTableOffset=$offsetTableOffset body=${bytes.size}B}"
        if (offsetIntSize !in 1..8 || objectRefSize !in 1..8) {
            throw IllegalArgumentException("Поле трейлера недопустимо $trailer")
        }
        if (numObjects <= 0 || numObjects > MAX_OBJECTS) {
            throw IllegalArgumentException("Недопустимое количество объектов $trailer")
        }
        if (topObject >= numObjects) throw IllegalArgumentException("Индекс корневого объекта выходит за пределы $trailer")
        if (offsetTableOffset < 9 ||
            offsetTableOffset + numObjects * offsetIntSize > bytes.size - 32
        ) {
            throw IllegalArgumentException("Таблица смещений выходит за пределы $trailer")
        }

        val count = numObjects.toInt()
        val tableOffset = offsetTableOffset.toInt()
        val offsets =
            IntArray(count) { index ->
                readUint(bytes, tableOffset + index * offsetIntSize, offsetIntSize).toInt()
            }
        return try {
            parseObject(bytes, offsets[topObject.toInt()], objectRefSize, offsets)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: IndexOutOfBoundsException) {
            throw IllegalArgumentException("Чтение за пределами границ ($trailer, ${error.message})")
        } catch (error: NegativeArraySizeException) {
            throw IllegalArgumentException("Поле длины отрицательное ($trailer).")
        } catch (error: OutOfMemoryError) {
            throw IllegalArgumentException("Поле длины слишком велико ($trailer)")
        }
    }

    private fun parseObject(
        bytes: ByteArray,
        offset: Int,
        refSize: Int,
        offsets: IntArray,
    ): Any? {
        val marker = u8(bytes[offset])
        val type = marker ushr 4
        val info = marker and 0xF
        return when (type) {
            0x0 ->
                when (info) {
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }

            // Целые числа всегда интерпретируются как **беззнаковые**: принимающая сторона (автомобиль) запишет растровое
            // изображение/микросекунды/частоту дискретизации в

            // 2-байтовое целое число, когда значение превышает 32767, оно становится отрицательным числом при чтении со
            // знаком (фактическое измерение: 44100 → -21436,

            // 35000us → -30536, 0x8854 → -30636). Как только битовое поле расширяется по знаку, старшие 16 битов
            // Все установлено в 1, любой бит формата > бит15 будет «показываться объявленным» — согласование подхватит его.
            // Формат вообще не поддерживается машиной.
            0x1 -> readUint(bytes, offset + 1, 1 shl info)

            0x2 ->
                when (info) {
                    0x2 -> Float.fromBits(readUint(bytes, offset + 1, 4).toInt()).toDouble()
                    0x3 -> Double.fromBits(readUint(bytes, offset + 1, 8))
                    else -> null
                }

            0x3 -> // Дата: CFAbsoluteTime (секунды с 1 января 2001 г.), вернуть как есть с помощью Double
                Double.fromBits(readUint(bytes, offset + 1, 8))

            0x4 -> { // данные
                val (count, start) = elementCount(bytes, offset, info)
                bytes.copyOfRange(start, start + count)
            }

            0x5 -> { // ASCII-строка
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count, Charsets.US_ASCII)
            }

            0x6 -> { // Строка UTF-16BE
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count * 2, Charsets.UTF_16BE)
            }

            0x7 -> { // Строка UTF-8 (тип номера bplist 0x7, некоторые кодировщики могут писать)
                val (count, start) = elementCount(bytes, offset, info)
                String(bytes, start, count, Charsets.UTF_8)
            }

            // UID: Длина определяется информацией (info+1 байт), **не** refSize - будет происходить чтение в соответствии с refSize.
            // Когда refSize>1, будут съедены дополнительные байты, а указатель объекта всего plist будет прочитан некорректно.
            0x8 -> readUint(bytes, offset + 1, info + 1)

            0xB -> { // UUID (фиксированная длина 16 байт)
                val size = info + 1
                bytes.copyOfRange(offset + 1, minOf(offset + 1 + size, bytes.size))
            }

            0xA, 0xC -> { // массив/набор
                val (count, start) = elementCount(bytes, offset, info)
                List(count) { index ->
                    parseObject(
                        bytes,
                        offsets[readUint(bytes, start + index * refSize, refSize).toInt()],
                        refSize,
                        offsets,
                    )
                }
            }

            0xD -> { // диктовать
                val (count, start) = elementCount(bytes, offset, info)
                val map = LinkedHashMap<String, Any?>(count)
                for (index in 0 until count) {
                    val keyRef = readUint(bytes, start + index * refSize, refSize).toInt()
                    val valueRef = readUint(bytes, start + (count + index) * refSize, refSize).toInt()
                    val key = parseObject(bytes, offsets[keyRef], refSize, offsets)
                    map[key?.toString() ?: "#$index"] = parseObject(bytes, offsets[valueRef], refSize, offsets)
                }
                map
            }

            else -> null
        }
    }

    /** Длина коллекции/строки/данных: если info<0xF, длина равна info; в противном случае длина сохраняется в последующем объекте int. */
    private fun elementCount(
        bytes: ByteArray,
        offset: Int,
        info: Int,
    ): Pair<Int, Int> {
        if (info != 0xF) return info to offset + 1
        if (offset + 1 >= bytes.size) {
            throw IllegalArgumentException("Отсутствует байт длины (смещение=$offset, тело=${bytes.size}B)")
        }
        val intMarker = u8(bytes[offset + 1])
        if (intMarker ushr 4 != 0x1) {
            throw IllegalArgumentException(
                "Ожидаемая длина объекта int, получена 0x${intMarker.toString(16)} (смещение = $offset" +
                    "контекст =" +
                    bytes
                        .copyOfRange(offset, minOf(offset + 8, bytes.size))
                        .joinToString(" ") { "%02x".format(it) } + "）",
            )
        }
        val intSize = 1 shl (intMarker and 0xF)
        // Расширенная длина должна читаться как **беззнаковая**: когда DiPlay отправляет данные 182B, запишите 0xB6 и
        // читайте как подписанное.

        // -74, copyOfRange(51, -23) напрямую вызывает IAE — вся команда iAPSendMessage будет рассматриваться как
        // «Нераспознанный» отброшен (фактическое расположение на 20 октября 2026 г., то же семейство ошибок, что и
        // расширение целочисленного отрицательного знака)

        val count = readUint(bytes, offset + 2, intSize)
        if (count < 0 || count > MAX_OBJECTS) {
            throw IllegalArgumentException("Поле длины неверно (count=$count offset=$offset)")
        }
        return count.toInt() to offset + 2 + intSize
    }

    private const val MAX_OBJECTS = 1 shl 20

    private fun readUint(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        if (size < 0 || size > 8) throw IllegalArgumentException("Недопустимая ширина чтения (размер=$size, смещение=$offset)")
        if (offset < 0 || offset + size > bytes.size) {
            throw IllegalArgumentException(
                "Чтение за пределами границ (смещение=$offset size=$size body=${bytes.size}B)——" +
                    "Он появится, если трейлер не соответствует фактической длине сообщения.",
            )
        }
        var value = 0L
        repeat(size) { value = (value shl 8) or u8(bytes[offset + it]).toLong() }
        return value
    }

    private fun u8(byte: Byte): Int = byte.toInt() and 0xFF
}
