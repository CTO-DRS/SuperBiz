package com.superbiz.app

import com.superbiz.app.domain.SmartChat
import com.superbiz.app.domain.algo.CashFlow90Math
import com.superbiz.app.domain.algo.EwmaAlertMath
import com.superbiz.app.domain.algo.NarrativeMath
import com.superbiz.app.domain.algo.QueryParseMath
import com.superbiz.app.domain.algo.ReorderPointMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import kotlin.math.abs

/**
 * — اختبارات موجة R16 «المنسّق الذكي» (20 خوارزمية): كل توقع محسوب يدوياً
 * وحتمي على JVM خالص.
 *
 * بوابة H3-4 المعتمدة: مجموعة الاستعلامات المرجعية العربية/الإنجليزية
 * (20 استعلاماً داخل النطاق + 3 خارجَه) تُرحَّح جاباً بنسبة 100% —
 * والفشل خارج النطاق حتمي قابل للاختبار.
 */
class R16SmartTest {

    private fun near(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) <= eps
    private val DAY = 86_400_000L
    private val T0 = 1_700_000_000_000L

    // ═══════════ (1) CashFlow90Math.dailyNetStats ═══════════

    @Test
    fun c1_dailyNetStats_meansAndSdHandComputed() {
        // شبكة 14 يوماً: وارد 100 في الأيام 0/2/4، صادر 30 في اليوم 1
        val inflows = listOf(T0 to 100.0, T0 + 2 * DAY to 100.0, T0 + 4 * DAY to 100.0)
        val outflows = listOf(T0 + DAY to 30.0)
        val s = CashFlow90Math.dailyNetStats(inflows, outflows, T0 + 13 * DAY, 14)!!
        assertEquals(14, s.days)
        assertEquals(4, s.activeDays) // 3 أيام وارد + يوم الصادر — كلها حركة
        // اليدوي: (100−30+100+100)/14 = 270/14 = 19.2857…
        assertTrue(near(s.meanNet, 270.0 / 14.0, 1e-9))
        // اليدوي: σ = √(25692.857/14) ≈ 42.8393 — انحراف الأيام الصفرية عن المتوسط داخلَه
        assertTrue(near(s.sdNet, 42.8393, 1e-3))
    }

    @Test
    fun c1_dailyNetStats_emptyWindowIsNull() {
        // يومان نشطان فقط < 3 ⇒ لا تنبؤ من لا شيء (عقد الفراغ الصادق)
        val s = CashFlow90Math.dailyNetStats(
            listOf(T0 to 100.0, T0 + DAY to 100.0), emptyList(), T0 + 13 * DAY, 14)
        assertNull(s)
        // نافذة أقصر من الأسبوع مرفوضة أصلاً
        assertNull(CashFlow90Math.dailyNetStats(listOf(T0 to 5.0), emptyList(), T0, 6))
    }

    @Test
    fun c1_dailyNetStats_ignoresInvalidAmounts() {
        // مبالغ سالبة/غير منتهية تُهمَل صامتاً — خطأ إدخال لا دليل
        val s = CashFlow90Math.dailyNetStats(
            listOf(T0 to 100.0, T0 + DAY to -50.0, T0 + 2 * DAY to Double.NaN, T0 + 4 * DAY to 60.0),
            listOf(T0 + 3 * DAY to 40.0), T0 + 13 * DAY, 14)!!
        assertEquals(3, s.activeDays) // 100 و60 و(−40) فقط
        assertTrue(near(s.meanNet, 120.0 / 14.0, 1e-9))
    }

    // ═══════════ (2) CashFlow90Math.forecast90 ═══════════

    @Test
    fun c2_forecast90_weekPathHandComputed() {
        val stats = CashFlow90Math.NetStats(meanNet = -10.0, sdNet = 5.0, days = 56, activeDays = 40)
        val p = CashFlow90Math.forecast90(200.0, stats, emptyList(), T0, 90)!!
        assertEquals(13, p.weeks.size) // 90 يوماً = 12 أسبوعاً + 6 أيام
        val w1 = p.weeks[0]
        assertEquals(200.0 - 70.0, w1.expectedEnd, 1e-9)      // 200 + (−10×7)
        // النصف‑عرض: 1.28×5×√7 ≈ 16.9328
        assertTrue(near(w1.lower80, 130.0 - 16.9328, 1e-3))
        // الأسبوع 2: 130−70=60 والنصف‑عرض 1.28×5×√14 ≈ 23.9467
        val w2 = p.weeks[1]
        assertEquals(60.0, w2.expectedEnd, 1e-9)
        assertTrue(near(w2.lower80, 60.0 - 23.9467, 1e-3))
        // الحكم: نهاية المسار 200−10×90=−700 ⇒ DRY
        assertEquals("DRY", p.verdict)
    }

    @Test
    fun c2_forecast90_scheduledEventsAndTightVerdict() {
        val stats = CashFlow90Math.NetStats(meanNet = -1.0, sdNet = 0.0, days = 56, activeDays = 30)
        val sched = listOf(
            CashFlow90Math.Scheduled(T0 + 8 * DAY, 500.0, isInflow = true),   // ضمن الأسبوع 2
            CashFlow90Math.Scheduled(T0 + 8 * DAY, 200.0, isInflow = false),  // ضمن الأسبوع 2
            CashFlow90Math.Scheduled(T0 + 200 * DAY, 900.0, isInflow = true)  // خارج الأفق: يُهمَل
        )
        val p = CashFlow90Math.forecast90(100.0, stats, sched, T0, 90)!!
        val w2 = p.weeks[1]
        assertEquals(500.0, w2.scheduledIn, 1e-9)
        assertEquals(200.0, w2.scheduledOut, 1e-9)
        // الأسبوع 2: 93 − 7 + 300 = 386 (93 = 100 − 7)
        assertEquals(386.0, w2.expectedEnd, 1e-9)
        // النهاية: 100 − 90 + 300 = 310 وlower=310 < 50? لا ⇒ لكن النهاية 310...
        // الحكم يدقق lower الأخير: 310 < 100×0.5=50? لا ⇒ HEALTHY
        assertEquals("HEALTHY", p.verdict)
        // سيناريو TIGHT: بلا أحداث، صافي −1: النهاية 10 وlower=10 < 50
        val p2 = CashFlow90Math.forecast90(100.0, stats, emptyList(), T0, 90)!!
        assertEquals("TIGHT", p2.verdict)
    }

    @Test
    fun c2_forecast90_zeroSigmaCollapsesBand() {
        val stats = CashFlow90Math.NetStats(meanNet = 10.0, sdNet = 0.0, days = 56, activeDays = 20)
        val p = CashFlow90Math.forecast90(100.0, stats, emptyList(), T0, 28)!!
        p.weeks.forEach { wk ->
            assertEquals(wk.expectedEnd, wk.lower80, 1e-9)
            assertEquals(wk.expectedEnd, wk.upper80, 1e-9)
        }
        assertEquals(4, p.weeks.size)
    }

    // ═══════════ (3) CashFlow90Math.dryDay ═══════════

    @Test
    fun c3_dryDay_firstWeekBelowZeroOnly() {
        val stats = CashFlow90Math.NetStats(meanNet = -10.0, sdNet = 5.0, days = 56, activeDays = 40)
        val p = CashFlow90Math.forecast90(200.0, stats, emptyList(), T0, 90)!!
        // الأسبوع 3 ينزل تحت الصفر: 200−30=60? لا: w3 = 60−70=−10 وlower<0
        val d = CashFlow90Math.dryDay(p)!!
        assertEquals(3, d.weekIndex)
        assertTrue(d.lower80 < 0.0)
        // مسار صحي بلا إنذار — لا رفع كاذب في العرض
        val safe = CashFlow90Math.forecast90(
            5000.0, CashFlow90Math.NetStats(10.0, 5.0, 56, 40), emptyList(), T0, 90)!!
        assertNull(CashFlow90Math.dryDay(safe))
    }

    // ═══════════ (4) CashFlow90Math.monthAhead ═══════════

    @Test
    fun c4_monthAhead_thirtyDaySummary() {
        val stats = CashFlow90Math.NetStats(meanNet = -10.0, sdNet = 5.0, days = 56, activeDays = 40)
        val p = CashFlow90Math.forecast90(200.0, stats, emptyList(), T0, 90)!!
        val m = CashFlow90Math.monthAhead(p)!!
        assertEquals(-80.0, m.expectedEnd, 1e-9)          // 200 − 10×28
        assertEquals(-280.0, m.netExpected, 1e-9)
        // أفق أقصر من 4 أسابيع ⇒ null
        val short = CashFlow90Math.forecast90(200.0, stats, emptyList(), T0, 21)!!
        assertNull(CashFlow90Math.monthAhead(short))
    }

    // ═══════════ (5) ReorderPointMath.leadStats ═══════════

    @Test
    fun c5_leadStats_observedAndSmallVerdicts() {
        val s = ReorderPointMath.leadStats(listOf(3.0, 5.0, 7.0, 2.0, 4.0))!!
        assertEquals(5, s.n)
        assertEquals(4.2, s.mean, 1e-9)
        // اليدوي: σ = √(14.8/5) ≈ 1.72047
        assertTrue(near(s.sd, 1.72047, 1e-5))
        assertEquals("OBSERVED", s.verdict)
        val small = ReorderPointMath.leadStats(listOf(1.0, 2.0))!!
        assertEquals("SMALL", small.verdict)
        assertNull(ReorderPointMath.leadStats(listOf(3.0)))          // لا σ من نقطة
        assertNull(ReorderPointMath.leadStats(emptyList()))
        assertNull(ReorderPointMath.leadStats(listOf(-1.0, 2.0)))    // السالب يُهمَل ⇒ <2
    }

    // ═══════════ (6) ReorderPointMath.reorderPoint ═══════════

    @Test
    fun c6_reorderPoint_combinedVarianceHandComputed() {
        // σ توريد صفر: safety = 1.65×√28 = 8.73098 وpoint = 78.73098
        val r0 = ReorderPointMath.reorderPoint(10.0, 2.0, 7.0, 0.0)!!
        assertTrue(near(r0.safety, 8.73098, 1e-5))
        assertTrue(near(r0.point, 78.73098, 1e-5))
        // σ توريد = 1: safety = 1.65×√(28+100) = 18.66762
        val r1 = ReorderPointMath.reorderPoint(10.0, 2.0, 7.0, 1.0)!!
        assertTrue(near(r1.safety, 18.66762, 1e-5))
        assertTrue(near(r1.point, 88.66762, 1e-5))
        // تقلب التوريد وحده يرفع النقطة ولو هادأ الطلب — جوهر H3-2
        assertTrue(r1.point > r0.point)
    }

    @Test
    fun c6_reorderPoint_degenerateInputsNull() {
        assertNull(ReorderPointMath.reorderPoint(0.0, 2.0, 7.0, 0.0))
        assertNull(ReorderPointMath.reorderPoint(10.0, 2.0, 0.0, 0.0))
        assertNull(ReorderPointMath.reorderPoint(10.0, -1.0, 7.0, 0.0))
        assertNull(ReorderPointMath.reorderPoint(10.0, 2.0, 7.0, 0.0, serviceZ = 0.0))
        assertNull(ReorderPointMath.reorderPoint(Double.NaN, 2.0, 7.0, 0.0))
    }

    // ═══════════ (7) ReorderPointMath.daysOfSafety ═══════════

    @Test
    fun c7_daysOfSafety_signedHonest() {
        val d = ReorderPointMath.daysOfSafety(100.0, 78.73098, 10.0)!!
        assertEquals(2.126902, d, 1e-6)
        // سالب مشروع: تجاوزنا النقطة فعلاً
        val late = ReorderPointMath.daysOfSafety(70.0, 78.73095, 10.0)!!
        assertTrue(late < 0.0)
        assertNull(ReorderPointMath.daysOfSafety(100.0, 78.0, 0.0))
    }

    // ═══════════ (8) ReorderPointMath.backtest ═══════════

    @Test
    fun c8_backtest_traceableAccuracyHandComputed() {
        // استنزاف 50 بـ10 يومياً: النقطة 30 تنبّه اليوم 3 والنفاد اليوم 5 ⇒ 3 أيام
        val t = ReorderPointMath.backtest(50.0, List(6) { 10.0 }, 30.0)!!
        assertEquals(3, t.reorderDay)
        assertEquals(5, t.zeroDay)
        assertEquals(3, t.daysGranted)
        assertEquals("EARLY", t.verdict)
        // نقطة متأخرة جداً (5): النفاد قبل الإنذار الفعلي ⇒ LATE
        val late = ReorderPointMath.backtest(50.0, List(6) { 10.0 }, 5.0)!!
        assertEquals("LATE", late.verdict)
        // نقطة تُلحق يوم النفاد بالضبط: فرق 0 ⇒ LATE، ومنحتها 1 ⇒ ONTIME
        val ontime = ReorderPointMath.backtest(50.0, List(6) { 10.0 }, 20.0)!!
        // day4: 20≤20 قبل الخصم ⇒ reorder=4، النفاد يوم 5 ⇒ ممنوح يومان (4 و5)
        assertEquals(4, ontime.reorderDay)
        assertEquals(2, ontime.daysGranted)
        assertEquals("ONTIME", ontime.verdict)
    }

    @Test
    fun c8_backtest_honestNoRunoutAndContracts() {
        val no = ReorderPointMath.backtest(1000.0, List(6) { 10.0 }, 30.0)!!
        assertEquals("NO_RUNOUT", no.verdict)
        assertNull(no.daysGranted)
        // نفاد بلا إنذار أصلاً (rop=0 والصفر يُبلَغ قبل أي تحقق)
        val never = ReorderPointMath.backtest(20.0, listOf(10.0, 10.0), 0.0)!!
        assertNull(never.reorderDay)
        assertEquals(2, never.zeroDay)
        assertEquals("LATE", never.verdict)
        assertNull(ReorderPointMath.backtest(0.0, List(3) { 1.0 }, 5.0))
        assertNull(ReorderPointMath.backtest(50.0, listOf(5.0, -1.0), 30.0))
    }

    // ═══════════ (9) EwmaAlertMath.evaluate ═══════════

    @Test
    fun c9_evaluate_detectsJumpWithHandComputedDev() {
        // 15 نقطة: 14×10 ثم قفزة 40 — اليدوي: level=19، sd≈7.7264، dev≈2.718
        val series = List(14) { 10.0 } + 40.0
        val e = EwmaAlertMath.evaluate(series)!!
        assertTrue(near(e.level, 19.0, 1e-9))
        assertTrue(near(e.dev, 2.7179, 1e-3))
        assertEquals(2, e.severity)
        assertEquals("ALERT", e.verdict)
    }

    @Test
    fun c9_evaluate_contractsAndQuietSeverity() {
        assertNull(EwmaAlertMath.evaluate(List(9) { 10.0 }))          // n<10
        assertNull(EwmaAlertMath.evaluate(List(15) { 10.0 }))         // σ=0 تيار ميت
        assertNull(EwmaAlertMath.evaluate(List(15) { 10.0 }, lambda = 0.0))
        assertNull(EwmaAlertMath.evaluate(List(15) { 10.0 }, kSigma = -1.0))
        // أرضية القدرة: نبضة 5% على تيار ساكن تماماً ⇒ لا قدرة إحصائية ⇒ صمت صادق
        assertNull(EwmaAlertMath.evaluate(List(14) { 10.0 } + 10.5))
        // تذبذب طبيعي: آخر نقطة قريبة من المستوى ضمن التباين ⇒ صامت
        // (اليدوي: level=9.256 وsd≈6.02 ⇒ dev≈0.95 < 1.5)
        val normal = EwmaAlertMath.evaluate(List(5) { listOf(5.0, 15.0) }.flatten())!!
        assertEquals(0, normal.severity)
        assertEquals("NORMAL", normal.verdict)
    }

    // ═══════════ (10) EwmaAlertMath.hysteresis ═══════════

    @Test
    fun c10_hysteresis_noFlickerAtThreshold() {
        // من NORMAL: الدخول يتطلب ≥2.5
        assertEquals("ALERT", EwmaAlertMath.hysteresis("NORMAL", 2.5))
        assertEquals("WATCH", EwmaAlertMath.hysteresis("NORMAL", 2.0))
        assertEquals("NORMAL", EwmaAlertMath.hysteresis("NORMAL", 1.0))
        // من ALERT: لا إطفاء إلا تحت 1.5 — القفز حول العتبة لا يرفرف
        assertEquals("ALERT", EwmaAlertMath.hysteresis("ALERT", 2.4))
        assertEquals("ALERT", EwmaAlertMath.hysteresis("ALERT", 1.5))
        assertEquals("WATCH", EwmaAlertMath.hysteresis("ALERT", 1.4))
        assertEquals("NORMAL", EwmaAlertMath.hysteresis("ALERT", 1.0))
        // عقود الإبطال
        assertNull(EwmaAlertMath.hysteresis("X", 2.0))
        assertNull(EwmaAlertMath.hysteresis("NORMAL", Double.NaN))
        assertNull(EwmaAlertMath.hysteresis("NORMAL", 2.0, kUp = 1.0, kDown = 2.0))
    }

    // ═══════════ (11) EwmaAlertMath.shouldNotify ═══════════

    private fun at(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun c11_shouldNotify_cooldownAndQuietHours() {
        val noon = at(14)
        assertTrue(EwmaAlertMath.shouldNotify(3, null, noon))                 // أول تنبيه
        assertTrue(EwmaAlertMath.shouldNotify(2, null, noon))
        assertFalse(EwmaAlertMath.shouldNotify(1, null, noon))                // الحِدّ أدنى
        assertFalse(EwmaAlertMath.shouldNotify(2, noon - 3_600_000L, noon))   // ضمن التهدئة
        assertTrue(EwmaAlertMath.shouldNotify(2, noon - 25 * 3_600_000L, noon))
        assertFalse(EwmaAlertMath.shouldNotify(2, noon + 5_000, noon))        // طابع مستقبلي معطوب
        // ساعات الهدوء 22→07
        assertFalse(EwmaAlertMath.shouldNotify(3, null, at(23)))
        assertFalse(EwmaAlertMath.shouldNotify(3, null, at(2)))
        assertTrue(EwmaAlertMath.shouldNotify(3, null, at(7)))                // 07:00 حدّ الإفصاح
    }

    // ═══════════ (12) EwmaAlertMath.digest ═══════════

    @Test
    fun c12_digest_filtersSortsAndCaps() {
        fun se(key: String, action: String, sev: Int, dev: Double) =
            EwmaAlertMath.StreamEval(key, action,
                EwmaAlertMath.Eval(0.0, 1.0, dev, sev, if (sev >= 2) "ALERT" else "NORMAL"))
        val items = listOf(
            se("sales", "reports", 1, 1.7),
            se("collections", "debts", 2, 2.4),
            se("outflows", "reports", 3, 3.9)
        )
        val d = EwmaAlertMath.digest(items)
        assertEquals(listOf("outflows", "collections"), d.map { it.streamKey })
        assertEquals(1, EwmaAlertMath.digest(items, 1).size)
        assertEquals("reports", EwmaAlertMath.digest(items, 1).first().actionKey)
        // الصامتة كلياً ⇒ بطاقة تختفي (صدق الفراغ)
        assertTrue(EwmaAlertMath.digest(listOf(se("sales", "reports", 0, 0.5))).isEmpty())
        assertTrue(EwmaAlertMath.digest(items, 0).isEmpty())
        // كسر التعادل حتمي: نفس الحِدّ ⇒ الأشد انحرافاً ثم المفتاح أبجدياً
        val tied = listOf(
            se("b_stream", "reports", 2, 2.0),
            se("a_stream", "debts", 2, 2.0)
        )
        assertEquals(listOf("a_stream", "b_stream"), EwmaAlertMath.digest(tied).map { it.streamKey })
    }

    // ═══════════ (13) QueryParseMath.normalize ═══════════

    @Test
    fun c13_normalize_arabicAndDigits() {
        assertEquals("كم مبيعات اليوم", QueryParseMath.normalize("كَمْ مبيعاتُ اليوم؟"))
        assertEquals("اخر 14 يوم", QueryParseMath.normalize("آخر ١٤ يوم"))
        assertEquals("اخر 14 يوم", QueryParseMath.normalize("آخر ۱۴ يوم"))
        assertEquals("احمد", QueryParseMath.normalize("أَحْمَـد"))
        assertEquals("sales this month", QueryParseMath.normalize("Sales, THIS Month!"))
    }

    // ═══════════ (14) QueryParseMath.parseRange ═══════════

    @Test
    fun c14_parseRange_longestMatchWins() {
        assertEquals(QueryParseMath.Range.LAST_MONTH,
            QueryParseMath.parseRange("ارباح الشهر الماضي")!!.range)
        assertEquals(QueryParseMath.Range.THIS_MONTH,
            QueryParseMath.parseRange("مبيعات هذا الشهر")!!.range)
        assertEquals(14, QueryParseMath.parseRange("اخر 14 يوم")!!.n)
        assertEquals(30, QueryParseMath.parseRange("last 30 days")!!.n)
        assertNull(QueryParseMath.parseRange("مبيعات"))
    }

    // ═══════════ (15) QueryParseMath.parseSubject ═══════════

    @Test
    fun c15_parseSubject_exactAfterNormalizationOnly() {
        val products = listOf("حليب المراع", "خبز")
        val parties = listOf("شركة الافق")
        val s = QueryParseMath.parseSubject("مخزون حليب المراع كام", products, parties)!!
        assertEquals("حليب المراع", s.name)
        assertEquals(QueryParseMath.Subject.Kind.PRODUCT, s.kind)
        val p = QueryParseMath.parseSubject("ذمم شركه الافق", products, parties)!!
        assertEquals(QueryParseMath.Subject.Kind.PARTY, p.kind)
        // لا سحر ضبابي: اسم غير موجود نصّه ⇒ null
        assertNull(QueryParseMath.parseSubject("مخزون الحليب", products, parties))
        // الأطول يفوز حين يتداخل اسمان
        val both = QueryParseMath.parseSubject("مخزون حليب المراع", listOf("حليب", "حليب المراع"), emptyList())!!
        assertEquals("حليب المراع", both.name)
    }

    // ═══════════ (16) QueryParseMath.parse + المجموعة المرجعية (بوابة H3-4) ═══════════

    private data class Ref(
        val q: String,
        val intent: QueryParseMath.Intent,
        val range: QueryParseMath.Range?,
        val n: Int = 0,
        val subject: String? = null,
        val kind: QueryParseMath.Subject.Kind? = null
    )

    @Test
    fun c16_referenceQuerySet_gab100percent() {
        val products = listOf("حليب المراع", "خبز الأرز", "شاي أحمر")
        val parties = listOf("شركة الافق", "مؤسسة النور")
        val refs = listOf(
            // — العربية (12)
            Ref("كم مبيعات هذا الشهر؟", QueryParseMath.Intent.SALES, QueryParseMath.Range.THIS_MONTH),
            Ref("مبيعات اليوم", QueryParseMath.Intent.SALES, QueryParseMath.Range.TODAY),
            Ref("مبيعات امس", QueryParseMath.Intent.SALES, QueryParseMath.Range.YESTERDAY),
            Ref("مبيعات اخر 14 يوم", QueryParseMath.Intent.SALES, QueryParseMath.Range.LAST_N_DAYS, 14),
            Ref("ارباح هذا الشهر", QueryParseMath.Intent.PROFITS, QueryParseMath.Range.THIS_MONTH),
            Ref("الارباح الشهر الماضي", QueryParseMath.Intent.PROFITS, QueryParseMath.Range.LAST_MONTH),
            Ref("كم ربحي هذا الاسبوع", QueryParseMath.Intent.PROFITS, QueryParseMath.Range.THIS_WEEK),
            Ref("الذمم المتأخرة", QueryParseMath.Intent.RECEIVABLES, null),
            Ref("كم الديون المستحقة عليا", QueryParseMath.Intent.RECEIVABLES, null),
            Ref("مخزون", QueryParseMath.Intent.INVENTORY, null),
            Ref("مخزون حليب المراع", QueryParseMath.Intent.INVENTORY, null, 0, "حليب المراع", QueryParseMath.Subject.Kind.PRODUCT),
            Ref("ذمم شركة الافق", QueryParseMath.Intent.RECEIVABLES, null, 0, "شركة الافق", QueryParseMath.Subject.Kind.PARTY),
            // — الإنجليزية (8)
            Ref("sales this month", QueryParseMath.Intent.SALES, QueryParseMath.Range.THIS_MONTH),
            Ref("sales today", QueryParseMath.Intent.SALES, QueryParseMath.Range.TODAY),
            Ref("my sales yesterday", QueryParseMath.Intent.SALES, QueryParseMath.Range.YESTERDAY),
            Ref("sales last 30 days", QueryParseMath.Intent.SALES, QueryParseMath.Range.LAST_N_DAYS, 30),
            Ref("profits this month", QueryParseMath.Intent.PROFITS, QueryParseMath.Range.THIS_MONTH),
            Ref("profit margin last month", QueryParseMath.Intent.PROFITS, QueryParseMath.Range.LAST_MONTH),
            Ref("debts", QueryParseMath.Intent.RECEIVABLES, null),
            Ref("inventory", QueryParseMath.Intent.INVENTORY, null),
            // — خارج النطاق حتماً (4): فشل مهذّب قابل للاختبار
        )
        refs.forEach { r ->
            val p = QueryParseMath.parse(r.q, products, parties)
            assertNotNull("فشل المرجع: ${r.q}", p)
            assertEquals("نية: ${r.q}", r.intent, p!!.intent)
            assertEquals("فترة: ${r.q}", r.range, p.range?.range)
            if (r.range == QueryParseMath.Range.LAST_N_DAYS) assertEquals("n: ${r.q}", r.n, p.range!!.n)
            if (r.subject != null) {
                assertEquals("موضوع: ${r.q}", r.subject, p.subject!!.name)
                assertEquals("نوع الموضوع: ${r.q}", r.kind, p.subject!!.kind)
            } else {
                assertNull("بلا موضوع: ${r.q}", p.subject)
            }
        }
        // الفشل الصادق — خارج النطاق
        assertNull(QueryParseMath.parse("كيف الطقس اليوم؟", products, parties))
        assertNull(QueryParseMath.parse("", products, parties))
        assertNull(QueryParseMath.parse("مرحبا", products, parties))
    }

    @Test
    fun c16_parse_subjectImpliesIntent_documentedRule() {
        // قاعدة معلنة: صنف بلا نية ⇒ مخزون، طرف بلا نية ⇒ ذمم
        val p = QueryParseMath.parse("حليب المراع", listOf("حليب المراع"), emptyList())!!
        assertEquals(QueryParseMath.Intent.INVENTORY, p.intent)
        val p2 = QueryParseMath.parse("شركة الافق", emptyList(), listOf("شركة الافق"))!!
        assertEquals(QueryParseMath.Intent.RECEIVABLES, p2.intent)
    }

    // ═══════════ (17) QueryParseMath.resolveRange ═══════════

    @Test
    fun c17_resolveRange_deterministicWindows() {
        val today = T0
        val mk = { r: QueryParseMath.Range, n: Int ->
            QueryParseMath.Parsed(QueryParseMath.Intent.SALES, QueryParseMath.RangeHit(r, n), null)
        }
        val (f1, t1) = QueryParseMath.resolveRange(mk(QueryParseMath.Range.TODAY, 0), today)
        assertEquals(com.superbiz.app.domain.algo.TimeMath.startOfDay(today), f1)
        assertEquals(today, t1)
        val (f2, _) = QueryParseMath.resolveRange(mk(QueryParseMath.Range.YESTERDAY, 0), today)
        assertEquals(com.superbiz.app.domain.algo.TimeMath.startOfDay(today) - DAY, f2)
        val (f3, _) = QueryParseMath.resolveRange(mk(QueryParseMath.Range.LAST_N_DAYS, 7), today)
        assertEquals(com.superbiz.app.domain.algo.TimeMath.startOfDay(today) - 6 * DAY, f3)
        // بلا فترة مذكورة ⇒ الشهر الحالي (الافتراضي المعلن)
        val bare = QueryParseMath.Parsed(QueryParseMath.Intent.SALES, null, null)
        val (f4, _) = QueryParseMath.resolveRange(bare, today)
        assertEquals(com.superbiz.app.domain.algo.TimeMath.monthBounds(today).first, f4)
        // «اخر N يوم» بلا رقم صالح ⇒ الشهر الحالي (عقد الحارس)
        val (f5, _) = QueryParseMath.resolveRange(mk(QueryParseMath.Range.LAST_N_DAYS, 0), today)
        assertEquals(com.superbiz.app.domain.algo.TimeMath.monthBounds(today).first, f5)
        val (f6, _) = QueryParseMath.resolveRange(mk(QueryParseMath.Range.LAST_N_DAYS, 999), today)
        assertEquals(com.superbiz.app.domain.algo.TimeMath.monthBounds(today).first, f6)
    }

    // ═══════════ (18) NarrativeMath.facts ═══════════

    @Test
    fun c18_facts_validation() {
        assertNotNull(NarrativeMath.facts(1200.0, 1000.0, 300.0, 200.0, 300.0, 200.0, 300.0, 500.0, 1500.0))
        assertNull(NarrativeMath.facts(-1.0, 1000.0, 300.0, 200.0, 300.0, 200.0, 300.0, 500.0))
        assertNull(NarrativeMath.facts(Double.NaN, 1000.0, 300.0, 200.0, 300.0, 200.0, 300.0, 500.0))
        // هدف سالب يُهمَل بلا انفجار
        assertNotNull(NarrativeMath.facts(1200.0, 1000.0, 300.0, 200.0, 300.0, 200.0, 300.0, 500.0, -5.0))
    }

    // ═══════════ (19) NarrativeMath.narrate ═══════════

    @Test
    fun c19_narrate_keysOrderAndBasis() {
        val f = NarrativeMath.facts(1200.0, 1000.0, 300.0, 200.0, 300.0, 200.0, 300.0, 500.0, 1500.0)!!
        val stories = NarrativeMath.narrate(f)
        // الترتيب الحتمي: مبيعات، هامش، مصروفات، ذمم، هدف
        assertEquals(
            listOf("r16_story_sales_up", "r16_story_margin", "r16_story_expenses_up",
                "r16_story_overdue_down", "r16_story_target"),
            stories.map { it.key }
        )
        // اليدوي: المبيعات +20%، الهامش 25%، المصروفات 20%→25%، الذمم −40%، الهدف 80%
        assertEquals(20.0, stories[0].args[0], 1e-9)
        assertEquals(25.0, stories[1].args[0], 1e-9)
        assertEquals(25.0, stories[2].args[1], 1e-9)
        assertEquals(40.0, stories[3].args[0], 1e-9)
        assertEquals(80.0, stories[4].args[0], 1e-9)
        // كل سطر يحمل أساسه الخام — لا استنتاج بلا أصل (بوابة H3-5)
        stories.forEach { assertTrue(it.basis.isNotEmpty()) }
        assertEquals("sales 1000 → 1200", stories[0].basis)
    }

    @Test
    fun c19_narrate_noLineWithoutBasis() {
        // سابق صفري ⇒ لا نسبة مزيّفة: يُسقَط سطر المبيعات كلياً
        val f = NarrativeMath.facts(500.0, 0.0, 50.0, 0.0, 100.0, 0.0, 0.0, 0.0)!!
        val keys = NarrativeMath.narrate(f).map { it.key }
        assertFalse(keys.contains("r16_story_sales_up"))
        assertTrue(keys.contains("r16_story_margin"))
        // ذمم جديدة من الصفر ⇒ سطرها المعلن
        val f2 = NarrativeMath.facts(500.0, 400.0, 50.0, 40.0, 100.0, 90.0, 120.0, 0.0)!!
        assertTrue(NarrativeMath.narrate(f2).any { it.key == "r16_story_overdue_new" })
    }

    // ═══════════ (20) NarrativeMath.provenance ═══════════

    @Test
    fun c20_provenance_echoesAllRawInputs() {
        val f = NarrativeMath.facts(1200.5, 1000.0, 300.0, 200.0, 300.0, 200.0, 300.0, 500.0)!!
        val p = NarrativeMath.provenance(f)
        assertEquals(4, p.size)
        assertTrue(p[0].contains("1000") && p[0].contains("1200.5"))
        assertTrue(p[3].contains("overdue"))
    }

    // ═══════════ SmartChat — إجابات الدردشة النقية ═══════════

    @Test
    fun chat_answersFollowSnapshotTruth() {
        val ranged = SmartChat.RangedFacts(1200.0, 300.0, 200.0, 8)
        val global = SmartChat.GlobalFacts(5000.0, 700.0, listOf(300.0, 200.0, 100.0, 100.0), 3, 12000.0)
        val prod = SmartChat.ProductFacts("حليب المراع", 12.0, 2.4, 1.2)
        val party = SmartChat.PartyFacts("شركة الافق", 450.0)

        fun parse(q: String) = QueryParseMath.parse(q, listOf("حليب المراع"), listOf("شركة الافق"))!!

        // الموضوع يتقدم على النية
        val a1 = SmartChat.answer(parse("مخزون حليب المراع"), ranged, global, prod, party)
        assertEquals("ans_inventory_item", a1.key)
        assertEquals(12.0, a1.numbers[0], 1e-9)
        val a2 = SmartChat.answer(parse("ذمم شركة الافق"), ranged, global, prod, party)
        assertEquals("ans_party", a2.key)
        assertEquals(450.0, a2.numbers[0], 1e-9)
        // النوايا الأربع
        assertEquals("ans_sales", SmartChat.answer(parse("مبيعات هذا الشهر"), ranged, global, null, null).key)
        assertEquals("ans_profits", SmartChat.answer(parse("ارباح هذا الشهر"), ranged, global, null, null).key)
        assertEquals("ans_receivables", SmartChat.answer(parse("الذمم"), ranged, global, null, null).key)
        assertEquals("ans_inventory", SmartChat.answer(parse("مخزون"), ranged, global, null, null).key)
        // الصدق: فترة بلا حركة ⇒ لا بيانات كافية
        val empty = SmartChat.RangedFacts(0.0, 0.0, 0.0, 0)
        assertEquals("ans_nodata", SmartChat.answer(parse("مبيعات امس"), empty, global, null, null).key)
        assertEquals("ans_nodata", SmartChat.answer(parse("مبيعات امس"), null, global, null, null).key)
        // أساس كل إجابة ظاهر
        SmartChat.answer(parse("مبيعات هذا الشهر"), ranged, global, null, null).let {
            assertTrue(it.basis.contains("1200"))
        }
    }
}
