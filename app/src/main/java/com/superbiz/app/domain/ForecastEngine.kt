package com.superbiz.app.domain

import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money

/** فاتورة مفتوحة (غير مسددة بالكامل) لغرض الخطر والتوقع */
data class OpenInvoice(
    val invoiceId: Long,
    val partyId: Long,
    val amountOpen: Double,
    val issueDate: Long,
    val dueDate: Long,
    val isSale: Boolean
)

data class RiskResult(
    val score: Int,          // 0..100
    val level: Int,          // 0 منخفض 1 متوسط 2 مرتفع
    val worstOverdueDays: Int,
    val factors: List<String>
)

/**
 * محرك تقييم الخطر — حتمي بالكامل (بلا أي ذكاء اصطناعي):
 * 30 نقطة نسبة الاستخدام + 40 نقطة التأخير + 20 نقطة عمر الدين + 10 نقاط انتظام السداد
 */
object RiskEngine {

    const val LEVEL_LOW = 0
    const val LEVEL_MEDIUM = 1
    const val LEVEL_HIGH = 2

    fun score(
        open: List<OpenInvoice>,
        totalInvoiced: Double,
        totalPaid: Double,
        avgPayGapDays: Double?,
        today: Long
    ): RiskResult {
        val day = 86_400_000.0
        var score = 0.0
        val factors = mutableListOf<String>()

        val openTotal = open.sumOf { it.amountOpen }
        // 1) نسبة الاستخدام: الدين المفتوح مقارنة بإجمالي التعامل
        val utilization = if (totalInvoiced > 0) (openTotal / totalInvoiced).coerceIn(0.0, 1.0) else 0.0
        val uPts = utilization * 30
        score += uPts
        factors += "utilization=${"%.0f".format(uPts)}"

        // 2) أسوأ تأخير عن الاستحقاق
        // [V1-B2 إصلاح] daysOverdue معرفة في Dates وليس Money (خطأ ترجمة كامن من P6-M6)
        // [P6-M6 إصلاح]: توحيد دلالة «متأخر» — كان ‎((today-due)/day).toInt()‎ بتراً للأرض (floor)
        // بينما Dates.daysOverdue وagingBuckets (M-2.5) بالتقريب لأعلى: تأخير بساعة كان 0 يوماً
        // هنا ويوماً واحداً هناك. الآن عبر Dates.daysOverdue نفسه — أي تجاوز = يوم واحد على الأقل
        val worstOverdue = open.filter { it.dueDate < today }
            .maxOfOrNull { Dates.daysOverdue(it.dueDate, today) } ?: 0
        val oPts = minOf(40.0, worstOverdue * 0.8)
        score += oPts
        factors += "overdue=${"%.0f".format(oPts)}"

        // 3) العمر المرجّح للديون المفتوحة (على أساس 120 يوم = كامل النقاط)
        val weightedAge = if (openTotal > 0)
            open.sumOf { inv ->
                val age = ((today - inv.issueDate) / day).coerceAtLeast(0.0)
                age * (inv.amountOpen / openTotal)
            } else 0.0
        val aPts = (weightedAge / 120.0).coerceIn(0.0, 1.0) * 20
        score += aPts
        factors += "age=${"%.0f".format(aPts)}"

        // 4) انتظام السداد: متوسط الفجوة بين الاستحقاق والسداد
        val dPts = when {
            open.isEmpty() -> 0.0
            totalPaid <= 0 -> 10.0
            avgPayGapDays == null -> 2.0
            else -> (avgPayGapDays.coerceAtLeast(0.0) / 45.0).coerceIn(0.0, 1.0) * 10
        }
        score += dPts
        factors += "discipline=${"%.0f".format(dPts)}"

        val final = score.toInt().coerceIn(0, 100)
        val level = when {
            final < 35 -> LEVEL_LOW
            final < 65 -> LEVEL_MEDIUM
            else -> LEVEL_HIGH
        }
        return RiskResult(final, level, worstOverdue, factors)
    }
}

data class ForecastResult(
    val next7: Double,
    val next30: Double,
    val dailyBuckets: List<Pair<Long, Double>>, // (يوم، متوقع)
    val fillRate: Double,                        // نسبة التحصيل التاريخية 0..1
    val trendPerWeek: Double                     // ميل الانحدار الخطي أسبوعياً
)

/**
 * محرك توقع التحصيل — إحصاء حتمي:
 * 1) نسبة التحصيل التاريخية (fill rate) من المدفوعات مقابل الفوترة
 * 2) توزيع الفواتير المفتوحة على أيام الاستحقاق (+ متوسط التأخير التاريخي)
 * 3) انحدار خطي على مقبوضات آخر 56 يوماً لاتجاه عام
 */
object ForecastEngine {

    fun forecast(
        openSalesInvoices: List<OpenInvoice>,
        receipts: List<Pair<Long, Double>>, // (تاريخ، مبلغ) آخر 8 أسابيع
        invoicedTotal: Double,
        paidTotal: Double,
        today: Long
    ): ForecastResult {
        val day = 86_400_000.0

        // نسبة التحصيل التاريخية
        val fillRate = if (invoicedTotal > 0)
            (paidTotal / invoicedTotal).coerceIn(0.05, 1.0) else 0.6

        // متوسط التأخير التاريخي في السداد مقارنة بتاريخ الفاتورة (تقريب: 15% من العمر)
        val avgDelayDays = if (receipts.isNotEmpty()) {
            receipts.map { (d, _) -> ((today - d) / day).coerceAtLeast(0.0) }.average()
        } else 0.0
        val delayFactor = (avgDelayDays * 0.15).coerceIn(0.0, 30.0)

        // توزيع المتوقع على الأيام القادمة 30 يوماً
        val buckets = mutableMapOf<Long, Double>()
        val horizon = 30
        for (inv in openSalesInvoices) {
            val dueOffset = ((inv.dueDate - today) / day)
            val dayOffset = when {
                dueOffset < 0 -> 0.0                       // متأخرة: تُحصَّل قريباً بنسبة أخف
                dueOffset > horizon -> horizon.toDouble()  // بعيدة: آخر الأفق
                else -> dueOffset
            }
            val adj = (dayOffset + delayFactor).coerceIn(0.0, horizon.toDouble())
            val idx = adj.toInt().coerceAtMost(horizon - 1)
            // المتأخرة تُحصَّل بنسبة أقل (fillRate × 0.6)
            val amount = inv.amountOpen * fillRate * if (dueOffset < 0) 0.6 else 1.0
            val bucketDay = today + (idx * day).toLong()
            buckets[bucketDay] = (buckets[bucketDay] ?: 0.0) + amount
        }
        val daily = (0 until horizon).map { i ->
            val d = today + (i * day).toLong()
            d to Money.round2(buckets[d] ?: 0.0) // [P6-M3 إصلاح]: التقريب الكانوني بدل الدالة الخاصة
        }
        val next7 = Money.round2(daily.take(7).sumOf { it.second })
        val next30 = Money.round2(daily.sumOf { it.second })

        // انحدار خطي بسيط على المقبوضات الأسبوعية (آخر 8 أسابيع)
        val trend = linearTrendPerWeek(receipts, today)

        return ForecastResult(next7, next30, daily, Money.round2(fillRate), trend)
    }

    /** ميل الانحدار الخطي (للأسبوع) على مجموعات 7 أيام */
    fun linearTrendPerWeek(receipts: List<Pair<Long, Double>>, today: Long): Double {
        if (receipts.isEmpty()) return 0.0
        val day = 86_400_000.0
        val weeks = Array(8) { 0.0 }
        for ((d, amt) in receipts) {
            val w = ((today - d) / (7 * day)).toInt()
            if (w in 0..7) weeks[w] += amt
        }
        // x = 0 (الأقدم) .. 7 (الأحدث)؛ weeks[0] هو الأحدث لذا نعكسه
        val xs = weeks.indices.map { it.toDouble() }
        val ys = weeks.reversed()
        val n = xs.size
        val mx = xs.sum() / n
        val my = ys.sum() / n
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { num += (xs[i] - mx) * (ys[i] - my); den += (xs[i] - mx) * (xs[i] - mx) }
        // [P6-M3 إصلاح]: التقريب الكانوني بدل الدالة الخاصة (تغيير دلالة فقط عند أنصاف الهللات:
        // ‎-0.125‎ كانت ‎-0.12‎ نحو الصفر وصارت ‎-0.13‎ بعيداً عنه — HALF_UP القانوني)
        return if (den > 0) Money.round2(num / den) else 0.0
    }
}
