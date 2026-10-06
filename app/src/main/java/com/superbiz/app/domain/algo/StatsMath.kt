package com.superbiz.app.domain.algo

import com.superbiz.app.domain.analytics.linearRegression
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * — StatsMath: خوارزميات إحصائية نقية لتحليلات المتجر.
 *
 * كل الدوال نقية بلا حالة ومغطاة في StatsMathTest.
 * تُستخدم في: كشف القيم المتطرفة، تركز العملاء (Gini)، بنوك الرسم البياني،
 * اتجاه السلسلة الزمنية، ومقارنات النمو (CAGR).
*/

// ───────── مواقع التوزيع ─────────

/** الوسيط — قائمة فارغة تعيد 0.0 (بلا بيانات = بلا إشارة) */
fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/**
 * المئين p (0..100) باستقراء خطي على نسخة مرتبة —
 * percentile([10,20,30,40], 50) = 25.0. قائمة فارغة → 0.0.
 */
fun percentile(values: List<Double>, p: Double): Double {
    require(p in 0.0..100.0) { "p in 0..100" }
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    if (s.size == 1) return s[0]
    val idx = p / 100.0 * (s.size - 1)
    val lo = idx.toInt()
    val hi = minOf(lo + 1, s.size - 1)
    val frac = idx - lo
    return s[lo] + (s[hi] - s[lo]) * frac
}

/**
 * القيم المتطرفة بمعيار المدى الربيعي IQR:
 * حد سفلي = Q1 - 1.5×IQR وحد أعلى = Q3 + 1.5×IQR — يعيد فهارس المخالفة.
 */
fun iqrOutliers(values: List<Double>): List<Int> {
    if (values.size < 4) return emptyList()
    val q1 = percentile(values, 25.0)
    val q3 = percentile(values, 75.0)
    val iqr = q3 - q1
    if (iqr <= 1e-12) return emptyList()
    val lo = q1 - 1.5 * iqr
    val hi = q3 + 1.5 * iqr
    return values.withIndex().filter { it.value < lo || it.value > hi }.map { it.index }
}

/** الانحراف المطلق الوسيطي MAD — صمام أمان كشف الشذوذ الوسيطي */
fun mad(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val med = median(values)
    return median(values.map { abs(it - med) })
}

/**
 * شذوذ وسطي قوي بـ z المعدَّل: 0.6745×(x−median)/MAD.
 * MAD = 0 (توزيع مسطح) → قائمة فارغة (لا حكم بلا مقياس).
 */
fun madOutliers(values: List<Double>, threshold: Double = 3.5): List<Int> {
    if (values.size < 4) return emptyList()
    val med = median(values)
    val m = mad(values)
    if (m <= 1e-12) return emptyList()
    return values.withIndex()
        .filter { abs(0.6745 * (it.value - med) / m) >= threshold }
        .map { it.index }
}

// ───────── علاقات وتشتت ─────────

/**
 * معامل بيرسون الارتباطي (−1..+1) — «هل المصروفات تلاحق المبيعات؟».
 * تباين صفري بأحد الطرفين → 0.0 (لا علاقة قابلة للقياس).
 */
fun pearson(x: List<Double>, y: List<Double>): Double {
    require(x.size == y.size && x.size >= 2) { "same size >= 2" }
    val mx = x.average(); val my = y.average()
    var sxy = 0.0; var sxx = 0.0; var syy = 0.0
    for (i in x.indices) {
        sxy += (x[i] - mx) * (y[i] - my)
        sxx += (x[i] - mx) * (x[i] - mx)
        syy += (y[i] - my) * (y[i] - my)
    }
    if (sxx <= 1e-12 || syy <= 1e-12) return 0.0
    return (sxy / sqrt(sxx * syy)).coerceIn(-1.0, 1.0)
}

/** معامل الاختلاف CV% = σ/μ × 100 — استقرار المبيعات اليومية (صغر = انتظام) */
fun coefficientOfVariation(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    if (abs(mean) <= 1e-12) return 0.0
    var sq = 0.0
    for (v in values) sq += (v - mean) * (v - mean)
    return round2(sqrt(sq / values.size) / abs(mean) * 100.0)
}

/** تطبيع min-max إلى 0..1 — سلسلة ثابتة → 0.5 محايد (لا تمييز) */
fun minMaxNormalize(values: List<Double>): List<Double> {
    if (values.isEmpty()) return emptyList()
    val mn = values.min(); val mx = values.max()
    val range = mx - mn
    if (range <= 1e-12) return List(values.size) { 0.5 }
    return values.map { (it - mn) / range }
}

// ───────── تجميع وتكثيف ─────────

/**
 * توزيع تكراري بـ binCount فئة على [min, max] — أساس الرسوم التوزيعية.
 * كل القيم متساوية → الفئة الوسطى تمسك الكل. يعيد counts فقط.
 */
fun histogram(values: List<Double>, binCount: Int): List<Int> {
    require(binCount >= 1) { "bins >= 1" }
    if (values.isEmpty()) return List(binCount) { 0 }
    val mn = values.min(); val mx = values.max()
    val out = IntArray(binCount)
    if (mx - mn <= 1e-12) {
        out[binCount / 2] = values.size
        return out.toList()
    }
    for (v in values) {
        var b = ((v - mn) / (mx - mn) * binCount).toInt()
        if (b >= binCount) b = binCount - 1 // القيمة القصوى تدخل آخر فئة
        out[b]++
    }
    return out.toList()
}

/**
 * معامل جيني للتركز: 0 = توزيع متساوٍ تماماً، 1 = تركّز كلي في عنصر واحد.
 * «أفضل عميل يستحوذ على كم من الإيراد؟» — مجموع <= 0 → 0.
 */
fun gini(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val total = values.sum()
    if (total <= 1e-12) return 0.0
    val s = values.sorted()
    var cum = 0.0
    var weighted = 0.0
    for (i in s.indices) {
        cum += s[i]
        weighted += (i + 1) * s[i]
    }
    // G = (2·Σ(i·x_i))/(n·Σx) − (n+1)/n على الترتيب الصاعد
    val n = s.size.toDouble()
    val g = (2.0 * weighted) / (n * total) - (n + 1.0) / n
    return g.coerceIn(0.0, 1.0)
}

/** حصة أفضل k عنصر من الإجمالي (0..1) — «أهم 3 أصناف = X% من المبيعات» */
fun topShare(values: List<Double>, k: Int): Double {
    require(k >= 0) { "k >= 0" }
    if (values.isEmpty() || k == 0) return 0.0
    val total = values.sum()
    if (total <= 1e-12) return 0.0
    val top = values.sortedDescending().take(k).sum()
    return (top / total).coerceIn(0.0, 1.0)
}

// ───────── اتجاه ونمو ─────────

/** مجموع متحرك بنافذة w — نوافذ ناقصة تُتجاهل (نظير movingAverage) */
fun movingSum(series: List<Double>, window: Int): List<Double> {
    require(window >= 1) { "window >= 1" }
    if (series.size < window) return emptyList()
    return (window - 1 until series.size).map { i ->
        var s = 0.0
        for (j in i - window + 1..i) s += series[j]
        s
    }
}

/**
 * اتجاه سلسلة زمنية مركّب من انحدار خطي (BizMath):
 * r² >= 0.2 يجعل الاتجاه حازماً (1 صاعد / -1 نازل) وإلا فهو مسطح 0.
 */
fun trendDirection(series: List<Double>, minR2: Double = 0.2): Int {
    if (series.size < 3) return 0
    val x = List(series.size) { it + 1.0 }
    val (slope, _, r2) = linearRegression(x, series)
    if (r2 < minR2 || abs(slope) <= 1e-12) return 0
    return if (slope > 0) 1 else -1
}

/** معدل النمو السنوي المركب CAGR كسورة (0.12 = 12% سنوياً) — begin <= 0 → 0 */
fun cagr(begin: Double, end: Double, periods: Int): Double {
    require(periods > 0) { "periods > 0" }
    if (begin <= 1e-12 || end <= 0) return 0.0
    return (end / begin).pow(1.0 / periods) - 1.0
}

/**
 * أوزان softmax بدرجة حرارة — توزيع توصيات/ترجيح خيارات:
 * حرارة صغيرة تُحدّ الفارق وأكبر قيمة تسيطر، كبيرة تُقارب الأوزان.
 * temperature > 0 إلزامي.
 */
fun softmaxWeights(values: List<Double>, temperature: Double = 1.0): List<Double> {
    require(temperature > 0) { "temperature > 0" }
    if (values.isEmpty()) return emptyList()
    val maxV = values.max()
    val exps = values.map { kotlin.math.exp((it - maxV) / temperature) }
    val sum = exps.sum()
    if (sum <= 1e-12) return List(values.size) { 1.0 / values.size }
    return exps.map { it / sum }
}
