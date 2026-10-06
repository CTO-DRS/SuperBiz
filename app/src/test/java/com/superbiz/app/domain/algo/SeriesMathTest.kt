package com.superbiz.app.domain.algo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات SeriesMath: سلاسل زمنية وبنفورد وباريتو على الحالات الحدية.
 * كل القيم العددية محسوبة يدوياً (ومتحقق منها بحساب مستقل) قبل كتابة التأكيد.
 * الطوابع الزمنية كلها ظهراً بالتوقيت العالمي (آمنة لانزياح المناطق الزمنية ±11 ساعة).
*/
class SeriesMathTest {

    // ───────── ema ─────────

    @Test
    fun ema_seededAndRecursive() {
        // ema[i] = 0.5x + 0.5prev: [1, 1.5, 2.25]
        val out = SeriesMath.ema(listOf(1.0, 2.0, 3.0), 0.5)
        assertEquals(3, out.size)
        assertEquals(1.0, out[0], 1e-9)
        assertEquals(1.5, out[1], 1e-9)
        assertEquals(2.25, out[2], 1e-9)
        // عنصر واحد = نفسه
        assertEquals(listOf(10.0), SeriesMath.ema(listOf(10.0), 0.3))
        // فارغة → فارغة
        assertTrue(SeriesMath.ema(emptyList()).isEmpty())
    }

    @Test
    fun ema_alphaClamped() {
        // α > 1 يُقيد إلى 1.0 → نسخة مطابقة
        assertEquals(listOf(1.0, 2.0, 3.0), SeriesMath.ema(listOf(1.0, 2.0, 3.0), 1.5))
        assertEquals(listOf(1.0, 2.0, 3.0), SeriesMath.ema(listOf(1.0, 2.0, 3.0), 1.0))
        // α ≤ 0 يُقيد لأدنى حد موجب → شبه ثابت عند أول قيمة
        val flat = SeriesMath.ema(listOf(1.0, 2.0, 3.0), 0.0)
        assertEquals(1.0, flat[0], 1e-9)
        assertEquals(1.0, flat[1], 1e-6)
        assertEquals(1.0, flat[2], 1e-6)
    }

    // ───────── wma ─────────

    @Test
    fun wma_linearWeights() {
        // نافذة 2: [1, (1×1+2×2)/3, (2×1+3×2)/3]
        val w2 = SeriesMath.wma(listOf(1.0, 2.0, 3.0), 2)
        assertEquals(1.0, w2[0], 1e-9)
        assertEquals(1.6666666666666667, w2[1], 1e-9)
        assertEquals(2.6666666666666665, w2[2], 1e-9)
        // نافذة 3: الفهرس الأخير (1×1+2×2+3×3)/6 = 14/6
        val w3 = SeriesMath.wma(listOf(1.0, 2.0, 3.0), 3)
        assertEquals(1.6666666666666667, w3[1], 1e-9)
        assertEquals(2.3333333333333335, w3[2], 1e-9)
    }

    @Test
    fun wma_guards() {
        val series = listOf(1.0, 2.0, 3.0)
        // window ≤ 1 → نسخة
        assertEquals(series, SeriesMath.wma(series, 1))
        assertEquals(series, SeriesMath.wma(series, 0))
        // نافذة أوسع من السلسلة = نافذة بحجم السلسلة
        val wide = SeriesMath.wma(series, 99)
        assertEquals(2.3333333333333335, wide[2], 1e-9)
        assertTrue(SeriesMath.wma(emptyList(), 3).isEmpty())
    }

    // ───────── holtForecast ─────────

    @Test
    fun holtForecast_twoPointSeries() {
        // level=10, trend=2 ثم تحديث عند 12 → level=12, trend=2
        assertEquals(listOf(14.0), SeriesMath.holtForecast(listOf(10.0, 12.0), 0.5, 0.3, 1))
        assertEquals(listOf(14.0, 16.0, 18.0), SeriesMath.holtForecast(listOf(10.0, 12.0), 0.5, 0.3, 3))
    }

    @Test
    fun holtForecast_threePointSeries() {
        assertEquals(listOf(16.0), SeriesMath.holtForecast(listOf(10.0, 12.0, 14.0), 0.5, 0.3, 1))
        // [8,10,15]: المستوى النهائي 13.5 والاتجاه 2.45 → [15.95, 18.4]
        assertEquals(
            listOf(15.95, 18.4),
            SeriesMath.holtForecast(listOf(8.0, 10.0, 15.0), 0.5, 0.3, 2),
        )
    }

    @Test
    fun holtForecast_degenerateAndHorizon() {
        // نقطة واحدة → تكرار آخر قيمة
        assertEquals(listOf(5.0, 5.0, 5.0), SeriesMath.holtForecast(listOf(5.0), 0.5, 0.3, 3))
        // فارغة → أصفار
        assertEquals(listOf(0.0, 0.0), SeriesMath.holtForecast(emptyList(), 0.5, 0.3, 2))
        // horizon ≤ 0 → فارغة
        assertTrue(SeriesMath.holtForecast(listOf(10.0, 12.0), 0.5, 0.3, 0).isEmpty())
        assertTrue(SeriesMath.holtForecast(listOf(10.0, 12.0), 0.5, 0.3, -1).isEmpty())
    }

    // ───────── streakDays ─────────

    @Test
    fun streakDays_countsBack() {
        assertEquals(3, SeriesMath.streakDays(setOf(100, 101, 102), 102))
        assertEquals(1, SeriesMath.streakDays(setOf(102), 102))
    }

    @Test
    fun streakDays_yesterdayGraceAndGaps() {
        // اليوم غائب لكن أمس حاضر → نبدأ من أمس
        assertEquals(3, SeriesMath.streakDays(setOf(100, 101, 102), 103))
        // انقطاع بالمنتصف
        assertEquals(1, SeriesMath.streakDays(setOf(100, 102), 102))
        // اليوم وأمس غائبان معًا → 0 حتى لو كانت أيام أقدم
        assertEquals(0, SeriesMath.streakDays(setOf(100, 101, 102), 105))
        assertEquals(0, SeriesMath.streakDays(setOf(100), 102))
        assertEquals(0, SeriesMath.streakDays(emptySet(), 102))
    }

    // ───────── dayOfWeekProfile ─────────

    @Test
    fun dayOfWeekProfile_binsByWeekday() {
        val thu1970 = 43_200_000L          // 1970-01-01 12:00 UTC — خميس
        val sun2024 = 1_704_628_800_000L   // 2024-01-07 12:00 UTC — أحد
        val mon2024 = 1_704_715_200_000L   // 2024-01-08 12:00 UTC — اثنين
        val profile = SeriesMath.dayOfWeekProfile(listOf(thu1970, sun2024, mon2024, sun2024))
        assertEquals(7, profile.size)
        // الفهرس 0 = الأحد … 6 = السبت (Calendar.DAY_OF_WEEK − 1، والأحد = 1)
        assertEquals(2, profile[0]) // أحد ×2
        assertEquals(1, profile[1]) // اثنين
        assertEquals(0, profile[2])
        assertEquals(0, profile[3])
        // Calendar.THURSDAY = 5 → الفهرس 4
        assertEquals(1, profile[4]) // خميس
        assertEquals(0, profile[5]) // جمعة
        assertEquals(0, profile[6])
        assertEquals(4, profile.sum())
    }

    @Test
    fun dayOfWeekProfile_empty() {
        assertArrayEquals(IntArray(7), SeriesMath.dayOfWeekProfile(emptyList()))
    }

    // ───────── firstDigit ─────────

    @Test
    fun firstDigit_significantDigits() {
        assertEquals(0, SeriesMath.firstDigit(0.0))
        assertEquals(4, SeriesMath.firstDigit(-42.0))
        assertEquals(5, SeriesMath.firstDigit(0.05))
        assertEquals(9, SeriesMath.firstDigit(9.99))
        assertEquals(1, SeriesMath.firstDigit(1000.0))
        assertEquals(7, SeriesMath.firstDigit(-0.007))
        assertEquals(3, SeriesMath.firstDigit(3.14))
        assertEquals(1, SeriesMath.firstDigit(1.0))
    }

    @Test
    fun firstDigit_nonFinite() {
        assertEquals(0, SeriesMath.firstDigit(Double.NaN))
        assertEquals(0, SeriesMath.firstDigit(Double.POSITIVE_INFINITY))
        assertEquals(0, SeriesMath.firstDigit(Double.NEGATIVE_INFINITY))
    }

    // ───────── benfordCounts ─────────

    @Test
    fun benfordCounts_binsAndSkipsInvalid() {
        assertArrayEquals(
            intArrayOf(9, 0, 0, 0, 0, 0, 0, 0, 1),
            SeriesMath.benfordCounts(listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9.0)),
        )
        // ≤ 0 الصفر و NaN تُتخطى (firstDigit يعيد 0)، والسالب يُحسب بقيمته المطلقة
        // (التعريف: «أول رقم دال لـ |amount| مع تجاهل الإشارة» → −5.0 = الرقم 5)
        assertArrayEquals(
            intArrayOf(1, 1, 0, 0, 1, 0, 0, 0, 0),
            SeriesMath.benfordCounts(listOf(1.0, -5.0, 0.0, Double.NaN, 22.0)),
        )
        assertArrayEquals(IntArray(9), SeriesMath.benfordCounts(emptyList()))
    }

    // ───────── benfordDeviation ─────────

    @Test
    fun benfordDeviation_skewedAndUniform() {
        // توزيع منحرف بشدة: 1 تسع مرات و9 مرة واحدة
        val skewed = listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9.0)
        assertEquals(0.14515833639452083, SeriesMath.benfordDeviation(skewed), 1e-9)
        // [1..9] كل رقم مرة واحدة — انحراف أقل بكثير
        val uniform = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)
        assertEquals(0.05971703510991756, SeriesMath.benfordDeviation(uniform), 1e-9)
    }

    @Test
    fun benfordDeviation_emptyAndAllInvalid() {
        assertEquals(0.0, SeriesMath.benfordDeviation(emptyList()), 1e-9)
        // الصفر (وحتى −0.0) وNaN فقط تعيد firstDigit لديها 0 → كلها تُتخطى
        assertEquals(0.0, SeriesMath.benfordDeviation(listOf(0.0, -0.0, Double.NaN)), 1e-9)
    }

    // ───────── numberingGaps ─────────

    @Test
    fun numberingGaps_positivesOnly() {
        assertEquals(listOf(3, 5, 6), SeriesMath.numberingGaps(listOf(1, 2, 4, 7)))
        assertEquals(emptyList<Int>(), SeriesMath.numberingGaps(listOf(3, 1, 2)))
        assertEquals(emptyList<Int>(), SeriesMath.numberingGaps(listOf(5)))
        assertEquals(emptyList<Int>(), SeriesMath.numberingGaps(emptyList()))
        // الصفر والسالب يُتجاهلان: الموجب 4..6 → الفجوة 5 فقط
        assertEquals(listOf(5), SeriesMath.numberingGaps(listOf(-2, 0, 4, 6)))
        assertEquals(emptyList<Int>(), SeriesMath.numberingGaps(listOf(10, 12, 11)))
    }

    // ───────── paretoCount ─────────

    @Test
    fun paretoCount_largestFirst() {
        assertEquals(1, SeriesMath.paretoCount(listOf(90.0, 9.0, 1.0)))
        assertEquals(2, SeriesMath.paretoCount(listOf(50.0, 30.0, 20.0)))
        // متساوي تمامًا → كلها
        assertEquals(4, SeriesMath.paretoCount(listOf(25.0, 25.0, 25.0, 25.0)))
        // حد >= بحدود 80/20
        assertEquals(1, SeriesMath.paretoCount(listOf(80.0, 20.0)))
        // share = 1.0 → كل القيم
        assertEquals(3, SeriesMath.paretoCount(listOf(50.0, 30.0, 20.0), 1.0))
    }

    @Test
    fun paretoCount_degenerate() {
        assertEquals(0, SeriesMath.paretoCount(emptyList()))
        assertEquals(0, SeriesMath.paretoCount(listOf(-1.0, -2.0)))
        assertEquals(0, SeriesMath.paretoCount(listOf(0.0, 0.0)))
        assertEquals(0, SeriesMath.paretoCount(listOf(50.0, 30.0, 20.0), 0.0))
    }
}
