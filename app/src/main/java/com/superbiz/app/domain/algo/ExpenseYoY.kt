package com.superbiz.app.domain.algo

/**
 * مقارنة المصروفات سنة بسنة لكل فئة (من بداية السنة حتى اليوم نفسه في السنتين). نقي (JVM)، قروش Long.
 * changePct = (الحالي − السابق) ÷ السابق × 100 مقرّباً للأقرب؛ null إن لم يكن للسابق مصروف (لا نسبة معرّفة).
 */
object ExpenseYoY {
    data class Row(val category: String, val current: Long, val previous: Long) {
        val delta: Long get() = current - previous
        val changePct: Int? get() = if (previous > 0L) Math.round(delta * 100.0 / previous).toInt() else null
    }

    data class Report(val rows: List<Row>) {
        val totalCurrent: Long get() = rows.sumOf { it.current }
        val totalPrevious: Long get() = rows.sumOf { it.previous }
    }

    /** الأكبر زيادة مطلقة أولاً ثم الاسم؛ فئات بلا أي مبلغ في السنتين تُحذف. */
    fun compare(current: Map<String, Long>, previous: Map<String, Long>): Report {
        val cats = (current.keys + previous.keys).filter { it.isNotBlank() }.toSortedSet()
        val rows = cats.map { Row(it, current[it] ?: 0L, previous[it] ?: 0L) }
            .filter { it.current != 0L || it.previous != 0L }
            .sortedWith(compareByDescending<Row> { it.delta }.thenBy { it.category })
        return Report(rows)
    }
}
