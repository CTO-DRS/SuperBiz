package com.superbiz.app

import com.superbiz.app.domain.algo.Bill
import com.superbiz.app.domain.algo.StockMath.Batch
import com.superbiz.app.domain.algo.CashMath
import com.superbiz.app.domain.algo.CreditMath
import com.superbiz.app.domain.algo.RetentionMath
import com.superbiz.app.domain.algo.GrowthMath
import com.superbiz.app.domain.algo.OpsMath
import com.superbiz.app.domain.algo.RevenueMath
import com.superbiz.app.domain.algo.StockMath
import com.superbiz.app.domain.algo.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات الخوارزميات الإحدى والعشرين على أمثلة
 * محسوبة يدوياً + الحالات الحدية، بعقد الصدق الموثقة في R9Smart.kt.
*/
class R9SmartTest {

    // ————— B1 Holt —————
    @Test
    fun `holtForecast empty single and linear`() {
        assertTrue(GrowthMath.holtForecast(emptyList()).isEmpty())
        val flat = GrowthMath.holtForecast(listOf(10.0), horizon = 3)
        assertEquals(listOf(10.0, 10.0, 10.0), flat)
        // سلسلة خطية مثالية: Holt يستقر فوراً على ميل 10 → 50/60/70
        val lin = GrowthMath.holtForecast(listOf(0.0, 10.0, 20.0, 30.0, 40.0), horizon = 3)
        assertEquals(50.0, lin[0], 1e-6)
        assertEquals(60.0, lin[1], 1e-6)
        assertEquals(70.0, lin[2], 1e-6)
    }

    // ————— B2 تقاطع المتوسطات —————
    @Test
    fun `smaCross golden death and none`() {
        assertEquals(0, GrowthMath.smaCross(List(23) { 5.0 }))
        assertEquals(0, GrowthMath.smaCross(List(10) { 5.0 }))                 // بيانات ناقصة
        val golden = GrowthMath.smaCross(List(21) { 5.0 } + listOf(10.0, 30.0))
        assertEquals(1, golden)
        val death = GrowthMath.smaCross(List(21) { 5.0 } + listOf(1.0, 0.0))
        assertEquals(-1, death)
    }

    // ————— B3 دقة التنبؤ —————
    @Test
    fun `forecastAccuracy grades and exclusions`() {
        assertNull(GrowthMath.forecastAccuracy(listOf(1.0, 2.0), listOf(1.0)))
        assertNull(GrowthMath.forecastAccuracy(listOf(0.0, 0.0), listOf(1.0, 1.0)))
        val a = GrowthMath.forecastAccuracy(listOf(100.0), listOf(105.0))!!
        assertEquals(4, a.grade); assertEquals(5.0, a.mapePct, 1e-9); assertEquals(5.0, a.biasPct, 1e-9)
        val b = GrowthMath.forecastAccuracy(listOf(100.0, 200.0), listOf(90.0, 180.0))!!
        assertEquals(10.0, b.mapePct, 1e-9); assertEquals(-10.0, b.biasPct, 1e-9); assertEquals(3, b.grade)
        // الفعلي صفر يُستبعد
        val c = GrowthMath.forecastAccuracy(listOf(0.0, 100.0), listOf(999.0, 110.0))!!
        assertEquals(10.0, c.mapePct, 1e-9)
    }

    // ————— B4 قوة الموسمية —————
    @Test
    fun `seasonalityStrength bounds`() {
        assertEquals(0.0, GrowthMath.seasonalityStrength(List(5) { 5.0 }), 1e-9)       // قصيرة
        assertEquals(0.0, GrowthMath.seasonalityStrength(List(14) { 7.0 }), 1e-9)      // بلا تباين
        val weekly = List(3) { listOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 20.0) }.flatten()
        assertEquals(1.0, GrowthMath.seasonalityStrength(weekly), 1e-9)               // موسمية مثالية → سقف 1
    }

    // ————— B5 مسح الفواتير —————
    @Test
    fun `billSweep exact dp greedy and guards`() {
        val bills = listOf(Bill(1, 40.0), Bill(2, 60.0), Bill(3, 30.0))
        val s = CashMath.billSweep(70.0, bills)
        assertEquals(listOf(1L, 3L), s.settledIds.sorted())
        assertEquals(70.0, s.usedCash, 1e-9)
        assertEquals(0.0, s.remainingCash, 1e-9)
        assertEquals(listOf(2L), s.deferredIds)
        // لا فاتورة ممكنة
        val none = CashMath.billSweep(25.0, bills)
        assertTrue(none.settledIds.isEmpty()); assertEquals(25.0, none.remainingCash, 1e-9)
        assertEquals(3, none.deferredIds.size)
        // الكل ممكن
        val all = CashMath.billSweep(1000.0, bills)
        assertEquals(3, all.settledIds.size); assertEquals(870.0, all.remainingCash, 1e-9)
        // مسار الجشع (أكثر من 12): 13 فاتورة × 10 ومال 45 → 4 فواتير
        val many = (1L..13L).map { Bill(it, 10.0) }
        val g = CashMath.billSweep(45.0, many)
        assertEquals(4, g.settledIds.size); assertEquals(40.0, g.usedCash, 1e-9)
        // حراس
        assertTrue(CashMath.billSweep(0.0, bills).settledIds.isEmpty())
        assertTrue(CashMath.billSweep(100.0, listOf(Bill(9, -5.0), Bill(8, 0.0))).settledIds.isEmpty())
    }

    // ————— B6 السيولة تحت الضغط —————
    @Test
    fun `runwayUnderStress scenarios`() {
        val rs = CashMath.runwayUnderStress(30000.0, 10000.0, 8000.0)
        assertEquals(3, rs.size)
        assertEquals(9999, rs[0].runwayDays)              // صافٍ موجب → آمن
        assertEquals(900, rs[1].runwayDays)               // 30000 ÷ 1000 × 30
        assertEquals(225, rs[2].runwayDays)               // 30000 ÷ 4000 × 30
        val safe = CashMath.runwayUnderStress(0.0, 10000.0, 0.0)
        assertTrue(safe.all { it.runwayDays == 9999 })
    }

    // ————— B7 الاحتياطي الموسمي —————
    @Test
    fun `seasonalReserve peak month drives it`() {
        assertEquals(0.0, CashMath.seasonalReserve(emptyList()), 1e-9)
        // [100,200,300,1000,100]: p90 خطي = 300+(1000-300)×0.6 = 720، best3 = 500 → 720×2
        val r = CashMath.seasonalReserve(listOf(100.0, 200.0, 300.0, 1000.0, 100.0), 2)
        assertEquals(1440.0, r, 1e-9)
    }

    // ————— B8 درجة السلوك —————
    @Test
    fun `behaviorScore deductions and caps`() {
        assertEquals(100, CreditMath.behaviorScore(10, 0, 0, 0.0))
        assertEquals(68, CreditMath.behaviorScore(5, 5, 0, 30.0))     // 100-22.5-10 = 67.5 → 68
        assertEquals(70, CreditMath.behaviorScore(10, 0, 5, 0.0))     // مرتجعات مسقوفة بـ3
        assertEquals(5, CreditMath.behaviorScore(0, 10, 3, 60.0))     // 100-45-20-30
    }

    // ————— B9 حد الائتمان —————
    @Test
    fun `creditLimit tiers and guards`() {
        assertEquals("MEDIUM", CreditMath.creditLimit(100.0, 4, 80).tier)      // 360
        assertEquals("HIGH", CreditMath.creditLimit(100.0, 10, 100).tier)      // 800
        val low = CreditMath.creditLimit(100.0, 1, 0)
        assertEquals("LOW", low.tier); assertEquals(50.0, low.amount, 1e-9)
        val bad = CreditMath.creditLimit(0.0, 4, 90)
        assertEquals(0.0, bad.amount, 1e-9); assertEquals("LOW", bad.tier)
    }

    // ————— B10 مخزون الأمان —————
    @Test
    fun `safetyStock classic formula`() {
        assertEquals(0.0, StockMath.safetyStock(10.0, 0.0, 7.0), 1e-9)
        assertEquals(8.73, StockMath.safetyStock(10.0, 2.0, 7.0), 0.01)        // 1.65×√28
        assertEquals(18.67, StockMath.safetyStock(10.0, 2.0, 7.0, 1.0), 0.01)  // 1.65×√128
        assertEquals(0.0, StockMath.safetyStock(-1.0, 2.0, 7.0), 1e-9)
        assertEquals(0.0, StockMath.safetyStock(10.0, 2.0, 0.0), 1e-9)
    }

    // ————— B11 كروستون —————
    @Test
    fun `crostonIntermittent sparse demand`() {
        val zero = StockMath.crostonIntermittent(List(10) { 0.0 })
        assertEquals(0.0, zero.forecastPerPeriod, 1e-9)
        val c = StockMath.crostonIntermittent(listOf(0.0, 0.0, 20.0, 0.0, 0.0, 0.0, 10.0, 0.0))
        assertEquals(4.0, c.avgInterval, 1e-9)
        assertEquals(15.0, c.avgSize, 1e-9)
        assertEquals(3.75, c.forecastPerPeriod, 1e-9)
        val single = StockMath.crostonIntermittent(listOf(0.0, 5.0, 0.0))
        // [P20-FIX agent11]: حدث واحد لا يُقدّر إيقاعاً — تنبؤ صادق 0.0 (كان 5.0/يوم تضخيم ×30)
        assertEquals(0.0, single.forecastPerPeriod, 1e-9)
        assertEquals(5.0, single.avgSize, 1e-9)
    }

    // ————— B12 تقادم الدفعات —————
    @Test
    fun `batchAging bands and capital`() {
        val today = 1000
        val r = StockMath.batchAging(
            listOf(Batch(1, 10.0, 5.0, today - 100), Batch(2, 20.0, 3.0, today - 20), Batch(3, 5.0, 4.0, today - 50), Batch(4, 0.0, 99.0, today - 200)),
            today, warnDays = 30, riskDays = 90
        )
        assertEquals(3, r.rows.size)
        assertEquals(1L, r.rows.first().batchId)                       // الأقدم أولاً
        assertEquals("CRITICAL", r.rows.first().band)
        assertEquals("RISK", r.rows[1].band)
        assertEquals("OK", r.rows[2].band)
        assertEquals(70.0, r.capitalAtRisk, 1e-9)                      // 50 + 20
    }

    // ————— B13 توازن الفئات —————
    @Test
    fun `categoryBalance over under and ok`() {
        val rows = StockMath.categoryBalance(mapOf("أ" to 600.0, "ب" to 400.0), mapOf("أ" to 200.0, "ب" to 800.0))
        assertEquals(2, rows.size)
        assertEquals("OVER", rows.first { it.category == "أ" }.verdict)
        assertEquals("UNDER", rows.first { it.category == "ب" }.verdict)
        val ok = StockMath.categoryBalance(mapOf("وحيد" to 100.0), mapOf("وحيد" to 100.0))
        assertEquals("OK", ok.single().verdict)
        assertTrue(StockMath.categoryBalance(mapOf("أ" to 100.0), emptyMap()).isEmpty())
        // فئة بمخزون بلا مبيعات → OVER
        val noSales = StockMath.categoryBalance(mapOf("أ" to 50.0, "ب" to 50.0), mapOf("أ" to 100.0))
        assertEquals("OVER", noSales.first { it.category == "ب" }.verdict)
    }

    // ————— B14 السعر الأمثل —————
    @Test
    fun `optimalPrice linear demand and guards`() {
        val pts = listOf(10.0 to 80.0, 20.0 to 60.0, 30.0 to 40.0, 40.0 to 20.0)   // q=100-2p
        assertEquals(25.0, RevenueMath.optimalPrice(pts)!!, 1e-6)
        assertNull(RevenueMath.optimalPrice(pts.take(3)))                           // أقل من 4
        assertNull(RevenueMath.optimalPrice(listOf(10.0 to 5.0, 20.0 to 6.0, 30.0 to 7.0, 40.0 to 8.0))) // ميل موجب
        assertNull(RevenueMath.optimalPrice(listOf(9.0 to 82.0, 10.0 to 80.0, 11.0 to 78.0, 12.0 to 76.0))) // خارج النطاق
    }

    // ————— B15 سعر الحزمة —————
    @Test
    fun `bundlePrice capped fair deal`() {
        assertEquals(71.43, RevenueMath.bundlePrice(listOf(30.0, 20.0), listOf(50.0, 40.0), 0.3)!!, 0.01)
        assertEquals(85.5, RevenueMath.bundlePrice(listOf(30.0, 20.0), listOf(50.0, 40.0), 0.6)!!, 0.01) // سقف 95%
        assertNull(RevenueMath.bundlePrice(listOf(80.0, 20.0), listOf(50.0, 40.0), 0.5))                 // تكلفة أعلى من السقف
        assertNull(RevenueMath.bundlePrice(listOf(10.0), listOf(10.0, 20.0), 0.3))
        assertNull(RevenueMath.bundlePrice(emptyList(), emptyList(), 0.3))
    }

    // ————— B16 الربح المعرض للخطر —————
    @Test
    fun `profitAtRisk nearest rank`() {
        val daily = listOf(-50.0, -20.0, -10.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 40.0)
        assertEquals(20.0, RevenueMath.profitAtRisk(daily, 0.95)!!, 1e-9)
        assertNull(RevenueMath.profitAtRisk(daily.take(5)))
        assertNull(RevenueMath.profitAtRisk(daily, 0.2))
        val allGood = List(20) { 100.0 + it }
        assertEquals(0.0, RevenueMath.profitAtRisk(allGood, 0.95)!!, 1e-9)      // لا خسائر
    }

    // ————— B17 احتفاظ الأفواج —————
    @Test
    fun `cohortRetention month offsets`() {
        val purchases = listOf(
            1L to 7, 1L to 8, 1L to 9,
            2L to 7, 2L to 8,
            3L to 7, 3L to 9,
            4L to 9,
            5L to 8,
        )
        val r = RetentionMath.cohortRetention(purchases, currentMonth = 10)!!
        assertEquals(5, r.cohortSize)
        assertEquals(0.4, r.m1, 1e-9)     // 2/5
        assertEquals(0.5, r.m2, 1e-9)     // 2/4
        assertEquals(0.0, r.m3, 1e-9)     // 0/3
        assertNull(RetentionMath.cohortRetention(emptyList(), 10))
        // لا فوج مؤهل: كل الشراءات في الشهر الحالي
        assertNull(RetentionMath.cohortRetention(listOf(9L to 10), 10))
    }

    // ————— B18 مؤشر الولاء —————
    @Test
    fun `npsProxy mapping`() {
        assertEquals(31, RetentionMath.npsProxy(30, 50, 5))
        assertEquals(0, RetentionMath.npsProxy(0, 0, 0))
        assertEquals(-50, RetentionMath.npsProxy(0, 100, 50))
        assertEquals(100, RetentionMath.npsProxy(10, 0, 0))
    }

    // ————— B19 الإجراء التالي —————
    @Test
    fun `nextBestAction decision table`() {
        assertEquals("COLLECT", RetentionMath.nextBestAction("Champions", 0.2, 10))
        assertEquals("WIN_BACK", RetentionMath.nextBestAction("New", 0.7, 0))
        assertEquals("NURTURE", RetentionMath.nextBestAction("New", 0.3, 0))
        assertEquals("UPSELL", RetentionMath.nextBestAction("Champions", 0.3, 0))
        assertEquals("UPSELL", RetentionMath.nextBestAction("Loyal", 0.1, 0))
        assertEquals("OK", RetentionMath.nextBestAction("Sleeping", 0.2, 0))
    }

    // ————— B20 خطة ساعات العمل —————
    @Test
    fun `staffingPlan proportional and filtered`() {
        val hours = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 4.0, 6.0, 8.0, 6.0, 4.0, 2.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val plan = OpsMath.staffingPlan(hours, 10)
        assertEquals(9, plan.size)
        assertEquals(12, plan.first().first)          // ساعة الذروة أولاً
        assertEquals(2.35, plan.first().second, 1e-9) // 8/34×10
        assertTrue(OpsMath.staffingPlan(hours, 0).isEmpty())
        assertTrue(OpsMath.staffingPlan(DoubleArray(24) { 0.0 }, 10).isEmpty())
    }

    // ————— B21 مصنف المهام —————
    @Test
    fun `eisenhower quadrants and ranking`() {
        val tasks = listOf(
            Task(1, 1, true), Task(2, 10, true), Task(3, 2, false), Task(4, 10, false), Task(5, 0, true),
        )
        val ranked = OpsMath.eisenhower(tasks)
        assertEquals(5, ranked.size)
        assertEquals("DO_FIRST", ranked[0].quadrant); assertEquals(5L, ranked[0].id)   // الأعسر أولاً
        assertEquals("DO_FIRST", ranked[1].quadrant); assertEquals(1L, ranked[1].id)
        assertEquals("SCHEDULE", ranked[2].quadrant)
        assertEquals("DELEGATE", ranked[3].quadrant)
        assertEquals("ELIMINATE", ranked[4].quadrant)
        assertTrue(OpsMath.eisenhower(emptyList()).isEmpty())
    }

    // ————— حارس عام: لا قيم NaN/مفاجئة —————
    @Test
    fun `no NaN leaks in outputs`() {
        val flat = GrowthMath.holtForecast(listOf(0.0, 0.0, 0.0))
        assertFalse(flat.any { it.isNaN() })
        val acc = GrowthMath.forecastAccuracy(listOf(100.0), listOf(0.0))!!
        assertFalse(acc.mapePct.isNaN() || acc.biasPct.isNaN())
        assertNotNull(StockMath.safetyStock(0.0, 0.0, 5.0))
    }
}
