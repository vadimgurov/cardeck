package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило сортировки памяти аудиоустройств ([AudioProfileMemory.order]).
 *
 * Предыстория   (фактическое испытание Honda HFT / Alpine 19 октября 2026 г.): После выбора «Видео + аудио»
 * сеанс может быть установлен, и видео работает нормально.
 * Однако после начала потоковой передачи звука в течение примерно 6 секунд автомобильное устройство удалило весь
 * сеанс AirPlay; только нажатие на видео было стабильным. Двигатель не скажет вам, какая передача.
 * Звук   несовместим. Вы можете сделать вывод только по «времени выживания после запуска потока», сохранить
 * вывод в соответствии с машиной автомобиля и переключить передачу при следующем подключении.
 */
class AudioProfileMemoryTest {
    // Истинное заявление об этой машине: размер медиафайла составляет всего 44,1 КБ, а размер файла совместимости — 48 КБ/44,1 КБ.
    private val hondaCandidates =
        listOf(
            AudioProfile("media", 1L shl 11, 44_100, 250),
            AudioProfile("compatibility", 1L shl 15, 48_000, 250),
            AudioProfile("compatibility", 1L shl 11, 44_100, 250),
        )

    @Test
    fun emptyMemoryKeepsBaselineOrder() {
        assertEquals(hondaCandidates, AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry()))
    }

    @Test
    fun workingProfileGoesFirst() {
        val compat48 = hondaCandidates[1].key
        val ordered = AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(working = compat48))
        assertEquals(compat48, ordered.first().key)
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun rejectedProfileGoesLastButStaysAsFallback() {
        val media44 = hondaCandidates[0].key
        val ordered =
            AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(rejected = setOf(media44)))
        assertEquals(media44, ordered.last().key)
        // Все еще в списке кандидатов: шанс использовать после смены прошивки еще есть
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun allRejectedKeepsTryingInsteadOfGoingSilent() {
        val all = hondaCandidates.map { it.key }.toSet()
        val ordered = AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(rejected = all))
        // Описание всех приговоренных к смертной казни не соответствует памяти (для замены автомобиля/обновления
        // прошивки используется один и тот же идентификационный ключ) - попробуйте все как обычно, чтобы избежать постоянного молчания

        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun disabledMeansNoAudioAtAll() {
        assertTrue(
            AudioProfileMemory
                .order(hondaCandidates, AudioProfileMemory.Entry(disabled = true))
                .isEmpty(),
        )
        assertFalse(AudioProfileMemory.order(emptyList(), AudioProfileMemory.Entry()).isNotEmpty())
    }

    @Test
    fun profileKeyIgnoresLatencySoRelearningWorks() {
        val a = AudioProfile("media", 1L shl 11, 44_100, 250)
        val b = AudioProfile("media", 1L shl 11, 44_100, 400)
        assertEquals(a.key, b.key)
        // Разница в случае (Медиа/медиа появляется в /info) не может вызвать два набора воспоминаний;
        // Ключ содержит как тип потока, так и кодировку: один и тот же формат — совершенно другой путь на 102/AAC и 100/PCM.
        assertEquals("100|media|2048|PCM", AudioProfile("Media", 1L shl 11, 44_100, 250).key)
        assertEquals(
            "102|media|4194304|AAC_LC",
            AudioProfile(
                "media",
                1L shl 22,
                44_100,
                250,
                streamType = 102L,
                codec = AudioCodec.AAC_LC,
            ).key,
        )
    }

    @Test
    fun receiverKeyIsStableAcrossCasingAndPunctuation() {
        val a = AudioProfileMemory.receiverKey("Alpine Electronics (China) Co., Ltd. 8A250-30A")
        val b = AudioProfileMemory.receiverKey("alpine electronics china co ltd 8a250 30a")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[a-z0-9_]+")))
        assertFalse(a == AudioProfileMemory.receiverKey("AUDI 3260 DELPHI"))
    }

    @Test
    fun setupFailureAloneNeverDisablesAudio() {
        // Отклонение/тайм-аут SETUP может быть просто арбитражем ресурсов автомобиля и машины или джиттером сети: только
        // сортировка на более раннюю версию не может уничтожить всю машину и машину.

        val id = "test receive setup-fail"
        AudioProfileMemory.recordRejected(null, id, "media|2048", setOf("media|2048"), markExhausted = false)
        val entry = AudioProfileMemory.load(null, id)
        assertTrue(entry.rejected.contains("media|2048"))
        assertFalse(entry.disabled)
    }

    @Test
    fun teardownOfEveryProfileDisablesAudio() {
        // Только неопровержимые доказательства, такие как «вся цепь была демонтирована после того, как начался поток»,
        // позволяют приговорить автозвука к смертной казни.

        val id = "test receive teardown-all"
        val all = setOf("media|2048", "compatibility|32768")
        AudioProfileMemory.recordRejected(null, id, "media|2048", all)
        AudioProfileMemory.recordRejected(null, id, "compatibility|32768", all)
        assertTrue(AudioProfileMemory.load(null, id).disabled)
        assertTrue(AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.load(null, id)).isEmpty())
    }
}
