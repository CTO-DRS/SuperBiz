package com.superbiz.app

import com.superbiz.app.domain.algo.AnomalyMath
import com.superbiz.app.domain.algo.AuditMath
import com.superbiz.app.domain.algo.CartMath
import com.superbiz.app.domain.algo.CatalogMath
import com.superbiz.app.domain.algo.CompareMath
import com.superbiz.app.domain.algo.CostMath
import com.superbiz.app.domain.algo.DunningMath
import com.superbiz.app.domain.algo.HourMath
import com.superbiz.app.domain.algo.PayoffMath
import com.superbiz.app.domain.algo.PaymentMath
import com.superbiz.app.domain.algo.QuartileMath
import com.superbiz.app.domain.algo.ShareMath
import com.superbiz.app.domain.algo.TurnMath
import com.superbiz.app.domain.algo.UpliftMath
import com.superbiz.app.domain.algo.VoidMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * — 20 اختباراً للخوارزميات الجديدة، كلها مرآة لعدّاء JVM
 * scripts/r12-harness/R12Harness.kt الذي اجتاز 91/91 فحصاً بكل القيم المحسوبة يدوياً.
 * العقد نفسه: بلا بيانات كافية أو بمدخلات غير منتهية ⇒ null/قائمة فارغة.
*/
class R12SmartTest {

    private fun approx(a: Double, b: Double, tol: Double = 1e-6) = abs(a - b) <= tol

    @Test fun ewma_detectsShiftAfterFlatBaseline() {
        val r = AnomalyMath.ewmaBands(List(9) { 100.0 } + List(3) { 130.0 })!!
        assertEquals("SHIFT", r.verdict)
        assertEquals(9, r.breachIndex)
        assertEquals(100.0, r.baseline, 1e-9)
    }

    @Test fun ewma_stableAndGuards() {
        assertTrue(AnomalyMath.ewmaBands(List(12) { 100.0 })!!.verdict == "STABLE")
        val noisy = AnomalyMath.ewmaBands(listOf(100.0, 102.0, 98.0, 101.0, 99.0, 100.0, 150.0, 152.0, 151.0, 149.0, 150.0, 153.0))!!
        assertEquals("SHIFT", noisy.verdict)
        assertEquals(6, noisy.breachIndex)
        assertTrue(approx(noisy.upper - noisy.baseline, noisy.baseline - noisy.lower))
        assertNull(AnomalyMath.ewmaBands(List(7) { 1.0 }))
        assertNull(AnomalyMath.ewmaBands(List(8) { if (it == 3) Double.NaN else 1.0 }))
    }

    @Test fun cusum_risingFallingStable() {
        val up = AnomalyMath.cusumShift(List(12) { 10.0 } + List(6) { 40.0 })!!
        assertEquals("RISING", up.verdict)
        assertTrue(approx(up.hi, 5.4853, 1e-3))
        val down = AnomalyMath.cusumShift(List(6) { 10.0 } + List(12) { 40.0 })!!
        assertEquals("FALLING", down.verdict)
        assertTrue(approx(down.lo, 5.4853, 1e-3))
        assertEquals("STABLE", AnomalyMath.cusumShift(List(8) { 5.0 })!!.verdict)
        assertNull(AnomalyMath.cusumShift(List(3) { 5.0 }))
    }

    @Test fun periodCompare_allVerdicts() {
        val r = CompareMath.periodCompare(120.0, 100.0, 90.0)!!
        assertEquals("UP", r.verdict)
        assertTrue(approx(r.momPct!!, 20.0))
        assertTrue(approx(r.yoyPct!!, 33.3333, 1e-3))
        assertEquals("NEW", CompareMath.periodCompare(100.0, null, null)!!.verdict)
        assertEquals("DOWN", CompareMath.periodCompare(50.0, 100.0, 80.0)!!.verdict)
        assertEquals("FLAT", CompareMath.periodCompare(100.0, 100.0, 100.0)!!.verdict)
        assertNull(CompareMath.periodCompare(100.0, 0.0, null)!!.momPct)
        assertNull(CompareMath.periodCompare(Double.NaN, 1.0, null))
    }

    @Test fun mix_methods() {
        val r = PaymentMath.mix(60.0, 30.0, 10.0)!!
        assertTrue(approx(r.cashPct, 60.0))
        assertEquals("BALANCED", r.verdict)
        assertEquals("CHECK_HEAVY", PaymentMath.mix(0.0, 45.0, 5.0)!!.verdict)
        assertEquals("CASH_ONLY", PaymentMath.mix(100.0, 0.0, 0.0)!!.verdict)
        assertNull(PaymentMath.mix(0.0, 0.0, 0.0))
        assertNull(PaymentMath.mix(-5.0, 10.0, 10.0))
    }

    @Test fun timing_verdicts() {
        assertEquals("SLIPPING", PaymentMath.timing(listOf(-1, 0, 0, 5, 2))!!.verdict)
        assertEquals("PROMPT", PaymentMath.timing(listOf(-2, -3, -1, 0))!!.verdict)
        assertEquals("NORMAL", PaymentMath.timing(listOf(0, 0, -1))!!.verdict)
        assertNull(PaymentMath.timing(emptyList()))
    }

    @Test fun turnover_rates() {
        val fast = TurnMath.turnover(90000.0, 10000.0, 90)!!
        assertTrue(approx(fast.turns, 36.5))
        assertEquals("FAST", fast.verdict)
        assertEquals("OK", TurnMath.turnover(9000.0, 10000.0, 90)!!.verdict)
        assertEquals("SLOW", TurnMath.turnover(3000.0, 20000.0, 90)!!.verdict)
        assertNull(TurnMath.turnover(0.0, 10000.0, 90))
    }

    @Test fun sellThrough_bands() {
        assertEquals("HOT", TurnMath.sellThrough(100.0, 80.0)!!.verdict)
        assertEquals("NORMAL", TurnMath.sellThrough(100.0, 40.0)!!.verdict)
        assertEquals("STALE", TurnMath.sellThrough(100.0, 10.0)!!.verdict)
        assertEquals(100.0, TurnMath.sellThrough(100.0, 150.0)!!.soldPct, 1e-9)
        assertNull(TurnMath.sellThrough(0.0, 5.0))
    }

    @Test fun quartile_bands() {
        val r = QuartileMath.classify(listOf("a" to 10.0, "b" to 20.0, "c" to 30.0, "d" to 40.0))!!
        assertEquals(listOf("d", "c", "b", "a"), r.map { it.name })
        assertEquals(listOf(1, 2, 3, 4), r.map { it.quartile })
        assertNull(QuartileMath.classify(listOf("a" to 1.0, "b" to 2.0, "c" to 3.0)))
        assertNull(QuartileMath.classify(listOf("a" to 1.0, "b" to 2.0, "c" to 3.0, "d" to Double.NaN)))
    }

    @Test fun activeSpan_windows() {
        val hours = DoubleArray(24)
        for (h in 10..17) hours[h] = listOf(30, 40, 50, 80, 100, 90, 60, 25)[h - 10].toDouble()
        val r = HourMath.activeSpan(hours)!!
        assertEquals(10, r.startHour)
        assertEquals(17, r.endHour)
        assertEquals(8, r.spanHours)
        assertEquals("NORMAL", r.verdict)
        val focused = DoubleArray(24)
        focused[12] = 50.0; focused[13] = 100.0; focused[14] = 90.0; focused[15] = 60.0
        assertEquals("FOCUSED", HourMath.activeSpan(focused)!!.verdict)
        assertNull(HourMath.activeSpan(DoubleArray(24)))
        assertNull(HourMath.activeSpan(DoubleArray(23)))
    }

    @Test fun voidTrend_alarmAndGuards() {
        val r = VoidMath.voidTrend(listOf(1 to 50, 2 to 50, 1 to 50, 6 to 50))!!
        assertEquals("ALARM", r.verdict)
        assertTrue(r.rising)
        assertTrue(approx(r.recent.pct, 12.0))
        val calm = VoidMath.voidTrend(listOf(0 to 50, 1 to 50))!!
        assertEquals("OK", calm.verdict)
        assertTrue(!calm.rising)
        assertNull(VoidMath.voidTrend(listOf(1 to 50)))
        assertNull(VoidMath.voidTrend(listOf(1 to 0, 2 to 0)))
    }

    @Test fun uplift_decomposition() {
        val win = UpliftMath.priceUplift(100.0, 10.0, 110.0, 11.0)!!
        assertEquals("WIN", win.verdict)
        assertTrue(approx(win.revenueDelta, 210.0))
        assertTrue(approx(win.priceEffect, 110.0))
        assertTrue(approx(win.volumeEffect, 100.0))
        assertTrue(approx(win.crossEffect, 10.0))
        assertEquals("LOSS", UpliftMath.priceUplift(100.0, 10.0, 90.0, 9.0)!!.verdict)
        assertEquals("NEUTRAL", UpliftMath.priceUplift(100.0, 10.0, 100.0, 10.0)!!.verdict)
        assertNull(UpliftMath.priceUplift(0.0, 10.0, 110.0, 11.0))
        assertNull(UpliftMath.priceUplift(100.0, -1.0, 110.0, 11.0))
    }

    @Test fun costCreep_verdicts() {
        val c = CostMath.costCreep(listOf(10.0, 10.5, 11.0, 11.5, 12.0))!!
        assertEquals("CREEPING", c.verdict)
        assertTrue(approx(c.slopePctPerMonth, 4.545, 1e-2))
        assertTrue(approx(c.risePct, 20.0))
        assertEquals("SPIKE", CostMath.costCreep(listOf(10.0, 10.0, 11.0, 13.0, 16.0))!!.verdict)
        assertEquals("FALLING", CostMath.costCreep(listOf(12.0, 11.8, 11.6, 11.4, 11.2))!!.verdict)
        assertEquals("STABLE", CostMath.costCreep(listOf(10.0, 10.0, 10.0, 10.0))!!.verdict)
        assertNull(CostMath.costCreep(listOf(10.0, 11.0, 12.0)))
    }

    @Test fun payoff_months() {
        assertEquals("SHORT", PayoffMath.monthsToClear(300.0, 100.0)!!.verdict)
        assertEquals(3, PayoffMath.monthsToClear(300.0, 100.0)!!.months)
        assertEquals("MEDIUM", PayoffMath.monthsToClear(600.0, 100.0)!!.verdict)
        assertEquals("LONG", PayoffMath.monthsToClear(1200.0, 100.0)!!.verdict)
        assertNull(PayoffMath.monthsToClear(0.0, 100.0))
        assertNull(PayoffMath.monthsToClear(100.0, 0.0))
        assertEquals(3, PayoffMath.monthsToClear(250.0, 100.0)!!.months)
    }

    @Test fun dunning_stages() {
        val d = DunningMath.stages(listOf(500.0 to 0, 200.0 to 10, 100.0 to 30, 50.0 to 60, 150.0 to 120))!!
        assertEquals(listOf("CURRENT", "REMIND", "URGE", "FINAL", "COLLECT"), d.rows.map { it.stage })
        assertTrue(approx(d.totalOpen, 1000.0))
        assertEquals("REMIND", d.worst)
        assertNull(DunningMath.stages(listOf(100.0 to 0))!!.worst)
        assertNull(DunningMath.stages(emptyList()))
        assertEquals("COLLECT", DunningMath.stages(listOf(100.0 to 91))!!.rows.single().stage)
    }

    @Test fun orphanPayments_onlyMissing() {
        val orphans = AuditMath.orphanPayments(
            listOf(Triple(1L, 10L, 50.0), Triple(2L, 99L, 70.0), Triple(3L, null, 30.0)),
            setOf(10L, 20L),
        )
        assertEquals(listOf(AuditMath.OrphanRow(2L, 99L, 70.0)), orphans)
        assertTrue(AuditMath.orphanPayments(emptyList(), setOf(1L)).isEmpty())
    }

    @Test fun overpaidInvoices_sortedAndFiltered() {
        val rows = AuditMath.overpaidInvoices(listOf(Triple(1L, 100.0, 150.0), Triple(2L, 200.0, 200.005), Triple(3L, 50.0, 60.0)))
        assertEquals(listOf(1L, 3L), rows.map { it.invoiceId })
        assertTrue(approx(rows[0].excess, 50.0))
        assertTrue(approx(rows[1].excess, 10.0))
        assertTrue(AuditMath.overpaidInvoices(listOf(Triple(1L, 100.0, Double.NaN))).isEmpty())
    }

    @Test fun catalog_neverSoldAndGaps() {
        val items = listOf(
            CatalogMath.CatItem(1, "a", 5.0, 100.0),
            CatalogMath.CatItem(2, "b", 0.0, 0.0),
            CatalogMath.CatItem(3, "c", 2.0, 50.0),
        )
        assertEquals(listOf(3L, 2L), CatalogMath.neverSold(items, setOf(1L)).map { it.id })
        assertTrue(CatalogMath.neverSold(items, setOf(1L, 2L, 3L)).isEmpty())
        val gaps = CatalogMath.categoryGaps(mapOf("أدوات" to 3, "" to 2, "أغذية" to 1), mapOf("أدوات" to 2))
        assertEquals(listOf(CatalogMath.CatGap("أغذية", 1, 0)), gaps)
        assertTrue(CatalogMath.categoryGaps(emptyMap(), emptyMap()).isEmpty())
    }

    @Test fun cartTrend_directions() {
        val grow = CartMath.cartSizeTrend(listOf(10.0 to 10, 12.0 to 10, 14.0 to 10, 16.0 to 10))!!
        assertEquals("GROWING", grow.direction)
        assertTrue(approx(grow.latest, 1.6))
        assertTrue(approx(grow.momentumPct!!, 60.0))
        assertEquals("SHRINKING", CartMath.cartSizeTrend(listOf(16.0 to 10, 14.0 to 10, 12.0 to 10, 10.0 to 10))!!.direction)
        assertEquals("FLAT", CartMath.cartSizeTrend(listOf(10.0 to 10, 10.0 to 10, 10.2 to 10, 10.0 to 10))!!.direction)
        assertNull(CartMath.cartSizeTrend(listOf(10.0 to 0, 12.0 to 10, 14.0 to 10, 16.0 to 10)))
    }

    @Test fun shareDrift_concentration() {
        val now = mapOf("a" to 800.0, "b" to 100.0, "c" to 100.0)
        val before = mapOf("a" to 400.0, "b" to 300.0, "c" to 300.0)
        val r = ShareMath.concentrationDrift(now, before)!!
        assertTrue(approx(r.hhiNow, 0.66))
        assertTrue(approx(r.hhiBefore, 0.34))
        assertEquals("CONCENTRATING", r.verdict)
        assertTrue(approx(r.topShareNowPct, 80.0))
        assertEquals("DIVERSIFYING", ShareMath.concentrationDrift(before, now)!!.verdict)
        assertEquals("STABLE", ShareMath.concentrationDrift(before, before)!!.verdict)
        assertNull(ShareMath.concentrationDrift(now, emptyMap()))
    }
}
