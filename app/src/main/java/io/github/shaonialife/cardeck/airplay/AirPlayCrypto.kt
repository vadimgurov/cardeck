package io.github.shaonialife.cardeck.airplay

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** Примитивы криптографии сопряжения AirPlay и канала управления (облегченный API BouncyCastle, независимая реализация). */
object AirPlayCrypto {
    private const val MAC_BITS = 128
    private const val NONCE_SIZE = 12
    private val random = SecureRandom()

    class X25519KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    class Ed25519KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    fun x25519Generate(): X25519KeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return X25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun x25519Shared(
        privateKeyRaw: ByteArray,
        peerPublicKeyRaw: ByteArray,
    ): ByteArray {
        val privateKey = X25519PrivateKeyParameters(privateKeyRaw, 0)
        val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyRaw, 0)
        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        privateKey.generateSecret(peerPublicKey, shared, 0)
        return shared
    }

    fun ed25519Generate(): Ed25519KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return Ed25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun ed25519PublicKey(privateKeyRaw: ByteArray): ByteArray = Ed25519PrivateKeyParameters(privateKeyRaw, 0).generatePublicKey().encoded

    fun ed25519Sign(
        privateKeyRaw: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyRaw, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(
        publicKeyRaw: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean =
        try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKeyRaw, 0))
            signer.update(data, 0, data.size)
            signer.verifySignature(signature)
        } catch (_: Exception) {
            false
        }

    fun hkdfSha512(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = SHA512Digest()
        parts.forEach { digest.update(it, 0, it.size) }
        val output = ByteArray(digest.digestSize)
        digest.doFinal(output, 0)
        return output
    }

    /**
     * Ключевой поток  AES-128-CTR (путь AES для `AirPlayCipherCodec` / `ScreenFrameCodec`).
     *
     * Счетчик       — это стандартный CTR128BE (BouncyCastle SICBlockCipher также является счетчиком с прямым порядком байтов),
     * **Состояние непрерывно во всех кадрах**——catplay создает только один `Aes128Ctr` для каждого потока,
     * применяя_keystream кадр за кадром,
     * Таким образом, здесь вызывающий объект должен хранить один и тот же экземпляр и не может создавать его в каждом кадре.
     */
    class Aes128CtrStream(
        key: ByteArray,
        iv: ByteArray,
    ) {
        // Тип возвращаемого значения newInstance — это интерфейс CTRModeCipher (детали реализации SICBlockCipher инкапсулированы).
        private val cipher: org.bouncycastle.crypto.modes.CTRModeCipher =
            SICBlockCipher
                .newInstance(AESEngine())
                .apply { init(true, ParametersWithIV(KeyParameter(key), iv)) }

        /** Поток ключей XOR на месте (шифрование и дешифрование — это одна и та же операция). */
        fun applyKeystream(data: ByteArray) {
            if (data.isEmpty()) return
            cipher.processBytes(data, 0, data.size, data, 0)
        }
    }

    fun chachaSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val length = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, length)
        return output
    }

    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        val processed = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, output, 0)
        val finalized = cipher.doFinal(output, processed)
        return if (processed + finalized == output.size) output else output.copyOf(processed + finalized)
    }

    /** Счетчик каналов управления nonce: 4-байтовый нулевой + 8-байтовый счетчик с прямым порядком байтов. */
    fun nonce64(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        var value = counter
        for (index in 4 until NONCE_SIZE) {
            nonce[index] = value.toByte()
            value = value ushr 8
        }
        return nonce
    }

    /** Nonce сообщения о сопряжении: 4-байтовый ноль + 8-байтовый тег ASCII. */
    fun nonceLabel(label: String): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        val ascii = label.toByteArray(Charsets.US_ASCII)
        ascii.copyInto(nonce, 4, 0, minOf(ascii.size, 8))
        return nonce
    }
}
