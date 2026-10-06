package com.superbiz.app

import com.superbiz.app.domain.analytics.abcClassify
import com.superbiz.app.domain.analytics.allocateFifo
import com.superbiz.app.domain.analytics.arabicNormalize
import com.superbiz.app.domain.analytics.agingBuckets
import com.superbiz.app.domain.analytics.creditScore
import com.superbiz.app.domain.analytics.daysOfCover
import com.superbiz.app.domain.analytics.eoq
import com.superbiz.app.domain.analytics.ewma
import com.superbiz.app.domain.analytics.goalPace
import com.superbiz.app.domain.analytics.isProbableDuplicate
import com.superbiz.app.domain.analytics.latestAnomalyZ
import com.superbiz.app.domain.analytics.levenshtein
import com.superbiz.app.domain.analytics.linearRegression
import com.superbiz.app.domain.analytics.movingAverage
import com.superbiz.app.domain.analytics.percentageShares
import com.superbiz.app.domain.analytics.reorderPoint
import com.superbiz.app.domain.analytics.safetyStock
import com.superbiz.app.domain.analytics.seasonalMonthlyIndex
import com.superbiz.app.domain.analytics.similarity
import com.superbiz.app.domain.analytics.standardDeviation
import com.superbiz.app.domain.analytics.topK
import com.superbiz.app.domain.analytics.zScores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات مكتبة الخوارزميات التحليلية BizMath
 * كل دالة لها اختبار حدودي + اختبار قيمة معروفة رياضياً.
*/
class AnalyticsTest {

    // ── 1) إحصاء ──
    @Test fun movingAverage_windowsAreCorrect() {
        // نوافذ 2 على [1,2,3,4] = [1.5, 2.5, 3.5]
        assertEquals(listOf(1.5, 2.5, 3.5), movingAverage(listOf(1.0, 2.0, 3.0, 4.0), 2))
        // سلسلة أقصر من النافذة = فراغ
        assertTrue(movingAverage(listOf(1.0), 2).isEmpty())
    }

    @Test fun standardDeviation_knownValue() {
        // [2,4,4,4,5,5,7,9] σ = 2 (المثال الكلاسيكي)
        assertEquals(2.0, standardDeviation(listOf(2.0,4.0,4.0,4.0,5.0,5.0,7.0,9.0)), 1e-9)
        assertEquals(0.0, standardDeviation(emptyList()), 1e-9)
        assertEquals(0.0, standardDeviation(listOf(5.0)), 1e-9)
    }

    @Test fun zScores_detectsOutlier() {
        val base = List(9) { 10.0 }
        val series = base + 30.0 // شذوذ إيجابي واضح
        val z = zScores(series).last()
        assertTrue("يجب أن يتجاوز z العتبة", z > 2.0)
        // سلسلة ثابتة: صفر تشتت → كل الدرجات صفر
        assertTrue(zScores(List(5) { 7.0 }).all { it == 0.0 })
    }

    @Test fun latestAnomalyZ_requiresMinimumData() {
        assertNull(latestAnomalyZ(listOf(1.0, 2.0)))
        // سلسلة طويلة بما يكفي مع نقطة شاذة واضحة: σ ≈ 11.18 و z ≈ 2.24
        val z = latestAnomalyZ(listOf(5.0, 5.0, 5.0, 5.0, 5.0, 35.0))
        assertTrue(z != null && z > 2.2)
    }

    @Test fun ewma_weightsLaterPoints() {
        val e = ewma(listOf(0.0, 0.0, 0.0, 100.0), 0.9)
        // بـ α=0.9: الأخير = 90.0 بالضبط — قريب جداً من آخر مشاهدة ويفوق كل السابقة الصفرية
        assertEquals(90.0, e.last(), 1e-9)
        assertTrue(e.last() > e[2])
        assertTrue(e.first() == 0.0)
    }

    @Test fun linearRegression_perfectLine() {
        val x = listOf(1.0, 2.0, 3.0, 4.0)
        val y = listOf(3.0, 5.0, 7.0, 9.0) // y = 2x + 1
        val (slope, intercept, r2) = linearRegression(x, y)
        assertEquals(2.0, slope, 1e-9)
        assertEquals(1.0, intercept, 1e-9)
        assertEquals(1.0, r2, 1e-9)
    }

    // ── 2) مخزون ──
    @Test fun abcClassify_paretoShape() {
        // قيمة كبيرة واحدة (80%) ووسطى (15%) وصغيرة (5%) وبقية صفرية
        val res = abcClassify(listOf(80.0, 15.0, 5.0, 0.0))
        assertEquals('A', res[0])
        assertEquals('B', res[1])
        assertEquals('C', res[2])
        assertEquals('C', res[3])
        // قائمة فارغة = فراغ؛ كل أصفار = كلها C
        assertTrue(abcClassify(emptyList()).isEmpty())
        assertEquals(listOf('C','C'), abcClassify(listOf(0.0, 0.0)))
    }

    @Test fun reorderPoint_combinesLeadDemandAndSafety() {
        // 10 يومياً × 5 أيام = 50 + أمان z1.65×σ2×√5 ≈ 7.38 → ≈57.38
        val rp = reorderPoint(10.0, 5.0, 2.0, 1.65)
        assertEquals(50.0 + 1.65 * 2.0 * kotlin.math.sqrt(5.0), rp, 1e-9)
        // بلا تقلب: أمان صفر
        assertEquals(30.0, reorderPoint(10.0, 3.0, 0.0), 1e-9)
        // زمن سالب مرفوض
        try { reorderPoint(1.0, -1.0, 0.0); assertTrue(false) } catch (e: IllegalArgumentException) { assertTrue(true) }
    }

    @Test fun safetyStock_zeroVarianceIsZero() {
        assertEquals(0.0, safetyStock(0.0, 7.0), 1e-9)
        assertEquals(0.0, safetyStock(5.0, 0.0), 1e-9)
    }

    @Test fun eoq_wilsonFormula() {
        // √(2×10000×50/2) = √500000 ≈ 707.107
        assertEquals(kotlin.math.sqrt(2.0 * 10000 * 50 / 2.0), eoq(10000.0, 50.0, 2.0), 1e-9)
        assertEquals(0.0, eoq(0.0, 50.0, 2.0), 1e-9) // بلا طلب
    }

    @Test fun daysOfCover_bounds() {
        assertEquals(10, daysOfCover(50.0, 5.0))
        assertEquals(999, daysOfCover(100.0, 0.0)) // بلا بيع والمخزون موجود
        assertEquals(0, daysOfCover(0.0, 5.0))
    }

    // ── 3) تحصيل ──
    @Test fun allocateFifo_oldestFirstAndCap() {
        val open = listOf(1L to 100.0, 2L to 50.0, 3L to 200.0)
        // دفعة 120: تغطي الأولى كاملة (100) و20 من الثانية
        val a = allocateFifo(open, 120.0)
        assertEquals(2, a.size)
        assertEquals(100.0, a[0].amount, 1e-9)
        assertEquals(20.0, a[1].amount, 1e-9)
        // دفعة أكبر من كل المفتوح: تتوقف عند الاستنفاد
        val b = allocateFifo(open, 9999.0)
        assertEquals(3, b.size)
        assertEquals(100.0 + 50.0 + 200.0, b.sumOf { it.amount }, 1e-9)
        // صفر = لا توزيع
        assertTrue(allocateFifo(open, 0.0).isEmpty())
    }

    @Test fun creditScore_bands() {
        // عميل جديد بلا فواتير: 70 حياداً
        assertEquals(70, creditScore(0.0, 0.0, null, 0, 0.0, 1.0))
        // كل المستحق متأخر وتأخير 90 يوماً وتركز كامل ولم يسدد: أدنى درجة
        val bad = creditScore(1000.0, 1000.0, 90.0, 5, 1.0, 0.0)
        assertEquals(0, bad)
        // تاريخ نظيف: درجة عالية
        val good = creditScore(100.0, 0.0, 0.0, 10, 0.2, 1.0)
        assertTrue(good >= 85)
    }

    // ── 4) تقارير ──
    @Test fun topK_ordersDescending() {
        val data = listOf("أ" to 5.0, "ب" to 9.0, "ج" to 1.0)
        val top = topK(data, { it.second }, 2)
        assertEquals(listOf("ب", "أ"), top.map { it.first })
    }

    @Test fun percentageShares_sumsExactly100() {
        val shares = percentageShares(listOf(1.0, 1.0, 1.0))
        assertEquals(listOf(34, 33, 33), shares.sortedDescending()) // المجموع 100 بالضبط
        assertEquals(100, shares.sum())
        // حالة كسور مقرّبة: 3 أثلاث
        val t = percentageShares(listOf(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 5.0))
        assertEquals(100, t.sum())
        // قائمة فارغة/أصفار
        assertTrue(percentageShares(emptyList()).isEmpty())
        assertEquals(listOf(0, 0), percentageShares(listOf(0.0, 0.0)))
    }

    @Test fun seasonalMonthlyIndex_flatsToOne() {
        val idx = seasonalMonthlyIndex(List(12) { 100.0 })
        assertTrue(idx.all { kotlin.math.abs(it - 1.0) < 1e-9 })
        val peak = seasonalMonthlyIndex(listOf(200.0) + List(11) { 100.0 })
        assertTrue(peak[0] > 1.5) // رمضان مثلاً أعلى من المتوسط
    }

    @Test fun goalPace_aheadVsBehind() {
        // هدف 300 في شهر 30 يوماً: يوم 10 المتوقع 100
        assertEquals(1.5, goalPace(150.0, 300.0, 10, 30), 1e-9) // أمام الجدول
        assertEquals(0.5, goalPace(50.0, 300.0, 10, 30), 1e-9)  // متأخر
        assertEquals(1.0, goalPace(0.0, 0.0, 10, 30), 1e-9)     // بلا هدف = حياد
    }

    // ── 5) تكرار وتشابه ──
    @Test fun levenshtein_basics() {
        assertEquals(0, levenshtein("كتاب", "كتاب"))
        assertEquals(3, levenshtein("kitten", "sitting"))
        assertEquals(2, levenshtein("flaw", "lawn")) // حذف f ثم w→n
        assertEquals(1, levenshtein("cat", "bat"))
    }

    @Test fun similarity_bounds() {
        assertEquals(1.0, similarity("نفس النص", "نفس النص"), 1e-9)
        assertTrue(similarity("أحمد محمد", "احمد mohamed") < 1.0)
    }

    @Test fun arabicNormalize_unifiesVariants() {
        assertEquals(arabicNormalize("أحمد"), arabicNormalize("احمد"))
        assertEquals(arabicNormalize("مؤسسة الإحتياط"), arabicNormalize("موسسه الاحتياط"))
        assertEquals("احمد", arabicNormalize("أَحمَد")) // التشكيل يُقذف
    }

    @Test fun duplicateDetection_phoneAndName() {
        // تكرار برقم هاتف متطابق
        assertTrue(isProbableDuplicate("شركة نجم", "نجم للخدمات", "0501234567", "٠٥٠١٢٣٤٥٦٧"))
        // تكرار باسم شبه مطابق بعد التطبيع
        assertTrue(isProbableDuplicate("مؤسسة الأمانة", "موسسه الامانه"))
        // اسماء مختلفة تماماً
        assertFalse(isProbableDuplicate("شركة الفجر", "مطعم البيك"))
    }

    // ── 6) أعمار ──
    @Test fun agingBuckets_monthBoundaries() {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val pairs = listOf(
            100.0 to (now - 10 * day),   // <= 30 → حالي
            50.0 to (now - 45 * day),    // 31-60
            25.0 to (now - 75 * day),    // 61-90
            12.5 to (now - 120 * day)    // 91+
        )
        val b = agingBuckets(pairs, now)
        assertEquals(100.0, b[0], 1e-9)
        assertEquals(50.0, b[1], 1e-9)
        assertEquals(25.0, b[2], 1e-9)
        assertEquals(12.5, b[3], 1e-9)
        // مستحق في المستقبل = حالي
        val f = agingBuckets(listOf(80.0 to (now + 5 * day)), now)
        assertEquals(80.0, f[0], 1e-9)
    }
}
