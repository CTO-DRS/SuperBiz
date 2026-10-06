package com.superbiz.app.domain.algo

import com.superbiz.app.domain.analytics.linearRegression
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * — إحدى وعشرون خوارزمية ذكية جديدة لـ SuperBiz.
 *
 * عقد الصدق نفسه المعمول به في R7Smart
 * - دوال نقية بلا أي اعتماد على Android، حتمية وقابلة للاختبار مباشرة.
 * - مدخلات فارغة/منحلة → نتيجة null أو حيادية موثقة، بلا قسمة على صفر.
 * - لا ازدواج مع StatsMath/R7Smart: نعيد استخدام median/percentile/round2
 * وما شابه، وكل دالة هنا تغطي حاجة لا تغطيها موجة سابقة.
 *
 * القائمة
 * B1 holtForecast B2 smaCross B3 forecastAccuracy
 * B4 seasonalityStrength B5 billSweep B6 runwayUnderStress
 * B7 seasonalReserve B8 behaviorScore B9 creditLimit
 * B10 safetyStock B11 crostonIntermittent B12 batchAging
 * B13 categoryBalance B14 optimalPrice B15 bundlePrice
 * B16 profitAtRisk B17 cohortRetention B18 npsProxy (RetentionMath)
 * B19 nextBestAction B20 staffingPlan B21 eisenhower
*/

// ═══════════════════════════ النمو والتنبؤ ═══════════════════════════

object GrowthMath {

    /**
     * (B1) تنبؤ Holt الخطي (مستوى + اتجاه) لآفاق قادمة — مختلف عن forecast7d
     * (EWMA+موسمية): Holt يلتقط الاتجاه المتسارع في سلاسل قصيرة.
     * فارغة → فارغة؛ أقل من نقطتين → مسطح عند القيمة الوحيدة؛
     * الاتجاه الابتدائي = الفرق بين النقطتين الأوليين.
     */
    fun holtForecast(series: List<Double>, alpha: Double = 0.4, beta: Double = 0.2, horizon: Int = 3): List<Double> {
        if (series.isEmpty()) return emptyList()
        if (series.size == 1) return List(horizon.coerceAtLeast(1)) { round2(max(0.0, series.first())) }
        var level = series.first()
        var trend = series[1] - series[0]
        for (v in series.drop(1)) {
            val prevLevel = level
            level = alpha * v + (1 - alpha) * (level + trend)
            trend = beta * (level - prevLevel) + (1 - beta) * trend
        }
        return List(horizon.coerceAtLeast(1)) { i -> round2(max(0.0, level + trend * (i + 1))) }
    }

    /**
     * (B2) إشارة تقاطع المتوسطات: +1 ذهبي (القصير قطع الطويل صعوداً)،
     * −1 موت، 0 بلا تقاطع/بيانات ناقصة. القرار على آخر نقطتين فقط — حتمي.
     */
    fun smaCross(series: List<Double>, shortLen: Int = 7, longLen: Int = 21): Int {
        if (series.size < longLen + 2 || shortLen >= longLen) return 0
        fun smaAt(n: Int): Double = series.subList(n - longLen, n).average()
        fun smaShortAt(n: Int): Double = series.subList(n - shortLen, n).average()
        val prevShort = smaShortAt(series.size - 2); val prevLong = smaAt(series.size - 2)
        val lastShort = smaShortAt(series.size - 1); val lastLong = smaAt(series.size - 1)
        return when {
            prevShort <= prevLong && lastShort > lastLong -> 1
            prevShort >= prevLong && lastShort < lastLong -> -1
            else -> 0
        }
    }

    /** نتيجة دقة التنبؤ (B3): MAPE وانحياز ودرجة 1..4. */
    data class Accuracy(val mapePct: Double, val biasPct: Double, val grade: Int)

    /**
     * (B3) دقة تنبؤ سابقة: يقارن الفعلي بالمتوقع زوجاً زوجاً.
     * الأزواج ذات الفعلي = 0 تُستبعد (موثق). تقييم: 4 ممتاز MAPE<10،
     * 3 جيد <20، 2 مقبول <35، 1 ضعيف. بلا أزواج صالحة → null.
     */
    fun forecastAccuracy(actual: List<Double>, predicted: List<Double>): Accuracy? {
        if (actual.size != predicted.size) return null
        val pairs = actual.zip(predicted).filter { it.first > 0 }
        if (pairs.isEmpty()) return null
        val ape = pairs.map { abs(it.first - it.second) / it.first * 100.0 }
        val mape = ape.average()
        val bias = pairs.map { (it.second - it.first) / it.first * 100.0 }.average()
        val grade = when {
            mape < 10 -> 4; mape < 20 -> 3; mape < 35 -> 2; else -> 1
        }
        return Accuracy(round2(mape), round2(bias), grade)
    }

    /**
     * (B4) قوة الموسمية 0..1: نسبة تباين متوسطات الفترات (مثل أيام الأسبوع)
     * إلى التباين الكلي — تقول هل موسمية forecast7d موثوقة (>0.35 قوية تقريباً).
     * سلسلة قصيرة/بلا تباين → 0.0.
     */
    fun seasonalityStrength(series: List<Double>, period: Int = 7): Double {
        if (series.size < period * 2) return 0.0
        val totalVar = variance(series)
        if (totalVar <= 1e-12) return 0.0
        val buckets = List(period) { i -> series.filterIndexed { j, _ -> j % period == i } }
        val bucketMeans = buckets.map { it.average() }
        val betweenVar = variance(bucketMeans)
        return round2((betweenVar / totalVar).coerceIn(0.0, 1.0))
    }

    private fun variance(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return v.sumOf { (it - m) * (it - m) } / (v.size - 1)
    }
}

// ═══════════════════════════ النقد والسيولة ═══════════════════════════

/** فاتورة مفتوحة مرشحة للمسح (B5). */
data class Bill(val id: Long, val openAmount: Double)

/** نتيجة المسح الذكي (B5): ما يُسوّى الآن وما يؤجَّل. */
data class Sweep(val settledIds: List<Long>, val usedCash: Double, val remainingCash: Double, val deferredIds: List<Long>)

object CashMath {

    /**
     * (B5) مسح فواتير بمال متاح: تعظيم عدد الفواتير المسدَّدة كلياً.
     * ≤12 فاتورة → حل أمثل ببرمجة ديناميكية (subset-sum بعدد أقصى)؛
     * أكثر → جشع من الأصغر. المال غير الكافي لأي فاتورة → قائمة فارغة.
     * المبالغ غير الموجبة تُستبعد.
     */
    fun billSweep(cash: Double, bills: List<Bill>): Sweep {
        val valid = bills.filter { it.openAmount > 0 }.sortedBy { it.openAmount }
        if (cash <= 0 || valid.isEmpty()) return Sweep(emptyList(), 0.0, round2(cash.coerceAtLeast(0.0)), valid.map { it.id })
        val affordable = valid.filter { it.openAmount <= cash }
        if (affordable.isEmpty()) return Sweep(emptyList(), 0.0, round2(cash), valid.map { it.id })
        val chosen: List<Bill> = if (affordable.size <= 12) {
            // DP: خريطة المبالغ الممكنة → أفضل عدد فواتير، مع تتبع الأب
            var reach = mapOf(0.0 to Pair(0, emptyList<Bill>()))  // sum: Double -> (count, items)
            for (b in affordable) {
                val next = reach.toMutableMap()
                for ((s, pair) in reach) {
                    val ns: Double = s + b.openAmount
                    if (ns > cash) continue
                    val cur = next[ns]
                    if (cur == null || pair.first + 1 > cur.first) next[ns] = Pair(pair.first + 1, pair.second + b)
                }
                reach = next
            }
            val best = reach.entries.filter { it.key <= cash }.maxByOrNull { it.value.first }
            best?.value?.second ?: emptyList()
        } else {
            // جشع من الأصغر حتى نفاد المال
            val out = mutableListOf<Bill>()
            var left = cash
            for (b in affordable) { if (b.openAmount <= left) { out.add(b); left -= b.openAmount } }
            out
        }
        val used = chosen.sumOf { it.openAmount }
        val chosenIds = chosen.map { it.id }.toSet()
        return Sweep(chosen.map { it.id }, round2(used), round2(cash - used), valid.map { it.id }.filter { it !in chosenIds })
    }

    /** سيناريو سيولة (B6): أيام التغطية تحت سيناريو تدفق معين. */
    data class Scenario(val label: String, val inflowFactor: Double, val runwayDays: Int)

    /**
     * (B6) السيولة تحت الضغط: ثلاثة سيناريوهات — عادي (100% من التدفق
     * الوارد)، ضغط (70%)، أزمة (40%) — مقابل نفقات شهرية ثابتة.
     * نفقات ≤ 0 → سيناريوهات بأيام null-ish (كبيرة) موثقة 9999.
     */
    fun runwayUnderStress(cash: Double, monthlyInflow: Double, monthlyOutflow: Double): List<Scenario> {
        val labels = listOf("BASE" to 1.0, "STRESS" to 0.7, "SEVERE" to 0.4)
        return labels.map { (label, f) ->
            val netMonthly = monthlyInflow * f - monthlyOutflow
            val days = if (netMonthly >= 0) 9999 else ((cash / (-netMonthly)) * 30.0).roundToInt().coerceAtLeast(0)
            Scenario(label, f, days)
        }
    }

    /**
     * (B7) احتياطي نقدي موسمي: أعلى (وسيط أفضل 3 أشهر مصروفات، p90 للتاريخ)
     * × عدد أشهر الاحتياط — يغطي موسم الذروة لا المتوسط فقط.
     * بلا تاريخ → 0.0.
     */
    fun seasonalReserve(monthlyOutflows: List<Double>, reserveMonths: Int = 2): Double {
        val v = monthlyOutflows.filter { it >= 0 }
        if (v.isEmpty()) return 0.0
        val p90 = percentile(v, 90.0)   // StatsMath.percentile: p في 0..100
        val best3 = v.sortedDescending().take(3).average()
        return round2(max(p90, best3) * reserveMonths.coerceAtLeast(1))
    }
}

// ═══════════════════════════ الائتمان والعملاء ═══════════════════════════

object CreditMath {

    /**
     * (B8) درجة سلوك سداد 0..100: تبدأ 100، تُخصم 45×نسبة التأخير،
     * حتى 20×شدة متوسط الأيام المتأخرة (60 يوماً = الخصم الأقصى)،
     * و10 عن كل شيك مرتجع (سقف 30). حتمية وموثقة.
     */
    fun behaviorScore(onTimeCount: Int, lateCount: Int, bouncedCount: Int = 0, avgDelayDays: Double = 0.0): Int {
        val total = (onTimeCount + lateCount).coerceAtLeast(1)
        var score = 100.0
        score -= 45.0 * (lateCount.toDouble() / total)
        score -= 20.0 * (avgDelayDays.coerceIn(0.0, 60.0) / 60.0)
        score -= 10.0 * min(bouncedCount, 3)
        return score.coerceIn(0.0, 100.0).roundToInt()
    }

    /** اقتراح حد ائتمان (B9). */
    data class CreditLimit(val amount: Double, val tier: String)   // LOW / MEDIUM / HIGH

    /**
     * (B9) حد ائتمان مقترح: متوسط الطلب × سقف الطلبات (بحد أقصى 8)
     * × معامل ثقة (0.5 + 0.5×الدرجة/100). الفئة: HIGH ≥ 5×متوسط الطلب،
     * LOW ≤ 1.5×، وإلا MEDIUM. مدخلات غير صالحة → حد 0 درجة LOW.
     */
    fun creditLimit(avgOrder: Double, ordersCount: Int, behaviorScore: Int): CreditLimit {
        if (avgOrder <= 0 || ordersCount <= 0) return CreditLimit(0.0, "LOW")
        val trust = 0.5 + 0.5 * behaviorScore.coerceIn(0, 100) / 100.0
        val amount = round2(avgOrder * min(ordersCount, 8) * trust)
        val tier = when {
            amount >= avgOrder * 5.0 -> "HIGH"
            amount <= avgOrder * 1.5 -> "LOW"
            else -> "MEDIUM"
        }
        return CreditLimit(amount, tier)
    }
}

// ═══════════════════════════ العميل — إجراءات ومنحنيات ═══════════════════════════

object RetentionMath {

    /** منحنى احتفاظ الأفواج (B17). */
    data class Retention(val cohortSize: Int, val m1: Double, val m2: Double, val m3: Double)

    /**
     * (B17) الاحتفاظ بالأفواج: مدخل (عميل، فهرس شهر شراء) لكل عملية شراء.
     * الفوج = شهر أول شراء. الناتج: نسبة عملاء الفوج الأحدث الذي أكمل
     * شهراً على الأقل والذين اشتروا في الشهر 1/2/3 بعد الأول.
     * بلا فوج مؤهل → null.
     */
    fun cohortRetention(purchases: List<Pair<Long, Int>>, currentMonth: Int): Retention? {
        if (purchases.isEmpty()) return null
        val byCustomer = purchases.groupBy { it.first }
        val cohorts = byCustomer.mapValues { (_, ps) -> ps.map { it.second }.min() }
        val qualified = cohorts.filter { (_, first) -> currentMonth - first >= 1 }
        if (qualified.isEmpty()) return null
        val size = qualified.size
        fun repeatRate(offset: Int): Double {
            val eligible = qualified.filter { (_, first) -> currentMonth - first >= offset }
            if (eligible.isEmpty()) return 0.0
            val repeat = eligible.count { (cid, first) ->
                byCustomer[cid]!!.any { it.second == first + offset }
            }
            return round2(repeat.toDouble() / eligible.size)
        }
        return Retention(size, repeatRate(1), repeatRate(2), repeatRate(3))
    }

    /**
     * (B18) مؤشر ولاء مبني على السلوك (بديل NPS بلا استبيان):
     * (المتكررون − المرتجعون) ÷ الكل × 100، محصور −100..100.
     * بلا عملاء → 0.
     */
    fun npsProxy(repeatCustomers: Int, oneTimeCustomers: Int, refundedCustomers: Int): Int {
        val total = repeatCustomers + oneTimeCustomers
        if (total == 0) return 0
        return (((repeatCustomers - refundedCustomers).toDouble() / total) * 100.0)
            .coerceIn(-100.0, 100.0).roundToInt()
    }

    /**
     * (B19) الإجراء التالي الأفضل — جدول قرار حتمي يُترجم إلى نص محلي في الواجهة:
     * دين متأخر → COLLECT؛ churn مرتفع → WIN_BACK؛ عميل جديد → NURTURE؛
     * ولاء عالٍ → UPSELL؛ وإلا OK.
     */
    fun nextBestAction(segment: String, churn: Double, daysOverdue: Int): String {
        return when {
            daysOverdue > 0 -> "COLLECT"
            churn >= 0.6 -> "WIN_BACK"
            segment == "New" -> "NURTURE"
            segment == "Champions" || segment == "Loyal" -> "UPSELL"
            else -> "OK"
        }
    }
}

// ═══════════════════════════ المخزون ═══════════════════════════

object StockMath {

    /**
     * (B10) مخزون الأمان الكلاسيكي: z×√(مهلة×σd² + d̄²×σl²) — يدمج تذبذب
     * الطلب وتذبذب المهلة معاً (عنديات R7 تفترض مهلة ثابتة).
     * مدخلات سالبة → 0.0.
     */
    fun safetyStock(avgDaily: Double, dailySd: Double, leadDays: Double, leadSdDays: Double = 0.0, z: Double = 1.65): Double {
        if (avgDaily < 0 || dailySd < 0 || leadDays <= 0 || leadSdDays < 0) return 0.0
        val variance = leadDays * dailySd * dailySd + avgDaily * avgDaily * leadSdDays * leadSdDays
        return round2(z * sqrt(variance))
    }

    /** نتيجة كروستون (B11). */
    data class Croston(val forecastPerPeriod: Double, val avgInterval: Double, val avgSize: Double)

    /**
     * (B11) كروستون للطلب المتقطع: فصل أحجام الطلب عن فترات الصمت،
     * تنبؤ = متوسط الحجم ÷ متوسط الفترة. لا طلب إطلاقاً → تنبؤ 0.0.
     * ملاحظة صادقة: النسخة الأساسية (لا SBA) — الانحياز مقبول للتقارير.
     */
    fun crostonIntermittent(demand: List<Double>): Croston {
        val sizes = demand.filter { it > 0 }
        if (sizes.isEmpty()) return Croston(0.0, 0.0, 0.0)
        var intervals = mutableListOf<Double>()
        var since = -1
        demand.forEachIndexed { i, v ->
            if (v > 0) { if (since >= 0) intervals.add((i - since).toDouble()); since = i }
        }
        val avgInterval = if (intervals.isEmpty()) Double.NaN else intervals.average()
        val avgSize = sizes.average()
        // [P20-FIX agent11]: حدث طلب واحد ⇒ الفترات فارغة ولا يُقدَّر إيقاع — كان avgInterval=1.0
        // يعطي تنبؤاً بحجم الحدث الكامل يومياً (تضخيم ×30 لمنتج باع مرة في شهر) ويتربّع أول قائمة
        // «الحيوان البطيء» مرتبة تنازلياً. الصدق: تنبؤ يومي 0.0 مع إبقاء avgSize للعرض
        if (intervals.isEmpty()) return Croston(0.0, 0.0, round2(avgSize))
        return Croston(round2(avgSize / max(avgInterval, 1.0)), round2(avgInterval), round2(avgSize))
    }

    /** دفعة مخزون (B12). */
    data class Batch(val id: Long, val qty: Double, val unitCost: Double, val receivedEpochDay: Int)

    /** صف تقادم (B12). */
    data class AgingRow(val batchId: Long, val ageDays: Int, val qty: Double, val capital: Double, val band: String) // OK / RISK / CRITICAL

    /** تقرير تقادم الدفعات (B12). */
    data class AgingReport(val rows: List<AgingRow>, val capitalAtRisk: Double)

    /**
     * (B12) تقادم دفعات المخزون (FIFO واعية بالعمر): ≥riskDays → CRITICAL،
     * ≥warnDays → RISK، وإلا OK. رأس المال المعرض = الدفعات غير OK.
     * ترتيب: الأقدم أولاً.
     */
    fun batchAging(batches: List<Batch>, todayEpochDay: Int, warnDays: Int = 30, riskDays: Int = 90): AgingReport {
        val rows = batches.filter { it.qty > 0 }.map { b ->
            val age = (todayEpochDay - b.receivedEpochDay).coerceAtLeast(0)
            val band = when {
                age >= riskDays -> "CRITICAL"
                age >= warnDays -> "RISK"
                else -> "OK"
            }
            AgingRow(b.id, age, b.qty, round2(b.qty * b.unitCost), band)
        }.sortedByDescending { it.ageDays }
        return AgingReport(rows, round2(rows.filter { it.band != "OK" }.sumOf { it.capital }))
    }

    /** صف توازن فئة (B13). */
    data class CatRow(val category: String, val stockSharePct: Double, val salesSharePct: Double, val verdict: String) // OVER / UNDER / OK

    /**
     * (B13) توازن المخزون مع المبيعات لكل فئة: مقارنة حصة المخزون بحصة
     * المبيعات؛ فارق >8 نقاط مئوية → OVER (مخزون زائد) أو UNDER (نقص
     * تغطية)؛ وإلا OK. فئات بلا مبيعات إطلاقاً لكن بمخزون → OVER.
     */
    fun categoryBalance(stockValueByCat: Map<String, Double>, salesValueByCat: Map<String, Double>, tolerancePp: Double = 8.0): List<CatRow> {
        val cats = (stockValueByCat.keys + salesValueByCat.keys).filter { it.isNotBlank() }.distinct()
        val stockTotal = stockValueByCat.values.filter { it > 0 }.sum()
        val salesTotal = salesValueByCat.values.filter { it > 0 }.sum()
        if (stockTotal <= 0 || salesTotal <= 0) return emptyList()
        return cats.map { c ->
            val ss = (stockValueByCat[c] ?: 0.0) / stockTotal * 100.0
            val ps = (salesValueByCat[c] ?: 0.0) / salesTotal * 100.0
            val diff = ss - ps
            val verdict = when {
                diff > tolerancePp -> "OVER"
                diff < -tolerancePp -> "UNDER"
                else -> "OK"
            }
            CatRow(c, round2(ss), round2(ps), verdict)
        }.sortedByDescending { abs(it.stockSharePct - it.salesSharePct) }
    }
}

// ═══════════════════════════ الإيراد والتسعير ═══════════════════════════

object RevenueMath {

    /**
     * (B14) السعر الأمثل للإيراد من منحنى طلب خطي مُركَّب q = a + b·p
     * (انحدار خطي حقيقي على نقاط سعر/كمية فعلية): p* = −a ÷ 2b.
     * يشترط b سالباً و≤8 نقاط صالحة وp* داخل ±40% من نطاق الملاحظ — وإلا null.
     */
    fun optimalPrice(priceQty: List<Pair<Double, Double>>): Double? {
        val pts = priceQty.filter { it.first > 0 && it.second > 0 }
        if (pts.size < 4) return null
        val prices = pts.map { it.first }
        val qtys = pts.map { it.second }
        val (b, a, _) = linearRegression(prices, qtys)
        if (b >= 0 || a.isNaN() || b.isNaN()) return null
        val pStar = -a / (2 * b)
        if (pStar <= 0 || pStar.isNaN()) return null
        val lo = prices.min() * 0.6
        val hi = prices.max() * 1.4
        return if (pStar in lo..hi) round2(pStar) else null
    }

    /**
     * (B15) سعر حزمة عادلة: تكلفة الحزمة ÷ (1 − الهامش المستهدف)،
     * مع سقف «صفقة حقيقية» = 95% من مجموع الأسعار المنفردة.
     * هامش غير قابل للتحقيق أو مدخلات منحلة → null.
     */
    fun bundlePrice(unitCosts: List<Double>, unitPrices: List<Double>, targetMarginPct: Double): Double? {
        if (unitCosts.size != unitPrices.size || unitCosts.isEmpty()) return null
        if (unitCosts.any { it < 0 } || unitPrices.any { it <= 0 }) return null
        if (targetMarginPct !in 0.0..0.95) return null
        val cost = unitCosts.sum()
        val listSum = unitPrices.sum()
        val raw = cost / (1 - targetMarginPct)
        val capped = min(raw, listSum * 0.95)
        if (capped <= cost) return null
        return round2(capped)
    }

    /**
     * (B16) الربح المعرض للخطر (VaR تاريخي): خسارة يومية عند مستوى ثقة،
     * بالمنهج الرتيب (nearest-rank) على أرباح يومية فعلية. أقل من 10
     * نقاط → null. الناتج موجب = حجم الخسارة اليومية المتوقعة في أسوأ
     * [1−ثقة] من الأيام.
     */
    fun profitAtRisk(dailyProfits: List<Double>, confidence: Double = 0.95): Double? {
        if (dailyProfits.size < 10) return null
        if (confidence !in 0.5..0.999) return null
        val sorted = dailyProfits.sorted()
        val idx = (ceil((1 - confidence) * sorted.size).toInt()).coerceIn(0, sorted.size - 1)
        val varValue = -sorted[idx]   // موجب عند الخسارة
        return round2(varValue.coerceAtLeast(0.0))
    }
}

// ═══════════════════════════ العمليات ═══════════════════════════

/** مهمة (B21). */
data class Task(val id: Long, val urgencyDaysLeft: Int, val importanceHigh: Boolean, val effortHours: Double = 1.0)

/** مهمة مصنفة (B21). */
data class RankedTask(val id: Long, val quadrant: String, val rank: Int) // DO_FIRST / SCHEDULE / DELEGATE / ELIMINATE

object OpsMath {

    /**
     * (B20) خطة ساعات العمل: توزيع ساعات الفريق على نوافذ المبيعات
     * بنسبة حصتها، مع تجاهل الساعات <5% من الذروة (تشويش).
     * ساعات غير صالحة → قائمة فارغة. الناتج: ساعة → حصة الساعات.
     */
    fun staffingPlan(hourTotals: DoubleArray, staffHours: Int): List<Pair<Int, Double>> {
        if (hourTotals.isEmpty() || staffHours <= 0) return emptyList()
        val total = hourTotals.sum()
        if (total <= 0) return emptyList()
        val peak = hourTotals.max()
        val eligible = hourTotals.withIndex().filter { it.value >= peak * 0.05 }
        if (eligible.isEmpty()) return emptyList()
        val eTotal = eligible.sumOf { it.value }
        return eligible.sortedByDescending { it.value }.map { (h, v) ->
            h to round2(v / eTotal * staffHours)
        }
    }

    /**
     * (B21) مصنف المهام (أيزنهاور): عاجل = تبقّى ≤3 أيام أو متأخر،
     * مهم = تأثير مالي مرتفع. الترتيب: DO_FIRST ثم SCHEDULE ثم
     * DELEGATE ثم ELIMINATE، وداخل الفئة بالعاجلة أولاً ثم الجهد الأقل.
     */
    fun eisenhower(tasks: List<Task>): List<RankedTask> {
        if (tasks.isEmpty()) return emptyList()
        fun quadrant(t: Task): String = when {
            t.urgencyDaysLeft <= 3 && t.importanceHigh -> "DO_FIRST"
            t.urgencyDaysLeft > 3 && t.importanceHigh -> "SCHEDULE"
            t.urgencyDaysLeft <= 3 && !t.importanceHigh -> "DELEGATE"
            else -> "ELIMINATE"
        }
        val order = mapOf("DO_FIRST" to 0, "SCHEDULE" to 1, "DELEGATE" to 2, "ELIMINATE" to 3)
        return tasks.sortedWith(
            compareBy({ order[quadrant(it)]!! }, { it.urgencyDaysLeft }, { it.effortHours })
        ).mapIndexed { i, t -> RankedTask(t.id, quadrant(t), i + 1) }
    }
}
