package com.superbiz.app

import com.superbiz.app.domain.algo.AbcXyzMath
import com.superbiz.app.domain.algo.BandMath
import com.superbiz.app.domain.algo.BordaMath
import com.superbiz.app.domain.algo.BlendMath
import com.superbiz.app.domain.algo.CampaignMath
import com.superbiz.app.domain.algo.CatalogHygieneMath
import com.superbiz.app.domain.algo.CoverageMath
import com.superbiz.app.domain.algo.DrawdownMath
import com.superbiz.app.domain.algo.EntropyMath
import com.superbiz.app.domain.algo.FairShareMath
import com.superbiz.app.domain.algo.FixedReserveMath
import com.superbiz.app.domain.algo.GapMath
import com.superbiz.app.domain.algo.HurstMath
import com.superbiz.app.domain.algo.KsMath
import com.superbiz.app.domain.algo.MarginMixMath
import com.superbiz.app.domain.algo.MoverMath
import com.superbiz.app.domain.algo.RunsMath
import com.superbiz.app.domain.algo.ShrinkageMath
import com.superbiz.app.domain.algo.StalenessMath
import com.superbiz.app.domain.algo.WinsorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات الخوارزميات العشرين الجديدة، كل توقع محسوب
 * يدوياً ويطابق الـharness الجري على JVM (scripts/r14-harness/R14Harness.kt — 134/134).
*/
class R14SmartTest {

    private fun near(a: Double, b: Double, eps: Double = 1e-9) = kotlin.math.abs(a - b) <= eps

    @Test
    fun winsor_trimsTailsExactly() {
        val w = WinsorMath.winsorizedMean(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 100.0), 5.0)!!
        assertEquals(1.25, w.lowLimit, 1e-9)
        assertEquals(76.25, w.highLimit, 1e-9)   // sorted[5]=100: 5+0.75×95
        assertEquals(91.5 / 6.0, w.winsorizedMean, 1e-9)
        assertEquals(2, w.changedCount)
        assertNull(WinsorMath.winsorizedMean(listOf(1.0, 2.0), 5.0))
        assertNull(WinsorMath.winsorizedMean(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), 60.0))
    }

    @Test
    fun hurst_distinguishesTrendFromAlternation() {
        assertNull(HurstMath.hurst(List(20) { 5.0 }))            // σ=0
        val alt = (0 until 32).map { if (it % 2 == 0) 1.0 else 2.0 }
        assertTrue(HurstMath.hurst(alt)!!.h < 0.45)
        val trend = (1..32).map { it.toDouble() }
        assertTrue(HurstMath.hurst(trend)!!.h > 0.6)
    }

    @Test
    fun ks_separatesShiftedAndSimilar() {
        val k1 = KsMath.ksStatistic(listOf(1.0, 2.0, 3.0, 4.0, 5.0), listOf(10.0, 11.0, 12.0, 13.0, 14.0))!!
        assertEquals(1.0, k1.d, 1e-9)
        assertEquals("SHIFTED", k1.verdict)
        val k2 = KsMath.ksStatistic(listOf(1.0, 2.0, 3.0, 4.0), listOf(2.0, 3.0, 4.0, 5.0))!!
        assertEquals(0.25, k2.d, 1e-9)
        assertEquals("SIMILAR", k2.verdict)
    }

    @Test
    fun coverage_ratiosAndShortfall() {
        val c = CoverageMath.obligationCoverage(1000.0, 500.0, listOf(600.0, 400.0))!!
        assertEquals(1.5, c.ratio, 1e-9)
        assertEquals("SAFE", c.verdict)
        val c2 = CoverageMath.obligationCoverage(100.0, 100.0, listOf(500.0))!!
        assertEquals(300.0, c2.shortfall, 1e-9)
        assertEquals("DEFICIT", c2.verdict)
        assertNull(CoverageMath.obligationCoverage(100.0, 0.0, listOf(0.0)))
    }

    @Test
    fun campaign_upliftFormula() {
        assertEquals(50.0, CampaignMath.requiredUplift(30.0, 10.0)!!.requiredUpliftPct!!, 1e-9)
        assertEquals("STEEP", CampaignMath.requiredUplift(30.0, 10.0)!!.verdict)
        assertEquals(20.0, CampaignMath.requiredUplift(60.0, 10.0)!!.requiredUpliftPct!!, 1e-9)
        assertEquals("NEVER", CampaignMath.requiredUplift(8.0, 10.0)!!.verdict)
        assertNull(CampaignMath.requiredUplift(8.0, 10.0)!!.requiredUpliftPct)
        assertNull(CampaignMath.requiredUplift(0.0, 5.0))
    }

    @Test
    fun marginMix_averagesAndVerdict() {
        val m = MarginMixMath.marginBySettlement(listOf(30.0, 40.0, 50.0), listOf(20.0, 25.0, 30.0))!!
        assertEquals(40.0, m.cashAvgPct, 1e-9)
        assertEquals(25.0, m.creditAvgPct, 1e-9)
        assertEquals("CASH_BETTER", m.verdict)
        assertNull(MarginMixMath.marginBySettlement(listOf(1.0, 2.0), listOf(1.0, 2.0, 3.0)))
    }

    @Test
    fun drawdown_peakTroughAndState() {
        val d = DrawdownMath.maxDrawdown(listOf(5.0, -3.0, 8.0, -10.0, 4.0))!!
        assertEquals(100.0, d.maxDrawdownPct, 1e-9)
        assertEquals(2, d.peakIndex)
        assertEquals(3, d.troughIndex)
        assertEquals("UNDERWATER", d.state)
        assertNull(DrawdownMath.maxDrawdown(listOf(-1.0, -2.0, -3.0, -4.0)))
    }

    @Test
    fun fairShare_oldestFirstCentsExact() {
        val invs = listOf(
            FairShareMath.OpenInv(1, 100.0, 200L),
            FairShareMath.OpenInv(2, 50.0, 100L),
            FairShareMath.OpenInv(3, 80.0, 300L),
        )
        val r = FairShareMath.applyPayment(invs, 120.0)!!
        assertEquals(50.0, r.allocations[0].amount, 1e-9)   // فاتورة 2 الأقدم
        assertEquals(70.0, r.allocations[1].amount, 1e-9)
        assertEquals(0.0, r.allocations[2].amount, 1e-9)
        assertEquals(1, r.closedCount)
        val r2 = FairShareMath.applyPayment(invs, 300.0)!!
        assertEquals(70.0, r2.unallocated, 1e-9)
        assertEquals(3, r2.closedCount)
        assertNull(FairShareMath.applyPayment(invs, -1.0))
    }

    @Test
    fun runs_medianExcludedAndVerdicts() {
        val ra = RunsMath.runsTest(listOf(1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0))!!
        assertEquals(10, ra.runs)
        assertEquals(6.0, ra.expectedRuns, 1e-9)
        assertEquals("ALTERNATING", ra.verdict)
        val cl = RunsMath.runsTest(listOf(1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0))!!
        assertEquals(2, cl.runs)
        assertEquals("CLUSTERED", cl.verdict)
        assertNull(RunsMath.runsTest(List(10) { 5.0 }))
    }

    @Test
    fun entropy_effectiveCount() {
        val e = EntropyMath.effectiveCategories(listOf(1.0, 1.0, 1.0, 1.0))!!
        assertEquals(kotlin.math.ln(4.0), e.entropy, 1e-9)
        assertEquals(4.0, e.effectiveCount, 1e-9)
        val e2 = EntropyMath.effectiveCategories(listOf(10.0, 1.0, 1.0, 1.0))!!
        assertTrue(e2.effectiveCount < 3.0)
        assertNull(EntropyMath.effectiveCategories(listOf(0.0, 0.0)))
    }

    @Test
    fun abcXyz_cellsMatchCumulativeAndCV() {
        val items = listOf(
            AbcXyzMath.Item(1, 100.0, listOf(4.0, 4.0, 4.0)),   // AX
            AbcXyzMath.Item(2, 50.0, listOf(2.0, 4.0, 6.0)),    // AX cv≈0.408
            AbcXyzMath.Item(3, 30.0, listOf(0.0, 4.0, 8.0)),    // AY cv≈0.816
            AbcXyzMath.Item(4, 20.0, listOf(0.0, 0.0, 12.0)),   // BZ cv≈1.414
        )
        val m = AbcXyzMath.matrix(items)!!
        assertEquals(listOf("AX", "AX", "AY", "BZ"), m.rows.map { it.cell })
        assertEquals(2, m.cellCounts["AX"])
        assertNull(AbcXyzMath.matrix(emptyList()))
    }

    @Test
    fun shrinkage_negativesOnlyAndPct() {
        val s = ShrinkageMath.shrinkage(
            listOf(
                ShrinkageMath.Move(1, -2.0, 10.0),
                ShrinkageMath.Move(2, -1.0, 50.0),
                ShrinkageMath.Move(1, -3.0, 10.0),
                ShrinkageMath.Move(3, 5.0, 10.0),
            ),
            2000.0,
        )!!
        assertEquals(6.0, s.lostQty, 1e-9)
        assertEquals(100.0, s.lostValue, 1e-9)
        assertEquals(5.0, s.pctOfCogs!!, 1e-9)
        assertNull(ShrinkageMath.shrinkage(listOf(ShrinkageMath.Move(1, 5.0, 10.0)), 100.0))
    }

    @Test
    fun hygiene_scoreAndWorst() {
        val h = CatalogHygieneMath.hygiene(
            listOf(
                CatalogHygieneMath.P(1, "123", "عام", 10.0, 15.0, 5.0),
                CatalogHygieneMath.P(2, "", "عام", 10.0, 15.0, 5.0),
                CatalogHygieneMath.P(3, "456", "", 0.0, 15.0, 5.0),
                CatalogHygieneMath.P(4, "789", "عام", 10.0, 15.0, -2.0),
            ),
        )!!
        assertEquals(80.0, h.scorePct, 1e-9)
        assertEquals(listOf(3L, 2L, 4L), h.worst)
        assertNull(CatalogHygieneMath.hygiene(emptyList()))
    }

    @Test
    fun bands_fixedThresholdsAndSortedWorst() {
        val b = BandMath.marginBands(
            listOf(
                BandMath.M(1, 5.0), BandMath.M(2, 15.0), BandMath.M(3, 25.0),
                BandMath.M(4, 45.0), BandMath.M(5, 55.0), BandMath.M(6, -5.0),
            ),
        )!!
        assertEquals(1, b.negative); assertEquals(1, b.thin); assertEquals(1, b.low)
        assertEquals(1, b.ok); assertEquals(1, b.good); assertEquals(1, b.top)
        assertEquals(listOf(6L, 1L), b.belowThin)
    }

    @Test
    fun borda_scoresTieBreaksAndValidation() {
        val r = BordaMath.bordaRank(listOf(listOf(1L, 2L, 3L), listOf(1L, 2L, 3L), listOf(2L, 1L, 3L)))!!
        assertEquals(listOf(1L, 2L, 3L), r.order.map { it.id })
        assertEquals(5, r.order[0].score)
        assertEquals(0, r.order[2].score)
        val tie = BordaMath.bordaRank(listOf(listOf(1L, 2L, 3L), listOf(3L, 2L, 1L)))!!
        assertEquals(listOf(1L, 2L, 3L), tie.order.map { it.id })   // تعادل → الأصغر معرفاً
        assertNull(BordaMath.bordaRank(listOf(listOf(1L, 2L), listOf(2L, 1L, 3L))))
        assertNull(BordaMath.bordaRank(listOf(listOf(1L))))
    }

    @Test
    fun movers_competitiveRanksAndDeltas() {
        val mv = MoverMath.rankMovers(
            mapOf(1L to 100.0, 2L to 80.0, 3L to 60.0, 4L to 40.0),
            mapOf(1L to 40.0, 2L to 60.0, 3L to 80.0, 4L to 100.0),
        )!!
        assertEquals(4L, mv.climbers[0].id)
        assertEquals(3, mv.climbers[0].delta)
        assertEquals(1L, mv.fallers[0].id)
        assertEquals(-3, mv.fallers[0].delta)
        assertNull(MoverMath.rankMovers(mapOf(1L to 10.0), mapOf(1L to 10.0)))
    }

    @Test
    fun blend_weightsFavorAccurateComponent() {
        val lin = (1..12).map { it.toDouble() }
        val b = BlendMath.errorWeightedForecast(lin, 4)!!
        assertTrue(b.wHolt > b.wSma)
        assertTrue(b.maeSma > b.maeHolt)
        assertEquals(1.0, b.wHolt + b.wSma, 1e-9)
        assertTrue(b.forecast[0] > 12.0 && b.forecast[0] < 14.5)
        val bf = BlendMath.errorWeightedForecast(List(12) { 7.0 })!!
        assertEquals(0.5, bf.wHolt, 1e-9)
        assertEquals(7.0, bf.forecast[0], 1e-9)
        assertNull(BlendMath.errorWeightedForecast((1..11).map { it.toDouble() }))
    }

    @Test
    fun staleness_weightedMeanAndMedian() {
        val a = StalenessMath.weightedAge(listOf(100.0 to 10L, 50.0 to 40L, 50.0 to 90L))!!
        assertEquals(37.5, a.weightedMeanDays, 1e-9)
        assertEquals(10.0, a.weightedMedianDays, 1e-9)
        // [P6-M21 إصلاح]: الحكم بالوسيط المرجّح (10 < 30 ⇒ FRESH) لا بالوسطى (37.5 ⇒ AGING)
        assertEquals("FRESH", a.verdict)
        // حالة تمييزية: الوسطى المرجّحة 50 (AGING لو حكمنا بها) لكن الوسيط 10 ⇒ FRESH
        val b = StalenessMath.weightedAge(listOf(50.0 to 10L, 50.0 to 10L, 50.0 to 90L, 50.0 to 90L))!!
        assertEquals(50.0, b.weightedMeanDays, 1e-9)
        assertEquals(10.0, b.weightedMedianDays, 1e-9)
        assertEquals("FRESH", b.verdict)
        // ووسيط ثقيل قديم يرفع الحكم إلى STALE رغم وسطى أقل من 60
        val c = StalenessMath.weightedAge(listOf(30.0 to 10L, 30.0 to 10L, 140.0 to 90L))!!
        assertEquals(66.0, c.weightedMeanDays, 1e-9)
        assertEquals(90.0, c.weightedMedianDays, 1e-9)
        assertEquals("STALE", c.verdict)
        assertNull(StalenessMath.weightedAge(listOf(0.0 to 5L, 0.0 to 5L)))
    }

    @Test
    fun gapTerms_medianP90Verdicts() {
        val g = GapMath.checkTermsGap(listOf(10.0, 20.0, 30.0, 40.0, 90.0))!!
        assertEquals(30.0, g.medianDays, 1e-9)
        assertEquals(70.0, g.p90Days, 1e-9)
        assertEquals(90.0, g.maxDays, 1e-9)
        assertEquals("MEDIUM", g.verdict)
        assertNull(GapMath.checkTermsGap(listOf(5.0, 8.0)))
        assertNull(GapMath.checkTermsGap(listOf(-5.0, 8.0, 10.0)))
    }

    @Test
    fun reserve_perDayAndCoverage() {
        val r = FixedReserveMath.dailyFixedReserve(12000.0, 24, 600.0)!!
        assertEquals(500.0, r.perWorkingDay, 1e-9)
        assertEquals(1.2, r.coverage, 1e-9)
        assertEquals("SAFE", r.verdict)
        assertEquals("STRAINED", FixedReserveMath.dailyFixedReserve(12000.0, 24, 480.0)!!.verdict)
        assertEquals("TIGHT", FixedReserveMath.dailyFixedReserve(12000.0, 24, 550.0)!!.verdict)
        assertNull(FixedReserveMath.dailyFixedReserve(0.0, 24, 600.0))
    }
}
