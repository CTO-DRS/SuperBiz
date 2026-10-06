package com.superbiz.app.domain.algo

/**
 * — مهايئات نقية تربط خوارزميات R9Smart ببيانات حقيقية.
 * كلها دوال نقية قابلة للاختبار الوحدوي مباشرة، بلا Android وبلا حالة.
*/
object R9Adapters {

    /**
     * من أزواج (استحقاق، سداد فعلي) بالملّي ثانية إلى مدخلات درجة السلوك:
     * (عدد في الوقت، عدد متأخر، متوسط أيام التأخير). الأزواج ذات سداد 0
     * (غير مسدد) تُستبعد. لا أزواج → (0, 0, 0.0).
     */
    fun paymentPairsToBehavior(pairs: List<Pair<Long, Long>>): Triple<Int, Int, Double> {
        if (pairs.isEmpty()) return Triple(0, 0, 0.0)
        var onTime = 0; var late = 0; var delaySum = 0.0
        val day = 86_400_000L
        for ((due, paid) in pairs) {
            if (paid <= 0) continue
            val diffDays = ((paid - due) / day).toInt()
            if (diffDays <= 0) onTime++ else { late++; delaySum += diffDays }
        }
        val avgDelay = if (late == 0) 0.0 else delaySum / late
        return Triple(onTime, late, avgDelay)
    }

    /**
     * اختبار رجعي لتنبؤ EWMA أحادي الخطوة: لكل يوم i≥1 التنبؤ =
     * EWMA حتى i-1 والفعلي = series[i]. يرجع (فعلي، متوقع) لمدخل
     * forecastAccuracy. أقل من نقطتين → null.
     */
    fun ewmaBacktest(series: List<Double>, alpha: Double = 0.35): Pair<List<Double>, List<Double>>? {
        if (series.size < 2) return null
        val actual = mutableListOf<Double>()
        val predicted = mutableListOf<Double>()
        var level = series.first()
        for (i in 1 until series.size) {
            actual.add(series[i])
            predicted.add(level)
            level = alpha * series[i] + (1 - alpha) * level
        }
        return actual to predicted
    }

    /**
     * من سلسلة كميات يومية (أيام بلا مبيعات = 0) إلى (متوسط، انحراف معياري)
     * لمخزون الأمان — الوجوب: أيام بلا مبيعات تُحسب صفراً لا تُستبعد.
     * قائمة فارغة → (0.0, 0.0).
     */
    fun dailyDemandStats(dailyQty: List<Double>): Pair<Double, Double> {
        if (dailyQty.isEmpty()) return 0.0 to 0.0
        val mean = dailyQty.average()
        val sd = if (dailyQty.size < 2) 0.0 else sqrt(dailyQty.sumOf { (it - mean) * (it - mean) } / (dailyQty.size - 1))
        return mean to sd
    }

    /**
     * تحويل حصص ساعات العمل الناتجة إلى نص ترتيب وديع — يعيد أزواج
     * (ساعة 0..23، حصة) مرتبة زمنياً بلا صفوف صفرية.
     */
    fun shiftsToSchedule(plan: List<Pair<Int, Double>>): List<Pair<Int, Double>> =
        plan.filter { it.second > 0 }.sortedBy { it.first }

    private fun sqrt(v: Double): Double = kotlin.math.sqrt(v)
}
