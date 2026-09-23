package com.airsink.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    /** HKDF-SHA512 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: String, info: String, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(salt.toByteArray(), "HmacSHA512"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA512"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info.toByteArray())
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    /** 12-byte nonce: four zero bytes followed by a little-endian 64-bit counter. */
    fun nonce(counter: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putLong(counter).array()

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray?, plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size): ByteArray {
        val c = cipher()
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(plaintext, offset, length)
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray?, ciphertextAndTag: ByteArray): ByteArray {
        val c = cipher()
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(ciphertextAndTag)
    }

    // Android (Conscrypt) and desktop JDKs use different names for the same transform.
    private fun cipher(): Cipher = try {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
    } catch (e: Exception) {
        Cipher.getInstance("ChaCha20-Poly1305")
    }
}
