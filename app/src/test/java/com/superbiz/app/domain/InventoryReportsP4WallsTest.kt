package com.superbiz.app.domain

import com.superbiz.app.data.db.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P37-T-A] جدران اختبار منطق domain النقي — InventoryP4.kt + ReportsP4.kt (JUnit4 بلا Android/Robolectric).
 *
 * النطاق المغطى حصراً من الملفين:
 * • PriceLabel.textPreview/buildBytes: اقتطاع السطر بعرض الورق، تحييد الفاصل 20..64،
 *   هيكل بايتات ESC/POS بأزاحة صارمة (تهيئة/جدولة CP1256/توسيط/عريض/حجم مضاعف)،
 *   باركود CODE128 بعدد بايتات مطابق، وبلا باركود تتخطى الكتلة وتنتهي بالقص.
 * • ShoppingList.build: التجميع والعناوين والفواصل وعدد الأصناف والفراغ والتوطين.
 * • ReorderApply.purchaseQty: رفض الصفر/السالب/غير المنتهي، الحد الأدنى 1.0، تقريب round2.
 * • NeverSold.filter: الإبقاء حصراً لغير المبيع.
 * • PeriodCompare.ratioChange/direction/metric/profitMetric/compare/hasData: الصدق الصفري.
 * • ReportSummaryText.build: نصوص الوصف لكل مسار والتوطين والسالب المقرّب.
 * • CategoryScope.select/aggregate: ترشيح التصنيف، أساس lineTotal الموحّد، ربح null بلا تكلفة.
 *
 * قواعد الملف: حتمية كاملة — لا زمن حائط ولا Locale حائط (Money.num/numP منسقاتها Locale.US
 * ثابتة). النصوص في اختبارات buildBytes لاتينية ASCII حصراً لأنها تمر عبر EscPos.arabicText
 * بلا تشكيل ولا عكس (مسار !hasArabic يُعيد النص كما هو، وwindows-1256 مطابق لـASCII) —
 * فتُثبَّت أزاحات البايتات حرفياً. المبالغ المخزنة قروش Long منذ v10 والكميات Double كما هي.
 *
 * ملاحظات موثقة (سلوك فعلي موثق بالاختبار لا خطأ):
 * • textPreview: الفاصل يُحيَّد إلى 20..64 بينما الاقتطاع يستخدم العرض الخام — عند width<20
 *   يكون الفاصل أطول من النص المقصوص، وعند width>64 قد يتجاوز النص (70>64) الفاصل.
 * • ChecksIcs/PriceLabel خارج نطاق هذا الملف — انظر DebtPlanP4Test وDebtsChecksP4Test.
 */
class InventoryReportsP4WallsTest {

    /** تأكيد بايتات متتالية بموقع صريح — رسالة تحمل الموقع للتفريغ السريع */
    private fun assertBytesAt(bytes: ByteArray, offset: Int, vararg expected: Int) {
        expected.forEachIndexed { i, b ->
            assertEquals("بايت عند ${offset + i}", b, bytes[offset + i].toInt() and 0xFF)
        }
    }

    // ══ 1) PriceLabel.textPreview — اقتطاع العرض ══

    @Test
    fun `textPreview يقيد كل سطر بعرض الورق ويقتطع الزائد`() {
        val preview = PriceLabel.textPreview("A".repeat(40), "12.50", "ABC123", width = 32)
        val lines = preview.lines()
        assertEquals(5, lines.size) // فاصل، اسم، سعر، باركود، فاصل
        assertEquals("-".repeat(32), lines[0])
        assertEquals("-".repeat(32), lines[4])
        // الاسم 40 حرفاً اقتُطع صادقاً إلى 32 — بلا التفاف طابعة مفاجئ
        assertEquals("A".repeat(32), lines[1])
        assertEquals("12.50", lines[2])
        assertEquals("ABC123", lines[3])
        assertTrue(lines.all { it.length <= 32 })
        // الاسم بطول العرض بالضبط لا يُمس
        assertEquals("B".repeat(32), PriceLabel.textPreview("B".repeat(32), "5", "", width = 32).lines()[1])
        // النهاية بلا سطر زائد: آخر محرف شرطة لا \n
        assertTrue(preview.endsWith("-"))
        assertTrue(!preview.endsWith("\n"))
    }

    @Test
    fun `textPreview يحييد الفاصل إلى 20 و64 والاقتطاع بالعرض الخام`() {
        // width=10: الفاصل يصير 20 (الحد الأدنى) لكن الاقتطاع بالعرض الخام 10 — سلوك موثق
        val narrow = PriceLabel.textPreview("CDEFGHIJKLMN", "5", "", width = 10)
        val nLines = narrow.lines()
        assertEquals("-".repeat(20), nLines[0])
        assertEquals("CDEFGHIJKL", nLines[1])
        assertEquals(20, nLines[0].length)
        assertEquals(10, nLines[1].length)
        // width=100: الفاصل يصير 64 (الحد الأعلى) والاسم 70 يمر بلا اقتطاع — تفاوت موثق
        val wide = PriceLabel.textPreview("N".repeat(70), "5", "", width = 100)
        val wLines = wide.lines()
        assertEquals("-".repeat(64), wLines[0])
        assertEquals("N".repeat(70), wLines[1])
    }

    @Test
    fun `textPreview بلا باركود يحذف سطره ولو كان فراغات`() {
        assertEquals(4, PriceLabel.textPreview("اسم", "10", "", width = 32).lines().size)
        // isNotBlank: الباركود المكون من فراغات يُعامل كغائب
        assertEquals(4, PriceLabel.textPreview("اسم", "10", "   ", width = 32).lines().size)
        val withBarcode = PriceLabel.textPreview("اسم", "10", "123", width = 32)
        assertEquals(5, withBarcode.lines().size)
    }

    // ══ 2) PriceLabel.buildBytes — هيكل ESC/POS بأزاحات صارمة ══

    @Test
    fun `buildBytes يثبت مقدمة الأوامر بالترتيب تهيئة جدولة توسيط عريض`() {
        val bytes = PriceLabel.buildBytes("ABC", "12.50", "", width = 32)
        // ESC @ ثم ESC t 32 (CP1256) ثم ESC a 1 (توسيط) ثم ESC E 1 (عريض للاسم)
        assertBytesAt(bytes, 0, 0x1B, 0x40)
        assertBytesAt(bytes, 2, 0x1B, 0x74, 0x20)
        assertBytesAt(bytes, 5, 0x1B, 0x61, 0x01)
        assertBytesAt(bytes, 8, 0x1B, 0x45, 0x01)
        // الاسم ASCII يمر كما هو ثم \n ثم إطفاء العريض
        assertEquals('A'.code, bytes[11].toInt() and 0xFF)
        assertEquals('B'.code, bytes[12].toInt() and 0xFF)
        assertEquals('C'.code, bytes[13].toInt() and 0xFF)
        assertBytesAt(bytes, 14, 0x0A)
        assertBytesAt(bytes, 15, 0x1B, 0x45, 0x00)
        // السعر بحجم مضاعف GS ! 0x11 ثم إعادته عادياً GS ! 0
        assertBytesAt(bytes, 18, 0x1D, 0x21, 0x11)
        assertEquals('1'.code, bytes[21].toInt() and 0xFF)
        assertEquals('2'.code, bytes[22].toInt() and 0xFF)
        assertEquals('.'.code, bytes[23].toInt() and 0xFF)
        assertEquals('5'.code, bytes[24].toInt() and 0xFF)
        assertEquals('0'.code, bytes[25].toInt() and 0xFF)
        assertBytesAt(bytes, 26, 0x0A)
        assertBytesAt(bytes, 27, 0x1D, 0x21, 0x00)
    }

    @Test
    fun `buildBytes يبني باركود CODE128 بعدد بايتات مطابق وحجما مضاعفا للسعر`() {
        val bytes = PriceLabel.buildBytes("ABC", "12.50", "ABC123456", width = 32)
        val s = String(bytes, Charsets.ISO_8859_1)
        // كتلة الباركود: GS h 80 ثم GS w 2 ثم GS k 73 n (n = 2 + طول القيمة = {B)
        assertBytesAt(bytes, 30, 0x1D, 0x68, 0x50)
        assertBytesAt(bytes, 33, 0x1D, 0x77, 0x02)
        assertBytesAt(bytes, 36, 0x1D, 0x6B, 0x49)
        assertEquals("عدد بايتات بيانات CODE128", 11, bytes[39].toInt() and 0xFF) // {B + 9
        assertEquals("{BABC123456", s.substring(40, 51))
        // سطران فارغان بعد الباركود ثم القص الجزئي GS V B 0
        assertBytesAt(bytes, 51, 0x0A, 0x0A)
        assertBytesAt(bytes, 53, 0x1D, 0x56, 0x42, 0x00)
        assertEquals(57, bytes.size)
        // لا أثر لنص UTF-8: كل البايتات أقل من 0x80 (نصوص لاتينية/أوامر)
        assertTrue(bytes.all { it.toInt() and 0xFF <= 0x7F })
    }

    @Test
    fun `buildBytes بلا باركود يتخطى الكتلة كاملة وينتهي بالقص`() {
        val bytes = PriceLabel.buildBytes("ABC", "30", "", width = 32)
        val s = String(bytes, Charsets.ISO_8859_1)
        // لا GS h ولا GS w ولا GS k ولا بيانات {B
        assertTrue(!s.contains("\u001D\u0068"))
        assertTrue(!s.contains("\u001D\u006B"))
        assertTrue(!s.contains("{B"))
        // بعد إعادة الحجم العادي يأتي القص مباشرة — لا وسط ولا كتلة باركود
        assertBytesAt(bytes, 24, 0x1D, 0x21, 0x00)
        assertBytesAt(bytes, 27, 0x1D, 0x56, 0x42, 0x00)
        // الإجمالي = موضع القص + 4 بايتات (الاختلاف عن نسخة الباركود: كتلة الباركود
        // كاملة مع سطريها الفارغين 3+3+3+12+2 = 23 بايتاً)
        assertEquals(31, bytes.size)
        assertBytesAt(bytes, bytes.size - 4, 0x1D, 0x56, 0x42, 0x00)
    }

    // ══ 3) ShoppingList.build — التجميع والعناوين ══

    @Test
    fun `build يجمع الصفوف تحت العناوين مع عدد الأصناف`() {
        val text = ShoppingList.build(
            listOf(
                ShoppingList.Row("عصير برتقال", "قطعة", 2.0, 10.0),
                ShoppingList.Row("سكر ناعم", "كيس", 0.5, 5.0)
            )
        )
        val lines = text.lines()
        assertEquals(7, lines.size) // عنوان، فاصل، صفان، فاصل، عدد، ملاحظة
        assertTrue(lines[0].contains("قائمة التسوّج"))
        // الفاصلان متطابقان ومكوّنان من شرطات طويلة حصراً
        assertEquals(lines[1], lines[4])
        assertTrue(lines[1].isNotEmpty() && lines[1].all { it == '—' })
        // كل صف بنقطة وبالوحدتين
        assertEquals("• عصير برتقال — المتوفر: 2 قطعة | المقترح شراء: 10 قطعة", lines[2])
        assertEquals("• سكر ناعم — المتوفر: 0.50 كيس | المقترح شراء: 5 كيس", lines[3])
        assertEquals("عدد الأصناف: 2", lines[5])
        // الملاحظة آخر سطر بلا \n زائد
        assertTrue(lines[6].contains("خوارزمية إعادة الطلب"))
        assertTrue(!text.endsWith("\n"))
    }

    @Test
    fun `build لقائمة فارغة يعيد نصا فارغا`() {
        assertEquals("", ShoppingList.build(emptyList()))
    }

    @Test
    fun `build يستبدل التسميات الممررة ويحل موضع العدد`() {
        val labels = ShoppingList.Labels(
            title = "Shopping", available = "Avail", suggested = "Buy",
            count = "Items: %1\$d", note = "Auto note"
        )
        val text = ShoppingList.build(listOf(ShoppingList.Row("Milk", "pcs", 1.0, 4.0)), labels)
        val lines = text.lines()
        assertEquals("Shopping", lines[0])
        assertTrue(lines[2].contains("Avail: 1 pcs"))
        assertTrue(lines[2].contains("Buy: 4 pcs"))
        // صف واحد ⇒ الترتيب: عنوان، فاصل، صف، فاصل، عدد، ملاحظة (6 أسطر)
        assertEquals("Items: 1", lines[4])
        assertEquals("Auto note", lines[5])
        assertTrue(text.endsWith("Auto note"))
    }

    @Test
    fun `تنسيق الكميات في الصفوف صحيح بلا كسور وكسريا بخانتين`() {
        val text = ShoppingList.build(
            listOf(
                ShoppingList.Row("عصير", "قطعة", 2.0, 10.0),
                ShoppingList.Row("سكر", "كيس", 0.5, 2.675)
            )
        )
        // Money.num: الصحيح بلا منزلة عشرية، الكسري بخانتين، والتقريب قرشاً HALF_UP
        assertTrue(text.contains("المتوفر: 2 قطعة"))
        assertTrue(text.contains("المقترح شراء: 10 قطعة"))
        assertTrue(text.contains("المتوفر: 0.50 كيس"))
        assertTrue(text.contains("المقترح شراء: 2.68 كيس"))
        assertTrue(!text.contains("2.675"))
    }

    // ══ 4) ReorderApply.purchaseQty ══

    @Test
    fun `purchaseQty يرفض الصفر والسالب وغير المنتهي`() {
        assertNull(ReorderApply.purchaseQty(0.0))
        assertNull(ReorderApply.purchaseQty(-3.0))
        assertNull(ReorderApply.purchaseQty(-0.001))
        assertNull(ReorderApply.purchaseQty(Double.NaN))
        assertNull(ReorderApply.purchaseQty(Double.POSITIVE_INFINITY))
        assertNull(ReorderApply.purchaseQty(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `purchaseQty يرفع الجزئي إلى الحد الأدنى 1`() {
        assertEquals(1.0, ReorderApply.purchaseQty(0.3)!!, 0.0)
        assertEquals(1.0, ReorderApply.purchaseQty(0.999)!!, 0.0)
        // 0.125 يقرَّب قرشاً إلى 0.13 ثم يرفعه الحد الأدنى
        assertEquals(1.0, ReorderApply.purchaseQty(0.125)!!, 0.0)
        // الحد بالضبط: اقتراح 1.0 يبقى 1.0
        assertEquals(1.0, ReorderApply.purchaseQty(1.0)!!, 0.0)
    }

    @Test
    fun `purchaseQty يقرب الاقتراح قرشا عبر round2`() {
        // 12.345 مكتوباً → HALF_UP إلى 12.35
        assertEquals(12.35, ReorderApply.purchaseQty(12.345)!!, 0.0)
        // 2.675 ثنائياً 2.67499… لكن العقد يلتقط المكتوب → 2.68
        assertEquals(2.68, ReorderApply.purchaseQty(2.675)!!, 0.0)
        // الصحيح المطبوع يمر كما هو
        assertEquals(7.25, ReorderApply.purchaseQty(7.25)!!, 0.0)
        assertEquals(12.0, ReorderApply.purchaseQty(12.0)!!, 0.0)
    }

    @Test
    fun `purchaseQty يلتزم حدا أدنى مخصصا ولا يخفض الصحيح`() {
        // حد مخصص أكبر من الاقتراح يرفعه
        assertEquals(5.0, ReorderApply.purchaseQty(2.0, minimum = 5.0)!!, 0.0)
        // حد مخصص أصغر لا يخفض الاقتراح الشرعي
        assertEquals(3.0, ReorderApply.purchaseQty(3.0, minimum = 2.5)!!, 0.0)
        assertEquals(7.0, ReorderApply.purchaseQty(7.0, minimum = 0.5)!!, 0.0)
    }

    // ══ 5) NeverSold.filter ══

    @Test
    fun `filter يبقي من لم يبع حصرا ويتسامح مع معرفات غريبة`() {
        val products = listOf(
            Product(id = 1, name = "مُباع", createdAt = 0L),
            Product(id = 2, name = "غير مباع", createdAt = 0L),
            Product(id = 3, name = "جديد", createdAt = 0L)
        )
        // المبيع 1 يُحذف، والمعرف الغريب 99 بلا أثر، والترتيب محفوظ
        assertEquals(listOf(2L, 3L), NeverSold.filter(products, setOf(1L, 99L)).map { it.id })
        // لا مبيعات قط → الكل؛ والكل مبيع → فراغ صادق
        assertEquals(3, NeverSold.filter(products, emptySet()).size)
        assertTrue(NeverSold.filter(products, setOf(1L, 2L, 3L)).isEmpty())
    }

    // ══ 6) PeriodCompare — مقارنة الفترات ══

    @Test
    fun `ratioChange يعطي النسب العادية والصفري المزدوج صفرا`() {
        assertEquals(25.0, PeriodCompare.ratioChange(125.0, 100.0)!!, 1e-9)
        assertEquals(-50.0, PeriodCompare.ratioChange(50.0, 100.0)!!, 1e-9)
        assertEquals(150.0, PeriodCompare.ratioChange(250.0, 100.0)!!, 1e-9)
        // كلاهما ≤ 0 → ثابت حقيقي بصفر (سالب على سالب ليس نمواً)
        assertEquals(0.0, PeriodCompare.ratioChange(0.0, 0.0)!!, 0.0)
        assertEquals(0.0, PeriodCompare.ratioChange(-5.0, -5.0)!!, 0.0)
        assertEquals(0.0, PeriodCompare.ratioChange(0.0, -7.0)!!, 0.0)
    }

    @Test
    fun `ratioChange بلا أساس حين يكون السابق صفرا أو سالبا والحالي موجبا`() {
        // قسمة على صفر صادقة: null يُعرض «—» في الواجهة
        assertNull(PeriodCompare.ratioChange(100.0, 0.0))
        assertNull(PeriodCompare.ratioChange(10.0, -5.0))
        assertNull(PeriodCompare.ratioChange(0.001, -0.001))
    }

    @Test
    fun `ratioChange يوثق الانهيار الكامل والسالب حين يقص الحالي`() {
        // سابق موجب وحالي صفر → −100%
        assertEquals(-100.0, PeriodCompare.ratioChange(0.0, 100.0)!!, 1e-9)
        // تحول الربح لخسارة: −150%
        assertEquals(-150.0, PeriodCompare.ratioChange(-50.0, 100.0)!!, 1e-9)
        assertEquals(-87.5, PeriodCompare.ratioChange(25.0, 200.0)!!, 1e-9)
    }

    @Test
    fun `direction يميز الصاعد والنازل والثابت`() {
        assertEquals(1, PeriodCompare.direction(120.0, 100.0))
        assertEquals(-1, PeriodCompare.direction(80.0, 100.0))
        assertEquals(0, PeriodCompare.direction(100.0, 100.0))
        assertEquals(0, PeriodCompare.direction(-5.0, -5.0))
        assertEquals(1, PeriodCompare.direction(0.0, -1.0))
    }

    @Test
    fun `metric يضم النسبة والاتجاه كما هما`() {
        assertEquals(
            PeriodCompare.MetricChange(125.0, 100.0, 25.0, 1),
            PeriodCompare.metric(125.0, 100.0)
        )
        assertEquals(
            PeriodCompare.MetricChange(80.0, 100.0, -20.0, -1),
            PeriodCompare.metric(80.0, 100.0)
        )
        assertEquals(
            PeriodCompare.MetricChange(99.0, 99.0, 0.0, 0),
            PeriodCompare.metric(99.0, 99.0)
        )
    }

    @Test
    fun `profitMetric بلا أساس في الفترتين غير قابل للقياس والغائب يعامل صفرا`() {
        // كلا الفترتين بلا أساس تكلفة → غير قابل للقياس صراحة
        assertEquals(PeriodCompare.MetricChange(0.0, 0.0, null, 0), PeriodCompare.profitMetric(null, null))
        // أساس في الحالي فقط: الغائب السابق صفر → لا نسبة لكن اتجاه صاعد صادق
        val up = PeriodCompare.profitMetric(50.0, null)
        assertNull(up.ratio)
        assertEquals(1, up.direction)
        assertEquals(50.0, up.current, 0.0)
        assertEquals(0.0, up.previous, 0.0)
        // أساس في السابق فقط: الحالي الغائب صفر → انهيار −100%
        val down = PeriodCompare.profitMetric(null, 40.0)
        assertEquals(-100.0, down.ratio!!, 1e-9)
        assertEquals(-1, down.direction)
        // صفر صريح ليس غياباً — ثابت حقيقي
        val flat = PeriodCompare.profitMetric(0.0, 0.0)
        assertEquals(0.0, flat.ratio!!, 0.0)
        assertEquals(0, flat.direction)
    }

    @Test
    fun `compare يعطي اربعة مؤشرات بالترتيب ويعوض غياب المتوسط صفرا`() {
        val cur = PeriodStats(sales = 1000.0, profit = 200.0, invoiceCount = 10)   // متوسط 100
        val prev = PeriodStats(sales = 800.0, profit = null, invoiceCount = 9)     // متوسط 800/9
        val rows = PeriodCompare.compare(cur, prev)
        assertEquals(4, rows.size)
        assertEquals(PeriodCompare.MetricChange(1000.0, 800.0, 25.0, 1), rows[0])          // المبيعات
        assertEquals(PeriodCompare.MetricChange(200.0, 0.0, null, 1), rows[1])             // الربح — السابق بلا أساس
        assertEquals(100.0 / 9.0, rows[2].ratio!!, 1e-9)                                    // عدد الفواتير (1/9)×100
        assertEquals(1, rows[2].direction)
        // المتوسط: (100 − 800/9) ÷ (800/9) × 100 = 12.5 بالضبط
        assertEquals(100.0, cur.avgInvoice!!, 1e-9)
        assertEquals(12.5, rows[3].ratio!!, 1e-9)
        assertEquals(1, rows[3].direction)
        // جانب بلا فواتير: متوسطه null يُعوَّض صفراً فيُظهر انهياراً −100%
        val zero = PeriodStats(sales = 0.0, profit = null, invoiceCount = 0)
        assertNull(zero.avgInvoice)
        val avgRow = PeriodCompare.compare(zero, PeriodStats(2000.0, 300.0, 20))[3]
        assertEquals(PeriodCompare.MetricChange(0.0, 100.0, -100.0, -1), avgRow)
    }

    @Test
    fun `hasData يخفي المقارنة حين لا فواتير في الفترتين`() {
        val empty = PeriodStats(0.0, null, 0)
        assertTrue(!PeriodCompare.hasData(empty, empty))
        assertTrue(PeriodCompare.hasData(empty, empty.copy(invoiceCount = 1)))
        assertTrue(PeriodCompare.hasData(empty.copy(invoiceCount = 5), empty))
    }

    // ══ 7) ReportSummaryText.build ══

    @Test
    fun `build يسرد الاقسام الاربعة بالعملة بلا سطر زائد`() {
        val text = ReportSummaryText.build(
            sales = 5000.0, expenses = 1200.5, profit = 3799.5,
            debts = 750.0, periodLabel = "آخر 30 يوماً", currency = "SAR"
        )
        val lines = text.lines()
        assertEquals(6, lines.size) // عنوان، فاصل، مبيعات، مصروفات، ربح، ذمم
        assertEquals("📊 ملخص التقرير — آخر 30 يوماً", lines[0])
        assertEquals("المبيعات: 5,000 SAR", lines[2])
        assertEquals("المصروفات: 1,200.50 SAR", lines[3])
        assertEquals("صافي الربح: 3,799.50 SAR", lines[4])
        assertEquals("ذمم العملاء المفتوحة: 750 SAR", lines[5])
        assertTrue(!text.endsWith("\n")) // trimEnd يقصص الذيل
    }

    @Test
    fun `build يحل موضع الفترة في العنوان حيثما كان`() {
        val text = ReportSummaryText.build(1.0, 0.0, 1.0, 0.0, "ديسمبر 2026", "ر.س")
        assertEquals("📊 ملخص التقرير — ديسمبر 2026", text.lines()[0])
        // موضع العنصر في وسط العنوان أيضاً يُستبدل
        val labels = ReportSummaryText.Labels(title = "تقرير %1\$s الشهري")
        val mid = ReportSummaryText.build(1.0, 0.0, 1.0, 0.0, "Q1", "SAR", labels)
        assertEquals("تقرير Q1 الشهري", mid.lines()[0])
    }

    @Test
    fun `build يدعم تسميات مخصصة وسالبا مقربا`() {
        val labels = ReportSummaryText.Labels(
            title = "Report %1\$s", sales = "S", expenses = "E", profit = "P", debts = "D"
        )
        val text = ReportSummaryText.build(
            sales = -1200.5, expenses = 300.0, profit = -1500.5,
            debts = 0.0, periodLabel = "Q1", currency = "$", labels = labels
        )
        val lines = text.lines()
        assertEquals("Report Q1", lines[0])
        assertEquals("S: -1,200.50 $", lines[2])
        assertEquals("E: 300 $", lines[3])
        assertEquals("P: -1,500.50 $", lines[4])
        assertEquals("D: 0 $", lines[5])
    }

    // ══ 8) CategoryScope.select/aggregate ══

    @Test
    fun `select يرشح بمعرفات التصنيف ويستبعد البيع الحر والمجموعة الفارغة`() {
        val items = listOf(
            CategoryScope.ItemRef(productId = 1L, qty = 2.0, unitPrice = 10.0, lineTotal = 20.0),
            CategoryScope.ItemRef(productId = 2L, qty = 1.0, unitPrice = 5.0, lineTotal = 5.0),
            CategoryScope.ItemRef(productId = null, qty = 1.0, unitPrice = 30.0, lineTotal = 30.0), // بيع حر
            CategoryScope.ItemRef(productId = 3L, qty = 1.0, unitPrice = 7.0, lineTotal = 7.0)
        )
        // تصنيف واحد
        assertEquals(listOf(20.0), CategoryScope.select(setOf(1L), items).map { it.lineTotal })
        // تصنيفان بالترتيب الأصلي للبنود
        assertEquals(listOf(20.0, 7.0), CategoryScope.select(setOf(3L, 1L), items).map { it.lineTotal })
        // البيع الحر (productId=null) يُستبعد دائماً حتى لو كان التصنيف الوحيد
        assertTrue(CategoryScope.select(setOf(1L), items).none { it.productId == null })
        // مجموعة فارغة → لا شيء مهما كانت البنود؛ وبنود فارغة → لا شيء
        assertTrue(CategoryScope.select(emptySet(), items).isEmpty())
        assertTrue(CategoryScope.select(setOf(1L), emptyList()).isEmpty())
    }

    @Test
    fun `aggregate يجمع المبيعات من lineTotal والربح من التكلفة المعروفة حصرا`() {
        val items = listOf(
            CategoryScope.ItemRef(productId = 1L, qty = 2.0, unitPrice = 10.0, lineTotal = 20.0), // تكلفة 4/وحدة → ربح 12
            CategoryScope.ItemRef(productId = 2L, qty = 1.0, unitPrice = 5.0, lineTotal = 5.0)    // بلا تكلفة → خارج الربح
        )
        val stats = CategoryScope.aggregate(items, invoiceCount = 2, costOf = { pid -> if (pid == 1L) 4.0 else null })
        assertEquals(25.0, stats.sales, 1e-9)
        assertEquals(12.0, stats.profit!!, 1e-9)
        assertEquals(2, stats.invoiceCount)
        assertEquals(12.5, stats.avgInvoice!!, 1e-9)
    }

    @Test
    fun `aggregate يوحد الاساس بالخصم ويستثني البيع الحر من الربح`() {
        // [P6-M29]: المبيعات والربح من lineTotal نفسه — 2×10 بخصم → 18، والربح 18−(4×2)=10
        val discounted = listOf(
            CategoryScope.ItemRef(productId = 1L, qty = 2.0, unitPrice = 10.0, lineTotal = 18.0)
        )
        val s1 = CategoryScope.aggregate(discounted, invoiceCount = 1, costOf = { 4.0 })
        assertEquals(18.0, s1.sales, 1e-9)
        assertEquals(10.0, s1.profit!!, 1e-9)
        // البيع الحر يدخل المبيعات ولا يدخل الربح (لا معرف لتكلفته)
        val mixed = discounted + CategoryScope.ItemRef(productId = null, qty = 1.0, unitPrice = 30.0, lineTotal = 30.0)
        val s2 = CategoryScope.aggregate(mixed, invoiceCount = 1, costOf = { 4.0 })
        assertEquals(48.0, s2.sales, 1e-9)
        assertEquals(10.0, s2.profit!!, 1e-9)
    }

    @Test
    fun `aggregate بلا تكلفة يعطي ربح null ويقرب الاساس قرشا`() {
        // لا تكلفة معروفة إطلاقاً → profit = null (صدق فوق تجميل)
        val items = listOf(CategoryScope.ItemRef(productId = 1L, qty = 1.0, unitPrice = 5.0, lineTotal = 5.0))
        val noCost = CategoryScope.aggregate(items, invoiceCount = 1, costOf = { null })
        assertEquals(5.0, noCost.sales, 1e-9)
        assertNull(noCost.profit)
        // بنود بيع حر فقط مع دالة تكلفة جاهزة: لا معرف → الربح يبقى null
        val freeOnly = listOf(CategoryScope.ItemRef(productId = null, qty = 1.0, unitPrice = 30.0, lineTotal = 30.0))
        assertNull(CategoryScope.aggregate(freeOnly, invoiceCount = 1, costOf = { 5.0 }).profit)
        // التقريب HALF_UP على القيمة المكتوبة: 10.125 → 10.13 وربح 2.125 → 2.13
        val rounding = listOf(CategoryScope.ItemRef(productId = 1L, qty = 1.0, unitPrice = 10.125, lineTotal = 10.125))
        val s = CategoryScope.aggregate(rounding, invoiceCount = 1, costOf = { 8.0 })
        assertEquals(10.13, s.sales, 0.0)
        assertEquals(2.13, s.profit!!, 0.0)
    }
}
