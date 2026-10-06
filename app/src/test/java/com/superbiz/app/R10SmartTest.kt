package com.superbiz.app

import com.superbiz.app.domain.algo.BasketMath
import com.superbiz.app.domain.algo.FlowMath
import com.superbiz.app.domain.algo.GoalMath
import com.superbiz.app.domain.algo.MarginMath
import com.superbiz.app.domain.algo.PricingMath
import com.superbiz.app.domain.algo.QualityMath
import com.superbiz.app.domain.algo.RiskMath
import com.superbiz.app.domain.algo.SeasonMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10-الموجةA — اختبارات الخوارزميات العشرين الجديدة (R10Smart).
 * عقود حتمية: نفس المدخل ⇒ نفس المخرج.
 */
class R10SmartTest {

    // ═══ A1 PricingMath.zScoreOutliers ═══

    @Test fun a1_zScore_detectsSingleHugeOutlier() {
        // ضوضاء حقيقية حتى لا تكون MAD=0 (عقد MAD=0 ⇒ فارغ موثق)
        val vals = List(10) { 100.0 + (it % 3) * 5.0 } + listOf(1000.0)
        val out = PricingMath.zScoreOutliers(vals)
        assertEquals(1, out.size)
        assertEquals(10, out[0].index)
        assertEquals(1000.0, out[0].value, 1e-9)
    }

    @Test fun a1_zScore_emptyOrFlatIsSilent() {
        assertTrue(PricingMath.zScoreOutliers(emptyList()).isEmpty())
        assertTrue(PricingMath.zScoreOutliers(listOf(5.0, 5.0, 5.0, 5.0, 5.0)).isEmpty()) // MAD=0
        assertTrue(PricingMath.zScoreOutliers(listOf(1.0, 2.0)).isEmpty())                // حجم غير كافٍ
    }

    // ═══ A2 PricingMath.markdownLadder ═══

    @Test fun a2_ladder_appliesDeepestEligibleStep() {
        val p = PricingMath.markdownLadder(100.0, 40.0, ageDays = 130)
        assertEquals(80.0, p, 1e-9) // شريحة 120 يوماً: −20%
    }

    @Test fun a2_ladder_floorAtCost_and_youngStockUntouched() {
        // سعر قريب من التكلفة: الأرضية تمنع النزول تحت 40
        assertEquals(40.0, PricingMath.markdownLadder(42.0, 40.0, ageDays = 200), 1e-9)
        // مخزون شباب 30 يوماً: بلا تخفيض
        assertEquals(100.0, PricingMath.markdownLadder(100.0, 40.0, ageDays = 30), 1e-9)
    }

    // ═══ A3 SeasonMath.weekdayProfile ═══

    @Test fun a3_weekday_bestDayHasHighestIndex() {
        // الخميس(4) يتصدّر، الثلاثاء(2) أضعف
        val pts = listOf(1 to 100.0, 2 to 50.0, 4 to 200.0, 1 to 100.0, 4 to 300.0)
        val prof = SeasonMath.weekdayProfile(pts)
        assertEquals(4, prof.first().weekday)
        assertTrue(prof.first().indexPct > 100)
        assertEquals(2, prof.last().weekday)
        assertTrue(prof.last().indexPct < 100)
    }

    @Test fun a3_weekday_emptyInput() {
        assertTrue(SeasonMath.weekdayProfile(emptyList()).isEmpty())
    }

    // ═══ A4 SeasonMath.hotCells ═══

    @Test fun a4_hotCells_topKByValue_deterministicTieBreak() {
        val m = Array(7) { DoubleArray(24) }
        m[3][20] = 500.0; m[1][13] = 250.0; m[5][21] = 125.0; m[6][9] = 125.0
        val top = SeasonMath.hotCells(m, topK = 3)
        assertEquals(3, top.size)
        assertEquals(3 to 20, top[0].day to top[0].hour)
        assertEquals(100, top[0].shareOfMaxPct)
        // تعادل 125: يفوز أصغر يوم (5 قبل 6)
        assertEquals(5, top[2].day)
    }

    @Test fun a4_hotCells_nullOrAllZero() {
        assertTrue(SeasonMath.hotCells(null).isEmpty())
        assertTrue(SeasonMath.hotCells(Array(7) { DoubleArray(24) }).isEmpty())
    }

    // ═══ A5 MarginMath.portfolioMargin ═══

    @Test fun a5_portfolio_marginAndHhi() {
        // حصص 60/30/10 ⇒ HHI = 3600+900+100 = 4600 (مقياس 0..10000) ⇒ HIGH
        val rev = mapOf("A" to 600.0, "B" to 300.0, "C" to 100.0)
        val cost = mapOf("A" to 300.0, "B" to 150.0, "C" to 50.0)
        val p = MarginMath.portfolioMargin(rev, cost)!!
        assertEquals(50.0, p.marginPct, 1e-9)
        assertEquals(4600.0, p.hhi, 1e-9)
        assertEquals("HIGH", p.verdict)
        // توزيع متساوٍ على 10 عناصر ⇒ HHI = 1000 ⇒ LOW
        val spread = (1..10).associate { "P$it" to 100.0 }
        val q = MarginMath.portfolioMargin(spread, spread.mapValues { it.value / 2.0 })!!
        assertEquals(1000.0, q.hhi, 1e-9)
        assertEquals("LOW", q.verdict)
    }

    @Test fun a5_portfolio_nullOnNoRevenue() {
        assertNull(MarginMath.portfolioMargin(mapOf("A" to 0.0), mapOf("A" to 10.0)))
        assertNull(MarginMath.portfolioMargin(emptyMap(), emptyMap()))
    }

    // ═══ A6 MarginMath.paretoABC ═══

    @Test fun a6_pareto_80_95_cut() {
        // تراكمي: A=60%(A)، B=80%(A لأن ≤80)، C=90%(B)، D=100%(C)
        val rev = mapOf("A" to 60.0, "B" to 20.0, "C" to 10.0, "D" to 10.0)
        val rows = MarginMath.paretoABC(rev)
        assertEquals('A', rows[0].klass)
        assertEquals('A', rows[1].klass)
        assertEquals('B', rows[2].klass)
        assertEquals('C', rows[3].klass)
        assertEquals(100.0, rows.last().cumSharePct, 0.01)
    }

    @Test fun a6_pareto_empty() = assertTrue(MarginMath.paretoABC(emptyMap()).isEmpty())

    // ═══ A7 MarginMath.capitalEfficiency ═══

    @Test fun a7_efficiency_rankingExcludesZeroCapital() {
        val eff = MarginMath.capitalEfficiency(
            profitByName = mapOf("X" to 50.0, "Y" to 30.0, "Z" to 99.0),
            stockCapitalByName = mapOf("X" to 100.0, "Y" to 10.0, "Z" to 0.0),
        )
        assertEquals(listOf("Y", "X"), eff.map { it.name }) // Y: 300/100، X: 50/100 — Z مستبعد
        assertEquals(300.0, eff[0].profitPerHundred, 1e-9)
    }

    // ═══ A8 FlowMath.agingBuckets ═══

    @Test fun a8_buckets_assignment() {
        val today = 10_000L
        val inv = listOf(
            today to 500.0,            // غير مستحقة ⇒ 0-30
            today - 20 to 300.0,       // 20 يوماً ⇒ 0-30
            today - 45 to 200.0,       // 45 ⇒ 31-60
            today - 75 to 100.0,       // 75 ⇒ 61-90
            today - 120 to 400.0,      // 120 ⇒ 90+
            today - 10 to 0.0,         // مفتوح=0 تُهمل
        )
        val b = FlowMath.agingBuckets(inv, today)
        assertEquals(4, b.size)
        assertEquals(800.0, b[0].amount, 1e-9); assertEquals(2, b[0].count)
        assertEquals(200.0, b[1].amount, 1e-9)
        assertEquals(100.0, b[2].amount, 1e-9)
        assertEquals(400.0, b[3].amount, 1e-9)
    }

    @Test fun a8_buckets_empty() = assertTrue(FlowMath.agingBuckets(emptyList(), 1L).isEmpty())

    // ═══ A9 FlowMath.collectionForecast ═══

    @Test fun a9_forecast_probWeighted_withinHorizon() {
        val today = 10_000L
        val inv = listOf(
            today to 100.0,          // ضمن الأفق، شريحة 0-30 ⇒ 0.95
            today + 10 to 200.0,     // ضمن الأفق (غير مستحقة) ⇒ 0.95
            today + 30 to 400.0,     // خارج أفق 14 يوماً ⇒ تُهمل
        )
        assertEquals(0.95 * 300.0, FlowMath.collectionForecast(inv, today, horizonDays = 14), 1e-6)
    }

    @Test fun a9_forecast_zeroHorizon() =
        assertEquals(0.0, FlowMath.collectionForecast(listOf(1L to 100.0), 1L, horizonDays = 0), 1e-9)

    // ═══ A10 FlowMath.dsoTrend ═══

    @Test fun a10_dso_risingTrend() {
        val r = FlowMath.dsoTrend(listOf(700.0 to 700.0, 800.0 to 700.0, 900.0 to 700.0))!!
        assertEquals(7.0, r.points[0].dsoDays, 1e-9)
        assertEquals(9.0, r.latest!!, 1e-9)
        assertEquals(1, r.direction)
    }

    @Test fun a10_dso_nullWhenThin() {
        assertNull(FlowMath.dsoTrend(emptyList()))
        assertNull(FlowMath.dsoTrend(listOf(100.0 to 0.0)))           // أسبوع واحد صالح
        assertNull(FlowMath.dsoTrend(listOf(100.0 to 0.0, 200.0 to 0.0))) // أسبوعان بلا مبيعات
    }

    // ═══ A11 BasketMath.marketBasketLift ═══

    @Test fun a11_lift_orderAndFilters() {
        val baskets = listOf(
            listOf(1L, 2L), listOf(1L, 2L), listOf(1L, 2L), listOf(1L, 3L), listOf(4L),
        )
        val rows = BasketMath.marketBasketLift(baskets, minSupport = 2)
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(1L, r.a); assertEquals(2L, r.b)
        assertEquals(60.0, r.supportPct, 1e-9)     // 3/5
        assertEquals(75.0, r.confidencePct, 1e-9)  // 3/4
        assertEquals(1.25, r.lift, 1e-9)           // 0.75/0.6
    }

    @Test fun a11_lift_emptyBaskets() =
        assertTrue(BasketMath.marketBasketLift(emptyList()).isEmpty())

    // ═══ A12 BasketMath.nextProduct ═══

    @Test fun a12_nextProduct_markovFirstOrder() {
        val baskets = listOf(
            listOf(10L, 20L), listOf(10L, 20L), listOf(10L, 30L), listOf(20L, 10L),
        )
        val next = BasketMath.nextProduct(baskets, productId = 10L)
        assertEquals(listOf(20L, 30L), next.map { it.nextId })
        assertEquals(2, next[0].count)
    }

    @Test fun a12_nextProduct_none() =
        assertTrue(BasketMath.nextProduct(listOf(listOf(1L)), productId = 9L).isEmpty())

    // ═══ A13 RiskMath.healthScore ═══

    @Test fun a13_health_perfectAndAwful() {
        val good = RiskMath.healthScore(2.0, 5.0, 0.0, 0.0)
        assertEquals(100, good.score)
        assertEquals("HEALTHY", good.band)
        val bad = RiskMath.healthScore(0.0, -5.0, 2.0, 1.0)
        assertEquals(0, bad.score)
        assertEquals("RISK", bad.band)
    }

    @Test fun a13_health_weightsSumTo100() {
        val mid = RiskMath.healthScore(1.0, 0.0, 1.0, 0.5)
        // سيولة 50×0.35 + هامش 50×0.25 + دين 50×0.25 + مصروف 50×0.15 = 50
        assertEquals(50, mid.score)
        assertEquals("WATCH", mid.band)
    }

    // ═══ A14 RiskMath.customerConcentration ═══

    @Test fun a14_concentration_top3AndHhi() {
        // حصص 50/30/20 ⇒ HHI = 2500+900+400 = 3800 ⇒ HIGH، وأكبر اثنين = 80%
        val rev = mapOf("أ" to 500.0, "ب" to 300.0, "ج" to 200.0)
        val c = RiskMath.customerConcentration(rev, topN = 2)!!
        assertEquals(80.0, c.topSharePct, 1e-9)
        assertEquals(3800.0, c.hhi, 1e-9)
        assertEquals("HIGH", c.verdict)
        // 10 عملاء متساوين ⇒ HHI = 1000 ⇒ LOW
        val spread = (1..10).associate { "عميل$it" to 100.0 }
        val low = RiskMath.customerConcentration(spread, topN = 3)!!
        assertEquals(30.0, low.topSharePct, 1e-9)
        assertEquals("LOW", low.verdict)
    }

    @Test fun a14_concentration_nullOnEmpty() = assertNull(RiskMath.customerConcentration(emptyMap()))

    // ═══ A15 RiskMath.maturityLadder ═══

    @Test fun a15_ladder_overdueFallsInWeek1_andFarChecksClipped() {
        val today = 10_000L
        val ladder = RiskMath.maturityLadder(
            listOf(today - 5, today + 3, today + 9, today + 100, today - 2),
            today, weeks = 8,
        )
        val w1 = ladder.first { it.week == 1 }
        assertEquals(3, w1.count) // متأخران + بعد 3 أيام
        val w2 = ladder.first { it.week == 2 }
        assertEquals(1, w2.count) // بعد 9 أيام
        assertTrue(ladder.none { it.week > 2 }) // بعد 100 يوم خارج أفق 8 أسابيع
    }

    @Test fun a15_ladder_empty() = assertTrue(RiskMath.maturityLadder(emptyList(), 1L).isEmpty())

    // ═══ A16 GoalMath.requiredPace ═══

    @Test fun a16_pace_onTrack_vsBoost() {
        val ok = GoalMath.requiredPace(remaining = 900.0, daysLeft = 3, capacityPerDay = 400.0)
        assertEquals(300.0, ok.requiredPerDay, 1e-9)
        assertTrue(ok.feasible)
        assertEquals("ON_TRACK", ok.verdict)
        val hard = GoalMath.requiredPace(remaining = 1500.0, daysLeft = 3, capacityPerDay = 400.0)
        assertFalse(hard.feasible)
        assertEquals("NEEDS_BOOST", hard.verdict)
        assertEquals("EXPIRED", GoalMath.requiredPace(100.0, 0, 10.0).verdict)
    }

    // ═══ A17 GoalMath.milestoneProjection ═══

    @Test fun a17_milestones_linearPace() {
        val m = GoalMath.milestoneProjection(done = 0.0, target = 1000.0, pacePerDay = 100.0)
        assertEquals(3, m.p25)   // 250/100 ⇒ ceil 2.5=3
        assertEquals(5, m.p50)
        assertEquals(8, m.p75)
        assertEquals(10, m.p100)
    }

    @Test fun a17_milestones_passedAndNullPace() {
        val m = GoalMath.milestoneProjection(done = 600.0, target = 1000.0, pacePerDay = 100.0)
        assertNull(m.p25); assertNull(m.p50)
        assertEquals(2, m.p75)
        assertEquals(4, m.p100)
        val z = GoalMath.milestoneProjection(0.0, 1000.0, 0.0)
        assertNull(z.p25); assertNull(z.p100)
    }

    // ═══ A18 QualityMath.dupScore ═══

    @Test fun a18_dup_identicalScoresHigh_arabicTolerant() {
        val s = QualityMath.dupScore("مؤسسة النور للتجارة", "مؤسسة النور للتجارة")
        assertEquals(100, s)
        // تطبيع عربي: أ/ا، ه/ة
        val near = QualityMath.dupScore("مؤسسة النور للتجاره", "مؤسسة النور للتجارة")
        assertTrue("near=$near", near >= 90)
        assertTrue(QualityMath.dupScore("شركة الفجر", "مطعم البركة") < 50)
    }

    @Test fun a18_dup_emptyZero() {
        assertEquals(0, QualityMath.dupScore("", "شركة"))
        assertEquals(0, QualityMath.dupScore("شركة", " "))
    }

    // ═══ A19 QualityMath.outliersIQR ═══

    @Test fun a19_iqr_detectsFenceViolations() {
        val vals = List(10) { it + 1.0 } + listOf(100.0) // 1..10 ثم 100
        val r = QualityMath.outliersIQR(vals)!!
        assertEquals(listOf(10), r.outlierIndices)
        assertTrue(r.upperFence < 100.0)
    }

    @Test fun a19_iqr_nullOrNoSpread() {
        assertNull(QualityMath.outliersIQR(listOf(1.0, 2.0, 3.0)))
        val flat = QualityMath.outliersIQR(List(6) { 7.0 })!!
        assertTrue(flat.outlierIndices.isEmpty())
    }

    // ═══ A20 QualityMath.roundingSweep ═══

    @Test fun a20_rounding_greedyCoins_documented() {
        val r = QualityMath.roundingSweep(listOf(137.75, 500.0))
        // 137.75: 100×1 + 10×3 + 5×1 + 1×2 + 0.5×1 + 0.25×1 = 9 قطع
        // 500: قطعة واحدة
        assertEquals(10, r.coinCount)
        assertEquals(0.0, r.delta, 1e-9)
    }

    @Test fun a20_rounding_ignoresNegative_andEmpty() {
        val r = QualityMath.roundingSweep(listOf(-5.0, 0.0))
        assertEquals(0, r.coinCount)
        assertEquals(QualityMath.Rounding(0, 0.0), QualityMath.roundingSweep(emptyList()))
    }
}
