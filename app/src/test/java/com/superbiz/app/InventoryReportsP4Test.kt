package com.superbiz.app

import com.superbiz.app.data.db.Product
import com.superbiz.app.domain.CategoryScope
import com.superbiz.app.domain.NeverSold
import com.superbiz.app.domain.PeriodCompare
import com.superbiz.app.domain.PeriodStats
import com.superbiz.app.domain.PriceLabel
import com.superbiz.app.domain.ProfitRow
import com.superbiz.app.domain.ReorderApply
import com.superbiz.app.domain.ReportSummaryText
import com.superbiz.app.domain.ShoppingList
import com.superbiz.app.export.ExportSheet
import com.superbiz.app.export.XlsxSheets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * : اختبارات موجة المخزون والتقارير —
 * بنّاء ملصق السعر ESC/POS، نص قائمة التسوّج، كمية الشراء المقترحة،
 * حسابات مقارنة الفترات، ملخص التقرير، «لم تُبع قط»، ترشيح التصنيف،
 * وكاتب XLSX متعدد الأوراق.
*/
class InventoryReportsP4Test {

    // ══ 1) وظيفة 12 — بنّاء ملصق السعر ══

    @Test
    fun priceLabel_previewContainsNamePriceBarcode_withinWidth() {
        val preview = PriceLabel.textPreview("عصير برتقال طازج طويل الاسم", "12.50", "2000000000017", width = 32)
        val lines = preview.lines().filter { it.isNotBlank() && !it.startsWith("---") }
        assertTrue(lines.any { it.contains("عصير برتقال") })
        assertTrue(lines.contains("12.50"))
        assertTrue(lines.contains("2000000000017"))
        // كل سطر مقيّد بعرض الورق 32 حرفاً
        assertTrue(preview.lines().all { it.length <= 32 })
        // اسم أطول من العرض يُقتطع صادقاً
        val fitted = PriceLabel.textPreview("اسم منتج طويل جداً يتجاوز عرض الورق المحدد للطباعة", "5", "", width = 32)
        assertTrue(fitted.lines().all { it.length <= 32 })
    }

    @Test
    fun priceLabel_bytes_startInit_code128Marker_barcodeAndCut() {
        val bytes = PriceLabel.buildBytes("عصير", "12.50", "2000000000017", width = 32)
        // يبدأ بتهيئة ESC @
        assertEquals(0x1B, bytes[0].toInt() and 0xFF)
        assertEquals(0x40, bytes[1].toInt() and 0xFF)
        val s = String(bytes, Charsets.ISO_8859_1)
        // أمر الباركود CODE128: GS k 73 ثم بيانات {B + القيمة
        val gsIdx = s.indexOf("\u001D\u006B\u0049")
        assertTrue(gsIdx > 0)
        assertTrue(s.contains("{B2000000000017"))
        // ينتهي بأمر القص الجزئي GS V B 0
        assertTrue(s.lastIndexOf("\u001DV\u0042\u0000") > gsIdx)
        // يحوي اسم المنتج والسعر (CP1256 للعربية — نتحقق من وجود السعر الصريح)
        assertTrue(s.contains("12.50"))
    }

    @Test
    fun priceLabel_bytes_withoutBarcode_skipBarcodeBlock() {
        val bytes = PriceLabel.buildBytes("خدمة", "30", "", width = 32)
        val s = String(bytes, Charsets.ISO_8859_1)
        assertTrue(!s.contains("\u001D\u006B\u0049"))      // لا أمر باركود
        assertTrue(!s.contains("{B"))                       // لا بيانات CODE128
        assertTrue(s.contains("30"))                        // السعر موجود
        assertEquals(0x1B, bytes[0].toInt() and 0xFF)       // التهيئة كما هي
    }

    // ══ 2) وظيفة 15 — بنّاء نص قائمة التسوّج ══

    @Test
    fun shoppingList_build_containsRowsAndCount_emptyGivesEmptyText() {
        val text = ShoppingList.build(
            listOf(
                ShoppingList.Row("عصير برتقال", "قطعة", 2.0, 10.0),
                ShoppingList.Row("سكر ناعم", "كيس", 0.5, 5.0)
            )
        )
        assertTrue(text.contains("عصير برتقال"))
        assertTrue(text.contains("سكر ناعم"))
        assertTrue(text.contains("10"))                    // المقترح شراء
        assertTrue(text.contains("عدد الأصناف: 2"))
        // قائمة فارغة → نص فارغ (الزر يُخفى في الواجهة)
        assertEquals("", ShoppingList.build(emptyList()))
    }

    // ══ 3) وظيفة 14 — كمية الشراء المقترحة بحد أدنى 1 ══

    @Test
    fun reorderApply_purchaseQty_rejectsInvalid_andAppliesMinimum() {
        assertNull(ReorderApply.purchaseQty(0.0))
        assertNull(ReorderApply.purchaseQty(-3.0))
        assertNull(ReorderApply.purchaseQty(Double.NaN))
        assertNull(ReorderApply.purchaseQty(Double.POSITIVE_INFINITY))
        // الحد الأدنى 1: الاقتراح الجزئي يُرفع إلى 1
        assertEquals(1.0, ReorderApply.purchaseQty(0.3)!!, 1e-9)
        // الاقتراح الصحيح يمر كما هو
        assertEquals(12.5, ReorderApply.purchaseQty(12.5)!!, 1e-9)
    }

    // ══ 4) وظيفة 20 — منتجات بلا بيع إطلاقاً ══

    @Test
    fun neverSold_filter_keepsProductsNeverInvoiced() {
        val products = listOf(
            Product(id = 1, name = "مُباع"),
            Product(id = 2, name = "غير مباع"),
            Product(id = 3, name = "جديد")
        )
        val out = NeverSold.filter(products, soldProductIds = setOf(1L, 99L))
        assertEquals(listOf(2L, 3L), out.map { it.id })
        // لا مبيعات قط → كل المنتجات
        assertEquals(3, NeverSold.filter(products, emptySet()).size)
        // الكل بيع → قائمة فارغة صادقة
        assertTrue(NeverSold.filter(products, setOf(1L, 2L, 3L)).isEmpty())
    }

    // ══ 5) وظيفة 16 — حسابات مقارنة الفترات ══

    @Test
    fun periodCompare_ratioChange_andDirection() {
        // صاعد ونازل
        assertEquals(25.0, PeriodCompare.ratioChange(125.0, 100.0)!!, 1e-9)
        assertEquals(-50.0, PeriodCompare.ratioChange(50.0, 100.0)!!, 1e-9)
        assertEquals(1, PeriodCompare.direction(120.0, 100.0))
        assertEquals(-1, PeriodCompare.direction(80.0, 100.0))
        assertEquals(0, PeriodCompare.direction(100.0, 100.0))
        // الثنائي الصفري ثابت حقيقي
        assertEquals(0.0, PeriodCompare.ratioChange(0.0, 0.0)!!, 1e-9)
        // سابق صفر وحالي موجب → لا نسبة (القسمة على صفر الصادقة)
        assertNull(PeriodCompare.ratioChange(100.0, 0.0))
        // سابق موجب وحالي صفر → نسبة -100%
        assertEquals(-100.0, PeriodCompare.ratioChange(0.0, 100.0)!!, 1e-9)
    }

    @Test
    fun periodCompare_profitWithoutCostBasis_isNotMeasurable() {
        val m = PeriodCompare.profitMetric(null, null)
        assertNull(m.ratio)
        assertEquals(0, m.direction)
        // أساس في إحدى الفترتين فقط → يُحسب بمعامل الغائب صفراً
        val m2 = PeriodCompare.profitMetric(50.0, null)
        assertNull(m2.ratio)          // سابق بلا أساس → لا نسبة
        assertEquals(1, m2.direction) // لكن الاتجاه صاعد صادق
    }

    @Test
    fun periodCompare_compare_fourMetrics_andHonestHiding() {
        val cur = PeriodStats(sales = 1000.0, profit = 200.0, invoiceCount = 10)   // متوسط 100
        val prev = PeriodStats(sales = 800.0, profit = 300.0, invoiceCount = 9)    // متوسط ≈88.9
        val rows = PeriodCompare.compare(cur, prev)
        assertEquals(4, rows.size)
        assertTrue(rows[0].direction > 0)   // مبيعات صاعدة
        assertTrue(rows[1].direction < 0)   // ربح نازل
        assertTrue(rows[2].direction > 0)   // عدد فواتير صاعد
        assertTrue(rows[3].direction > 0)   // متوسط الفاتورة صاعد
        assertEquals(100.0, cur.avgInvoice!!, 1e-9)
        // الإخفاء الصادق: لا فواتير في الشهرين → لا بطاقة
        assertTrue(!PeriodCompare.hasData(PeriodStats(0.0, null, 0), PeriodStats(0.0, null, 0)))
        assertTrue(PeriodCompare.hasData(cur, prev))
    }

    // ══ 6) وظيفة 18 — بنّاء ملخص التقرير ══

    @Test
    fun reportSummaryText_containsAllSections() {
        val text = ReportSummaryText.build(
            sales = 5000.0, expenses = 1200.0, profit = 3800.0,
            debts = 750.0, periodLabel = "آخر 30 يوماً", currency = "SAR"
        )
        assertTrue(text.contains("آخر 30 يوماً"))
        assertTrue(text.contains("المبيعات"))
        assertTrue(text.contains("المصروفات"))
        assertTrue(text.contains("صافي الربح"))
        assertTrue(text.contains("ذمم العملاء المفتوحة"))
        assertTrue(text.contains("5,000"))
        assertTrue(text.contains("SAR"))
    }

    // ══ 7) وظيفة 19 — ترشيح التصنيف ══

    @Test
    fun categoryScope_selectFiltersByProductIds_nullProductExcluded() {
        val items = listOf(
            CategoryScope.ItemRef(productId = 1L, qty = 2.0, unitPrice = 10.0, lineTotal = 20.0),
            CategoryScope.ItemRef(productId = 2L, qty = 1.0, unitPrice = 5.0, lineTotal = 5.0),
            CategoryScope.ItemRef(productId = null, qty = 1.0, unitPrice = 30.0, lineTotal = 30.0) // بيع حر
        )
        val selected = CategoryScope.select(setOf(1L), items)
        assertEquals(1, selected.size)
        assertEquals(20.0, selected.sumOf { it.lineTotal }, 1e-9)
        // لا تصنيف (مجموعة فارغة) → لا بنود
        assertTrue(CategoryScope.select(emptySet(), items).isEmpty())
    }

    @Test
    fun categoryScope_aggregate_profitOnlyFromKnownCosts_elseNull() {
        val items = listOf(
            CategoryScope.ItemRef(productId = 1L, qty = 2.0, unitPrice = 10.0, lineTotal = 20.0), // تكلفة 4 → ربح 12
            CategoryScope.ItemRef(productId = 2L, qty = 1.0, unitPrice = 5.0, lineTotal = 5.0)    // بلا تكلفة → يُستثنى من الربح
        )
        val stats = CategoryScope.aggregate(items, invoiceCount = 2, costOf = { pid -> if (pid == 1L) 4.0 else null })
        assertEquals(25.0, stats.sales, 1e-9)
        assertEquals(12.0, stats.profit!!, 1e-9)
        assertEquals(2, stats.invoiceCount)
        assertEquals(12.5, stats.avgInvoice!!, 1e-9)
        // لا تكلفة معروفة إطلاقاً → الربح null (لا أرقام مزيّفة)
        val noCost = CategoryScope.aggregate(items, invoiceCount = 2, costOf = { null })
        assertNull(noCost.profit)
    }

    @Test
    fun categoryScope_aggregate_profitUsesLineTotalNotUnitPrice() {
        // [P6-M29 إصلاح] خصم السطر يُحترم: 2×10 مع خصم → lineTotal=18، تكلفة 4/وحدة
        val items = listOf(
            CategoryScope.ItemRef(productId = 1L, qty = 2.0, unitPrice = 10.0, lineTotal = 18.0)
        )
        val stats = CategoryScope.aggregate(items, invoiceCount = 1, costOf = { 4.0 })
        assertEquals(18.0, stats.sales, 1e-9)
        // الربح = 18 − (4×2) = 10 — السلوك القديم (unitPrice×qty) كان يعطي 20−8=12 فيضخّم الربح
        assertEquals(10.0, stats.profit!!, 1e-9)
    }

    // ══ 8) بديل الاحتياطي R1 — XLSX متعدد الأوراق ══

    @Test
    fun xlsxSheets_writeTwoSheets_validPackageStructure() {
        val out = ByteArrayOutputStream()
        XlsxSheets.write(
            out,
            listOf(
                ExportSheet("الملخص", listOf("البند", "القيمة"), listOf(listOf("المبيعات", 1000.0))),
                ExportSheet("ميزان المراجعة", listOf("الحساب", "له", "عليه"), listOf(listOf("الصندوق", 500.0, 0.0)))
            )
        )
        val entries = mutableListOf<String>()
        var workbookXml = ""
        var sheet2 = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                entries += e.name
                when (e.name) {
                    "xl/workbook.xml" -> workbookXml = zip.readBytes().toString(Charsets.UTF_8)
                    "xl/worksheets/sheet2.xml" -> sheet2 = zip.readBytes().toString(Charsets.UTF_8)
                }
                e = zip.nextEntry
            }
        }
        assertTrue(entries.contains("[Content_Types].xml"))
        assertTrue(entries.contains("xl/workbook.xml"))
        assertTrue(entries.contains("xl/_rels/workbook.xml.rels"))
        assertTrue(entries.contains("xl/worksheets/sheet1.xml"))
        assertTrue(entries.contains("xl/worksheets/sheet2.xml"))
        // workbook يسجّل الورقتين بأسمائهما
        assertTrue(workbookXml.contains("الملخص"))
        assertTrue(workbookXml.contains("ميزان المراجعة"))
        // الورقة الثانية تحوي رأسها وبياناتها
        assertTrue(sheet2.contains("الصندوق"))
        assertTrue(sheet2.contains("<v>500.0</v>"))
    }

    @Test
    fun xlsxSheets_rejectsWorkbookWithoutValidSheets_andSanitizesNames() {
        // بلا أي ورقة صالحة → رفض صادق بدل ملف تالف
        try {
            XlsxSheets.write(ByteArrayOutputStream(), listOf(ExportSheet("", emptyList(), emptyList())))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* مقبول */ }
        // أسماء الأوراق تُنظَّف من محارف OOXML الممنوعة
        val out = ByteArrayOutputStream()
        XlsxSheets.write(
            out,
            listOf(ExportSheet("ورقة/تجربة:أ", listOf("رأس"), listOf(listOf("قيمة"))))
        )
        var workbookXml = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == "xl/workbook.xml") workbookXml = zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        assertTrue(!workbookXml.contains("/") || !workbookXml.contains("<sheet name=\"ورقة/"))
        assertTrue(workbookXml.contains("ورقة تجربة أ"))
    }

    // ══ 9) صفوف الربحية — ترتيب صادق بلا أساس تكلفة ══

    @Test
    fun profitRow_sortingKeepsNoCostRowsLast() {
        val rows = listOf(
            ProfitRow(1, "بلا تكلفة", 500.0, margin = null),
            ProfitRow(2, "هامش منخفض", 300.0, margin = 30.0),
            ProfitRow(3, "هامش عالٍ", 200.0, margin = 120.0)
        )
        val sorted = rows.sortedWith(compareByDescending<ProfitRow> { it.margin ?: Double.NEGATIVE_INFINITY })
        assertEquals(3L, sorted[0].productId)
        assertEquals(2L, sorted[1].productId)
        assertEquals(1L, sorted[2].productId)
        assertNull(sorted[2].margin)
    }
}
