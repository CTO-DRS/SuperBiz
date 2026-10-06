package com.superbiz.app

import com.superbiz.app.domain.algo.AllocMath
import com.superbiz.app.domain.algo.CadenceMath
import com.superbiz.app.domain.algo.CorrMath
import com.superbiz.app.domain.algo.DispersionMath
import com.superbiz.app.domain.algo.ExpenseMixMath
import com.superbiz.app.domain.algo.FxDriftMath
import com.superbiz.app.domain.algo.FloatMath
import com.superbiz.app.domain.algo.GiniMath
import com.superbiz.app.domain.algo.HalfLifeMath
import com.superbiz.app.domain.algo.LoanMath
import com.superbiz.app.domain.algo.LossMakerMath
import com.superbiz.app.domain.algo.PartyMatchMath
import com.superbiz.app.domain.algo.PercentileMath
import com.superbiz.app.domain.algo.RobustMath
import com.superbiz.app.domain.algo.WorkingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * — اختبارات الخوارزميات العشرين في R13Smart (مرآة الـharness على JVM)
 * كل توقع محسوب يدوياً وموثق في docs/ALGORITHMS.md.
*/
class R13SmartTest {

    private fun near(a: Double, b: Double, eps: Double = 1e-6) = abs(a - b) <= eps

    // ═══ 1) theilSen ═══
    @Test fun theilSen_detectsCleanRise() {
        val t = RobustMath.theilSen(listOf(1.0, 2.0, 3.0, 4.0, 5.0))!!
        assertEquals(1.0, t.slope, 1e-9)
        assertEquals("RISING", t.verdict)
    }
    @Test fun theilSen_immuneToSingleOutlier() {
        // ستة أزواج من النقاط الأربع الأولى بميل 1.0 — وسيط الميول يبقى 1.0
        // (المربعات الصغرى كانت ستعطي ≈23.7)
        val t = RobustMath.theilSen(listOf(1.0, 2.0, 3.0, 4.0, 100.0))!!
        assertEquals(1.0, t.slope, 1e-9)
    }
    @Test fun theilSen_honestOnSparseOrDirty() {
        assertNull(RobustMath.theilSen(listOf(1.0, 2.0, 3.0)))
        assertNull(RobustMath.theilSen(listOf(1.0, 2.0, Double.NaN, 4.0, 5.0)))
    }

    // ═══ 2) madOutliers ═══
    @Test fun mad_findsExtremeWithoutPoisoning() {
        val m = RobustMath.madOutliers(listOf(10.0, 11.0, 10.0, 12.0, 10.0, 11.0, 60.0), 3.5)!!
        assertEquals(11.0, m.median, 1e-9)
        assertEquals(1.0, m.mad, 1e-9)
        assertEquals(listOf(6), m.outlierIndices)
    }
    @Test fun mad_zeroDispersion_staysSilent() {
        assertNull(RobustMath.madOutliers(listOf(5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0)))
        assertNull(RobustMath.madOutliers(listOf(1.0, 2.0, 3.0, 4.0, 99.0)))
    }

    // ═══ 3) largestRemainder ═══
    @Test fun largestRemainder_sumIsExact() {
        val r = AllocMath.largestRemainder(10.0, listOf(5.0, 5.0, 5.0))!!
        assertEquals(3.34, r[0], 1e-9)
        assertEquals(10.0, r.sum(), 1e-9)
        val r2 = AllocMath.largestRemainder(100.0, listOf(1.0, 1.0, 1.0))!!
        assertEquals(100.0, r2.sum(), 1e-9)
    }
    @Test fun largestRemainder_rejectsBadWeights() {
        assertNull(AllocMath.largestRemainder(10.0, listOf(1.0, -1.0)))
        // أوزان سالبة بمجموع موجب كانت تمرّ وتنتج نصيباً سالباً
        assertNull(AllocMath.largestRemainder(100.0, listOf(10.0, -5.0)))
        assertNull(AllocMath.largestRemainder(10.0, listOf(0.0, 0.0)))
        assertNull(AllocMath.largestRemainder(-1.0, listOf(1.0)))
    }

    // ═══ 4) cashBreakdown ═══
    @Test fun cashBreakdown_greedyLargestFirst() {
        val c = AllocMath.cashBreakdown(1375.75)!!
        assertEquals(2, c.pieces.first { it.first == 500.0 }.second)
        assertEquals(3, c.pieces.first { it.first == 100.0 }.second)
        assertEquals(11, c.totalPieces)
        assertEquals(0.0, c.leftover, 1e-9)
        assertEquals(0.24, AllocMath.cashBreakdown(1375.74)!!.leftover, 1e-9)
    }
    @Test fun cashBreakdown_honestEdges() {
        assertEquals(0, AllocMath.cashBreakdown(0.0)!!.totalPieces)
        assertNull(AllocMath.cashBreakdown(-5.0))
    }

    // ═══ 5-6) pearson / spearman ═══
    @Test fun pearson_perfectBounds() {
        assertEquals(1.0, CorrMath.pearson(listOf(1.0, 2.0, 3.0, 4.0, 5.0), listOf(2.0, 4.0, 6.0, 8.0, 10.0))!!, 1e-9)
        assertEquals(-1.0, CorrMath.pearson(listOf(1.0, 2.0, 3.0, 4.0, 5.0), listOf(10.0, 8.0, 6.0, 4.0, 2.0))!!, 1e-9)
        assertNull(CorrMath.pearson(listOf(1.0, 2.0, 3.0), listOf(5.0, 5.0, 5.0)))
        assertNull(CorrMath.pearson(listOf(1.0, 2.0), listOf(1.0, 2.0)))
    }
    @Test fun spearman_capturesMonotoneAndTies() {
        assertEquals(1.0, CorrMath.spearman(listOf(1.0, 2.0, 3.0, 4.0), listOf(1.0, 4.0, 9.0, 16.0))!!, 1e-9)
        assertEquals(0.94868, CorrMath.spearman(listOf(1.0, 1.0, 2.0, 3.0), listOf(5.0, 7.0, 8.0, 9.0))!!, 1e-4)
    }

    // ═══ 7) drySpells ═══
    @Test fun drySpells_streaksAndPct() {
        val d = CadenceMath.drySpells(listOf(10.0, 0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 20.0))!!
        assertEquals(3, d.longestDry)
        assertEquals(5, d.dryDays)
        assertEquals(0, d.currentDry)
        assertEquals(62.5, d.dryPct, 1e-9)
        assertEquals(3, CadenceMath.drySpells(listOf(0.0, 0.0, 0.0))!!.currentDry)
        assertNull(CadenceMath.drySpells(emptyList()))
    }

    // ═══ 8) regularityCV ═══
    @Test fun regularityCV_verdictLadder() {
        assertEquals("REGULAR", CadenceMath.regularityCV(listOf(7.0, 7.0, 7.0))!!.verdict)
        val r = CadenceMath.regularityCV(listOf(2.0, 10.0, 2.0, 10.0))!!
        assertEquals(0.666667, r.cv, 1e-4)
        assertEquals("NORMAL", r.verdict)
        assertEquals("IRREGULAR", CadenceMath.regularityCV(listOf(1.0, 2.0, 30.0))!!.verdict)
        assertNull(CadenceMath.regularityCV(listOf(5.0, 5.0)))
        assertNull(CadenceMath.regularityCV(listOf(0.0, 1.0, 2.0)))
    }

    // ═══ 9) gini ═══
    @Test fun gini_knownValues() {
        assertEquals(0.666667, GiniMath.gini(listOf(0.0, 0.0, 100.0))!!, 1e-4)
        assertEquals(0.0, GiniMath.gini(listOf(50.0, 50.0))!!, 1e-9)
        assertEquals(0.22222, GiniMath.gini(listOf(10.0, 20.0, 30.0))!!, 1e-4)
        assertEquals(0.0, GiniMath.gini(listOf(0.0, 0.0))!!, 1e-9)
        assertNull(GiniMath.gini(listOf(-1.0, 5.0)))
    }

    // ═══ 10-11) percentileRank / percentiles ═══
    @Test fun percentileRank_midpointMethod() {
        assertEquals(62.5, PercentileMath.percentileRank(listOf(10.0, 20.0, 30.0, 40.0), 30.0)!!, 1e-9)
        assertEquals(100.0, PercentileMath.percentileRank(listOf(10.0, 20.0, 30.0, 40.0), 100.0)!!, 1e-9)
        assertNull(PercentileMath.percentileRank(emptyList(), 1.0))
    }
    @Test fun percentiles_linearInterpolationR7() {
        val ps = PercentileMath.percentiles(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0))!!
        assertEquals(3.25, ps.first { it.first == 25.0 }.second, 1e-9)
        assertEquals(5.5, ps.first { it.first == 50.0 }.second, 1e-9)
        assertEquals(7.75, ps.first { it.first == 75.0 }.second, 1e-9)
        assertEquals(9.1, ps.first { it.first == 90.0 }.second, 1e-9)
        assertNull(PercentileMath.percentiles(listOf(1.0)))
        assertNull(PercentileMath.percentiles(listOf(1.0, 2.0), listOf(150.0)))
    }

    // ═══ 12) cashConversionCycle ═══
    @Test fun ccc_componentsAndVerdict() {
        val c = WorkingMath.cashConversionCycle(30000.0, 20000.0, 10000.0, 1000.0, 500.0, 500.0)
        assertEquals(30.0, c.dso!!, 1e-9)
        assertEquals(40.0, c.dio!!, 1e-9)
        assertEquals(20.0, c.dpo!!, 1e-9)
        assertEquals(50.0, c.ccc!!, 1e-9)
        assertEquals("HEAVY", c.verdict)
        val c2 = WorkingMath.cashConversionCycle(1000.0, 1000.0, 1000.0, 0.0, 0.0, 0.0)
        assertNull(c2.ccc)
        assertEquals("UNKNOWN", c2.verdict)
    }

    // ═══ 13) impliedRate ═══
    @Test fun impliedRate_bisectionSolvesAnnuity() {
        val ir = LoanMath.impliedRate(10000.0, 900.0, 12)!!
        assertTrue(ir.monthlyRate in 0.0115..0.0125)
        assertTrue(ir.annualPct in 13.8..15.0)
        assertEquals("NORMAL", ir.verdict)
    }
    @Test fun impliedRate_honestBoundaries() {
        assertNull(LoanMath.impliedRate(10000.0, 800.0, 12))     // إجمالي الأقساط < الأصل
        assertEquals(0.0, LoanMath.impliedRate(10000.0, 10000.0 / 12.0, 12)!!.monthlyRate, 1e-12)
        assertTrue(LoanMath.impliedRate(10000.0, 834.0, 12)!!.annualPct < 2.0)
        assertNull(LoanMath.impliedRate(1000.0, 600.0, 1))
        assertNull(LoanMath.impliedRate(0.0, 100.0, 12))
        val heavy = LoanMath.impliedRate(1000.0, 400.0, 3)!!
        assertTrue(heavy.monthlyRate in 0.09..0.10)
    }

    // ═══ 14) phoneMatch ═══
    @Test fun phoneMatch_normalizesSaudiPrefixes() {
        assertEquals("LIKELY", PartyMatchMath.phoneMatch("0501234567", "+966501234567").verdict)
        assertEquals("501234567", PartyMatchMath.phoneMatch("0501234567", "+966501234567").core)
        assertEquals("EXACT", PartyMatchMath.phoneMatch("0501234567", "0501234567").verdict)
        assertEquals("LIKELY", PartyMatchMath.phoneMatch("00966501234567", "0501234567").verdict)
        assertEquals("NO", PartyMatchMath.phoneMatch("0501234567", "0512345678").verdict)
        assertEquals("NO", PartyMatchMath.phoneMatch("", "0501234567").verdict)
    }

    // ═══ 15) rateDrift ═══
    @Test fun rateDrift_verdictLadder() {
        assertEquals("STABLE", FxDriftMath.rateDrift(listOf(3.70, 3.75, 3.75), 3.75)!!.verdict)
        assertEquals(9.3333, FxDriftMath.rateDrift(listOf(3.70, 3.75, 3.75), 4.10)!!.driftPct, 1e-3)
        assertEquals("WATCH", FxDriftMath.rateDrift(listOf(3.70, 3.75, 3.75), 3.85)!!.verdict)
        assertNull(FxDriftMath.rateDrift(listOf(3.7, 3.8), 3.8))
        assertNull(FxDriftMath.rateDrift(listOf(0.0, 3.7, 3.8), 3.8))
    }

    // ═══ 16) floatDays ═══
    @Test fun floatDays_stats() {
        val f = FloatMath.floatDays(listOf(3.0, 5.0, 7.0))!!
        assertEquals(5.0, f.medianDays, 1e-9)
        assertEquals(6.6, f.p90Days, 1e-9)
        assertEquals(7.0, f.maxDays, 1e-9)
        assertEquals("FAST", f.verdict)
        val f2 = FloatMath.floatDays(listOf(10.0, 14.0, 18.0, 25.0, 40.0))!!
        assertEquals(18.0, f2.medianDays, 1e-9)
        assertEquals(34.0, f2.p90Days, 1e-9)
        assertEquals("NORMAL", f2.verdict)
        assertNull(FloatMath.floatDays(listOf(1.0, 2.0)))
        assertNull(FloatMath.floatDays(listOf(-1.0, 2.0, 3.0)))
    }

    // ═══ 17) topRiser ═══
    @Test fun topRiser_biggestShareGainWins() {
        val r = ExpenseMixMath.topRiser(
            before = mapOf("A" to 600.0, "B" to 400.0),
            after = mapOf("A" to 600.0, "B" to 400.0, "C" to 200.0),
        )!!
        assertEquals("C", r.category)
        assertEquals(16.6667, r.deltaPctPoints, 1e-3)
        assertEquals("SHIFT", r.verdict)
        assertNull(ExpenseMixMath.topRiser(mapOf("A" to 0.0), mapOf("A" to 5.0)))
        assertNull(ExpenseMixMath.topRiser(emptyMap(), mapOf("A" to 5.0)))
    }

    // ═══ 18) belowCost ═══
    @Test fun belowCost_aggregatesLosses() {
        val rows = LossMakerMath.belowCost(listOf(
            LossMakerMath.Line(1L, 2.0, 8.0, 10.0),
            LossMakerMath.Line(1L, 1.0, 11.0, 10.0),   // فوق التكلفة — يُهمَل
            LossMakerMath.Line(2L, 3.0, 5.0, 6.0),
            LossMakerMath.Line(3L, 1.0, 20.0, 15.0),   // سليم
            LossMakerMath.Line(4L, 0.0, 5.0, 9.0),     // كمية صفر — يُهمَل
        ))
        assertEquals(2, rows.size)
        assertEquals(1L, rows[0].productId)
        assertEquals(4.0, rows[0].lost, 1e-9)
        assertEquals(3.0, rows[1].lost, 1e-9)
        assertEquals(1, rows[0].linesBelow)
        assertTrue(LossMakerMath.belowCost(emptyList()).isEmpty())
    }

    // ═══ 19) engagement ═══
    @Test fun engagement_halfLifeWeights() {
        val en = HalfLifeMath.engagement(listOf(
            Triple(1L, 1001.0, 0L),
            Triple(1L, 1000.0, 90L),
            Triple(2L, 1500.0, 0L),
            Triple(3L, 10000.0, 365L),
            Triple(4L, 500.0, -5L),   // عمر سالب — يُهمَل
        ), 90.0)!!
        assertEquals(3, en.size)
        assertEquals(1L, en[0].partyId)
        assertEquals(1501.0, en[0].score, 1e-9)
        assertEquals(1500.0, en[1].score, 1e-9)
        assertEquals(601.39, en[2].score, 0.5)
        assertNull(HalfLifeMath.engagement(listOf(Triple(1L, 1.0, 0L)), 0.0))
        assertTrue(HalfLifeMath.engagement(emptyList(), 90.0)!!.isEmpty())
    }

    // ═══ 20) priceSpread ═══
    @Test fun priceSpread_verdictLadder() {
        assertEquals("TIGHT", DispersionMath.priceSpread(listOf(100.0, 100.0, 105.0))!!.verdict)
        val w = DispersionMath.priceSpread(listOf(100.0, 120.0))!!
        assertEquals(20.0, w.spreadPct, 1e-9)
        assertEquals("WILD", w.verdict)
        assertEquals("LOOSE", DispersionMath.priceSpread(listOf(100.0, 110.0))!!.verdict)
        assertEquals("UNIFORM", DispersionMath.priceSpread(listOf(100.0, 100.0))!!.verdict)
        assertNull(DispersionMath.priceSpread(listOf(100.0)))
        assertNull(DispersionMath.priceSpread(listOf(0.0, -5.0, 100.0)))
    }
}
