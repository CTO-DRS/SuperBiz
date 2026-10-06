package com.superbiz.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [P43-D1] جولة 4 — عقد محرك الدفتر التفصيلي (تصدير عميق).
 *
 * العقد المجرَّب:
 *  - الفاتورة الواعية بالسطر تنزل **بنداً بنداً** بالنسبة الفعالة وضريبة
 *    LineTaxP41 نفسها (نفس دالة الحفظ والتقرير — صندوق رياضيات واحد).
 *  - الفاتورة التاريخية (لا بند صريح فيها) تنزل **صفاً واحداً مجمّعاً** من
 *    رأسها: صافي subtotal − discount وضريبة taxAmount المحجوزة — لا إعادة
 *    اشتقاق (عقد R14-F19/P41-L2 نفسه).
 *  - الملخص جمع تام بلا تقريب — وترتيب الصفوف مستقر (ترتيب الإدخال).
 *  - بلا بنود إطلاقاً (خريطة فارغة) ⇒ صفوف مجمعة من الرؤوس (المسار التاريخي).
 */
class DeepExportP43Test {

    private val inv = DeepExportP43.InvView(
        id = 1L, number = "INV-1", party = "عميل الدفتر", dateMs = 1_700_000_000_000L,
        taxRate = 15.0, subtotalP = 35_000L, discountP = 0L, taxAmountP = 3_550L,
    )

    private val items = listOf(
        // بند صريح 5.5%: 10000 × 5.5% = 550
        DeepExportP43.ItemView(1L, "بند 5.5", 1.0, 10_000L, 0L, 10_000L, LineTaxP41.KIND_STANDARD, 5.5),
        // بند وارث: 20000 × 15% = 3000
        DeepExportP43.ItemView(1L, "بند وارث", 2.0, 10_000L, 0L, 20_000L, LineTaxP41.KIND_STANDARD, LineTaxP41.RATE_INHERIT),
        // بند صفرية: 0
        DeepExportP43.ItemView(1L, "صفرية", 1.0, 3_000L, 0L, 3_000L, LineTaxP41.KIND_ZERO, LineTaxP41.RATE_INHERIT),
        // بند معفاة: 0
        DeepExportP43.ItemView(1L, "معفاة", 1.0, 2_000L, 0L, 2_000L, LineTaxP41.KIND_EXEMPT, LineTaxP41.RATE_INHERIT),
    )

    @Test fun lineAwareInvoice_emitsOneRowPerItem_withEngineFigures() {
        val rows = DeepExportP43.registerRows(listOf(inv), mapOf(1L to items))
        assertEquals("أربعة بنود ⇒ أربعة صفوف", 4, rows.size)
        assertEquals("الصريح بنسبته المعلنة", 5.5, rows[0].effectiveRate, 0.0)
        assertEquals(550L, rows[0].lineVatP)
        assertEquals("الوارث بنسبة الرأس", 15.0, rows[1].effectiveRate, 0.0)
        assertEquals(3_000L, rows[1].lineVatP)
        assertEquals("الصفرية 0 بلا استثناء", 0.0, rows[2].effectiveRate, 0.0)
        assertEquals("المعفاة 0 بلا استثناء", 0.0, rows[3].effectiveRate, 0.0)
    }

    @Test fun rowsCarryInvoiceIdentity_andStableItemOrder() {
        val rows = DeepExportP43.registerRows(listOf(inv), mapOf(1L to items))
        rows.forEach {
            assertEquals("INV-1", it.invoiceNo)
            assertEquals("عميل الدفتر", it.party)
            assertEquals(inv.dateMs, it.dateMs)
        }
        assertEquals("ترتيب البنود كما وردت من القارئ", "بند 5.5", rows[0].desc)
        assertEquals("معفاة", rows[3].desc)
    }

    @Test fun legacyInvoice_singleAggregatedHeadRow_noReDerivation() {
        val legacy = inv.copy(number = "INV-L", subtotalP = 10_000L, taxAmountP = 1_500L)
        val rows = DeepExportP43.registerRows(
            listOf(legacy), mapOf(1L to listOf(DeepExportP43.ItemView(1L, "تاريخي", 1.0, 10_000L, 0L, 10_000L, 0, LineTaxP41.RATE_INHERIT)))
        )
        assertEquals("صف واحد مجمع", 1, rows.size)
        assertEquals("لا وصف بند للصف المجمع", "", rows[0].desc)
        assertEquals(10_000L, rows[0].lineNetP)
        assertEquals("ضريبة الرأس المحجوزة كما هي", 1_500L, rows[0].lineVatP)
        assertEquals(15.0, rows[0].effectiveRate, 0.0)
    }

    @Test fun lineAwareSum_equalsHeadBooking_noDrift() {
        val rows = DeepExportP43.registerRows(listOf(inv), mapOf(1L to items))
        val total = DeepExportP43.summarize(rows)
        assertEquals("مجموع ضرائب الأسطر = المحجوز في الرأس (نفس دالة المحرك)",
            3_550L, total.vatP)
        assertEquals("الصافي = مجموع صوافي البنود", 35_000L, total.netP)
        assertEquals(4, total.rows)
    }

    @Test fun emptyItemsMap_fallsBackToHeadPath_forEveryInvoice() {
        val rows = DeepExportP43.registerRows(listOf(inv), emptyMap())
        assertEquals(1, rows.size)
        assertEquals(35_000L, rows[0].lineNetP)
        assertEquals(3_550L, rows[0].lineVatP)
    }

    @Test fun summarize_emptyRegister_isAllZero() {
        val t = DeepExportP43.summarize(emptyList())
        assertEquals(0, t.rows)
        assertEquals(0L, t.netP)
        assertEquals(0L, t.vatP)
    }
}
