package com.superbiz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.superbiz.app.domain.algo.TrendMath
import com.superbiz.app.domain.algo.LiquidityMath
import com.superbiz.app.domain.algo.TaxMath
import com.superbiz.app.domain.algo.PricingAuditMath
import com.superbiz.app.domain.algo.InvoiceMath
import com.superbiz.app.domain.algo.LoyaltyMath
import com.superbiz.app.domain.algo.ExpenseMath
import com.superbiz.app.domain.algo.ReplenishMath
import com.superbiz.app.domain.algo.CurrencyMath
import com.superbiz.app.domain.algo.CheckMath
import com.superbiz.app.domain.algo.InstallmentMath
import com.superbiz.app.domain.algo.RhythmMath
import com.superbiz.app.domain.algo.round2

/**
 * — 20 خوارزمية R11Smart: كل خوارزمية بعقدها الصريح.
 * نفس الحالات مُتحقق منها ميدانياً على JVM قبل الإدراج (مِرآة R11Harness).
*/
class R11SmartTest {

    // ═══ A1/A2 TrendMath ═══

    @Test
    fun a1_linearTrend_rising_flat_andShort() {
        val t = TrendMath.linearTrend(listOf(10.0, 12.0, 14.0, 16.0))!!
        assertEquals("RISING", t.verdict)
        assertTrue(t.slope > 0 && t.r2 > 0.99)
        assertEquals("STABLE", TrendMath.linearTrend(listOf(5.0, 5.0, 5.0))?.verdict)
        assertNull(TrendMath.linearTrend(listOf(1.0, 2.0)))
    }

    @Test
    fun a2_momentum_halves_andZeroBase() {
        assertEquals(100.0, TrendMath.momentumPct(listOf(10.0, 10.0, 20.0, 20.0))!!, 1e-9)
        assertNull(TrendMath.momentumPct(listOf(0.0, 0.0, 5.0, 5.0)))   // لا نسبة من صفر
        assertNull(TrendMath.momentumPct(listOf(1.0)))
    }

    // ═══ A3/A4 CashMath ═══

    @Test
    fun a3_burnAndRunway_bands() {
        val r = LiquidityMath.burnAndRunway(2000.0, 1000.0, 4000.0, 30) // 100/يوم → 20 يوماً
        assertEquals(100.0, r.dailyBurn, 1e-9)
        assertEquals(20.0, r.runwayDays!!, 1e-9)
        assertEquals("CRITICAL", r.verdict)
        assertEquals("TIGHT", LiquidityMath.burnAndRunway(3000.0, 1000.0, 4000.0, 30).verdict) // 30 بالضبط ليس حرجاً (عقد <30)
        assertEquals("OK", LiquidityMath.burnAndRunway(9000.0, 1000.0, 4000.0, 30).verdict)
        assertEquals("SURPLUS", LiquidityMath.burnAndRunway(1000.0, 500.0, 500.0).verdict)
        assertNull(LiquidityMath.burnAndRunway(1000.0, 500.0, 500.0).runwayDays)
    }

    @Test
    fun a4_cashGapCurve_runningBalance() {
        val g = LiquidityMath.cashGapCurve(100.0, listOf(
            LiquidityMath.GapEvent(1, 0.0, 150.0),
            LiquidityMath.GapEvent(2, 50.0, 0.0),
            LiquidityMath.GapEvent(2, 30.0, 40.0),
            LiquidityMath.GapEvent(5, 200.0, 0.0),
        ))
        assertEquals(1L, g.minDay)
        assertEquals(-50.0, g.minBalance, 1e-9)
        assertEquals(190.0, g.endBalance, 1e-9)
        assertEquals(2, g.deficitDays) // يوم1: -50، يوم2: -10
        val g0 = LiquidityMath.cashGapCurve(50.0, emptyList())
        assertEquals(50.0, g0.minBalance, 1e-9)
        assertEquals(0, g0.deficitDays)
    }

    // ═══ A5/A6 TaxMath ═══

    @Test
    fun a5_vatPosition_bands() {
        val v = TaxMath.vatPosition(150.0, 50.0, 1000.0)
        assertEquals(100.0, v.net, 1e-9)
        assertEquals("PAYABLE", v.position)
        assertEquals(10.0, v.effectiveRatePct!!, 1e-9)
        assertEquals("REFUND", TaxMath.vatPosition(50.0, 150.0, 1000.0).position)
        val n = TaxMath.vatPosition(0.0, 0.0, 0.0)
        assertEquals("NEUTRAL", n.position)
        assertNull(n.effectiveRatePct)
    }

    @Test
    fun a6_roundingDrift_worstRow() {
        val d = TaxMath.roundingDrift(listOf(100.0 to 100.01, 50.0 to 50.5, 20.0 to 20.0))
        assertEquals(2, d.skewedRows)   // 0.01 و 0.5 تتجاوزان عتبة نصف هللة
        assertEquals(1, d.worstIndex)
        assertEquals(0.5, d.worstDrift, 1e-9)
        assertEquals(0.0, TaxMath.roundingDrift(emptyList()).totalDrift, 1e-9)
    }

    // ═══ A7/A8 PriceMath ═══

    @Test
    fun a7_marginAudit_excludesZeroPrice_worstFirst() {
        val m = PricingAuditMath.marginAudit(listOf(
            PricingAuditMath.MarginLine("أ", 100.0, 90.0),  // 10% < 20%
            PricingAuditMath.MarginLine("ب", 100.0, 50.0),  // 50%
            PricingAuditMath.MarginLine("ج", 0.0, 10.0),    // مستبعد
            PricingAuditMath.MarginLine("د", 100.0, 85.0),  // 15% < 20%
        ), 20.0)
        assertEquals(3, m.validCount)
        assertEquals(2, m.belowCount)
        assertEquals(66.67, m.belowSharePct, 1e-9)
        assertEquals("أ", m.offenders.first().name)
        assertEquals(10.0, m.offenders.first().marginPct, 1e-9)
    }

    @Test
    fun a8_discountLeak_minGrossFilter() {
        val lk = PricingAuditMath.discountLeak(listOf(
            PricingAuditMath.DiscountRow("ز", 1000.0, 200.0),
            PricingAuditMath.DiscountRow("ع", 500.0, 10.0),
            PricingAuditMath.DiscountRow("ص", 50.0, 25.0),  // تحت الحد الأدنى
        ), minGross = 100.0)
        assertEquals(round2(235.0 / 1550.0 * 100.0), lk.overallPct!!, 1e-9)
        assertEquals(2, lk.rows.size)
        assertEquals("ز", lk.rows.first().party)
        assertNull(PricingAuditMath.discountLeak(emptyList()).overallPct)
    }

    // ═══ A9/A10 InvoiceMath ═══

    @Test
    fun a9_aovTrend_usesTrendComposition() {
        val a = InvoiceMath.aovTrend(listOf(1000.0 to 10, 2000.0 to 10, 4000.0 to 10))!!
        assertEquals(400.0, a.latest, 1e-9)
        assertEquals("RISING", a.direction)
        assertNull(InvoiceMath.aovTrend(listOf(100.0 to 2)))
        // أسبوع بعدد 0 يُهمل ولا يفسد الاتجاه
        assertNull(InvoiceMath.aovTrend(listOf(100.0 to 0, 200.0 to 0)))
    }

    @Test
    fun a10_duplicateSuspects_windowAndConsumption() {
        val s = InvoiceMath.duplicateSuspects(listOf(
            InvoiceMath.TxRow(1, "عميل", 250.0, 10),
            InvoiceMath.TxRow(1, "عميل", 250.0, 12),
            InvoiceMath.TxRow(2, "ثاني", 99.0, 3),
            InvoiceMath.TxRow(2, "ثاني", 99.0, 20), // خارج نافذة 7
            InvoiceMath.TxRow(3, "ثالث", 55.0, 1),
            InvoiceMath.TxRow(3, "ثالث", 55.0, 2),
        ), 7)
        assertEquals(2, s.size)
        assertTrue(s.any { it.partyName == "عميل" && it.dayGap == 2L })
        assertFalse(s.any { it.partyName == "ثاني" })
    }

    // ═══ A11/A12 CustomerMath ═══

    @Test
    fun a11_rfm_decisionTable() {
        assertEquals("CHAMPION", LoyaltyMath.rfmSegment(3, 6, 500.0))
        assertEquals("LOYAL", LoyaltyMath.rfmSegment(20, 4, 500.0))
        assertEquals("NEW", LoyaltyMath.rfmSegment(20, 1, 100.0))
        assertEquals("PROMISING", LoyaltyMath.rfmSegment(50, 2, 100.0))
        assertEquals("AT_RISK", LoyaltyMath.rfmSegment(80, 2, 100.0))
        assertEquals("SLEEPING", LoyaltyMath.rfmSegment(150, 1, 100.0))
        assertEquals("LOST", LoyaltyMath.rfmSegment(300, 1, 100.0))
        assertEquals("NO_ORDERS", LoyaltyMath.rfmSegment(null, 0, 0.0))
    }

    @Test
    fun a12_churnRisk_ratioThreshold() {
        val c = LoyaltyMath.churnRisk(100, 160, 30)!!  // صمت 60 ÷ فجوة 30 = 2 → درجة 50
        assertEquals(60L, c.silenceDays)
        assertEquals(50, c.score)
        assertEquals("MED", c.verdict)
        assertNull(LoyaltyMath.churnRisk(100, 130, 30))  // نسبة < 1.5
        assertEquals("HIGH", LoyaltyMath.churnRisk(100, 200, 30)!!.verdict)
        assertNull(LoyaltyMath.churnRisk(100, 160, 0))   // بلا معيار صمت
    }

    // ═══ A13/A14 ExpenseMath ═══

    @Test
    fun a13_budgetVsActual_unionAndSorting() {
        val b = ExpenseMath.budgetVsActual(
            mapOf("إيجار" to 1000.0, "كهرباء" to 200.0),
            mapOf("إيجار" to 1200.0, "وقود" to 100.0),
        )
        assertEquals(3, b.rows.size)
        assertEquals(1300.0, b.totalActual, 1e-9)
        val top = b.rows.first()
        assertEquals("إيجار", top.category)
        assertEquals(20.0, top.overPct!!, 1e-9)
        assertTrue(top.over)
        assertTrue(b.rows.first { it.category == "وقود" }.over) // فئة جديدة بلا أساس
    }

    @Test
    fun a14_fixedVariableSplit_byRecurringMonths() {
        val sp = ExpenseMath.fixedVariableSplit(mapOf(
            "إيجار" to (3000.0 to 4),
            "رواتب" to (2000.0 to 3),
            "ضيافة" to (500.0 to 1),
        ))!!
        assertEquals(round2(5000.0 / 5500.0 * 100.0), sp.fixedSharePct, 1e-9)
        assertEquals(round2(100.0 - sp.fixedSharePct), sp.variableSharePct, 1e-9)
        assertEquals(listOf("إيجار", "رواتب"), sp.fixedCats) // ترتيب وحدات الكود
        assertNull(ExpenseMath.fixedVariableSplit(mapOf("أ" to (0.0 to 3))))
    }

    // ═══ A15/A16 StockMath ═══

    @Test
    fun a15_reorderPlan_verdicts() {
        val p = ReplenishMath.reorderPlan(7.0, 5.0, 4.0, 8.0) // هدف 28، أمان 8، جرد 7
        assertEquals(28.0, p.target, 1e-9)
        assertEquals(21.0, p.orderQty, 1e-9)
        assertEquals("URGENT", p.verdict)
        assertEquals("SOON", ReplenishMath.reorderPlan(10.0, 5.0, 4.0, 8.0).verdict)
        assertEquals("OK", ReplenishMath.reorderPlan(30.0, 5.0, 4.0, 8.0).verdict)
        assertEquals("OUT", ReplenishMath.reorderPlan(0.0, 5.0, 4.0, 8.0).verdict)
        assertEquals("OK", ReplenishMath.reorderPlan(5.0, 0.0, 0.0, 0.0).verdict) // بلا هدف
    }

    @Test
    fun a16_gmroi_excludesZeroStock_annualizes() {
        val gr = ReplenishMath.gmroiByCategory(
            marginByCat = mapOf("مشروبات" to 600.0, "سكريات" to 300.0),
            stockValueByCat = mapOf("مشروبات" to 200.0, "سكريات" to 0.0),
            periodMonths = 2.0,
        )
        assertEquals(1, gr.size)
        assertEquals(18.0, gr.first().gmroiAnnual, 1e-9) // (600/200)×6
    }

    // ═══ A17 CurrencyMath ═══

    @Test
    fun a17_fxExposure_excludesBaseAndMissingRates() {
        // [P20-FIX agent10] الاتجاهية = وحدات العملة لكل 1 من الأساسية (نفس بذور SeedDefaults:
        // 1 ريال = 0.2665 دولار) — المعادل الأساسي = المبلغ ÷ المعدل، لا الضرب (كان يضخّم بمربع المعدل)
        val fx = CurrencyMath.fxExposure(
            openByCurrency = mapOf("SAR" to 5000.0, "USD" to 1000.0, "EUR" to 1000.0),
            rateToBase = mapOf("SAR" to 1.0, "USD" to 0.2665, "EUR" to 0.2453),
            base = "SAR",
        )!!
        assertEquals(round2(1000.0 / 0.2665 + 1000.0 / 0.2453), fx.totalBase, 1e-9)
        assertEquals("CONCENTRATED", fx.verdict) // حصة اليورو ≈ 52% ≥ 50
        val fx2 = CurrencyMath.fxExposure(
            openByCurrency = mapOf("USD" to 1000.0, "EUR" to 1000.0, "AED" to 2000.0),
            rateToBase = mapOf("USD" to 0.2665, "EUR" to 0.2453, "AED" to 0.9785),
            base = "SAR",
        )!!
        assertEquals("DIVERSIFIED", fx2.verdict) // حصة اليورو ≈ 41%
        assertNull(CurrencyMath.fxExposure(mapOf("SAR" to 100.0), mapOf("SAR" to 1.0), "SAR"))
        // عملة بلا سعر معتمد تُستبعد ولا يُخترع سعر
        assertNull(CurrencyMath.fxExposure(mapOf("USD" to 100.0), emptyMap(), "SAR"))
    }

    // ═══ A18 CheckMath ═══

    @Test
    fun a18_bounceStats_minIssuedFilter() {
        val bo = CheckMath.bounceStats(listOf(
            Triple("ز", 5, 2), Triple("ع", 1, 1), Triple("ص", 10, 0)), minIssued = 3)
        assertEquals(round2(3.0 / 16.0 * 100.0), bo.overallPct!!, 1e-9)
        assertEquals(2, bo.rows.size) // «ع» تحت حد 3 شيكات
        assertEquals("ز", bo.rows.first().party)
        assertNull(CheckMath.bounceStats(emptyList()).overallPct)
    }

    // ═══ A19 InstallmentMath ═══

    @Test
    fun a19_delinquency_bucketsAndDueTodayNotLate() {
        val d = InstallmentMath.delinquencyProfile(listOf(
            100L to 500.0,   // متأخر 10 → 1-15
            105L to 300.0,   // متأخر 5 → 1-15
            70L to 200.0,    // متأخر 40 → 31-60
            110L to 100.0,   // مستحق اليوم — ليس متأخراً
            120L to 400.0,   // مستقبلي
        ), 110)
        assertEquals(1000.0, d!!.lateOpen, 1e-9)
        assertEquals(1500.0, d.totalOpen, 1e-9)
        assertEquals(round2(1000.0 / 1500.0 * 100.0), d.ratePct, 1e-9)
        assertTrue(d.buckets.any { it.label == "1-15" && it.amount == 800.0 })
        assertTrue(d.buckets.any { it.label == "31-60" && it.amount == 200.0 })
        assertNull(InstallmentMath.delinquencyProfile(listOf(100L to 0.0), 110))
    }

    // ═══ A20 RhythmMath ═══

    @Test
    fun a20_todayPace_bandsAndGuards() {
        val p = RhythmMath.todayPace(230.0, 1000.0, 20.0)!! // متوقع 200 → 115%
        assertEquals(200.0, p.expectedSoFar, 1e-9)
        assertEquals(115, p.pacePct)
        assertEquals("AHEAD", p.verdict)
        assertNull(RhythmMath.todayPace(0.0, 0.0, 50.0))     // بلا معدل مرجعي
        assertNull(RhythmMath.todayPace(100.0, 1000.0, 101.0)) // تقدم غير منطقي
        assertEquals("CRITICAL", RhythmMath.todayPace(30.0, 1000.0, 50.0)!!.verdict)
    }
}
