package io.github.shaonialife.cardeck.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * анализ медиа-ключа HID автомобиля Consumer Control (фактическое испытание Audi eso_playback).
 *
 * Семантика ключа   (фактическое измерение 17 октября 2026 г.): **значение байта в отчете о массиве является
 * индексом массива использования** ——
 * Audi нажимает «Следующая песня», чтобы получить 0x05, что соответствует использованию [5] = 0xB5; «Предыдущая
 * песня» 0x06→использование[6]=0xB6.
 * Вначале   использовал байты непосредственно в качестве идентификатора использования и получил 0x05/0x06 → нет
 * соответствующего ключа (отзывы пользователей: «получил сообщение, но не вырезал песню»).
 */
class ConsumerKeysTest {
    /** Исходный текст дескриптора/сообщения записывается в побайтовом формате, что облегчает побайтовое сравнение с шестнадцатеричным форматом журнала. */
    private fun hex(text: String): ByteArray =
        text
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** Audi/info hidDevices[0].hidDescriptor исходный текст (46B). */
    private val audiDescriptor =
        hex(
            "05 0c 09 01 a1 01 15 00 25 08 05 0c 0a 00 00 00 0a b0 00 0a b1 00 0a b3 00 0a b4 00 0a b5 00 0a b6 " +
                "00 0a b9 00 0a bc 00 75 08 95 01 81 00 c0",
        )

    @Test
    fun descriptorParsedAsArrayWithNineUsages() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)
        assertNotNull("должен уметь разбирать Consumer картографирование", map)
        assertEquals(true, map!!.isArray)
        assertEquals(9, map.usages.size)
        // Порядок должен соответствовать объявлению дескриптора (от этого зависит семантика индекса)
        assertEquals(
            listOf(0x0000, 0xB0, 0xB1, 0xB3, 0xB4, 0xB5, 0xB6, 0xB9, 0xBC),
            map.usages,
        )
    }

    @Test
    fun reportValueIsArrayIndexNotUsageId() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)!!
        // Фактическое измерение: следующая песня по байту 0x05, предыдущая песня 0x06.
        assertEquals(0xB5, map.usageFor(0x05))
        assertEquals(0xB6, map.usageFor(0x06))
        // Урок: если вы используете 0x05 непосредственно в качестве идентификатора использования, вы получите
        // «Неизвестно» — эти два утверждения блокируют правильную семантику.

        assertEquals(0, ConsumerKeys.keyCodeFor(0x05))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(0xB5))
        // воспроизведение/пауза = индекс 1/2
        assertEquals(0xB0, map.usageFor(1))
        assertEquals(0xB1, map.usageFor(2))
        // 0 и за пределами = нет кнопки
        assertNull(map.usageFor(0))
        assertNull(map.usageFor(20))
    }

    @Test
    fun nonConsumerDevicesAreNotMediaKeys() {
        // Решение основано на дескрипторе, а не на длине сообщения (ручка DiPlay=4B, телефония=1B будет перекрываться с
        // мультимедийной клавишей):

        // Дескриптор телефонии (страница «Телефония» 0x0B) не может рассматриваться как медиа-ключ — его использование
        // 0xB3 будет неправильно истолковано.

        // Будет звучать «Fast Forward».
        val telephonyDescriptor =
            hex(
                "05 0b 09 07 a1 01 15 00 25 11 05 0b 09 00 09 20 09 21 09 26 09 2f 09 b0 09 b1 09 b2 09 b3 75 08 95 " +
                    "01 81 00 c0",
            )
        assertNull(ConsumerKeys.parseDescriptor(telephonyDescriptor))
    }

    @Test
    fun diplayMediaDescriptorUsesIndexSemantics() {
        // Медиа-дескриптор DiPlay (тестовый стенд): массив использования, 6 элементов, сообщаемые значения также являются индексами.
        val diplayMedia =
            hex(
                "05 0c 09 01 a1 01 15 00 25 06 05 0c 0a 00 00 00 0a b0 00 0a b1 00 0a cd 00 0a b5 00 0a b6 00 0a 9e " +
                    "02 02 75 08 95 01 81 00 c0",
            )
        val map = ConsumerKeys.parseDescriptor(diplayMedia)!!
        assertEquals(0xB0, map.usageFor(1))
        assertEquals(0xB1, map.usageFor(2))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(map.usageFor(4)!!))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ConsumerKeys.keyCodeFor(map.usageFor(5)!!))
    }

    @Test
    fun usageMapsToAndroidMediaKeys() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, ConsumerKeys.keyCodeFor(0xB0))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, ConsumerKeys.keyCodeFor(0xB1))
        assertEquals(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, ConsumerKeys.keyCodeFor(0xB3))
        assertEquals(KeyEvent.KEYCODE_MEDIA_REWIND, ConsumerKeys.keyCodeFor(0xB4))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(0xB5))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ConsumerKeys.keyCodeFor(0xB6))
        assertEquals(KeyEvent.KEYCODE_MEDIA_STOP, ConsumerKeys.keyCodeFor(0xB9))
        // Повторить Нет соответствующего ключа Android → 0 (игнорируется вызывающим абонентом)
        assertEquals(0, ConsumerKeys.keyCodeFor(0xBC))
    }

    @Test
    fun descriptorWithoutConsumerPageReturnsNull() {
        // Дескриптор сенсорного экрана Audi (uuid=1): Общий рабочий стол/дигитайзер, без потребительской страницы
        val touchDescriptor =
            hex(
                "05 0d 09 04 a1 01 05 0d 09 22 a1 02 05 0d 09 33 15 00 25 01 75 01 95 01 81 02 c0 c0",
            )
        assertNull(ConsumerKeys.parseDescriptor(touchDescriptor))
    }

    @Test
    fun allDescriptorUsagesNamed() {
        listOf(0xB0, 0xB1, 0xB3, 0xB4, 0xB5, 0xB6, 0xB9, 0xBC).forEach { usage ->
            assertFalse(
                "usage=0x%02x должно быть читабельно".format(usage),
                ConsumerKeys.nameOf(usage).startsWith("неизвестно"),
            )
        }
    }

    // ---- Honda HFT (Alpine 8A250-30A): отчет о растровом изображении (переменная) ----

    /**
     * Дескриптор (33B), включенный в машину  0x6800 StartHID, `81 02` = Данные/Переменная/Абсолютное значение:
     * Каждое из пяти использований       занимает 1 бит, а последний `75 03 95 01 81 03` занимает 3 бита.
     */
    private val hondaDescriptor =
        hex(
            "05 0c 09 01 a1 01 15 00 25 01 75 01 95 05 09 b0 09 b1 09 b5 09 b6 09 b9 81 02 75 03 95 01 81 03 c0",
        )

    @Test
    fun hondaDescriptorIsBitmapWithFiveUsages() {
        val map = ConsumerKeys.parseDescriptor(hondaDescriptor)!!
        assertFalse("`81 02` это растровый отчет，Нет Array", map.isArray)
        assertEquals(listOf(0xB0, 0xB1, 0xB5, 0xB6, 0xB9), map.usages)
    }

    @Test
    fun hondaBitmapReportsDecodeToMediaKeys() {
        val map = ConsumerKeys.parseDescriptor(hondaDescriptor)!!
        // Фактическое сообщение об измерении: нажмите и удерживайте кнопку «Следующая песня», непрерывно нажимая 0x04, отпустите 0x00.
        assertEquals(listOf(0xB5), map.usagesFor(byteArrayOf(0x04)))
        assertEquals(listOf(0xB6), map.usagesFor(byteArrayOf(0x08)))
        assertEquals(listOf(0xB0), map.usagesFor(byteArrayOf(0x01)))
        assertEquals(listOf(0xB1), map.usagesFor(byteArrayOf(0x02)))
        assertEquals(listOf(0xB9), map.usagesFor(byteArrayOf(0x10)))
        assertEquals(emptyList<Int>(), map.usagesFor(byteArrayOf(0x00)))
        // Старая реализация рассматривает 0x04 непосредственно как идентификатор использования → соответствующий ключ
        // отсутствует. Это первопричина "клавиша нажата, но песня не переключается"

        assertEquals(0, ConsumerKeys.keyCodeFor(0x04))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, ConsumerKeys.keyCodeFor(map.usagesFor(byteArrayOf(0x04))[0]))
    }

    @Test
    fun audiArrayReportStillWorksThroughUsagesFor() {
        val map = ConsumerKeys.parseDescriptor(audiDescriptor)!!
        assertEquals(listOf(0xB5), map.usagesFor(byteArrayOf(0x05)))
        assertEquals(listOf(0xB6), map.usagesFor(byteArrayOf(0x06)))
        assertEquals(emptyList<Int>(), map.usagesFor(byteArrayOf(0x00)))
    }
}
