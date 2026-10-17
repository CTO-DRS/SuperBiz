package com.superbiz.app.domain.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RVW-8 V 3.2.3] عقود ملح الإقران — الاختبار الجافي للوحدة النقية:
 *
 * ① الحجم 16 بايت حرفياً والعشوائية حقيقية (توليدان متتاليان مختلفان).
 * ② الترميز/الفك دالة أُسّية بلا فقد — نفس البايتات تعود حرفياً.
 * ③ الفك صارم فشله مغلق: فارغ/حجم مخالف/محارف غريبة ⇒ null لا بايتات ملفّقة.
 * ④ الاشتقاق بملحين مختلفين يعطي KEK مختلفين (جوهر إغلاق الجدولة المسبقة)،
 *    وبملحٍ واحد حتمي (شرط تطابق KEK بين الجهازين — بصمة الإقرار D5).
 *    معاملات مصغّرة للسرعة — العقود التطابقية للمعاملات الإنتاجية في Argon2idTest.
 */
class PairingSaltTest {

    @Test
    fun `newSalt is 16 bytes and non-deterministic`() {
        val a = PairingSalt.newSalt()
        val b = PairingSalt.newSalt()
        assertEquals(PairingSalt.SIZE_BYTES, 16)
        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `encode decode roundtrip is identity`() {
        val salt = PairingSalt.newSalt()
        val text = PairingSalt.encode(salt)
        assertTrue(text.isNotBlank())
        assertArrayEquals(salt, PairingSalt.decodeOrNull(text))
        // الحتمية: نفس الملح يرمّز بنفس النص (العرض يجب أن يكون مستقراً)
        assertEquals(text, PairingSalt.encode(salt))
    }

    @Test
    fun `encode rejects wrong size — fail closed`() {
        try {
            PairingSalt.encode(ByteArray(15))
            throw AssertionError("encode(15B) must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("16B"))
        }
    }

    @Test
    fun `decodeOrNull strict — blank wrong-size and garbage are null`() {
        assertNull(PairingSalt.decodeOrNull(""))
        assertNull(PairingSalt.decodeOrNull("   "))
        // نفس المرمّز بمدخل 12 بايت ⇒ نص صالح ترميزاً بحجم مخالف ⇒ null
        val wrongSize = SyncCrypto.toBase64(ByteArray(12))
        assertNull(PairingSalt.decodeOrNull(wrongSize))
        assertNull(PairingSalt.decodeOrNull("!!!not-base64-at-all!!!"))
        // المدخل يُقتطع من الحواف — نسخة بمسافات عرضية تبقى صالحة
        val salt = PairingSalt.newSalt()
        assertEquals(
            PairingSalt.encode(salt),
            PairingSalt.encode(PairingSalt.decodeOrNull("  " + PairingSalt.encode(salt) + " ")!!)
        )
    }

    @Test
    fun `different salts derive different KEKs - same salt derives deterministically`() {
        val pass = "pairing-passphrase-1".toByteArray(Charsets.UTF_8)
        val s1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val s2 = byteArrayOf(16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
        val k1a = Argon2id.derive(pass, s1, tagLen = 16, memoryKib = 8, iterations = 1)
        val k1b = Argon2id.derive(pass, s1, tagLen = 16, memoryKib = 8, iterations = 1)
        val k2 = Argon2id.derive(pass, s2, tagLen = 16, memoryKib = 8, iterations = 1)
        // حتمية بملح واحد — شرط تطابق KEK بين الجهازين (D5)
        assertArrayEquals(k1a, k1b)
        // تفرّد بملحين — إغلاق الجدولة المسبقة (v2)
        assertFalse(k1a.contentEquals(k2))
        assertEquals(16, k1a.size)
        // والملح الثابت v1 يبقى مسموحاً كافتراض — عقد التوافق محفوظ
        assertNotNull(Argon2id.DOMAIN_SALT)
        assertNotEquals(0, Argon2id.DOMAIN_SALT.size)
    }
}
