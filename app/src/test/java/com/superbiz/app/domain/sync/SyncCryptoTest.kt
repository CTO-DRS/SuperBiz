package com.superbiz.app.domain.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات [SyncCrypto] — عقد D4: AEAD مغلق، nonce أحادي التصاعد، AAD يمنع
 * نقل الكتلة بين هويتين، وفشل فك التعمية فشل مغلقاً (لا كتلة مشوهة تُطبَّق).
 */
class SyncCryptoTest {

    private fun kek() = ByteArray(32) { (it * 7 + 3).toByte() }
    private fun kek2() = ByteArray(32) { (it * 11 + 5).toByte() }

    @Test
    fun `encrypt-decrypt roundtrip preserves payload`() {
        val payload = """{"format":"superbiz-sync","rows":[{"t":"parties","k":"a|1"}]}""".toByteArray()
        val block = SyncCrypto.EncryptedBlock("device-a", 1L, SyncCrypto.newNoncePrefix(), ByteArray(0))
        val enc = SyncCrypto.encrypt(kek(), block, payload)
        val dec = SyncCrypto.decrypt(kek(), enc)
        assertArrayEquals(payload, dec)
    }

    @Test
    fun `wrong key fails closed`() {
        val payload = "secret-payload".toByteArray()
        val block = SyncCrypto.EncryptedBlock("device-a", 1L, SyncCrypto.newNoncePrefix(), ByteArray(0))
        val enc = SyncCrypto.encrypt(kek(), block, payload)
        assertThrows(Exception::class.java) { SyncCrypto.decrypt(kek2(), enc) }
    }

    @Test
    fun `tampered ciphertext fails closed (GCM tag)`() {
        val payload = "secret-payload".toByteArray()
        val block = SyncCrypto.EncryptedBlock("device-a", 1L, SyncCrypto.newNoncePrefix(), ByteArray(0))
        val enc = SyncCrypto.encrypt(kek(), block, payload)
        val tampered = enc.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x41).toByte() }
        assertThrows(Exception::class.java) {
            SyncCrypto.decrypt(kek(), enc.copy(ciphertext = tampered))
        }
    }

    @Test
    fun `replay across device identity fails (AAD)`() {
        val payload = "for-a-only".toByteArray()
        val enc = SyncCrypto.encrypt(
            kek(),
            SyncCrypto.EncryptedBlock("device-a", 1L, SyncCrypto.newNoncePrefix(), ByteArray(0)),
            payload
        )
        // نفس الكتلة بمعرّف جهاز آخر (نقل بين هويتين) — AAD يردها
        val relayed = enc.copy(deviceId = "device-b")
        assertThrows(Exception::class.java) { SyncCrypto.decrypt(kek(), relayed) }
    }

    @Test
    fun `same seq with same prefix reuses nonce — seq must be monotonic (guard)`() {
        // العقد: nonce = prefix4 + seq8 — seq مكرر لنفس المفتاح يكرر الـnonce
        // (لذلك SyncClock.nextSeq عقد أحادي التصاعد — هذا الاختبار يثبت البنية فقط)
        val p = SyncCrypto.newNoncePrefix()
        assertEquals(4, p.size)
        val n1 = SyncCrypto.EncryptedBlock("d", 1L, p, ByteArray(0))
        val n2 = SyncCrypto.EncryptedBlock("d", 2L, p, ByteArray(0))
        assertTrue(!n1.equals(n2))
    }

    @Test
    fun `base64 roundtrip for binary blocks`() {
        val data = ByteArray(257) { (it * 31).toByte() }
        val b64 = SyncCrypto.toBase64(data)
        assertArrayEquals(data, SyncCrypto.fromBase64(b64))
        assertArrayEquals(ByteArray(0), SyncCrypto.fromBase64(SyncCrypto.toBase64(ByteArray(0))))
    }

    @Test
    fun `argon2 kek is 32 bytes for AES-256`() {
        val kek = Argon2id.derive("pass".toByteArray(), memoryKib = 64L, iterations = 1)
        assertEquals(32, kek.size)
    }
}
