package com.superbiz.app

import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.domain.ChecksArchive
import com.superbiz.app.domain.ChecksIcs
import com.superbiz.app.domain.ChecksWeek
import com.superbiz.app.domain.DebtAging
import com.superbiz.app.domain.DebtPlan
import com.superbiz.app.domain.DebtSort
import com.superbiz.app.domain.EarlyPay
import com.superbiz.app.domain.TopDebtors
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * : اختبارات موجة الذمم + الشيكات + الأقساط —
 * خطة السداد (قسمة القرش وتقصير الأشهر)، أرشفة الشيكات، مقارنات الفرز،
 * شيكات الأسبوع، بنّاء ICS (بنية/تواريخ/تهريب)، أعمار الذمم وأوزانها،
 * أعلى المدينين وحصصهم، وخصم السداد المبكر.
 * [P33-P8] مبالغ الكيانات قروش Long — والدوال العرضية الريالية (weights/TopDebtors/riyals) بقيت Double.
*/
class DebtsChecksP4Test {

    private val day = 86_400_000L

    /** توقيت ثابت: 2026-01-31 12:00 محلياً — لبناء تواريخ حتمية */
    private fun ts(year: Int, month1to12: Int, dayOfMonth: Int, hour: Int = 12): Long {
        val c = java.util.Calendar.getInstance()
        c.clear()
        c.set(year, month1to12 - 1, dayOfMonth, hour, 0, 0)
        return c.timeInMillis
    }

    // [P33-P8] amount قروش Long (كان 100.0 ريال = 10000 قروشاً)
    private fun check(
        id: Long, status: Int = 0, dueDate: Long, amount: Long = 10000L,
        number: String = "C-$id", partyId: Long = 1, bank: String = ""
    ) = CheckEntity(
        id = id, number = number, partyId = partyId, bank = bank,
        amount = amount, issueDate = dueDate - 30 * day, dueDate = dueDate,
        direction = 0, status = status
    )

    // ══ 1) خطة السداد: قسمة القرش ══

    @Test
    fun plan_pennySplit_sumsExactlyToBalance() {
        val today = ts(2026, 1, 15)
        // 100.00 على 3 دفعات: 3334 + 3333 + 3333 هللة — البقايا قرشاً بقرش للأوائل
        val plan = DebtPlan.build(10000L, 3, today)
        assertEquals(3, plan.size)
        assertEquals(10000L, DebtPlan.total(plan))
        assertEquals(3334L, plan[0].halalas)
        assertEquals(3333L, plan[1].halalas)
        assertEquals(3333L, plan[2].halalas)
        // مثال العُشر: 0.10 على 4 دفعات → 3+3+2+2 هللة
        val tiny = DebtPlan.build(10L, 4, today)
        assertEquals(10L, DebtPlan.total(tiny))
        assertEquals(listOf(3L, 3L, 2L, 2L), tiny.map { it.halalas })
    }

    @Test
    fun plan_monthlyDates_clampShortMonths() {
        // 31 يناير → التالي 28 فبراير (تقصير) → ثم 31 مارس
        val jan31 = ts(2026, 1, 31)
        val plan = DebtPlan.build(20000L, 3, jan31)
        val feb28 = ts(2026, 2, 28)
        val mar31 = ts(2026, 3, 31)
        assertEquals(feb28, plan[0].dueDate)
        assertEquals(mar31, plan[1].dueDate)
        assertEquals(ts(2026, 4, 30), plan[2].dueDate) // 31 أبريل غير موجود → 30
    }

    @Test
    fun plan_bounds_n1_n12_andRejectsNonPositiveBalance() {
        val today = ts(2026, 1, 15)
        // N = 1: دفعة واحدة كاملة
        val single = DebtPlan.build(12345L, 1, today)
        assertEquals(1, single.size)
        assertEquals(12345L, single[0].halalas)
        // N = 12: اثنتا عشرة دفعة بمجموع كامل
        val twelve = DebtPlan.build(12000L, 12, today)
        assertEquals(12, twelve.size)
        assertEquals(12000L, DebtPlan.total(twelve))
        // الحدود: n أقل من 1 يُقيَّد إلى 1، وأكبر من 12 يُقيَّد إلى 12
        assertEquals(1, DebtPlan.build(5000L, 0, today).size)
        assertEquals(12, DebtPlan.build(12000L, 99, today).size)
        // رصيد غير موجب → خطة فارغة (صدق الفراغ)
        assertTrue(DebtPlan.build(0L, 3, today).isEmpty())
        assertTrue(DebtPlan.build(-5L, 3, today).isEmpty())
        // تحويلات الهللات المستقرة
        assertEquals(12345L, DebtPlan.halalasOf(123.45))
        assertEquals(123.45, DebtPlan.riyals(12345L), 1e-9)
    }

    // ══ 2) أرشفة الشيكات (بديل R2) ══

    @Test
    fun archive_candidates_clearedOnly_andSplitPreservesOrder() {
        val now = ts(2026, 1, 15)
        val all = listOf(
            check(1, status = 0, dueDate = now),      // قيد التحصيل — نشط
            check(2, status = 2, dueDate = now),      // محصّل — مرشح
            check(3, status = 3, dueDate = now),      // مرتجع — غير مرشح
            check(4, status = 2, dueDate = now),      // محصّل — مرشح
            check(5, status = 1, dueDate = now)       // مودع — نشط
        )
        assertEquals(listOf(2L, 4L), ChecksArchive.candidates(all).map { it.id })
        val (active, archived) = ChecksArchive.split(all, setOf(2L, 4L, 99L))
        assertEquals(listOf(1L, 3L, 5L), active.map { it.id })
        assertEquals(listOf(2L, 4L), archived.map { it.id })
        // بلا أرشيف: كل القائمة نشطة
        val (a2, arc2) = ChecksArchive.split(all, emptySet())
        assertEquals(5, a2.size)
        assertTrue(arc2.isEmpty())
    }

    // ══ 3) فرز الذمم ══

    @Test
    fun sort_oldest_firstNullsLast_thenBalance_thenNameNormalized() {
        // [P33-P8] الرصيد قروش Long — الفرز رتيب فلا يتغير الترتيب
        val rows = listOf(
            DebtSort.SortRow(1, "سالم", 10000L, ts(2025, 6, 1)),   // الأقدم
            DebtSort.SortRow(2, "نور", 20000L, null),              // بلا آجلة → أخيراً
            DebtSort.SortRow(3, "أحمد", 30000L, ts(2025, 9, 1)),
            DebtSort.SortRow(4, "بدر", 5000L, ts(2025, 9, 1))
        )
        val byOldest = rows.sortedWith(DebtSort.comparator(DebtSort.MODE_OLDEST) { it })
        assertEquals(listOf(1L, 3L, 4L, 2L), byOldest.map { it.partyId }) // تعادل 3/4 بالرصيد
        val byBalance = rows.sortedWith(DebtSort.comparator(DebtSort.MODE_BALANCE) { it })
        assertEquals(listOf(3L, 2L, 1L, 4L), byBalance.map { it.partyId })
        // الاسم بعد التطبيع العربي: أحمد قبل بدر قبل سالم قبل نور
        val byName = rows.sortedWith(DebtSort.comparator(DebtSort.MODE_NAME) { it })
        assertEquals(listOf(3L, 4L, 1L, 2L), byName.map { it.partyId })
        // أقدم فاتورة مفتوحة لكل طرف — فواتير البيع غير المسددة فقط
        val invoices = listOf(
            inv(10, partyId = 1, date = ts(2025, 5, 1), total = 10000L, status = 0),
            inv(11, partyId = 1, date = ts(2025, 4, 1), total = 10000L, status = 0),
            inv(12, partyId = 1, date = ts(2025, 3, 1), total = 10000L, status = 2),  // مسددة — تُستبعد
            inv(13, partyId = 2, date = ts(2025, 8, 1), total = 10000L, status = 3),  // ملغاة — تُستبعد
            inv(14, partyId = 3, date = ts(2025, 7, 1), total = 10000L, status = 0, type = 1) // شراء — يُستبعد
        )
        val map = DebtSort.oldestOpenMap(invoices)
        assertEquals(ts(2025, 4, 1), map[1L])
        assertNull(map[2L])
        assertNull(map[3L])
    }

    // [P33-P8] total قروش Long (كان Double ريال)
    private fun inv(
        id: Long, partyId: Long, date: Long, total: Long,
        status: Int = 0, type: Int = 0
    ) = Invoice(
        id = id, number = "INV-$id", partyId = partyId, type = type,
        date = date, dueDate = date, subtotal = total, total = total, status = status
    )

    // ══ 4) شيكات هذا الأسبوع ══

    @Test
    fun weekStats_countsDueSoonAndOverdue_onlyOpenChecks() {
        val now = ts(2026, 1, 15)
        val checks = listOf(
            check(1, status = 0, dueDate = now + 3 * day),   // خلال الأسبوع
            check(2, status = 1, dueDate = now + 7 * day),   // آخر اليوم السابع — ضمن الأسبوع
            check(3, status = 0, dueDate = now + 8 * day),   // بعد الأسبوع
            check(4, status = 0, dueDate = now - 2 * day),   // متأخر
            check(5, status = 2, dueDate = now - 9 * day),   // محصّل — يُستبعد من المتأخر
            check(6, status = 3, dueDate = now - 5 * day)    // مرتجع — يُستبعد
        )
        val (dueSoon, overdue) = ChecksWeek.weekStats(checks, now)
        assertEquals(2, dueSoon)
        assertEquals(1, overdue)
        // مسند الترشيح: مفتوحة وتستحق خلال 7 أيام أو متأخرة
        val filtered = checks.filter(ChecksWeek.predicate(now))
        assertEquals(listOf(1L, 2L, 4L), filtered.map { it.id })
    }

    // ══ 5) بنّاء ICS ══

    @Test
    fun ics_structure_eventsPerOpenCheck_crlf() {
        val now = ts(2026, 1, 15)
        val stamp = ts(2026, 1, 15, 9)
        val checks = listOf(
            check(1, status = 0, dueDate = now + day, number = "101", partyId = 1, amount = 150050L),  // [P33-P8] كان 1500.5 ريال
            check(2, status = 2, dueDate = now + day, number = "102"),   // محصّل — لا حدث له
            check(3, status = 1, dueDate = now + 2 * day, number = "103", partyId = 2, bank = "بنك الرياض")
        )
        val ics = ChecksIcs.build(checks, { pid -> if (pid == 1L) "شركة النور" else "أحمد" }, stamp)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.contains("VERSION:2.0\r\n"))
        assertTrue(ics.contains("PRODID:-//SuperBiz//Checks Calendar//AR\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertEquals(2, Regex("END:VEVENT").findAll(ics).count())
        // حقل واحد على الأقل لكل حدث: UID + DTSTAMP + DTSTART + SUMMARY لكل VEVENT
        assertEquals(2, Regex("UID:check-\\d+@superbiz\\.app").findAll(ics).count())
        assertEquals(2, Regex("DTSTART;VALUE=DATE:\\d{8}").findAll(ics).count())
        assertEquals(2, Regex("SUMMARY:").findAll(ics).count())
        assertEquals(2, Regex("DESCRIPTION:").findAll(ics).count())
        // لا سطر مفرد \n — كل الأسطر CRLF
        assertTrue(!Regex("(?<!\r)\n").containsMatchIn(ics))
    }

    @Test
    fun ics_dates_and_summaryContent() {
        val now = ts(2026, 1, 15)
        val checks = listOf(
            check(7, status = 0, dueDate = ts(2026, 2, 1), number = "55", partyId = 1, amount = 95000L)  // [P33-P8] كان 950.0 ريال
        )
        val ics = ChecksIcs.build(checks, { "شركة النور" }, now)
        // DTSTART بصيغة التاريخ الأساسية YYYYMMDD — 1 فبراير 2026
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260201\r\n"))
        // الملخص: شيك #رقم — طرف — مبلغ (numP لقروش صحيحة بدون كسور تطبع بدون خانتين عشريتين)
        assertTrue(ics.contains("SUMMARY:شيك #55 — شركة النور — 950\r\n"))
        assertTrue(ics.contains("UID:check-7@superbiz.app\r\n"))
    }

    @Test
    fun ics_escaping_comma_semicolon_newline_backslash() {
        assertEquals("a\\,b\\;c", ChecksIcs.escape("a,b;c"))
        assertEquals("سطر\\nثاني", ChecksIcs.escape("سطر\nثاني"))
        assertEquals("م\\,\\;\\\\ن", ChecksIcs.escape("م,;\\ن"))
        assertEquals("plain", ChecksIcs.escape("plain"))
    }

    // ══ 6) أعمار الذمم وأوزان الشريط ══

    @Test
    fun aging_buckets_fromOpenSaleInvoices_boundaries() {
        val today = ts(2026, 1, 15)
        val invoices = listOf(
            inv(1, 1, date = today - 10 * day, total = 10000L),               // 0-30
            inv(2, 1, date = today - 30 * day, total = 20000L),               // حد 30 → الأول
            inv(3, 1, date = today - 31 * day, total = 40000L),               // حد 31 → الثاني
            inv(4, 1, date = today - 61 * day, total = 80000L),               // 61+ → الثالث
            inv(5, 1, date = today - 91 * day, total = 160000L),              // 91+ → الرابع
            inv(6, 1, date = today - 5 * day, total = 99900L, status = 2),    // مسددة — تُستبعد
            inv(7, 1, date = today - 60 * day, total = 5000L, type = 1)       // شراء — يُستبعد
        )
        val buckets = DebtAging.bucketsFromInvoices(invoices, today)
        assertEquals(4, buckets.size)
        assertEquals(300.0, buckets[0], 1e-9)
        assertEquals(400.0, buckets[1], 1e-9)
        assertEquals(800.0, buckets[2], 1e-9)
        assertEquals(1600.0, buckets[3], 1e-9)
        // الأوزان: 300/3100 ≈ 0.0968 لكل شريط، والصفر بلا مديونية
        val w = DebtAging.weights(buckets)
        assertEquals(300.0 / 3100.0, w[0].toDouble(), 1e-6)
        assertEquals(1600.0 / 3100.0, w[3].toDouble(), 1e-6)
        assertTrue(DebtAging.weights(listOf(0.0, 0.0, 0.0, 0.0)).all { it == 0f })
        assertTrue(DebtAging.weights(emptyList()).isEmpty())
    }

    // ══ 7) أعلى المدينين ══

    @Test
    fun topDebtors_order_shares_andHonestEmpty() {
        val rows = listOf(
            "أ" to 500.0, "ب" to 300.0, "ج" to 100.0,
            "د" to 50.0, "هـ" to 50.0, "و" to 40.0,   // سادس خارج أعلى 5
            "ز" to -70.0,                              // دائن — لا يُعد مدينًا
            "ح" to 0.0                                 // موازن — لا يُعد
        )
        val top = TopDebtors.top(rows)
        assertEquals(5, top.size)
        assertEquals("أ", top[0].name)
        assertEquals(500.0, top[0].balance, 1e-9)
        // الحصة من إجمالي المديونية الكلي (1040) لا من أعلى 5 — مقرّبة قرشاً بقرش
        assertEquals(48.08, top[0].sharePct, 1e-9)   // 500/1040 = 48.0769… → 48.08
        assertEquals(4.81, top[4].sharePct, 1e-9)    // 50/1040  = 4.8076…  → 4.81
        // مجموع حصص أعلى 5 ≤ 100
        assertTrue(top.sumOf { it.sharePct } <= 100.0 + 1e-9)
        // التعادل بالاسم: د قبل هـ
        assertTrue(top.indexOfFirst { it.name == "د" } < top.indexOfFirst { it.name == "هـ" })
        // إخفاء صادق: بلا مدينين → قائمة فارغة
        assertTrue(TopDebtors.top(listOf("أ" to -5.0, "ب" to 0.0)).isEmpty())
        assertTrue(TopDebtors.top(emptyList()).isEmpty())
    }

    // ══ 8) خصم السداد المبكر ══

    @Test
    fun earlyPay_quote_fixedRate_andGuards() {
        // [P33-P8] 2% من 100000 قروش (1000 ريال) → يوفّر 2000 ويدفع 98000 — مقارنات صحيحة تامة
        val q = EarlyPay.quote(100000L)!!
        assertEquals(98000L, q.first)
        assertEquals(2000L, q.second)
        assertEquals(EarlyPay.RATE_PCT, 2.0, 1e-9)
        // المفتوح الصغير/الصفر → لا عرض — [P33-P8] أقل من قرش يُحوَّل صفراً via toPiasters (كان ≤ EPS 0.005)
        assertNull(EarlyPay.quote(0L))
        assertNull(EarlyPay.quote(Money.toPiasters(0.001)))
        // نسبة غير صالحة → لا عرض
        assertNull(EarlyPay.quote(10000L, 0.0))
        assertNull(EarlyPay.quote(10000L, 100.0))
        assertNull(EarlyPay.quote(10000L, Double.NaN))
    }

    @Test
    fun earlyPay_eligible_onlyBeforeDueDate_andNotFullyPaid() {
        val today = ts(2026, 1, 15)
        val due = ts(2026, 2, 1)
        // [P33-P8] المفتوح قروش — مساواة صحيحة تامة (كانت open <= EPS)
        assertTrue(EarlyPay.eligible(100000L, 0L, due, today))          // قبل الاستحقاق — مؤهل
        assertTrue(EarlyPay.eligible(100000L, 20000L, due, today))        // جزئي قبل الاستحقاق — مؤهل
        assertTrue(!EarlyPay.eligible(100000L, 0L, due, due))           // يوم الاستحقاق نفسه — ليس مبكراً
        assertTrue(!EarlyPay.eligible(100000L, 0L, due, due + day))     // بعد الاستحقاق
        assertTrue(!EarlyPay.eligible(100000L, 100000L, due, today))      // مسدد كلياً
    }

    // ══ 9) [P6-M7 إصلاح] توحيد أيام الأعمار على ceil — تطابق سلات الأعمار مع كشف «متأخر» ══
    // ReportsRepo.agingBuckets أصبحت تحسب الأيام بـ Dates.daysOverdue (ceil) نفسها
    // المستعملة في كشف التأخر — الاختبار يثبت عقد التركيب (daysOverdue × agingBucket):

    @Test
    fun agingDays_ceilUnification_matchesOverdueSemantics() {
        val now = ts(2026, 1, 15, 12)
        val hour = 3_600_000L
        // تأخر ساعات فقط → يوماً واحداً (ceil) لا صفر — فاتورة «متأخرة» في الكشف
        // تدخل السلة الأولى بوصف متسق بدل أن تظهر بأيام صفرية floor
        assertEquals(1, Dates.daysOverdue(now - 12 * hour, now))
        // حدود السلات بالتقريب لأعلى: 30 يوماً و12 ساعة → سلة 31-60 لا 0-30
        assertEquals(1, Dates.agingBucket(Dates.daysOverdue(now - 30 * day - 12 * hour, now)))
        // 30 يوماً بالضبط → سلة 0-30 (الحد الكامل كما هو)
        assertEquals(0, Dates.agingBucket(Dates.daysOverdue(now - 30 * day, now)))
        // 60 و12 ساعة → الثالثة، و90 و12 ساعة → الرابعة
        assertEquals(2, Dates.agingBucket(Dates.daysOverdue(now - 60 * day - 12 * hour, now)))
        assertEquals(3, Dates.agingBucket(Dates.daysOverdue(now - 90 * day - 12 * hour, now)))
        // تاريخ مستقبلي → صفر أيام (لا سالب، ولا يُعد متأخراً)
        assertEquals(0, Dates.daysOverdue(now + 5 * day, now))
    }
}
