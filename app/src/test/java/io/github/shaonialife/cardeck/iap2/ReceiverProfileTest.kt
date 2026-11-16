package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.net.AirPlayFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Оценка изображения транспортного средства-машины (чистая функция): комбинация сигналов → эра/wifiGen + интерпретируемая основа. */
class ReceiverProfileTest {
    @Test
    fun unknownWhenNoSignals() {
        val p = ReceiverProfile.classify(ReceiverProfile.Signals())
        assertEquals(ReceiverProfile.Era.UNKNOWN, p.era)
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
        assertTrue(p.reasons.isNotEmpty())
    }

    @Test
    fun preHomeKitWhenBit38Clear() {
        // Audi 4xxx / CarPlay 210.81: функции Нет бит38 → обычный текст + файл нулевого ключа AES-CTR
        val features =
            (AirPlayFeatures.parse("0x5653aee2,0x61") ?: 0L) and
                AirPlayFeatures.HK_PAIRING_AND_ENCRYPT.inv()
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = features, srcvers = "210.81"),
            )
        assertEquals(ReceiverProfile.Era.PRE_HOMEKIT, p.era)
        assertTrue(p.reasons.any { it.contains("Нет bit38") })
    }

    @Test
    fun modernWhenBit38Set() {
        val features =
            (AirPlayFeatures.parse("0x5653aee2,0x61") ?: 0L) or
                AirPlayFeatures.HK_PAIRING_AND_ENCRYPT
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = features, srcvers = "450.14.2"),
            )
        assertEquals(ReceiverProfile.Era.MODERN, p.era)
    }

    @Test
    fun wifiGenLegacyWhenListsDeclareOldProtocol() {
        // Форма Audi: список отправки содержит 0x5703
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    name = "Audi_MMI_3315",
                    model = "AUDI 5163",
                    manufacturer = "DELPHI",
                    sendIds = setOf(0x4301, 0xea03),
                    receiveIds = setOf(0x4e0c, 0x4e09, 0x4300, 0xea00),
                ),
            )
        // Список Audi скуден (2+5 элементов), а 0x57xx в нем не указан — хотя на самом деле он соответствует старому
        // протоколу, список не является доказательством:

        // Разреженный список будет оценен как НЕИЗВЕСТНЫЙ (консервативный старый протокол), а полный список будет оценен как НОВЫЙ.
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
        assertEquals("Audi_MMI_3315 AUDI 5163 DELPHI", p.identityKey)
    }

    @Test
    fun wifiGenNewWhenFullListsLackOldProtocol() {
        // Форма BMW iD9: оба списка полные (10+ позиций), ни один 0x5702/0x5703
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(
                    sendIds = setOf(0x4301, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157),
                    receiveIds = setOf(0x4300, 0x4e09, 0xaa00, 0xaa02, 0x5001, 0x5201, 0x4158),
                ),
            )
        assertEquals(ReceiverProfile.WifiGen.NEW, p.wifiGen)
        assertTrue(p.reasons.any { it.contains("параметры точки доступа предоставляются через 0x4301") })
    }

    @Test
    fun sparseSendListAloneStaysUnknown() {
        // Одиночный разреженный список (2 элемента) Нет 0x5703: Не считается «новым» - контрпример Audi доказывает разреженный список
        // Вы также можете использовать старый протокол без указания 0x57xx.
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(sendIds = setOf(0x4301, 0xea03)),
            )
        assertEquals(ReceiverProfile.WifiGen.UNKNOWN, p.wifiGen)
    }

    @Test
    fun describeCarriesReasons() {
        val p =
            ReceiverProfile.classify(
                ReceiverProfile.Signals(features = 1L shl 38, srcvers = "450.14.2", sendIds = setOf(0x5703)),
            )
        val text = ReceiverProfile.describe(p)
        assertTrue(text.contains("era=MODERN"))
        assertTrue(text.contains("wifiGen=LEGACY"))
        assertTrue(text.contains("srcvers=450.14.2"))
    }

    // ---- Определение генерации ссылки (linkGenerationOf)----
    // Цена ошибки в неправильном направлении асимметрична: Audi была неправильно оценена EVENT_LINK = маркер При
    // согласовании зависает пользовательский интерфейс автомобиля (необходим перезапуск).

    // автомобиль); Honda была неправильно оценена CLASSIC_MIB3 = Мультимедийные клавиши отключены без уведомления.
    // Три условия (delphi+audi+разреженный список)

    // Это размеренный почерк Audi 3315/0628. Как только формулировка прошивки будет изменена, она автоматически
    // вернется к аварийному поведению — варианты использования закрепляются один за другим.

    private val audi3315Signals =
        ReceiverProfile.Signals(
            name = "Audi_MMI_3315",
            model = "AUDI 5163",
            manufacturer = "DELPHI",
            // Реальная форма 0x1D01: отправить 2 предмета + получить 5 предметов (реальный журнал машины 2026-10-23)
            sendIds = setOf(0x4301, 0xea03),
            receiveIds = setOf(0x5703, 0x4e09, 0x4e0b, 0x4e0d, 0x4e0e),
        )

    @Test
    fun delphiAudiWithSparseListsIsClassicMib3() {
        assertEquals(ReceiverProfile.LinkGeneration.CLASSIC_MIB3, ReceiverProfile.linkGenerationOf(audi3315Signals))
    }

    @Test
    fun fullListsAreNeverClassicMib3() {
        // Общий список ≥10 элементов (форма iD9): даже если поле идентификации попадает случайно, оно не будет расценено как КЛАССИЧЕСКОЕ.
        val p =
            ReceiverProfile.linkGenerationOf(
                audi3315Signals.copy(
                    sendIds = setOf(0x4301, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157),
                    receiveIds = setOf(0x4300, 0x4e09, 0xaa00, 0xaa02, 0x5001, 0x5201, 0x4158),
                ),
            )
        assertEquals(ReceiverProfile.LinkGeneration.EVENT_LINK, p)
    }

    @Test
    fun missingManufacturerOrModelStaysEventLink() {
        // Прошивка занижает идентификационные параметры (в том числе и усечение NUL): Я лучше отвалюсь от совместимой
        // стороны и не буду гадать.

        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(manufacturer = null)),
        )
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(model = null)),
        )
    }

    @Test
    fun missingListsStaysEventLink() {
        // 0x1D01 не предоставляет двойного списка: он разрежен, не может быть доказан и не является КЛАССИЧЕСКИМ.
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(sendIds = null)),
        )
        assertEquals(
            ReceiverProfile.LinkGeneration.EVENT_LINK,
            ReceiverProfile.linkGenerationOf(audi3315Signals.copy(receiveIds = null)),
        )
    }

    @Test
    fun nonAudiDelphiHardwareStaysEventLink() {
        // Honda HFT (детали Alpine) и другие детали, произведенные DELPHI не Audi: форма CLASSIC отсутствует.
        val honda =
            ReceiverProfile.Signals(
                name = "HFT",
                model = "Honda HFT",
                manufacturer = "ALPINE",
                sendIds = setOf(0x4301),
                receiveIds = setOf(0x5703, 0x6800),
            )
        assertEquals(ReceiverProfile.LinkGeneration.EVENT_LINK, ReceiverProfile.linkGenerationOf(honda))
        val delphiNonAudi = audi3315Signals.copy(model = "T-Roc 23")
        assertEquals(ReceiverProfile.LinkGeneration.EVENT_LINK, ReceiverProfile.linkGenerationOf(delphiNonAudi))
    }
}
