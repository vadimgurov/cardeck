package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.net.AirPlayFeatures

/**
 * **Портрет автомобиля и машины**: Объедините разрозненные сигналы о версии/возможностях в интерпретируемое суждение.
 *
 * Сигнал   поступает из трех мест с разным временем прибытия: идентификационный кадр 0x1D01 (раннее подключение Bluetooth), mDNS/`/info`.
 * Функции и источники   (период анализа конечной точки), реальное поведение транспортного средства (функция
 * длины вызова/отключения цепочки, отслеживание существующего обучения памяти).
 * можно пересчитывать каждый раз при поступлении нового сигнала — [classify] — это чистая функция, один и тот же
 * вход всегда имеет один и тот же выход.
 *
 * Два железных закона (обобщение уроков HID-дескрипторов):
 * 1. **Ветвь по возможностям, не жестко запрограммированная по номеру версии/торговой марке** —
 * источники/функции дают только априорные подсказки,
 * Фактическое распределение   зависит от списка возможностей (список приема/получения, audioFormats,
 * hidDescriptors) и фактического измеренного поведения;
 * 2. **Решение должно быть объяснимым** —— [describe] Запишите доказательства в журнал, и вы сможете сразу
 * увидеть, какой сигнал вводит в заблуждение, когда суждение неверно.
 */
object ReceiverProfile {
    /** Три возраста (ср. классификацию кошачьих игр, §9f). НЕИЗВЕСТНО = Потеря сигнала, сохраняйте старый путь неагрессивным. */
    enum class Era { PRE_HOMEKIT, OPTIONAL_PAIRING, MODERN, UNKNOWN }

    /** Генерация протокола обмена учетными данными WiFi (основа назначения для PR № 10, изображение импорта). */
    enum class WifiGen { LEGACY, NEW, UNKNOWN }

    /**
     * Генерация виртуального канала iAP2 (решение 23 октября 2026 г., чистая функция).
     *
     * **23 октября 2026 г. Второе решение: поведение маркера больше не будет распределяться между поколениями** —
     * все транспортные средства должны завершить ссылку
     * Переговоры       (маркер = машина ожидает нашей таблицы сеансов, бесконечная повторная передача 1 Гц; если
     * ответа нет, ссылка недоступна
     * NORMAL, удалено через ~10 с после ЗАПИСИ: 0.1.9 измерял 10,17 с, 0.1.17 владелец измерял 10,18 с).
     * игнорировал маркер (0.1.16/0.1.17) для CLASSIC_MIB3 на основе этого, который был основан на «0.1.9 игнорировать маркер
     * «Audi входит как обычно, когда      » был отменен собственным журналом 0.1.9; настоящая причина «Переговоров о
     * приостановке работы пользовательского интерфейса автомобиля» заключается в том, что переговоры завершены.
     * Отсутствующие режимы. Изменено переопределение ресурсов после       (тайминг AADisplay r17). Решение оставлено
     * для журналов и будущего
     * Отправка       (например, предварительная длина запроса MFi, дисперсия сообщения внутри туннеля).
     *
     * Критерии       (все из протокола самоотчета, никаких новых оснований, кроме спекуляций о бренде):
     * Марка DELPHI + модель AUDI + разреженный список (всего <10 позиций, измерено 2+5) → CLASSIC_MIB3.
     * засчитывается только в том случае, если три из них попали одновременно - любой из них может быть случайно
     * поврежден (другие автомобили с немногочисленным списком, детали не Audi DELPHI).
     * Все остальное (в том числе неизвестные автомобили и машины) → EVENT_LINK.
     */
    enum class LinkGeneration { CLASSIC_MIB3, EVENT_LINK }

    /**
     * определяет генерацию канала на основе сигнала самоотчета 0x1D01 (см. документ [LinkGeneration]).
     * Недостаточный сигнал (манифест не предоставлен/отсутствует идентификатор) → EVENT_LINK (поведение, совместимое с версиями до 0.1.13).
     */
    fun linkGenerationOf(signals: Signals): LinkGeneration {
        val manufacturer = signals.manufacturer?.lowercase().orEmpty()
        val model = signals.model?.lowercase().orEmpty()
        val listTotal = (signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0)
        val listsSparse = signals.sendIds != null && signals.receiveIds != null && listTotal in 1..9
        val isDelphiAudi = manufacturer.contains("delphi") && model.contains("audi")
        return if (isDelphiAudi && listsSparse) {
            LinkGeneration.CLASSIC_MIB3
        } else {
            LinkGeneration.EVENT_LINK
        }
    }

    /** Ввод изображения: Все поля можно оставить пустыми — столько сигналов, сколько есть. */
    data class Signals(
        val name: String? = null,
        val model: String? = null,
        val manufacturer: String? = null,
        /** AirPlay поддерживает растровое изображение (mDNS TXT//info); ноль = не получено. */
        val features: Long? = null,
        /** Версия стека AirPlay (mDNS TXT srcvers/0x4301 sourceVersion). */
        val srcvers: String? = null,
        /** 0x1D01 Параметр 6: Список номеров сообщений, которые автомобиль заявляет «отправит»; ноль = не указано. */
        val sendIds: Set<Int>? = null,
        /** 0x1D01 Параметр 7: Список номеров сообщений, которые автомобиль объявляет «подлежащими получению»; ноль = не указано. */
        val receiveIds: Set<Int>? = null,
    )

    data class Profile(
        val era: Era,
        val wifiGen: WifiGen,
        /** Ключ идентификации (того же происхождения, что и MfiLengthMemory/AudioProfileMemory, позже используемый в сочетании с Memory). */
        val identityKey: String,
        /** Основа суждения (интерпретируемость: исходный текст сигнала, соответствующий каждому выводу). */
        val reasons: List<String>,
    )

    fun classify(signals: Signals): Profile {
        val reasons = ArrayList<String>()
        val pairing = AirPlayFeatures.supportsPairing(signals.features)
        val era =
            when {
                pairing == null -> Era.UNKNOWN
                !pairing -> {
                    reasons += "features(${signals.features?.let { "0x${it.toString(16)}" } ?: "?"}) Нет bit38=HK_PAIRING_AND_ENCRYPT"
                    Era.PRE_HOMEKIT
                }

                else -> {
                    reasons += "функции bit38=HK_PAIRING_AND_ENCRYPT объявлены"
                    Era.MODERN
                }
            }
        if (signals.srcvers != null) reasons += "srcvers=${signals.srcvers}"
        if (era != Era.PRE_HOMEKIT && signals.srcvers != null) {
            // Априорная подсказка (не меняет решения): старый номер версии + необъявленное сопряжение → дополнительный файл
            // сопряжения v280.

            val v = signals.srcvers.toDoubleOrNull()?.let { (it * 100).toInt() } ?: 0
            if (v in 1..36599 && era == Era.UNKNOWN) reasons += "srcvers подсказывает дополнительный файл сопряжения v280 (только априори)"
        }

        val wifiGen =
            when {
                signals.sendIds == null && signals.receiveIds == null -> {
                    reasons += "0x1D01 Список получателей/получателей не предоставлен."
                    WifiGen.UNKNOWN
                }

                signals.sendIds?.contains(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) == true ||
                    signals.receiveIds?.contains(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION) == true -> {
                    reasons += "Оператор манифеста 0x5703/0x5702 (присутствует старый протокол)"
                    WifiGen.LEGACY
                }

                (signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0) >= 10 -> {
                    // Листинги полные и достаточно полные, но формы 0x5703/0x5702 – BMW iD9 до сих пор нет (в обоих листингах более
                    // 10 позиций).

                    // Вы не можете судить о новинке по «необъявленному»: в списке Audi указана только информация о жизненном цикле
                    // CarPlay (фактический тест

                    // Список отправки 2 элемента/получение 5 элементов, ни одного 0x57xx), но старый протокол работает нормально——
                    // Разреженные списки не учитываются, консервативный UNKNOWN использует старый протокол (0x5703 имеет тайм-аут)
                    reasons +=
                        "Полный список (элементы ${(signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0)}) все " +
                        "еще отсутствует 0x5703/0x5702 — параметры точки доступа предоставляются через 0x4301"
                    WifiGen.NEW
                }

                (signals.sendIds != null || signals.receiveIds != null) -> {
                    reasons +=
                        "Список скуден и необъявлен 0x5703/0x5702 — не является свидетельством «нового соглашения», " +
                        "консервативно следуйте старому соглашению (гарантия тайм-аута)"
                    WifiGen.UNKNOWN
                }

                else -> WifiGen.UNKNOWN
            }
        return Profile(era, wifiGen, identityKeyOf(signals), reasons)
    }

    /** Исходный идентификационный текст (имя+модель+производитель, дословно соответствует 0x1D01; пробелы пропускаются). */
    fun identityKeyOf(signals: Signals): String =
        listOfNotNull(
            signals.name?.takeIf { it.isNotBlank() },
            signals.model?.takeIf { it.isNotBlank() },
            signals.manufacturer?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { "unknown" }

    /** Одна строка доступна для чтения (в файл журнала). */
    fun describe(profile: Profile): String =
        "PROFILE: era=${profile.era} wifiGen=${profile.wifiGen} key=${MfiLengthMemory.receiverKey(profile.identityKey)}" +
            " На основе [" + profile.reasons.joinToString("；") + "]"
}
