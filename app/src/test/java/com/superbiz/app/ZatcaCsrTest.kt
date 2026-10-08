package com.superbiz.app

import com.superbiz.app.domain.algo.ZatcaCsr
import com.superbiz.app.domain.algo.ZatcaStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory

/**
 * اختبارات [Z2-أ V 1.5.0] — مولّد CSR بصيغة ZATCA — نقي تماماً بلا أندرويد.
 *
 * التحقق هنا بنيوي ذاتي الغلق: نبني CSR بمفتاح برمجي P-256 ثم نحلل TLV ناتجنا
 * حرفياً ونتحقق أن (أ) التوقيع ECDSA صحيح على CertificationRequestInfo نفسها،
 * (ب) الموضوع يحمل الحقول الخمسة بترتيبها وقيمها، (ج) الامتدادات الثلاثة موجودة،
 * (د) الحتمية البايتية لجسم الطلب، (هـ) الأطوال الطويلة 0x81/0x82 للأسماء العربية،
 * (و) شكل PEM.
 */

/** محلّل TLV مصغّر خاص بالاختبار (على مستوى الملف كي تستخدمه العقدة الداخلية) */
private fun readAt(buf: ByteArray, offset: Int): DerNode {
    var i = offset
    val tag = buf[i].toInt() and 0xFF
    i++
    var len = buf[i].toInt() and 0xFF
    i++
    if (len and 0x80 != 0) {
        val n = len and 0x7F
        len = 0
        repeat(n) { len = (len shl 8) or (buf[i].toInt() and 0xFF); i++ }
    }
    val content = buf.copyOfRange(i, i + len)
    return DerNode(tag, content, buf.copyOfRange(offset, i + len))
}

/** عقدة DER مصغّرة للفحص البنيوي */
private class DerNode(val tag: Int, val content: ByteArray, val full: ByteArray) {
    val children: List<DerNode> by lazy {
        if (tag != 0x30 && tag != 0x31 && tag != 0xA0) emptyList()
        else {
            val out = ArrayList<DerNode>()
            var i = 0
            while (i < content.size) {
                val n = readAt(content, i)
                out.add(n)
                i += n.full.size
            }
            out
        }
    }
}

class ZatcaCsrTest {

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun softwareSigner(kp: KeyPair) = ZatcaStamp.Signer { data ->
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(kp.private)
            update(data)
        }.sign()
    }

    private fun pubPointOf(kp: KeyPair): ByteArray {
        val enc = kp.public.encoded
        return enc.copyOfRange(enc.size - 65, enc.size)
    }

    // ─── محلّل DER مصغّر خاص بالاختبار (البنية على مستوى الملف أعلاه) ───

    /** بيانات اختبار: حقول عربية + ASCII مختلطة */
    private val input = ZatcaCsr.CsrInput(
        vatNumber = "300012345600003",
        invoiceType = "0100000",
        solutionName = "SuperBiz",
        branchName = "فرع الرياض الرئيسي",
        country = "SA",
    )

    // ───────── 1) التوقيع على CertificationRequestInfo ─────────

    @Test
    fun signature_verifiesOverCertificationRequestInfo() {
        val kp = newKeyPair()
        val der = ZatcaCsr.buildDer(input, pubPointOf(kp), softwareSigner(kp))
        val outer = readAt(der, 0)
        assertEquals(0x30, outer.tag)
        // العنصر الأول = CertificationRequestInfo (يُعاد ترميزه كما هو لأن DER حتمي هنا)
        val cri = outer.children[0]
        val pub: PublicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(kp.public.encoded))
        val v = Signature.getInstance("SHA256withECDSA")
        v.initVerify(pub)
        v.update(cri.full)
        val sigNode = readAt(outer.children[2].content, 1) // BIT STRING بعد بايت unused-bits
        assertTrue("توقيع CSR يجب أن يتحقق على CRI كاملاً", v.verify(sigNode.full))
    }

    // ───────── 2) الموضوع: خمسة RDNs بقيمها وترتيبها ─────────

    @Test
    fun subject_containsFiveRdnswithExactValuesInOrder() {
        val kp = newKeyPair()
        val der = ZatcaCsr.buildDer(input, pubPointOf(kp), softwareSigner(kp))
        val cri = readAt(der, 0).children[0]
        val subject = cri.children[1]
        assertEquals(0x30, subject.tag)
        val rdns = subject.children
        assertEquals(5, rdns.size)
        // الترتيب الحتمي الموثق: CN, SN, OU, O, C
        val expected = listOf(
            "2.5.4.3" to "300012345600003",
            "2.5.4.5" to "0100000",
            "2.5.4.11" to "SuperBiz",
            "2.5.4.10" to "فرع الرياض الرئيسي",
            "2.5.4.6" to "SA",
        )
        for (i in expected.indices) {
            val atv = rdns[i].children[0] // SET { SEQUENCE { OID, value } }
            val oidNode = atv.children[0]
            val valueNode = atv.children[1]
            assertEquals("OID at index $i", expected[i].first, decodeOid(oidNode.content))
            // العربية ⇒ UTF8String (0x0C)، والASCII ⇒ PrintableString (0x13)
            val expectedTag = if (expected[i].second.all { it.code in 0x20..0x7E }) 0x13 else 0x0C
            assertEquals("tag at index $i", expectedTag, valueNode.tag)
            assertEquals(expected[i].second, String(valueNode.content, Charsets.UTF_8))
        }
    }

    // ───────── 3) الامتدادات الثلاثة داخل extensionRequest ─────────

    @Test
    fun attributes_extensionRequestWithThreeExtensions() {
        val kp = newKeyPair()
        val der = ZatcaCsr.buildDer(input, pubPointOf(kp), softwareSigner(kp))
        val cri = readAt(der, 0).children[0]
        val attrs = cri.children[3] // [0] IMPLICIT
        assertEquals(0xA0, attrs.tag)
        val extReq = attrs.children[0]
        assertEquals("1.2.840.113549.1.9.14", decodeOid(extReq.children[0].content))
        val extensions = extReq.children[1].children[0] // SET { SEQUENCE OF Extension }
        val oids = extensions.children.map { decodeOid(it.children[0].content) }
        assertEquals(listOf("2.5.29.15", "2.5.29.37", "2.5.29.19"), oids)
        // keyUsage حاسم (BOOLEAN TRUE) وقيمته BIT STRING [03, 02, 06, C0]: 6 unused bits ثم 0xC0
        val ku = extensions.children[0]
        assertEquals(0x01, ku.children[1].tag)
        assertEquals(1, ku.children[1].content[0].toInt())
        val kuValue = ku.children[2].content // OCTET STRING يحمل BIT STRING كاملة
        assertEquals(0x03, kuValue[0].toInt() and 0xFF)  // BIT STRING tag
        assertEquals(6, kuValue[2].toInt() and 0xFF)     // unused bits
        assertEquals(0xC0.toByte(), kuValue[3])          // bits 0,1
    }

    // ───────── 4) SPKI يحمل النقطة نفسها ─────────

    @Test
    fun spki_carriesTheExact65BytePoint() {
        val kp = newKeyPair()
        val point = pubPointOf(kp)
        val der = ZatcaCsr.buildDer(input, point, softwareSigner(kp))
        val cri = readAt(der, 0).children[0]
        val spki = cri.children[2]
        val bitString = spki.children[1].content // unused-bits byte + النقطة
        assertEquals(66, bitString.size)
        assertEquals(0, bitString[0].toInt()) // unused bits = 0
        assertTrue(point.contentEquals(bitString.copyOfRange(1, bitString.size)))
    }

    // ───────── 5) حتمية جسم الطلب (CRI) بين نداءين ─────────

    @Test
    fun certificationRequestInfo_isByteDeterministic_acrossCalls() {
        val kp = newKeyPair()
        val point = pubPointOf(kp)
        val der1 = ZatcaCsr.buildDer(input, point, softwareSigner(kp))
        val der2 = ZatcaCsr.buildDer(input, point, softwareSigner(kp))
        val cri1 = readAt(der1, 0).children[0]
        val cri2 = readAt(der2, 0).children[0]
        assertTrue(cri1.full.contentEquals(cri2.full))
        // التوقيع وحده ECDSA غير حتمي — لكن بنيته ثابتة
        assertEquals(readAt(der1, 0).children[1].full.size, readAt(der2, 0).children[1].full.size)
    }

    // ───────── 6) طول DER طويل للأسماء العربية ─────────

    @Test
    fun der_longFormLength_usedForLongArabicSubjects() {
        val longName = "ش".repeat(80) // 80×2 بايت = 160 > 127 ⇒ طول طويل 0x81
        val inp = input.copy(branchName = longName)
        val cri = ZatcaCsr.certificationRequestInfo(inp, pubPointOf(newKeyPair()))
        // نبحث داخل Subject عن RDN قيمتها بطول 160
        val subject = readAt(cri, 0).children[1]
        var foundLong = false
        for (rdn in subject.children) {
            val value = rdn.children[0].children[1]
            if (value.content.size == 160) {
                foundLong = true
                // full = tag(1) + len bytes — 160 ⇒ 0x81 ثم 0xA0
                assertEquals(0x81, value.full[1].toInt() and 0xFF)
                assertEquals(0xA0, value.full[2].toInt() and 0xFF)
            }
        }
        assertTrue("يجب أن يظهر الطول الطويل لاسم 160 بايتاً", foundLong)
    }

    // ───────── 7) شكل PEM ─────────

    @Test
    fun pem_hasCertificateRequestEnvelope_64CharLines() {
        val kp = newKeyPair()
        val pem = ZatcaCsr.buildPem(input, pubPointOf(kp), softwareSigner(kp))
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE REQUEST-----\n"))
        assertTrue(pem.endsWith("-----END CERTIFICATE REQUEST-----\n"))
        val body = pem.substringAfter("-----\n").substringBefore("-----END")
        for (line in body.lines()) {
            if (line.isNotEmpty()) assertTrue("سطر PEM >64: ${line.length}", line.length <= 64)
        }
        assertTrue(body.lines().count() > 2)
    }

    // ───────── 8) رفض نقطة غير صالحة ─────────

    @Test(expected = IllegalArgumentException::class)
    fun buildDer_rejectsMalformedPoint() {
        ZatcaCsr.buildDer(input, ByteArray(64), softwareSigner(newKeyPair()))
    }

    // ───────── فك ترميز OID (اختبار فقط) ─────────

    private fun decodeOid(content: ByteArray): String {
        val parts = ArrayList<Long>()
        var value = 0L
        for (b in content) {
            value = (value shl 7) or (b.toInt() and 0x7F).toLong()
            if (b.toInt() and 0x80 == 0) {
                parts.add(value)
                value = 0
            }
        }
        val first = parts.removeAt(0)
        val out = StringBuilder()
        out.append(first / 40).append('.').append(first % 40)
        for (p in parts) out.append('.').append(p)
        return out.toString()
    }
}
