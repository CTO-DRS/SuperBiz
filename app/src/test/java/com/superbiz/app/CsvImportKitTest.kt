package com.superbiz.app

import com.superbiz.app.domain.algo.CsvImportKit
import com.superbiz.app.domain.algo.CsvKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H4-6 V 2.5.0] اختبارات CsvImportKit — الاستيراد النقي (JVM خالص)
 * ═══════════════════════════════════════════════════════════════════════════
 * بوابة H4-6: «جولة تصدير/استيراد ذهاباً وإياباً بلا فقدان + حرس حقن صيغ موحد
 * على كل مسار». الجولة هنا حرفية: يُصدَّر CSV عبر CsvKit.buildCsv (المسار
 * الإنتاجي نفسه الذي تستعمله شاشات التصدير) ثم يُستورد عبر CsvImportKit
 * وتُطابَق القيم قيمةً قيمة. حرس الحقن: الاستيراد يخزّن النص حرفياً والتصدير
 * اللاحق يحروبه عبر CsvKit.escape — الاختبار يثبت السلسلة كاملة.
 */
class CsvImportKitTest {

    // ═══════════ (1) المحلل — RFC4180-lite ═══════════

    @Test
    fun c1_parse_basicAndQuoted() {
        val csv = "الاسم,الهاتف\n\"عميل، بفاصلة\",\"0501234567\"\nمورد بسيط,0509876543"
        val p = CsvImportKit.parse(csv)!!
        assertEquals(listOf("الاسم", "الهاتف"), p.header)
        assertEquals(2, p.rows.size)
        assertEquals("عميل، بفاصلة", p.rows[0][0]) // الفاصلة داخل الاقتباس حرفية
        assertEquals("0509876543", p.rows[1][1])
    }

    @Test
    fun c1_parse_bomAndCrlfAndQuoteEscape() {
        val csv = "\uFEFFالاسم,ملاحظة\r\n\"صنف \"\"مميز\"\"\",سطر1\r\nثانٍ,سطر2"
        val p = CsvImportKit.parse(csv)!!
        assertEquals(2, p.rows.size)
        assertEquals("صنف \"مميز\"", p.rows[0][0]) // «""» → «"»
        assertEquals("سطر1", p.rows[0][1])
    }

    @Test
    fun c1_parse_widthLockedToHeader() {
        val csv = "الاسم,الهاتف\nأحمد\nخالد,0500000000,عمود زائد"
        val p = CsvImportKit.parse(csv)!!
        assertEquals(listOf("أحمد", ""), p.rows[0])          // الناقص يُحشى
        assertEquals(listOf("خالد", "0500000000"), p.rows[1]) // الزائد يُقصّ
    }

    @Test
    fun c1_parse_rejectsEmpty() {
        assertNull(CsvImportKit.parse(""))
        assertNull(CsvImportKit.parse("\uFEFF  \n \n"))
        assertNull(CsvImportKit.parse(",,\n,,"))
    }

    // ═══════════ (2) كشف النوع من الرأس ═══════════

    @Test
    fun c2_detectKind_deterministic() {
        assertEquals(
            "products",
            CsvImportKit.detectKind(listOf("الاسم", "رمز SKU", "الباركود", "الوحدة", "سعر التكلفة", "سعر البيع", "الكمية المتوفرة", "حد الطلب", "قيمة المخزون"))
        )
        assertEquals("parties", CsvImportKit.detectKind(listOf("الاسم", "الهاتف", "النوع", "الرصيد", "مؤشر الخطر", "الحالة")))
        assertEquals("products", CsvImportKit.detectKind(listOf("name", "sku", "cost_price")))
        assertNull(CsvImportKit.detectKind(listOf("الاسم", "الملاحظة")))
    }

    // ═══════════ (3) الجولة الذهاب-الإياب — تصدير المنتج ↔ استيراده ═══════════

    @Test
    fun c3_productsRoundTrip_exportImport_lossless() {
        // التصدير بالمسار الإنتاجي نفسه (CsvKit.buildCsv — كما تفعه شاشات التصدير)
        val header = listOf("الاسم", "رمز SKU", "الباركود", "الوحدة", "سعر التكلفة", "سعر البيع", "الكمية المتوفرة", "حد الطلب", "قيمة المخزون")
        val rows = listOf(
            listOf("قهوة مختصة", "SKU-1", "6280000001", "كيس", "45.00", "60.00", "12", "3", "540.00"),
            listOf("سكر ناعم", "SKU-2", "", "كجم", "8.50", "12.25", "100.5", "20", "850.00")
        )
        val exported = CsvKit.buildCsv(header, rows, bom = true)

        val parsed = CsvImportKit.parse(exported)!!
        assertEquals("products", CsvImportKit.detectKind(parsed.header))
        val mapped = CsvImportKit.mapProducts(parsed)!!
        assertEquals(0, mapped.skipped.size)
        assertEquals(2, mapped.rows.size)
        // مطابقة قيمة-قيمة — صفر فقدان في الجولة
        val p0 = mapped.rows[0]
        assertEquals("قهوة مختصة", p0.name)
        assertEquals("SKU-1", p0.sku)
        assertEquals("6280000001", p0.barcode)
        assertEquals("كيس", p0.unit)
        assertEquals(4500L, p0.costPiasters)   // 45.00 → 4500 قرشاً
        assertEquals(6000L, p0.salePiasters)   // 60.00 → 6000
        assertEquals(12.0, p0.stockQty, 0.0)
        assertEquals(3.0, p0.reorderLevel, 0.0)
        // كسر الكمية والأسعار الغريبة تمر بالدقة نفسها
        val p1 = mapped.rows[1]
        assertEquals(850L, p1.costPiasters)    // 8.50
        assertEquals(1225L, p1.salePiasters)   // 12.25
        assertEquals(100.5, p1.stockQty, 0.0)
        assertEquals("كجم", p1.unit)           // الوحدة الفارغة سلوك مختلف — هذه غير فارغة
    }

    @Test
    fun c3_englishHeadersAccepted() {
        val csv = "name,sku,barcode,unit,cost_price,sale_price,stock_qty,reorder_level\nTea,SKU-9,,box,10,15,5,1"
        val parsed = CsvImportKit.parse(csv)!!
        val mapped = CsvImportKit.mapProducts(parsed)!!
        assertEquals(1, mapped.rows.size)
        assertEquals(1000L, mapped.rows[0].costPiasters)
        assertEquals(1500L, mapped.rows[0].salePiasters)
        assertEquals("box", mapped.rows[0].unit) // الوحدة الموجودة تمر كما هي
    }

    @Test
    fun c3_badRowsSkippedWithReasons() {
        val csv = "الاسم,سعر التكلفة,الكمية المتوفرة\nصالح,10,5\n,10,5\nتالف,abc,5\nسالب,10,-3"
        val mapped = CsvImportKit.mapProducts(CsvImportKit.parse(csv)!!)!!
        assertEquals(1, mapped.rows.size)
        assertEquals("صالح", mapped.rows[0].name)
        assertEquals(3, mapped.skipped.size) // اسم فارغ + رقم غير قابل للتحليل + كمية سالبة
        assertTrue(mapped.skipped[0].contains("الاسم"))
        assertTrue(mapped.skipped[1].contains("غير قابل للتحليل"))
    }

    // ═══════════ (4) الأطراف — الهوية فقط، الرصيد عمداً لا يُدخل ═══════════

    @Test
    fun c4_partiesMapping_typeWords() {
        val csv = "الاسم,الهاتف,النوع,الرصيد\nعميل أول,0501,العملاء,100.00\nمورد أول,0502,الموردون,50\nكلاهما,0503,عميل ومورد,9\nرقمي,0504,1,0\nغريب,0505,فضائي,0\nبلا نوع,0506,,0"
        val mapped = CsvImportKit.mapParties(CsvImportKit.parse(csv)!!, "العملاء", "الموردون", "عميل ومورد")!!
        assertEquals(5, mapped.rows.size)
        assertEquals(1, mapped.skipped.size) // «فضائي» فقط
        assertEquals(0, mapped.rows[0].type)
        assertEquals(1, mapped.rows[1].type)
        assertEquals(2, mapped.rows[2].type)
        assertEquals(1, mapped.rows[3].type) // رقم خام
        assertEquals(0, mapped.rows[4].type) // فارغ = عميل
    }

    @Test
    fun c4_partyType_wordMatching() {
        assertEquals(2, CsvImportKit.mapPartyType("عميل ومورد", "العملاء", "الموردون", "عميل ومورد"))
        assertEquals(1, CsvImportKit.mapPartyType("الموردون", "العملاء", "الموردون", "عميل ومورد"))
        assertEquals(0, CsvImportKit.mapPartyType("العملاء", "العملاء", "الموردون", "عميل ومورد"))
        assertEquals(1, CsvImportKit.mapPartyType("supplier", "", "", ""))
        assertEquals(0, CsvImportKit.mapPartyType("customer", "", "", ""))
        assertNull(CsvImportKit.mapPartyType("alien", "", "", ""))
    }

    // ═══════════ (5) حرس الحقن الموحد — سلسلة الاستيراد/التصدير ═══════════

    @Test
    fun c5_injectionChain_importVerbatim_exportEscaped() {
        // الاستيراد يخزّن النص كما هو — الحرس الفعلي عند التصدير التالي (عقد L-5)
        val evil = "=HYPERLINK(\"http://x\",\"نفّذ\")"
        // الاقتباس السليم RFC4180: علامات الاقتباس الداخلية تُضاعَف (كما يكتب CsvKit نفسه)
        val csv = "الاسم,الهاتف\n\"${evil.replace("\"", "\"\"")}\",0500"
        val mapped = CsvImportKit.mapParties(CsvImportKit.parse(csv)!!, "", "", "")!!
        assertEquals(evil, mapped.rows[0].name) // بلا تحريف في الاستيراد
        // والتصدير اللاحق يسبقه بعلامة اقتباس أحادية تُخفيه عن Excel كصيغة (CsvKit.escape)
        val reExported = CsvKit.buildCsv(listOf("الاسم"), listOf(listOf(mapped.rows[0].name)), bom = false)
        assertTrue("يجب أن يبدأ الحقل المصدَّر بعلامة الحرس", reExported.contains("'=HYPERLINK"))
    }
}
