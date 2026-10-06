package com.superbiz.app

import com.superbiz.app.domain.algo.BayesMath
import com.superbiz.app.domain.algo.BenfordMath
import com.superbiz.app.domain.algo.CusumMath
import com.superbiz.app.domain.algo.ElasticityMath
import com.superbiz.app.domain.algo.FourierMath
import com.superbiz.app.domain.algo.GamblerMath
import com.superbiz.app.domain.algo.GrubbsMath
import com.superbiz.app.domain.algo.IqrMath
import com.superbiz.app.domain.algo.JaccardMath
import com.superbiz.app.domain.algo.KappaMath
import com.superbiz.app.domain.algo.LogitMath
import com.superbiz.app.domain.algo.MarkovMath
import com.superbiz.app.domain.algo.NelsonMath
import com.superbiz.app.domain.algo.OeeMath
import com.superbiz.app.domain.algo.ParetoMath
import com.superbiz.app.domain.algo.RecencyMath
import com.superbiz.app.domain.algo.TheilMath
import com.superbiz.app.domain.algo.UtestMath
import com.superbiz.app.domain.algo.VaRMath
import com.superbiz.app.domain.algo.ZipfMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * — اختبارات الخوارزميات العشرين الجديدة، كل توقع محسوب
 * يدوياً ويطابق الـharness الجري على JVM (scripts/r15-harness — 101/101).
*/
class R15SmartTest {

    private fun near(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) <= eps

    @Test
    fun bayes_updatesOddsExactly() {
        assertEquals(0.5, BayesMath.posterior(0.2, 4.0)!!, 1e-9)
        assertNull(BayesMath.posterior(0.0, 4.0))
        assertNull(BayesMath.posterior(1.0, 4.0))
        assertNull(BayesMath.posterior(0.5, 0.0))
        assertEquals(0.25, BayesMath.posteriorChain(0.2, listOf(4.0, 1.0 / 3.0))!!, 1e-9)
    }

    @Test
    fun benford_countsFirstDigits() {
        assertNull(BenfordMath.firstDigitShares(listOf(10.0, 20.0)))
        val b = BenfordMath.firstDigitShares(listOf(199.0, 299.0, 399.0, 499.0, 599.0, 99.0))!!
        assertTrue(b.counts.toList() == listOf(1, 1, 1, 1, 1, 0, 0, 0, 1))
        assertEquals(1.0, b.observed.sum(), 1e-12)
        assertTrue(b.deviation in 0.0..1.0)
    }

    @Test
    fun cusum_alarmsInTailOnly() {
        assertNull(CusumMath.detect(List(7) { 5.0 }, 5.0))
        assertNull(CusumMath.detect(List(10) { 5.0 }, 5.0))   // σ=0
        val c = CusumMath.detect(List(20) { 10.0 } + List(5) { 20.0 }, 4.0)!!
        assertTrue(c.firstAlarmIndex!! >= 20)   // يدوياً: الإنذار عند الفهرس 22
        assertNull(CusumMath.detect(List(25) { 10.0 }, 4.0))  // مستوية ⇒ σ=0 ⇒ null
    }

    @Test
    fun elasticity_arcMidpointFormula() {
        val e = ElasticityMath.arc(10.0, 100.0, 8.0, 130.0)!!
        assertEquals((30.0 / 115.0) / (-2.0 / 9.0), e.value, 1e-9)
        assertEquals("ELASTIC", e.verdict)
        assertNull(ElasticityMath.arc(10.0, 100.0, 10.0, 120.0))
        assertEquals("INELASTIC", ElasticityMath.arc(10.0, 100.0, 9.0, 104.0)!!.verdict)
    }

    @Test
    fun fourier_findsPeriod7Sine() {
        assertNull(FourierMath.dominantCycle(List(15) { 1.0 }))
        assertNull(FourierMath.dominantCycle(List(32) { 7.0 }))
        val sine = (0 until 42).map { sin(2.0 * Math.PI * it / 7.0) }
        val c = FourierMath.dominantCycle(sine)!!
        assertEquals(7, c.period)
        assertTrue(c.strength > 0.9)
    }

    @Test
    fun gambler_burnDaysAndSigmaBuffer() {
        assertNull(GamblerMath.ruinHorizon(1000.0, 50.0, 30.0))   // لا احتراق
        assertNull(GamblerMath.ruinHorizon(0.0, -50.0, 30.0))
        val r = GamblerMath.ruinHorizon(1000.0, -100.0, 50.0)!!
        assertEquals(10, r.burnDays)
        assertEquals(20.0, r.bufferSigma, 1e-9)
    }

    @Test
    fun grubbs_flagsSingleExtreme() {
        assertNull(GrubbsMath.extreme(List(2) { 1.0 }))
        assertNull(GrubbsMath.extreme(List(6) { 5.0 }))
        val g = GrubbsMath.extreme(listOf(10.0, 10.0, 10.0, 10.0, 10.0, 50.0))!!
        assertEquals(5, g.index)
        // [P6-M20 إصلاح]: بσ العينة g = 33.33/16.33 ≈ 2.041 > 1.822 (n=6) — لا يزال شاذاً
        assertTrue(g.isOutlier)
        assertTrue(!GrubbsMath.extreme(listOf(10.0, 11.0, 12.0, 11.0, 10.0))!!.isOutlier)
        // حالة كان يعلمها الخطأ القديم شاذةً بσ المجتمعية (g≈1.386 > 1.153) وهي ليست كذلك
        // بσ العينة (g = 3/2.646 ≈ 1.134 < 1.153) — شاذ كاذب مُصحح
        assertTrue(!GrubbsMath.extreme(listOf(1.0, 2.0, 6.0))!!.isOutlier)
    }

    @Test
    fun iqr_tukeyFences() {
        assertNull(IqrMath.fences(emptyList()))
        val f = IqrMath.fences(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0))!!
        assertEquals(3.25, f.q1, 1e-9)
        assertEquals(7.75, f.q3, 1e-9)
        assertEquals(listOf(100.0), f.outliers.map { it.second })
    }

    @Test
    fun jaccard_similarityAndCoPurchase() {
        assertEquals(0.5, JaccardMath.similarity(setOf("a", "b", "c"), setOf("b", "c", "d")), 1e-9)
        assertEquals(1.0, JaccardMath.similarity(emptySet(), emptySet()), 1e-12)
        val pairs = JaccardMath.coPurchase(
            listOf(setOf("شاي", "سكر"), setOf("شاي", "سكر"), setOf("شاي", "حليب")), 2)
        assertEquals(1, pairs.size)
        assertEquals(2, pairs[0].third)
    }

    @Test
    fun kappa_perfectAndInverted() {
        assertNull(KappaMath.agreement(emptyList(), emptyList()))
        assertNull(KappaMath.agreement(listOf("a"), listOf("a", "b")))
        val k1 = KappaMath.agreement(listOf("1", "1", "2", "2"), listOf("1", "1", "2", "2"))!!
        assertEquals(1.0, k1.kappa, 1e-9)
        val k0 = KappaMath.agreement(listOf("1", "1", "2", "2"), listOf("2", "2", "1", "1"))!!
        assertTrue(k0.kappa < 0.0)
    }

    @Test
    fun logit_sigmoidAndClamp() {
        assertEquals(0.5, LogitMath.probability(0.0, 0.0, 5.0, 0.0, 5.0)!!, 1e-12)
        assertEquals(1.0, LogitMath.probability(0.0, 1.0, 100.0, 0.0, 0.0)!!, 1e-9)
        assertNull(LogitMath.probability(0.0, Double.NaN, 1.0, 0.0, 0.0))
        assertEquals("LOW", LogitMath.verdict(0.29))
        assertEquals("MEDIUM", LogitMath.verdict(0.30))
        assertEquals("HIGH", LogitMath.verdict(0.60))
    }

    @Test
    fun markov_transitionMatrix() {
        assertNull(MarkovMath.transitions(listOf(true)))
        val c = MarkovMath.transitions(listOf(true, true, false, true, false))!!
        assertEquals(1.0 / 3.0, c.upToUp!!, 1e-9)
        assertEquals(2.0 / 3.0, c.upToDown!!, 1e-9)
        assertEquals(1.0, c.downToUp!!, 1e-9)
        // [P20-FIX agent12]: πU = p(D→U)/(p(D→U)+p(U→D)) = 1/(1+2/3) = 0.6 — كانت تُحسب من العدّادات 1/3
        assertEquals(0.6, c.stationaryUp, 1e-9)
        assertNull(MarkovMath.transitions(listOf(true, true, true)))
    }

    @Test
    fun nelson_ruleViolations() {
        assertNull(NelsonMath.violations(List(8) { 5.0 }))
        assertNull(NelsonMath.violations(List(10) { 5.0 }))   // σ=0
        // 9 متساوية + شاذة = z بالضبط 3 (هويّة رياضية) — 19+1 يعطي z≈4.36
        val n1 = NelsonMath.violations(List(19) { 50.0 } + listOf(500.0))!!
        assertEquals(listOf(19), n1.beyond3Sigma)
        val n2 = NelsonMath.violations(List(10) { 90.0 } + List(10) { 110.0 })!!
        assertTrue(n2.run9SameSide.isNotEmpty())
    }

    @Test
    fun oee_productOfComponents() {
        assertEquals(0.36, OeeMath.composite(0.9, 0.8, 0.5)!!.oee, 1e-12)
        assertNull(OeeMath.composite(1.2, 0.5, 0.5))
        assertNull(OeeMath.composite(-0.1, 0.5, 0.5))
    }

    @Test
    fun pareto_headCountTo80() {
        assertNull(ParetoMath.cut(emptyList()))
        assertNull(ParetoMath.cut(listOf(0.0, 0.0)))
        val p = ParetoMath.cut(listOf(100.0, 50.0, 25.0, 25.0))!!
        assertEquals(3, p.headCount)
        assertEquals(0.875, p.headShare, 1e-9)
    }

    @Test
    fun recency_scoresWithExplicitCuts() {
        assertNull(RecencyMath.rfm(-1.0, 3.0, 100.0, listOf(1.0), listOf(1.0), listOf(1.0)))
        val r = RecencyMath.rfm(5.0, 10.0, 2000.0,
            fCuts = listOf(2.0, 4.0, 6.0, 8.0), mCuts = listOf(500.0, 1000.0, 1500.0),
            rCuts = listOf(10.0, 20.0, 30.0, 40.0))!!
        assertEquals(5, r.f)
        assertEquals(4, r.m)
        assertEquals(5, r.r)   // الأحدث ⇒ أعلى نقاط
        val old = RecencyMath.rfm(60.0, 1.0, 100.0,
            fCuts = listOf(2.0, 4.0, 6.0, 8.0), mCuts = listOf(500.0, 1000.0, 1500.0),
            rCuts = listOf(10.0, 20.0, 30.0, 40.0))!!
        assertEquals(1, old.r)
    }

    @Test
    fun theil_medianSlope() {
        assertNull(TheilMath.fit(listOf(1.0)))
        val t = TheilMath.fit(listOf(1.0, 3.0, 5.0, 7.0))!!
        assertEquals(2.0, t.slope, 1e-9)
        assertEquals(1.0, t.intercept, 1e-9)
        val robust = TheilMath.fit(listOf(1.0, 3.0, 5.0, 7.0, 50.0))!!
        assertTrue(robust.slope <= 4.5)   // متانة ضد الشاذة
    }

    @Test
    fun utest_separatesAndMatches() {
        assertNull(UtestMath.mannWhitney(emptyList(), listOf(1.0)))
        val sep = UtestMath.mannWhitney(listOf(1.0, 2.0, 3.0), listOf(10.0, 11.0, 12.0))!!
        assertEquals(0.0, sep.u, 1e-9)
        assertEquals("DIFFERENT", sep.verdict)
        assertNull(UtestMath.mannWhitney(List(6) { 5.0 }, List(6) { 5.0 }))   // σ=0
        assertEquals("SAME", UtestMath.mannWhitney(listOf(1.0, 2.0, 3.0, 4.0), listOf(1.0, 2.0, 3.0, 4.0))!!.verdict)
    }

    @Test
    fun var_empiricalNearestRank() {
        assertNull(VaRMath.historical(List(9) { 1.0 }))
        assertNull(VaRMath.historical(List(20) { 1.0 }, 0.4))
        val v = VaRMath.historical(List(95) { 100.0 } + List(5) { -1000.0 }, 0.95)!!
        assertEquals(-1000.0, v.varValue, 1e-9)   // لا استقراء خطي عند الحدود المتقطعة
        assertEquals(-1000.0, v.cvar, 1e-9)
        val v90 = VaRMath.historical(List(90) { 100.0 } + List(10) { -100.0 }, 0.90)!!
        assertEquals(-100.0, v90.varValue, 1e-9)
    }

    @Test
    fun zipf_headShareVsExpected() {
        assertNull(ZipfMath.headShare(emptyList()))
        assertNull(ZipfMath.headShare(listOf(0.0, 0.0, 0.0, 0.0, 0.0)))
        val z = ZipfMath.headShare(listOf(50.0, 20.0, 10.0, 10.0, 5.0, 3.0, 1.0, 1.0))!!
        assertEquals(2, z.headCount)
        assertEquals(0.7, z.headShare, 1e-9)
        assertTrue(z.expectedShare > 0.5 && z.expectedShare < 0.6)
        assertTrue(z.headShare > z.expectedShare)
    }
}
