package com.superbiz.app.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F2 - ADR-005 D4/تعبئة المحرر] اختبارات محرك التحويل مسودة OCR → تعبئة محرر الفاتورة.
 *
 * العقد المختبر هنا حرفيًا:
 * - السطر المطابق لكتالوج المنتجات يهبط للمحرر بمعرف المنتج واسمه الكتالوجي الحقيقي —
 *   لا اسم OCR مشتق أبداً حين وُجدت مطابقة فوق حد القرار.
 * - السطر غير المطابق يبقى نصًا حرًا (productId=null) بوصف OCR الأصلي.
 * - أي سطر بكمية أو سعر خارج العقد الصلب (نفس حدود OcrInvoiceParser) يُسقط بإعلان —
 *   لا قيمة مالية زائفة تعبر للمحرر، والعدّ في skippedLines وwarnings مُحصَّل.
 * - المسودة الفارغة ماليًا غير صالحة للتعبئة (usable=false) — لا محرر فارغ يُفتح زائفًا.
 * - النسبة الضريبية تُقبل في 0..100 حصرًا — خارجها تُترك للمحرر قيمته الافتراضية.
 * - اسم الجهة مقصوص لحده، والنوع مُحصَّر على 0/1 (غير ذلك = شراء 1 كاتجاه المسح الطبيعي).
 * - محرك نقي صفر أندرويد — JVM صرف.
 */
class OcrDraftToEditorTest {

    // ── عوانس البناء ──

    private fun line(
        desc: String, qty: Double, price: Long,
        total: Long = Math.round(qty * price.toDouble())
    ) = OcrInvoiceParser.OcrItemLine(desc, qty, price, total)

    private fun draft(
        lines: List<OcrInvoiceParser.OcrItemLine>,
        merchant: String? = "مؤسسة النور للتجارة",
        vatRate: Double? = 15.0,
        consistent: Boolean = true
    ) = OcrInvoiceParser.OcrInvoiceDraft(
        merchant = merchant, dateMillis = 1_700_000_000_000L, lines = lines,
        subtotalQirush = if (lines.isEmpty()) null else lines.sumOf { it.lineTotalQirush },
        vatRate = vatRate,
        vatQirush = if (vatRate == null) null else 1500L,
        totalQirush = if (lines.isEmpty()) null else lines.sumOf { it.lineTotalQirush } + 1500L,
        totalsConsistent = consistent, confidence = 0.9, warnings = emptyList()
    )

    private fun match(id: Long, name: String) = OcrProductMatcher.Match(id, name, 1.0)

    // ── 1) السطر المطابق يهبط بمعرف المنتج واسمه الكتالوجي ──

    @Test
    fun matched_line_maps_product_id_and_catalog_name() {
        val d = draft(listOf(line("تاون 500مل", 2.0, 300L)))
        val p = OcrDraftToEditor.toPrefill(d, listOf(match(7L, "تاون 500 مل بالليمون")))
        assertEquals(1, p.items.size)
        val it = p.items[0]
        assertEquals(7L, it.productId)
        // الاسم الكتالوجي الحقيقي يزحزح نص OCR — حفاظًا على اتساق القوائم والتقارير
        assertEquals("تاون 500 مل بالليمون", it.desc)
        assertEquals(2.0, it.qty, 1e-9)
        assertEquals(300L, it.priceQirush)
        assertTrue(p.usable)
    }

    // ── 2) السطر غير المطابق يبقى نصًا حرًا بوصف OCR ──

    @Test
    fun unmatched_line_keeps_ocr_desc_with_null_product() {
        val d = draft(listOf(line("أقلام جاف أزرق", 5.0, 250L)))
        val p = OcrDraftToEditor.toPrefill(d, listOf(null))
        assertEquals(1, p.items.size)
        val it = p.items[0]
        assertNull(it.productId)
        assertEquals("أقلام جاف أزرق", it.desc)
    }

    // ── 3) قائمة مطابقات أقصر من البنود — دفاع بلا انهيار ──

    @Test
    fun shorter_matches_list_is_defensive_not_crashing() {
        val d = draft(listOf(line("سطر أ", 1.0, 100L), line("سطر ب", 1.0, 200L)))
        val p = OcrDraftToEditor.toPrefill(d, listOf(match(3L, "سطر أ")))
        assertEquals(2, p.items.size)
        assertEquals(3L, p.items[0].productId)
        assertNull(p.items[1].productId)
    }

    // ── 4) كمية فوق السقف الصلب — إسقاط معلن ──

    @Test
    fun line_qty_over_cap_is_dropped_with_warning() {
        val d = draft(listOf(line("بند", OcrDraftToEditor.MAX_QTY + 1.0, 100L)))
        val p = OcrDraftToEditor.toPrefill(d, listOf(null))
        assertFalse(p.usable)
        assertEquals(0, p.items.size)
        assertEquals(1, p.skippedLines)
        assertTrue(p.warnings.isNotEmpty())
    }

    // ── 5) كمية صفرية أو سالبة — إسقاط ──

    @Test
    fun zero_or_negative_qty_is_dropped() {
        val d = draft(listOf(line("بند صفر", 0.0, 100L), line("بند سالب", -2.0, 100L)))
        val p = OcrDraftToEditor.toPrefill(d, listOf(null, null))
        assertFalse(p.usable)
        assertEquals(2, p.skippedLines)
    }

    // ── 6) سعر فوق السقف — إسقاط ──

    @Test
    fun line_price_over_cap_is_dropped() {
        val d = draft(listOf(line("بند", 1.0, OcrDraftToEditor.MAX_LINE_QIRUSH + 1L)))
        val p = OcrDraftToEditor.toPrefill(d, listOf(null))
        assertFalse(p.usable)
        assertEquals(1, p.skippedLines)
    }

    // ── 7) سقف السعر يُقبل عند الحد نفسه — حد ضمني لا صارم زائد ──

    @Test
    fun price_at_cap_exactly_is_kept() {
        val d = draft(listOf(line("بند", 1.0, OcrDraftToEditor.MAX_LINE_QIRUSH)))
        val p = OcrDraftToEditor.toPrefill(d, listOf(null))
        assertTrue(p.usable)
        assertEquals(OcrDraftToEditor.MAX_LINE_QIRUSH, p.items[0].priceQirush)
    }

    // ── 8) النسبة الضريبية الصالحة تُقبل ──

    @Test
    fun valid_vat_rate_prefills_tax_rate() {
        val d = draft(listOf(line("بند", 1.0, 100L)), vatRate = 15.0)
        val p = OcrDraftToEditor.toPrefill(d, listOf(null))
        assertEquals(15.0, p.taxRate!!, 1e-9)
    }

    // ── 9) النسبة خارج 0..100 تُترك للمحرر (null) ──

    @Test
    fun out_of_range_vat_rate_prefers_editor_default() {
        val over = OcrDraftToEditor.toPrefill(draft(listOf(line("بند", 1.0, 100L)), vatRate = 120.0), listOf(null))
        val neg = OcrDraftToEditor.toPrefill(draft(listOf(line("بند", 1.0, 100L)), vatRate = -3.0), listOf(null))
        val absent = OcrDraftToEditor.toPrefill(draft(listOf(line("بند", 1.0, 100L)), vatRate = null), listOf(null))
        assertNull(over.taxRate)
        assertNull(neg.taxRate)
        assertNull(absent.taxRate)
    }

    // ── 10) اسم الجهة مقصوص لحده ──

    @Test
    fun merchant_name_is_capped_to_limit() {
        val long = "ش".repeat(120)
        val p = OcrDraftToEditor.toPrefill(draft(listOf(line("بند", 1.0, 100L)), merchant = long), listOf(null))
        assertEquals(OcrDraftToEditor.MAX_PARTY_CHARS, p.partyName!!.length)
    }

    // ── 11) جهة مفقودة/فارغة → partyName=null (المحرر يبقى بلا طرف حتى يختار المالك) ──

    @Test
    fun missing_or_blank_merchant_gives_null_party_name() {
        val none = OcrDraftToEditor.toPrefill(draft(listOf(line("بند", 1.0, 100L)), merchant = null), listOf(null))
        val blank = OcrDraftToEditor.toPrefill(draft(listOf(line("بند", 1.0, 100L)), merchant = "   "), listOf(null))
        assertNull(none.partyName)
        assertNull(blank.partyName)
    }

    // ── 12) النوع مُحصَّر — غير الصالح يعود لشراء (اتجاه المسح الطبيعي) ──

    @Test
    fun invalid_type_coerced_to_purchase() {
        val p = OcrDraftToEditor.toPrefill(draft(listOf(line("بند", 1.0, 100L))), listOf(null), type = 9)
        assertEquals(1, p.type)
        val sale = OcrDraftToEditor.toPrefill(draft(listOf(line("بند", 1.0, 100L))), listOf(null), type = 0)
        assertEquals(0, sale.type)
        val purchase = OcrDraftToEditor.toPrefill(draft(listOf(line("بند", 1.0, 100L))), listOf(null), type = 1)
        assertEquals(1, purchase.type)
    }

    // ── 13) مسودة واقعية كاملة عبر المحلل الحقيقي → تعبئة متماسكة ──

    @Test
    fun full_realistic_receipt_flows_through_real_parser() {
        val receipt = """
            مؤسسة الخليج للمواد الغذائية
            رقم: 8812    تاريخ: 2026-11-18
            ------
            أرز بسمتي 5كجم  3  22.50  67.50
            زيت دوار الشمس 1.5ل  4  12.00  48.00
            ------
            المجموع الفرعي: 115.50
            ضريبة القيمة المضافة 15%: 17.33
            الإجمالي: 132.83
        """.trimIndent()
        val parsed = OcrInvoiceParser.parse(receipt)
        assertTrue(parsed.lines.isNotEmpty())
        val matches = parsed.lines.map {
            if (it.desc.contains("أرز")) match(11L, "أرز بسمتي 5 كجم") else null
        }
        val p = OcrDraftToEditor.toPrefill(parsed, matches, type = 1)
        assertEquals(1, p.type)
        assertTrue(p.usable)
        assertEquals("مؤسسة الخليج للمواد الغذائية", p.partyName)
        assertEquals(11L, p.items[0].productId)
        assertEquals("أرز بسمتي 5 كجم", p.items[0].desc)
        assertNull(p.items[1].productId)
        // الضريبة القادمة من الإيصال تصل المحرر إن كانت ضمن العقد
        parsed.vatRate?.let { assertEquals(it, p.taxRate!!, 1e-9) }
        // كل الأسعار قروش عبر المسار القانوني — لا كسر لعقد P33-P8
        p.items.forEach { assertTrue(it.priceQirush > 0L && it.priceQirush <= OcrDraftToEditor.MAX_LINE_QIRUSH) }
    }

    // ── 14) مسودة عديمة المحتوى المالي غير صالحة — لا محرر يُفتح على لا شيء ──

    @Test
    fun financially_empty_draft_is_not_usable() {
        val empty = OcrInvoiceParser.OcrInvoiceDraft(
            "جهة بلا أرقام", null, emptyList(),
            null, null, null, null, false, 0.30, emptyList()
        )
        val p = OcrDraftToEditor.toPrefill(empty, emptyList())
        assertFalse(p.usable)
        assertTrue(p.items.isEmpty())
    }
}
