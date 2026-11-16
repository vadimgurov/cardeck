package io.github.shaonialife.cardeck.input

/**
 * сенсорный анализ сообщений HID.
 *
 * два формата сообщений, **отличающиеся дескриптором HID устройства** (не запрограммированные жестко в
 * зависимости от модели автомобиля или uuid):
 * — **Многослотовый формат** (тестовый стенд DiPlay): 12 байт = 2 слота × 6 байт
 * [номер слота] [нажмите 0/1] [X u16LE][Y u16LE]; дескриптор — страница дигитайзера (0x0D), каждый палец содержит
 * Tip Switch + X/Y два 16-битных поля.
 * - **Абсолютные координаты одного пальца** (Audi MIB3 eso_touchScreen): 5 байт
 * [tip 1bit + 7bit прокладка][X u16LE][Y u16LE]; дескрипторы такие же, как на странице дигитайзера, но расположение полей другое.
 *
 * Порядок определения: сначала проверьте размер отчета (16 бит) X/Y в дескрипторе и наличие только одной коллекции пальцев;
 * Когда дескриптор   недоступен, он возвращается к критерию «длина сообщения» (5B → один палец, 12B+ → многослотовый).
 */
object TouchReports {
    const val TOUCH_UUID_HEX = "2a2a2a2a"
    const val KNOB_UUID_HEX = "2a2a2a2b"
    private const val SLOTS = 2
    private const val BYTES_PER_FINGER = 6

    data class Contact(
        val slot: Int,
        val xPixels: Int,
        val yPixels: Int,
        val down: Boolean,
    )

    /** Формат сенсорного сообщения (определяется дескриптором или длиной сообщения). */
    enum class Format { MULTI_SLOT, SINGLE_ABSOLUTE }

    /**
     * Определяет формат сенсорного экрана по дескриптору; возвращает значение null для страниц без дигитайзера (сенсорные устройства).
     *
     * Основа идентификации      : появляется страница дигитайзера (0x0D) и содержит 16-битный ввод X/Y → сенсорное устройство;
     * single refers to Collection (there is only one Application with 0x22=Contact Identifier in the descriptor)
     * и сообщение имеет 5-байтовый формат → SINGLE_ABSOLUTE.
     */
    fun formatFromDescriptor(descriptor: ByteArray): Format? {
        var page = 0
        var sawDigitizer = false
        var has16BitXy = false
        // Как один палец, так и несколько слотов содержат коллекцию идентификаторов контактов (0x22); используйте «X’s ReportSize=16»
        // В качестве общей точки идентификации между двумя форматами используются различия в длине дескриптора и
        // количестве байтов на палец.

        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size = prefix and 0x03
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            val dataBytes =
                when (size) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            if (index + 1 + dataBytes > descriptor.size) break
            var value = 0
            for (offset in 0 until dataBytes) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            index += 1 + dataBytes
            when {
                type == 1 && tag == 0x0 -> if (value == 0x0D) sawDigitizer = true
                // Размер отчета (глобальный тег элемента = 7) = 16 → X/Y — 16 бит (оба формата)
                type == 1 && tag == 0x7 -> if (value == 16) has16BitXy = true
            }
        }
        if (!sawDigitizer) return null
        if (!has16BitXy) return null
        // Дескриптор Audi с одним пальцем 62B, дескриптор DiPlay с двумя пальцами длиннее (включая коллекцию с двумя пальцами).
        // Оценка коллекции по одному пальцу: количество вхождений идентификатора контакта (0x09 0x22).
        val fingerCollections = countOccurrences(descriptor, byteArrayOf(0x09, 0x22))
        return if (fingerCollections <= 1) Format.SINGLE_ABSOLUTE else Format.MULTI_SLOT
    }

    private fun countOccurrences(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        var count = 0
        var index = 0
        while (index <= haystack.size - needle.size) {
            var match = true
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) {
                    match = false
                    break
                }
            }
            if (match) {
                count++
                index += needle.size
            } else {
                index++
            }
        }
        return count
    }

    /**
     * анализирует сенсорные сообщения.
     * @param Формат Формат определения дескриптора; при нулевом значении происходит возврат в соответствии с длиной
     * сообщения (5B=один палец, ≥12B=многослотовое)
     */
    fun parse(
        report: ByteArray,
        format: Format? = null,
    ): List<Contact>? {
        val resolved =
            format ?: when {
                report.size == 5 -> Format.SINGLE_ABSOLUTE
                report.size >= SLOTS * BYTES_PER_FINGER -> Format.MULTI_SLOT
                else -> return null
            }
        return when (resolved) {
            Format.SINGLE_ABSOLUTE -> parseSingleTouch(report)
            Format.MULTI_SLOT -> parseMultiSlot(report)
        }
    }

    /** Совместимость со старыми сайтами вызовов: выберите преобразователь по uuid (резервный путь, если дескриптор недоступен). */
    fun parseByUuid(
        uuid: String?,
        report: ByteArray,
    ): List<Contact>? =
        when (uuid) {
            TOUCH_UUID_HEX -> parseMultiSlot(report)
            // Устройство с одним пальцем (различный uuid для каждого автомобиля): используйте длину сообщения для
            // самостоятельной сертификации.

            else -> parse(report, format = null)
        }

    /** Анализ многослотового формата (тестовый стенд DiPlay: 12 байт, 2 слота). */
    fun parseMultiSlot(report: ByteArray): List<Contact> {
        if (report.size < SLOTS * BYTES_PER_FINGER) return emptyList()
        return (0 until SLOTS).mapNotNull { slot ->
            val offset = slot * BYTES_PER_FINGER
            Contact(
                slot = slot,
                down = report[offset + 1].toInt() and 0xFF == 1,
                xPixels = readU16Le(report, offset + 2),
                yPixels = readU16Le(report, offset + 4),
            )
        }
    }

    /** Абсолютные координаты одного пальца: 5 байт [tip 1bit][X u16LE][Y u16LE]. */
    fun parseSingleTouch(report: ByteArray): List<Contact> {
        if (report.size < 5) return emptyList()
        return listOf(
            Contact(
                slot = 0,
                down = report[0].toInt() and 0x01 != 0,
                xPixels = readU16Le(report, 1),
                yPixels = readU16Le(report, 3),
            ),
        )
    }

    private fun readU16Le(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)
}
