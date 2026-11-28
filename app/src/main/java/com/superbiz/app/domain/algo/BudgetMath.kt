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

    /**
     * توقع نهاية السنة لفئة: monthlyBudget × 12 هو الميزانية السنوية؛ المتوقع = الفعلي منذ بداية السنة
     * ÷ الأشهر المنقضية × 12. monthsElapsed بين 1 و12 (يُقيَّد). نقي.
     */
    data class Annual(val category: String, val annualBudget: Long, val ytdActual: Long, val projected: Long) {
        val projectedOver: Boolean get() = annualBudget > 0L && projected > annualBudget
        val projectedGap: Long get() = annualBudget - projected
    }

    fun annualProjection(monthlyBudgets: Map<String, Long>, ytdActual: Map<String, Long>, monthsElapsed: Int): List<Annual> {
        val m = monthsElapsed.coerceIn(1, 12).toLong()
        val cats = (monthlyBudgets.keys + ytdActual.keys).filter { it.isNotBlank() }.toSortedSet()
        return cats.map {
            val a = ytdActual[it] ?: 0L
            Annual(it, (monthlyBudgets[it] ?: 0L) * 12L, a, a * 12L / m)
        }.sortedWith(compareByDescending<Annual> { it.projectedOver }.thenBy { it.category })
    }
}
