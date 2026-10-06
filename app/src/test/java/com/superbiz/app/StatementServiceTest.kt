package com.superbiz.app

import com.superbiz.app.domain.statement.BalanceDirection
import com.superbiz.app.domain.statement.StatementCompanyInfo
import com.superbiz.app.domain.statement.StatementData
import com.superbiz.app.domain.statement.StatementLang
import com.superbiz.app.domain.statement.StatementPartyInfo
import com.superbiz.app.domain.statement.StatementPeriodPreset
import com.superbiz.app.domain.statement.StatementService
import com.superbiz.app.domain.statement.StatementSummary
import com.superbiz.app.domain.statement.StatementTxRow
import com.superbiz.app.domain.statement.StatementTxTypes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [P17-a] اختبارات خدمة كشف الحساب — JVM نقي بلا أندرويد (نمط OverdueReminderPolicyTest).
 *
 * المنطقة الزمنية مثبَّتة على America/New_York كي تكون الحسابات حتمية عبر أي جهاز بناء
 * ولنفس سلوك TimeMath/المنطق المرآتي الذي جرى التحقق منه خارج البناء (أوراكل Java
 * مؤقت — 19/19 قبل التثبيت). المنطقة الأصلية تُستعاد في tearDown احتراماً لبقية الاختبارات.
 */
class StatementServiceTest {

    private val originalTz: TimeZone = TimeZone.getDefault()

    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    // ═══ أدوات بناء لحظات (بالمنطقة المثبَّتة) ═══

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, sec: Int = 0, ms: Int = 0): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, hour, minute, sec)
        c.set(Calendar.MILLISECOND, ms)
        return c.timeInMillis
    }

    /** حدود فترة متوقعة من حقول تقويمية — للتحقق المزدوج بلا حساب يدوي بالميلي */
    private fun expect(from: Long, to: Long, fy: Int, fm: Int, fd: Int, ty: Int, tm: Int, td: Int) {
        assertEquals(at(fy, fm, fd, 0, 0), from)
        assertEquals(at(ty, tm, td, 23, 59, 59, 999), to)
    }

    // ═══ periodRange — الحدود العشر ═══

    @Test
    fun periodRange_today() {
        val now = at(2026, 9, 24, 15, 30)
        val (f, t) = StatementService.periodRange(StatementPeriodPreset.TODAY, now)
        expect(f, t, 2026, 9, 24, 2026, 9, 24)
    }

    @Test
    fun periodRange_thisMonth() {
        val now = at(2026, 9, 24, 15, 30)
        val (f, t) = StatementService.periodRange(StatementPeriodPreset.THIS_MONTH, now)
        expect(f, t, 2026, 9, 1, 2026, 9, 30)
    }

    @Test
    fun periodRange_lastMonth_acrossYearBoundary() {
        val now = at(2026, 1, 15, 8, 0)
        val (f, t) = StatementService.periodRange(StatementPeriodPreset.LAST_MONTH, now)
        expect(f, t, 2025, 12, 1, 2025, 12, 31)
    }

    @Test
    fun periodRange_lastMonth_leapFebruary() {
        // مارس 2024 → فبراير الكبسة حتى 29 (Calendar يعالج الطول تلقائياً)
        val now = at(2024, 3, 15, 12, 0)
        val (f, t) = StatementService.periodRange(StatementPeriodPreset.LAST_MONTH, now)
        expect(f, t, 2024, 2, 1, 2024, 2, 29)
    }

    @Test
    fun periodRange_thisWeek_sundayStart() {
        // 2026-09-24 خميس — بداية الأسبوع بالمنطقة US = الأحد 09-20
        val now = at(2026, 9, 24, 15, 30)
        val (f, t) = StatementService.periodRange(StatementPeriodPreset.THIS_WEEK, now)
        expect(f, t, 2026, 9, 20, 2026, 9, 26)
    }

    @Test
    fun periodRange_lastWeek_isThisWeekMinus7Days() {
        val now = at(2026, 9, 24, 15, 30)
        val (f, t) = StatementService.periodRange(StatementPeriodPreset.LAST_WEEK, now)
        // نفس نافذة هذا الأسبوع مزاحة 7 أيام — تحقق بالعلاقة لا بالقيم المكتوبة
        val (cf, ct) = StatementService.periodRange(StatementPeriodPreset.THIS_WEEK, now)
        assertEquals(cf - 7L * 86_400_000, f)
        assertEquals(ct - 7L * 86_400_000, t)
        expect(f, t, 2026, 9, 13, 2026, 9, 19)
    }

    @Test
    fun periodRange_trailing3And6Months_includeCurrentMonth() {
        val now = at(2026, 9, 24, 15, 30)
        val (f3, t3) = StatementService.periodRange(StatementPeriodPreset.LAST_3_MONTHS, now)
        // قرار موثق: أشهر تقويمية تشمل الحالي → يوليو..أيلول
        expect(f3, t3, 2026, 7, 1, 2026, 9, 30)
        val (f6, t6) = StatementService.periodRange(StatementPeriodPreset.LAST_6_MONTHS, now)
        expect(f6, t6, 2026, 4, 1, 2026, 9, 30)
    }

    @Test
    fun periodRange_thisYear_and_lastYear() {
        val now = at(2026, 9, 24, 15, 30)
        val (f, t) = StatementService.periodRange(StatementPeriodPreset.THIS_YEAR, now)
        expect(f, t, 2026, 1, 1, 2026, 12, 31)
        val (lf, lt) = StatementService.periodRange(StatementPeriodPreset.LAST_YEAR, now)
        expect(lf, lt, 2025, 1, 1, 2025, 12, 31)
    }

    @Test
    fun periodRange_custom_coercesInversion() {
        val (f, t) = StatementService.periodRange(StatementPeriodPreset.CUSTOM, 0, customFrom = 500, customTo = 100)
        assertEquals(100L, f)
        assertEquals(500L, t)
        // نافذة سليمة تمر كما هي
        val (f2, t2) = StatementService.periodRange(StatementPeriodPreset.CUSTOM, 0, customFrom = 10, customTo = 20)
        assertEquals(10L, f2)
        assertEquals(20L, t2)
    }

    // ═══ summarize — الاتجاه الثلاثي بمساواة تامة [P33-P8] ═══

    // [P33-P8] مبالغ قروش Long — 1 ريال = 100 قرشاً؛ الرصيد الابتدائي 0L
    private fun row(ts: Long, debit: Long = 0L, credit: Long = 0L, typeKey: String = StatementTxTypes.ADJUST) =
        StatementTxRow(ts, "", typeKey, "", debit, credit, 0L)

    @Test
    fun summarize_dueOnCustomer() {
        val s = StatementService.summarize(
            opening = 10_000L,   // [P33-P8] 100.0 ريال → 10000 قروش
            rows = listOf(
                row(1, debit = 50_000L, typeKey = StatementTxTypes.INVOICE),   // 500.0 ريال
                row(2, credit = 20_000L, typeKey = StatementTxTypes.PAYMENT)   // 200.0 ريال
            ),
            totalDiscounts = 5_000L   // 50.0 ريال
        )
        assertEquals(40_000L, s.due)    // [P33-P8] مساواة تامة بلا عتبة (كانت 1e-9)
        assertEquals(40_000L, s.final)
        assertEquals(BalanceDirection.DUE_ON_CUSTOMER, s.direction)
    }

    @Test
    fun summarize_inFavorOfCustomer() {
        val s = StatementService.summarize(
            opening = -30_000L, // [P33-P8] 300.0- ريال — دفعات سابقة تجاوزت الديون (أو مورد)
            rows = listOf(row(1, credit = 10_000L, typeKey = StatementTxTypes.PAYMENT)),   // 100.0 ريال
            totalDiscounts = 0L
        )
        assertEquals(-40_000L, s.due)   // [P33-P8] مساواة تامة
        assertEquals(BalanceDirection.IN_FAVOR_OF_CUSTOMER, s.direction)
    }

    @Test
    fun summarize_balanced_exactEquality() {   // [P33-P8] كانت withToleranceBoundary — الاتزان صار مساواة تامة
        // [P33-P8] عتبة TOLERANCE (0.005 ريال Double) لم يعد لها معنى بالقروش:
        // صفر تام ⇒ متوازن، وأصغر قروش غير صفريّة (±1L) تُخرج عن الاتزان فوراً
        assertEquals(BalanceDirection.BALANCED, StatementService.summarize(0L, emptyList(), 0L).direction)
        assertEquals(BalanceDirection.DUE_ON_CUSTOMER, StatementService.summarize(1L, emptyList(), 0L).direction)
        assertEquals(BalanceDirection.IN_FAVOR_OF_CUSTOMER, StatementService.summarize(-1L, emptyList(), 0L).direction)
    }

    @Test
    fun summarize_emptyRows_keepsOpening() {
        val s = StatementService.summarize(123_456L, emptyList(), 0L)   // [P33-P8] 1234.56 ريال → 123456 قروش
        assertEquals(123_456L, s.due)
        assertEquals(0L, s.totalDebit)
        assertEquals(0L, s.totalCredit)
        assertEquals(0L, s.totalInvoices)
        assertEquals(0L, s.totalPayments)
        assertEquals(BalanceDirection.DUE_ON_CUSTOMER, s.direction)
    }

    @Test
    fun summarize_typeScopedTotals() {
        val s = StatementService.summarize(
            opening = 0L,
            rows = listOf(
                row(1, debit = 100_000L, typeKey = StatementTxTypes.INVOICE),  // [P33-P8] 1000.0 ريال
                row(2, debit = 70_000L, typeKey = StatementTxTypes.INVOICE),   // 700.0 ريال
                row(3, debit = 30_000L, typeKey = StatementTxTypes.DEBT),      // لا يدخل totalInvoices
                row(4, credit = 50_000L, typeKey = StatementTxTypes.PAYMENT),  // 500.0 ريال
                row(5, credit = 25_000L, typeKey = StatementTxTypes.CHECK),    // لا يدخل totalPayments
                row(6, debit = 5_000L, typeKey = StatementTxTypes.ADJUST)      // 50.0 ريال
            ),
            totalDiscounts = 2_500L   // 25.0 ريال
        )
        assertEquals(170_000L, s.totalInvoices)   // INVOICE فقط
        assertEquals(50_000L, s.totalPayments)    // PAYMENT فقط
        assertEquals(205_000L, s.totalDebit)      // كل المدين
        assertEquals(75_000L, s.totalCredit)      // كل الدائن
        assertEquals(2_500L, s.totalDiscounts)
        assertEquals(130_000L, s.due)             // [P33-P8] 0 + 205000 - 75000 قروشاً
    }

    // ═══ الترقيم ═══

    @Test
    fun statementNumber_zeroPadded() {
        assertEquals("STATEMENT-2026-000001", StatementService.statementNumber(1, 2026))
        assertEquals("STATEMENT-2026-123456", StatementService.statementNumber(123456, 2026))
        // seq أكبر من ستة أرقام: padStart لا يقتطع — التسلسل الصادق يفضل على النسق
        assertEquals("STATEMENT-2026-1234567", StatementService.statementNumber(1234567, 2026))
    }

    @Test
    fun verificationId_format_and_seqCarry() {
        val at = at(2026, 9, 24, 21, 45)
        val v = StatementService.verificationId("STATEMENT-2026-000001", at)
        assertTrue(v.matches(Regex("^SB-ST-\\d{8}-\\d{6}$")))
        assertEquals("SB-ST-20260924-000001", v)
        // ذيل الرقم يُحمل للتحقق حتى لو تجاوز ستة أرقام
        assertEquals("SB-ST-20260924-1234567", StatementService.verificationId("STATEMENT-2026-1234567", at))
    }

    @Test
    fun verificationId_uniqueAcrossTimes() {
        val morning = at(2026, 9, 24, 8, 0)
        val evening = at(2026, 9, 25, 23, 59)  // اليوم التالي ⇒ تاريخ مختلف
        assertNotEquals(
            StatementService.verificationId("STATEMENT-2026-000001", morning),
            StatementService.verificationId("STATEMENT-2026-000001", evening)
        )
    }

    @Test
    fun partyNumber_format() {
        assertEquals("P-000001", StatementService.partyNumber(1))
        assertEquals("P-000042", StatementService.partyNumber(42))
        assertEquals("P-123456", StatementService.partyNumber(123456))
    }

    // ═══ safeFileName — متجهات التنظيف ═══

    @Test
    fun safeFileName_arabicWithIllegalChars() {
        val t = at(2026, 9, 24, 12, 0)
        val name = StatementService.safeFileName("محمد/أحمد:*?\"<>|", t, t + 30L * 86_400_000)
        // العربية محفوظة، والممنوعات شرطات سفلية (استبدال لا حذف)
        assertTrue(name.startsWith("محمد_أحمد"))
        // بين «أحمد» وتاريخ البداية: 7 ممنوعات بعد أحمد + فاصل النسق = 8 شرطات
        val underscores = name.indexOf("20260924") - (name.indexOf("أحمد") + "أحمد".length)
        assertEquals(8, underscores)
        assertTrue(name.endsWith("_20260924-20261024"))
    }

    @Test
    fun safeFileName_trimsDotsAndSpaces() {
        val t = at(2026, 9, 24, 12, 0)
        assertEquals("company .co_" + "20260924-20260924", StatementService.safeFileName("  .company .co.  ", t, t))
    }

    @Test
    fun safeFileName_dropsControlChars() {
        val t = at(2026, 9, 24, 12, 0)
        // \n و\t محارف تحكم تُسقط لا تُستبدل (كسر الأسطر في اسم ملف فساد)
        val name = StatementService.safeFileName("ab\ncd\tef", t, t)
        assertEquals("abcdef_20260924-20260924", name)
    }

    @Test
    fun safeFileName_truncatesTo80_keepingDates() {
        val t = at(2026, 9, 24, 12, 0)
        val longName = "A very long party name that definitely exceeds eighty characters in total length for sure"
        val name = StatementService.safeFileName(longName, t, t)
        val dates = "20260924-20260924"
        assertTrue("الطول ≤ 80 دائماً", name.length <= 80)
        assertTrue("الفترة محفوظة بعد القص", name.endsWith("_" + dates))
        // ميزانية الاسم = 80 − 17 (التاريخان) − 1 (الفاصل) = 62 محرفاً
        assertEquals(longName.take(80 - dates.length - 1), name.removeSuffix("_" + dates))
    }

    @Test
    fun safeFileName_allIllegal_keepsUnderscores() {
        val t = at(2026, 9, 24, 12, 0)
        // قرار موثق: ممنوعات تُستبدل لا تُحذف — اسم مكلّس يبقى شرطات صالحة كاسم ملف
        // (4 ممنوعات → 4 شرطات + فاصل النسق = 5 شرطات قبل التاريخ)
        assertEquals("_____20260924-20260924", StatementService.safeFileName("/*:?", t, t))
    }

    @Test
    fun safeFileName_blankFallsBack() {
        val t = at(2026, 9, 24, 12, 0)
        assertEquals("statement_20260924-20260924", StatementService.safeFileName("   ", t, t))
    }

    // ═══ dedupKey ═══

    @Test
    fun dedupKey_manualVsRule() {
        assertEquals("party:7:1000:2000", StatementService.dedupKey(7, 1000, 2000, null))
        assertEquals("party:7:1000:2000:42", StatementService.dedupKey(7, 1000, 2000, 42))
        // يدوي ≠ قاعدة حتى للفترة نفسها — قرار موثق (القاعدة مجدولة بمعرف مستقل)
        assertNotEquals(
            StatementService.dedupKey(7, 1000, 2000, null),
            StatementService.dedupKey(7, 1000, 2000, 42)
        )
    }

    // ═══ contentHash ═══

    private fun company(no: String = "SB-COMPANY-001") = StatementCompanyInfo(
        "مؤسسة النور", "أحمد", "0500000000", "a@b.c", "الرياض",
        "الرياض", "السعودية", "noor.sa", "300000000000003", "1010000000", no, "/logo.png", ""
    )

    private fun party(no: String = "P-000001", name: String = "شركة العميل") = StatementPartyInfo(
        1, name, "0511111111", null, "جدة", null, null, null, null, null, null, no
    )

    private fun data(
        rows: List<StatementTxRow> = listOf(row(1000, debit = 10_000L, typeKey = StatementTxTypes.INVOICE)),  // [P33-P8] 100.0 ريال → 10000 قروش
        note: String? = null,
        partyName: String = "شركة العميل",
        summary: StatementSummary? = null,
        number: String = "STATEMENT-2026-000001",
        verification: String = "SB-ST-20260924-000001"
    ): StatementData {
        val s = summary ?: StatementService.summarize(0L, rows, 0L)   // [P33-P8] قروش
        return StatementData(
            party = party(name = partyName), company = company(),
            fromTs = 1_000L, toTs = 2_000L, currency = "SAR",
            rows = rows, summary = s, statementNumber = number,
            verificationId = verification, createdAt = 5_000L,
            note = note, lang = StatementLang.AR
        )
    }

    @Test
    fun sha256_emptyStringStandardVector() {
        // المتجه القياسي المعروف لـSHA-256 على السلسلة الفارغة — يقفل طريقة التلبيد نفسها
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            StatementService.sha256Hex("")
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            StatementService.sha256Hex("abc")
        )
    }

    @Test
    fun contentHash_deterministic_andWellFormed() {
        val h1 = StatementService.contentHash(data())
        val h2 = StatementService.contentHash(data())
        assertEquals(h1, h2)
        assertEquals(64, h1.length)
        assertTrue(h1.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun contentHash_changesWithEveryContentField() {
        val base = StatementService.contentHash(data())
        // سطر مبالغه مختلف
        val diffAmount = StatementService.contentHash(
            data(rows = listOf(row(1000, debit = 10_100L, typeKey = StatementTxTypes.INVOICE)))   // [P33-P8] 101.0 ريال
        )
        assertNotEquals(base, diffAmount)
        // وصف مختلف
        val diffDesc = StatementService.contentHash(
            data(rows = listOf(StatementTxRow(1000, "x", StatementTxTypes.INVOICE, "مغاير", 10_000L, 0L, 10_000L)))   // [P33-P8] قروش
        )
        assertNotEquals(base, diffDesc)
        // ملاحظة مختلفة
        assertNotEquals(base, StatementService.contentHash(data(note = "ملاحظة")))
        // اسم طرف مختلف (بطاقة الطرف جزء من المحتوى)
        assertNotEquals(base, StatementService.contentHash(data(partyName = "مغاير")))
        // رقم الكشف نفسه يدخل البصمة (يُثبت عند الإصدار)
        assertNotEquals(
            base,
            StatementService.contentHash(data(number = "STATEMENT-2026-000002"))
        )
        // اتجاه/قيم الملخص مختلفة
        val flipped = StatementService.summarize(-100L, data().rows, 0L)   // [P33-P8] 1.0- ريال → 100- قرشاً
        assertNotEquals(
            base,
            StatementService.contentHash(data(summary = flipped))
        )
    }

    @Test
    fun contentHash_rowOrderMatters() {
        val a = listOf(row(1, debit = 1_000L), row(2, credit = 1_000L))   // [P33-P8] 10.0 ريال → 1000 قروش
        val b = listOf(row(2, credit = 1_000L), row(1, debit = 1_000L))
        assertNotEquals(
            StatementService.contentHash(data(rows = a)),
            StatementService.contentHash(data(rows = b))
        )
    }

    @Test
    fun contentHash_multilineNote_normalizes() {
        // ملاحظة بأسطر جديدة == نفس الملاحظة بمسافات — أحادية السطر للسلسلة القياسية
        assertEquals(
            StatementService.contentHash(data(note = "سطر أول\nسطر ثان")),
            StatementService.contentHash(data(note = "سطر أول سطر ثان"))
        )
    }
}
