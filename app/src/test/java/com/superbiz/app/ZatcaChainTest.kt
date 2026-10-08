package com.superbiz.app

import com.superbiz.app.domain.algo.ZatcaChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * اختبارات [Z2-أ V 1.5.0] — سلسلة ICV/PIH:
 * - بذرة أول فاتورة الثابتة الرسمية Base64(SHA256("0"))
 * - التتابع الحرفي للعداد والهاش
 * - معيار قبول الموجة: سلسلة 100 وثيقة بينها 3 دائنة تتحقق كاملة
 *   (ICV تتابع + PIH يربط + توقيع كل وثيقة ECDSA يتحقق)
 * - أي عبث بالبايتات/العداد/الهاش يوقف التحقق عند أول حلقة
 */
class ZatcaChainTest {

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun signerOf(kp: KeyPair) = { data: ByteArray ->
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(kp.private); update(data)
        }.sign()
    }

    private fun pointOf(kp: KeyPair): ByteArray {
        val enc = kp.public.encoded
        return enc.copyOfRange(enc.size - 65, enc.size)
    }

    // ───────── 1) بذرة أول فاتورة ─────────

    @Test
    fun firstPih_matchesOfficialSeed_hashOfZero() {
        // قيمة محسوبة مستقلاً (python): Base64(SHA256("0")) — ثابت المواصفة
        assertEquals("X+zrZv/IbzjZUnhsbWlsecLbwjndTpG0ZynXOif7V+k=", ZatcaChain.firstPih())
    }

    @Test
    fun nextStamp_firstDocument_usesFirstPihAndIcvOne() {
        val next = ZatcaChain.nextStamp(ZatcaChain.EMPTY)
        assertEquals(1L, next.icv)
        assertEquals(ZatcaChain.firstPih(), next.pih)
    }

    @Test
    fun nextStamp_followsState_icvIncrements_pihCarriesLastHash() {
        val state = ZatcaChain.ChainState(lastIcv = 41, lastHash = "HASH-41")
        val next = ZatcaChain.nextStamp(state)
        assertEquals(42L, next.icv)
        assertEquals("HASH-41", next.pih)
    }

    // ───────── 2) سلسلة 100 وثيقة بينها 3 دائنة — معيار القبول ─────────

    @Test
    fun verifyChain_hundredDocumentsWithThreeCreditNotes_allVerified() {
        val kp = newKeyPair()
        val sign = signerOf(kp)
        val point = pointOf(kp)

        val entries = ArrayList<ZatcaChain.ChainEntry>(100)
        var lastHash: String? = null
        var icv = 0L
        val creditAt = setOf(19, 50, 87) // مواضع الوثائق الدائنة (0-based)

        repeat(100) { i ->
            val next = ZatcaChain.nextStamp(ZatcaChain.ChainState(icv, lastHash))
            // وثيقة XML مميزة تماماً لكل إصدار (دائنة أو لا)
            val kind = if (i in creditAt) "CreditNote" else "Invoice"
            val xml = "<$kind><n>${i + 1}</n><icv>${next.icv}</icv><payload>doc-${i}</payload></$kind>"
                .toByteArray(Charsets.UTF_8)
            val hash = ZatcaChain.documentHash(xml)
            val sig = sign(xml)
            entries.add(ZatcaChain.ChainEntry(xml, next.icv, next.pih, sig, point))
            icv = next.icv
            lastHash = hash
        }

        val result = ZatcaChain.verifyChain(entries)
        assertTrue("سلسلة 100 وثيقة يجب أن تتحقق: ${result.reason}", result.ok)
        assertNull(result.firstFailureIndex)
        // العداد وصل 100 بالضبط
        assertEquals(100L, entries.last().icv)
    }

    // ───────── 3) الكشف عن كل نوع عبث ─────────

    private fun buildEntries(n: Int): MutableList<ZatcaChain.ChainEntry> {
        val entries = ArrayList<ZatcaChain.ChainEntry>(n)
        var lastHash: String? = null
        repeat(n) { i ->
            val next = ZatcaChain.nextStamp(ZatcaChain.ChainState(i.toLong(), lastHash))
            val xml = "<Invoice><n>${i + 1}</n></Invoice>".toByteArray(Charsets.UTF_8)
            val hash = ZatcaChain.documentHash(xml)
            entries.add(ZatcaChain.ChainEntry(xml, next.icv, next.pih))
            lastHash = hash
        }
        return entries
    }

    @Test
    fun verifyChain_icvJump_detectedAtExactIndex() {
        val entries = buildEntries(10)
        entries[5] = entries[5].copy(icv = 99L) // قفزة عداد
        val r = ZatcaChain.verifyChain(entries)
        assertFalse(r.ok)
        assertEquals(5, r.firstFailureIndex)
        assertTrue(r.reason!!.contains("ICV"))
    }

    @Test
    fun verifyChain_pihBreak_detectedAtExactIndex() {
        val entries = buildEntries(10)
        entries[3] = entries[3].copy(pih = "TAMPERED")
        val r = ZatcaChain.verifyChain(entries)
        assertFalse(r.ok)
        assertEquals(3, r.firstFailureIndex)
        assertTrue(r.reason!!.contains("PIH"))
    }

    @Test
    fun verifyChain_tamperedXml_detectedBySignature() {
        val kp = newKeyPair()
        val sign = signerOf(kp)
        val point = pointOf(kp)
        val xml1 = "<Invoice><n>1</n></Invoice>".toByteArray(Charsets.UTF_8)
        val xml2 = "<Invoice><n>2</n></Invoice>".toByteArray(Charsets.UTF_8)
        val entries = listOf(
            ZatcaChain.ChainEntry(xml1, 1, ZatcaChain.firstPih(), sign(xml1), point),
            // PIH يحمل هاش xml1 الأصلي، لكن بايتات xml2 معدّة عن ما وُقّع
            ZatcaChain.ChainEntry(
                xml2, 2, ZatcaChain.documentHash(xml1),
                sign(xml2.copyof1ByteFlipped()), point
            ),
        )
        val r = ZatcaChain.verifyChain(entries)
        assertFalse(r.ok)
        assertEquals(1, r.firstFailureIndex)
        assertTrue(r.reason!!.contains("signature"))
    }

    @Test
    fun verifyChain_wrongKeySignature_detected() {
        val kp1 = newKeyPair()
        val kp2 = newKeyPair()
        val xml = "<Invoice><n>1</n></Invoice>".toByteArray(Charsets.UTF_8)
        val wrongSig = signerOf(kp2)(xml)
        val r = ZatcaChain.verifyChain(
            listOf(ZatcaChain.ChainEntry(xml, 1, ZatcaChain.firstPih(), wrongSig, pointOf(kp1)))
        )
        assertFalse(r.ok)
        assertEquals(0, r.firstFailureIndex)
    }

    @Test
    fun verifyChain_emptyChain_ok() {
        assertTrue(ZatcaChain.verifyChain(emptyList()).ok)
    }

    // ───────── 4) هاش الوثيقة مطابق لمسار QR (وسم 6) ─────────

    @Test
    fun documentHash_matchesStampSha256Base64() {
        val xml = "<Invoice>x</Invoice>".toByteArray(Charsets.UTF_8)
        // المصدر الوحيد للهاش هو ZatcaStamp.sha256Base64 — العقد المشترك مع وسم 6
        assertEquals(com.superbiz.app.domain.algo.ZatcaStamp.sha256Base64(xml), ZatcaChain.documentHash(xml))
    }
}

/** عربي مساعد صغير لعبث بايت واحد */
private fun ByteArray.copyof1ByteFlipped(): ByteArray =
    copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
