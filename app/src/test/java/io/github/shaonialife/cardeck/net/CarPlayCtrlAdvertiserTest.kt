package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ctrl Регрессионная защита для полей широковещательной идентификации mDNS.
 *
 * 19 октября 2026 г. Сравнение реальных автомобилей Audi MMI 3315 (`docs/PROTOCOL-NOTES.md` §9i):
 * 0.1.6 При использовании `name=sender` + `srcvers=950.7.1` машина выдаст 0x4301 в течение 0,65 секунды;
 * После замены   0.1.7 на `name=iPhone` + `srcvers=566.25.21` Catplay автомобиль и машина могут подключаться к ctrl-int.
 * Обнаружение   больше не будет отправлять **, 0x4301 никогда не придет. Эти два элемента являются единственными
 * видимыми идентификаторами мобильного телефона в сети Wi-Fi.
 * Модификация   эквивалентна смене устройства - должно быть тестирование, чтобы не допустить дальнейших
 * модификаций "копирования из кошачьей игры".
 *
 * также блокирует `_device-info._tcp` и больше не регистрируется: Android NsdServiceInfo не принимает порт = 0
 * (реальный отчет машины `The port is missing`), и это дополнительная трансляция непроверенных доходов, а ее отказ только создает шум.
 */
class CarPlayCtrlAdvertiserTest {
    @Test
    fun ctrlTxtKeepsValuesVerifiedOnAudi() {
        val srcvers = CarPlayCtrlAdvertiser.advertisedSrcversForTest()
        val name = CarPlayCtrlAdvertiser.advertisedServiceNameForTest()
        assertEquals(
            "Ветка автомобилей и машин по этому номеру версии：Изменить соединение ctrl Обнаружение не отправляется",
            "950.7.1",
            srcvers,
        )
        assertEquals("0.1.6 Доступные имена экземпляров из фактического тестирования", "sender", name)
        assertTrue("Тип услуги не может быть изменен", CarPlayCtrlAdvertiser.SERVICE_TYPE == "_carplay-ctrl._tcp")
    }

    @Test
    fun deviceInfoIsNotAdvertised() {
        // _device-info._tcp был зарегистрирован с портом = 0 и, должно быть, произошел сбой (порт отсутствует).
        // Если вы захотите выполнить восстановление в будущем, вы должны сначала подтвердить, что NsdManager принимает
        // легальные порты и что автомобиль имеет это требование.

        assertTrue(
            "не следует транслировать снова _device-info._tcp",
            !CarPlayCtrlAdvertiser.advertisedServiceTypesForTest().contains("_device-info._tcp"),
        )
    }
}
