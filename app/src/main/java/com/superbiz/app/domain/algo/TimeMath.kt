package com.superbiz.app.domain.algo

import java.util.Calendar

/**
 * — TimeMath: خوارزميات توقيت وتقويم نقية للأعمال.
 *
 * مبنية على java.util.Calendar حصراً (minSdk 24 بلا desugaring — لا java.time).
 * كل الدوال نقية ومغطاة في TimeMathTest.
 *
 * عطلة نهاية الأسبوع الافتراضية للسعودية: الجمعة + السبت
 * (Calendar.DAY_OF_WEEK: الجمعة = 6، السبت = 7).
*/
object TimeMath {

    val KSA_WEEKEND: Set<Int> = setOf(Calendar.FRIDAY, Calendar.SATURDAY)
    private const val DAY_MS = 86_400_000L

    // ───────── حدود اليوم والشهر ─────────

    /** بداية اليوم المحلي (00:00.000) لتوقيت epoch المُمرَّر */
    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** نهاية اليوم المحلي (23:59.999) */
    fun endOfDay(ms: Long): Long = startOfDay(ms) + DAY_MS - 1

    /** هل التوقيتان في اليوم المحلي نفسه؟ (تجميع مبيعات اليوم) */
    fun isSameLocalDay(a: Long, b: Long): Boolean = startOfDay(a) == startOfDay(b)

    /** عدد أيام الشهر (month1to12 من 1..12) — يعالج الكبسة تلقائياً */
    fun daysInMonth(year: Int, month1to12: Int): Int {
        require(month1to12 in 1..12) { "month 1..12" }
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month1to12 - 1, 1)
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /** حدود الشهر المحلي للتوقيت المُمرَّر → (بداية، نهاية) بالميلي ثانية */
    fun monthBounds(ms: Long): Pair<Long, Long> {
        val c = Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return start to (c.timeInMillis - 1)
    }

    /** تقدم الشهر: (يوم الشهر الحالي، أيام الشهر) — غذاء مباشر لخوارزمية goalPace */
    fun monthProgress(ms: Long): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.DAY_OF_MONTH) to c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /** عدد الأيام بين توقيتين (فرق بدايتي اليوم) — سالب إذا b قبل a */
    fun daysBetween(fromMs: Long, toMs: Long): Int =
        ((startOfDay(toMs) - startOfDay(fromMs)) / DAY_MS.toDouble()).toInt()

    // ───────── أيام العمل ─────────

    /**
     * إضافة أيام عمل (تتخطى عطلة الأسبوع المُمرَّرة) من تاريخ.
     * n >= 0؛ يبدأ من اليوم التالي — موعد توريد «خلال 5 أيام عمل».
     */
    fun addBusinessDays(fromMs: Long, n: Int, weekend: Set<Int> = KSA_WEEKEND): Long {
        require(n >= 0) { "n >= 0" }
        val c = Calendar.getInstance().apply { timeInMillis = startOfDay(fromMs) }
        var left = n
        while (left > 0) {
            c.add(Calendar.DAY_OF_MONTH, 1)
            if (c.get(Calendar.DAY_OF_WEEK) !in weekend) left--
        }
        return c.timeInMillis
    }

    /**
     * عدد أيام العمل بين تاريخين: من اليوم التالي لـ from حتى to شاملاً.
     * to < from يعيد قيمة سالبة بالمقابل — مفيد لـ«متبقٍ X يوم عمل».
     */
    fun businessDaysBetween(fromMs: Long, toMs: Long, weekend: Set<Int> = KSA_WEEKEND): Int {
        if (fromMs == toMs) return 0
        val c = Calendar.getInstance()
        var count = 0
        val step = if (toMs > fromMs) 1 else -1
        var cur = startOfDay(fromMs)
        val end = startOfDay(toMs)
        // كان شرط الحلقة يتطلب الهبوط على منتصف الليل بالضبط، وفي
        // مناطق التوقيت الصيفي قد تنزلق add(+24h) عن شبكة منتصف الليل فلا ينتهي
        // الحلول أبداً (تجميد الواجهة). التقدم الآن عبر Calendar.add(DAY_OF_MONTH)
        // مثل addBusinessDays، ومقارنة بداية اليوم لا الطابع الخام.
        while (startOfDay(cur) != end) {
            c.timeInMillis = cur
            c.add(Calendar.DAY_OF_MONTH, step)
            cur = c.timeInMillis
            if (c.get(Calendar.DAY_OF_WEEK) !in weekend) count += step
        }
        return count
    }

    /**
     * الاستحقاق الشهري التالي: أول تاريخ بعد fromMs يكون يومه targetDay،
     * مع اقتصار اليوم على طول الشهر (31 → 28/30 عند الحاجة) — أقساط شهرية دقيقة.
     */
    fun nextMonthlyDue(fromMs: Long, targetDay: Int): Long {
        require(targetDay in 1..31) { "day 1..31" }
        val c = Calendar.getInstance().apply {
            timeInMillis = startOfDay(fromMs)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val fromDay = c.get(Calendar.DAY_OF_MONTH)
        var monthAhead = if (targetDay > fromDay) 0 else 1
        while (true) {
            val probe = c.clone() as Calendar
            probe.add(Calendar.MONTH, monthAhead)
            val maxDay = probe.getActualMaximum(Calendar.DAY_OF_MONTH)
            probe.set(Calendar.DAY_OF_MONTH, minOf(targetDay, maxDay))
            if (probe.timeInMillis > fromMs) return probe.timeInMillis
            monthAhead++
        }
    }

    // ───────── تصعيد التحصيل ─────────

    /**
     * مستوى تصعيد التحصيل من أيام التأخير (0..4):
     * 0 نظيف · 1 تذكير ودّي (1-15) · 2 متابعة (16-45) · 3 إنذار (46-90) · 4 تحصيل عاجل (>90).
     */
    fun escalationLevel(daysOverdue: Int): Int = when {
        daysOverdue <= 0 -> 0
        daysOverdue <= 15 -> 1
        daysOverdue <= 45 -> 2
        daysOverdue <= 90 -> 3
        else -> 4
    }

    // ───────── ساعات الذروة ─────────

    /**
     * شريحة الوقت البيعية من الساعة (0..3):
     * 0 صباح (6-11) · 1 عصر (12-16) · 2 مساء (17-21) · 3 ليل.
     * تُستخدم لكشف «وقت الذروة» من ساعات الفواتير الحقيقية.
     */
    fun hourBucket(hourOfDay: Int): Int = when (hourOfDay) {
        in 6..11 -> 0
        in 12..16 -> 1
        in 17..21 -> 2
        else -> 3
    }

    /**
     * وقت الذروة: من أزواج (ساعة، مبلغ) يرجع فهرس الشريحة الأعلى إيراداً.
     * بلا بيانات كافية (كل المبالغ صفر أو القائمة فارغة) يعيد -1.
     */
    fun peakBucket(hourAmounts: List<Pair<Int, Double>>): Int {
        if (hourAmounts.isEmpty()) return -1
        val sums = DoubleArray(4)
        for ((h, v) in hourAmounts) {
            if (h !in 0..23) continue
            sums[hourBucket(h)] += v
        }
        val maxV = sums.max()
        if (maxV <= 1e-9) return -1
        for (i in sums.indices) if (sums[i] == maxV) return i
        return -1
    }
}
