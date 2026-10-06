package com.superbiz.app.domain.algo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * — اختبارات FinMath: قرشاً بقرش على الحالات الحدية الحقيقية.
 * كل القيم العددية محسوبة يدوياً (ومتحقق منها بحساب مستقل) قبل كتابة التأكيد.
*/
class FinMathTest {

    // ───────── dso ─────────

    @Test
    fun dso_happyPathAndClamp() {
        // 365 × 50000 / 500000 = 36.5
        assertEquals(36.5, FinMath.dso(50000.0, 500000.0, 365), 1e-9)
        // مستحقات سالبة تُقيد عند 0
        assertEquals(0.0, FinMath.dso(-100.0, 100.0, 10), 1e-9)
    }

    @Test
    fun dso_guardsReturnZero() {
        assertEquals(0.0, FinMath.dso(100.0, 0.0, 30), 1e-9)
        assertEquals(0.0, FinMath.dso(100.0, -5.0, 30), 1e-9)
        assertEquals(0.0, FinMath.dso(100.0, 1000.0, 0), 1e-9)
        assertEquals(0.0, FinMath.dso(100.0, 1000.0, -3), 1e-9)
    }

    // ───────── collectionEfficiency ─────────

    @Test
    fun collectionEfficiency_basicAndClamp() {
        assertEquals(80.0, FinMath.collectionEfficiency(800.0, 1000.0), 1e-9)
        // تحصيل أكثر من المفوتر → سقف 100
        assertEquals(100.0, FinMath.collectionEfficiency(1200.0, 1000.0), 1e-9)
        assertEquals(0.0, FinMath.collectionEfficiency(-50.0, 1000.0), 1e-9)
        assertEquals(0.0, FinMath.collectionEfficiency(0.0, 1000.0), 1e-9)
    }

    @Test
    fun collectionEfficiency_guardsReturnZero() {
        assertEquals(0.0, FinMath.collectionEfficiency(800.0, 0.0), 1e-9)
        assertEquals(0.0, FinMath.collectionEfficiency(800.0, -100.0), 1e-9)
    }

    // ───────── burnRate ─────────

    @Test
    fun burnRate_lastWindowMean() {
        val series = (1..100).map { it.toDouble() }
        // آخر 7 قيم: 94..100 → المتوسط 97.0
        assertEquals(97.0, FinMath.burnRate(series, window = 7), 1e-9)
        // نافذة أوسع من السلسلة → متوسط الكل = 50.5
        assertEquals(50.5, FinMath.burnRate(series, window = 200), 1e-9)
    }

    @Test
    fun burnRate_emptyAndSign() {
        assertEquals(0.0, FinMath.burnRate(emptyList()), 1e-9)
        assertEquals(0.0, FinMath.burnRate(listOf(1.0, 2.0), window = 0), 1e-9)
        // موجب = فائض
        assertEquals(66.66666666666667, FinMath.burnRate(listOf(100.0, -200.0, 300.0)), 1e-9)
        // سالب = حرق نقود
        assertEquals(-15.0, FinMath.burnRate(listOf(-10.0, -20.0)), 1e-9)
    }

    // ───────── runwayDays ─────────

    @Test
    fun runwayDays_basicAndNegativeCash() {
        assertEquals(30.0, FinMath.runwayDays(90000.0, 3000.0), 1e-9)
        assertEquals(0.0, FinMath.runwayDays(0.0, 10.0), 1e-9)
        // نقود سالبة = نفدت السيولة
        assertEquals(0.0, FinMath.runwayDays(-100.0, 10.0), 1e-9)
    }

    @Test
    fun runwayDays_unlimitedSentinel() {
        // لا حرق → سيولة غير محدودة (حارسة -1.0)
        assertEquals(-1.0, FinMath.runwayDays(1000.0, 0.0), 1e-9)
        assertEquals(-1.0, FinMath.runwayDays(1000.0, -5.0), 1e-9)
    }

    // ───────── eoq ─────────

    @Test
    fun eoq_happyPath() {
        // √(2 × 1000 × 50 / 2) = √50000
        assertEquals(223.60679774997897, FinMath.eoq(1000.0, 50.0, 2.0), 1e-9)
    }

    @Test
    fun eoq_guardsReturnZero() {
        assertEquals(0.0, FinMath.eoq(0.0, 50.0, 2.0), 1e-9)
        assertEquals(0.0, FinMath.eoq(1000.0, 0.0, 2.0), 1e-9)
        assertEquals(0.0, FinMath.eoq(1000.0, 50.0, 0.0), 1e-9)
        assertEquals(0.0, FinMath.eoq(-1000.0, 50.0, 2.0), 1e-9)
    }

    // ───────── safetyStock ─────────

    @Test
    fun safetyStock_formulaAndDefaultZ() {
        // 1.65 × 10 × √9 = 49.5
        assertEquals(49.5, FinMath.safetyStock(100.0, 10.0, 9.0, 1.65), 1e-9)
        assertEquals(49.5, FinMath.safetyStock(100.0, 10.0, 9.0), 1e-9)
    }

    @Test
    fun safetyStock_negativesGiveZero() {
        assertEquals(0.0, FinMath.safetyStock(100.0, -10.0, 9.0), 1e-9)
        assertEquals(0.0, FinMath.safetyStock(100.0, 10.0, -9.0), 1e-9)
        assertEquals(0.0, FinMath.safetyStock(-100.0, 10.0, 9.0), 1e-9)
        assertEquals(0.0, FinMath.safetyStock(100.0, 10.0, 9.0, -1.65), 1e-9)
        assertEquals(0.0, FinMath.safetyStock(100.0, 0.0, 9.0), 1e-9)
    }

    // ───────── wacUnitCost ─────────

    @Test
    fun wacUnitCost_weightedAverage() {
        // (100×10 + 50×12 + 50×14) / 200 = 2300/200 = 11.5
        assertEquals(
            11.5,
            FinMath.wacUnitCost(100.0, 10.0, listOf(50.0 to 12.0, 50.0 to 14.0)),
            1e-9,
        )
        // لا مشتريات → تكلفة الافتتاحي نفسها
        assertEquals(10.0, FinMath.wacUnitCost(100.0, 10.0, emptyList()), 1e-9)
        // افتتاحي صفري → تكلفة أول مشترى
        assertEquals(20.0, FinMath.wacUnitCost(0.0, 0.0, listOf(100.0 to 20.0)), 1e-9)
        // مقام صفري
        assertEquals(0.0, FinMath.wacUnitCost(0.0, 10.0, emptyList()), 1e-9)
    }

    // ───────── fifoValue ─────────

    @Test
    fun fifoValue_partialAndFullConsumption() {
        val layers = listOf(100.0 to 10.0, 50.0 to 12.0)
        // يستهلك 100@10 + 20@12 → يبقى 30@12 = 360
        assertEquals(360.0, FinMath.fifoValue(layers, 120.0), 1e-9)
        // يستهلك 25@10 → يبقى 75@10 + 50@12 = 1350
        assertEquals(1350.0, FinMath.fifoValue(layers, 25.0), 1e-9)
        // كمية ≥ إجمالي الطبقات → 0.0
        assertEquals(0.0, FinMath.fifoValue(layers, 150.0), 1e-9)
        assertEquals(0.0, FinMath.fifoValue(layers, 999.0), 1e-9)
        assertEquals(0.0, FinMath.fifoValue(emptyList(), 10.0), 1e-9)
    }

    @Test
    fun fifoValue_noConsumption() {
        val layers = listOf(100.0 to 10.0, 50.0 to 12.0)
        // صفر أو سالب → قيمة كل الطبقات = 1600
        assertEquals(1600.0, FinMath.fifoValue(layers, 0.0), 1e-9)
        assertEquals(1600.0, FinMath.fifoValue(layers, -5.0), 1e-9)
    }

    // ───────── charmPrice ─────────

    @Test
    fun charmPrice_ending95() {
        assertEquals(100.95, FinMath.charmPrice(100.0), 1e-9)
        assertEquals(100.95, FinMath.charmPrice(100.94), 1e-9)
        // كسر 0.96 > 0.95 → يقفز للتالي
        assertEquals(101.95, FinMath.charmPrice(100.96), 1e-9)
        assertEquals(50.95, FinMath.charmPrice(50.3), 1e-9)
        assertEquals(0.0, FinMath.charmPrice(0.0), 1e-9)
        assertEquals(0.0, FinMath.charmPrice(-5.0), 1e-9)
        // حافة FP موثقة: 100.95 - 100 = 0.9500000000000028 (> 0.95) → السعر التالي
        assertEquals(101.95, FinMath.charmPrice(100.95), 1e-9)
    }

    @Test
    fun charmPrice_customEnding() {
        assertEquals(100.99, FinMath.charmPrice(100.0, 0.99), 1e-9)
        // كسر 0.995 > 0.99 → floor + 1 + 0.99
        assertEquals(101.99, FinMath.charmPrice(100.995, 0.99), 1e-9)
    }

    // ───────── vatByRate ─────────

    @Test
    fun vatByRate_groupsAndSortsDesc() {
        val rows = listOf(100.0 to 0.15, 200.0 to 0.15, 50.0 to 0.0)
        val result = FinMath.vatByRate(rows)
        assertEquals(2, result.size)
        assertEquals(0.15, result[0].first, 1e-9)
        assertEquals(300.0, result[0].second, 1e-9)
        assertEquals(45.0, result[0].third, 1e-9)
        assertEquals(0.0, result[1].first, 1e-9)
        assertEquals(50.0, result[1].second, 1e-9)
        assertEquals(0.0, result[1].third, 1e-9)

        // تجميع نسبة مكررة غير متجاورة + ترتيب تنازلي
        val mixed = FinMath.vatByRate(listOf(10.0 to 0.05, 20.0 to 0.15, 30.0 to 0.05))
        assertEquals(2, mixed.size)
        assertEquals(0.15, mixed[0].first, 1e-9)
        assertEquals(20.0, mixed[0].second, 1e-9)
        assertEquals(3.0, mixed[0].third, 1e-9)
        assertEquals(0.05, mixed[1].first, 1e-9)
        assertEquals(40.0, mixed[1].second, 1e-9)
        assertEquals(2.0, mixed[1].third, 1e-9)
    }

    @Test
    fun vatByRate_empty() {
        assertEquals(0, FinMath.vatByRate(emptyList()).size)
    }
}
