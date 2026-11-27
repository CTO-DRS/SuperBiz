package com.superbiz.app.domain.algo

/**
 * [D4] ميزانية تقديرية مقابل فعلي لكل فئة مصروف (قروش Long). نقي (JVM).
 * usedPct = الفعلي ÷ الميزانية × 100 (null إن لم توجد ميزانية). الفئات بلا ميزانية تظهر بالفعلي فقط.
 */
object BudgetMath {
    data class Row(val category: String, val budget: Long, val actual: Long) {
        val remaining: Long get() = budget - actual
        val hasBudget: Boolean get() = budget > 0L
        val over: Boolean get() = hasBudget && actual > budget
        /** تنبيه مبكر: بلغ 80% من الهدف أو أكثر (يشمل المتجاوز) */
        val warn: Boolean get() = hasBudget && actual * 100L >= budget * 80L
        val usedPct: Int? get() = if (hasBudget) ((actual * 100L) / budget).toInt() else null
    }

    data class Report(val rows: List<Row>, val totalBudget: Long, val totalActual: Long) {
        val totalBudgetedActual: Long get() = rows.filter { it.hasBudget }.sumOf { it.actual }
        val overCount: Int get() = rows.count { it.over }
        val warnCount: Int get() = rows.count { it.warn }
    }

    fun compare(budgets: Map<String, Long>, actual: Map<String, Long>): Report {
        val cats = (budgets.keys + actual.keys).filter { it.isNotBlank() }.toSortedSet()
        val rows = cats.map { Row(it, budgets[it] ?: 0L, actual[it] ?: 0L) }
            // المتجاوزة أولاً ثم الأعلى استهلاكاً ثم الاسم
            .sortedWith(compareByDescending<Row> { it.over }.thenByDescending { it.usedPct ?: -1 }.thenBy { it.category })
        return Report(rows, rows.sumOf { it.budget }, rows.sumOf { it.actual })
    }
}
