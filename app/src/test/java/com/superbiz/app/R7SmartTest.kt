package com.superbiz.app

import com.superbiz.app.domain.algo.Bundle
import com.superbiz.app.domain.algo.CollectItem
import com.superbiz.app.domain.algo.CollectionMath
import com.superbiz.app.domain.algo.CustomerMath
import com.superbiz.app.domain.algo.DemandMath
import com.superbiz.app.domain.algo.InsightMath
import com.superbiz.app.domain.algo.PlanItem
import com.superbiz.app.domain.algo.PriceMath
import com.superbiz.app.domain.algo.StockItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات الخوارزميات العشرين على أمثلة محسوبة يدوياً
 * + الحالات الحدية (فراغ/صفر/انحراف) بعقد الصدق الموثقة في R7Smart.kt.
*/
class R7SmartTest {

    // ————— A1 تنبؤ 7 أيام —————
    @Test
    fun `forecast7d empty flat and seasonality`() {
        assertTrue(DemandMath.forecast7d(emptyList()).isEmpty())
        // أقل من 7 → مسطح عند EWMA
        val flat = DemandMath.forecast7d(listOf(10.0, 10.0, 10.0))
        assertEquals(7, flat.size); assertTrue(flat.all { it == 10.0 })
        // سلسلة أسبوعية منتظمة مع ذروة الجمعة (موقع 6) تتكرر في التوقع
        val week = listOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 30.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 30.0)
        val fc = DemandMath.forecast7d(week)
        assertEquals(7, fc.size)
        assertTrue("ذروة الجمعة أعلى", fc.max() > fc.min() * 1.5)
    }

    // ————— A2 أيام النفاد —————
    @Test
    fun `stockoutEta null and monotone`() {
        assertNull(DemandMath.stockoutEta(0.0, listOf(5.0, 5.0)))
        assertNull(DemandMath.stockoutEta(100.0, emptyList()))
        val calm = DemandMath.stockoutEta(100.0, List(14) { 5.0 })!!
        val wild = DemandMath.stockoutEta(100.0, List(14) { if (it % 2 == 0) 1.0 else 9.0 })!!
        assertTrue("التباين يقصر الأيام", wild < calm)
        assertEquals(20.0, calm, 0.01) // 100 ÷ 5
    }

    // ————— A3 احتمال النفاد —————
    @Test
    fun `stockoutRisk monotone in stock and lead`() {
        val d = List(14) { 10.0 }
        val low = DemandMath.stockoutRisk(50.0, 7.0, d)     // 50 < 70 مطلوب المهلة
        val high = DemandMath.stockoutRisk(200.0, 7.0, d)   // 200 >> 70
        assertTrue(low > 0.5); assertTrue(high < 0.1)
        assertEquals(0.0, DemandMath.stockoutRisk(100.0, 0.0, d), 1e-9)
    }

    @Test
    fun `stockoutRisk sigma is sqrt of lead not lead power`() {
        // [P6-M19 إصلاح]: كان σ المهلة = σd·L^1.5 فيكبت الخطر عند المخزون القريب من الطلب.
        // سلسلة متقلبة (σd≈4.15، طلب مهلة 7 أيام ≈ 40.3): عند مخزون 45 يجب أن يكون
        // الخطر دون 0.40 بσd·√L (z≈0.43) — كان يعود ≈0.48 بالصيغة القديمة (z≈0.05).
        val wild = List(14) { if (it % 2 == 0) 1.0 else 9.0 }
        assertTrue(DemandMath.stockoutRisk(45.0, 7.0, wild) < 0.40)
        // والرتيب في المخزون محفوظ بعد الإصلاح
        val lo = DemandMath.stockoutRisk(30.0, 7.0, wild)
        val hi = DemandMath.stockoutRisk(50.0, 7.0, wild)
        assertTrue("زيادة المخزون تقلل الخطر", hi < lo)
    }

    // ————— A4 الراكد —————
    @Test
    fun `deadStock filters sorts and sums`() {
        val today = 1000
        val items = listOf(
            StockItem(1, 10.0, 50.0, today - 40),   // راكد 500
            StockItem(2, 5.0, 40.0, today - 10),    // حديث — يستبعد
            StockItem(3, 20.0, 30.0, today - 60),   // راكد 600
            StockItem(4, 0.0, 99.0, today - 99),    // كمية صفر — يستبعد
        )
        val r = DemandMath.deadStock(items, today, 30)
        assertEquals(2, r.rows.size)
        assertEquals(3L, r.rows.first().productId)          // الأكبر رأس مال أولاً
        assertEquals(1100.0, r.tiedCapital, 1e-9)
    }

    // ————— A5 خطة إعادة الطلب —————
    @Test
    fun `reorderPlan priorities and skips no-demand`() {
        val items = listOf(
            PlanItem(1, onHand = 10.0, avgDaily = 10.0, demandCv = 0.2, unitCost = 5.0),   // تغطية يوم
            PlanItem(2, onHand = 500.0, avgDaily = 1.0, demandCv = 0.0, unitCost = 5.0),   // غني — لا شراء
            PlanItem(3, onHand = 100.0, avgDaily = 0.0, demandCv = 0.0, unitCost = 5.0),   // بلا طلب — يستبعد
            PlanItem(4, onHand = 20.0, avgDaily = 4.0, demandCv = 0.1, unitCost = 5.0),    // تغطية 5 أيام
        )
        val plan = DemandMath.reorderPlan(items, leadTimeDays = 7.0, coverDays = 14.0)
        assertEquals(listOf(1L, 4L), plan.map { it.productId })
        assertTrue("الأقل تغطية أولاً", plan[0].daysCover < plan[1].daysCover)
        assertTrue(plan[0].orderQty >= 14.0 * 10.0 - 10.0)
        assertTrue(DemandMath.reorderPlan(items, leadTimeDays = 0.0).isEmpty())
    }

    // ————— A6 المرونة —————
    @Test
    fun `elasticityProxy negative and degenerate null`() {
        // تقليدي: p: 10→20 و q: 100→25 → مرونة ≈ −2
        val e = PriceMath.elasticityProxy(listOf(10.0, 15.0, 20.0, 25.0), listOf(100.0, 44.0, 25.0, 16.0))!!
        assertTrue("مرونة سالبة متوقعة", e < -0.5)
        assertNull(PriceMath.elasticityProxy(listOf(10.0, 10.0, 10.0, 10.0), listOf(1.0, 2.0, 3.0, 4.0))) // بلا تشتت
        assertNull(PriceMath.elasticityProxy(listOf(10.0, 12.0), listOf(5.0, 6.0)))                       // نقاط قليلة
    }

    // ————— A7 السعر المقترح —————
    @Test
    fun `suggestPrice cuts for volume and guards floor`() {
        val p = PriceMath.suggestPrice(100.0, elasticity = -2.0, wantedVolumeChangePct = 0.10, floorPrice = 90.0)!!
        assertEquals(95.0, p, 0.01) // %Δp = 0.10 ÷ −2 = −5%
        assertNull(PriceMath.suggestPrice(100.0, -2.0, 0.5, 90.0)) // يتجاوز الأرضية → null
        assertNull(PriceMath.suggestPrice(100.0, +2.0, 0.1, 90.0)) // مرونة موجبة → null
    }

    // ————— A8 سقف الخصم —————
    @Test
    fun `maxSafeDiscount respects margin and vat`() {
        // تكلفة 60، سعر 100، هامش مستهدف 25% بعد VAT 15%: الأدنى = 60×1.15÷0.75 = 92
        val d = PriceMath.maxSafeDiscountPct(100.0, 60.0, 0.25, 0.15)
        assertEquals(8.0, d, 0.05)
        assertEquals(0.0, PriceMath.maxSafeDiscountPct(100.0, 95.0, 0.25), 1e-9) // لا مساحة
        assertEquals(0.0, PriceMath.maxSafeDiscountPct(100.0, 10.0, 1.2), 1e-9)  // هامش غير منطقي
    }

    // ————— A9 تدرج الكميات —————
    @Test
    fun `volumeTiers ascending discounts never below floor`() {
        val tiers = PriceMath.volumeTiers(100.0, 50.0, 0.20)
        assertEquals(4, tiers.size)
        assertTrue(tiers.zipWithNext().all { (a, b) -> a.discountPct <= b.discountPct })
        // أعمق درجة تحفظ الهامش
        val deepest = tiers.last().unitPrice
        assertTrue((deepest - 50.0) / deepest >= 0.20 - 0.01)
        assertTrue(PriceMath.volumeTiers(100.0, 98.0, 0.5).isEmpty())
    }

    // ————— A10 نقطة التعادل —————
    @Test
    fun `breakEven units safety margin and no-contribution`() {
        val be = PriceMath.breakEven(3000.0, 100.0, 70.0, expectedMonthlyRevenue = 12500.0)
        assertEquals(100.0, be.units, 1e-9)     // 3000 ÷ 30
        assertEquals(10000.0, be.revenue, 1e-9)
        assertEquals(20.0, be.marginOfSafetyPct!!, 1e-9)
        val bad = PriceMath.breakEven(3000.0, 50.0, 70.0)
        assertTrue(bad.units.isNaN()); assertEquals(-20.0, bad.contributionPerUnit, 1e-9)
    }

    // ————— A11 القيمة العمرية —————
    @Test
    fun `ltv math and zero guards`() {
        assertEquals(4500.0, CustomerMath.ltv(300.0, 5.0, 3.0, 1.0), 1e-9)
        assertEquals(3600.0, CustomerMath.ltv(300.0, 5.0, 3.0, 0.8), 1e-9)
        assertEquals(0.0, CustomerMath.ltv(0.0, 5.0, 3.0), 1e-9)
    }

    // ————— A12 مخاطرة الفقد —————
    @Test
    fun `churnRisk monotone with silence and loyalty damp`() {
        val fresh = CustomerMath.churnRisk(5, 30.0, 2)     // صمت قصير
        val silent = CustomerMath.churnRisk(90, 30.0, 2)   // 3 دورات صمت
        assertTrue(fresh < 0.3); assertTrue(silent > 0.9)
        assertTrue("الولاء يخمد", CustomerMath.churnRisk(60, 30.0, 12) < CustomerMath.churnRisk(60, 30.0, 2))
        assertEquals(0.5, CustomerMath.churnRisk(10, 0.0, 3), 1e-9) // بلا تاريخ → حياد
    }

    // ————— A13 RFM —————
    @Test
    fun `rfm segments map correctly`() {
        val champ = CustomerMath.rfm(5, 9, 5000.0, monetaryAnchor = 1000.0)
        assertEquals("Champions", champ.segment); assertEquals(5, champ.r)
        assertEquals("AtRisk", CustomerMath.rfm(90, 6, 5000.0, monetaryAnchor = 1000.0).segment)
        assertEquals("Sleeping", CustomerMath.rfm(120, 1, 50.0, monetaryAnchor = 1000.0).segment)
        assertEquals("New", CustomerMath.rfm(3, 1, 100.0, monetaryAnchor = 1000.0).segment)
        assertEquals("Champions", CustomerMath.rfm(10, 8, 100.0, monetaryAnchor = 1000.0).segment)
        assertEquals("Loyal", CustomerMath.rfm(25, 8, 100.0, monetaryAnchor = 1000.0).segment)
    }

    // ————— A14 الشراء القادم —————
    @Test
    fun `nextPurchaseEta future vs overdue`() {
        val future = CustomerMath.nextPurchaseEta(lastPurchaseEpochDay = 1000, medianGapDays = 10.0, todayEpochDay = 1005)
        assertEquals(1010, future.expectedEpochDay); assertEquals(5, future.daysFromToday)
        val overdue = CustomerMath.nextPurchaseEta(lastPurchaseEpochDay = 900, medianGapDays = 10.0, todayEpochDay = 1005)
        assertTrue("المتأخر يُتوقع من اليوم لا من الماضي", overdue.expectedEpochDay > 1005)
        assertTrue(overdue.windowDays >= 2)
    }

    // ————— A15 أولوية التحصيل —————
    @Test
    fun `collectionPriority ordering and weights`() {
        val out = CollectionMath.priority(listOf(
            CollectItem(1, 100.0, 5, 0.9),     // صغير/حديث/موثوق — أخير
            CollectItem(2, 1000.0, 90, 0.2),   // كبير/متأخر/غير موثوق — أولاً
            CollectItem(3, 900.0, 120, 0.95),
        ))
        assertEquals(2L, out.first().partyId)
        assertTrue(out.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertTrue(CollectionMath.priority(emptyList()).isEmpty())
    }

    // ————— A16 الفجوة النقدية —————
    @Test
    fun `cashGap first negative week and trough`() {
        val g = CollectionMath.cashGap(100.0, listOf(300.0, 100.0), listOf(500.0, 200.0))
        assertEquals(1, g.firstNegativeWeek)
        assertEquals(-200.0, g.lowestBalance, 1e-9); assertEquals(2, g.lowestWeek)
        val ok = CollectionMath.cashGap(1000.0, listOf(500.0), listOf(100.0))
        assertNull(ok.firstNegativeWeek); assertEquals(1000.0, ok.lowestBalance, 1e-9); assertEquals(0, ok.lowestWeek) // القاع يشمل البداية
    }

    // ————— A17 مخاطرة الشيك —————
    @Test
    fun `checkBounceRisk combines history and amount`() {
        assertTrue(CollectionMath.checkBounceRisk(1.0, 5.0) > 0.9)
        assertEquals(0.0, CollectionMath.checkBounceRisk(0.0, 0.5), 0.15)
        assertTrue(CollectionMath.checkBounceRisk(0.5, 3.0) > CollectionMath.checkBounceRisk(0.2, 1.0))
    }

    // ————— A18 قفزة المصروفات —————
    @Test
    fun `expenseJump threshold adapts to volatility`() {
        val calm = List(6) { 100.0 }
        assertTrue(InsightMath.expenseJump(150.0, calm).isJump)     // +50% فوق هادئ
        assertFalse(InsightMath.expenseJump(120.0, calm).isJump)    // +20% عادي
        val wild = listOf(50.0, 200.0, 80.0, 250.0, 60.0, 220.0)
        assertFalse("المتقلب يرفع العتبة", InsightMath.expenseJump(220.0, wild).isJump)
        assertFalse(InsightMath.expenseJump(999.0, emptyList()).isJump)
    }

    // ————— A19 الأزواج المرفوعة —————
    @Test
    fun `bundlePairs lift filter and ordering`() {
        val baskets = listOf(
            listOf(1L, 2L), listOf(1L, 2L), listOf(1L, 2L), listOf(1L, 3L), listOf(2L, 3L), listOf(4L),
        )
        val out: List<Bundle> = InsightMath.bundlePairs(baskets, minCount = 2, topK = 5)
        assertEquals(1L to 2L, out.first().a to out.first().b)
        assertEquals(3, out.first().count)
        assertTrue(out.first().lift > 1.1)
        assertTrue(InsightMath.bundlePairs(listOf(listOf(1L)), minCount = 1).isEmpty())
    }

    // ————— A20-1 ساعات الذروة —————
    @Test
    fun `peakWindows primary and secondary`() {
        val h = DoubleArray(24)
        h[9] = 50.0; h[10] = 100.0; h[11] = 60.0   // ذروة صباحية
        h[18] = 70.0                                // ذروة مسائية ≥60%
        val w = InsightMath.peakWindows(h)
        assertEquals(2, w.size)
        assertEquals(9, w[0].startHour); assertEquals(11, w[0].endHour)
        assertTrue(InsightMath.peakWindows(DoubleArray(24)).isEmpty())
    }

    // ————— A20-2 استقرار الأرباح —————
    @Test
    fun `profitStability levels and insufficient data`() {
        assertEquals(0, InsightMath.profitStability(listOf(100.0, 120.0)).level)
        assertEquals(4, InsightMath.profitStability(List(8) { 100.0 }).level)
        assertEquals(1, InsightMath.profitStability(listOf(100.0, -50.0, 200.0, -80.0, 150.0, -20.0)).level) // خاسر>25%
        val mild = InsightMath.profitStability(listOf(100.0, 140.0, 110.0, 150.0))
        assertTrue(mild.level >= 3)
    }

    // ————— A20-3 محاكي الهدف —————
    @Test
    fun `goalSim eta and required pace`() {
        val ok = InsightMath.goalSim(100.0, 500.0, 10)
        assertEquals(5, ok.etaDays); assertTrue(ok.willFinishInTime)
        val late = InsightMath.goalSim(20.0, 500.0, 10)
        assertEquals(25, late.etaDays); assertFalse(late.willFinishInTime); assertEquals(50.0, late.requiredPacePerDay, 1e-9)
        assertNull(InsightMath.goalSim(0.0, 500.0, 10).etaDays)
        assertTrue(InsightMath.goalSim(100.0, 0.0, 10).willFinishInTime)
    }

    // ————— A20-4 تفكيك الصحة —————
    @Test
    fun `healthDecomposition weights and sorting`() {
        val w = mapOf("margin" to 50.0, "liquidity" to 30.0, "collection" to 20.0)
        val prev = mapOf<String, Double?>("margin" to 0.8, "liquidity" to 0.6, "collection" to 0.7)
        val now = mapOf<String, Double?>("margin" to 0.9, "liquidity" to 0.5, "collection" to 0.7)
        val out = InsightMath.healthDecomposition(prev, now, w)
        assertEquals("margin", out.first().component)          // أكبر أثر مطلق
        assertEquals(5.0, out.first().delta, 0.01)             // +0.1 × (50/100) × 100
        assertEquals(-3.0, out[1].delta, 0.01)
        assertTrue(InsightMath.healthDecomposition(prev, now, mapOf("" to 0.0)).isEmpty())
    }
}
