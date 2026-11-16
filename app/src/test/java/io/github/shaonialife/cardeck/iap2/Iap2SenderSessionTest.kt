package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Сквозная петлевая проверка сеанса отправителя с фиктивным аксессуаром:
 * запускает канал связи на обоих концах дуплексного потока памяти. Макет реагирует в соответствии с
 * синхронизацией DiPlay/xcertplay на стороне аксессуаров.
 * Полная последовательность этапа проверки 1 (Идентификация→Пропуск MFi→HID→Конфигурация Wi-Fi→Запуск CarPlay→Уведомление).
 */
class Iap2SenderSessionTest {
    @Test
    fun fullBringUpAgainstMockAccessory() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)
        assertEquals(2, endpoint.securityType)
        assertEquals(49152, endpoint.airPlayPort)
        assertEquals("mock-device-id", endpoint.deviceIdentifier)
        assertNotNull(endpoint.publicKey)
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * Форма  Audi MMI 9310: В рамке распознавания указывается только 0x5703/0xea03 (0x4301 не передается), Фаза 1
     * Поэтому       не ждет; машина перевыпустит 0x4301 в течение периода поддержки активности (после фазы 1).
     * Старая реализация обрабатывает это сообщение как подтверждение активности и запоминает только длину, а
     * сопряжение никогда не перезапускается — этот вариант использования заблокирован.
     * «Поздний 0x4301 должен быть проанализирован в конечную точку и вызван обратно».
     */
    @Test
    fun lateStartSessionAfterPhaseOneTriggersEndpointUpdate() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, shape = MockAccessory.Shape.AUDI_9310, lateStartSession = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val phaseOne = session.run(timeoutMillis = 10_000L)
        // Декларация транспортного средства не отправляет 0x4301: Фаза 1 должна немедленно вернуть пустую конечную
        // точку, а не ждать трехсекундного окна.

        assertTrue(phaseOne.ipAddresses.isEmpty())

        val pump = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pump.isDaemon = true
        pump.start()

        assertTrue("Прибытие поздно 0x4301 Обратный вызов конечной точки не запущен", updateLatch.await(5, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals(49152, late.airPlayPort)
        assertEquals(listOf("192.168.49.1", "fd00::1"), late.ipAddresses)
        assertEquals("mock-device-id", late.deviceIdentifier)

        phoneChannel.close()
        accessoryChannel.close()
        pump.join(2_000)
    }

    /**
     * PR № 19 (Cadillac CT5): Заявление о новом автомобиле, согласованном с машиной, будет отправлено 0x4301, но окно не пришло——
     * **не разрывает цепочку**, возвращает пустую конечную точку и переходит в режим поддержки активности; насос
     * поддержки активности анализирует поздний 0x4301 в полную конечную точку
     * через обратный вызов onEndpointUpdate (Audi MMI 9310 в своей родственной форме прибывает на 2 минуты 35 секунд позже).
     */
    @Test
    fun newProtocolCarWithoutCredentialsKeepsLinkForLateStartSession() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory =
            MockAccessory(
                accessoryChannel,
                shape = MockAccessory.Shape.CT5_NEW_PROTOCOL,
                lateStartSession = true,
            )

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val endpoint = session.run(timeoutMillis = 10_000L)
        assertEquals("", endpoint.ssid)

        val pumpThread = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pumpThread.isDaemon = true
        pumpThread.start()
        assertTrue("Прибытие поздно 0x4301 Не здесь 10s Нэй Цзин onEndpointUpdate обратный вызов", updateLatch.await(10, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals("secret-passphrase", late.passphrase)
        assertEquals(49152, late.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * **Настоящая версия Audi MMI 3315 должна иметь номер 0x5702** (возврат гвоздя 22 октября 2026 г.).
     *
     * В списке дебиторской задолженности       всего 5 позиций, в него не входит 0x5702. Старая реализация будет
     * оценивать «пропустить старый протокол» на основании этого → один раз за весь раунд.
     * 0x5702 не были отправлены → Служба AirPlay на автомобиле **никогда не запускалась** (все 45 подключений были ECONNREFUSED,
     * `_airplay._tcp` тоже не транслируется). И та же машина в старой версии (0x5702) — 122~368мс.
     * возвращается к 0x5703, и порт можно подключить через 30 мс после подключения к сети.
     *
     * Критерием для       является `ReceiverProfile.wifiGen`: разреженный список → НЕИЗВЕСТНО → консервативный старый протокол.
     */
    @Test
    fun sparseDeclarationsStillSendWifiRequest() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, shape = MockAccessory.Shape.AUDI_3315)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertTrue(
            "Ауди 3315 Форма должна быть 0x5702（Пропуск приведет к остановке движения автомобиля. AirPlay услуга）",
            accessory.sawWifiRequest,
        )
        // И должен использовать учетные данные, предоставленные 0x5703.
        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * **Форма BMW iD9 не может выдавать 0x5702** (вторую половину той же модификации).
     *
     * Оба его списка полны (всего ≥10 элементов), но 0x57xx все еще нет → Оценка изображения НОВОЕ → Пропустить 0x5702,
     * Информация о точке доступа       полностью предоставлена 0x4301. Если вы по ошибке отправите 0x5702 и дождетесь 0x5703, это 8b64147.
     * изначально хотел исправить старую проблему «таймаута для каждого раунда ожидания смерти».
     */
    @Test
    fun fullDeclarationsWithoutOldProtocolSkipWifiRequest() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, shape = MockAccessory.Shape.BMW_ID9)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertTrue(
            "БМВ iD9 Форма не должна быть 0x5702（AP Информация предоставлена 0x4301 Принесите свой собственный）",
            !accessory.sawWifiRequest,
        )
        // Информация о точке доступа поступает от беспроводной группы 0x4301.
        assertEquals("MockAP", endpoint.ssid)
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * **Не разрывайте ссылку, не имея новых учетных данных, но зная SSID точки доступа** (Второй гвоздь в
     * возвращении Audi 22 октября 2026 г.).
     *
     * Ключевое доказательство времени: настоящий iPhone подключился к сети в 14:23:02 и получил приглашение ctrl в 14:23:23.
     * (21 секунда); старая реализация «выдает исключение, если 0x4301 не достигается в течение 8 секунд» совершает
     * самоубийство каждый раунд, и сторона автомобиля ждет вечно
     * перезапускает время с 0. Пока вы все еще знаете, к какой точке доступа подключаться, вы должны позволить
     * главному элементу управления продолжать ждать в соответствии с текущей сетью.
     */
    @Test
    fun knownCarSsidAvoidsCredentiallessAbort() {
        val endpoint = credentiallessEndpoint("Audi_MMI_3315_5GHz")
        assertNotNull(
            "Когда горячая точка известна, необходимо синтезировать нижнюю конечную точку.，вместо того, чтобы разорвать цепь",
            endpoint,
        )
        assertEquals("Audi_MMI_3315_5GHz", endpoint!!.ssid)
        // Это должна быть «несамостоятельная конечная точка»: мастер соответственно выберет путь «ожидание приглашения ctrl → mDNS/шлюз».
        assertEquals(0, endpoint.airPlayPort)
        assertTrue(endpoint.ipAddresses.isEmpty())
    }

    /** Даже если не знать, что SSID может выйти из строя (продолжение ожидания приведет только к простою). */
    @Test
    fun noCredentialAndNoKnownSsidStillFails() {
        assertEquals(null, credentiallessEndpoint(null))
        assertEquals(null, credentiallessEndpoint(""))
        assertEquals(null, credentiallessEndpoint("   "))
    }

    /**
     * Форма  Audi MMI 3315: поле адреса 0x4301 заполняется NUL-значениями фиксированной длины (последние 22 для
     * IPv6, последние 5 для IPv4).
     * Старая реализация удаляет только **один** конечный NUL, в результате чего получается
     * `"10.174.189.1\0\0\0\0\0"` - он содержит('.')=true,
     * содержит(':')=false, что может обмануть все суждения о том, "является ли это IPv4", но getaddrinfo Android не
     * может решить эту проблему.
     * ведет себя как `Unable to resolve host "10.174.189.1"` (фактический тест 18 октября 2026 г.: видеопорт после успешного сопряжения
     * и KeepAlive все отключены, а сессия снесена за 10 секунд).
     */
    @Test
    fun nulPaddedAddressesAreStrippedCompletely() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, padAddresses = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        // Каждый адрес должен быть чистым: без NUL и пробелов.
        endpoint.ipAddresses.forEach { address ->
            check(address.none { it == '\u0000' }) { "Адрес содержит NUL：${address.toByteArray().toList()}" }
            check(address.trim() == address) { "Адрес содержит начальные и конечные пробелы：'$address'" }
        }
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)
        // На разрешение порта не влияет заполнение (параметр u32 в том же кадре)
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * 0x5703 Отложенный ответ: отправитель должен **повторно отправить приветствие о состоянии устройства**, а затем
     * **повторно отправить 0x5702** в течение окна ожидания,
     * вместо того, чтобы ждать, пока автомобиль будет отсоединен (BMW iD8.5 застрял в точке ремонта 0x5703).
     */
    @Test
    fun slowWifiConfigurationTriggersResends() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, slowWifi = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 15_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /** Ответ по сценарию времени от DiPlay Iap2WirelessControlClient. */
    private class MockAccessory(
        private val channel: Iap2LinkChannel,
        /**
         * Форма машины  (двойной список 0x1D01 + отвечать ли на 0x5702).
         *
         * Эти четыре состояния являются базовыми источниками **все**, назначенными Wi-Fi в соответствии с протоколом.
         * Любое неправильное суждение приведет к
         * не дает возможности подключить соответствующие модели, поэтому они прибиваются поочередно:
         * - [Shape.TEST_BENCH]: тестовый стенд DiPlay/xcertplay, без списка, перейдите 0x5702→0x5703;
         * - [Shape.AUDI_9310]: Отправьте 2 предмета из списка (включая 0x5703), следуйте старому соглашению;
         * – [Shape.AUDI_3315]: отправьте 2 предмета (**исключая** 0x5703) и получите 5 предметов (**исключая** 0x5702)
         * — разреженный и неполный, но **фактически полностью поддерживает старый протокол**; судя по тому как
         * "пропустить 0x5702" сделает машину
         * вообще не запускает службу AirPlay (реальный автомобильный журнал 22 октября 2026 г.: 45 раз ECONNREFUSED);
         * - [Shape.BMW_ID9]: Оба списка полные (более 10 элементов), 0x57xx по-прежнему отсутствует, а информация о
         * точке доступа находится только в 0x4301.
         */
        private val shape: Shape = Shape.TEST_BENCH,
        /** 0x4301 будет переиздан после завершения Фазы 1 (фактический момент позднего открытия окна автомобиля и машины). */
        private val lateStartSession: Boolean = false,
        /**
         * Поле адреса  заполняется в соответствии с фиксированной длиной реального автомобиля (фактическое измерение
         * Audi MMI 3315: IPv6, за которым следуют 22 NUL,
         * IPv4, за которым следует 5). Используется для блокировки поведения «все конечные NUL должны быть удалены».
         */
        private val padAddresses: Boolean = false,
        /** 0x5703 отвечает с задержкой: отправитель должен повторно отправить приветствие, а затем повторно отправить 0x5702 (вместо того, чтобы ждать, пока автомобиль и машина будут отключены). */
        private val slowWifi: Boolean = false,
    ) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()

        /** Отправил ли отправитель 0x5702 (для подтверждения отправки). */
        @Volatile
        var sawWifiRequest: Boolean = false
            private set

        /** Форма автомобиля. */
        enum class Shape {
            TEST_BENCH,
            AUDI_9310,
            AUDI_3315,
            BMW_ID9,

            // Cadillac CT5 (GM/Cinemo): заявление будет выдано 0x4301, без 0x5702/0x5703——
            // Информацию о точке доступа можно найти только в 0x4301, и она часто недоступна в окне (журнал PR № 19, 21 октября 2026 г.).
            CT5_NEW_PROTOCOL,
        }

        /** Старый Audi/испытательный стенд: список приемочных работ не заявлен, StartHID не выдан. */
        private val legacyHid: Boolean get() = shape != Shape.TEST_BENCH

        /** Отвечать ли 0x5702 (старый протокол имеет обмен учетными данными на стороне сети). CT5 — это настоящая новинка, как и BMW. */
        private val answersWifiRequest: Boolean get() = shape != Shape.BMW_ID9 && shape != Shape.CT5_NEW_PROTOCOL

        fun run() {
            check(channel.awaitReady(10_000)) { "mock link not ready" }

            expect(Iap2MessageIds.START_IDENTIFICATION)
            send(
                Iap2MessageIds.IDENTIFICATION_INFORMATION,
                Iap2Csm.string(0, identityName()),
                Iap2Csm.string(1, "MU-1"),
                Iap2Csm.string(2, "Mock Manufacturer"),
                *declarationParams(),
            )
            expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, ByteArray(64) { it.toByte() }))
            expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70) { (it * 7).toByte() }))
            expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

            // Приветствие о состоянии устройства следует за 0xAA05 (реальная машинная последовательность, предшествующая
            // подписке и 0x5702; ожидание сегмента PR17 также зависит от нее)

            expectDeviceStateGreetings()

            // Подписаться + StartHID (формы Audi/BMW/CT5 не отправляются, отправителю следует сразу пропустить ожидание)
            if (!legacyHid) {
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                send(Iap2MessageIds.START_POWER_UPDATES)
                send(
                    Iap2MessageIds.START_HID,
                    Iap2Csm.u16(0, 0),
                    Iap2Csm.u16(1, 2),
                    Iap2Csm.u16(2, 1),
                    Iap2Csm.param(4, byteArrayOf(0x05, 0x0c)),
                )
                expect(Iap2MessageIds.DEVICE_HID_REPORT)
            }

            // Конфигурация Wi-Fi: режим BMW/CT5 не отправляет 0x5702 (информацию о точке доступа передает 0x4301).
            // Если отправитель по-прежнему отправляет 0x5702 в режиме BMW, первое ожидаемое сообщение (0x4e0d) ниже будет
            // немедленно отправлено.

            // Отчет "ожидаемый 0x4e0d получил 0x5702" - ошибкам отправки некуда спрятаться.
            if (answersWifiRequest) {
                expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                sawWifiRequest = true
                if (slowWifi) {
                    // Отправитель не дождался 0x5703 в течение 1,5 с → отправляет приветствие повторно; затем ждет 1,3 секунды →
                    // повторно отправляет 0x5702

                    // (Сегментированный скрипт ожидания спасения PR17, реальный тест на BMW iD8.5)
                    expectDeviceStateGreetings()
                    expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                }
                // Один запрос на подписку задерживается (обычно подключаются настоящие автомобильные машины: 0x5000 и 0x5703
                // прибывают рядом).

                // Его необходимо проиндексировать, а не многократно перечитывать в ожидаемом ОрНуле — старая реализация помещала
                // обратно неожиданные кадры.

                // Очередь «собственное чтение первым в следующем раунде» бесконечно зацикливается в одном и том же кадре
                // (основная причина обновления экрана журнала 18 октября 2026 г.).

                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                send(
                    Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                    Iap2Csm.string(1, "MockAP"),
                    Iap2Csm.string(2, "secret-passphrase"),
                    Iap2Csm.u8(3, 2),
                    Iap2Csm.u8(4, 36),
                )
            }

            // Запуск CarPlay
            expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
            if (!lateStartSession) {
                sendStartSession()
            }

            expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
            if (lateStartSession) {
                // Перевыпуск после открытия окна CarPlay в машине (1 этап давно закончился)
                Thread.sleep(150)
                sendStartSession()
            }
        }

        /** Приветствие о состоянии оборудования пять последовательных (0x4E0D/0x4E0A/0x4E09/0x4E0B/0x4E0C). */
        private fun expectDeviceStateGreetings() {
            expect(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
            expect(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE)
            expect(Iap2MessageIds.DEVICE_INFORMATION_UPDATE)
            expect(Iap2MessageIds.DEVICE_TIME_UPDATE)
            expect(Iap2MessageIds.DEVICE_UUID_UPDATE)
        }

        /** Имя 0x1D01 (для форм Audi/BMW/CT5 используйте настоящее имя автомобиля, память MFi и портретные ключи должны совпадать). */
        private fun identityName(): String =
            when (shape) {
                Shape.AUDI_9310, Shape.AUDI_3315 -> "Audi_MMI_3315"
                Shape.BMW_ID9 -> "BMW 87866"
                Shape.CT5_NEW_PROTOCOL -> "CADILLAC_2179"
                Shape.TEST_BENCH -> "MockCarPlay"
            }

        /**
         * Параметр 6  0x1D01 (автомобиль может передавать) и параметр 7 (автомобиль может втягиваться).
         *
         * Список приемов Audi 3315 из 5 пунктов взят из реального кадра распознавания (журнал 2026-10-22), BMW iD9
         * Два полных списка взяты из одной и той же формы ReceiverProfileTest — оба являются «местами ошибок распределения».
         */
        private fun declarationParams(): Array<ByteArray> =
            when (shape) {
                // Испытательный стенд: вообще никаких деклараций → портрет НЕИЗВЕСТНО → следовать старому протоколу
                Shape.TEST_BENCH -> emptyArray()
                // Audi MMI 9310: декларируйте только 0x5703/0xea03 (без списка получения)
                Shape.AUDI_9310 -> arrayOf(Iap2Csm.u16List(6, intArrayOf(0x5703, 0xea03)))
                // Audi MMI 3315: отправить 2 предмета (0x4301/0xea03), получить 5 предметов (без 0x57xx)
                Shape.AUDI_3315 ->
                    arrayOf(
                        Iap2Csm.u16List(6, intArrayOf(0x4301, 0xea03)),
                        Iap2Csm.u16List(7, intArrayOf(0x4e0c, 0x4e09, 0x4300, 0xea00, 0xea01)),
                    )
                // BMW iD9: по 10 позиций в каждом списке, всего 20 (≥10), 0x57xx по-прежнему нет → НОВОЕ
                Shape.BMW_ID9 ->
                    arrayOf(
                        Iap2Csm.u16List(
                            6,
                            intArrayOf(0xaa01, 0xaa03, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157, 0x4301),
                        ),
                        Iap2Csm.u16List(
                            7,
                            intArrayOf(0xaa00, 0xaa02, 0xaa04, 0xaa05, 0xea00, 0xea01, 0x5001, 0x5201, 0x4300, 0x4e0d),
                        ),
                    )
                // Cadillac CT5 (PR № 19): отправьте 11 предметов, содержащих 0x4301, получите 9 предметов, ни одного 0x57xx → НОВОЕ
                Shape.CT5_NEW_PROTOCOL ->
                    arrayOf(
                        Iap2Csm.u16List(
                            6,
                            intArrayOf(0xaa06, 0x6800, 0x6802, 0x6803, 0x4c00, 0x4c02, 0x4c03, 0x4c05, 0x5000, 0x5002, 0x4301),
                        ),
                        Iap2Csm.u16List(
                            7,
                            intArrayOf(0x6801, 0x4c01, 0x4c04, 0x5001, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4300),
                        ),
                    )
            }

        private fun sendStartSession() {
            send(
                Iap2MessageIds.CARPLAY_START_SESSION,
                Iap2Csm.group(
                    1,
                    Iap2Csm.string(0, "MockAP"),
                    Iap2Csm.string(1, "secret-passphrase"),
                    Iap2Csm.u8(2, 36),
                    addressParam(3, "192.168.49.1", padTo = 17),
                    addressParam(3, "fd00::1", padTo = 47),
                    Iap2Csm.u8(4, 2),
                ),
                Iap2Csm.u32(2, 49152L),
                Iap2Csm.string(3, "mock-device-id"),
                Iap2Csm.string(4, "a".repeat(64) + "publickeyhex"),
                Iap2Csm.string(5, "560.20"),
            )
        }

        /** Параметр адреса: Реальный автомобиль заполнен NUL фиксированной длины; значение по умолчанию (не заполнено) соответствует предыдущему поведению. */
        private fun addressParam(
            id: Int,
            value: String,
            padTo: Int,
        ): ByteArray {
            val raw = value.encodeToByteArray()
            if (!padAddresses) return Iap2Csm.param(id, raw + byteArrayOf(0))
            // Сохраните хотя бы один терминатор NUL (семантика строки CSM), а остальные дополните до фиксированной ширины.
            val width = maxOf(padTo, raw.size + 1)
            val padded = raw + ByteArray(width - raw.size)
            return Iap2Csm.param(id, padded)
        }

        private fun send(
            messageId: Int,
            vararg body: ByteArray,
        ) {
            val joined =
                when (body.size) {
                    0 -> ByteArray(0)
                    1 -> body[0]
                    else -> body.reduce { acc, bytes -> acc + bytes }
                }
            val frame = Iap2Csm.encode(messageId, joined)
            var offset = 0
            val limit = channel.peerMaxControlPayload() ?: 2048
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                check(channel.sendControl(frame.copyOfRange(offset, end))) { "mock send failed" }
                offset = end
            }
        }

        private fun expect(messageId: Int) {
            println("MOCK expect 0x${messageId.toString(16)}")
            val frame =
                recvFrame(10_000)
                    ?: error("mock timed out waiting 0x${messageId.toString(16)}")
            check(frame.messageId == messageId) {
                "mock expected 0x${messageId.toString(16)} got 0x${frame.messageId.toString(16)}"
            }
            println("MOCK got 0x${frame.messageId.toString(16)}")
        }

        private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
            pending.firstOrNull()?.let {
                pending.removeFirst()
                return it
            }
            val bytes = channel.recvControl(timeoutMillis) ?: return null
            val frames = parser.offer(bytes)
            if (frames.isEmpty()) return null
            frames.forEachIndexed { index, frame -> if (index > 0) pending += frame }
            return frames.first()
        }
    }
}
