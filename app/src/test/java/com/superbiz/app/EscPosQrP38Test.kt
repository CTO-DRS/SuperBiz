package com.superbiz.app

import com.superbiz.app.print.EscPos
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات [P38-Z2] — كتلة QR الضريبي في الإيصال الحراري (ESC/POS GS ( k).
 * بايتات أمر qrBlock حرفاً-حرفاً (نمط InventoryReportsP4WallsTest لبايتات
 * ملصق السعر)، وعقد الوجود/الغياب/الموضع في بايتات build الكاملة.
 */
class EscPosQrP38Test {

    private val payload = "ARZCQSBTZWxsZXIODjMxMDEyMjM5MzUwMDAwMwMUMjAyMi0wNC0yNVQxNTozMDowMFoEBjExNS4wMAUFMTUuMDA="

    // ── qrBlock: التركيب الحرفي ──

    @Test
    fun qrBlock_exactBytes_shortPayload() {
        // "ABC" → storeLen = 3 + 3 = 6 → pL=0x06 pH=0x00
        val expected = byteArrayOf(
            // نموذج 2
            0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00,
            // وحدة 6 نقاط
            0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x06,
            // تصحيح خطأ M
            0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31,
            // تخزين البيانات — pL/pH صيغة صغيرة أولاً
            0x1D, 0x28, 0x6B, 0x06, 0x00, 0x31, 0x50, 0x30,
            0x41, 0x42, 0x43, // "ABC"
            // أمر الطباعة
            0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30,
        )
        assertArrayEquals(expected, EscPos.qrBlock("ABC"))
    }

    @Test
    fun qrBlock_littleEndianLength_forLargePayload() {
        // حمولة مرحلة-2 واقعية (~336 حرفاً Base64) — storeLen يتجاوز 255 فتُبنى
        // pL/pH صيغة صغيرة أولاً بلا اقتطاع
        val big = "A".repeat(336)
        val block = EscPos.qrBlock(big)
        val storeLen = 336 + 3 // 339 = 0x0153
        val pL = (storeLen and 0xFF).toByte()      // 0x53
        val pH = ((storeLen shr 8) and 0xFF).toByte() // 0x01
        // pL/pH بعد رأس أمر التخزين — الضبط 25 بايتاً (9 نموذج + 8 وحدة + 8 تصحيح)
        assertEquals(pL, block[28])
        assertEquals(pH, block[29])
        // طول الكتلة الكاملة: 25 ضبط + (8 + 336) تخزين + 8 طباعة
        assertEquals(25 + 8 + 336 + 8, block.size)
    }

    // ── عقد build الكاملة ──

    /** إيصال ASCII خالص (لا مسار العربية) بـ QR اختياري */
    private fun asciiReceipt(qr: String? = null) = EscPos.Receipt(
        businessName = "SuperBiz Cafe",
        title = "SALE INVOICE - INV-12",
        dateText = "25/04/2022 15:30",
        partyName = "Walk-in",
        lines = listOf(EscPos.ItemLine("Coffee", "2", "10.00", "20.00")),
        totals = listOf("SUBTOTAL" to "20.00", "VAT" to "3.00", "TOTAL" to "23.00"),
        statusText = "PAID",
        footer = "Thank you",
        qrPayload = qr
    )

    @Test
    fun build_withoutQr_containsNoGsKCommands() {
        val bytes = EscPos.build(asciiReceipt(null), width = 32, arabicMode = EscPos.MODE_UTF8)
        var i = 0
        while (i + 3 <= bytes.size) {
            assertFalse("GS ( k found in receipt without QR", bytes[i] == 0x1D.toByte() && bytes[i + 1] == 0x28.toByte() && bytes[i + 2] == 0x6B.toByte())
            i++
        }
    }

    @Test
    fun build_withQr_embedsQrBlockOnce_beforeCut() {
        val bytes = EscPos.build(asciiReceipt(payload), width = 32, arabicMode = EscPos.MODE_UTF8)
        val qr = EscPos.qrBlock(payload)
        // الكتلة موجودة مرة واحدة بالضبط
        val idx = indexOf(bytes, qr)
        assertTrue(idx >= 0)
        assertEquals(idx, lastIndexOf(bytes, qr))
        // قبل أمر القص (0x1D 0x56 0x42 0x00)
        val cut = indexOf(bytes, byteArrayOf(0x1D, 0x56, 0x42, 0x00))
        assertTrue("QR must precede the cut", idx + qr.size < cut)
        // وسط الورق: C_CENTER (0x1B 0x61 0x01) يسبق الكتلة مباشرة
        assertArrayEquals(byteArrayOf(0x1B, 0x61, 0x01), bytes.sliceArray(idx - 3 until idx))
    }

    @Test
    fun build_withQr_afterStatus_beforeFooter() {
        val bytes = EscPos.build(asciiReceipt(payload), width = 32, arabicMode = EscPos.MODE_UTF8)
        val qrIdx = indexOf(bytes, EscPos.qrBlock(payload))
        val statusIdx = indexOf(bytes, "PAID".toByteArray(Charsets.US_ASCII))
        val footerIdx = indexOf(bytes, "Thank you".toByteArray(Charsets.US_ASCII))
        assertTrue(statusIdx in 0 until qrIdx)
        assertTrue(qrIdx < footerIdx)
    }

    @Test
    fun build_qrIdentical_acrossWidthsAndModes() {
        val b32 = EscPos.build(asciiReceipt(payload), width = 32, arabicMode = EscPos.MODE_CP1256)
        val b48 = EscPos.build(asciiReceipt(payload), width = 48, arabicMode = EscPos.MODE_UTF8)
        val qr = EscPos.qrBlock(payload)
        // الكتلة نفسها موجودة كاملة في كلا البنيين — مستقلة عن العرض والترميز
        val i32 = indexOf(b32, qr)
        val i48 = indexOf(b48, qr)
        assertTrue(i32 > 0)
        assertTrue(i48 > 0)
        assertArrayEquals(qr, b32.sliceArray(i32 until i32 + qr.size))
        assertArrayEquals(qr, b48.sliceArray(i48 until i48 + qr.size))
    }

    // ── أدوات ──
    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        for (i in 0..hay.size - needle.size) {
            var ok = true
            for (j in needle.indices) if (hay[i + j] != needle[j]) { ok = false; break }
            if (ok) return i
        }
        return -1
    }

    private fun lastIndexOf(hay: ByteArray, needle: ByteArray): Int {
        for (i in hay.size - needle.size downTo 0) {
            var ok = true
            for (j in needle.indices) if (hay[i + j] != needle[j]) { ok = false; break }
            if (ok) return i
        }
        return -1
    }
}
