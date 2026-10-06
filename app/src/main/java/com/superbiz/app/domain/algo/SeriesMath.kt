package com.superbiz.app.domain.algo

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.log10

/**
 * — SeriesMath: خوارزميات سلاسل زمنية وتحليل بيانات نقية.
 *
 * كل الدوال نقية وبلا حالة — قابلة للاختبار على JVM، ومغطاة بالكامل في SeriesMathTest.
 * التوقيت عبر java.util.Calendar حصراً (minSdk 24 بلا desugaring — لا java.time).
 * الغرض: توقّع المبيعات (EMA/WMA/Holt)، سلاسل الالتزام اليومية، كشف التلاعب بفواتير
 * نجم بنفورد، فجوات الترقيم، ومبدأ باريتو.
*/
object SeriesMath {

    // ───────── 1) تنعيم وتوقّع السلاسل ─────────

    /**
     * المتوسط المتحرك الأسي: يبدأ بأول قيمة، ثم ema[i] = α×x + (1−α)×السابق.
     * سلسلة فارغة → قائمة فارغة. α مقيَّد ضمن (0, 1] (α=1 يعيد نسخة من السلسلة).
     */
    fun ema(series: List<Double>, alpha: Double = 0.3): List<Double> {
        if (series.isEmpty()) return emptyList()
        val a = alpha.coerceIn(1e-12, 1.0)
        val out = ArrayList<Double>(series.size)
        var prev = series[0]
        out.add(prev)
        for (i in 1 until series.size) {
            prev = a * series[i] + (1.0 - a) * prev
            out.add(prev)
        }
        return out
    }

    /**
     * المتوسط المتحرك المرجّح خطياً بنفس طول السلسلة: عند الفهرس i تُستخدم آخر
     * min(window, i+1) نقطة بأوزان خطية (الأحدث وزنها n، وما قبلها n−1، … الأقدم 1).
     * window ≤ 1 → نسخة من السلسلة.
     */
    fun wma(series: List<Double>, window: Int): List<Double> {
        if (window <= 1) return series.toList()
        val out = ArrayList<Double>(series.size)
        for (i in series.indices) {
            val n = minOf(window, i + 1)
            var weighted = 0.0
            for (k in 0 until n) {
                weighted += series[i - k] * (n - k)
            }
            out.add(weighted / (n * (n + 1) / 2.0))
        }
        return out
    }

    /**
     * تنعيم Holt المزدوج (مستوى + اتجاه) ثم توقّع horizon خطوة: level + h×trend.
     * البدء: level = s[0]، trend = s[1] − s[0] (تتطلب نقطتين على الأقل،
     * وإلا تكرار آخر قيمة horizon مرة — أو 0.0 للسلسلة الفارغة). horizon ≤ 0 → قائمة فارغة.
     */
    fun holtForecast(
        series: List<Double>,
        alpha: Double = 0.5,
        beta: Double = 0.3,
        horizon: Int = 1,
    ): List<Double> {
        if (horizon <= 0) return emptyList()
        if (series.size < 2) return List(horizon) { series.lastOrNull() ?: 0.0 }
        var level = series[0]
        var trend = series[1] - series[0]
        for (i in 1 until series.size) {
            val previousLevel = level
            level = alpha * series[i] + (1.0 - alpha) * (level + trend)
            trend = beta * (level - previousLevel) + (1.0 - beta) * trend
        }
        return (1..horizon).map { level + it * trend }
    }

    // ───────── 2) الالتزام اليومي والتقويم ─────────

    /**
     * سلسلة الأيام المتتالية (streak) العدّ تنازلياً من todayEpochDay:
     * إن غاب اليوم لكن الحاضر أمسه نبدأ من أمس (سماحية يوم واحد)،
     * وأي انقطاع يوقف العد. فارغ/منفصل → 0.
     */
    fun streakDays(activeEpochDays: Set<Int>, todayEpochDay: Int): Int {
        if (activeEpochDays.isEmpty()) return 0
        var day = todayEpochDay
        if (day !in activeEpochDays) {
            day -= 1
            if (day !in activeEpochDays) return 0
        }
        var streak = 0
        while (day in activeEpochDays) {
            streak++
            day--
        }
        return streak
    }

    /**
     * توزيع طوابع زمنية على أيام الأسبوع: IntArray(7) بفهرس 0=الأحد … 6=السبت
     * (Calendar.DAY_OF_WEEK − 1). عبر java.util.Calendar — بلا java.time (minSdk 24).
     */
    fun dayOfWeekProfile(epochMillis: List<Long>): IntArray {
        val profile = IntArray(7)
        if (epochMillis.isEmpty()) return profile
        val calendar = Calendar.getInstance()
        for (millis in epochMillis) {
            calendar.timeInMillis = millis
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) // 1=الأحد … 7=السبت
            profile[dayOfWeek - 1]++
        }
        return profile
    }

    // ───────── 3) قانون نجم بنفورد (كشف التلاعب بالمبالغ) ─────────

    /**
     * أول رقم دال (1..9) للقيمة المطلقة مع تجاهل الأصفار البادئة والإشارة.
     * amount ≤ 0 أو NaN أو لانهائي → 0.
     */
    fun firstDigit(amount: Double): Int {
        if (amount.isNaN() || amount.isInfinite()) return 0
        var d = abs(amount)
        if (d <= 0.0) return 0
        while (d < 1.0) d *= 10.0
        while (d >= 10.0) d /= 10.0
        return d.toInt()
    }

    /**
     * عدّ المبالغ حسب أول رقم دال: IntArray(9) حيث الفهرس d−1 = عدد المبالغ
     * التي أول رقم لها d. المبالغ غير المنتهية أو ≤ 0 تُتخطى تلقائياً (firstDigit يعيد 0).
     */
    fun benfordCounts(amounts: List<Double>): IntArray {
        val counts = IntArray(9)
        for (amount in amounts) {
            val digit = firstDigit(amount)
            if (digit in 1..9) counts[digit - 1]++
        }
        return counts
    }

    /**
     * متوسط الانحراف المطلق لتكرارات الأرقام الأولى عن توزيع بنفورد log10(1 + 1/d).
     * فارغة أو لا مبالغ صالحة → 0.0.
     * مثال: [1×9 ثم 9] → ≈ 0.145158 (انحراف واضح عن القانون).
     */
    fun benfordDeviation(amounts: List<Double>): Double {
        val counts = benfordCounts(amounts)
        val total = counts.sum()
        if (total == 0) return 0.0
        var sumAbsDeviation = 0.0
        for (digit in 1..9) {
            val observed = counts[digit - 1].toDouble() / total
            val expected = log10(1.0 + 1.0 / digit)
            sumAbsDeviation += abs(observed - expected)
        }
        return sumAbsDeviation / 9.0
    }

    // ───────── 4) سلامة الترقيم ومبدأ باريتو ─────────

    /**
     * الأرقام المفقودة في المدى [min, max] للقيم الموجبة فقط (فحص فجوات ترقيم الفواتير).
     * لا قيم موجبة → قائمة فارغة.
     */
    fun numberingGaps(present: List<Int>): List<Int> {
        val positives = present.filter { it > 0 }
        if (positives.isEmpty()) return emptyList()
        val seen = positives.toHashSet()
        val min = positives.min()
        val max = positives.max()
        // ترقيم مختلط الأنماط (مثل تاريخ كامل داخل الرقم) كان يفتح
        // مدى بملايين الخانات يُخصص عشرات الملايين على الخيط الرئيسي (ANR/OOM) —
        // المدى الأوسع من 100 ألف يُرفض حمايةً موثقة
        if (max.toLong() - min.toLong() > 100_000L) return emptyList()
        val gaps = ArrayList<Int>()
        for (n in min..max) {
            if (n !in seen) gaps.add(n)
        }
        return gaps
    }

    /**
     * أقل عدد من أكبر القيم مجموعها التراكمي ≥ share × الإجمالي (مبدأ باريتو/تركّز الإيراد).
     * قائمة فارغة أو الإجمالي ≤ 0 أو share ≤ 0 → 0.
     */
    fun paretoCount(values: List<Double>, share: Double = 0.8): Int {
        if (values.isEmpty() || share <= 0.0) return 0
        val total = values.sum()
        if (total <= 0.0) return 0
        val target = share * total
        var cumulative = 0.0
        val sorted = values.sortedDescending()
        for ((index, value) in sorted.withIndex()) {
            cumulative += value
            if (cumulative >= target) return index + 1
        }
        return sorted.size
    }
}
