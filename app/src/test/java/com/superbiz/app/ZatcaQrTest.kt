package com.superbiz.app

import com.superbiz.app.domain.algo.ZatcaQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * اختبارات [P9-9a-ZATCA] — ترميز TLV/Base64 لرمز QR ضريبي (ZATCA مرحلة-1).
 * المتجه الذهبي مُشتق ومُثبَّت بـ python مستقلة (base64.b64encode) بنفس الحقول حرفياً.
 */
class ZatcaQrTest {

    /** 2022-04-25T15:30:00Z يُبنى من تقويم صريح كي يبقى الاختبار ذاتي التوثيق */
    private fun sampleTs(): Long =
        GregorianCalendar(2022, Calendar.APRIL, 25, 15, 30, 0).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.timeInMillis

    @Test
    fun goldenVector_canonicalZatcaSample() {
        // العينة المرجعية المنشورة: Bunnia Company Limited / 310122393500003 / 2022-04-25T15:30:00Z / 115.00 / 15.00
        val expected = "ARZCdW5uaWEgQ29tcGFueSBMaW1pdGVkAg8zMTAxMjIzOTM1MDAwMDMDFDIwMjItMDQtMjVUMTU6MzA6MDBaBAYxMTUuMDAFBTE1LjAw"
        assertEquals(
            expected,
            ZatcaQr.qrPayload("Bunnia Company Limited", "310122393500003", sampleTs(), 115.00, 15.00)
        )
    }

    @Test
    fun segments_exactBytesOfEachTlv() {
        val bytes = ZatcaQr.qrPayloadBytes("شركة النور", "300012345600003", sampleTs(), 115.0, 15.0)
        // tag1 len1 name | tag2 len2 vat | tag3 len3 ts | tag4 len4 total | tag5 len5 vat
        val name = "شركة النور".toByteArray(Charsets.UTF_8)
        val vat = "300012345600003".toByteArray(Charsets.US_ASCII)
        val ts = "2022-04-25T15:30:00Z".toByteArray(Charsets.US_ASCII)
        val total = "115.00".toByteArray(Charsets.US_ASCII)
        val vatAmt = "15.00".toByteArray(Charsets.US_ASCII)
        val expected = byteArrayOf(1, name.size.toByte()) + name +
            byteArrayOf(2, vat.size.toByte()) + vat +
            byteArrayOf(3, ts.size.toByte()) + ts +
            byteArrayOf(4, total.size.toByte()) + total +
            byteArrayOf(5, vatAmt.size.toByte()) + vatAmt
        assertEquals(expected.toList(), bytes.toList())
    }

    @Test
    fun moneyFormatting_twoDecimalsLatinDigits() {
        val bytes = ZatcaQr.qrPayloadBytes("س", "1", sampleTs(), 1000.5, 150.0)
        // الإزاحات: مقطع tag1 = 2+2 بايت ("س" حرفان UTF-8)، tag2 = 2+1، tag3 = 2+20
        val totalStart = (2 + "س".toByteArray(Charsets.UTF_8).size) + (2 + 1) + (2 + 20)
        assertEquals(4, bytes[totalStart].toInt())
        assertEquals(7, bytes[totalStart + 1].toInt())
        assertEquals("1000.50", String(bytes, totalStart + 2, 7, Charsets.US_ASCII))
        val vatStart = totalStart + 2 + 7
        assertEquals(5, bytes[vatStart].toInt())
        assertEquals("150.00", String(bytes, vatStart + 2, 6, Charsets.US_ASCII))
    }

    @Test
    fun emptySellerName_isLegalZeroLengthValue() {
        val bytes = ZatcaQr.qrPayloadBytes("", "310122393500003", sampleTs(), 115.0, 15.0)
        assertEquals(1, bytes[0].toInt())   // tag 1
        assertEquals(0, bytes[1].toInt())   // طول صفري قانوني
        assertEquals(2, bytes[2].toInt())   // يليه tag 2 مباشرة
    }

    @Test
    fun boundary_255ValueBytesAccepted_256Rejected() {
        val name255 = "ا".repeat(255) // حرف عربي واحد = 2 بايت، نستخدم ASCII لضبط البايتات
        val ascii255 = "a".repeat(255)
        // 255 بايت قيمة ASCII = 255 بايت → مقبول
        val bytes = ZatcaQr.tlv(1, ascii255.toByteArray(Charsets.US_ASCII))
        assertEquals(257, bytes.size)
        assertEquals(255.toByte(), bytes[1])
        // قيمة 256 بايت ترفض
        assertThrows(IllegalArgumentException::class.java) {
            ZatcaQr.tlv(1, "a".repeat(256).toByteArray(Charsets.US_ASCII))
        }
        // وسم خارج النطاق يرفض
        assertThrows(IllegalArgumentException::class.java) { ZatcaQr.tlv(0, byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { ZatcaQr.tlv(256, byteArrayOf()) }
    }

    @Test
    fun timestamp_isIso8601UtcSuffix() {
        val bytes = ZatcaQr.qrPayloadBytes("س", "1", sampleTs(), 1.0, 0.0)
        val tsStart = (2 + "س".toByteArray(Charsets.UTF_8).size) + (2 + 1)
        assertEquals(3, bytes[tsStart].toInt())
        val tsLen = bytes[tsStart + 1].toInt()
        assertEquals(20, tsLen)
        val ts = String(bytes, tsStart + 2, tsLen, Charsets.US_ASCII)
        assertEquals("2022-04-25T15:30:00Z", ts)
    }

    @Test
    fun utf8ArabicName_lengthCountsBytesNotChars() {
        // "شركة النور" = 10 محارف لكن بالبايتات: ش ر ك ة + مسافة + ن و ر = 9 حروف عربية ×2 + مسافة = 19 بايت
        val bytes = ZatcaQr.qrPayloadBytes("شركة النور", "1", sampleTs(), 1.0, 0.0)
        assertEquals(1, bytes[0].toInt())
        assertEquals(19, bytes[1].toInt())
        assertEquals("شركة النور", String(bytes, 2, 19, Charsets.UTF_8))
    }
}
