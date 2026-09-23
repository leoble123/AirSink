package com.airsink.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Vectors generated with Python's srptools (the SRP library pyatv uses to pair with real Apple
 * devices), cryptography's HKDF/ChaCha20-Poly1305 and plistlib.
 */
class ReferenceVectorsTest {

    @Test
    fun srpMatchesSrptools() {
        val a = BigInteger("7e1bc2e0c3d9e19a6f7a1d11bc0e3e4b2f9ad7c1e0f52b6c8d9e0a1b2c3d4e5f", 16)
        val client = SrpClient("Pair-Setup", "3939", privateKey = a)
        assertEquals(SRP_A, client.publicKey.hex())

        val m1 = client.proof(unhex("0102030405060708090a0b0c0d0e0f10"), unhex(SRP_B))
        assertEquals(SRP_K, client.sessionKey.hex())
        assertEquals(SRP_M1, m1.hex())
        assertTrue(client.verifyServer(unhex(SRP_M2)))
    }

    @Test
    fun hkdfMatchesReference() {
        val ikm = ByteArray(64) { it.toByte() }
        assertEquals("5a6cb19bcbe7d4df2dd8279f39562f7fae2dbf73eb5a4f98849c245c82b2fe96", Crypto.hkdf(ikm, "Control-Salt", "Control-Write-Encryption-Key").hex())
        assertEquals("11954fc3044c580a9a64ed0b699039dfd0e7dd476eb78366ae160f2962708e18", Crypto.hkdf(ikm, "Events-Salt", "Events-Read-Encryption-Key").hex())
    }

    @Test
    fun chachaMatchesReference() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = Crypto.seal(key, Crypto.nonce(5), byteArrayOf(5, 0), "hello".toByteArray())
        assertEquals("4b6dfa662a0ae0f04b34809065bc2d49ab305d2332", sealed.hex())
        assertArrayEquals("hello".toByteArray(), Crypto.open(key, Crypto.nonce(5), byteArrayOf(5, 0), sealed))
    }

    @Test
    fun decodesPlistlibOutput() {
        val root = BPlist.decode(unhex(BPLIST)) as Map<*, *>
        assertEquals(7011L, root["timingPort"])
        val stream = (root["streams"] as List<*>)[0] as Map<*, *>
        assertEquals(96L, stream["type"])
        assertEquals(true, stream["isMedia"])
        assertEquals(1234567890123L, stream["streamConnectionID"])
        assertEquals("Café", stream["name"])
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), stream["shk"] as ByteArray)
    }

    @Test
    fun plistRoundTrip() {
        val input = mapOf(
            "streams" to listOf(mapOf("type" to 96, "ct" to 2, "shk" to byteArrayOf(0, 1, 2, 3), "isMedia" to true, "name" to "Café")),
            "timingPort" to 7011,
            "negative" to -5,
            "rate" to 1.5,
            "nested" to mapOf("a" to listOf(1, 2, 3), "b" to "x".repeat(40)),
        )
        val encoded = BPlist.encode(input)
        // Written out so scripts can check it against Python's plistlib as well.
        java.io.File(System.getProperty("java.io.tmpdir"), "airsink-bplist.bin").writeBytes(encoded)
        val out = BPlist.decode(encoded) as Map<*, *>
        assertEquals(7011L, out["timingPort"])
        assertEquals(-5L, out["negative"])
        assertEquals(1.5, out["rate"])
        assertEquals(listOf(1L, 2L, 3L), (out["nested"] as Map<*, *>)["a"])
        assertEquals("x".repeat(40), (out["nested"] as Map<*, *>)["b"])
        val s = (out["streams"] as List<*>)[0] as Map<*, *>
        assertEquals("Café", s["name"])
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), s["shk"] as ByteArray)
    }

    companion object {
        const val SRP_A =
            "648c0f7d9c643ee7402599975951ee20baf6c93d45795f4e6c916680e7d59572502eaf6446df62e483f45ce674cca5647c9f" +
            "9822cc5bb1ac24ae9467597c468a3aa57b9c66574433856313021f72533eb840800eaf1b5a58e5bbc6813387151f6217f81b" +
            "7b95b5c7ea9d150eee71d697bae77997ff1774a79e925fd2a3d1db47f5ba51035f7ec1a8b2b1a1f65d4bb37437906a5d475a" +
            "9fe254713a3dcd40f55aaa37259f690d7d7bb6d1a36102629b27e13fbea27a18313976df270573c037bfd3d5798ce4c8a268" +
            "60524e2e2379b3bdffda18ef51a99579adf70bfa51847aa39ba0176cf3a578b50904e18a82dccfe20a53bb9cd00dfbf2e5c8" +
            "d0a924407601a1a0e2e81fe8153ddcc0bf1eba27eaabac40b3c604a4a573c94c36c64debd9573be7aeb069610ef172511044" +
            "86b02d650beaffe0f4c24966c796341c7d818e133a9feff57c94144ac83c1b2cdc9c102b587995fb79b886f8193dd101825e" +
            "e0284f220c3a8700721089ad747f03ce7bd9b6b917f7b2aaf929457ad1fef04c7e1f"
        const val SRP_B =
            "1fee29c9a798f0ba2ec88cfbce9b039c2fe7093631a54ce8009ae428f24caa3ea66fda164730ed5f4b30fcd9f2ff4f179f00" +
            "e6e481e0f13e80a678ec00246615d13c32e605c17adb56999af0008ad8166ad9cf20358346806922ffe243f91efc8507b7c5" +
            "ce0f86be1c51159ed8d37abe445c4d1b11c29f51f94af83b96497c72d02da2c2dfd8f93cc463279f55367d405021c63f187a" +
            "e12d065aa150102661b59170a1446733c137e4f7f9776f71bc58cd7b5aef9370b05ddc051dde510886b92aec84d22f84c61c" +
            "560067c732dd6dcfa073dfa2e70d37646fbea9c1c74ca10d774cacd23f5243f524d90127cea4d6381d500879bdccc23fac0f" +
            "36e017ab7edca8a7a073f3f1ef48245ea5bf51bd5aa21201149c08490c54b09d12e5731ed7cdbc5ed546823702dc0fd696e9" +
            "240c22dfc599e93be130eb4d7937d0c5168589beb56cf6d0d65ad4afe001e357dc77247bf6e217b7824520fd2872f160f7dd" +
            "76e0922a04e727034ac5c2f21a7ecc341935219ba9a45934e1b1f580f7c2864af907"
        const val SRP_K = "6cbde548fab4525c9104baac5a03d2f8d6c516698bdf19492951433d8232e76095b31ba4bc3431d411ec31f7d0a2a48b4655" +
            "0555a8f534622b79f181f12a8eed"
        const val SRP_M1 = "0220c6a74483ea4fe365af03a218eaac8a93dc57032716c7e01a90b0c28fb081f06d60c314754c7e579ef402a359f0d08a19" +
            "13f2dd65984987a198d33b3de8fb"
        const val SRP_M2 = "e0d666ebd7119e230e86f8f39345bc39384cb12c4393bef3ba2bf2afccedecd2ff05c881d3d467fa28df824c9ac8bbc662b6" +
            "9b90d78382c7040fe8ae9c87d16f"
        const val BPLIST =
            "62706c6973743030d2010203135773747265616d735a74696d696e67506f7274a104d705060708090a0b0c0d0e0f10111252" +
            "63745769734d656469615a6c6174656e63794d696e546e616d655373686b5f101273747265616d436f6e6e656374696f6e49" +
            "445474797065100209112b116400430061006600e94400010203130000011f71fb04cb1060111b63080d15202231343c474c" +
            "50656a6c6d70797e8789000000000000010100000000000000140000000000000000000000000000008c"

        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
