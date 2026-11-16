package io.github.shaonialife.cardeck.airplay

/**
 * метод шифрования кадров экрана/аудиопотока (три формы поля AirPlay `et`).
 *
 * Основа выбора   Catplay `ScreenFrameCodec::new`:
 * — прошел через /pair-setup/pair-verify → ChaCha20-Poly1305 (деривация общего ключа);
 * — прошел только через /auth-setup (MFi-SAP) → AES-128-CTR (сессионный ключ);
 * — нет (`et = NONE`, старая машина до HomeKit) → **AES-128-CTR и все сеансовые ключи равны нулю**
 * (комментарий кошачьей игры: «Никогда не было поддержки незашифрованных сеансов экрана.
 * ET = None соответствует сеансу AES, где session_key до деривации содержит все нули.")
 *
 * 19 октября 2026 г. Данные: Audi 4xxx (например, 4851) может попасть в категорию 3 — заставьте это сделать.
 * Парная установка   неизбежно завершится неудачно, но сначала начинается игра с открытым текстом SETUP, а
 * нулевой ключ AES можно подключить после перехода на экран.
 */
sealed class StreamFrameCipher {
    /** Видеокадры запечатаны ChaCha20-Poly1305 (тег 16B), кадры KeepAlive/config не шифруются. */
    class ChaCha(
        val key: ByteArray,
    ) : StreamFrameCipher()

    /** Потоковое шифрование видеокадров AES-128-CTR (без тегов, непрерывный поток ключей между кадрами). */
    class AesCtr(
        val key: ByteArray,
        val iv: ByteArray,
    ) : StreamFrameCipher()
}

object AirPlayStreamKeys {
    /**
     * Ключ потока ChaCha: HKDF-SHA512(shared_secret, «DataStream-Salt<connId>», «DataStream-Output-Encryption-Key»).
     * соответствует правилам вывода Catplay `create_chacha_ciphers`/получения ScreenStream.
     */
    fun chachaStreamKey(
        sharedSecret: ByteArray,
        connectionId: Long,
    ): ByteArray =
        AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )

    /** Ключ сеанса по адресу `et=NONE`: 16 байт со всеми нулями (так же, как в Catplay ScreenFrameCodec::new). */
    val ZERO_SESSION_KEY: ByteArray = ByteArray(16)

    /**
     * Ключ потока AES/IV (выровнен по байтам Catplay `derive_aes_stream_keys`):
     * Ключ       = SHA512 («AirPlayStreamKey» + connId (десятичный) + sessionKey) [0..16]
     * iv = SHA512("AirPlayStreamIV" + connId(десятичный) + sessionKey)[0..16]
     */
    fun aesStreamKeys(
        sessionKey: ByteArray,
        connectionId: Long,
    ): Pair<ByteArray, ByteArray> {
        fun digest(prefix: String): ByteArray =
            AirPlayCrypto
                .sha512(
                    prefix.toByteArray(Charsets.US_ASCII),
                    connectionId.toString().toByteArray(Charsets.US_ASCII),
                    sessionKey,
                ).copyOfRange(0, 16)
        return digest("AirPlayStreamKey") to digest("AirPlayStreamIV")
    }
}
