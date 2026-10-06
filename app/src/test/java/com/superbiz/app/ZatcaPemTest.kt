package com.superbiz.app

import com.superbiz.app.domain.algo.ZatcaPem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * اختبارات [P16-a] — ZatcaPem (غلاف PEM + بصمة SHA-256 بنمط openssl) —
 * نقي تماماً بلا أندرويد (JUnit4، بدون Robolectric، نمط ZatcaStampTest).
 *
 * بيانات الاختبار: SPKI DER حقيقي لمنحنى P-256 (91 بايتاً — البنية القياسية
 * ذاتها التي يولّدها ZatcaKeys على الجهاز) وُلِّد مرة واحدة بأدوات JDK/SunEC
 * ومُثبِّت نصياً هنا. هذا مفتاح عام مُهمَل لا سرّ فيه، والمفتاح الخاص المقابل
 * أُلقي منذ لحظة التوليد — بيانات ثابتة للقفل الذهبي لا أكثر.
 *
 * المتجهات الذهبية مُثبَّتة مستقلة: PEM الكامل قارَن مُولِّد الاختبار بمنفّذ
 * java.util.Base64.getMimeEncoder(64, "\n") كمرجع مستقل (oracle) خارج الكود
 * المُختبر، وبصمة «abc» هي المتجه القياسي المعروف لـ SHA-256.
 */
class ZatcaPemTest {

    companion object {
        /** SPKI DER حقيقي (91 بايت) لمنحنى P-256 — بيانات اختبار فقط (مفتاح عام مُهمَل) */
        private const val SPKI_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExv5J2oI9tPI4xb/WJkFl3KqDYnbj" +
                "RRahjyuPoPRHTBtQev/ZRnDvV18X8un6jk4HiNAaiY3UMrXoJavZD6KjSQ=="

        private fun spki(): ByteArray = Base64.getDecoder().decode(SPKI_B64)

        private const val BEGIN = "-----BEGIN PUBLIC KEY-----\n"
        private const val END = "-----END PUBLIC KEY-----\n"

        /** فكّ جسم PEM (أسطره الفعلية بين BEGIN وEND — كل سطر مُنهي بـ\n حتى الأخير) */
        private fun bodyBytes(pem: String, begin: String = BEGIN, end: String = END): ByteArray {
            val body = pem.removePrefix(begin).removeSuffix(end).removeSuffix("\n")
            return Base64.getDecoder().decode(body.replace("\n", ""))
        }

        /** أسطر الجسم الفعلية (بلا العنصر الفارغ الذي يُنتجه منزلة النهاية قبل END) */
        private fun bodyLines(pem: String, begin: String = BEGIN, end: String = END): List<String> =
            pem.removePrefix(begin).removeSuffix(end).removeSuffix("\n").split("\n")
    }

    // ───────── 1) toPem — البنية الكاملة على SPKI حقيقي ─────────

    @Test
    fun toPem_fixtureStructure_beginEndLineLengthsAndByteRoundtrip() {
        val pem = ZatcaPem.toPem(spki())
        assertTrue(pem.startsWith(BEGIN))
        assertTrue(pem.endsWith(END))
        // نهاية بمنزلة واحدة بالضبط (لا سطر فارغ أخير)
        assertFalse(pem.endsWith("\n\n"))
        // 91 بايت ⇒ 124 محرف Base64 ⇒ سطر 64 + سطر 60
        val lines = bodyLines(pem)
        assertEquals(2, lines.size)
        assertEquals(64, lines[0].length)
        assertEquals(60, lines[1].length)
        // إعادة بناء الأصل: دمج الأسطر وفكّها ⇒ مطابقة بايتاً ببايت
        assertArrayEquals(spki(), bodyBytes(pem))
    }

    @Test
    fun toPem_fixtureGolden_exactOutputLockedAgainstIndependentOracle() {
        val expected = BEGIN +
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAExv5J2oI9tPI4xb/WJkFl3KqDYnbj\n" +
            "RRahjyuPoPRHTBtQev/ZRnDvV18X8un6jk4HiNAaiY3UMrXoJavZD6KjSQ==\n" +
            END
        assertEquals(expected, ZatcaPem.toPem(spki()))
    }

    // ───────── 2) toPem — الحدود ─────────

    @Test
    fun toPem_emptyInput_returnsEmptyString_lockedDecision() {
        // القرار المُثبَّت في رأس ZatcaPem: إدخال فارغ ⇒ سلسلة فارغة (لا غلاف
        // «مفتاح عام» كاذب بجسم خالٍ) — القفل هنا يمنع تغييره بصمت
        assertEquals("", ZatcaPem.toPem(ByteArray(0)))
    }

    @Test
    fun toPem_oneByte_singlePaddedLine_roundtrip() {
        val input = byteArrayOf(0x2A) // «Kg==» القياسي
        val pem = ZatcaPem.toPem(input)
        val lines = bodyLines(pem)
        assertEquals(1, lines.size) // 4 محارف ⇒ سطر واحد
        assertEquals("Kg==", lines[0])
        assertArrayEquals(input, bodyBytes(pem))
    }

    @Test
    fun toPem_exactly48Bytes_singleFullLine_noWrap() {
        // 48 بايت ⇒ 64 محرف Base64 بالضبط ⇒ سطر وحيد ممتلئ (حدّ الالتفاف نفسه)
        val input = ByteArray(48) { it.toByte() }
        val pem = ZatcaPem.toPem(input)
        val lines = bodyLines(pem)
        assertEquals(1, lines.size)
        assertEquals(64, lines[0].length)
        assertArrayEquals(input, bodyBytes(pem))
    }

    @Test
    fun toPem_49Bytes_wrapsToSecondLine_roundtrip() {
        // 49 بايت ⇒ 68 محرفاً ⇒ سطر 64 + سطر 4 (أول انتقال عبر السطور)
        val input = ByteArray(49) { (it + 1).toByte() }
        val pem = ZatcaPem.toPem(input)
        val lines = bodyLines(pem)
        assertEquals(2, lines.size)
        assertEquals(64, lines[0].length)
        assertEquals(4, lines[1].length)
        assertArrayEquals(input, bodyBytes(pem))
    }

    @Test
    fun toPem_lineLengthInvariant_manySizes_lfOnlyAndRoundtrip() {
        for (size in intArrayOf(1, 2, 3, 31, 47, 48, 49, 64, 90, 91, 100, 180)) {
            val input = ByteArray(size) { (it * 7 + 3).toByte() }
            val pem = ZatcaPem.toPem(input)
            // \n وحده — لا \r إطلاقاً (openssl نمط يونكس)
            assertFalse("CR at size=$size", pem.contains('\r'))
            val lines = bodyLines(pem)
            assertTrue("empty body at size=$size", lines.isNotEmpty())
            for (i in 0 until lines.size - 1) {
                assertEquals("line $i at size=$size", 64, lines[i].length)
            }
            assertTrue("last line too long at size=$size", lines.last().length <= 64)
            assertTrue("last line empty at size=$size", lines.last().isNotEmpty())
            assertArrayEquals("roundtrip at size=$size", input, bodyBytes(pem))
        }
    }

    // ───────── 3) toPem — التسمية ─────────

    @Test
    fun toPem_label_defaultIsPublicKey_customIsUsedVerbatim() {
        // الافتراضي PUBLIC KEY (يتحقق فيه كل اختبار البنية أعلاه — هنا توكيد صريح)
        assertTrue(ZatcaPem.toPem(spki()).startsWith(BEGIN))
        val custom = ZatcaPem.toPem(spki(), "EC PUBLIC KEY")
        assertTrue(custom.startsWith("-----BEGIN EC PUBLIC KEY-----\n"))
        assertTrue(custom.endsWith("-----END EC PUBLIC KEY-----\n"))
        // الجسم نفسه لا يتأثر بالتسمية
        assertArrayEquals(spki(), bodyBytes(custom, "-----BEGIN EC PUBLIC KEY-----\n", "-----END EC PUBLIC KEY-----\n"))
    }

    // ───────── 4) fingerprintSha256 — المتجهات والتنسيق ─────────

    @Test
    fun fingerprintSha256_emptyInput_emptyString() {
        assertEquals("", ZatcaPem.fingerprintSha256(ByteArray(0)))
    }

    @Test
    fun fingerprintSha256_abc_knownVector() {
        // SHA-256("abc") القياسي — بنمط openssl: hex صغير مفصولاً بنقطتين
        assertEquals(
            "ba:78:16:bf:8f:01:cf:ea:41:41:40:de:5d:ae:22:23:" +
                "b0:03:61:a3:96:17:7a:9c:b4:10:ff:61:f2:00:15:ad",
            ZatcaPem.fingerprintSha256("abc".toByteArray(Charsets.US_ASCII))
        )
    }

    @Test
    fun fingerprintSha256_fixtureFormat_95Chars31ColonsLowercaseHexOnly() {
        val fp = ZatcaPem.fingerprintSha256(spki())
        // 64 محرف hex + 31 نقطتين = 95 محرفاً بالضبط
        assertEquals(95, fp.length)
        assertEquals(31, fp.count { it == ':' })
        assertEquals(fp, fp.lowercase()) // لا حروف كبيرة إطلاقاً
        // محارف مسموحة فقط: أرقام أو abcdef أو نقطتين
        assertTrue(fp.all { it.isDigit() || it in 'a'..'f' || it == ':' })
        // لا نقطتين في الطرفين ولا نقطتان متجاورتان
        assertFalse(fp.startsWith(":"))
        assertFalse(fp.endsWith(":"))
        assertFalse(fp.contains("::"))
    }

    @Test
    fun fingerprintSha256_deterministic_andDiscriminatesInputs() {
        assertEquals(ZatcaPem.fingerprintSha256(spki()), ZatcaPem.fingerprintSha256(spki()))
        assertFalse(
            ZatcaPem.fingerprintSha256(spki()) ==
                ZatcaPem.fingerprintSha256("abc".toByteArray(Charsets.US_ASCII))
        )
    }
}
