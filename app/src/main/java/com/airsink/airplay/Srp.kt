package com.airsink.airplay

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SRP-6a client with the 3072-bit RFC 5054 group and SHA-512, as used by HomeKit and AirPlay 2
 * pair-setup. Padding follows RFC 5054 for k and u; everything else uses minimal encodings.
 */
class SrpClient(
    private val username: String,
    private val password: String,
    random: SecureRandom = SecureRandom(),
    /** Only for tests: a fixed private exponent. */
    privateKey: BigInteger? = null,
) {
    private val a: BigInteger = privateKey ?: BigInteger(1, ByteArray(32).also(random::nextBytes))
    val publicKey: ByteArray = bytes(G.modPow(a, N))

    lateinit var sessionKey: ByteArray
        private set
    private lateinit var expectedServerProof: ByteArray

    /** Computes the client proof M1 from the server's salt and public key B. */
    fun proof(salt: ByteArray, serverPublicKey: ByteArray): ByteArray {
        val bigB = BigInteger(1, serverPublicKey)
        require(bigB.mod(N) != BigInteger.ZERO) { "Invalid server public key" }

        val k = BigInteger(1, sha512(bytes(N), pad(bytes(G))))
        val u = BigInteger(1, sha512(pad(publicKey), pad(serverPublicKey)))
        val x = BigInteger(1, sha512(salt, sha512("$username:$password".toByteArray())))

        val s = bigB.subtract(k.multiply(G.modPow(x, N))).mod(N).modPow(a.add(u.multiply(x)), N)
        sessionKey = sha512(bytes(s))

        val hN = sha512(bytes(N))
        val hG = sha512(bytes(G))
        val hNxorG = ByteArray(hN.size) { (hN[it].toInt() xor hG[it].toInt()).toByte() }
        val m1 = sha512(hNxorG, sha512(username.toByteArray()), salt, publicKey, serverPublicKey, sessionKey)
        expectedServerProof = sha512(publicKey, m1, sessionKey)
        return m1
    }

    fun verifyServer(serverProof: ByteArray): Boolean =
        MessageDigest.isEqual(serverProof, expectedServerProof)

    private fun pad(b: ByteArray): ByteArray {
        val len = (N.bitLength() + 7) / 8
        return if (b.size >= len) b else ByteArray(len - b.size) + b
    }

    companion object {
        private val N = BigInteger(
            ("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"),
            16,
        )
        private val G = BigInteger.valueOf(5)

        /** Big-endian bytes without the sign byte BigInteger sometimes adds. */
        fun bytes(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }

        fun sha512(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            parts.forEach(md::update)
            return md.digest()
        }
    }
}
