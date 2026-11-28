package com.superbiz.app.domain.algo

/**
 * قائمة التدفق النقدي الشهرية (طريقة مباشرة) من حركات حساب النقدية في الدفتر. نقي (JVM).
 * كل مبلغ قروش Long. التواريخ epoch millis؛ الشهر بحسب [tzOffsetMillis] (توقيت الجهاز).
 * المدخل: debit على النقدية = قبض، credit = صرف. الرصيد الافتتاحي = مجموع كل ما قبل أول شهر معروض.
 */
object CashFlowStatement {
    data class Cash(val date: Long, val debit: Long, val credit: Long)
    data class Month(val year: Int, val month: Int, val opening: Long, val inflow: Long, val outflow: Long) {
        val net: Long get() = inflow - outflow
        val closing: Long get() = opening + net
    }

    private fun ym(date: Long, tz: Long): Int {
        val d = java.time.LocalDate.ofEpochDay(Math.floorDiv(date + tz, 86_400_000L))
        return d.year * 12 + (d.monthValue - 1)
    }

    /** آخر [months] شهراً تنتهي بالشهر الذي فيه [now] (شاملاً الأشهر الفارغة). الأقدم أولاً. */
    fun build(rows: List<Cash>, now: Long, months: Int = 6, tzOffsetMillis: Long = 0L): List<Month> {
        require(months in 1..36)
        val last = ym(now, tzOffsetMillis)
        val first = last - (months - 1)
        var opening = rows.filter { ym(it.date, tzOffsetMillis) < first }.sumOf { it.debit - it.credit }
        val byMonth = rows.groupBy { ym(it.date, tzOffsetMillis) }
        val out = ArrayList<Month>()
        for (k in first..last) {
            val r = byMonth[k].orEmpty()
            val m = Month(k / 12, k % 12 + 1, opening, r.sumOf { it.debit }, r.sumOf { it.credit })
            out.add(m)
            opening = m.closing
        }
        return out
    }
}
