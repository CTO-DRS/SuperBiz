package com.superbiz.app.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * اختبارات [Argon2id] — ثلاث بوابات معيارية:
 * 1) متجه RFC 9106 §5.3 الرسمي (م=32KiB، ت=3، ع=4) — الوسم النهائي.
 * 2) متجه بمعاملات الإنتاج (م=64MiB، ت=3، ع=1) مولَّد من المكتبة المرجعية argon2-cffi.
 * 3) متجه صغير سريع (م=64KiB، ت=1، ع=1) من المرجع نفسه — لحارس الانحراف في كل دورة CI.
 */
class Argon2idTest {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `RFC 9106 section 5_3 argon2id vector`() {
        val pwd = ByteArray(32) { 0x01 }
        val salt = ByteArray(16) { 0x02 }
        val tag = Argon2id.derive(
            password = pwd, salt = salt, tagLen = 32,
            memoryKib = 32L, iterations = 3, parallelism = 4,
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 }
        )
        assertEquals(
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            tag.toHex()
        )
    }

    @Test
    fun `small reference vector — argon2-cffi m=64 t=1 p=1`() {
        val tag = Argon2id.derive(
            password = "TestPassphrase-2026".toByteArray(Charsets.UTF_8),
            salt = Argon2id.DOMAIN_SALT, tagLen = 32,
            memoryKib = 64L, iterations = 1, parallelism = 1
        )
        assertEquals(
            "337ce2030516e7d0d13ea6f457ff4a5bc47769db6b3b6abf2d2b7e13a8af394b",
            tag.toHex()
        )
    }

    @Test
    fun `production vector — argon2-cffi m=64MiB t=3 p=1 (ADR-002 D1)`() {
        val tag = Argon2id.derive(
            password = "TestPassphrase-2026".toByteArray(Charsets.UTF_8),
            salt = Argon2id.DOMAIN_SALT, tagLen = 32
        )
        assertEquals(
            "78addb4f1ae0320e1e81a088321152cedc36ee76007b054421f59b1aee0c3563",
            tag.toHex()
        )
    }

    @Test
    fun `same passphrase and salt derive identical keys, different do not`() {
        val k1 = Argon2id.derive("pass-a".toByteArray(), memoryKib = 64L, iterations = 1)
        val k2 = Argon2id.derive("pass-a".toByteArray(), memoryKib = 64L, iterations = 1)
        val k3 = Argon2id.derive("pass-b".toByteArray(), memoryKib = 64L, iterations = 1)
        assertEquals(k1.toHex(), k2.toHex())
        assertEquals(false, k1.toHex() == k3.toHex())
    }

    @Test
    fun `parameter guards`() {
        assertThrows(IllegalArgumentException::class.java) {
            Argon2id.derive("x".toByteArray(), memoryKib = 4L, parallelism = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Argon2id.derive("x".toByteArray(), memoryKib = 64L, parallelism = 0)
        }
    }
}
