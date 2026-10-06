package com.superbiz.app.domain.algo

import com.superbiz.app.domain.analytics.linearRegression
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * — عشرون خوارزمية ذكية جديدة لـ SuperBiz.
 * كل الدوال نقية (بلا Android) وقابلة للاختبار الوحدوي مباشرة، وكلها
 * تحافظ على عقد الصدق: مدخلات فارغة/منحلة → نتيجة null أو حيادية موثقة،
 * بلا قسمة على صفر، وبلا مؤثرات جانبية.
*/

// ——————————————————————————————————————————— الطلب والمخزون ———————————————————————————————————————————

/** عناصر كاشف الراكد (A4). */
data class StockItem(val productId: Long, val qty: Double, val unitCost: Double, val lastSaleEpochDay: Int)

/** صف راكد: أيام الخمول ورأس المال المعطل. */
data class DeadRow(val productId: Long, val qty: Double, val capital: Double, val idleDays: Int)

/** تقرير الراكد: صفوف مرتبة برأس المال تنازلياً + الإجمالي. */
data class DeadReport(val rows: List<DeadRow>, val tiedCapital: Double)

/** عنصر خطة إعادة الطلب (A5). */
data class PlanItem(
    val productId: Long,
    val onHand: Double,
    val avgDaily: Double,
    val demandCv: Double,      // تباين الطلب اليومي (std/mean) — 0 لو ثابت
    val unitCost: Double,
    val minOrderQty: Double = 0.0,
)

/** صف خطة الشراء: نقطة إعادة الطلب والكمية المقترحة وأولوية التغطية. */
data class PlanRow(val productId: Long, val reorderPoint: Double, val orderQty: Double, val daysCover: Double)

object DemandMath {

    /**
     * (A1) تنبؤ طلب 7 أيام قادمة من سلسلة مبيعات يومية (الأقدم أولاً).
     * القاعدة = EWMA(α=0.35)، الاتجاه = متوسط فرق النصفين مقسوماً على الطول،
     * وموسمية يوم-الأسبوع تُستخرج من السلسلة نفسها (موقع البداية مستمر مع طولها).
     * سلسلة فارغة → قائمة فارغة؛ أقل من 7 نقاط → توقع مسطح عند القاعدة.
     */
    fun forecast7d(daily: List<Double>): List<Double> {
        if (daily.isEmpty()) return emptyList()
        val alpha = 0.35
        var base = daily.first()
        for (v in daily) base = alpha * v + (1 - alpha) * base
        if (daily.size < 7) return List(7) { round2(max(0.0, base)) }
        val half = daily.size / 2
        val trend = (daily.takeLast(half).average() - daily.take(half).average()) / half
        val mean = daily.average().takeIf { it > 0 } ?: return List(7) { round2(max(0.0, base + trend)) }
        val start = daily.size % 7
        val factors = List(7) { i ->
            val idx = (start + i) % 7
            val bucket = daily.filterIndexed { j, _ -> j % 7 == idx }
            val f = if (bucket.isEmpty()) 1.0 else (bucket.average() / mean)
            f.coerceIn(0.4, 1.8)
        }
        return List(7) { i -> round2(max(0.0, (base + trend * (i + 1)) * factors[i])) }
    }

    /**
     * (A2) أيام حتى النفاد المتوقع. يضخّم الطلب الفعّال بـ25% من معامل التباين
     * احتياطاً ضد التذبذب. لا طلب (قاعدة≈0) → null (لا نفاد متوقع).
     */
    fun stockoutEta(stockQty: Double, daily: List<Double>): Double? {
        if (daily.isEmpty() || stockQty <= 0) return null
        val alpha = 0.35
        var base = daily.first()
        for (v in daily) base = alpha * v + (1 - alpha) * base
        if (base <= 0) return null
        val mean = daily.average().takeIf { it > 0 } ?: base
        val cv = if (mean > 0) (daily.stdDev() / mean) else 0.0
        val effective = base * (1.0 + 0.25 * cv.coerceIn(0.0, 1.5))
        return round2(max(0.5, stockQty / effective))
    }

    /**
     * (A3) احتمال النفاد قبل انتهاء مهلة التوريد (0..1).
     * z = (المخزون − طلب المهلة) / تذبذب طلب المهلة، ثم 1−Φ(z) بتقريب طبيعي.
     */
    fun stockoutRisk(stockQty: Double, leadTimeDays: Double, daily: List<Double>): Double {
        if (daily.isEmpty() || leadTimeDays <= 0) return 0.0
        val alpha = 0.35
        var base = daily.first()
        for (v in daily) base = alpha * v + (1 - alpha) * base
        if (base <= 0) return 0.0
        val mean = daily.average().takeIf { it > 0 } ?: base
        val cv = if (mean > 0) (daily.stdDev() / mean).coerceIn(0.0, 1.5) else 0.0
        val leadMean = base * leadTimeDays
        // [P6-M19 إصلاح]: كان تذبذب طلب المهلة leadSd = leadMean·cv·√L أي σd·L^1.5 —
        // عامل L زائد يضخّم σ ويقلّص احتمال النفاد عند المخزون المنخفض (عكس غرض المؤشر).
        // التباين الصحيح للطلب التراكمي عبر مهلة ثابتة (بافتراض استقلال الأيام): σd·√L
        // حيث σd التذبذب اليومي = المتوسط × cv (بسقف cv الموثق 0..1.5).
        val sigmaDaily = mean * cv
        val leadSd = max(sigmaDaily * sqrt(leadTimeDays), 1e-9)
        val z = (stockQty - leadMean) / leadSd
        return normalCdfUpper(z).coerceIn(0.0, 1.0)
    }

    /**
     * (A4) كاشف المخزون الراكد: أصناف بكمية موجبة لم تُبَع منذ [thresholdDays].
     * يرجع الصفوف مرتبة برأس المال المعطل تنازلياً + الإجمالي.
     */
    fun deadStock(items: List<StockItem>, todayEpochDay: Int, thresholdDays: Int = 30): DeadReport {
        val rows = items.asSequence()
            .filter { it.qty > 0 && (todayEpochDay - it.lastSaleEpochDay) >= thresholdDays }
            .map { DeadRow(it.productId, it.qty, round2(it.qty * it.unitCost), todayEpochDay - it.lastSaleEpochDay) }
            .sortedByDescending { it.capital }
            .toList()
        return DeadReport(rows, round2(rows.sumOf { it.capital }))
    }

    /**
     * (A5) خطة إعادة طلب كاملة. لكل صنف: نقطة إعادة = طلب المهلة + أمان(z)؛
     * يُشترى فقط من تجاوز نقطة الإعادة (أو تغطيته أقل من نصف المهلة)؛
     * الكمية = تغطية [coverDays] − الموجود + الأمان (لا تقل عن minOrderQty).
     * الترتيب: الأقل تغطية أولاً (الأخطر). أصناف بلا طلب تُستبعد.
     */
    fun reorderPlan(items: List<PlanItem>, leadTimeDays: Double, serviceZ: Double = 1.65, coverDays: Double = 14.0): List<PlanRow> {
        if (leadTimeDays <= 0) return emptyList()
        val rows = items.asSequence().filter { it.avgDaily > 0 }.mapNotNull { it ->
            val safety = serviceZ * it.avgDaily * it.demandCv.coerceAtLeast(0.0) * sqrt(leadTimeDays)
            val rop = it.avgDaily * leadTimeDays + safety
            val cover = it.onHand / it.avgDaily
            val needs = it.onHand <= rop || cover < leadTimeDays / 2.0
            if (!needs) return@mapNotNull null
            val target = coverDays * it.avgDaily + safety
            val qty = max(ceil(target - it.onHand), it.minOrderQty.coerceAtLeast(0.0))
            PlanRow(it.productId, round2(rop), qty.roundToInt().toDouble(), round2(cover))
        }.sortedBy { it.daysCover }.toList()
        return rows
    }
}

// ——————————————————————————————————————————— التسعير ———————————————————————————————————————————

object PriceMath {

    /**
     * (A6) مرونة سعرية تقديرية من تاريخ (أسعار، كميات) عبر انحدار لوغاريتمي لوغاريتمي.
     * تشترط ≥4 نقاط وتشتت سعر حقيقي؛ وإلا null. القيمة المتوقعة سالبة.
     */
    fun elasticityProxy(priceSeries: List<Double>, qtySeries: List<Double>): Double? {
        if (priceSeries.size != qtySeries.size || priceSeries.size < 4) return null
        if (priceSeries.any { it <= 0 } || qtySeries.any { it <= 0 }) return null
        if (priceSeries.stdDev() <= 1e-9) return null
        val (slope, _, _) = linearRegression(priceSeries.map { ln(it) }, qtySeries.map { ln(it) })
        return if (slope.isNaN()) null else round2(slope)
    }

    /**
     * (A7) سعر مقترح لتحقيق تغيّر حجم مطلوب: %Δp = %Δq ÷ المرونة.
     * القيد: لا ينزل عن [floorPrice] وإلا null (الهدف غير قابل للتحقيق بأمان).
     */
    fun suggestPrice(currentPrice: Double, elasticity: Double, wantedVolumeChangePct: Double, floorPrice: Double): Double? {
        if (currentPrice <= 0 || elasticity >= 0 || floorPrice <= 0) return null
        val pctPriceChange = wantedVolumeChangePct / elasticity   // مرونة سالبة → تخفيض لزيادة الحجم
        val p = currentPrice * (1.0 + pctPriceChange)
        if (p < floorPrice) return null
        return round2(p.coerceAtMost(currentPrice * 2.0))
    }

    /**
     * (A8) أقصى نسبة خصم (٪) تحفظ هامش [targetMarginPct] بعد ضريبة [vatPct].
     * السعر الأدنى المسموح = تكلفة×(1+vat)÷(1−هامش). الناتج محصور [0..100]،
     * والصفر إذا كان السعر الحالي لا يسمح أصلاً بأي خصم.
     */
    fun maxSafeDiscountPct(price: Double, unitCost: Double, targetMarginPct: Double, vatPct: Double = 0.0): Double {
        if (price <= 0 || unitCost < 0 || targetMarginPct !in 0.0..0.99 || vatPct < 0) return 0.0
        val minPrice = unitCost * (1 + vatPct) / (1 - targetMarginPct)
        if (minPrice >= price) return 0.0
        return ((1.0 - minPrice / price) * 100.0).coerceIn(0.0, 100.0).let { round2(it) }
    }

    /** درجة تسعير حجمي (A9). */
    data class Tier(val minQty: Double, val discountPct: Double, val unitPrice: Double)

    /**
     * (A9) تدرّج كميات يحفظ هامش الهدف: كل درجة تأخذ جزءاً متزايداً من
     * مساحة الخصم الآمنة (25%، 40%، 55%، 70% من السقف) بمقادير مقربة لنصف وحدة.
     */
    fun volumeTiers(price: Double, unitCost: Double, targetMarginPct: Double, qtyThresholds: List<Double> = listOf(5.0, 10.0, 20.0, 50.0), vatPct: Double = 0.0): List<Tier> {
        val safe = maxSafeDiscountPct(price, unitCost, targetMarginPct, vatPct)
        if (safe <= 0) return emptyList()
        val shares = listOf(0.25, 0.40, 0.55, 0.70)
        return qtyThresholds.mapIndexed { i, q ->
            val d = (safe * shares[i]).let { (it * 2).roundToInt() / 2.0 }   // لأقرب 0.5
            Tier(q, d, round2(price * (1 - d / 100.0)))
        }
    }

    /** نتيجة نقطة التعادل (A10). */
    data class BreakEven(val contributionPerUnit: Double, val units: Double, val revenue: Double, val marginOfSafetyPct: Double?)

    /**
     * (A10) نقطة التعادل: وحدات = ثابت ÷ هامش المساهمة. بلا مساهمة
     * (سعر ≤ متغير) → وحدات NaN مع contribution ≤ 0. هامش الأمان يُحسب
     * فقط إذا مُرّر إيراد متوقع.
     */
    fun breakEven(fixedMonthly: Double, price: Double, variablePerUnit: Double, expectedMonthlyRevenue: Double? = null): BreakEven {
        val cm = price - variablePerUnit
        val units = if (cm > 0) fixedMonthly / cm else Double.NaN
        val revenue = if (cm > 0) units * price else Double.NaN
        val mos = expectedMonthlyRevenue?.takeIf { it > 0 && cm > 0 }?.let { (it - revenue) / it * 100.0 }?.let { round2(it) }
        // [P9-T1 إصلاح]: كان round2(NaN)=0.0 يخفي عقد «لا مساهمة ⇒ وحدات NaN» فيبدو التعادل محققاً فوراً —
        // تُحفظ NaN للوحدات والإيراد حرفياً (المساهمة السالبة رقم صالح وتُقرّب كالمعتاد)
        return BreakEven(
            round2(cm),
            if (units.isNaN()) Double.NaN else round2(units),
            if (revenue.isNaN()) Double.NaN else round2(revenue),
            mos
        )
    }
}

// ——————————————————————————————————————————— العملاء ———————————————————————————————————————————

object CustomerMath {

    /** (A11) قيمة عمرية صافية: متوسط الطلب × تكرار شهري × أشهر العلاقة × هامش. */
    fun ltv(avgOrderValue: Double, ordersPerMonth: Double, monthsActive: Double, marginPct: Double = 1.0): Double {
        if (avgOrderValue <= 0 || ordersPerMonth <= 0 || monthsActive <= 0) return 0.0
        return round2(avgOrderValue * ordersPerMonth * monthsActive * marginPct.coerceIn(0.0, 1.0))
    }

    /**
     * (A12) مخاطرة فقد العميل 0..1: مقارنة الصمت الحالي بوسيط دورة الشراء
     * الخاصة به عبر منحنى لوجستي؛ الولاء الطويل (≥10 طلبات) يخمد الخطر 10%.
     * بلا تاريخ (medianGap≤0) → 0.5 حياد موثق.
     */
    fun churnRisk(daysSinceLast: Int, medianGapDays: Double, purchasesCount: Int): Double {
        if (medianGapDays <= 0) return 0.5
        val ratio = daysSinceLast / medianGapDays
        var risk = 1.0 / (1.0 + exp(-(ratio - 1.25) * 3.0))
        if (purchasesCount >= 10) risk *= 0.9
        return round2(risk.coerceIn(0.0, 1.0))
    }

    /** نتيجة RFM (A13). */
    data class Rfm(val r: Int, val f: Int, val m: Int, val segment: String)

    /**
     * (A13) RFM مركّب 1..5 لكل بُعد بمسارات نسبية (مقارنة بعوامل المستخدم نفسه)،
     * ثم شريحة: Champions/Loyal/New/AtRisk/Sleeping/Potential.
     */
    fun rfm(recencyDays: Int, frequency: Int, monetary: Double, recencyAnchor: Int = 30, freqAnchor: Int = 5, monetaryAnchor: Double): Rfm {
        val r = scoreByRatio(recencyDays / recencyAnchor.coerceAtLeast(1).toDouble(), lowerIsBetter = true)
        val f = scoreByRatio(frequency / freqAnchor.coerceAtLeast(1).toDouble(), lowerIsBetter = false)
        val m = scoreByRatio(monetary / monetaryAnchor.coerceAtLeast(1e-9), lowerIsBetter = false)
        val segment = when {
            r >= 4 && f >= 4 -> "Champions"
            f >= 4 && r >= 3 -> "Loyal"      // الولاء يتطلب حداثة معقولة — وإلا فهو AtRisk
            r >= 4 && f <= 2 -> "New"
            r <= 2 && f >= 3 -> "AtRisk"
            r <= 2 && f <= 2 -> "Sleeping"
            else -> "Potential"
        }
        return Rfm(r, f, m, segment)
    }

    private fun scoreByRatio(x: Double, lowerIsBetter: Boolean): Int {
        val bands = doubleArrayOf(0.3, 0.6, 1.0, 2.0)
        val idx = bands.indexOfFirst { x <= it }.let { if (it == -1) 4 else it }
        return if (lowerIsBetter) 5 - idx else idx + 1
    }

    /** توقع الشراء القادم (A14). */
    data class Eta(val expectedEpochDay: Int, val daysFromToday: Int, val windowDays: Int)

    /**
     * (A14) موعد الشراء المتوقع: آخر شراء + وسيط الدورة (نافذة ±25% بحد أدنى يومين).
     * متأخر عن الموعد → توقع من اليوم + 15% من الدورة (بحد أدنى يوم) — سلوك
     * «صمتٌ غيّر النموذج» موثق بدل التنبؤ بتاريخ ماضٍ.
     */
    fun nextPurchaseEta(lastPurchaseEpochDay: Int, medianGapDays: Double, todayEpochDay: Int): Eta {
        val gap = max(medianGapDays, 1.0)
        val window = max(2.0, gap * 0.25).roundToInt()
        val naive = lastPurchaseEpochDay + gap.roundToInt()
        return if (naive >= todayEpochDay) {
            Eta(naive, naive - todayEpochDay, window)
        } else {
            val d = todayEpochDay + max(1, (gap * 0.15).roundToInt())
            Eta(d, d - todayEpochDay, window)
        }
    }
}

// ——————————————————————————————————————————— التحصيل والنقد ———————————————————————————————————————————

/** عنصر أولوية التحصيل (A15). */
data class CollectItem(val partyId: Long, val amount: Double, val daysOverdue: Int, val reliability: Double, val score: Double = 0.0)

object CollectionMath {

    /**
     * (A15) درجة أولوية تحصيل 0..1 لكل طرف:
     * الوزن النسبي للمبلغ 45% + شدة التأخير 35% + انعكاس الموثوقية 20%.
     * تُعاد القائمة مرتبة تنازلياً بالدرجة (قائمة اتصال جاهزة).
     */
    fun priority(items: List<CollectItem>): List<CollectItem> {
        if (items.isEmpty()) return emptyList()
        val maxAmt = items.maxOf { it.amount }.takeIf { it > 0 } ?: return items.map { it.copy(score = 0.0) }
        return items.map { it ->
            val amtN = (it.amount / maxAmt).coerceIn(0.0, 1.0)
            val lateN = (it.daysOverdue.coerceAtLeast(0) / 120.0).coerceIn(0.0, 1.0)
            val rel = it.reliability.coerceIn(0.0, 1.0)
            it.copy(score = round2(0.45 * amtN + 0.35 * lateN + 0.20 * (1 - rel)))
        }.sortedByDescending { it.score }
    }

    /** نتيجة فجوة النقد (A16). */
    data class Gap(val firstNegativeWeek: Int?, val lowestBalance: Double, val lowestWeek: Int)

    /**
     * (A16) محاكاة رصيد أسبوعي: الرصيد يبدأ من [cashNow] ويُسقط أول أسبوع
     * يصير فيه سالباً + أعمق قاع. لا سالب → firstNegativeWeek=null.
     */
    fun cashGap(cashNow: Double, weeklyInflows: List<Double>, weeklyOutflows: List<Double>): Gap {
        var bal = cashNow
        var lowest = cashNow
        var lowestWeek = 0
        var firstNeg: Int? = null
        val n = max(weeklyInflows.size, weeklyOutflows.size)
        for (w in 0 until n) {
            bal += (weeklyInflows.getOrNull(w) ?: 0.0) - (weeklyOutflows.getOrNull(w) ?: 0.0)
            if (bal < lowest) { lowest = bal; lowestWeek = w + 1 }
            if (bal < 0 && firstNeg == null) firstNeg = w + 1
        }
        return Gap(firstNeg, round2(lowest), lowestWeek)
    }

    /**
     * (A17) مخاطرة ارتجاع شيك 0..1: 60% تاريخ الارتجاع + 40% انحراف المبلغ
     * عن وسيط شيكات المُصدر (منحنى لوجستي حول 2× الوسيط).
     */
    fun checkBounceRisk(bouncedRatio: Double, amountToMedianFactor: Double): Double {
        val br = bouncedRatio.coerceIn(0.0, 1.0)
        val amt = 1.0 / (1.0 + exp(-(amountToMedianFactor - 2.0) * 1.5))
        return round2((0.6 * br + 0.4 * amt).coerceIn(0.0, 1.0))
    }
}

// ——————————————————————————————————————————— الرؤى الذكية ———————————————————————————————————————————

/** نتيجة قفزة مصروفات (A18). */
data class Jump(val ratio: Double, val isJump: Boolean, val baselineMedian: Double)

/** زوج «يُشترى معاً» (A19). */
data class Bundle(val a: Long, val b: Long, val count: Int, val lift: Double)

/** نافذة ذروة (A20-1). */
data class Window(val startHour: Int, val endHour: Int, val sharePct: Double)

object InsightMath {

    /**
     * (A18) قفزة مصروفات فئة: الحالي مقابل وسيط التاريخ؛ عتبة القفز
     * = أكبر من 35% أو 1.2× معامل التباين (أقل عتبة عند التاريخ الهادئ).
     */
    fun expenseJump(currentMonth: Double, history: List<Double>): Jump {
        if (history.isEmpty()) return Jump(0.0, false, 0.0)
        val med = history.median()
        val cv = if (med > 0) history.stdDev() / med else 0.0
        val threshold = 1.0 + max(0.35, 1.2 * cv)
        val ratio = if (med > 0) currentMonth / med else 0.0
        return Jump(round2(ratio), med > 0 && currentMonth > med * threshold, round2(med))
    }

    /**
     * (A19) أزواج منتجات يُشترى معاً من سلّات فعلية: الرفع lift =
     * P(معاً)÷(P(a)·P(b))؛ تُقبل فقط lift>1.1 وعدد ≥ minCount، مرتبة بالعدد.
     */
    fun bundlePairs(baskets: List<List<Long>>, minCount: Int = 2, topK: Int = 5): List<Bundle> {
        if (baskets.size < 2) return emptyList()
        val n = baskets.size
        val contain = HashMap<Long, Int>()
        val pairs = HashMap<Pair<Long, Long>, Int>()
        for (b in baskets) {
            val uniq = b.toSortedSet()
            for (p in uniq) contain[p] = (contain[p] ?: 0) + 1
            val list = uniq.toList()
            for (i in list.indices) for (j in i + 1 until list.size) {
                val key = if (list[i] < list[j]) list[i] to list[j] else list[j] to list[i]
                pairs[key] = (pairs[key] ?: 0) + 1
            }
        }
        return pairs.mapNotNull { (k, c) ->
            if (c < minCount) return@mapNotNull null
            val pa = (contain[k.first] ?: 0).toDouble() / n
            val pb = (contain[k.second] ?: 0).toDouble() / n
            if (pa <= 0 || pb <= 0) return@mapNotNull null
            val lift = (c.toDouble() / n) / (pa * pb)
            if (lift <= 1.1) return@mapNotNull null
            Bundle(k.first, k.second, c, round2(lift))
        }.sortedByDescending { it.count }.take(topK.coerceAtLeast(1))
    }

    /**
     * (A20-1) نافذتا ذروة من مبيعات 24 ساعة: النافذة = ذروة ±ساعة،
     * والثانية أعلى قمة محلية منفصلة ≥60% من الأولى (خارج نافذتها).
     * مصفوفة فارغة/كلها صفر → قائمة فارغة.
     */
    fun peakWindows(hourTotals: DoubleArray): List<Window> {
        if (hourTotals.isEmpty() || hourTotals.all { it <= 0 }) return emptyList()
        val total = hourTotals.sum()
        val maxV = hourTotals.max()
        fun windowAt(i: Int) = Window((i + 23) % 24, (i + 1) % 24, round2(hourTotals[i] / total * 100.0))
        val peak1 = hourTotals.indices.maxBy { hourTotals[it] }
        val out = mutableListOf(windowAt(peak1))
        var peak2 = -1
        var best = maxV * 0.6
        for (i in hourTotals.indices) {
            val inW1 = abs(i - peak1) <= 1 || abs(i - peak1) >= 23
            if (!inW1 && hourTotals[i] >= best) { best = hourTotals[i]; peak2 = i }
        }
        if (peak2 >= 0) out.add(windowAt(peak2))
        return out
    }

    /** نتيجة استقرار الأرباح (A20-2). */
    data class Stability(val cv: Double, val level: Int, val negativeShare: Double)

    /**
     * (A20-2) استقرار الأرباح الأسبوعية: مستوى 4 (ثابت cv<0.2) … 1 (مضطرب)،
     * مع هبوط فوري للمستوى 1 إذا تجاوزت الأسابيع الخاسرة 25%.
     * أقل من 4 أسابيع → مستوى 0 (بيانات غير كافية).
     */
    fun profitStability(weeklyProfits: List<Double>): Stability {
        if (weeklyProfits.size < 4) return Stability(0.0, 0, 0.0)
        val mean = weeklyProfits.average()
        val cv = if (mean != 0.0) abs(weeklyProfits.stdDev() / mean) else 0.0
        val negShare = weeklyProfits.count { it < 0 }.toDouble() / weeklyProfits.size
        val level = when {
            negShare > 0.25 -> 1
            cv < 0.2 -> 4
            cv < 0.45 -> 3
            cv < 0.8 -> 2
            else -> 1
        }
        return Stability(round2(cv), level, round2(negShare))
    }

    /** نتيجة محاكاة الهدف (A20-3). */
    data class GoalSim(val etaDays: Int?, val willFinishInTime: Boolean, val requiredPacePerDay: Double)

    /**
     * (A20-3) محاكاة الهدف: بأيام بالوتيرة الحالية (null بلا وتيرة)،
     * وهل ينتهي داخل المهلة، ووتيرة اللازم يومياً للالتزام بالمهلة.
     */
    fun goalSim(pacePerDay: Double, targetRemaining: Double, daysLeft: Int): GoalSim {
        if (daysLeft <= 0 || targetRemaining <= 0) return GoalSim(null, true, 0.0)
        val eta = if (pacePerDay > 0) ceil(targetRemaining / pacePerDay).toInt() else null
        val required = targetRemaining / daysLeft
        return GoalSim(eta, (eta != null && eta <= daysLeft), round2(required))
    }

    /** مساهمة مكوّن في تغير درجة الصحة (A20-4). */
    data class Contribution(val component: String, val delta: Double)

    /**
     * (A20-4) تفكيك تغيّر درجة الصحة: مساهمة كل مكوّن = وزنه النسبي ×
     * تغيّره (0..1) × 100. تُرتب بحسب حجم الأثر المطلق.
     */
    fun healthDecomposition(prev: Map<String, Double?>, now: Map<String, Double?>, weights: Map<String, Double>): List<Contribution> {
        val wSum = weights.values.filter { it > 0 }.sum().takeIf { it > 0 } ?: return emptyList()
        return weights.mapNotNull { (k, w) ->
            val p = prev[k] ?: return@mapNotNull null
            val c = now[k] ?: return@mapNotNull null
            Contribution(k, round2((c - p) * (w / wSum) * 100.0))
        }.sortedByDescending { abs(it.delta) }
    }
}

// ——————————————————————————————————————————— أدوات داخلية ———————————————————————————————————————————

private fun List<Double>.stdDev(): Double {
    if (size < 2) return 0.0
    val m = average()
    return sqrt(sumOf { (it - m) * (it - m) } / (size - 1))
}

private fun List<Double>.median(): Double {
    if (isEmpty()) return 0.0
    val s = sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
}

/** Φ(z) العلوية: احتمال تجاوز z في التوزيع الطبيعي (تقريب Zelen-Severo). */
private fun normalCdfUpper(z: Double): Double {
    val t = 1.0 / (1.0 + 0.2316419 * abs(z))
    val d = 0.3989423 * exp(-z * z / 2.0)
    val p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
    return if (z >= 0) p else 1.0 - p
}
// round2 مُعاد استخدامها من MoneyMath (نفس الحزمة domain.algo) — لا ازدواج.
