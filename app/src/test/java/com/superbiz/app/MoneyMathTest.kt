package com.superbiz.app

import com.superbiz.app.domain.algo.addVat
import com.superbiz.app.domain.algo.amortize
import com.superbiz.app.domain.algo.breakEvenUnits
import com.superbiz.app.domain.algo.changeBreakdown
import com.superbiz.app.domain.algo.compoundGrowth
import com.superbiz.app.domain.algo.loanPayment
import com.superbiz.app.domain.algo.lateFee
import com.superbiz.app.domain.algo.marginPct
import com.superbiz.app.domain.algo.markupPct
import com.superbiz.app.domain.algo.moneyEquals
import com.superbiz.app.domain.algo.priceForMargin
import com.superbiz.app.domain.algo.round2
import com.superbiz.app.domain.algo.splitVat
import com.superbiz.app.domain.algo.tierDiscount
import com.superbiz.app.domain.algo.weightedAverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات MoneyMath: قرشاً بقرش على الحالات الحدية الحقيقية.
*/
class MoneyMathTest {

    @Test
    fun `round2 half-up cents`() {
        assertEquals(12.35, round2(12.346), 1e-9)
        assertEquals(12.34, round2(12.344), 1e-9)
        // [P6-M3 إصلاح]: المنتصف لم يعد «غامضاً» — round2 تفوّض الآن إلى BigDecimal HALF_UP القانوني
        // (كانت الحالة متجنّبة عمداً بعرف kotlin.math.round) — والسالب بعيداً عن الصفر لا نحو صفره
        assertEquals(0.13, round2(0.125), 1e-9)   // كانت 0.12 بعرف kotlin.round (ربط الأنصاف للزوجي)
        assertEquals(-0.13, round2(-0.125), 1e-9) // كانت -0.12 نحو الصفر — الدلالة المحاسبية الجديدة
        assertEquals(0.01, round2(0.006), 1e-9)
        assertEquals(-12.35, round2(-12.346), 1e-9)
    }

    @Test
    fun `moneyEquals tolerance is half a halala`() {
        assertTrue(moneyEquals(100.001, 100.0))
        assertFalse(moneyEquals(100.02, 100.0))
    }

    @Test
    fun `splitVat 115 at 15 is exactly 100 plus 15`() {
        val (net, vat) = splitVat(115.0, 15.0)
        assertEquals(100.0, net, 1e-9)
        assertEquals(15.0, vat, 1e-9)
    }

    @Test
    fun `splitVat round trip with addVat preserves gross`() {
        for (gross in listOf(47.5, 9.99, 1333.33, 0.05)) {
            val (net, vat) = splitVat(gross, 15.0)
            val (gross2, vat2) = addVat(net, 15.0)
            assertEquals(gross, gross2, 0.011)
            assertEquals(vat, vat2, 0.011)
        }
    }

    @Test
    fun `splitVat zero rate passes through`() {
        val (net, vat) = splitVat(250.0, 0.0)
        assertEquals(250.0, net, 1e-9)
        assertEquals(0.0, vat, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `splitVat rejects negative gross`() { splitVat(-1.0, 15.0) }

    @Test
    fun `margin and markup of known pair`() {
        // تكلفة 60 وسعر 100 → هامش 40% وعلامة 66.67%
        assertEquals(40.0, marginPct(60.0, 100.0), 1e-9)
        assertEquals(66.67, markupPct(60.0, 100.0), 0.01)
    }

    @Test
    fun `priceForMargin closes the loop with marginPct`() {
        val price = priceForMargin(75.0, 20.0)
        assertEquals(93.75, price, 1e-9)
        assertEquals(20.0, marginPct(75.0, price), 1e-9)
    }

    @Test
    fun `tierDiscount picks the highest applicable tier`() {
        val tiers = listOf(10.0 to 5.0, 50.0 to 10.0, 5.0 to 3.0)
        val (net, applied) = tierDiscount(60.0, 10.0, tiers)
        assertEquals(10.0, applied, 1e-9)
        assertEquals(540.0, net, 1e-9)
    }

    @Test
    fun `tierDiscount below all tiers keeps full price`() {
        val (net, applied) = tierDiscount(2.0, 10.0, listOf(5.0 to 20.0))
        assertEquals(20.0, net, 1e-9)
        assertEquals(0.0, applied, 1e-9)
    }

    @Test
    fun `lateFee respects daily accrual and total cap`() {
        // 1000 بـ 0.5% يومياً: 10 أيام = 50 بلا سقف، 30 يوماً تُسقَّف عند 10% = 100
        assertEquals(50.0, lateFee(1000.0, 10, 0.5, 100.0), 1e-9)
        assertEquals(100.0, lateFee(1000.0, 30, 0.5, 10.0), 1e-9)
        assertEquals(0.0, lateFee(1000.0, 0, 0.5, 10.0), 1e-9)
        assertEquals(0.0, lateFee(0.0, 30, 0.5, 10.0), 1e-9)
    }

    @Test
    fun `compoundGrowth of known series`() {
        // 1000 بنمو 10% لعامين = 1210
        assertEquals(1210.0, compoundGrowth(1000.0, 10.0, 2), 1e-9)
        assertEquals(1000.0, compoundGrowth(1000.0, 10.0, 0), 1e-9)
    }

    @Test
    fun `loanPayment zero interest is simple division`() {
        assertEquals(100.0, loanPayment(1200.0, 0.0, 12), 1e-9)
    }

    @Test
    fun `loanPayment positive interest amortizes to zero`() {
        val pmt = loanPayment(10000.0, 12.0, 12)
        val rows = amortize(10000.0, 12.0, 12)
        assertEquals(12, rows.size)
        assertEquals(0.0, rows.last().remaining, 1e-9)
        // مجموع أجزاء الأصل = الأصل بالضبط
        assertEquals(10000.0, rows.sumOf { it.principalPart }, 0.01)
        // كل قسط = فائدة + أصل
        rows.forEach { assertEquals(it.payment, it.interest + it.principalPart, 0.011) }
        assertTrue(pmt > 0)
    }

    @Test
    fun `breakEvenUnits reaches and rejects`() {
        // ثابتة 1000، ربح الوحدة 25 → 40 وحدة
        assertEquals(40.0, breakEvenUnits(1000.0, 100.0, 75.0), 1e-9)
        // سعر دون التكلفة المتغيرة → لا تعادل ممكن
        assertEquals(-1.0, breakEvenUnits(1000.0, 50.0, 75.0), 1e-9)
        // لا مصاريف ثابتة → تعادل فوري
        assertEquals(0.0, breakEvenUnits(0.0, 50.0, 75.0), 1e-9)
    }

    @Test
    fun `weightedAverage of quantity-weighted prices`() {
        // (10×2) + (20×1) = 40 على 3 وحدات → 13.33
        val w = weightedAverage(listOf(10.0 to 2.0, 20.0 to 1.0))
        assertEquals(13.33, w, 0.01)
        assertEquals(0.0, weightedAverage(emptyList()), 1e-9)
        assertEquals(0.0, weightedAverage(listOf(5.0 to 0.0, 7.0 to 0.0)), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `weightedAverage rejects negative weights`() {
        weightedAverage(listOf(5.0 to -1.0))
    }

    @Test
    fun `changeBreakdown greedy on SAR denominations`() {
        val sar = listOf(500.0, 100.0, 50.0, 10.0, 5.0, 1.0, 0.5)
        val ch = changeBreakdown(137.5, sar)
        assertEquals(137.5, ch.amount, 1e-9)
        assertEquals(137.5, ch.totalGiven, 1e-9)
        // 100 + 10×3 + 5 + 1×2 + 0.5 — الباقي بعد 135 هو 2.5 وليس 1.5
        assertEquals(listOf(100.0 to 1, 10.0 to 3, 5.0 to 1, 1.0 to 2, 0.5 to 1), ch.pieces)
    }

    @Test
    fun `changeBreakdown zero and unmatchable remainder`() {
        assertTrue(changeBreakdown(0.0, listOf(100.0)).pieces.isEmpty())
        // 3.2 بفئات 5/1 → قطعة (1×3) + كسر 0.2 صريح لا مختفٍ (إدخالان فقط)
        val ch = changeBreakdown(3.2, listOf(5.0, 1.0))
        assertEquals(3.2, ch.amount, 1e-9)
        assertEquals(2, ch.pieces.size)
        assertEquals(1.0 to 3, ch.pieces[0])
        assertEquals(0.2, ch.pieces[1].first, 1e-9)
    }
}
