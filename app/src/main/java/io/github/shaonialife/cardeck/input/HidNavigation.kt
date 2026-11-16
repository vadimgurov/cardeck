package io.github.shaonialife.cardeck.input

import android.view.KeyEvent

/**
 * автомобильная «навигация» HID (ручка iDrive/четырехпозиционный рычаг/клавиша подтверждения) → клавиша направления Android.
 *
 * Разделение труда между   и [ConsumerKeys]:
 * — Страница потребителя (0x0C) = медиа-ключ → `AudioManager.dispatchMediaKeyEvent`, действует глобально и не требует фокуса;
 * — Колесо (0x38)/Шляпа (0x39) = Ручка/Направление → **DPAD_*** страницы общего рабочего стола (0x01),
 * должен быть введен в виртуальный экран и обработан системой фокусировки (`CarInputBridge.injectNavigationKey`).
 *
 * **Не запрограммировано жестко в зависимости от модели автомобиля**: сначала используйте дескриптор HID для
 * определения (оно считается навигационным устройством, если оно имеет колесо/шляпу и не является дигитайзером),
 * Если дескриптор   отсутствует, используется длина сообщения (2–4 байта, не 5 байт).
 *
 * Формат сообщения   (для BMW iD8.5 фактическая калибровка измерений: [rawLogEnabled] имеет исходный
 * шестнадцатеричный код в журнале после открытия):
 * — последний байт интерпретируется как **подписанный** как шаг ручки (по часовой стрелке — положительное значение);
 * — предыдущий байт анализируется как растровая кнопка: бит0 = подтверждение, 1 = вверх, 2 = вниз, 3 = влево, 4 = вправо, 5 = возврат.
 * — когда дескриптор объявляет переключатель Hat, вместо этого используется семантика «младшие 4 бита = 8-позиционная шляпа».
 */
object HidNavigation {
    /** Переключатель журнала необработанных сообщений (Настройки → Дисплей → «Журнал необработанных сообщений HID»). */
    @Volatile
    var rawLogEnabled: Boolean = false

    private const val PAGE_GENERIC_DESKTOP = 0x01
    private const val PAGE_BUTTON = 0x09
    private const val PAGE_DIGITIZER = 0x0D
    private const val USAGE_WHEEL = 0x38
    private const val USAGE_HAT_SWITCH = 0x39

    /** Максимальное количество шагов, которые необходимо выполнить в одном отчете (избегайте одновременного добавления десятков кнопок, чтобы отключить пользовательский интерфейс при быстром повороте ручки). */
    private const val MAX_STEPS = 5

    private val lastButtonMask = HashMap<String, Int>()
    private val lastHat = HashMap<String, Int>()

    /** Сообщите, является ли соответствующее устройство навигационным типом (с колесиком/шляпой, а не с сенсорным экраном). */
    fun isNavigationDevice(descriptor: ByteArray?): Boolean {
        if (descriptor == null || descriptor.isEmpty()) return false
        if (hasPage(descriptor, PAGE_DIGITIZER)) return false
        return hasPage(descriptor, PAGE_GENERIC_DESKTOP) &&
            (hasUsage(descriptor, USAGE_WHEEL) || hasUsage(descriptor, USAGE_HAT_SWITCH))
    }

    /**
     * обрабатывает кадр навигационного отчета и возвращает последовательность ключей (0 или более), которую необходимо ввести.
     *
     * @param uuid Uuid HID устройства (состояние границы того же uuid поддерживается отдельно)
     */
    fun handle(
        uuid: String,
        descriptor: ByteArray?,
        report: ByteArray,
    ): List<Int> {
        if (report.isEmpty()) return emptyList()
        val nav = isNavigationDevice(descriptor) || (descriptor == null && report.size in 2..4)
        if (!nav) return emptyList()
        if (rawLogEnabled) {
            android.util.Log.i(
                "CarDeck-HID",
                "HID-RAW uuid=$uuid bytes=" + report.joinToString(" ") { "%02X".format(it) } +
                    " nav=${isNavigationDevice(descriptor)}",
            )
        }
        return if (descriptor != null && hasUsage(descriptor, USAGE_HAT_SWITCH) && report.size == 1) {
            parseHat(uuid, report[0].toInt() and 0xFF)
        } else {
            parseWheelAndButtons(uuid, report)
        }
    }

    /** 8 До конца: 0=вверху 1=вверху справа 2=вправо 3=внизу справа 4=внизу 5=внизу слева 6=слева 7=вверху слева, остальное = свободно. */
    private fun parseHat(
        uuid: String,
        value: Int,
    ): List<Int> {
        val hat = value and 0x0F
        if (hat == lastHat[uuid]) return emptyList()
        lastHat[uuid] = hat
        return when (hat) {
            0 -> listOf(KeyEvent.KEYCODE_DPAD_UP)
            1 -> listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            2 -> listOf(KeyEvent.KEYCODE_DPAD_RIGHT)
            3 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            4 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN)
            5 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            6 -> listOf(KeyEvent.KEYCODE_DPAD_LEFT)
            7 -> listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)
            else -> emptyList()
        }
    }

    /** Последний байт = шаг со знаком, предыдущий байт = растровое изображение кнопки (срабатывает по краю). */
    private fun parseWheelAndButtons(
        uuid: String,
        report: ByteArray,
    ): List<Int> {
        val keys = ArrayList<Int>(3)
        val buttonBytes = if (report.size >= 2) report.size - 1 else report.size
        var mask = 0
        for (i in 0 until buttonBytes.coerceAtMost(2)) {
            mask = mask or ((report[i].toInt() and 0xFF) shl (8 * i))
        }
        val pressed = mask and lastButtonMask[uuid].orZero().inv()
        lastButtonMask[uuid] = mask
        if (pressed != 0) {
            if ((pressed and 0x01) != 0) keys += KeyEvent.KEYCODE_DPAD_CENTER
            if ((pressed and 0x02) != 0) keys += KeyEvent.KEYCODE_DPAD_UP
            if ((pressed and 0x04) != 0) keys += KeyEvent.KEYCODE_DPAD_DOWN
            if ((pressed and 0x08) != 0) keys += KeyEvent.KEYCODE_DPAD_LEFT
            if ((pressed and 0x10) != 0) keys += KeyEvent.KEYCODE_DPAD_RIGHT
            if ((pressed and 0x20) != 0) keys += KeyEvent.KEYCODE_BACK
        }
        if (report.size >= 2) {
            val delta = report[report.size - 1].toInt()
            if (delta != 0) {
                val key = if (delta > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                repeat(kotlin.math.abs(delta).coerceAtMost(MAX_STEPS)) { keys += key }
            }
        }
        return keys
    }

    private fun Int?.orZero(): Int = this ?: 0

    /**
     * Появляется ли страница использования в дескрипторе .
     * выполняет только «сканирование записей»: UsagePage — это глобальный элемент (префикс `0000 0101`? фактический bTag=0x0),
     * Здесь выражение «значение любого элемента данных размером 1–2 байта равно странице» не совсем точно совпадает
     * — неправильная оценка влияет только на распределение.
     * не использует сенсорный экран в качестве ручки (сначала исключается страница дигитайзера).
     */
    private fun hasPage(
        descriptor: ByteArray,
        page: Int,
    ): Boolean {
        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size =
                when (prefix and 0x03) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            if (index + 1 + size > descriptor.size) return false
            var value = 0
            for (offset in 0 until size) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            if (type == 1 && tag == 0x0 && value == page) return true
            index += 1 + size
        }
        return false
    }

    /** Появляется ли определенное Usage в дескрипторе (локальный элемент bTag=0x0, 1/2 байта). */
    private fun hasUsage(
        descriptor: ByteArray,
        usage: Int,
    ): Boolean {
        var index = 0
        while (index < descriptor.size) {
            val prefix = descriptor[index].toInt() and 0xFF
            val size =
                when (prefix and 0x03) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            if (index + 1 + size > descriptor.size) return false
            var value = 0
            for (offset in 0 until size) {
                value = value or ((descriptor[index + 1 + offset].toInt() and 0xFF) shl (8 * offset))
            }
            if (type == 2 && tag == 0x0 && value == usage) return true
            index += 1 + size
        }
        return false
    }

    /** Удобочитаемое имя клавиши клавиатуры (для целей регистрации). */
    fun nameOf(keyCode: Int): String =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "на"
            KeyEvent.KEYCODE_DPAD_DOWN -> "вниз"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ушел"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "правильно"
            KeyEvent.KEYCODE_DPAD_CENTER -> "Подтвердить"
            KeyEvent.KEYCODE_BACK -> "Возврат"
            else -> "$keyCode"
        }
}
