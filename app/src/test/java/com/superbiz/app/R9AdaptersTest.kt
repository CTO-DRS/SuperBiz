package com.superbiz.app

import com.superbiz.app.domain.algo.GrowthMath
import com.superbiz.app.domain.algo.R9Adapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات مهايئات الربط بين الخوارزميات والبيانات الحقيقية.
*/
class R9AdaptersTest {

    // ————— paymentPairsToBehavior —————
    @Test
    fun `payment pairs to behavior`() {
        assertEquals(Triple(0, 0, 0.0), R9Adapters.paymentPairsToBehavior(emptyList()))
        val day = 86_400_000L
        // سداد قبل الاستحقاق → في الوقت
        val (on1, late1, d1) = R9Adapters.paymentPairsToBehavior(listOf(10L * day to 9L * day))
        assertEquals(1, on1); assertEquals(0, late1); assertEquals(0.0, d1, 1e-9)
        // متأخر يومين ومتأخر 4 أيام → متوسط 3
        val (on2, late2, d2) = R9Adapters.paymentPairsToBehavior(
            listOf(10L * day to 12L * day, 20L * day to 24L * day)
        )
        assertEquals(0, on2); assertEquals(2, late2); assertEquals(3.0, d2, 1e-9)
        // غير المسدد (صفر) يُستبعد
        val (on3, late3, _) = R9Adapters.paymentPairsToBehavior(listOf(10L * day to 0L, 10L * day to 9L * day))
        assertEquals(1, on3); assertEquals(0, late3)
    }

    // ————— ewmaBacktest —————
    @Test
    fun `ewma backtest pairs`() {
        assertNull(R9Adapters.ewmaBacktest(listOf(5.0)))
        val (act, pred) = R9Adapters.ewmaBacktest(listOf(10.0, 10.0, 10.0))!!
        assertEquals(listOf(10.0, 10.0), act)
        assertEquals(listOf(10.0, 10.0), pred)
        // سلسلة صاعدة: التنبؤ يفتقد الصعود (انحياز سالب متوقع)
        val up = R9Adapters.ewmaBacktest(listOf(1.0, 2.0, 3.0, 4.0, 5.0))!!
        assertTrue(up.first.size == 4 && up.first.last() == 5.0)
        assertTrue(up.second.last() < up.first.last())
        // الدقة المنطقية للتسلسل
        val acc = GrowthMath.forecastAccuracy(up.first, up.second)!!
        assertTrue(acc.grade in 1..4)
    }

    // ————— dailyDemandStats —————
    @Test
    fun `daily demand stats includes zero days`() {
        assertEquals(0.0 to 0.0, R9Adapters.dailyDemandStats(emptyList()))
        val (m1, sd1) = R9Adapters.dailyDemandStats(List(30) { 4.0 })
        assertEquals(4.0, m1, 1e-9); assertEquals(0.0, sd1, 1e-9)
        // بيع في يومين فقط من 30: الوسطى تُحسب مع الأصفار
        val series = List(30) { 0.0 }.toMutableList().also { it[2] = 30.0; it[10] = 30.0 }
        val (m2, _) = R9Adapters.dailyDemandStats(series)
        assertEquals(2.0, m2, 1e-9)
    }

    // ————— shiftsToSchedule —————
    @Test
    fun `shifts sorted by hour and filtered`() {
        val out = R9Adapters.shiftsToSchedule(listOf(18 to 3.0, 12 to 2.5, 9 to 0.0))
        assertEquals(listOf(12 to 2.5, 18 to 3.0), out)
    }
}
