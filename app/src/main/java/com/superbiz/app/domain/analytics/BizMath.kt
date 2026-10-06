package com.superbiz.app.domain.analytics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * — مكتبة الخوارزميات التحليلية للأعمال (BizMath)
 *
 * دوال نقية بالكامل (بلا Android وبدون حالة) تخدم قرارات المتجر الحقيقية
 * تنبؤ، مخزون، تسعير، تحصيل، كشف الشذوذ، تقسيم العملاء، كشف التكرار.
 * كل دالة مغطاة باختبار وحدة مستقل — انظر AnalyticsTest.kt.
*/

// ───────────────────────── 1) إحصاء وصوف سلسلة زمنية ─────────────────────────

/** المتوسط المتحرك بنافذة w — متوسط كل نافذة كاملة؛ نوافذ ناقصة تُتجاهل */
fun movingAverage(series: List<Double>, window: Int): List<Double> {
    require(window >= 1) { "window >= 1" }
    if (series.size < window) return emptyList()
    return (window - 1 until series.size).map { i ->
        var s = 0.0
        for (j in i - window + 1..i) s += series[j]
        s / window
    }
}

/** الانحراف المعياري (المجتمع) — أساس كشف الشذوذ وحساب المخزون الأمان */
fun standardDeviation(series: List<Double>): Double {
    if (series.size < 2) return 0.0
    val mean = series.average()
    val varr = series.sumOf { (it - mean) * (it - mean) } / series.size
    return sqrt(varr)
}

/** درجات z لكل نقطة — |z| >= 2 تعتبر شذوذاً عملياً */
fun zScores(series: List<Double>): List<Double> {
    val sd = standardDeviation(series)
    if (sd <= 1e-12) return List(series.size) { 0.0 }
    val mean = series.average()
    return series.map { (it - mean) / sd }
}

/** كشف شذوذ نقطة اليوم الأخير مقابل السلسلة: يرجع z أو null إن لا يكفي وقت */
fun latestAnomalyZ(series: List<Double>): Double? =
    if (series.size < 3) null else zScores(series).lastOrNull()

/** التمويس الأسي EWMA — متوسط متحرك يعطي وزناً أكبر لآخر المشاهدات */
fun ewma(series: List<Double>, alpha: Double): List<Double> {
    require(alpha in 0.0..1.0) { "alpha in 0..1" }
    if (series.isEmpty()) return emptyList()
    val out = ArrayList<Double>(series.size)
    var prev = series[0]
    out.add(prev)
    for (i in 1 until series.size) {
        prev = alpha * series[i] + (1 - alpha) * prev
        out.add(prev)
    }
    return out
}

/** انحدار خطي بسيط — يرجع (ميل، تقاطع، معامل تحديد R²) */
fun linearRegression(x: List<Double>, y: List<Double>): Triple<Double, Double, Double> {
    require(x.size == y.size && x.size >= 2) { "same size >= 2" }
    val mx = x.average(); val my = y.average()
    var sxy = 0.0; var sxx = 0.0; var syy = 0.0
    for (i in x.indices) {
        sxy += (x[i] - mx) * (y[i] - my)
        sxx += (x[i] - mx) * (x[i] - mx)
        syy += (y[i] - my) * (y[i] - my)
    }
    val slope = if (abs(sxx) < 1e-12) 0.0 else sxy / sxx
    val intercept = my - slope * mx
    val r2 = if (abs(sxx) < 1e-12 || abs(syy) < 1e-12) 0.0
    else (sxy * sxy) / (sxx * syy)
    return Triple(slope, intercept, r2)
}

// ───────────────────────── 2) قرارات المخزون ─────────────────────────

/**
 * تصنيف ABC (باريتو): ترتيب الأصناف بالقيمة ثم شطر تراكمي
 * A حتى 80%، B حتى 95%، C الباقي — يرجع زوج (فهرس أصلي، صنف 'A'/'B'/'C')
 */
fun abcClassify(values: List<Double>, aThreshold: Double = 0.80, bThreshold: Double = 0.95): List<Char> {
    if (values.isEmpty()) return emptyList()
    val total = values.sum()
    if (total <= 1e-12) return List(values.size) { 'C' }
    val order = values.withIndex().sortedByDescending { it.value }
    val out = CharArray(values.size) { 'C' }
    var cum = 0.0
    for (iv in order) {
        cum += iv.value
        val share = cum / total
        out[iv.index] = when {
            share <= aThreshold + 1e-9 -> 'A'
            share <= bThreshold + 1e-9 -> 'B'
            else -> 'C'
        }
    }
    return out.toList()
}

/** نقطة إعادة الطلب = الطلب اليومي × زمن التوريد + مخزون الأمان */
fun reorderPoint(
    avgDailySales: Double, leadTimeDays: Double,
    demandStdDaily: Double, serviceZ: Double = 1.65 // 95%
): Double {
    require(leadTimeDays >= 0) { "leadTime >= 0" }
    val lt = max(0.0, leadTimeDays)
    val safety = safetyStock(demandStdDaily, lt, serviceZ)
    return max(0.0, max(0.0, avgDailySales) * lt + safety)
}

/** مخزون الأمان = z × σالطلب اليومي × √زمن التوريد */
fun safetyStock(demandStdDaily: Double, leadTimeDays: Double, serviceZ: Double = 1.65): Double {
    require(leadTimeDays >= 0) { "leadTime >= 0" }
    if (demandStdDaily <= 0 || serviceZ <= 0) return 0.0
    return serviceZ * demandStdDaily * sqrt(max(0.0, leadTimeDays))
}

/** كمية الطلب الاقتصادية EOQ (نموذج ويلسون) */
fun eoq(demandAnnual: Double, orderCost: Double, holdingCostPerUnit: Double): Double {
    if (demandAnnual <= 0 || orderCost <= 0 || holdingCostPerUnit <= 0) return 0.0
    return sqrt(2.0 * demandAnnual * orderCost / holdingCostPerUnit)
}

/** أيام التغطية المتبقية بمعدل البيع الحالي (سقف 999) */
fun daysOfCover(stockQty: Double, avgDailySales: Double): Int {
    if (avgDailySales <= 1e-9) return if (stockQty > 0) 999 else 0
    return (stockQty / avgDailySales).coerceIn(0.0, 999.0).roundToInt()
}

// ───────────────────────── 3) تحصيل وتوزيع المدفوعات ─────────────────────────

data class Allocation(val invoiceId: Long, val amount: Double)

/**
 * توزيع دفعة على الفواتير المفتوحة بالأقدم أولاً (FIFO):
 * المدخلات (المعرّف، المتبقي) بترتيب تاريخ الاستحقاق — يرجع التوزيع فقط للذي يغطيه المبلغ
 */
fun allocateFifo(openInvoices: List<Pair<Long, Double>>, payment: Double): List<Allocation> {
    require(payment >= 0) { "payment >= 0" }
    var left = payment
    val out = ArrayList<Allocation>()
    for ((id, open) in openInvoices) {
        if (left <= 0.004) break
        if (open <= 0.004) continue
        val take = minOf(open, left)
        out.add(Allocation(id, take))
        left -= take
    }
    return out
}

/**
 * درجة جدارة العميل 0..100 — من سلوك السداد الحقيقي:
 * تبدأ 100 وتُخصم: نسبة المتأخر من المستحق (حتى 45)، متوسط أيام التأخير (حتى 30)،
 * تركز الدين في فاتورة واحدة (حتى 15)، ونسبة التحصيل المنخفضة (حتى 10)
 */
fun creditScore(
    openTotal: Double, overdueTotal: Double, avgDelayDays: Double?,
    invoiceCount: Int, largestOpenShare: Double, paidRatio: Double
): Int {
    if (invoiceCount <= 0) return 70 // عميل جديد: حياد إيجابي متحفظ
    var score = 100.0
    if (openTotal > 0) score -= 45.0 * (overdueTotal / openTotal).coerceIn(0.0, 1.0)
    if (avgDelayDays != null && avgDelayDays > 0) score -= 30.0 * (avgDelayDays / 90.0).coerceIn(0.0, 1.0)
    score -= 15.0 * largestOpenShare.coerceIn(0.0, 1.0)
    if (paidRatio < 1.0) score -= 10.0 * (1.0 - paidRatio.coerceIn(0.0, 1.0))
    return score.roundToInt().coerceIn(0, 100)
}

// ───────────────────────── 4) تقارير وحصص وأهداف ─────────────────────────

/** أفضل K عناصر حسب القيمة — ترتيب تنازلي مستقر */
fun <T> topK(items: List<T>, valueOf: (T) -> Double, k: Int): List<T> {
    require(k >= 0) { "k >= 0" }
    return items.sortedWith(compareByDescending(valueOf)).take(k)
}

/**
 * حصص مئوية 0..100 مجموعها 100 بالضبط — تُصلح بواسطة أكبر البواقي
 * (تقريب المئات المتشعب يضيع كسوراً؛ هذه الدالة تعيد الفرق لأكبر كسور)
 */
fun percentageShares(values: List<Double>): List<Int> {
    if (values.isEmpty()) return emptyList()
    val total = values.sum()
    if (total <= 1e-12) return List(values.size) { 0 }
    val exact = values.map { it * 100.0 / total }
    val floors = exact.map { it.toInt() }
    var remaining = 100 - floors.sum()
    val order = exact.withIndex().sortedByDescending { it.value - kotlin.math.floor(it.value) }
    val out = floors.toMutableList()
    var i = 0
    while (remaining > 0 && order.isNotEmpty()) {
        val idx = order[i % order.size].index
        out[idx] += 1
        remaining--
        i++
    }
    return out
}

/**
 * المؤشر الموسمي الشهري — يرجع 12 معاملاً حول 1.0
 * (شهر أعلى من متوسطه التاريخي يعني موسمية مرتفعة)
 */
fun seasonalMonthlyIndex(monthlyTotals: List<Double>): List<Double> {
    require(monthlyTotals.size == 12) { "12 شهراً" }
    val mean = monthlyTotals.average()
    if (mean <= 1e-12) return List(12) { 1.0 }
    return monthlyTotals.map { it / mean }
}

/** إيقاع تحقيق الهدف: المطلوب يومياً لبقية الشهر مقابل الإنجاز حتى اليوم */
fun goalPace(
    achievedSoFar: Double, monthlyGoal: Double, dayOfMonth: Int, daysInMonth: Int
): Double {
    require(daysInMonth > 0 && dayOfMonth in 1..daysInMonth) { "أيام الشهر غير صالحة" }
    if (monthlyGoal <= 0) return 1.0
    val expected = monthlyGoal * dayOfMonth / daysInMonth
    if (expected <= 1e-9) return 1.0
    return achievedSoFar / expected // >1 أمام الجدول، <1 متأخر
}

// ───────────────────────── 5) كشف التكرار والتشابه ─────────────────────────

/** مسافة ليفنشتاين الكلاسيكية بمصفوفة أحادية البعد */
fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    val cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        System.arraycopy(cur, 0, prev, 0, cur.size)
    }
    return prev[b.length]
}

/** نسبة التشابه 0..1 (1 = تطابق تام) */
fun similarity(a: String, b: String): Double {
    val la = a.length; val lb = b.length
    if (la == 0 && lb == 0) return 1.0
    val d = levenshtein(a, b)
    return 1.0 - d.toDouble() / max(la, lb)
}

/** هل مرشحا الطرف/المنتج تكرار محتمل؟ (تشابه >= 0.87 بعد التطبيع) */
fun isProbableDuplicate(nameA: String, nameB: String, phoneA: String = "", phoneB: String = ""): Boolean {
    // تحويل الأرقام العربية-الهندية قبل المقارنة — كانت ٠٥٠ ≠ 050
    fun digits(s: String): String = buildString(s.length) {
        for (c in s) when {
            c in '٠'..'٩' -> append(c - '٠')
            c in '۰'..'۹' -> append(c - '۰')
            c.isDigit() -> append(c)
        }
    }
    val pa = digits(phoneA)
    val pb = digits(phoneB)
    if (pa.length >= 7 && pa == pb) return true
    return similarity(arabicNormalize(nameA), arabicNormalize(nameB)) >= 0.87
}

/** تطبيع نص عربي للبحث والمطابقة: همزات/تاء مربوطة/تشكيل + أحادي الجانب اللاتيني */
fun arabicNormalize(s: String): String = buildString(s.length) {
    for (c in s) {
        when (c) {
            'أ', 'إ', 'آ', 'ٱ' -> append('ا')
            'ة' -> append('ه')
            'ؤ' -> append('و')
            'ئ', 'ى' -> append('ي')
            in '\u064B'..'\u0652' -> {}          // تشكيل
            '\u0640' -> {}                        // تطويل
            else -> append(c.lowercaseChar())
        }
    }
}

// ───────────────────────── 6) أعمار وأرصدة ─────────────────────────

/** تقسيم المبالغ على أعمار دينية: (حالٍ، ‎31+‎، ‎61+‎، ‎91+‎ يوم) */
fun agingBuckets(pairs: List<Pair<Double, Long>>, todayMs: Long): List<Double> {
    val out = mutableListOf(0.0, 0.0, 0.0, 0.0)
    for ((amount, dueMs) in pairs) {
        // (M-2.5 توحيد): أيام التأخير بالتقريب لأعلى — أي تجاوز للاستحقاق حتى لو دقائق
        // يُعد يوماً واحداً (كان floor يجعل أول 24 ساعة تأخير = 0 يوماً فتتعارض سلات
        // الأعمار مع كشف «متأخر» الذي يطابق أي تجاوز)
        val days = kotlin.math.ceil((todayMs - dueMs) / 86_400_000.0).toInt()
        val idx = when {
            days <= 0 -> 0
            days <= 30 -> 0
            days <= 60 -> 1
            days <= 90 -> 2
            else -> 3
        }
        out[idx] += amount
    }
    // (M-2.2 توحيد): التقريب عبر المساعد القانوني
    return out.map { com.superbiz.app.util.Money.round2(it) }
}
