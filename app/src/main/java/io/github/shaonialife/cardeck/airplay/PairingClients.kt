package io.github.shaonialife.cardeck.airplay

import java.io.IOException

/** Абстракция передачи запроса/ответа TLV8: RTSP POST используется для реальной реализации, а макетная принимающая сторона подключается напрямую для тестирования. */
fun interface TlvTransport {
    /** Отправьте тело запроса TLV8 и верните тело ответа TLV8 принимающей стороны. */
    fun exchange(
        path: String,
        body: ByteArray,
    ): ByteArray
}

/** Долгосрочная идентификация телефона (контроллера): пара ключей ed25519 + идентификатор сопряжения, генерируется при первом использовании. */
class ControllerIdentity(
    val pairingId: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

/**
 * AirPlay/клиент настройки пары (M1/M3/M5).
 * Эталонный приемник: фиксированный код SRP-6a («Pair-Setup»/«3939»), после чего долгосрочный открытый ключ
 * ed25519 передается в зашифрованном конверте.
 */
class PairSetupClient(
    private val identity: ControllerIdentity,
    private val transport: TlvTransport,
    private val log: (String) -> Unit,
) {
    /** По завершении принимающая сторона сохранила наш долгосрочный открытый ключ; возвращает статический идентификатор принимающей стороны (из M6, необязательно). */
    fun run(): ReceiverIdentity? {
        // M1: Запросить параметры SRP (Метод=Нет + Состояние=1, по сравнению с игрой PairSetup::start)
        val m2 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-setup",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_METHOD, byteArrayOf(0)),
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(1)),
                        ),
                    ),
                ),
            )
        val salt = m2[Tlv8.TYPE_SALT] ?: throw IOException("Пара-настройка M2, отсутствует соль")
        val serverPublic = m2[Tlv8.TYPE_PUBLIC_KEY] ?: throw IOException("в парной настройке M2 отсутствует открытый ключ")
        log("настройка пары: M2 получает параметр SRP salt=${salt.size}B B=${serverPublic.size}B")

        // M3: Открытый ключ клиента A + доказательство M1.
        val srp = Srp6aClient.start(salt, serverPublic)
        val clientProof = srp.clientProof() ?: throw IOException("установка пары: SRP B недопустима")
        val m4 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-setup",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(3)),
                            Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, srp.publicKeyA),
                            Tlv8.Item(Tlv8.TYPE_PROOF, clientProof),
                        ),
                    ),
                ),
            )
        val serverProof = m4[Tlv8.TYPE_PROOF] ?: throw IOException("парная установка M4 отсутствует доказательство")
        if (!srp.serverProofValid(serverProof)) throw IOException("настройка пары: проверка сертификата сервера не удалась")
        val sessionKey = srp.sessionKey ?: throw IOException("настройка пары: отсутствует сеансовый ключ")
        log("настройка пары: взаимная проверка M4 SRP пройдена")

        // M5: Зашифрованный конверт передает долгосрочный открытый ключ.
        val encryptionKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "Pair-Setup-Encrypt-Salt".toByteArray(),
                "Pair-Setup-Encrypt-Info".toByteArray(),
            )
        val controllerSignKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "Pair-Setup-Controller-Sign-Salt".toByteArray(),
                "Pair-Setup-Controller-Sign-Info".toByteArray(),
            )
        val identifierBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature =
            AirPlayCrypto.ed25519Sign(
                identity.privateKey,
                controllerSignKey + identifierBytes + identity.publicKey,
            )
        val sub =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_IDENTIFIER, identifierBytes),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, identity.publicKey),
                    Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                ),
            )
        val m6 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-setup",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(5)),
                            Tlv8.Item(
                                Tlv8.TYPE_ENCRYPTED_DATA,
                                AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg05"), sub),
                            ),
                        ),
                    ),
                ),
            )
        if (m6[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt() != 6) {
            throw IOException("Статус настройки пары M6 не равен 6: ${m6[Tlv8.TYPE_ERROR]?.firstOrNull()}")
        }
        log("настройка пары: ✓ M6 завершен, принимающая сторона сохранила наш долгосрочный открытый ключ")

        // Анализ личности получателя в зашифрованном конверте M6 (проверка целостности)
        val encrypted = m6[Tlv8.TYPE_ENCRYPTED_DATA] ?: return null
        val inner =
            Tlv8.decode(
                AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg06"), encrypted),
            )
        val receiverId = inner[Tlv8.TYPE_IDENTIFIER] ?: return null
        val receiverKey = inner[Tlv8.TYPE_PUBLIC_KEY] ?: return null
        val receiverSignature = inner[Tlv8.TYPE_SIGNATURE] ?: return null
        val accessorySignKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "Pair-Setup-Accessory-Sign-Salt".toByteArray(),
                "Pair-Setup-Accessory-Sign-Info".toByteArray(),
            )
        val valid =
            AirPlayCrypto.ed25519Verify(
                receiverKey,
                accessorySignKey + receiverId + receiverKey,
                receiverSignature,
            )
        log(
            "настройка пары: идентификатор получателя = ${receiverId.toString(Charsets.UTF_8)}" +
                "pub=${receiverKey.toHexString().take(16)}… проверка подписи=$valid",
        )
        return ReceiverIdentity(receiverId.toString(Charsets.UTF_8), receiverKey)
    }

    data class ReceiverIdentity(
        val pairingId: String,
        val publicKey: ByteArray,
    )
}

/**
 * Клиент AirPlay/pair-verify (M1/M3): временный X25519 + долгосрочная подпись ed25519 для каждого сеанса,
 * после успеха получает ключ ChaCha20 канала управления.
 */
class PairVerifyClient(
    private val identity: ControllerIdentity,
    private val transport: TlvTransport,
    private val log: (String) -> Unit,
) {
    class ControlKeys(
        val phoneWriteKey: ByteArray,
        val phoneReadKey: ByteArray,
        val sharedSecret: ByteArray,
    )

    fun run(receiverStaticPublicKey: ByteArray? = null): ControlKeys {
        // M1: временный открытый ключ X25519.
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-verify",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(1)),
                            Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, ephemeral.publicKey),
                        ),
                    ),
                ),
            )
        val receiverEphemeral = m2[Tlv8.TYPE_PUBLIC_KEY] ?: throw IOException("пара-проверить отсутствие открытого ключа M2")
        val encrypted = m2[Tlv8.TYPE_ENCRYPTED_DATA] ?: throw IOException("пара-проверка M2 отсутствует зашифрованные данные")

        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, receiverEphemeral)
        val encryptionKey =
            AirPlayCrypto.hkdfSha512(
                shared,
                "Pair-Verify-Encrypt-Salt".toByteArray(),
                "Pair-Verify-Encrypt-Info".toByteArray(),
            )
        val inner =
            Tlv8.decode(
                AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg02"), encrypted),
            )
        val receiverId = inner[Tlv8.TYPE_IDENTIFIER] ?: throw IOException("пара-проверьте, что в конверте M2 отсутствует идентификатор")
        val receiverSignature = inner[Tlv8.TYPE_SIGNATURE] ?: throw IOException("пара-проверьте, что в конверте M2 отсутствует подпись")

        // Используйте статический открытый ключ принимающей стороны, предоставленный mDNS/0x4301, для проверки
        // (подтвердите, если да, пропустите, если нет).

        if (receiverStaticPublicKey != null) {
            val valid =
                AirPlayCrypto.ed25519Verify(
                    receiverStaticPublicKey,
                    receiverEphemeral + receiverId + ephemeral.publicKey,
                    receiverSignature,
                )
            log("Pair-verify: проверка подписи получателя (по сравнению с pk) =$valid")
        }

        // M3: Наш долгосрочный контракт
        val identifierBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature =
            AirPlayCrypto.ed25519Sign(
                identity.privateKey,
                ephemeral.publicKey + identifierBytes + receiverEphemeral,
            )
        val sub =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_IDENTIFIER, identifierBytes),
                    Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                ),
            )
        val m4 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-verify",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(3)),
                            Tlv8.Item(
                                Tlv8.TYPE_ENCRYPTED_DATA,
                                AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), sub),
                            ),
                        ),
                    ),
                ),
            )
        if (m4[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt() != 4) {
            throw IOException("пара-проверьте статус M4, отличный от 4: ${m4[Tlv8.TYPE_ERROR]?.firstOrNull()}")
        }
        log("проверка пары: ✓ Завершить получение ключа канала управления.")

        // Направление реализуется в соответствии с принимающей стороной: принимающая сторона readKey="Control-Write-..."
        // (то есть направление, которое мы отправили)

        val phoneWriteKey =
            AirPlayCrypto.hkdfSha512(
                shared,
                "Control-Salt".toByteArray(),
                "Control-Write-Encryption-Key".toByteArray(),
            )
        val phoneReadKey =
            AirPlayCrypto.hkdfSha512(
                shared,
                "Control-Salt".toByteArray(),
                "Control-Read-Encryption-Key".toByteArray(),
            )
        return ControlKeys(phoneWriteKey, phoneReadKey, shared)
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
