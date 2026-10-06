package com.superbiz.app.domain

import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Invoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [P37-T-A] جدران اختبار منطق domain النقي — DebtPlanP4.kt (JUnit4 بلا Android/Robolectric).
 *
 * النطاق المغطى حصراً من app/src/main/java/com/superbiz/app/domain/DebtPlanP4.kt:
 * • DebtPlan.build: توزيع الرصيد قرشاً بقرش، بقايا القسمة، n=1 والحدود 1..12، رصيد ≤ 0،
 *   المواعيد الشهرية التصاعدية مع تقصير الأشهر القصيرة والحفاظ على وقت اليوم،
 *   total() يساوي الرصيد بالضبط.
 * • عقد تحويل المال: halalasOf/riyals عبر Money.toPiasters/fromPiasters — الريال Double
 *   يُلتقط بقرش واحد (HALF_UP على التمثيل العشري المكتوب) والقروش Long هي المخزَّن الوحيد منذ v10.
 * • ChecksArchive.candidates/split: التصفية status==2 والفصل بالمحفوظات مع حفظ الترتيب.
 * • DebtSort.comparator للأوضاع الأربعة (MODE_DEFAULT/OLDEST/BALANCE/NAME) على SortRow،
 *   وoldestOpenMap لفواتير البيع غير المسددة (الأقدم لكل طرف).
 * • ChecksWeek.weekStats/predicate: حدود الأسبوع السبعة، شيك اليوم، المتأخر، المغلق.
 * • ChecksIcs.build: بنية VCALENDAR/VEVENT وCRLF، escape، localDate/utcBasic، UID الثابت.
 * • EarlyPay.quote/eligible: خصم 2% بقرش، الحراسة على النسب والدفع الموجب، الأهلية قبل الاستحقاق.
 *
 * قواعد الملف: حتمية كاملة — لا System.currentTimeMillis ولا LocalDate.now؛ كل التواريخ
 * تُبنى بـCalendar من قيم صريحة (نمط DebtsChecksP4Test). أسماء الاختبارات بالعربية بين backticks.
 *
 * ملاحظة موثقة (سلوك فعلي لا خطأ): بقايا قسمة الرصيد تلحق «الأولى» من الدفعات بالترتيب
 * (كل دفعة تحصل floor ثم +1 قرش بالترتيب) — أي أن الدفعة الأولى تمتص البقايا لا الأخيرة،
 * مطابقةً لتوثيق DebtPlan.build نفسه «تُوزَّع قرشاً واحداً لكل دفعة بالترتيب».
 * كذلك ChecksIcs.build له باراميتر stamp افتراضي System.currentTimeMillis — الاختبارات
 * تمرر طابعاً صريحاً دائماً (فخ الحتمية).
 */
class DebtPlanP4Test {

    private val day = 86_400_000L

    /** توقيت ثابت حتمي: بتاريخ وساعة صريحين — منتصف الليل المحلي إذا سكتت الساعة */
    private fun ts(year: Int, month1to12: Int, dayOfMonth: Int, hour: Int = 12, minute: Int = 0): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month1to12 - 1, dayOfMonth, hour, minute, 0)
        return c.timeInMillis
    }

    /** شيك ثابت البذرة: issueDate قبل الاستحقاق بثلاثين يوماً */
    private fun check(
        id: Long, status: Int = 0, dueDate: Long, amount: Long = 10000L,
        number: String = "C-$id", partyId: Long = 1, bank: String = ""
    ) = CheckEntity(
        id = id, number = number, partyId = partyId, bank = bank,
        amount = amount, issueDate = dueDate - 30 * day, dueDate = dueDate,
        direction = 0, status = status
    )

    /** فاتورة ثابتة البذرة — المبالغ قروش Long منذ [P33-P8] */
    private fun inv(
        id: Long, partyId: Long, date: Long, total: Long,
        paid: Long = 0L, status: Int = 0, type: Int = 0
    ) = Invoice(
        id = id, number = "INV-$id", partyId = partyId, type = type,
        date = date, dueDate = date + 30 * day, subtotal = total, total = total,
        paid = paid, status = status
    )

    // ══ 1) DebtPlan.build — قسمة القرش ══

    @Test
    fun `التقسيم يوزع الرصيد قرشا بقرش والمجموع يساوي الرصيد بالضبط`() {
        val today = ts(2026, 1, 15)
        // 100.00 ريال (10000 قروش) على 3 دفعات: base=3333 وبقية قرش واحد للأولى
        val plan = DebtPlan.build(10000L, 3, today)
        assertEquals(3, plan.size)
        assertEquals(listOf(3334L, 3333L, 3333L), plan.map { it.halalas })
        assertEquals(10000L, DebtPlan.total(plan))
        // رصيد لا يقبل القسمة: 10001 على 4 → بقية قرش واحد للأولى حصراً
        val odd = DebtPlan.build(10001L, 4, today)
        assertEquals(listOf(2501L, 2500L, 2500L, 2500L), odd.map { it.halalas })
        assertEquals(10001L, DebtPlan.total(odd))
    }

    @Test
    fun `بقايا القسمة تلحق الدفعات الأولى بالترتيب لا الأخيرة`() {
        val today = ts(2026, 1, 15)
        // عُشر ريال (10 قروش) على 4: 3+3+2+2 — البقايا قرشاً بقرش من الأول
        assertEquals(listOf(3L, 3L, 2L, 2L), DebtPlan.build(10L, 4, today).map { it.halalas })
        // رصيد أصغر من عدد الدفعات: دفعات صفرية موثقة (1 على 3 → الأولى فقط)
        assertEquals(listOf(1L, 0L, 0L), DebtPlan.build(1L, 3, today).map { it.halalas })
        assertEquals(listOf(1L, 1L, 0L, 0L), DebtPlan.build(2L, 4, today).map { it.halalas })
    }

    @Test
    fun `دفعات واحدة تعيد الرصيد كاملا مع تسلسل يبدأ من 1`() {
        val today = ts(2026, 1, 15)
        val single = DebtPlan.build(12345L, 1, today)
        assertEquals(1, single.size)
        assertEquals(12345L, single[0].halalas)
        assertEquals(1, single[0].seq)
        // الحالة الحدية n=1 مع أي رصيد: دفعة واحدة بالرصيد كاملاً بلا زيادة
        assertEquals(7L, DebtPlan.build(7L, 1, today).single().halalas)
    }

    @Test
    fun `عدد الدفعات يقيد إلى المجال من 1 إلى 12 من الطرفين`() {
        val today = ts(2026, 1, 15)
        assertEquals(1, DebtPlan.build(5000L, 0, today).size)
        assertEquals(1, DebtPlan.build(5000L, -3, today).size)
        assertEquals(12, DebtPlan.build(12000L, 13, today).size)
        assertEquals(12, DebtPlan.build(12000L, 99, today).size)
        // التقييد لا يضيّع قروشاً — المجموع يبقى الرصيد بالضبط في كل حالة
        assertEquals(5000L, DebtPlan.total(DebtPlan.build(5000L, -3, today)))
        assertEquals(12000L, DebtPlan.total(DebtPlan.build(12000L, 99, today)))
    }

    @Test
    fun `اثنتا عشرة دفعة متساوية ومواعيدها تصاعدية صارمة عند القبول للقسمة`() {
        val today = ts(2026, 1, 15)
        val plan = DebtPlan.build(120000L, 12, today)
        assertEquals(12, plan.size)
        assertEquals(List(12) { 10000L }, plan.map { it.halalas })
        assertEquals((1..12).toList(), plan.map { it.seq })
        // التصاعد الصارم بين كل موعدين متتاليين
        plan.zipWithNext { a, b -> assertTrue("المواعيد يجب أن تتصاعد", b.dueDate > a.dueDate) }
        assertEquals(120000L, DebtPlan.total(plan))
    }

    @Test
    fun `رصيد صفر أو سالب يعطي خطة فارغة ومجموع الفراغ صفر`() {
        val today = ts(2026, 1, 15)
        assertTrue(DebtPlan.build(0L, 3, today).isEmpty())
        assertTrue(DebtPlan.build(-5L, 3, today).isEmpty())
        assertEquals(0L, DebtPlan.total(emptyList()))
    }

    @Test
    fun `المواعيد شهرية تصاعدية بنفس يوم الشهر`() {
        val today = ts(2026, 1, 10, 9)
        val plan = DebtPlan.build(90000L, 3, today)
        // اليوم 10 → أول استحقاق الشهر التالي ثم شهرياً بنفس اليوم والساعة
        assertEquals(
            listOf(ts(2026, 2, 10, 9), ts(2026, 3, 10, 9), ts(2026, 4, 10, 9)),
            plan.map { it.dueDate }
        )
    }

    @Test
    fun `وقت اليوم يحفظ عبر كل مواعيد الدفع`() {
        // اليوم 14:30:00.500 — فرق اليوم (dayOffset) يُضاف إلى منتصف ليل كل موعد
        val today = ts(2026, 3, 10, 14, 30) + 500L
        val plan = DebtPlan.build(50000L, 2, today)
        assertEquals(ts(2026, 4, 10, 14, 30) + 500L, plan[0].dueDate)
        assertEquals(ts(2026, 5, 10, 14, 30) + 500L, plan[1].dueDate)
    }

    @Test
    fun `اليوم 31 يتقصر في الشهور القصيرة ثم يستأنف ثلاثين`() {
        val jan31 = ts(2026, 1, 31)
        val plan = DebtPlan.build(40000L, 4, jan31)
        // 2026 ليست كبيسة: 28 فبراير ثم 31 مارس ثم 30 أبريل (تقصير) ثم 31 مايو
        assertEquals(ts(2026, 2, 28), plan[0].dueDate)
        assertEquals(ts(2026, 3, 31), plan[1].dueDate)
        assertEquals(ts(2026, 4, 30), plan[2].dueDate)
        assertEquals(ts(2026, 5, 31), plan[3].dueDate)
    }

    @Test
    fun `عقد halalasOf يحول الريال بقرش واحد بنصف القرش HALF_UP`() {
        // التحويل عبر Money.toPiasters: HALF_UP على التمثيل العشري المكتوب حصراً
        assertEquals(12345L, DebtPlan.halalasOf(123.45))
        assertEquals(1L, DebtPlan.halalasOf(0.005))   // نصف القرش يُقرَّب بعيداً عن الصفر
        assertEquals(0L, DebtPlan.halalasOf(0.004))   // أقل من نصف قرش يسقط
        assertEquals(268L, DebtPlan.halalasOf(2.675)) // 2.675 ثنائياً 2.67499… لكن المكتوب يحسم
        assertEquals(-150L, DebtPlan.halalasOf(-1.5))
        assertEquals(-1L, DebtPlan.halalasOf(-0.005)) // السالب أيضاً بعيداً عن الصفر
        // غير المنتهي → صفر قروش (لا انهيار)
        assertEquals(0L, DebtPlan.halalasOf(Double.NaN))
        assertEquals(0L, DebtPlan.halalasOf(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `riyals وhalalasOf عكسان بلا فقد قروش في النطاق العملي`() {
        // العقد: قروش Long → ريال → قروش Long دائرة مقفلة بقرش واحد
        val samples = listOf(1L, 2L, 99L, 100L, 101L, 12345L, 999_999L, 100_000_000L)
        for (p in samples) {
            assertEquals("عكس القروش $p فشل", p, DebtPlan.halalasOf(DebtPlan.riyals(p)))
        }
        assertEquals(123.45, DebtPlan.riyals(12345L), 0.0)
    }

    @Test
    fun `تسلسل الدفعات يبدأ من 1 وينتهي بعدد الدفعات`() {
        val today = ts(2026, 1, 15)
        assertEquals(listOf(1, 2, 3), DebtPlan.build(10000L, 3, today).map { it.seq })
        assertEquals((1..12).toList(), DebtPlan.build(600L, 12, today).map { it.seq })
    }

    // ══ 2) ChecksArchive — أرشفة الشيكات ══

    @Test
    fun `مرشحو الأرشفة هم الشيكات المحصلة حصرا`() {
        val now = ts(2026, 1, 15)
        val all = listOf(
            check(1, status = 0, dueDate = now), // قيد التحصيل
            check(2, status = 1, dueDate = now), // مودع
            check(3, status = 2, dueDate = now), // محصّل — المرشح الوحيد
            check(4, status = 3, dueDate = now), // مرتجع
            check(5, status = 4, dueDate = now)  // ملغى
        )
        assertEquals(listOf(3L), ChecksArchive.candidates(all).map { it.id })
        assertTrue(ChecksArchive.candidates(all).all { it.status == 2 })
        assertTrue(ChecksArchive.candidates(emptyList()).isEmpty())
    }

    @Test
    fun `الفصل بالمحفوظات يحفظ الترتيب ويتسامح مع معرفات مجهولة`() {
        val now = ts(2026, 1, 15)
        val all = listOf(
            check(1, status = 0, dueDate = now),
            check(2, status = 2, dueDate = now),
            check(3, status = 3, dueDate = now),
            check(4, status = 2, dueDate = now),
            check(5, status = 1, dueDate = now)
        )
        val (active, archived) = ChecksArchive.split(all, setOf(2L, 4L, 99L))
        // الترتيب الأصلي محفوظ في القسمين، والمعرف المجهول 99 لا يضر
        assertEquals(listOf(1L, 3L, 5L), active.map { it.id })
        assertEquals(listOf(2L, 4L), archived.map { it.id })
        // بلا أرشيف: الكل نشط؛ وقائمة فارغة تعطي قسمين فارغين
        val (a2, arc2) = ChecksArchive.split(all, emptySet())
        assertEquals(all, a2)
        assertTrue(arc2.isEmpty())
        val (a3, arc3) = ChecksArchive.split(emptyList(), setOf(1L))
        assertTrue(a3.isEmpty() && arc3.isEmpty())
    }

    // ══ 3) DebtSort — مقارنات الفرز ══

    @Test
    fun `الوضع الافتراضي يرتب بالقيمة المطلقة للرصيد تنازليا`() {
        val rows = listOf(
            DebtSort.SortRow(1, "أ", 300L, null),
            DebtSort.SortRow(2, "ب", -800L, null),
            DebtSort.SortRow(3, "ج", 500L, null)
        )
        val sorted = rows.sortedWith(DebtSort.comparator(DebtSort.MODE_DEFAULT) { it })
        // |−800| أكبر من 500 — الدائن الكبير يتصدر السلوك القائم
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.partyId })
        // التعادل المطلق (500 مقابل −500) لا يكسر الاستقرار
        val tie = listOf(
            DebtSort.SortRow(7, "س", 500L, null),
            DebtSort.SortRow(8, "ص", -500L, null)
        )
        val cmp: Comparator<DebtSort.SortRow> = DebtSort.comparator(DebtSort.MODE_DEFAULT) { it }
        assertEquals(0, cmp.compare(tie[0], tie[1]))
    }

    @Test
    fun `وضع الأقدمية يقدم الأقدم ويؤخر من بلا آجلة والتعادل بالأعلى رصيدا`() {
        val rows = listOf(
            DebtSort.SortRow(1, "أ", 100L, ts(2025, 6, 1)),  // الأقدم
            DebtSort.SortRow(2, "ب", 900L, ts(2025, 6, 1)),  // تعادل الأقدمية → الرصيد الأعلى أولاً
            DebtSort.SortRow(3, "ج", 500L, ts(2025, 9, 1)),
            DebtSort.SortRow(4, "د", 9999L, null),           // بلا آجلة → أخيراً
            DebtSort.SortRow(5, "هـ", 100L, null)            // بلا آجلة → تعادل بالرصيد الأعلى
        )
        val sorted = rows.sortedWith(DebtSort.comparator(DebtSort.MODE_OLDEST) { it })
        assertEquals(listOf(2L, 1L, 3L, 4L, 5L), sorted.map { it.partyId })
    }

    @Test
    fun `وضع أعلى رصيد يرتب تنازليا دون قيمة مطلقة`() {
        val rows = listOf(
            DebtSort.SortRow(1, "أ", 300L, null),
            DebtSort.SortRow(2, "ب", -800L, null),
            DebtSort.SortRow(3, "ج", 500L, null)
        )
        val sorted = rows.sortedWith(DebtSort.comparator(DebtSort.MODE_BALANCE) { it })
        // بلا abs: الدائن (−800) أخيراً — عكس الوضع الافتراضي
        assertEquals(listOf(3L, 1L, 2L), sorted.map { it.partyId })
    }

    @Test
    fun `وضع الاسم يقارن بعد التطبيع العربي ويعادل المتطابقات`() {
        val rows = listOf(
            DebtSort.SortRow(3, "إبراهيم", 10L, null),
            DebtSort.SortRow(1, "أحمد", 20L, null),
            DebtSort.SortRow(4, "بدر", 30L, null),
            DebtSort.SortRow(2, "سالم", 40L, null),
            DebtSort.SortRow(5, "نور", 50L, null)
        )
        val sorted = rows.sortedWith(DebtSort.comparator(DebtSort.MODE_NAME) { it })
        // التطبيع: إ→ا فيصير ابراهيم قبل احمد (ب قبل ح)
        assertEquals(listOf(3L, 1L, 4L, 2L, 5L), sorted.map { it.partyId })
        // التعادل بعد التطبيع: التشكيل يُهمَل وة→ه — المقارنة تعيد صفراً
        val cmp: Comparator<DebtSort.SortRow> = DebtSort.comparator(DebtSort.MODE_NAME) { it }
        assertEquals(0, cmp.compare(DebtSort.SortRow(9, "علي", 0L, null), DebtSort.SortRow(10, "عَلي", 0L, null)))
        assertEquals(0, cmp.compare(DebtSort.SortRow(11, "سارة", 0L, null), DebtSort.SortRow(12, "ساره", 0L, null)))
    }

    @Test
    fun `خريطة الأقدم تبنى من فواتير البيع المفتوحة حصرا`() {
        val invoices = listOf(
            inv(1, partyId = 1, date = ts(2025, 5, 1), total = 10000L, status = 0),                     // مفتوحة
            inv(2, partyId = 1, date = ts(2025, 4, 1), total = 10000L, paid = 3000L, status = 1),       // جزئية مفتوحة — الأقدم للطرف 1
            inv(3, partyId = 1, date = ts(2025, 3, 1), total = 10000L, status = 2),                     // مسددة — تستبعد
            inv(4, partyId = 2, date = ts(2025, 8, 1), total = 10000L, status = 3),                     // ملغاة — تستبعد
            inv(5, partyId = 3, date = ts(2025, 7, 1), total = 10000L, type = 1),                       // شراء — يستبعد
            inv(6, partyId = 4, date = ts(2025, 6, 1), total = 5000L, paid = 5000L, status = 1),        // مفتوح = 0 — تستبعد
            inv(7, partyId = 2, date = ts(2025, 9, 1), total = 2000L, status = 0)                       // الوحيدة المفتوحة للطرف 2
        )
        val map = DebtSort.oldestOpenMap(invoices)
        assertEquals(2, map.size)
        assertEquals(ts(2025, 4, 1), map[1L]) // الأقدم بين المفتوحتين 5/4
        assertEquals(ts(2025, 9, 1), map[2L])
        assertNull(map[3L])
        assertNull(map[4L])
        // بلا فواتير مفتوحة إطلاقاً → خريطة فارغة صادقة
        assertTrue(DebtSort.oldestOpenMap(emptyList()).isEmpty())
        assertTrue(DebtSort.oldestOpenMap(listOf(inv(9, 1, ts(2025, 1, 1), 100L, status = 2))).isEmpty())
    }

    // ══ 4) ChecksWeek — شيكات الأسبوع ══

    @Test
    fun `weekStats تعد المستحق خلال سبعة أيام والمتأخر للمفتوح حصرا`() {
        val now = ts(2026, 1, 15)
        val checks = listOf(
            check(1, status = 0, dueDate = ts(2026, 1, 15, 0)),  // مستحق اليوم (منتصف الليل) — قريب
            check(2, status = 1, dueDate = ts(2026, 1, 22)),     // اليوم السابع بالضبط — قريب (شامل)
            check(3, status = 0, dueDate = ts(2026, 1, 23)),     // اليوم الثامن — خارج
            check(4, status = 0, dueDate = ts(2026, 1, 14)),     // متأخر
            check(5, status = 1, dueDate = ts(2026, 1, 22, 18)), // ضمن اليوم السابع بأي وقت — قريب
            check(6, status = 2, dueDate = ts(2026, 1, 10)),     // محصّل — خارج الإحصاء
            check(7, status = 3, dueDate = ts(2026, 1, 10)),     // مرتجع — خارج
            check(8, status = 4, dueDate = ts(2026, 1, 10))      // ملغى — خارج
        )
        val (dueSoon, overdue) = ChecksWeek.weekStats(checks, now)
        assertEquals(3, dueSoon)
        assertEquals(1, overdue)
    }

    @Test
    fun `شيك اليوم يقع في عداد الأسبوع لا المتأخر`() {
        val now = ts(2026, 1, 15, 12)
        // مستحق الآن (بوقت اليوم): بداية يومه = بداية اليوم الحالي → قريب لا متأخر
        val (dueSoon, overdue) = ChecksWeek.weekStats(listOf(check(1, dueDate = now)), now)
        assertEquals(1, dueSoon)
        assertEquals(0, overdue)
        // قبل بداية اليوم بميلّي واحدة: بداية يومه أمس → متأخر لا قريب
        // (ts بلا ساعة = الظهر افتراضياً — يجب تمرير 0 للحصول على منتصف الليل)
        val (d2, o2) = ChecksWeek.weekStats(listOf(check(2, dueDate = ts(2026, 1, 15, 0) - 1)), now)
        assertEquals(0, d2)
        assertEquals(1, o2)
    }

    @Test
    fun `predicate يمرر المفتوح حتى حد الأسبوع والمتأخر ويستبعد المغلق`() {
        val now = ts(2026, 1, 15)
        val checks = listOf(
            check(1, status = 0, dueDate = ts(2026, 1, 15, 0)),
            check(2, status = 1, dueDate = ts(2026, 1, 22)),
            check(3, status = 0, dueDate = ts(2026, 1, 23)),   // بعد الأسبوع
            check(4, status = 0, dueDate = ts(2026, 1, 14)),   // متأخر مفتوح
            check(5, status = 2, dueDate = ts(2026, 1, 15)),   // محصّل مستحق اليوم — مغلق
            check(6, status = 3, dueDate = ts(2026, 1, 10)),   // مرتجع متأخر — مغلق
            check(7, status = 1, dueDate = ts(2026, 1, 22, 23))// آخر لحظات اليوم السابع
        )
        val filtered = checks.filter(ChecksWeek.predicate(now))
        assertEquals(listOf(1L, 2L, 4L, 7L), filtered.map { it.id })
    }

    // ══ 5) ChecksIcs — تقويم VCALENDAR ══

    @Test
    fun `بنية VCALENDAR مكتملة وكل أسطرها CRLF`() {
        val now = ts(2026, 1, 15)
        val checks = listOf(
            check(1, status = 0, dueDate = now + day),
            check(2, status = 1, dueDate = now + 2 * day)
        )
        val ics = ChecksIcs.build(checks, { "طرف" }, 0L) // طابع صريح — لا ساعة حائط
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.contains("VERSION:2.0\r\n"))
        assertTrue(ics.contains("PRODID:-//SuperBiz//Checks Calendar//AR\r\n"))
        assertTrue(ics.contains("CALSCALE:GREGORIAN\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertEquals(2, Regex("END:VEVENT").findAll(ics).count())
        // لا سطر واحد بـ\n مفرد — كل الأسطر CRLF
        assertTrue(!Regex("(?<!\\r)\\n").containsMatchIn(ics))
    }

    @Test
    fun `حدث لكل شيك مفتوح ومعرف ثابت والمحصل بلا حدث`() {
        val now = ts(2026, 1, 15)
        val checks = listOf(
            check(1, status = 0, dueDate = now + day, number = "101"),
            check(2, status = 2, dueDate = now + day, number = "102"), // محصّل — لا VEVENT
            check(3, status = 1, dueDate = now + day, number = "103")
        )
        val ics = ChecksIcs.build(checks, { "طرف" }, 0L)
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        // UID ثابت مشتق من معرف الشيك لا من الزمن
        assertTrue(ics.contains("UID:check-1@superbiz.app\r\n"))
        assertTrue(ics.contains("UID:check-3@superbiz.app\r\n"))
        assertTrue(!ics.contains("check-2@"))
        // الحتمية: المدخل نفسه والطابع نفسه → مخرج متطابق حرفياً (UID ثابت عبر الاستدعاءات)
        assertEquals(ics, ChecksIcs.build(checks, { "طرف" }, 0L))
    }

    @Test
    fun `DTSTART تاريخ محلي أساسي وDTSTAMP من الطابع المرر بصيغة UTC`() {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(2026, 0, 15, 6, 30, 45)
        val checks = listOf(check(7, status = 0, dueDate = ts(2026, 2, 1), number = "55"))
        val ics = ChecksIcs.build(checks, { "شركة النور" }, utc.timeInMillis)
        // DTSTART;VALUE=DATE بالصيغة الأساسية YYYYMMDD (تاريخ محلي، منتصف النهار آمن من الإزاحات)
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260201\r\n"))
        // DTSTAMP يلتقط الطابع الممرر حصراً — 2026-01-15T06:30:45Z
        assertTrue(ics.contains("DTSTAMP:20260115T063045Z\r\n"))
        // وحدات التحويل: الصفر = 1970 بوحدة UTC الأساسية
        assertEquals("19700101T000000Z", ChecksIcs.utcBasic(0L))
        assertEquals("20260201", ChecksIcs.localDate(ts(2026, 2, 1)))
    }

    @Test
    fun `escape يهرب المائل ثم الفاصلة المنقوطة والفاصلة والأسطر`() {
        assertEquals("a\\,b\\;c", ChecksIcs.escape("a,b;c"))
        assertEquals("سطر\\nثاني", ChecksIcs.escape("سطر\nثاني"))
        assertEquals("a\\nb\\nc", ChecksIcs.escape("a\r\nb\rc"))
        assertEquals("م\\,\\;\\\\ن", ChecksIcs.escape("م,;\\ن"))
        assertEquals("plain", ChecksIcs.escape("plain"))
        // ترتيب التهريب: المائل أولاً — المائل+فاصلة منقوطة يخرج ثلاثة مائلة ثم ;
        assertEquals("x\\\\\\;y", ChecksIcs.escape("x\\;y"))
        // عبر build: طرف حاوي محارف خاصة يُهرَّب في SUMMARY وDESCRIPTION
        val checks = listOf(check(9, status = 0, dueDate = ts(2026, 2, 1), amount = 95000L, number = "9"))
        val ics = ChecksIcs.build(checks, { "نور,وحيد;ابن\\علي" }, 0L)
        assertTrue(ics.contains("SUMMARY:شيك #9 — نور\\,وحيد\\;ابن\\\\علي — 950\r\n"))
        assertTrue(ics.contains("طرف: نور\\,وحيد\\;ابن\\\\علي"))
    }

    @Test
    fun `الملخص والوصف يحملان الطرف والبنك والمبلغ والاستحقاق مهربين`() {
        val checks = listOf(
            check(55, status = 0, dueDate = ts(2026, 2, 1), amount = 95000L,
                number = "55", partyId = 1, bank = "بنك الرياض"),
            check(8, status = 1, dueDate = ts(2026, 2, 2), amount = 250005L,
                number = "8", partyId = 2, bank = "")
        )
        val ics = ChecksIcs.build(checks, { if (it == 1L) "شركة النور" else "أحمد" }, 0L)
        // numP للقروش: 95000 → «950» بلا كسور
        assertTrue(ics.contains("SUMMARY:شيك #55 — شركة النور — 950\r\n"))
        // فاصل الآلاف في 250005 («2,500.05») يُهرَّب أيضاً داخل SUMMARY
        assertTrue(ics.contains("— 2\\,500.05\r\n"))
        // الوصف: الطرف ثم البنك إن وجد ثم المبلغ ثم الاستحقاق dd/MM/yyyy
        assertTrue(ics.contains("طرف: شركة النور | بنك: بنك الرياض | مبلغ: 950 | استحقاق: 01/02/2026"))
        // بلا بنك: لا مقطع «بنك:» (واحد فقط للشيك الأول)
        assertEquals(1, Regex("بنك:").findAll(ics).count())
        // التهريب يمر على الوصف كله — حتى فاصل الآلاف في المبلغ يُهرَّب داخل DESCRIPTION
        assertTrue(ics.contains("طرف: أحمد | مبلغ: 2\\,500.05 | استحقاق: 02/02/2026"))
    }

    // ══ 6) EarlyPay — خصم السداد المبكر ══

    @Test
    fun `quote بنسبة 2 يحدد المدفوع والوفر بقرش واحد`() {
        // 2% من 100000 قروش (1000 ريال): وفر 2000 ودفع 98000
        val q = EarlyPay.quote(100000L)!!
        assertEquals(98000L, q.first)
        assertEquals(2000L, q.second)
        assertEquals(2.0, EarlyPay.RATE_PCT, 0.0)
        // تقريب النسبة: 125×2% = 2.5 → Math.round نصف بعيداً → وفر 3 ودفع 122
        assertEquals(122L to 3L, EarlyPay.quote(125L))
        // 101×2% = 2.02 → وفر 2 ودفع 99
        assertEquals(99L to 2L, EarlyPay.quote(101L))
    }

    @Test
    fun `quote بلا عرض لمبلغ غير موجب`() {
        assertNull(EarlyPay.quote(0L))
        assertNull(EarlyPay.quote(-1L))
        assertNull(EarlyPay.quote(-1000L))
    }

    @Test
    fun `quote بلا عرض لنسبة خارج النطاق أو غير منتهية`() {
        assertNull(EarlyPay.quote(10000L, 0.0))
        assertNull(EarlyPay.quote(10000L, 100.0))
        assertNull(EarlyPay.quote(10000L, -2.0))
        assertNull(EarlyPay.quote(10000L, Double.NaN))
        assertNull(EarlyPay.quote(10000L, Double.POSITIVE_INFINITY))
        // تحت 100 مباشرة: عرض شرعي — 10000 بخصم 99% → وفر 9900 ودفع 100
        assertEquals(100L to 9900L, EarlyPay.quote(10000L, 99.0))
    }

    @Test
    fun `quote يحرس الدفع الموجب عند النسب العالية والمبالغ الضئيلة`() {
        // 1 قرش بخصم 99% → الوفر يلتهم المبلغ كله (payable = 0) → لا عرض
        assertNull(EarlyPay.quote(1L, 99.0))
        // 3 قروش بخصم 66%: 1.98 → وفر 2 ودفع 1
        assertEquals(1L to 2L, EarlyPay.quote(3L, 66.0))
        // 2 قروش بخصم 50%: وفر 1 ودفع 1
        assertEquals(1L to 1L, EarlyPay.quote(2L, 50.0))
        // 1000 بخصم 99.9%: 999 → دفع 1
        assertEquals(1L to 999L, EarlyPay.quote(1000L, 99.9))
    }

    @Test
    fun `eligible قبل الاستحقاق حصرا وللأقساط غير المسددة كليا`() {
        val due = ts(2026, 2, 1)
        val today = ts(2026, 1, 15)
        assertTrue(EarlyPay.eligible(100000L, 0L, due, today))            // مفتوح قبل الاستحقاق
        assertTrue(EarlyPay.eligible(100000L, 20000L, due, today))        // مسدد جزئياً — مؤهل
        assertTrue(EarlyPay.eligible(100000L, 0L, due, ts(2026, 1, 31, 23, 59))) // آخر لحظة قبل يوم الاستحقاق
        assertTrue(!EarlyPay.eligible(100000L, 0L, due, due))             // يوم الاستحقاق ليس «مبكراً»
        assertTrue(!EarlyPay.eligible(100000L, 0L, due, due + day))       // بعد الاستحقاق
        assertTrue(!EarlyPay.eligible(100000L, 100000L, due, today))      // مسدد كلياً
        assertTrue(!EarlyPay.eligible(100000L, 120000L, due, today))      // مسدد زائداً
    }
}
