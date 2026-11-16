package io.github.shaonialife.cardeck.airplay

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Клиент  SRP-6a (3072-битная группа RFC 5054, SHA-512) — вариант парной настройки AirPlay.
 * поэлементно выравнивается в соответствии с соглашением приемника ссылок Srp6a.kt:
 * - x = SHA512(соль, SHA512(I || ":" || P)), I = "Настройка пары", P = фиксированный код "3939"
 * - k = SHA512(площадка(N), площадка(г))
 * — сеансовый ключ K = SHA512(байт(S)); доказательство клиента M1 = SHA512(H(N)^H(g), H(I), соль, площадка(A), площадка(B), K)
 */
object Srp6aClient {
    private val random = SecureRandom()
    internal val n = BigInteger(N_HEX, 16)
    internal val g: BigInteger = BigInteger.valueOf(5)
    internal val k = toBigInt(AirPlayCrypto.sha512(pad(n), pad(g)))
    private val COLON = byteArrayOf(':'.code.toByte())

    internal fun modulus(): BigInteger = n

    internal fun generator(): BigInteger = g

    internal fun multiplier(): BigInteger = k

    const val USERNAME = "Pair-Setup"
    const val SETUP_CODE = "3939"

    class Session internal constructor(
        val publicKeyA: ByteArray,
        private val a: BigInteger,
        private val b: ByteArray,
        private val salt: ByteArray,
        private val identifier: ByteArray,
    ) {
        /** K = SHA512(байты(S)), доступен после первого успеха [clientProof]. */
        var sessionKey: ByteArray? = null
            private set

        /** Создать доказательство клиента M1; верните ноль, если B недействителен. */
        fun clientProof(): ByteArray? {
            val bigB = toBigInt(b)
            if (bigB.mod(n).signum() == 0) return null
            val u = toBigInt(AirPlayCrypto.sha512(publicKeyA, pad(bigB)))
            val x = toBigInt(AirPlayCrypto.sha512(salt, AirPlayCrypto.sha512(identifier, COLON, SETUP_CODE.toByteArray())))
            val verifier = g.modPow(x, n)
            val base = (bigB - k * verifier).mod(n)
            if (base.signum() == 0) return null
            val s = base.modPow(a + u * x, n)
            val key = AirPlayCrypto.sha512(toBytes(s))
            sessionKey = key

            val hashN = AirPlayCrypto.sha512(toBytes(n))
            val hashG = AirPlayCrypto.sha512(toBytes(g))
            val hashXor = ByteArray(hashN.size)
            for (index in hashN.indices) {
                hashXor[index] = (hashN[index].toInt() xor hashG[index].toInt()).toByte()
            }
            return AirPlayCrypto.sha512(hashXor, AirPlayCrypto.sha512(identifier), salt, publicKeyA, pad(bigB), key)
        }

        /** Сервер подтверждает HAMK = SHA512(pad(A), M1, K). */
        fun serverProofValid(serverProof: ByteArray): Boolean {
            val m1 = clientProof() ?: return false
            val key = sessionKey ?: return false
            return AirPlayCrypto.sha512(publicKeyA, m1, key).contentEquals(serverProof)
        }
    }

    fun start(
        salt: ByteArray,
        publicKeyB: ByteArray,
    ): Session {
        val a = toBigInt(ByteArray(32).also(random::nextBytes))
        val publicKeyA = pad(g.modPow(a, n))
        return Session(
            publicKeyA = publicKeyA,
            a = a,
            b = publicKeyB,
            salt = salt,
            identifier = USERNAME.toByteArray(Charsets.UTF_8),
        )
    }

    internal fun toBytes(value: BigInteger): ByteArray {
        if (value.signum() == 0) return byteArrayOf(0)
        val encoded = value.toByteArray()
        return if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
    }

    internal fun toBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    internal fun pad(value: BigInteger): ByteArray {
        val bytes = toBytes(value)
        if (bytes.size >= 384) return bytes
        return ByteArray(384).also { bytes.copyInto(it, 384 - bytes.size) }
    }

    /** RFC 5054, 3072-битное простое число (согласно эталонной реализации). */
    private const val N_HEX =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183" +
            "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A" +
            "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB" +
            "F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8" +
            "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208" +
            "E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
}
