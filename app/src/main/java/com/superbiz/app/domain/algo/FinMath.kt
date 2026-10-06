package com.superbiz.app.domain.algo

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * — FinMath: خوارزميات مالية وتشغيلية نقية لإدارة الأعمال.
 *
 * كل الدوال نقية وبلا حالة وبلا Android — قابلة للاختبار على JVM، ومغطاة بالكامل في FinMathTest.
 * القاعدة العامة: المدخلات غير المنطقية (صفر/سالب في المقام أو النافذة) تعيد 0.0
 * أو قيمة حارسة موثقة بدل رمي استثناءات — مناسبة للاستهلاك المباشر من الـ ViewModels.
*/
object FinMath {

    // ───────── 1) التحصيل والسيولة ─────────

    /**
     * أيام التحصيل المتوسط (DSO) = days × receivables ÷ creditSales.
     * creditSales ≤ 0 أو days ≤ 0 → 0.0، والنتيجة مقيدة بـ ≥ 0.
     * مثال: 50,000 مستحق من 500,000 مبيعات آجلة على 365 يوماً → 36.5 يوماً.
     */
    fun dso(receivables: Double, creditSales: Double, days: Int): Double {
        if (creditSales <= 0.0 || days <= 0) return 0.0
        return (days * receivables / creditSales).coerceAtLeast(0.0)
    }

    /**
     * كفاءة التحصيل = 100 × collected ÷ invoiced مقيّدة ضمن 0..100.
     * invoiced ≤ 0 → 0.0 (لا فواتير = لا كفاءة قابلة للقياس).
     */
    fun collectionEfficiency(collected: Double, invoiced: Double): Double {
        if (invoiced <= 0.0) return 0.0
        return (100.0 * collected / invoiced).coerceIn(0.0, 100.0)
    }

    /**
     * معدل الحرق اليومي = متوسط آخر window قيمة من سلسلة الصافي اليومي
     * (الكل إذا كانت السلسلة أقصر). سالب = حرق نقود، موجب = فائض.
     * سلسلة فارغة أو window ≤ 0 → 0.0.
     */
    fun burnRate(dailyNetSeries: List<Double>, window: Int = 30): Double {
        if (dailyNetSeries.isEmpty() || window <= 0) return 0.0
        val from = maxOf(0, dailyNetSeries.size - window)
        return dailyNetSeries.subList(from, dailyNetSeries.size).average()
    }

    /**
     * أيام السيولة المتبقية = cashOnHand ÷ dailyBurn.
     * dailyBurn ≤ 0 → -1.0 (حارسة: سيولة غير محدودة — لا حرق).
     * cashOnHand سالبة → 0.0 (نفدت السيولة).
     */
    fun runwayDays(cashOnHand: Double, dailyBurn: Double): Double {
        if (dailyBurn <= 0.0) return -1.0
        if (cashOnHand < 0.0) return 0.0
        return cashOnHand / dailyBurn
    }

    // ───────── 2) المخزون والمشتريات ─────────

    /**
     * الكمية الاقتصادية للطلب (EOQ) = √(2 × الطلب السنوي × تكلفة الطلب ÷ تكلفة الاحتفاظ للوحدة).
     * أي مدخل من الثلاثة ≤ 0 → 0.0.
     * مثال: طلب 1000/سنة، طلبية 50، احتفاظ 2 → √50000 ≈ 223.607.
     */
    fun eoq(demandPerYear: Double, orderCost: Double, holdingCostPerUnit: Double): Double {
        if (demandPerYear <= 0.0 || orderCost <= 0.0 || holdingCostPerUnit <= 0.0) return 0.0
        return sqrt(2.0 * demandPerYear * orderCost / holdingCostPerUnit)
    }

    /**
     * مخزون الأمان = serviceLevelZ × σ الطلب اليومي × √مدة التوريد.
     * أي مدخل سالب يُعامل كصفر → الناتج 0.0 (الصفر نفسه يعطي 0.0 حسابياً).
     * z الافتراضي 1.65 ≈ مستوى خدمة 95%.
     */
    fun safetyStock(
        avgDailyDemand: Double,
        demandStdDev: Double,
        leadTimeDays: Double,
        serviceLevelZ: Double = 1.65,
    ): Double {
        if (avgDailyDemand < 0.0 || demandStdDev <= 0.0 || leadTimeDays <= 0.0 || serviceLevelZ <= 0.0) {
            return 0.0
        }
        return serviceLevelZ * demandStdDev * sqrt(leadTimeDays)
    }

    /**
     * تكلفة الوحدة بالمعدل المرجّح (WAC) =
     * (المخزون الافتتاحي × تكلفته + Σ كمية الشراء × تكلفته) ÷ (الافتتاحي + Σ كميات الشراء).
     * المقام الكلي ≤ 0 → 0.0.
     */
    fun wacUnitCost(
        openingQty: Double,
        openingUnitCost: Double,
        receipts: List<Pair<Double, Double>>,
    ): Double {
        val totalQty = openingQty + receipts.sumOf { it.first }
        if (totalQty <= 0.0) return 0.0
        val totalValue = openingQty * openingUnitCost + receipts.sumOf { it.first * it.second }
        return totalValue / totalQty
    }

    /**
     * قيمة المخزون المتبقي بطريقة FIFO: طبقات زمنية (كمية، تكلفة وحدة) تُستهلك من الأقدم،
     * والقيمة = Σ الكميات المتبقية × تكلفتها. qtyOnHand ≥ إجمالي الطبقات → 0.0 (نُفد المخزون)،
     * وqtyOnHand ≤ 0 → قيمة كل الطبقات (لم يُستهلك شيء).
     */
    fun fifoValue(layers: List<Pair<Double, Double>>, qtyOnHand: Double): Double {
        if (layers.isEmpty()) return 0.0
        if (qtyOnHand >= layers.sumOf { it.first }) return 0.0
        if (qtyOnHand <= 0.0) return layers.sumOf { it.first * it.second }
        var toConsume = qtyOnHand
        var remaining = 0.0
        for ((qty, unitCost) in layers) {
            if (toConsume >= qty) {
                toConsume -= qty
            } else {
                remaining += (qty - toConsume) * unitCost
                toConsume = 0.0
            }
        }
        return remaining
    }

    // ───────── 3) التسعير النفسي والضريبة ─────────

    /**
     * السعر النفسي التالي ≥ price بالكسر العشري المطلوب (افتراضياً 0.95):
     * إذا كان كسر السعر ≤ ending → floor + ending، وإلا → floor + 1 + ending.
     * price ≤ 0 → 0.0.
     * ملاحظة: عند حافة الكسر بالضبط (مثل 100.95) قد يجعل الفاصلة العائمة الكسر
     * أكبر بـ epsilon فيقفز للسعر التالي — سلوك حتمي موثق في الاختبارات.
     */
    fun charmPrice(price: Double, ending: Double = 0.95): Double {
        if (price <= 0.0) return 0.0
        val floored = floor(price)
        val fraction = price - floored
        return if (fraction <= ending) floored + ending else floored + 1.0 + ending
    }

    /**
     * تجميع الضريبة حسب النسبة: صفوف (صافي، النسبة ككسر) → لكل نسبة
     * (النسبة، مجموع الصافي، مجموع الضريبة = Σ net×rate) مرتبة تنازلياً حسب النسبة.
     * قائمة فارغة → قائمة فارغة.
     */
    fun vatByRate(rows: List<Pair<Double, Double>>): List<Triple<Double, Double, Double>> {
        if (rows.isEmpty()) return emptyList()
        val rates = LinkedHashSet<Double>()
        for ((_, rate) in rows) rates.add(rate)
        val netByRate = HashMap<Double, Double>()
        val vatByRate = HashMap<Double, Double>()
        for ((net, rate) in rows) {
            netByRate[rate] = (netByRate[rate] ?: 0.0) + net
            vatByRate[rate] = (vatByRate[rate] ?: 0.0) + net * rate
        }
        return rates.sortedDescending().map { rate ->
            Triple(rate, netByRate.getValue(rate), vatByRate.getValue(rate))
        }
    }
}
