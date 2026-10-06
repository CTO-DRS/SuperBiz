package com.superbiz.app

import com.superbiz.app.domain.algo.cagr
import com.superbiz.app.domain.algo.coefficientOfVariation
import com.superbiz.app.domain.algo.gini
import com.superbiz.app.domain.algo.histogram
import com.superbiz.app.domain.algo.iqrOutliers
import com.superbiz.app.domain.algo.mad
import com.superbiz.app.domain.algo.madOutliers
import com.superbiz.app.domain.algo.median
import com.superbiz.app.domain.algo.minMaxNormalize
import com.superbiz.app.domain.algo.movingSum
import com.superbiz.app.domain.algo.pearson
import com.superbiz.app.domain.algo.percentile
import com.superbiz.app.domain.algo.softmaxWeights
import com.superbiz.app.domain.algo.topShare
import com.superbiz.app.domain.algo.trendDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات StatsMath على أمثلة محسوبة يدوياً.
*/
class StatsMathTest {

    @Test
    fun `median odd even and empty`() {
        assertEquals(3.0, median(listOf(5.0, 1.0, 3.0)), 1e-9)
        assertEquals(2.5, median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9)
        assertEquals(0.0, median(emptyList()), 1e-9)
    }

    @Test
    fun `percentile interpolates linearly`() {
        assertEquals(25.0, percentile(listOf(10.0, 20.0, 30.0, 40.0), 50.0), 1e-9)
        assertEquals(10.0, percentile(listOf(10.0, 20.0, 30.0, 40.0), 0.0), 1e-9)
        assertEquals(40.0, percentile(listOf(10.0, 20.0, 30.0, 40.0), 100.0), 1e-9)
        assertEquals(20.0, percentile(listOf(10.0, 20.0, 30.0, 40.0), 33.333), 0.01)
    }

    @Test
    fun `iqrOutliers flags the classic 100x value`() {
        // 1..9 ثم 100: 100 فوق Q3 + 1.5×IQR
        val vals = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0)
        assertEquals(listOf(9), iqrOutliers(vals))
        assertEquals(emptyList<Int>(), iqrOutliers(listOf(1.0, 2.0, 3.0)))
    }

    @Test
    fun `mad of known sample`() {
        // الوسيط 3، الانحرافات [2,1,0,1,2] مرتبة [0,1,1,2,2] وسيطها 1
        assertEquals(1.0, mad(listOf(1.0, 2.0, 3.0, 4.0, 5.0)), 1e-9)
        assertEquals(0.0, mad(emptyList()), 1e-9)
    }

    @Test
    fun `madOutliers robust to heavy tails`() {
        // الوسيط 10 و MAD = 1.0 (انحرافات [2,1,0,0,1,2,0,80] وسيطها 1)
        // z المعدّل لقيمة 90 = 0.6745×80/1 ≈ 54 شاذة، ولقيمة 8 ≈ 1.35 مقبولة
        val vals = listOf(8.0, 9.0, 10.0, 10.0, 11.0, 12.0, 10.0, 90.0)
        assertEquals(listOf(7), madOutliers(vals))
        // توزيع مسطح: لا حكم
        assertEquals(emptyList<Int>(), madOutliers(List(6) { 5.0 }))
    }

    @Test
    fun `pearson perfect positive negative and none`() {
        assertEquals(1.0, pearson(listOf(1.0, 2.0, 3.0, 4.0), listOf(10.0, 20.0, 30.0, 40.0)), 1e-6)
        assertEquals(-1.0, pearson(listOf(1.0, 2.0, 3.0, 4.0), listOf(40.0, 30.0, 20.0, 10.0)), 1e-6)
        assertEquals(0.0, pearson(listOf(5.0, 5.0, 5.0), listOf(1.0, 2.0, 3.0)), 1e-6)
    }

    @Test
    fun `coefficientOfVariation stability ranking`() {
        // منتظم 5% مقابل متقلب ~52%
        val stable = coefficientOfVariation(listOf(100.0, 105.0, 95.0, 100.0))
        val wild = coefficientOfVariation(listOf(10.0, 90.0, 5.0, 95.0))
        assertTrue(stable < 10.0)
        assertTrue(wild > 50.0)
        assertEquals(0.0, coefficientOfVariation(emptyList()), 1e-9)
    }

    @Test
    fun `minMaxNormalize bounds and constant series`() {
        val n = minMaxNormalize(listOf(2.0, 4.0, 6.0))
        assertEquals(0.0, n[0], 1e-9)
        assertEquals(1.0, n[2], 1e-9)
        assertEquals(listOf(0.5, 0.5), minMaxNormalize(listOf(7.0, 7.0)))
        assertTrue(minMaxNormalize(emptyList()).isEmpty())
    }

    @Test
    fun `histogram bins and max value goes to last bin`() {
        // 1..10 في 5 فئات: التوزيع منتظم 2 لكل فئة، و10 يدخل آخر فئة لا فئة وهمية
        val h = histogram(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), 5)
        assertEquals(5, h.size)
        assertEquals(10, h.sum())
        assertEquals(2, h[0])
        assertEquals(2, h[4])
        // قيم متساوية → الفئة الوسطى
        val flat = histogram(listOf(5.0, 5.0, 5.0), 4)
        assertEquals(4, flat.size)
        assertEquals(3, flat.sum())
        assertEquals(3, flat[2])
    }

    @Test
    fun `gini equal distribution is zero and single owner is near one`() {
        assertEquals(0.0, gini(listOf(100.0, 100.0, 100.0, 100.0)), 1e-6)
        assertEquals(0.0, gini(listOf(50.0, 50.0)), 1e-6)
        // عميل واحد يأخذ كل الإيراد: اقتراب من 1
        val concentrated = gini(listOf(0.0, 0.0, 0.0, 100.0))
        assertTrue("gini=$concentrated", concentrated > 0.7)
        assertEquals(0.0, gini(emptyList()), 1e-9)
        assertEquals(0.0, gini(listOf(0.0, 0.0)), 1e-9)
    }

    @Test
    fun `topShare of top customers`() {
        // الأفضل 2 من 4 (100+100 من 250 إجمالاً) = 0.8
        assertEquals(0.8, topShare(listOf(100.0, 100.0, 25.0, 25.0), 2), 1e-9)
        assertEquals(1.0, topShare(listOf(10.0, 20.0), 5), 1e-9)
        assertEquals(0.0, topShare(emptyList(), 3), 1e-9)
    }

    @Test
    fun `movingSum windows`() {
        assertEquals(listOf(6.0, 9.0, 12.0), movingSum(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3))
        assertTrue(movingSum(listOf(1.0, 2.0), 5).isEmpty())
    }

    @Test
    fun `trendDirection up down and flat`() {
        assertEquals(1, trendDirection(listOf(10.0, 12.0, 14.0, 16.0, 20.0)))
        assertEquals(-1, trendDirection(listOf(20.0, 16.0, 14.0, 12.0, 10.0)))
        // صعود ثم هبوط ثم صعود: r2 منخفض → مسطح
        assertEquals(0, trendDirection(listOf(10.0, 25.0, 12.0, 28.0, 9.0, 30.0, 8.0)))
        assertEquals(0, trendDirection(listOf(5.0, 5.0)))
    }

    @Test
    fun `cagr of doubling revenue`() {
        // من 100 إلى 200 في 4 سنوات: نمو 18.92% سنوياً
        assertEquals(0.1892, cagr(100.0, 200.0, 4), 0.001)
        assertEquals(0.0, cagr(0.0, 200.0, 4), 1e-9)
        assertEquals(0.0, cagr(100.0, 100.0, 4), 1e-9)
    }

    @Test
    fun `softmaxWeights dominance and uniform`() {
        val hot = softmaxWeights(listOf(10.0, 1.0, 1.0), 0.1)
        assertTrue(hot[0] > 0.9)
        assertEquals(1.0, hot.sum(), 1e-6)
        val uniform = softmaxWeights(listOf(5.0, 5.0, 5.0), 1.0)
        assertEquals(1.0 / 3, uniform[0], 1e-9)
        assertEquals(1.0 / 3, uniform[1], 1e-9)
        assertEquals(1.0 / 3, uniform[2], 1e-9)
        val cold = softmaxWeights(listOf(10.0, 1.0), 50.0)
        assertTrue(cold[0] < 0.65)
    }
}
