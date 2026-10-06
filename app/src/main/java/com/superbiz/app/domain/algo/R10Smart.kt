package com.superbiz.app.domain.algo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * — 20 خوارزمية ذكية جديدة عبر 8 كائنات نقيّة حتمية.
 *
 * العقود العامة (نفس انضباط R7Smart/R9Smart)
 * - دوال نقية: بلا وصول لقاعدة البيانات أو الشبكة أو الوقت الحقيقي.
 * - حتمية: نفس المدخل ⇒ نفس المخرج دائماً.
 * - بلا بيانات ⇒ null/قائمة فارغة، ولا تُخترع أرقام.
 * - كل خوارزمية موثقة بعقد صريح في docs/ALGORITHMS.md.
 *
 * قائمة الـ20
 * A1 PricingMath.zScoreOutliers كشف الشواذ بمتوسط منيع (وسيط+MAD)
 * A2 PricingMath.markdownLadder سلّم تخفيض زمني للمخزون المتقادم بحد أدنى التكلفة
 * A3 SeasonMath.weekdayProfile مؤشر أيام الأسبوع 0..100
 * A4 SeasonMath.hotCells أفضل خلايا (يوم×ساعة) من مصفوفة 7×24
 * A5 MarginMath.portfolioMargin هامش المحفظة المرجّح + تركّز HHI
 * A6 MarginMath.paretoABC تصنيف ABC (80/15/5 تراكمي)
 * A7 MarginMath.capitalEfficiency ربحية كل 100 وحدة من رأس المال المجمّد
 * A8 FlowMath.agingBuckets أعمار الذمم 0-30/31-60/61-90/90+
 * A9 FlowMath.collectionForecast توقع التحصيل خلال أفق زمني باحتمالات الشرائح
 * A10 FlowMath.dsoTrend اتجاه فترة التحصيل DSO أسبوعياً
 * A11 BasketMath.marketBasketLift الرفع/الدعم/الثقة لأزواج المنتجات
 * A12 BasketMath.nextProduct «المنتج التالي» ماركوف من الرتبة الأولى
 * A13 RiskMath.healthScore درجة صحة 0..100 من 4 مكونات موزونة
 * A14 RiskMath.customerConcentration تركّز الإيراد على أكبر العملاء
 * A15 RiskMath.maturityLadder سلّم استحقاق الشيكات أسبوعياً
 * A16 GoalMath.requiredPace الوتيرة المطلوبة لإنقاذ هدف + حكم الجدوى
 * A17 GoalMath.milestoneProjection إسقاط تواريخ المحطات 25/50/75/100%
 * A18 QualityMath.dupScore درجة تشابه 0..100 (JW + تداخل الرموز)
 * A19 QualityMath.outliersIQR أسوار IQR الخفيفة/الثقيلة
 * A20 QualityMath.roundingSweep جرد نقدية جشع موثق + فروق التقريب
*/

// ═══════════════ 1) PricingMath — تسعير: شواذ + تخفيض زمني ═══════════════

object PricingMath {

    /**
     * A1 — كشف القيم الشاذة بمقياس z منيع: z = 0.6745·(x − الوسيط)/MAD.
     * عقد: MAD=0 (توزيع منحل أو قيم متطابقة) ⇒ قائمة فارغة (لا انفجار قسمة).
     * threshold الافتراضي 3.5 (عتبة Iglewicz–Hoaglin المعتمدة).
     */
    data class Anomaly(val index: Int, val value: Double, val z: Double)

    fun zScoreOutliers(values: List<Double>, threshold: Double = 3.5): List<Anomaly> {
        if (values.size < 4) return emptyList()
        val med = median(values)
        val mad = mad(values)   // ملاحظة: mad() الحالية تحسب الانحراف عن الوسيط داخلياً — تمرير القيم الخام فقط
        if (mad <= 0.0) return emptyList()
        return values.mapIndexedNotNull { i, v ->
            val z = 0.6745 * (v - med) / mad
            if (abs(z) > threshold) Anomaly(i, v, round(z * 100) / 100) else null
        }
    }

    /**
     * A2 — سلّم تخفيض زمني: حسب عمر الدفعة (أيام) نُطبق أول شريحة يحققها العمر.
     * عقد: الناتج لا ينزل تحت التكلفة عندما floorAtCost=true؛ خطوات مرتبة تصاعدياً بالعمر.
     */
    data class MarkdownStep(val ageDaysMin: Int, val markdownPct: Double)

    val defaultLadder = listOf(
        MarkdownStep(60, 5.0),
        MarkdownStep(90, 10.0),
        MarkdownStep(120, 20.0),
        MarkdownStep(180, 30.0),
    )

    fun markdownLadder(
        currentPrice: Double,
        unitCost: Double,
        ageDays: Int,
        steps: List<MarkdownStep> = defaultLadder,
        floorAtCost: Boolean = true,
    ): Double {
        if (currentPrice <= 0.0) return currentPrice
        val md = steps.filter { ageDays >= it.ageDaysMin }.maxOfOrNull { it.markdownPct } ?: 0.0
        var price = currentPrice * (1.0 - md / 100.0)
        if (floorAtCost) price = max(price, unitCost)
        return round2(price)
    }
}

// ═══════════════ 2) SeasonMath — إيقاع المبيعات الزمني ═══════════════

object SeasonMath {

    /**
     * A3 — مؤشر أيام الأسبوع: index = متوسط اليوم ÷ المتوسط الكلي × 100.
     * عقد: بلا نقاط ⇒ فارغ؛ الأسبوع يُرمز 1..7 (الاثنين=1) كما يعيد Calendar.DAY_OF_WEEK مُحوّلاً.
     */
    data class DayProfile(val weekday: Int, val avg: Double, val indexPct: Int)

    fun weekdayProfile(points: List<Pair<Int, Double>>): List<DayProfile> {
        if (points.isEmpty()) return emptyList()
        val overall = points.map { it.second }.average()
        if (overall <= 0.0) return emptyList()
        return (1..7).mapNotNull { d ->
            val vals = points.filter { it.first == d }.map { it.second }
            if (vals.isEmpty()) null
            else DayProfile(d, round2(vals.average()), round((vals.average() / overall * 100)).toInt())
        }.sortedByDescending { it.indexPct }
    }

    /**
     * A4 — أفضل خلايا (يوم×ساعة) من مصفوفة 7×24 (صف=اليوم 0..6، عمود=الساعة 0..23).
     * عقد: المصفوفة null أو فارغة أو قيمها كلها ≤0 ⇒ فارغ؛ الناتج أعلى topK بحصة٪ من الأعلى.
     */
    data class HotCell(val day: Int, val hour: Int, val value: Double, val shareOfMaxPct: Int)

    fun hotCells(matrix: Array<DoubleArray>?, topK: Int = 3): List<HotCell> {
        if (matrix == null || matrix.isEmpty()) return emptyList()
        val cells = ArrayList<HotCell>()
        var maxV = 0.0
        matrix.forEachIndexed { d, row ->
            row.forEachIndexed { h, v ->
                if (v > 0.0) {
                    cells += HotCell(d, h, v, 0)
                    if (v > maxV) maxV = v
                }
            }
        }
        if (maxV <= 0.0) return emptyList()
        return cells.map { it.copy(shareOfMaxPct = round(it.value / maxV * 100).toInt()) }
            .sortedWith(compareByDescending<HotCell> { it.value }.thenBy { it.day }.thenBy { it.hour })
            .take(max(0, topK))
    }
}

// ═══════════════ 3) MarginMath — هامش المحفظة وتركيز الربح ═══════════════

object MarginMath {

    /**
     * A5 — هامش المحفظة المرجّح + تركّز HHI (0..10000) على حصص الإيراد.
     * عقد: مجموع إيراد ≤0 ⇒ null. الحكم: HHI≥2500 HIGH، ≥1500 MED، وإلا LOW.
     */
    data class Portfolio(val marginPct: Double, val hhi: Double, val verdict: String)

    fun portfolioMargin(revenueByItem: Map<String, Double>, costByItem: Map<String, Double>): Portfolio? {
        val totalRev = revenueByItem.values.sum()
        if (totalRev <= 0.0) return null
        val totalCost = revenueByItem.keys.sumOf { k -> costByItem[k] ?: 0.0 }
        val marginPct = (totalRev - totalCost) / totalRev * 100.0
        // HHI على حصص الإيراد المئوية: Σ (حصة٪)²
        val hhi = revenueByItem.values.sumOf { r -> val s = r / totalRev * 100.0; s * s }
        val verdict = when {
            hhi >= 2500.0 -> "HIGH"
            hhi >= 1500.0 -> "MED"
            else -> "LOW"
        }
        return Portfolio(round2(marginPct), round2(hhi), verdict)
    }

    /**
     * A6 — تصنيف ABC تراكمي: A حتى 80%، B حتى 95%، C الباقي.
     * عقد: خريطة فارغة/مجموع ≤0 ⇒ فارغ؛ الترتيب تنازلي بالإيراد ثم بالاسم لضمان الحتمية.
     */
    data class AbcRow(val name: String, val revenue: Double, val cumSharePct: Double, val klass: Char)

    fun paretoABC(revenueByName: Map<String, Double>): List<AbcRow> {
        val total = revenueByName.values.sum()
        if (total <= 0.0) return emptyList()
        var cum = 0.0
        return revenueByName.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .map { (name, rev) ->
                cum += rev
                val share = cum / total * 100.0
                val klass = when {
                    share <= 80.0 -> 'A'
                    share <= 95.0 -> 'B'
                    else -> 'C'
                }
                AbcRow(name, round2(rev), round2(share), klass)
            }
    }

    /**
     * A7 — كفاءة رأس المال: ربح لكل 100 وحدة من قيمة المخزون المجمّد، تنازلياً.
     * عقد: رأس مال ≤0 يُستبعد؛ بلا أزواج صالحة ⇒ فارغ.
     */
    data class EfficiencyRow(val name: String, val profitPerHundred: Double)

    fun capitalEfficiency(
        profitByName: Map<String, Double>,
        stockCapitalByName: Map<String, Double>,
    ): List<EfficiencyRow> =
        profitByName.mapNotNull { (name, profit) ->
            val cap = stockCapitalByName[name] ?: 0.0
            if (cap <= 0.0) null
            else EfficiencyRow(name, round2(profit / cap * 100.0))
        }.sortedByDescending { it.profitPerHundred }
}

// ═══════════════ 4) FlowMath — ذمم وتحصيل ═══════════════

object FlowMath {

    private fun bucketIndex(daysOverdue: Long): Int = when {
        daysOverdue <= 30 -> 0
        daysOverdue <= 60 -> 1
        daysOverdue <= 90 -> 2
        else -> 3
    }

    /**
     * A8 — أعمار الذمم: شرائح 0-30/31-60/61-90/90+ يوماً من تاريخ الاستحقاق.
     * عقد: فاتورة غير مستحقة بعد (due>today) تُحسب في شريحة 0-30 بعمر سالب مستبعد من العد؟
     * لا — تُحسب في الشريحة 0 (غير متأخرة)؛ المبالغ تُقرَّب لخانتين.
     */
    data class Bucket(val label: String, val amount: Double, val count: Int)

    fun agingBuckets(openInvoices: List<Pair<Long, Double>>, todayEpochDay: Long): List<Bucket> {
        if (openInvoices.isEmpty()) return emptyList()
        val labels = listOf("0-30", "31-60", "61-90", "90+")
        val amounts = DoubleArray(4)
        val counts = IntArray(4)
        for ((due, open) in openInvoices) {
            if (open <= 0.0) continue
            val overdue = todayEpochDay - due          // >0 متأخرة، ≤0 غير مستحقة
            val idx = if (overdue < 0) 0 else bucketIndex(overdue)
            amounts[idx] += open
            counts[idx] += 1
        }
        return labels.mapIndexed { i, l -> Bucket(l, round2(amounts[i]), counts[i]) }
            .filter { it.count > 0 }
    }

    /**
     * A9 — توقع التحصيل: مجموع المفتوح المستحق خلال الأفق × احتمال شريحته.
     * عقد: احتمالات الشرائح الافتراضية [0.95,0.80,0.60,0.40] لـ 0-30/31-60/61-90/90+؛
     * الفواتير بعد الأفق تُهمل؛ لا مبالغ ⇒ 0.0 (ليست null — صفر توقع مشروع).
     */
    fun collectionForecast(
        openInvoices: List<Pair<Long, Double>>,
        todayEpochDay: Long,
        horizonDays: Int = 14,
        probByBucket: List<Double> = listOf(0.95, 0.80, 0.60, 0.40),
    ): Double {
        if (openInvoices.isEmpty() || horizonDays <= 0) return 0.0
        var expected = 0.0
        for ((due, open) in openInvoices) {
            if (open <= 0.0) continue
            val dueDay = due
            if (dueDay > todayEpochDay + horizonDays) continue
            val overdue = todayEpochDay - due
            val idx = if (overdue < 0) 0 else bucketIndex(overdue)
            val p = probByBucket.getOrElse(idx) { 0.5 }
            expected += open * p
        }
        return round2(expected)
    }

    /**
     * A10 — اتجاه DSO أسبوعياً: dso = متوسط المفتوح ÷ مبيعات الأسبوع × 7.
     * عقد: أسبوع بمبيعات ≤0 يُحذف؛ أقل من نقطتين صالحتين ⇒ null؛
     * الاتجاه بميل خطي بسيط: ميل>0.01 ⇒ +1، <−0.01 ⇒ −1، وإلا 0.
     */
    data class DsoPoint(val week: Int, val dsoDays: Double)
    data class DsoTrendResult(val points: List<DsoPoint>, val direction: Int, val latest: Double?)

    fun dsoTrend(weeklyOpenVsSales: List<Pair<Double, Double>>): DsoTrendResult? {
        val pts = weeklyOpenVsSales.mapIndexedNotNull { i, (open, sales) ->
            if (sales > 0.0) DsoPoint(i, round2(open / sales * 7.0)) else null
        }
        if (pts.size < 2) return null
        // ميل بالأقل المربعات على (ترتيب النقطة، dso)
        val n = pts.size
        val xs = pts.map { it.week.toDouble() }
        val ys = pts.map { it.dsoDays }
        val mx = xs.average(); val my = ys.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        val slope = if (den > 0.0) num / den else 0.0
        val dir = when {
            slope > 0.01 -> 1
            slope < -0.01 -> -1
            else -> 0
        }
        return DsoTrendResult(pts, dir, pts.last().dsoDays)
    }
}

// ═══════════════ 5) BasketMath — سلة المشتريات ═══════════════

object BasketMath {

    /**
     * A11 — رفع السوق لأزواج المنتجات: support=|A∪B|/N، confidence=|A∪B|/|A|، lift=conf/(|B|/N).
     * عقد: يُستبعد ما دعمه < minSupport أو lift ≤ 1 (لا قيمة إرشادية)؛
     * الترتيب: lift تنازلي ثم (a,b) تصاعدي للحتمية.
     */
    data class LiftRow(val a: Long, val b: Long, val supportPct: Double, val confidencePct: Double, val lift: Double)

    fun marketBasketLift(baskets: List<List<Long>>, minSupport: Int = 2, topK: Int = 5): List<LiftRow> {
        val n = baskets.size
        if (n == 0) return emptyList()
        val single = HashMap<Long, Int>()
        val pairs = HashMap<Pair<Long, Long>, Int>()
        for (b in baskets) {
            val items = b.distinct().sorted()
            for (p in items) single[p] = (single[p] ?: 0) + 1
            for (i in items.indices) for (j in i + 1 until items.size) {
                val key = items[i] to items[j]
                pairs[key] = (pairs[key] ?: 0) + 1
            }
        }
        return pairs.mapNotNull { (pair, both) ->
            if (both < minSupport) return@mapNotNull null
            val (a, b) = pair
            val ca = single[a] ?: 0
            val cb = single[b] ?: 0
            if (ca == 0 || cb == 0) return@mapNotNull null
            val support = both.toDouble() / n * 100.0
            val confidence = both.toDouble() / ca * 100.0
            val lift = confidence / (cb.toDouble() / n * 100.0)
            if (lift <= 1.0) null
            else LiftRow(a, b, round2(support), round2(confidence), round2(lift))
        }.sortedWith(compareByDescending<LiftRow> { it.lift }.thenBy { it.a }.thenBy { it.b })
            .take(max(0, topK))
    }

    /**
     * A12 — «المنتج التالي» (ماركوف رتبة 1): داخل كل سلة، ما الذي يأتي بعد المنتج p؟
     * عقد: السلة متجهة بترتيب الإضافة؛ التكرارات المتتالية لنفس المنتج لا تُحسب؛
     * بلا تتابعات ⇒ فارغ.
     */
    data class NextRow(val nextId: Long, val count: Int)

    fun nextProduct(baskets: List<List<Long>>, productId: Long, topK: Int = 3): List<NextRow> {
        val counts = HashMap<Long, Int>()
        for (b in baskets) {
            var prev: Long? = null
            for (item in b) {
                if (prev == productId && item != productId) counts[item] = (counts[item] ?: 0) + 1
                prev = item
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .take(max(0, topK))
            .map { NextRow(it.key, it.value) }
    }
}

// ═══════════════ 6) RiskMath — درجة صحة وتركيز ومخاطر ═══════════════

object RiskMath {

    /**
     * A13 — درجة صحة 0..100 من 4 مكونات موزونة:
     *  السيولة 35%: نسبة (نقد+ذمم)÷مصروف شهري — 2.0⇒100، خطي حتى 0⇒0
     *  اتجاه الهامش 25%: +5%⇒100، 0⇒50، −5%⇒0 (خطي مقصوص)
     *  نسبة المديونية 25%: (ديون ÷ إيراد شهري) 0⇒100، 2.0⇒0 خطي
     *  نسبة المصروفات 15%: (مصروف ÷ إيراد) 0⇒100، 1.0⇒0 خطي
     * الحكم: ≥80 HEALTHY، ≥60 OK، ≥40 WATCH، وإلا RISK.
     */
    data class HealthScore(val score: Int, val band: String, val parts: List<Pair<String, Int>>)

    private fun clamp100(v: Double): Int = round(min(100.0, max(0.0, v))).toInt()

    fun healthScore(
        liquidityRatio: Double,
        marginTrendPct: Double,
        debtRatio: Double,
        expenseRatio: Double,
    ): HealthScore {
        val pLiq = clamp100(liquidityRatio / 2.0 * 100.0)
        val pMargin = clamp100(50.0 + marginTrendPct / 5.0 * 50.0)
        val pDebt = clamp100((1.0 - debtRatio / 2.0) * 100.0)
        val pExpense = clamp100((1.0 - expenseRatio) * 100.0)
        val score = round(pLiq * 0.35 + pMargin * 0.25 + pDebt * 0.25 + pExpense * 0.15).toInt()
        val band = when {
            score >= 80 -> "HEALTHY"
            score >= 60 -> "OK"
            score >= 40 -> "WATCH"
            else -> "RISK"
        }
        return HealthScore(score, band, listOf("liquidity" to pLiq, "margin" to pMargin, "debt" to pDebt, "expense" to pExpense))
    }

    /**
     * A14 — تركّز الإيراد: حصة أكبر topN عملاء + HHI.
     * عقد: مجموع ≤0 ⇒ null؛ الحكم كالعادة HHI≥2500 HIGH، ≥1500 MED، وإلا LOW.
     */
    data class Concentration(val topSharePct: Double, val hhi: Double, val verdict: String)

    fun customerConcentration(revenueByCustomer: Map<String, Double>, topN: Int = 3): Concentration? {
        val total = revenueByCustomer.values.sum()
        if (total <= 0.0) return null
        val shares = revenueByCustomer.values.map { it / total * 100.0 }
        val topShare = shares.sortedDescending().take(max(1, topN)).sum()
        val hhi = shares.sumOf { it * it }
        val verdict = when {
            hhi >= 2500.0 -> "HIGH"
            hhi >= 1500.0 -> "MED"
            else -> "LOW"
        }
        return Concentration(round2(topShare), round2(hhi), verdict)
    }

    /**
     * A15 — سلّم استحقاق الشيكات: تجميع أسبوعي لأفق أسابيع.
     * عقد: الشيكات المستحقة قبل اليوم تُجمَّع في الأسبوع 1 (متأخرة تحتاج إجراء فوري)؛
     * خارج الأفق تُهمل؛ بلا شيكات ⇒ فارغ.
     */
    data class WeekBucket(val week: Int, val amount: Double, val count: Int)

    fun maturityLadder(dueEpochDays: List<Long>, todayEpochDay: Long, weeks: Int = 8): List<WeekBucket> {
        if (dueEpochDays.isEmpty() || weeks <= 0) return emptyList()
        val amounts = DoubleArray(weeks)
        val counts = IntArray(weeks)
        for (due in dueEpochDays) {
            val diff = due - todayEpochDay
            val w = if (diff < 0) 0 else (diff / 7).toInt()   // [0..6]⇒أسبوع1(فهرس0)، [7..13]⇒أسبوع2
            if (w >= weeks) continue
            amounts[w] += 1.0
            counts[w] += 1
        }
        return (0 until weeks).filter { counts[it] > 0 }
            .map { WeekBucket(it + 1, round2(amounts[it]), counts[it]) }
    }
}

// ═══════════════ 7) GoalMath — أهداف ووتيرة ═══════════════

object GoalMath {

    /**
     * A16 — الوتيرة المطلوبة لإنقاذ هدف: remaining/daysLeft مع حكم جدوى مقابل السعة.
     * عقد: daysLeft≤0 ⇒ required=remaining (فوري) وfeasible=false؛ capacity≤0 ⇒ feasible=false.
     */
    data class Pace(val requiredPerDay: Double, val feasible: Boolean, val verdict: String)

    fun requiredPace(remaining: Double, daysLeft: Int, capacityPerDay: Double): Pace {
        if (remaining <= 0.0) return Pace(0.0, true, "DONE")
        val required = if (daysLeft <= 0) remaining else remaining / daysLeft
        val feasible = daysLeft > 0 && capacityPerDay > 0.0 && required <= capacityPerDay
        val verdict = when {
            remaining <= 0.0 -> "DONE"
            daysLeft <= 0 -> "EXPIRED"
            feasible -> "ON_TRACK"
            else -> "NEEDS_BOOST"
        }
        return Pace(round2(required), feasible, verdict)
    }

    /**
     * A17 — إسقاط المحطات: بعد كم يوم (من الآن) تتحقق 25/50/75/100% بالوتيرة الحالية.
     * عقد: pace≤0 ⇒ كل المحطات null؛ المحطة غير قابلة للتحقق (هدف صغير مكتمل جزئياً) ⇒ null لها فقط.
     */
    data class Milestones(val p25: Int?, val p50: Int?, val p75: Int?, val p100: Int?)

    fun milestoneProjection(done: Double, target: Double, pacePerDay: Double): Milestones {
        if (target <= 0.0 || pacePerDay <= 0.0) return Milestones(null, null, null, null)
        fun daysFor(fraction: Double): Int? {
            val need = target * fraction - done
            if (need <= 0.0) return null          // المحطة تجاوزت فعلياً
            return kotlin.math.ceil(need / pacePerDay).toInt()
        }
        return Milestones(daysFor(0.25), daysFor(0.50), daysFor(0.75), daysFor(1.0))
    }
}

// ═══════════════ 8) QualityMath — جودة بيانات ═══════════════

object QualityMath {

    /**
     * A18 — درجة تشابه 0..100 بين اسمين: 60% Jaro-Winkler المعياري + 40% تداخل رموز.
     * عقد: يعتمد TextMath.arabicNormalize؛ نص فارغ في أي طرف ⇒ 0.
     */
    fun dupScore(a: String, b: String): Int {
        val na = TextMath.arabicNormalize(a.trim())
        val nb = TextMath.arabicNormalize(b.trim())
        if (na.isEmpty() || nb.isEmpty()) return 0
        val jw = TextMath.jaroWinkler(na, nb)
        val ta = na.split(" ").filter { it.isNotBlank() }.toSet()
        val tb = nb.split(" ").filter { it.isNotBlank() }.toSet()
        val overlap = if (ta.isEmpty() || tb.isEmpty()) 0.0
        else ta.intersect(tb).size.toDouble() / maxOf(ta.size, tb.size)
        return round((jw * 60.0 + overlap * 40.0)).toInt().coerceIn(0, 100)
    }

    /**
     * A19 — شواذ IQR: أسوار Q1−k·IQR و Q3+k·IQR؛ k=1.5 خفيف، 3.0 ثقيل.
     * عقد: قيم <4 ⇒ null (لا معنى إحصائي)؛ IQR=0 ⇒ لا شواذ (كل القيم متطابقة).
     */
    data class IqrResult(val lowerFence: Double, val upperFence: Double, val outlierIndices: List<Int>)

    fun outliersIQR(values: List<Double>, k: Double = 1.5): IqrResult? {
        if (values.size < 4) return null
        val q1 = percentile(values, 25.0)
        val q3 = percentile(values, 75.0)
        val iqr = q3 - q1
        if (iqr <= 0.0) return IqrResult(round2(q1 - k * iqr), round2(q3 + k * iqr), emptyList())
        val lo = q1 - k * iqr
        val hi = q3 + k * iqr
        val idx = values.mapIndexedNotNull { i, v -> if (v < lo || v > hi) i else null }
        return IqrResult(round2(lo), round2(hi), idx)
    }

    /**
     * A20 — جرد نقدي جشع موثّق: أصغر عدد قطع بجشع الفئات (الفئات يجب أن تكون تنازلية؛
     * للفئات النقدية القياسية الجشع أمثل — موثق في العقد) + مجموع فروق التقريب لأقرب عملة صغرى.
     * عقد: المبالغ السالبة تُهمل؛ denominations تُرتَّب تنازلياً داخلياً.
     */
    data class Rounding(val coinCount: Int, val delta: Double)

    fun roundingSweep(
        cashAmounts: List<Double>,
        denominations: List<Double> = listOf(500.0, 100.0, 50.0, 10.0, 5.0, 1.0, 0.5, 0.25),
    ): Rounding {
        if (cashAmounts.isEmpty()) return Rounding(0, 0.0)
        val denoms = denominations.filter { it > 0.0 }.sortedDescending()
        var coins = 0
        var delta = 0.0
        for (amount in cashAmounts) {
            if (amount <= 0.0) continue
            var rem = round2(amount)
            for (d in denoms) {
                if (rem <= 0.0) break
                val c = (rem / d).toInt()
                if (c > 0) { coins += c; rem = round2(rem - c * d) }
            }
            delta += rem  // ما لم تغطّه الفئات (كسور أقل من أصغر عملة)
        }
        return Rounding(coins, round2(delta))
    }
}
