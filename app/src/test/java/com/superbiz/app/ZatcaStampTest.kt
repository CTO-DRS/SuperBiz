package com.superbiz.app

import com.superbiz.app.domain.algo.ZatcaQr
import com.superbiz.app.domain.algo.ZatcaStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * اختبارات [P15-a] — بصمة ZATCA المرحلة-2 (الهاش + التوقيع + المفتاح العام) —
 * نقي تماماً بلا أندرويد (JUnit4، بدون Robolectric، نمط RouteOrderTest/ZatcaQrTest).
 *
 * التوقيع هنا بمفتاح برمجي (SunEC، secp256r1 = NIST P-256 — المنحنى نفسه الذي
 * يولّده ZatcaKeys على الجهاز)، و«SHA256withECDSA» قياسي في java.security —
 * نفس العقد الذي يلتفّ عليه مغلف AndroidKeyStore في ZatcaKeys.signer().
 *
 * ملاحظة: اختبارات حدّ الوسم/الطول لـ TLV موجودة في ZatcaQrTest
 * (boundary_255ValueBytesAccepted_256Rejected) ولا تُكرَّر هنا —
 * stampedPayloadBytes يعيد استخدام ZatcaQr.tlv حرفياً فالحدود نفسها مغطاة.
 * المتجهات الذهبية للهاش مُثبَّتة مستقلاً (مخرجات SHA-256 القياسية لـ "" و"abc").
 */
class ZatcaStampTest {

    /** 2022-04-25T15:30:00Z — نفس عينة ZatcaQrTest كي تتطابق المقارنات بين المرحلتين */
    private fun sampleTs(): Long =
        GregorianCalendar(2022, Calendar.APRIL, 25, 15, 30, 0).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.timeInMillis

    // ─── تجهيز مفتاح برمجي P-256 ومغلف توقيع/نقطة عامة على نمط ZatcaKeys ───

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    /** مُوقِّع برمجي بنفس عقد ZatcaStamp.Signer: بيانات خام ⇒ توقيع DER (التهشيم داخلي) */
    private fun softwareSigner(kp: KeyPair) = ZatcaStamp.Signer { data ->
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(kp.private)
            update(data)
        }.sign()
    }

    /** آخر 65 بايت من ترميز X.509 لمفتاح عام — نفس قاعدة الاستخراج الدفاعية في ZatcaKeys */
    private fun pubPointOf(kp: KeyPair): ByteArray {
        val enc = kp.public.encoded
        return enc.copyOfRange(enc.size - 65, enc.size)
    }

    /** محلّل TLV مساعد لفحص بنية الحمولة (وسم بايت + طول بايت + قيمة) */
    private fun parseTags(bytes: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var i = 0
        while (i < bytes.size) {
            val tag = bytes[i].toInt()
            val len = bytes[i + 1].toInt()
            out[tag] = bytes.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        return out
    }

    private fun sampleXml(): ByteArray = ZatcaStamp.canonicalXml(
        "شركة النور", "300012345600003", sampleTs(), 115.0, 15.0, 3, "INV-100"
    ).toByteArray(Charsets.UTF_8)

    // ───────── 1) sha256Base64 — متجهات معروفة ─────────

    @Test
    fun sha256Base64_knownVectors_emptyAndAbc() {
        // SHA-256("") القياسي
        assertEquals(
            "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=",
            ZatcaStamp.sha256Base64(ByteArray(0))
        )
        // SHA-256("abc") القياسي
        assertEquals(
            "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
            ZatcaStamp.sha256Base64("abc".toByteArray(Charsets.US_ASCII))
        )
    }

    @Test
    fun sha256Base64_digestIs32Bytes_base64Is44Chars() {
        val b64 = ZatcaStamp.sha256Base64(sampleXml())
        assertEquals(44, b64.length) // Base64 لـ 32 بايت = 44 محرفاً (بحشو «=» واحد)
    }

    // ───────── 2) canonicalXml حتمي ─────────

    @Test
    fun canonicalXml_deterministic_sameInputSameBytes() {
        val a = ZatcaStamp.canonicalXml(
            "متجر الأمل", "310122393500003", sampleTs(), 1150.5, 172.5, 7, "INV-7"
        )
        val b = ZatcaStamp.canonicalXml(
            "متجر الأمل", "310122393500003", sampleTs(), 1150.5, 172.5, 7, "INV-7"
        )
        assertEquals(a, b) // نصاً
        assertTrue(a.toByteArray(Charsets.UTF_8).contentEquals(b.toByteArray(Charsets.UTF_8))) // وبايتات
        // تغيير أي مدخل يغيّر الناتج (لا تجميد غلط للحالة)
        assertTrue(
            a != ZatcaStamp.canonicalXml(
                "متجر الأمل", "310122393500003", sampleTs(), 1150.5, 172.5, 8, "INV-7"
            )
        )
    }

    // ───────── 3) ترتيب الحقول والتهريب ─────────

    @Test
    fun canonicalXml_fieldOrderAndEscaping_exactForm() {
        val xml = ZatcaStamp.canonicalXml(
            sellerName = "متجر <أ&ب\">", vatNumber = "300012345600003",
            timestampMs = sampleTs(), invoiceTotal = 115.0, vatTotal = 15.0,
            lineNumberCount = 3, invoiceNumber = "INV-1"
        )
        val expected = "<Invoice><InvoiceNumber>INV-1</InvoiceNumber>" +
            "<SellerName>متجر &lt;أ&amp;ب&quot;&gt;</SellerName>" +
            "<VATNumber>300012345600003</VATNumber>" +
            "<TimeStamp>2022-04-25T15:30:00Z</TimeStamp>" +
            "<InvoiceTotal>115.00</InvoiceTotal>" +
            "<VATTotal>15.00</VATTotal>" +
            "<LineCount>3</LineCount></Invoice>"
        assertEquals(expected, xml)
    }

    // ───────── 4) بنية الحمولة المرحلة-2 ─────────

    @Test
    fun stampedPayloadBytes_structure_tags678() {
        val kp = newKeyPair()
        val xmlBytes = sampleXml()
        val sig = softwareSigner(kp).sign(xmlBytes)
        val point = pubPointOf(kp)
        val p1to5 = ZatcaQr.qrPayloadBytes("شركة النور", "300012345600003", sampleTs(), 115.0, 15.0)
        val hash = ZatcaStamp.sha256Base64(xmlBytes)

        val stamped = ZatcaStamp.stampedPayloadBytes(p1to5, hash, sig, point)

        // البادئة = حمولة المرحلة-1 حرفياً (توافق بايتاً ببايت)
        assertEquals(p1to5.toList(), stamped.copyOfRange(0, p1to5.size).toList())
        // البنية: وسم 6 = هاش Base64 (44 بايت ASCII)، وسم 7 = التوقيع، وسم 8 = نقطة 65 بايت
        val tags = parseTags(stamped)
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8), tags.keys)
        assertEquals(44, tags[6]!!.size)
        assertEquals(hash, String(tags[6]!!, Charsets.US_ASCII))
        assertTrue(sig.contentEquals(tags[7]!!))
        assertEquals(65, tags[8]!!.size)
        assertTrue(point.contentEquals(tags[8]!!))
        assertEquals(0x04, tags[8]!![0].toInt())
        // الطول الكلي
        assertEquals(p1to5.size + (2 + 44) + (2 + sig.size) + (2 + 65), stamped.size)
    }

    // ───────── 5) توقيع/تحقق ذهاباً وإياباً ─────────

    @Test
    fun signThenVerify_roundtrip_true() {
        val kp = newKeyPair()
        val xmlBytes = sampleXml()
        val sig = softwareSigner(kp).sign(xmlBytes)
        assertTrue(ZatcaStamp.verify(sig, pubPointOf(kp), xmlBytes))
    }

    @Test
    fun verify_tamperedData_false() {
        val kp = newKeyPair()
        val xmlBytes = sampleXml()
        val sig = softwareSigner(kp).sign(xmlBytes)
        val tampered = xmlBytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse(ZatcaStamp.verify(sig, pubPointOf(kp), tampered))
    }

    @Test
    fun verify_tamperedSignature_false() {
        val kp = newKeyPair()
        val xmlBytes = sampleXml()
        val sig = softwareSigner(kp).sign(xmlBytes)
        val badSig = sig.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse(ZatcaStamp.verify(badSig, pubPointOf(kp), xmlBytes))
    }

    @Test
    fun verify_wrongKeyAndMalformedPoint_falseWithoutThrowing() {
        val kp1 = newKeyPair()
        val kp2 = newKeyPair()
        val xmlBytes = sampleXml()
        val sig = softwareSigner(kp1).sign(xmlBytes)
        // توقيع صحيح بمفتاح آخر ⇒ false
        assertFalse(ZatcaStamp.verify(sig, pubPointOf(kp2), xmlBytes))
        // نقطة مشوّهة (طول خاطئ / بلا بادئة 0x04) ⇒ false بلا استثناءات
        assertFalse(ZatcaStamp.verify(sig, ByteArray(64), xmlBytes))
        assertFalse(ZatcaStamp.verify(sig, ByteArray(65), xmlBytes))
    }

    // ───────── 6) التهريب — الكيانات الخمسة كاملة ─────────

    @Test
    fun xmlEscape_allFiveEntities_arabicUntouched_noDoubleEscaping() {
        // الكيانات الخمسة واحدة واحدة
        assertEquals("&amp;", ZatcaStamp.xmlEscape("&"))
        assertEquals("&lt;", ZatcaStamp.xmlEscape("<"))
        assertEquals("&gt;", ZatcaStamp.xmlEscape(">"))
        assertEquals("&quot;", ZatcaStamp.xmlEscape("\""))
        assertEquals("&apos;", ZatcaStamp.xmlEscape("'"))
        // «&» تُهرب أولاً — لا تهريب مزدوج لكيان مُهرَّب مسبقاً
        assertEquals("&amp;lt;", ZatcaStamp.xmlEscape("&lt;"))
        assertEquals("&amp;amp;", ZatcaStamp.xmlEscape("&amp;"))
        // العربية والحروف غير اللاتينية تمر كما هي
        assertEquals("شركة النور ١٢٣", ZatcaStamp.xmlEscape("شركة النور ١٢٣"))
        // مزيج
        assertEquals("أ&amp;ب&lt;ج&gt;د&quot;ه&apos;و", ZatcaStamp.xmlEscape("أ&ب<ج>د\"ه'و"))
    }

    // ───────── 7) توافق المرحلة-1 ─────────

    @Test
    fun stage1Compat_stampedPrefix_equalsQrPayloadBytes() {
        val kp = newKeyPair()
        val xmlBytes = sampleXml()
        val sig = softwareSigner(kp).sign(xmlBytes)
        val p1to5 = ZatcaQr.qrPayloadBytes("شركة النور", "300012345600003", sampleTs(), 115.0, 15.0)
        val stamped = ZatcaStamp.stampedPayloadBytes(
            p1to5, ZatcaStamp.sha256Base64(xmlBytes), sig, pubPointOf(kp)
        )
        // إعادة البناء العكسي: حذف الوسوم 6/7/8 يعيد حمولة المرحلة-1 نفسها
        val again = stamped.copyOfRange(0, p1to5.size)
        assertTrue(p1to5.contentEquals(again))
        // والحمولة النهائية (Base64) تُبنى من toBase64 نفسها المستخدمة للمرحلة-1.
        // شرط بدء Base64 للجزء المدمج بترميز الجزء الأول حرفياً: طول الجزء الأول
        // من مضاعفات 3 (عندنا 75 بايت بالضبط) — شرط مُصرَّح به كي يفشل loudly
        // لو غيّر أحد العينة دون انتباه بدل فشل غامض في startsWith
        assertEquals(0, p1to5.size % 3)
        val b64 = ZatcaQr.toBase64(stamped)
        assertTrue(b64.startsWith(ZatcaQr.qrPayload("شركة النور", "300012345600003", sampleTs(), 115.0, 15.0)))
    }

    // ───────── 8) اتساق الطابع الزمني مع وسم 3 ─────────

    @Test
    fun canonicalXml_timestampMatchesTag3OfStage1() {
        val name = "شركة النور"
        val vat = "300012345600003"
        val stage1 = parseTags(ZatcaQr.qrPayloadBytes(name, vat, sampleTs(), 115.0, 15.0))
        val tsTag3 = String(stage1[3]!!, Charsets.US_ASCII)
        val xml = ZatcaStamp.canonicalXml(name, vat, sampleTs(), 115.0, 15.0, 1, "INV-9")
        val ts = xml.substringAfter("<TimeStamp>").substringBefore("</TimeStamp>")
        assertEquals(tsTag3, ts) // وسم 3 و«TimeStamp» من الصيغة ISO-8601 UTC نفسها
    }
}
