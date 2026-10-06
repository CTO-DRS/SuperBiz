package com.superbiz.app

import com.superbiz.app.domain.DeepExportP43
import com.superbiz.app.domain.LineTaxP41
import com.superbiz.app.export.DeepWorkbookP44
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P44-K2] جولة 5 — عقد مصنف الدفتر التفصيلي متعدد الأوراق (نقي بلا Android):
 *
 * • الأوراق الثلاث دائماً بترتيبها الثابت (ملخص الدفتر/المبيعات/المشتريات)
 *   حتى على فترة فارغة — رأس بلا صفوف، هيكل صادق حتمي.
 * • صفو الملخص أرقام البطاقة نفسها: العدد صحيح والصافي والضريبة خلايا رقمية
 *   بالريال عبر المحوّل الممرَّر (Money.fromPiasters في الإنتاج) — لا انحراف
 *   عن RegisterTotals.
 * • الأوراق التفصيلية تُبنى بـ deepRows — أعمدة P43 الإحدى عشرة نفسها: الخلايا
 *   المالية Double والكمية Double والفئة من خريطة سلاسل المنتقي والنسبة تنزل
 *   كما هي ووصف الصف المجمع «—» — فالمصنف والتصدير المنفرد أرقامهم واحدة.
 */
class DeepWorkbookP44Test {

    // محوّل الاختبار: قروش → ريال (نفس دلالة Money.fromPiasters)
    private val money: (Long) -> Double = { it / 100.0 }
    private val dateFmt: (Long) -> String = { "2026-09-15" }

    private val labels = DeepWorkbookP44.Labels(
        summarySheet = "ملخص الدفتر", salesSheet = "المبيعات", purchasesSheet = "المشتريات",
        metricSales = "المبيعات", metricPurchases = "المشتريات",
    )
    private val deepHeader = (1..11).map { "عمود$it" }
    private val summaryHeader = listOf("البند", "العدد", "الصافي", "الضريبة")
    private val catLabels = mapOf(
        LineTaxP41.KIND_STANDARD to "قياسية",
        LineTaxP41.KIND_ZERO to "صفرية",
        LineTaxP41.KIND_EXEMPT to "معفاة",
    )

    // ─── لبنات بيانات — بنفس عقود DeepExportP43 (المبالغ قروش) ───

    private fun inv(id: Long, no: String, party: String, subtotalP: Long, discountP: Long, taxP: Long) =
        DeepExportP43.InvView(
            id = id, number = no, party = party, dateMs = 1_757_990_400_000L,
            taxRate = 15.0, subtotalP = subtotalP, discountP = discountP, taxAmountP = taxP,
        )

    private fun item(invoiceId: Long, desc: String, qty: Double, unitP: Long, lineTotalP: Long) =
        DeepExportP43.ItemView(
            invoiceId = invoiceId, desc = desc, qty = qty, unitPriceP = unitP,
            discountP = 0L, lineTotalP = lineTotalP,
            taxKind = LineTaxP41.KIND_STANDARD, taxRate = 15.0,
        )

    /** فاتورة واعية بالسطر: بندان قياسيان — 1000+2000 قروش صافياً بضريبة 450 */
    private fun awarePair(): DeepExportP43.RegisterTotalsPair {
        val invA = inv(1L, "S-1", "عميل الوعي", 3000L, 0L, 450L)
        val rowsA = DeepExportP43.registerRows(
            listOf(invA),
            mapOf(1L to listOf(item(1L, "صنف أول", 2.0, 500L, 1000L), item(1L, "صنف ثانٍ", 1.0, 2000L, 2000L))),
        )
        // فاتورة تاريخية: صف مجمع من رأسها
        val invB = inv(2L, "S-2", "عميل التاريخ", 2000L, 200L, 270L)
        val rowsB = DeepExportP43.registerRows(listOf(invB), emptyMap())
        val all = rowsA + rowsB
        return DeepExportP43.RegisterTotalsPair(
            sales = all, purchases = emptyList(),
            salesTotals = DeepExportP43.summarize(all),
            purchasesTotals = DeepExportP43.summarize(emptyList()),
        )
    }

    // ─── العقد ───

    @Test
    fun sheets_areAlwaysThreeInFixedOrder() {
        val s = DeepWorkbookP44.sheets(awarePair(), deepHeader, summaryHeader, labels, catLabels, money, dateFmt)
        assertEquals(3, s.size)
        assertEquals("ملخص الدفتر", s[0].name)
        assertEquals("المبيعات", s[1].name)
        assertEquals("المشتريات", s[2].name)
        // والأوراق التفصيلية برأس الأعمدة الإحدى عشرة
        assertEquals(deepHeader, s[1].header)
        assertEquals(deepHeader, s[2].header)
    }

    @Test
    fun emptyPeriod_stillGivesHeaderOnlySheets() {
        val empty = DeepExportP43.RegisterTotalsPair(
            sales = emptyList(), purchases = emptyList(),
            salesTotals = DeepExportP43.summarize(emptyList()),
            purchasesTotals = DeepExportP43.summarize(emptyList()),
        )
        val s = DeepWorkbookP44.sheets(empty, deepHeader, summaryHeader, labels, catLabels, money, dateFmt)
        assertEquals(3, s.size)
        assertTrue(s.all { it.header.isNotEmpty() })
        // الأوراق التفصيلية فارغة فعلاً، والملخص يحمل صفَّي الأصفار الصادقة دائماً
        assertTrue(s[1].rows.isEmpty())
        assertTrue(s[2].rows.isEmpty())
        assertEquals(2, s[0].rows.size)
        // الملخص على الفارغ: أصفار صادقة لا اختراع
        assertEquals(listOf<Any?>("المبيعات", 0, 0.0, 0.0), s[0].rows[0])
        assertEquals(listOf<Any?>("المشتريات", 0, 0.0, 0.0), s[0].rows[1])
    }

    @Test
    fun summaryRows_matchRegisterTotalsExactly() {
        val pair = awarePair()
        val s = DeepWorkbookP44.sheets(pair, deepHeader, summaryHeader, labels, catLabels, money, dateFmt)
        val summary = s[0]
        assertEquals(summaryHeader, summary.header)
        assertEquals(2, summary.rows.size)
        val salesRow = summary.rows[0]
        // العدد = عدد صفوف الدفتر (3: بندان واعيان + صف التاريخية المجمع)
        assertEquals(3, salesRow[1])
        // الصافي والضريبة بالريال: (4800 قروش → 48.0) و(720 → 7.2)
        assertEquals(48.0, salesRow[2] as Double, 1e-9)
        assertEquals(7.2, salesRow[3] as Double, 1e-9)
        // أرقام الملخص = أرقام summarize حرفياً
        assertEquals(pair.salesTotals.rows, salesRow[1] as Int)
        assertEquals(money(pair.salesTotals.netP), salesRow[2] as Double, 1e-9)
        assertEquals(money(pair.salesTotals.vatP), salesRow[3] as Double, 1e-9)
    }

    @Test
    fun detailSheets_haveP43RowCountAndElevenColumns() {
        val pair = awarePair()
        val s = DeepWorkbookP44.sheets(pair, deepHeader, summaryHeader, labels, catLabels, money, dateFmt)
        assertEquals(pair.sales.size, s[1].rows.size) // 3 صفوف بيع
        assertEquals(0, s[2].rows.size)               // لا مشتريات
        assertTrue(s[1].rows.all { it.size == 11 })
    }

    @Test
    fun deepRows_cellTypesAndCategoryMappingAreExact() {
        val pair = awarePair()
        val s = DeepWorkbookP44.sheets(pair, deepHeader, summaryHeader, labels, catLabels, money, dateFmt)
        // صف بند واعٍ: خلايا مالية Double والتاريخ نص والفئة من الخريطة
        val first = s[1].rows[0]
        assertEquals("S-1", first[0])
        assertEquals("2026-09-15", first[1])
        assertEquals("عميل الوعي", first[2])
        assertEquals("صنف أول", first[3])
        assertEquals(2.0, first[4] as Double, 1e-9)          // الكمية
        assertEquals(5.0, first[5] as Double, 1e-9)          // السعر بالريال
        assertEquals(10.0, first[7] as Double, 1e-9)         // الصافي بالريال
        assertEquals("قياسية", first[8])                     // الفئة من سلاسل المنتقي
        assertEquals(15.0, first[9] as Double, 1e-9)         // النسبة الفعالة كما هي
        assertEquals(1.5, first[10] as Double, 1e-9)         // ضريبة البند 150 قروش
        // صف التاريخية المجمع: وصف البند «—» والفئة قياسية بعقد P43
        val aggregated = s[1].rows[2]
        assertEquals("—", aggregated[3])
        assertEquals("قياسية", aggregated[8])
        assertEquals(20.0 - 2.0, aggregated[7] as Double, 1e-9) // (2000−200) قروش → 18.0
        assertEquals(2.7, aggregated[10] as Double, 1e-9)       // ضريبة الرأس المحجوزة
    }

    @Test
    fun unknownTaxKind_mapsToEmptyLabel() {
        val line = DeepExportP43.RegisterLine(
            invoiceId = 9L, invoiceNo = "S-9", party = "طرف", dateMs = 1L,
            desc = "بند", qty = 1.0, unitPriceP = 100L, discountP = 0L, lineNetP = 100L,
            taxKind = 99, effectiveRate = 15.0, lineVatP = 15L,
        )
        val rows = DeepWorkbookP44.deepRows(listOf(line), catLabels, money, dateFmt)
        assertEquals("", rows[0][8]) // فئة مجهولة ⇒ خلية فارغة لا اختراع
    }

    @Test
    fun purchasesSheet_mirrorsItsOwnLines() {
        // ورقة المشتريات لا تنزلق إلى صفوف البيع (مرآة مستقلة بعقد P43)
        val pInv = inv(5L, "P-1", "مورد", 1000L, 0L, 150L)
        val pRows = DeepExportP43.registerRows(
            listOf(pInv),
            mapOf(5L to listOf(item(5L, "بند مشتريات", 3.0, 333L, 999L))),
        )
        val pair = DeepExportP43.RegisterTotalsPair(
            sales = emptyList(), purchases = pRows,
            salesTotals = DeepExportP43.summarize(emptyList()),
            purchasesTotals = DeepExportP43.summarize(pRows),
        )
        val s = DeepWorkbookP44.sheets(pair, deepHeader, summaryHeader, labels, catLabels, money, dateFmt)
        assertEquals(0, s[1].rows.size)
        assertEquals(1, s[2].rows.size)
        assertEquals("مورد", s[2].rows[0][2])
        // وملخص المشتريات أرقامها: صف واحد وصافي 9.99 وضريبة محجوزة 1.5
        assertEquals(1, s[0].rows[1][1])
        assertEquals(9.99, s[0].rows[1][2] as Double, 1e-9)
        assertEquals(1.5, s[0].rows[1][3] as Double, 1e-9)
    }
}
