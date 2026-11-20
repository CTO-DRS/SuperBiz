package com.superbiz.app.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * [F1 - ADR-005] اختبارات محرك تحليل الفواتير من نص OCR النقي —
 * عقد الوحدة: نص خام من ML Kit → مسودة فاتورة مهيكلة بمال قروش Long
 * (نفس عقد P33-P8: صفر Double في أي مسار مالي، التحويل عبر Money.parseToPiasters حصراً).
 * الحدود الصلبة مختبرة صراحة: طول النص، عدد البنود، سقف السعر والكمية، الحماية من القيم السالبة.
 */
class InvoiceOcrParserTest {

    // ── الناقل الذهبي: إيصال عربي كامل (المرجع الحرفي للموجة — أي تغيير في العقد يكسره) ──
    private val goldenArabic = """
        مطعم الأصيل
        الرياض - طريق الملك فهد
        التاريخ: 12/05/2026
        -----------------------------
        شاورما دجاج   2 × 12.50   25.00
        عصير برتقال   1 × 6.00    6.00
        -----------------------------
        المجموع الفرعي   31.00
        ضريبة القيمة المضافة 15%   4.65
        الإجمالي المستحق   35.65
    """.trimIndent()

    private fun dateMillis(year: Int, month: Int, day: Int): Long {
        val c = GregorianCalendar.getInstance().apply {
            set(Calendar.YEAR, year); set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day); set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun dayOf(millis: Long): Triple<Int, Int, Int> {
        val c = GregorianCalendar.getInstance().apply { timeInMillis = millis }
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `golden arabic receipt - full structure`() {
        val d = OcrInvoiceParser.parse(goldenArabic)
        assertEquals("مطعم الأصيل", d.merchant)
        assertEquals(Triple(2026, 5, 12), dayOf(d.dateMillis!!))
        assertEquals(2, d.lines.size)
        // البند الأول: الاسم/الكمية/السعر قروش/السطر قروش — مال Long صرف
        assertEquals("شاورما دجاج", d.lines[0].desc)
        assertEquals(2.0, d.lines[0].qty, 1e-9)
        assertEquals(1250L, d.lines[0].unitPriceQirush)
        assertEquals(2500L, d.lines[0].lineTotalQirush)
        assertEquals(600L, d.lines[1].unitPriceQirush)
        assertEquals(600L, d.lines[1].lineTotalQirush)
        // المجاميع قروش: 31.00 → 3100 و4.65 → 465 و35.65 → 3565
        assertEquals(3100L, d.subtotalQirush)
        assertEquals(15.0, d.vatRate!!, 1e-9)
        assertEquals(465L, d.vatQirush)
        assertEquals(3565L, d.totalQirush)
        assertTrue("الناقل الذهبي يجب أن يكون متسقًا", d.totalsConsistent)
        assertTrue("ثقة عالية على الناقل الذهبي: ${d.confidence}", d.confidence >= 0.85)
        assertEquals(3100L, d.lines.sumOf { it.lineTotalQirush })
    }

    @Test
    fun `empty text yields zero confidence with warning`() {
        val d = OcrInvoiceParser.parse("   \n  ")
        assertTrue(d.lines.isEmpty())
        assertNull(d.merchant)
        assertNull(d.totalQirush)
        assertEquals(0.0, d.confidence, 1e-9)
        assertTrue(d.warnings.isNotEmpty())
    }

    @Test
    fun `garbage text yields near-zero confidence`() {
        val d = OcrInvoiceParser.parse("%%% --- ###\n=== --- ===")
        assertTrue(d.lines.isEmpty())
        assertTrue("الفاقد بلا هوية ولا مضمون = ثقة شبه صفرية: ${d.confidence}", d.confidence <= 0.10)
    }

    @Test
    fun `merchant-only gibberish capped weak without financial content`() {
        val d = OcrInvoiceParser.parse("asdkjh asdjhaks asdkjha")
        assertTrue(d.lines.isEmpty())
        assertTrue("بلا أي مضمون مالي = سقف الثقة 0.30: ${d.confidence}", d.confidence <= 0.30)
    }

    @Test
    fun `oversized input is truncated without exception and lines capped`() {
        val raw = "بند تجربة 1 × 5.00\n".repeat(3000)
        val d = OcrInvoiceParser.parse(raw)
        assertTrue("سقف البنود مفروض: ${d.lines.size}", d.lines.size == OcrInvoiceParser.MAX_LINES)
        assertTrue(d.warnings.any { it.contains("الحد") })
    }

    @Test
    fun `arabic-indic digits normalized to same result`() {
        val d = OcrInvoiceParser.parse("مطعم تجريبي\nمحتوى ٢ × ١٢٫٥٠")
        assertEquals(1, d.lines.size)
        assertEquals(2.0, d.lines[0].qty, 1e-9)
        assertEquals(1250L, d.lines[0].unitPriceQirush)
        assertEquals(2500L, d.lines[0].lineTotalQirush)
    }

    @Test
    fun `qty times price without line total`() {
        val d = OcrInvoiceParser.parse("متجر الخير\nحليب المراعي 2 × 7.25")
        assertEquals(1, d.lines.size)
        assertEquals(1450L, d.lines[0].lineTotalQirush)
    }

    @Test
    fun `name-price lines only when explicitly enabled`() {
        val text = "متجر الخير\nشاورما دجاج 12.50\nعصير برتقال 6.00"
        val off = OcrInvoiceParser.parse(text)
        assertTrue("الوضع الافتراضي يرفض السطر الثنائي الغامض", off.lines.isEmpty())
        val on = OcrInvoiceParser.parse(text, allowNamePriceLines = true)
        assertEquals(2, on.lines.size)
        assertEquals(1.0, on.lines[0].qty, 1e-9)
        assertEquals(1250L, on.lines[0].unitPriceQirush)
        assertTrue(on.warnings.isNotEmpty())
    }

    @Test
    fun `price beyond cap is rejected with warning`() {
        val d = OcrInvoiceParser.parse("متجر الخير\nبند غالي 2 × 999999999.00")
        assertTrue(d.lines.isEmpty())
        assertTrue(d.warnings.any { it.contains("النطاق") })
    }

    @Test
    fun `quantity beyond cap is rejected with warning`() {
        val d = OcrInvoiceParser.parse("متجر الخير\nبند كميته هائلة 99999 × 5.00")
        assertTrue(d.lines.isEmpty())
        assertTrue(d.warnings.any { it.contains("النطاق") })
    }

    @Test
    fun `thousands separator parsed through canonical money path`() {
        val d = OcrInvoiceParser.parse("متجر الخير\nمنتج غالي 1 × 1,234.56   1,234.56")
        assertEquals(1, d.lines.size)
        assertEquals(123456L, d.lines[0].unitPriceQirush)
        assertEquals(123456L, d.lines[0].lineTotalQirush)
    }

    @Test
    fun `vat rate detected from percent near tax keyword`() {
        val d = OcrInvoiceParser.parse(
            "متجر الخير\nبند 1 × 100.00   100.00\nالضريبة 15%   15.00\nالإجمالي 115.00"
        )
        assertEquals(15.0, d.vatRate!!, 1e-9)
        assertEquals(1500L, d.vatQirush)
        assertEquals(11500L, d.totalQirush)
        assertTrue(d.totalsConsistent)
    }

    @Test
    fun `subtotal mismatch with lines flagged inconsistent`() {
        val d = OcrInvoiceParser.parse(
            "متجر الخير\nبند 1 × 100.00   100.00\nالمجموع الفرعي 90.00\nالإجمالي 90.00"
        )
        assertEquals(9000L, d.subtotalQirush)
        assertFalse(d.totalsConsistent)
        assertTrue(d.warnings.any { it.contains("المجموع") })
    }

    @Test
    fun `total mismatch with subtotal plus vat flagged inconsistent`() {
        val d = OcrInvoiceParser.parse(
            "متجر الخير\nبند 1 × 100.00   100.00\nالمجموع الفرعي 100.00\nضريبة القيمة المضافة 15%   15.00\nالإجمالي 140.65"
        )
        assertFalse(d.totalsConsistent)
        assertTrue(d.warnings.any { it.contains("الإجمالي") })
    }

    @Test
    fun `vat amount not matching rate flagged inconsistent`() {
        val d = OcrInvoiceParser.parse(
            "متجر الخير\nبند 1 × 100.00   100.00\nالمجموع الفرعي 100.00\nضريبة القيمة المضافة 15%   1.00\nالإجمالي 101.00"
        )
        assertEquals(100L, d.vatQirush)
        assertFalse(d.totalsConsistent)
        assertTrue(d.warnings.any { it.contains("الضريبة") })
    }

    @Test
    fun `keyword-only receipt parses totals without items`() {
        val d = OcrInvoiceParser.parse(
            "فاتورة ضريبية\nالمجموع الفرعي 100.00\nضريبة القيمة المضافة 15%   15.00\nالإجمالي 115.00"
        )
        assertTrue(d.lines.isEmpty())
        assertEquals(11500L, d.totalQirush)
        assertEquals(10000L, d.subtotalQirush)
        assertEquals(1500L, d.vatQirush)
        assertTrue(d.totalsConsistent)
    }

    @Test
    fun `merchant skips invoice keyword and date lines`() {
        val d = OcrInvoiceParser.parse(
            "فاتورة ضريبية\nالتاريخ: 2026-05-12\nمطعم الأصيل\nبند 1 × 5.00"
        )
        assertEquals("مطعم الأصيل", d.merchant)
        assertEquals(Triple(2026, 5, 12), dayOf(d.dateMillis!!))
    }

    @Test
    fun `iso and dash date formats parse to same day`() {
        val expected = dayOf(dateMillis(2026, 5, 12))
        listOf("2026-05-12", "12-05-2026", "12/05/2026", "12.05.2026").forEach { stamp ->
            val d = OcrInvoiceParser.parse("متجر\nالتاريخ: $stamp\nبند 1 × 5.00")
            assertEquals("صيغة $stamp", expected, dayOf(d.dateMillis!!))
        }
    }

    @Test
    fun `politeness lines never become items`() {
        val d = OcrInvoiceParser.parse(
            "متجر الخير\nشاورما دجاج 2 × 12.50   25.00\nشكرا لزيارتكم\nنتشرف بخدمتكم دائمًا"
        )
        assertEquals(1, d.lines.size)
    }

    @Test
    fun `english receipt golden`() {
        val d = OcrInvoiceParser.parse(
            "SUPER MARKET\nDate: 12/05/2026\nCOFFEE 2 x 10.00   20.00\nTEA 1 x 4.00    4.00\nSubtotal 24.00\nVAT 15%   3.60\nTOTAL 27.60"
        )
        assertEquals("SUPER MARKET", d.merchant)
        assertEquals(2, d.lines.size)
        assertEquals(2400L, d.subtotalQirush)
        assertEquals(360L, d.vatQirush)
        assertEquals(2760L, d.totalQirush)
        assertTrue(d.totalsConsistent)
        assertTrue(d.confidence >= 0.85)
    }
}
