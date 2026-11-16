package io.github.shaonialife.cardeck.input

import android.view.KeyEvent

/**
 * Отчет Car Consumer Control HID → Медиа-ключи Android.
 *
 * Мультимедийные клавиши автомобиля/руля используют путь HID в CarPlay (а не команду события AirPlay), и устройство обычно
 * `eso_playback` (Audi `/info` hidDevices[0], uuid=0).
 *
 * **Байт сообщения является индексом массива использования, а не идентификатором использования** (фактическое
 * измерение 17 октября 2026 г.):
 * Дескриптор Audi `LogicalMin 0 / LogicalMax 8 + ReportSize 8 + ReportCount 1 +
 * Input(Data,Array,Abs)`, список использования объявляет 9 элементов по порядку (0x0000, 0xB0, 0xB1, 0xB3, 0xB4,
 * 0xB5, 0xB6, 0xB9, 0xBC) — 1-байтовый отчет в разделе «Семантика массива» дает «число».
 * Фактическое измерение  : нажмите «Следующая песня», чтобы получить 0x05, нажмите «Предыдущая песня», чтобы
 * получить 0x06, что соответствует использованию [5] = 0xB5 соответственно.
 * Использование  [6]=0xB6; если байт напрямую используется в качестве идентификатора использования, 0x05/0x06 →
 * соответствующий ключ отсутствует (исходная ошибка).
 *
 * Поэтому здесь **анализируйте дескриптор HID, поставляемый с устройством**, чтобы получить последовательность
 * массива использования (без жесткого кодирования определенной машины),
 * Затем нажмите нижний индекс, чтобы получить значение; когда дескриптор недоступен, он возвращается к семантике
 * прямого чтения «байты - это идентификатор использования».
 */
object ConsumerKeys {
    /** Страница потребителя (наиболее часто используемая мультимедийная страница за пределами Generic Desktop). */
    private const val USAGE_PAGE_CONSUMER = 0x0C

    /**
     * Сопоставление потребительских ключей для HID-устройства.
     *
     * @param Использование Идентификатор использования, упорядоченный по нижнему индексу (анализ самодескриптора,
     * сохранение порядка объявления)
     * @param isArray true=Значение сообщения представляет собой индекс использования (стандартная семантика
     * массива); false = Значение представляет собой идентификатор использования.
     */
    class KeyMap(
        val usages: List<Int>,
        val isArray: Boolean,
    ) {
        /** Значение отчета → Идентификатор использования; 0 или ноль, если выходит за пределы (0 = ни одна клавиша не нажата/отпущена). */
        fun usageFor(reportValue: Int): Int? {
            if (reportValue == 0) return null
            return if (isArray) {
                usages.getOrNull(reportValue)?.takeIf { it != 0 }
            } else {
                reportValue
            }
        }

        /**
         * HID-сообщение → На этот раз нажат сбор данных об использовании.
         *
         * Эти две семантики отчета должны быть разделены. Если они смешаны, ни одна из клавиш не будет нажата:
         * — Массив (`81 00`): Значение байта — это индекс использования, и одновременно может быть только одно значение;
         * — переменная/растровое изображение (`81 02`): **каждое использование занимает 1 бит** и в отчете расширяется на биты.
         *
         * Дескриптор StartHID           Honda HFT (Alpine 8A250-30A) представляет собой именно растровое изображение:
         * `… 75 01 95 05 09 B0 09 B1 09 B5 09 B6 09 B9 81 02 …` → отчет размером 1 байт,
         * бит0=воспроизведение (0xB0), бит1=пауза (0xB1), бит2=следующая песня (0xB5), бит3=предыдущая песня (0xB6),
         * бит4=Стоп (0xB9); сообщить 0x04 = следующая песня, 0x00 = выпустить все.
         */
        fun usagesFor(report: ByteArray): List<Int> {
            if (report.isEmpty()) return emptyList()
            if (isArray) return listOfNotNull(usageFor(report[0].toInt() and 0xFF))
            val pressed = ArrayList<Int>(usages.size)
            usages.forEachIndexed { index, usage ->
                val byteIndex = index / 8
                if (byteIndex >= report.size || usage == 0) return@forEachIndexed
                val bit = (report[byteIndex].toInt() shr (index % 8)) and 0x01
                if (bit == 1) pressed += usage
            }
            return pressed
        }
    }

    /**
     * анализирует массив Consumer Usage в дескрипторе HID.
     * принимает только элемент Usage в коллекции приложений первого «UsagePage=Consumer»;
     * Возвращает значение null, если дескриптор       не содержит страницу потребителя (устройство не является медиа-ключом).
     *
     * поддерживает кодирование коротких записей (bSize: 0→0 байт/1→1/2→2/3→4).
     */
    fun parseDescriptor(descriptor: ByteArray): KeyMap? {
        var index = 0
        var page = 0
        var inConsumerApp = false
        var sawConsumerPage = false
        val usages = ArrayList<Int>()
        var isArray = true

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
                // Глобальный элемент: Страница использования.
                type == 1 && tag == 0x0 -> {
                    page = value
                    if (value == USAGE_PAGE_CONSUMER) sawConsumerPage = true
                }
                // Глобальный элемент: логический максимум (используется для определения диапазона индексов в отчете о массиве).
                type == 1 && tag == 0x2 -> Unit
                // Локальный элемент: Использование (1 или 2 байта) — массив в текущем потребительском приложении.
                type == 2 && tag == 0x0 -> {
                    if (inConsumerApp && sawConsumerPage) usages += value
                }
                // Основной элемент: Коллекция/EndCollection
                type == 0 && tag == 0xA -> {
                    // Включайте сбор только в том случае, если текущая страница является потребителем и находится в приложении
                    // верхнего уровня.

                    if (!inConsumerApp && page == USAGE_PAGE_CONSUMER) inConsumerApp = true
                }
                type == 0 && tag == 0xC -> {
                    if (inConsumerApp) return KeyMap(usages, isArray)
                }
                // Основной элемент: Ввод — бит1=0 означает массив (значение — нижний индекс), =1 означает переменную (значение —
                // растровое изображение).

                // бит0=1 — константа (бит дополнения в конце дескриптора, например `75 03 95 01 81 03`),
                // Без участия в ключевой семантике нельзя допустить перезаписи предыдущего реального формата отчета.
                type == 0 && tag == 0x8 -> {
                    if ((value and 0x01) == 0) isArray = (value and 0x02) == 0
                }
            }
        }
        if (sawConsumerPage && usages.isNotEmpty()) return KeyMap(usages, isArray)
        return null
    }

    /** Идентификатор использования → Медиа-ключ Android; возвращает 0, если нет соответствующего ключа (например, Повтор). */
    fun keyCodeFor(usage: Int): Int =
        when (usage) {
            0xB0 -> KeyEvent.KEYCODE_MEDIA_PLAY
            0xB1 -> KeyEvent.KEYCODE_MEDIA_PAUSE
            0xB3 -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            0xB4 -> KeyEvent.KEYCODE_MEDIA_REWIND
            0xB5 -> KeyEvent.KEYCODE_MEDIA_NEXT
            0xB6 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            0xB9 -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> 0
        }

    /** Человекочитаемое имя (для ведения журнала). */
    fun nameOf(usage: Int): String =
        when (usage) {
            0xB0 -> "играть"
            0xB1 -> "пауза"
            0xB3 -> "перемотка вперед"
            0xB4 -> "Перемотка назад"
            0xB5 -> "Следующая песня"
            0xB6 -> "Предыдущая песня"
            0xB9 -> "стоп"
            0xBC -> "петля"
            else -> "Неизвестно (0x%02x)".format(usage)
        }
}
