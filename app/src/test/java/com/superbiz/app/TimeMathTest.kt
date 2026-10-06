package com.superbiz.app

import com.superbiz.app.domain.algo.TimeMath.addBusinessDays
import com.superbiz.app.domain.algo.TimeMath.businessDaysBetween
import com.superbiz.app.domain.algo.TimeMath.daysBetween
import com.superbiz.app.domain.algo.TimeMath.daysInMonth
import com.superbiz.app.domain.algo.TimeMath.endOfDay
import com.superbiz.app.domain.algo.TimeMath.escalationLevel
import com.superbiz.app.domain.algo.TimeMath.hourBucket
import com.superbiz.app.domain.algo.TimeMath.isSameLocalDay
import com.superbiz.app.domain.algo.TimeMath.monthBounds
import com.superbiz.app.domain.algo.TimeMath.monthProgress
import com.superbiz.app.domain.algo.TimeMath.nextMonthlyDue
import com.superbiz.app.domain.algo.TimeMath.peakBucket
import com.superbiz.app.domain.algo.TimeMath.startOfDay
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات TimeMath: تقويم حقيقي بأيام عمل سعودية (جمعة+سبت).
*/
class TimeMathTest {

    private fun at(year: Int, month1to12: Int, day: Int, hour: Int = 12, min: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month1to12 - 1, day, hour, min, 0)
        }.timeInMillis

    @Test
    fun `startOfDay strips time and isSameLocalDay groups`() {
        val d = at(2026, 2, 10, 15, 37)
        val c = Calendar.getInstance().apply { timeInMillis = startOfDay(d) }
        assertEquals(0, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(Calendar.MINUTE))
        assertEquals(0, c.get(Calendar.SECOND))
        assertTrue(isSameLocalDay(d, at(2026, 2, 10, 23, 59)))
        assertFalse(isSameLocalDay(d, at(2026, 2, 11, 0, 1)))
        assertEquals(startOfDay(d) + 86_399_999L, endOfDay(d))
    }

    @Test
    fun `daysInMonth handles leap year`() {
        assertEquals(29, daysInMonth(2024, 2))
        assertEquals(28, daysInMonth(2026, 2))
        assertEquals(31, daysInMonth(2026, 1))
        assertEquals(30, daysInMonth(2026, 4))
    }

    @Test
    fun `monthBounds covers exactly the month`() {
        val (s, e) = monthBounds(at(2026, 2, 17))
        val cs = Calendar.getInstance().apply { timeInMillis = s }
        val ce = Calendar.getInstance().apply { timeInMillis = e }
        assertEquals(1, cs.get(Calendar.DAY_OF_MONTH))
        assertEquals(28, ce.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, ce.get(Calendar.HOUR_OF_DAY))
        // بداية الشهر لا تدخل الشهر السابق ونهايته لا تدخل الشهر التالي
        assertEquals(1, Calendar.getInstance().apply {
            timeInMillis = e + 1
        }.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `monthProgress feeds goalPace correctly`() {
        val (day, total) = monthProgress(at(2026, 2, 20))
        assertEquals(20, day)
        assertEquals(28, total)
    }

    @Test
    fun `daysBetween is calendar-day based not 24h based`() {
        // عبر تعديل توقيت صيفي/فروق ساعات: 23:59 إلى اليوم التالي 00:01 = يوم واحد
        assertEquals(1, daysBetween(at(2026, 3, 8, 23, 59), at(2026, 3, 9, 0, 1)))
        assertEquals(-5, daysBetween(at(2026, 3, 10), at(2026, 3, 5)))
        assertEquals(0, daysBetween(at(2026, 3, 5, 8, 0), at(2026, 3, 5, 22, 0)))
    }

    @Test
    fun `addBusinessDays skips Friday and Saturday`() {
        // 2026-02-10 هو الثلاثاء — خمسة أيام عمل = الثلاثاء 2026-02-17
        // (أربعاء 1، خميس 2، ثم تخطي جمعة+سبت، أحد 3، اثنين 4، ثلاثاء 5)
        val res = Calendar.getInstance().apply { timeInMillis = addBusinessDays(at(2026, 2, 10), 5) }
        assertEquals(Calendar.TUESDAY, res.get(Calendar.DAY_OF_WEEK))
        assertEquals(17, res.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `addBusinessDays zero returns same day start`() {
        assertEquals(startOfDay(at(2026, 2, 10)), addBusinessDays(at(2026, 2, 10), 0))
    }

    @Test
    fun `businessDaysBetween excludes weekend counts workdays only`() {
        // من الأربعاء 2026-02-11 إلى الثلاثاء 2026-02-17:
        // خميس، [جمعة، سبت]، أحد، اثنين، ثلاثاء = 4 أيام عمل
        assertEquals(4, businessDaysBetween(at(2026, 2, 11), at(2026, 2, 17)))
        // السالب معكوس تماماً
        assertEquals(-4, businessDaysBetween(at(2026, 2, 17), at(2026, 2, 11)))
        assertEquals(0, businessDaysBetween(at(2026, 2, 11), at(2026, 2, 11)))
    }

    @Test
    fun `businessDaysBetween honours custom weekend`() {
        // عطلة أحد فقط: من الأربعاء إلى الأربعاء (7 أيام) = 6 أيام عمل
        assertEquals(6, businessDaysBetween(at(2026, 2, 11), at(2026, 2, 18), setOf(Calendar.SUNDAY)))
    }

    @Test
    fun `nextMonthlyDue clamps short months and skips forward`() {
        // النتيجة دائماً بداية يوم (00:00) — نبدأ من startOfDay للتوقع
        // استحقاق يوم 31: بعد 2026-01-15 → 2026-01-31؛ وبعد 2026-01-31 → 2026-02-28 (اقتصار)
        assertEquals(startOfDay(at(2026, 1, 31)), nextMonthlyDue(at(2026, 1, 15), 31))
        assertEquals(startOfDay(at(2026, 2, 28)), nextMonthlyDue(at(2026, 1, 31), 31))
        assertEquals(startOfDay(at(2026, 4, 30)), nextMonthlyDue(at(2026, 3, 31), 31))
        // نفس اليوم: يقدّم للشهر التالي (بعد الاستحقاق صرماً)
        assertEquals(startOfDay(at(2026, 3, 10)), nextMonthlyDue(at(2026, 2, 10, 23, 59), 10))
    }

    @Test
    fun `escalationLevel bands match collections policy`() {
        assertEquals(0, escalationLevel(-5))
        assertEquals(0, escalationLevel(0))
        assertEquals(1, escalationLevel(1))
        assertEquals(1, escalationLevel(15))
        assertEquals(2, escalationLevel(16))
        assertEquals(2, escalationLevel(45))
        assertEquals(3, escalationLevel(46))
        assertEquals(3, escalationLevel(90))
        assertEquals(4, escalationLevel(91))
        assertEquals(4, escalationLevel(365))
    }

    @Test
    fun `hourBucket and peakBucket detect rush hours`() {
        assertEquals(0, hourBucket(6)); assertEquals(0, hourBucket(11))
        assertEquals(1, hourBucket(12)); assertEquals(1, hourBucket(16))
        assertEquals(2, hourBucket(17)); assertEquals(2, hourBucket(21))
        assertEquals(3, hourBucket(23)); assertEquals(3, hourBucket(3))
        // ذروة مسائية: مبيعات 20 و21 أعلى من غيرها
        val data = (0..23).map { it to if (it in 19..21) 100.0 else 5.0 }
        assertEquals(2, peakBucket(data))
        assertEquals(-1, peakBucket(emptyList()))
        assertEquals(-1, peakBucket(listOf(9 to 0.0, 10 to 0.0)))
        // ساعة خارج النطاق تتجاهل ولا تُسقط
        assertEquals(0, peakBucket(listOf(25 to 99.0, 8 to 10.0)))
    }

    @Test
    fun `monthProgress differs across months`() {
        val (d1, t1) = monthProgress(at(2026, 1, 15))
        assertEquals(31, t1)
        val (_, t2) = monthProgress(at(2026, 4, 15))
        assertNotEquals(t1, t2)
        assertEquals(15, d1)
    }
}
